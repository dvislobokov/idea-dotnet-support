package io.github.dotnetsupport.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.ui.ConflictsDialog
import com.intellij.util.containers.MultiMap
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys
import io.github.dotnetsupport.lang.semantic.CSharpInjections
import io.github.dotnetsupport.lang.semantic.CSharpSearchTarget
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch
import org.jetbrains.annotations.TestOnly

/**
 * Rename of the types and members of the solution without the language server (CSHARP_PSI_MIGRATION.md, task C4b; feature `RENAME`): every
 * declaration (the parts of a partial type, its constructors and finalizer), every usage the search of [CSharpSolutionSearch] finds (`nameof`,
 * doc comment `cref` and the short name of an attribute among them), the members that override or implement the member and the ones it
 * overrides or implements when the user says so (Rider asks the same), the file named after a type, what the frameworks name it by
 * ([CSharpRenameParticipant]: the tags and attributes of Razor's markup, the `.razor` file of a component). A usage in C# injected into
 * another file is written in its host; what a source generator made is not renamed (it is made again). Conflicts — another member or type
 * of the new name in the same place — are shown as the platform shows them.
 */
object NativeCSharpSolutionRename {
    /** What is renamed: the symbols (the member and, if asked, its hierarchy) and every range per file, the declarations included. */
    class Plan(
        val symbols: List<CSharpSearchTarget>, val ranges: Map<PsiFile, List<TextRange>>, val oldName: String, val typeFiles: List<PsiFile>,
        /** What the frameworks insert beside the rename ([CSharpRenameParticipant]): `[FormerlySerializedAs("old")]` of Unity. */
        val insertions: List<CSharpRenameParticipant.Insertion> = emptyList(),
    ) {
        val primary: CSharpSearchTarget get() = symbols.first()
    }

    /** Whether the members of the hierarchy are renamed together: asked in a dialog; tests set the answer. */
    @Volatile
    private var hierarchyAnswerForTests: Boolean? = null

    @TestOnly
    fun answerHierarchyForTests(answer: Boolean?) {
        hierarchyAnswerForTests = answer
    }

    /** Whether the last search for conflicts ran on the EDT (for the tests: it must not, the stub index is a slow operation there). */
    @Volatile
    @get:TestOnly
    var conflictsOnEdtForTests: Boolean? = null
        private set

    /** The other members of [target]'s hierarchy: those it overrides or implements (of the solution), and everything that overrides those. */
    fun family(project: Project, target: CSharpSearchTarget): List<CSharpSearchTarget> {
        val whole = CSharpSolutionSearch.withHierarchy(project, target, CSharpSemanticSession(project))
        return whole.declarations.filter { it !in target.declarations }.mapNotNull(CSharpSolutionSearch::targetOf)
    }

    /** Asks whether to rename the hierarchy too; null: cancelled. */
    fun askHierarchy(project: Project, target: CSharpSearchTarget, others: List<CSharpSearchTarget>): Boolean? {
        if (others.isEmpty()) return false
        hierarchyAnswerForTests?.let { return it }
        if (ApplicationManager.getApplication().isUnitTestMode) return true
        val list = others.take(8).joinToString("\n") { "  " + describe(it) } + if (others.size > 8) "\n  …" else ""
        return when (Messages.showYesNoCancelDialog(project, "'${target.name}' is part of a hierarchy of members:\n$list\n\nRename them all?", "Rename", "Rename All", "Only This", "Cancel", Messages.getQuestionIcon())) {
            Messages.YES -> true
            Messages.NO -> false
            else -> null
        }
    }

    private fun describe(target: CSharpSearchTarget): String {
        val element = target.primary ?: return target.name
        val owner = CSharpSolutionSearch.ownerType(element)?.let(CSharpDeclarationNames::name)
        return if (owner != null) "$owner.${target.name}" else target.name
    }

    /** Every range to rename for [symbols]: in a read action, under the progress of the caller. */
    fun plan(project: Project, symbols: List<CSharpSearchTarget>): Plan {
        val ranges = LinkedHashMap<PsiFile, MutableSet<TextRange>>()
        fun add(element: PsiElement?) {
            val file = element?.containingFile as? CSharpFile ?: return
            if (isGenerated(file)) return
            ranges.getOrPut(file) { LinkedHashSet() } += element.textRange
        }
        val session = CSharpSemanticSession(project)
        val typeFiles = ArrayList<PsiFile>()
        val primary = symbols.first()
        for (symbol in symbols) {
            for (declaration in symbol.declarations) {
                add(nameOf(declaration))
                if (symbol.isType && declaration is CSharpTypeDeclaration) for (member in declaration.members) {
                    when (member) {
                        is CSharpConstructorDeclaration -> add(member.identifier)
                        is CSharpDestructorDeclaration -> add(member.identifier)
                    }
                }
            }
            CSharpSolutionSearch.processUsages(project, symbol, GlobalSearchScope.projectScope(project), session) { usage ->
                // `base(…)` / `new()` / `foreach` / a deconstruction call a member without its name: nothing to rename there
                if (!usage.implicit && CSharpLeaves.isIdentifier(usage.leaf)) add(usage.leaf)
                true
            }
        }
        for (symbol in symbols) for (declaration in symbol.declarations) {
            for (occurrence in CSharpRenameParticipant.occurrences(declaration, symbol.name)) ranges.getOrPut(occurrence.file) { LinkedHashSet() } += occurrence.range
        }
        if (primary.isType) {
            for (declaration in primary.declarations) {
                val file = declaration.containingFile as? CSharpFile ?: continue
                if (!isGenerated(file) && file.viewProvider.virtualFile.nameWithoutExtension == primary.name && file !in typeFiles) typeFiles += file
                for (named in CSharpRenameParticipant.namedFiles(declaration, primary.name)) if (named !in typeFiles) typeFiles += named
            }
        }
        val insertions = symbols.flatMap { symbol -> symbol.declarations.flatMap { CSharpRenameParticipant.insertions(it, symbol.name) } }
            .distinctBy { Triple(it.file, it.offset, it.text) }
        return Plan(symbols, ranges.mapValues { (_, set) -> set.sortedBy { it.startOffset } }, primary.name, typeFiles, insertions)
    }

    /** What a source generator made (its output folder): made again from the renamed sources, never edited. */
    fun isGenerated(file: PsiFile): Boolean {
        val virtualFile = file.viewProvider.virtualFile
        return file.project.getServiceIfCreated(io.github.dotnetsupport.codeanalysis.CodeAnalysisService::class.java)?.isGenerated(virtualFile) == true
    }

    private fun nameOf(declaration: PsiElement): PsiElement? = when (declaration) {
        is CSharpVariableDeclarator -> declaration.identifier
        is CSharpBaseFieldDeclaration -> declaration.declaration?.variables?.singleOrNull()?.identifier
        else -> CSharpDeclarationNames.nameElement(declaration)
    }

    /** What renaming to [written] would break: another member of the type or type of the namespace with that name. */
    fun conflicts(project: Project, plan: Plan, written: String): List<String> {
        val name = written.removePrefix("@")
        val out = LinkedHashSet<String>()
        val renamed = plan.symbols.flatMap { it.declarations }.toSet()
        for (symbol in plan.symbols) for (declaration in symbol.declarations) {
            if (symbol.isType) {
                val namespace = namespaceOf(declaration)
                StubIndex.getInstance().processElements(CSharpStubIndexKeys.TYPE_NAMES, name, project, GlobalSearchScope.projectScope(project), CSharpElement::class.java) { other ->
                    if (other !in renamed && namespaceOf(other) == namespace && arity(other) == arity(declaration) && CSharpSolutionSearch.ownerType(other) == CSharpSolutionSearch.ownerType(declaration)) {
                        out += "A type named '$name' is already declared${namespace.takeIf { it.isNotEmpty() }?.let { " in namespace '$it'" }.orEmpty()} (${other.containingFile.name})"
                    }
                    true
                }
                continue
            }
            val owner = CSharpSolutionSearch.ownerType(declaration) as? CSharpTypeDeclaration ?: continue
            val ownerName = CSharpDeclarationNames.name(owner)
            if (ownerName == name) out += "A member cannot have the name of its type '$name'"
            val parts = (owner.containingFile as? CSharpFile)?.let { NativeCSharpResolver(it).declaredType(owner)?.targets() }.orEmpty().ifEmpty { listOf(owner) }
            for (part in parts.filterIsInstance<CSharpTypeDeclaration>()) for (member in part.members) {
                if (member in renamed) continue
                val names = when (member) {
                    is CSharpBaseFieldDeclaration -> member.declaration?.variables.orEmpty().mapNotNull { it.identifier?.text }
                    is CSharpConstructorDeclaration, is CSharpDestructorDeclaration -> emptyList()
                    else -> listOfNotNull(CSharpDeclarationNames.nameElement(member)?.text)
                }.map { it.removePrefix("@") }
                // overloads stay overloads: a method of another signature may share the name with a method
                if (name in names && !(member is CSharpMethodDeclaration && declaration is CSharpMethodDeclaration)) {
                    out += "The type '$ownerName' already has a member named '$name'"
                }
            }
        }
        return out.toList()
    }

    private fun namespaceOf(element: PsiElement): String =
        generateSequence(element.parent) { it.parent }.takeWhile { it !is CSharpFile }.filterIsInstance<CSharpBaseNamespaceDeclaration>()
            .mapNotNull(CSharpDeclarationNames::name).toList().asReversed().joinToString(".")

    private fun arity(element: PsiElement): Int = when (element) {
        is CSharpTypeDeclaration -> element.typeParameterList?.parameters?.size ?: 0
        is CSharpDelegateDeclaration -> element.typeParameterList?.parameters?.size ?: 0
        else -> 0
    }

    /**
     * Writes [newName] over every range of [plan] in one command (Undo takes it back at once), the short name of an attribute keeps its
     * form, the files named after a renamed type are renamed. Asks about conflicts; false when nothing was renamed.
     */
    fun apply(project: Project, editor: Editor?, plan: Plan, newName: String): Boolean {
        val name = newName.trim()
        if (!NativeCSharpRename.isIdentifier(name)) {
            NativeCSharpRename.showError(project, editor, "'$name' is not a valid C# identifier")
            return false
        }
        val written = NativeCSharpRename.written(name)
        if (written.removePrefix("@") == plan.oldName) return false
        // under a progress, as the hierarchy and the plan: the stub index on the EDT is a slow operation the platform reports (docs/compare № 66)
        val conflicts = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<String>, RuntimeException>({
            conflictsOnEdtForTests = ApplicationManager.getApplication().isDispatchThread
            ReadAction.compute<List<String>, RuntimeException> { conflicts(project, plan, written) }
        }, "Checking Conflicts of ${plan.oldName}", true, project)
        if (conflicts.isNotEmpty() && !confirm(project, plan, conflicts)) return false
        val documents = PsiDocumentManager.getInstance(project)
        // by the file of the editor: C# injected into another file is written in its host, with the markup of that file around it
        val edits = LinkedHashMap<PsiFile, MutableSet<Pair<TextRange, String?>>>()
        fun edit(file: PsiFile, range: TextRange, insertion: String?) {
            val host = CSharpInjections.hostFile(file) ?: return
            val base = host.viewProvider.getPsi(host.viewProvider.baseLanguage) ?: host
            edits.getOrPut(base) { LinkedHashSet() } += CSharpInjections.toHost(file, range) to insertion
        }
        for ((file, ranges) in plan.ranges) for (range in ranges) edit(file, range, null)
        for (insertion in plan.insertions) edit(insertion.file, TextRange.from(insertion.offset, 0), insertion.text)
        val files = edits.keys.toList()
        WriteCommandAction.writeCommandAction(project, files).withName("Rename").run<RuntimeException> {
            for (file in files) {
                val document = documents.getDocument(file) ?: continue
                // the renamed ranges and the insertions of the frameworks, from the end of the text so the offsets before stay right
                for ((range, insertion) in edits.getValue(file).sortedWith(compareByDescending<Pair<TextRange, String?>> { it.first.startOffset }.thenByDescending { it.second == null })) {
                    if (insertion != null) {
                        document.insertString(range.startOffset, insertion)
                        continue
                    }
                    val old = document.charsSequence.subSequence(range.startOffset, range.endOffset).toString().removePrefix("@")
                    val replacement = if (plan.primary.isType && old != plan.oldName && plan.oldName.endsWith("Attribute") && old == plan.oldName.removeSuffix("Attribute")) {
                        NativeCSharpRename.written(name.removeSuffix("Attribute").ifEmpty { name })
                    } else written
                    document.replaceString(range.startOffset, range.endOffset, replacement)
                }
                documents.commitDocument(document)
            }
            // `Old.cs` → `New.cs`, `Old.razor.cs` → `New.razor.cs`
            for (file in plan.typeFiles) {
                val virtualFile = file.viewProvider.virtualFile
                if (!virtualFile.name.startsWith(plan.oldName + ".")) continue
                val target = name + virtualFile.name.substring(plan.oldName.length)
                if (virtualFile.isValid && virtualFile.parent?.findChild(target) == null) virtualFile.rename(this, target)
            }
        }
        return true
    }

    private fun confirm(project: Project, plan: Plan, conflicts: List<String>): Boolean {
        if (ApplicationManager.getApplication().isUnitTestMode) {
            if (BaseRefactoringProcessor.ConflictsInTestsException.isTestIgnore()) return true
            throw BaseRefactoringProcessor.ConflictsInTestsException(conflicts)
        }
        val map = MultiMap<PsiElement, String>()
        val anchor = plan.primary.primary ?: return Messages.showYesNoDialog(project, conflicts.joinToString("\n"), "Rename", null) == Messages.YES
        conflicts.forEach { map.putValue(anchor, StringUtil.escapeXmlEntities(it)) }
        return ConflictsDialog(project, map).showAndGet()
    }

    /**
     * Shift+F6 on a type or member: the hierarchy question, the search under a progress, then the new name — inplace over the occurrences in
     * this editor (the rest follows on Enter), from [name] when given (tests, the dialog of the platform), or from a dialog — always with
     * [dialog] (the editor is not of [file]: the markup of Razor naming a parameter).
     */
    fun invoke(project: Project, editor: Editor, file: CSharpFile, target: CSharpSearchTarget, name: String?, dialog: Boolean = false) {
        val family = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<CSharpSearchTarget>, RuntimeException>({
            ReadAction.compute<List<CSharpSearchTarget>, RuntimeException> { family(project, target) }
        }, "Searching for the Hierarchy of ${target.name}", true, project)
        val withFamily = askHierarchy(project, target, family) ?: return
        val symbols = if (withFamily) listOf(target) + family else listOf(target)
        val plan = ProgressManager.getInstance().runProcessWithProgressSynchronously<Plan, RuntimeException>({
            ReadAction.compute<Plan, RuntimeException> { plan(project, symbols) }
        }, "Searching for Usages of ${target.name}", true, project)
        val here = plan.ranges[file].orEmpty()
        val offset = editor.caretModel.offset
        when {
            name != null -> apply(project, editor, plan, name)
            !dialog && editor.settings.isVariableInplaceRenameEnabled && here.isNotEmpty() -> {
                val primary = here.firstOrNull { it.containsOffset(offset) } ?: here.first()
                NativeCSharpInplaceRename(project, editor, file, here, primary, plan.oldName) { newName -> apply(project, editor, plan, newName) }.start()
            }
            else -> Messages.showInputDialog(project, "Rename ${plan.oldName} to:", "Rename", null, plan.oldName, null)?.let { apply(project, editor, plan, it) }
        }
    }
}
