package io.github.dotnetsupport.lang

import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateBuilderImpl
import com.intellij.codeInsight.template.TemplateEditingAdapter
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TextExpression
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.impl.FinishMarkAction
import com.intellij.openapi.command.impl.StartMarkAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.refactoring.ui.ConflictsDialog
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.containers.MultiMap
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLiteralScanner
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpSearchTarget
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch
import io.github.dotnetsupport.lsp.RoslynServerStatus
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectFileIndex

/**
 * Rename without the language server (CSHARP_PSI_MIGRATION.md, step 9, task A5, feature `RENAME`, the syntactic part): a symbol whose every
 * use is in its file and visible to the one resolver of the native tree ([NativeCSharpResolver]) — a local, a parameter of a lambda, an
 * anonymous method or a local function, a parameter of a method, constructor, indexer, delegate or primary constructor of a class that no
 * named argument elsewhere uses, a local function, a label, a range variable, a type parameter of what is not `partial`. Its declaration,
 * its uses, the named arguments of calls the resolver ties to its function and the `<param>` / `<typeparam>` (`ref`) tags of the doc
 * comment of its owner are renamed together; a reserved keyword gets `@`; a name that would change what another name means (a field the
 * new name captures, a lambda's parameter that would capture a use, a second declaration in the same scope) is a conflict, shown as the
 * platform shows them.
 *
 * Types and members of the solution are renamed across it by [NativeCSharpSolutionRename] (task C4b). What is left (a record's positional
 * parameter, which is a property; a name the resolver does not know) goes to the language server when it is ready; without the server a
 * hint says why. ROSLYN: the server's rename, as before.
 */
object NativeCSharpRename {
    /** The switch gives RENAME to the native tree and [file] is of it. */
    fun serves(file: PsiFile?): Boolean = file is CSharpFile && file.compilationUnit != null && CSharpFeatures.native(CSharpFeature.RENAME, file.project)

    private val forwarding = ThreadLocal.withInitial { false }

    /**
     * The rename of the language server answers for [file] (`LspRenameSupport.shouldRunRename`): ROSLYN, a file of the heuristic tree, or
     * a symbol [NativeCSharpRenameHandler] hands over to the server. One handler at a time, so the platform never asks which.
     */
    fun serverRenames(file: PsiFile): Boolean = !serves(file) || forwarding.get()

    /** What renames the name at [offset]: this rename, the server's, or nobody (with the reason). */
    sealed interface Decision
    class Native(val target: Target) : Decision
    class Refuse(val reason: String) : Decision
    data object Server : Decision

    /** A type or member of the solution: [NativeCSharpSolutionRename] (task C4b). */
    class Solution(val target: CSharpSearchTarget) : Decision

    /**
     * The local [symbol] of [file] and every range to rename: the [declaration], the uses ([references]) and the named arguments and doc
     * comment tags ([extra]). [primary] is the one the caret is on.
     */
    class Target(val file: CSharpFile, val symbol: LocalSymbol, val declaration: TextRange, val references: List<TextRange>, val extra: List<TextRange>, val primary: TextRange) {
        val ranges: List<TextRange> get() = (listOf(declaration) + references + extra).distinct().sortedBy { it.startOffset }
        val name: String get() = symbol.name.removePrefix("@")
    }

    /** The identifier leaf at [offset] or right before it (the caret at the end of a name). */
    fun identifierAt(file: PsiFile, offset: Int): PsiElement? =
        file.findElementAt(offset)?.takeIf(CSharpLeaves::isIdentifier) ?: if (offset > 0) file.findElementAt(offset - 1)?.takeIf(CSharpLeaves::isIdentifier) else null

    /**
     * Who renames the identifier at [offset]. [deep]: also look for named arguments of a parameter in the other files of the solution
     * (the index of words and the trees of the files that have the word; at Shift+F6, not on every update of the action).
     */
    fun decide(file: CSharpFile, offset: Int, deep: Boolean = true): Decision {
        if (!serves(file)) return Server
        val leaf = identifierAt(file, offset) ?: return Server
        val resolver = NativeCSharpResolver(file)
        val symbol = resolver.symbolAt(leaf)
        if (symbol == null || symbol.isMember) return solution(file, leaf)
        val owner = owner(symbol)
        // every part of a partial type or method declares its type parameters (and a partial method its parameters) again
        if (symbol.kind != LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER && owner is CSharpMemberDeclaration && owner.modifiers.any { it.textMatches("partial") }) {
            return elsewhere(file.project, "'${symbol.name}' is declared by every part of a partial declaration: it is renamed by the C# language server, which is not ready")
        }
        val extra = ArrayList<TextRange>()
        if (symbol.kind == LocalSymbolKind.PARAMETER || symbol.kind == LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER) {
            val named = namedArguments(file, resolver, symbol, owner)
            if (named == null || deep && owner !is CSharpLocalFunctionStatement && namedArgumentsElsewhere(file, symbol.name.removePrefix("@"))) {
                return elsewhere(file.project, "Parameter '${symbol.name}' is used as a named argument the built-in rename cannot follow: it is renamed by the C# language server, which is not ready")
            }
            extra += named
        }
        if (owner != null && (symbol.kind == LocalSymbolKind.PARAMETER || symbol.kind == LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER || symbol.kind == LocalSymbolKind.TYPE_PARAMETER)) {
            extra += docTags(file, owner, symbol.name.removePrefix("@"), typeParameter = symbol.kind == LocalSymbolKind.TYPE_PARAMETER)
        }
        val references = resolver.references(symbol).map { it.textRange }
        val all = listOf(symbol.declaration.textRange) + references + extra
        val primary = all.firstOrNull { it.containsOffset(offset) } ?: symbol.declaration.textRange
        return Native(Target(file, symbol, symbol.declaration.textRange, references, extra, primary))
    }

    /**
     * A type or member of the solution (C4b): every declaration in the sources of the project. A record's positional parameter (a property
     * declared by a parameter) and what is declared in an assembly stay out.
     */
    private fun solution(file: CSharpFile, leaf: PsiElement): Decision {
        val project = file.project
        val notLocal = "'${leaf.text}' is not a local symbol: it is renamed by the C# language server, which is not ready"
        if (leaf.parent is CSharpParameter || DumbService.isDumb(project)) return elsewhere(project, notLocal)
        var target = CSharpSolutionSearch.targetAt(leaf, CSharpSemanticSession(project)) ?: return elsewhere(project, notLocal)
        // a constructor is named by its type: renaming it renames the type, as in Rider
        if (target.kind == CSharpSearchTarget.Kind.CONSTRUCTOR) {
            target = target.primary?.let(CSharpSolutionSearch::ownerType)?.let(CSharpSolutionSearch::targetOf) ?: return elsewhere(project, notLocal)
        }
        if (target.declarations.isEmpty()) return Refuse("'${target.name}' is declared in a referenced assembly and cannot be renamed")
        val index = ProjectFileIndex.getInstance(project)
        val outside = target.declarations.firstOrNull { declaration ->
            val virtualFile = declaration.containingFile?.viewProvider?.virtualFile
            virtualFile == null || !virtualFile.isWritable || virtualFile.isInLocalFileSystem && !index.isInContent(virtualFile)
        }
        if (outside != null) return Refuse("'${target.name}' is declared outside the solution (${outside.containingFile?.name}) and cannot be renamed")
        return Solution(target)
    }

    private fun elsewhere(project: Project, reason: String): Decision = if (RoslynServerStatus.isReady(project)) Server else Refuse(reason)

    /** What declares the parameters or type parameters [symbol] is one of: a method, a lambda, a type...; null for locals and labels. */
    private fun owner(symbol: LocalSymbol): PsiElement? = when (val parent = symbol.declaration.parent) {
        is CSharpParameter -> parent.parent?.let { if (it is CSharpBaseParameterList) it.parent else it }
        is CSharpTypeParameter -> parent.parent?.parent
        else -> null
    }

    /**
     * The names of the named arguments of calls of [owner] in [file] (`Local(count: 1)`, `M(count: 1)` with `M` resolved to [owner]); null
     * when a named argument of the name goes to a call the resolver does not tie to a function (`new T(count: 1)`, `a.M(count: 1)`, an
     * attribute), which may be [owner]'s — except for a local function, which nothing outside its body calls.
     */
    private fun namedArguments(file: CSharpFile, resolver: NativeCSharpResolver, symbol: LocalSymbol, owner: PsiElement?): List<TextRange>? {
        if (owner is CSharpAnonymousFunctionExpression) return emptyList()
        val name = symbol.name.removePrefix("@")
        val ownerTargets = setOfNotNull(owner, (owner as? CSharpLocalFunctionStatement)?.identifier)
        val found = ArrayList<TextRange>()
        for (nameColon in SyntaxTraverser.psiTraverser(file).filter(CSharpNameColon::class.java)) {
            val identifier = nameColon.nameElement?.identifier ?: continue
            if (identifier.text.removePrefix("@") != name || !isArgumentName(nameColon)) continue
            val call = (nameColon.parent?.parent?.parent as? CSharpInvocationExpression)?.expression
            val callee = when (call) {
                is CSharpSimpleName -> call.identifier
                is CSharpMemberAccessExpression -> if (call.expression is CSharpThisExpression || call.expression is CSharpBaseExpression) call.nameElement?.identifier else null
                else -> null
            }
            val targets = callee?.let(resolver::declarations)
            when {
                targets != null && targets.size == 1 && targets[0] in ownerTargets -> found += identifier.textRange
                targets != null && targets.none { it in ownerTargets } -> {} // a named argument of another function
                owner is CSharpLocalFunctionStatement -> {}
                else -> return null
            }
        }
        return found
    }

    /** `f(name: 1)` or `[A(name: 1)]`: not a tuple element `(name: 1, 2)` nor a property pattern `{ name: 1 }`. */
    private fun isArgumentName(nameColon: CSharpNameColon): Boolean {
        val parent = nameColon.parent
        return parent is CSharpAttributeArgument || parent is CSharpArgument && parent.parent?.parent !is CSharpTupleExpression
    }

    /** A named argument [name] in another C# file of the solution: the files with the word, then their trees. */
    private fun namedArgumentsElsewhere(file: CSharpFile, name: String): Boolean {
        var found = false
        PsiSearchHelper.getInstance(file.project).processAllFilesWithWord(name, GlobalSearchScope.projectScope(file.project), { other ->
            if (other != file && other is CSharpFile && other.compilationUnit != null) {
                found = SyntaxTraverser.psiTraverser(other).filter(CSharpNameColon::class.java).any { it.nameElement?.identifier?.text?.removePrefix("@") == name && isArgumentName(it) }
            }
            !found
        }, true)
        return found
    }

    /** The names in `<param name="x">`, `<paramref name="x"/>` (`typeparam` for a type parameter) of the doc comment right before [owner]. */
    private fun docTags(file: CSharpFile, owner: PsiElement, name: String, typeParameter: Boolean): List<TextRange> {
        var first: PsiElement? = PsiTreeUtil.getDeepestFirst(owner)
        while (first != null && isTrivia(first)) first = PsiTreeUtil.nextLeaf(first)
        val end = first?.textRange?.startOffset ?: return emptyList()
        var start = end
        var previous = PsiTreeUtil.prevLeaf(first!!)
        while (previous != null && isTrivia(previous)) {
            start = previous.textRange.startOffset
            previous = PsiTreeUtil.prevLeaf(previous)
        }
        if (start == end) return emptyList()
        val tag = if (typeParameter) "typeparam" else "param"
        val regex = Regex("""<(?:$tag|${tag}ref)\s+name\s*=\s*"(${Regex.escape(name)})"""")
        val text = file.viewProvider.contents.subSequence(start, end)
        return regex.findAll(text).map { TextRange(start + it.groups[1]!!.range.first, start + it.groups[1]!!.range.last + 1) }
            .filter { range -> file.findElementAt(range.startOffset)?.let { PsiTreeUtil.getParentOfType(it, PsiComment::class.java, false) != null } == true }.toList()
    }

    private fun isTrivia(leaf: PsiElement): Boolean = leaf is PsiWhiteSpace || PsiTreeUtil.getParentOfType(leaf, PsiComment::class.java, false) != null

    // ---- the new name

    /** [name] is an identifier of C#, `@` allowed in front. */
    fun isIdentifier(name: String): Boolean {
        val bare = name.removePrefix("@")
        return bare.isNotEmpty() && CSharpLiteralScanner.isIdentifierStartCharacter(bare[0]) && bare.all(CSharpLiteralScanner::isIdentifierPartCharacter)
    }

    /** How [name] is written: `@` before a reserved keyword (`@class`), as typed otherwise. */
    fun written(name: String): String = if (!name.startsWith("@") && CSharpSyntaxFacts.getKeywordKind(name) != null) "@$name" else name

    /**
     * What renaming [target] to [written] would break, as messages: a use that would mean another declaration, a name that would mean the
     * renamed symbol instead of what it means now, a declaration of the name in an enclosing or enclosed scope, a member of the type that
     * would hide a parameter of the primary constructor. The file with the new name is parsed apart and its scopes compared.
     */
    fun conflicts(target: Target, written: String): List<String> {
        val file = target.file
        val text = file.viewProvider.contents.toString()
        val newName = written.removePrefix("@")
        val ranges = target.ranges
        val newText = buildString {
            var at = 0
            for (range in ranges) {
                append(text, at, range.startOffset)
                append(written)
                at = range.endOffset
            }
            append(text, at, text.length)
        }
        fun moved(offset: Int): Int = offset + ranges.filter { it.endOffset <= offset }.sumOf { written.length - it.length }
        fun line(offset: Int): Int = StringUtil.offsetToLineNumber(text, offset) + 1
        val copy = parseLike(file, newText)
        val after = NativeCSharpScopes.build(copy)
        fun symbolAfter(offset: Int): LocalSymbol? = NativeCSharpRename.identifierAt(copy, moved(offset))?.let(after::symbolAt)
        val renamed = symbolAfter(target.declaration.startOffset) ?: return listOf("The new name '$written' breaks the declaration of '${target.name}'")
        val kind = describe(target.symbol)
        val out = LinkedHashSet<String>()
        for (reference in target.references) if (symbolAfter(reference.startOffset) !== renamed) {
            out += "The usage of $kind '${target.name}' at line ${line(reference.startOffset)} would refer to another declaration named '$newName'"
        }
        val resolver = NativeCSharpResolver(file)
        for (leaf in SyntaxTraverser.psiTraverser(file).filter { it.firstChild == null && CSharpLeaves.isIdentifier(it) && it.text.removePrefix("@") == newName }) {
            if (symbolAfter(leaf.textRange.startOffset) === renamed) {
                val meant = resolver.symbolAt(leaf)?.let { "${describe(it)} '$newName'" } ?: "'$newName'"
                out += "The usage of $meant at line ${line(leaf.textRange.startOffset)} would refer to the renamed $kind"
            }
        }
        for (other in after.symbols) {
            if (other === renamed || other.name.removePrefix("@") != newName || (other.kind == LocalSymbolKind.LABEL) != (renamed.kind == LocalSymbolKind.LABEL)) continue
            if (PsiTreeUtil.isAncestor(other.scope, renamed.declaration, false) || PsiTreeUtil.isAncestor(renamed.scope, other.declaration, false)) {
                val offset = other.declaration.textRange.startOffset
                out += "A ${describe(other)} named '$newName' is already declared at line ${StringUtil.offsetToLineNumber(newText, offset) + 1}"
            }
        }
        if (target.symbol.kind == LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER && target.references.isNotEmpty() && resolver.memberHiding(newName, target.symbol.declaration) != null) {
            out += "A member named '$newName' of the type would hide the primary constructor parameter in its uses"
        }
        return out.toList()
    }

    private fun describe(symbol: LocalSymbol): String = when (symbol.kind) {
        LocalSymbolKind.LOCAL -> "local variable"
        LocalSymbolKind.PARAMETER -> "parameter"
        LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER -> "primary constructor parameter"
        LocalSymbolKind.LOCAL_FUNCTION -> "local function"
        LocalSymbolKind.TYPE_PARAMETER -> "type parameter"
        LocalSymbolKind.LABEL -> "label"
    }

    /** [text] parsed as [file] would be: the same `#if` symbols and language version (the copy's virtual file points to the original). */
    private fun parseLike(file: CSharpFile, text: String): CSharpFile {
        val copy = NativeCSharpSyntaxModel.parse(text)
        (copy.viewProvider.virtualFile as? LightVirtualFile)?.originalFile = file.viewProvider.virtualFile
        return copy
    }

    /**
     * Renames the symbol at [offset] to [newName] in one command: checks the name, asks about [conflicts] (in tests they throw, as the
     * platform's refactorings do), writes `@` before a keyword. False when nothing was renamed.
     */
    fun perform(project: Project, editor: Editor?, file: CSharpFile, offset: Int, newName: String): Boolean {
        val target = (decide(file, offset) as? Native)?.target ?: return false
        val name = newName.trim()
        if (!isIdentifier(name)) {
            showError(project, editor, "'$name' is not a valid C# identifier")
            return false
        }
        val written = written(name)
        if (written == target.symbol.name && target.ranges.all { file.viewProvider.contents.subSequence(it.startOffset, it.endOffset).toString() == written }) return false
        val conflicts = conflicts(target, written)
        if (conflicts.isNotEmpty() && !confirm(project, target, conflicts)) return false
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return false
        WriteCommandAction.writeCommandAction(project, file).withName("Rename").run<RuntimeException> {
            for (range in target.ranges.asReversed()) document.replaceString(range.startOffset, range.endOffset, written)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        return true
    }

    private fun confirm(project: Project, target: Target, conflicts: List<String>): Boolean {
        if (ApplicationManager.getApplication().isUnitTestMode) {
            if (BaseRefactoringProcessor.ConflictsInTestsException.isTestIgnore()) return true
            throw BaseRefactoringProcessor.ConflictsInTestsException(conflicts)
        }
        val map = MultiMap<PsiElement, String>()
        conflicts.forEach { map.putValue(target.symbol.declaration, StringUtil.escapeXmlEntities(it)) }
        return ConflictsDialog(project, map).showAndGet()
    }

    internal fun showError(project: Project, editor: Editor?, message: String) {
        if (editor == null) Messages.showErrorDialog(project, message, "Rename") else CommonRefactoringUtil.showErrorHint(project, editor, message, "Rename", null)
    }

    /** The rename of the language server on [dataContext], as if it were the only handler: for what this rename leaves to it. */
    internal fun forwardToServer(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext): Boolean {
        val server = RenameHandler.EP_NAME.extensionList.firstOrNull { it.javaClass.name.endsWith(".LspRenameHandler") } ?: return false
        forwarding.set(true)
        try {
            if (!server.isAvailableOnDataContext(dataContext)) return false
            server.invoke(project, editor, file, dataContext)
            return true
        } finally {
            forwarding.set(false)
        }
    }
}

/**
 * Shift+F6 on the native tree when RENAME is NATIVE ([NativeCSharpRename]): available on any identifier of such a file, so the platform
 * never offers a choice between it and the language server's (that one stands down, `RoslynLspIntegration`); a symbol it does not rename
 * goes on to the server from here, or gets a hint without it. Inplace (a template over every occurrence, as the platform renames a local)
 * unless the editor's "Rename in place" is off or a test passes the new name.
 */
class NativeCSharpRenameHandler : RenameHandler {
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return false
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) as? CSharpFile ?: return false
        return NativeCSharpRename.serves(file) && NativeCSharpRename.identifierAt(file, editor.caretModel.offset) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (editor == null || file !is CSharpFile) return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val offset = editor.caretModel.offset
        when (val decision = NativeCSharpRename.decide(file, offset)) {
            is NativeCSharpRename.Native -> {
                val name = dataContext?.let(PsiElementRenameHandler.DEFAULT_NAME::getData)
                when {
                    name != null -> NativeCSharpRename.perform(project, editor, file, offset, name)
                    editor.settings.isVariableInplaceRenameEnabled -> NativeCSharpInplaceRename(project, editor, decision.target).start()
                    else -> Messages.showInputDialog(project, "Rename ${decision.target.symbol.name} to:", "Rename", null, decision.target.symbol.name, null)
                        ?.let { NativeCSharpRename.perform(project, editor, file, offset, it) }
                }
            }
            is NativeCSharpRename.Solution -> NativeCSharpSolutionRename.invoke(project, editor, file, decision.target, dataContext?.let(PsiElementRenameHandler.DEFAULT_NAME::getData))
            is NativeCSharpRename.Refuse -> NativeCSharpRename.showError(project, editor, decision.reason)
            NativeCSharpRename.Server -> if (dataContext == null || !NativeCSharpRename.forwardToServer(project, editor, file, dataContext)) {
                NativeCSharpRename.showError(project, editor, "The C# language server cannot rename this symbol now")
            }
        }
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {}
}

/**
 * The inplace rename of [target], as the platform's `VariableInplaceRenamer` does it: a template whose variable is the name under the caret
 * and whose other occurrences follow it, all of it one step of Undo (start and finish marks). On Enter the occurrences go back to the old
 * name and [NativeCSharpRename.perform] renames them for real — the name checked, conflicts shown, keywords escaped; Escape restores the
 * old name.
 */
class NativeCSharpInplaceRename(
    private val project: Project, private val editor: Editor, private val file: CSharpFile, private val ranges: List<TextRange>, private val primaryRange: TextRange,
    private val oldName: String, private val apply: (String) -> Unit,
) {
    constructor(project: Project, editor: Editor, target: NativeCSharpRename.Target) : this(project, editor, target.file, target.ranges, target.primary, target.symbol.name, { name ->
        (PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? CSharpFile)?.let { NativeCSharpRename.perform(project, editor, it, target.primary.startOffset, name) }
    })

    private var texts: List<String> = emptyList()
    private var markers: List<RangeMarker> = emptyList()
    private var primary: RangeMarker? = null
    private var done = false

    fun start() {
        val mark = try {
            var started: StartMarkAction? = null
            CommandProcessor.getInstance().executeCommand(project, { started = StartMarkAction.start(editor, project, "Rename") }, "Rename", null)
            started
        } catch (e: StartMarkAction.AlreadyStartedException) {
            NativeCSharpRename.showError(project, editor, e.message ?: "Another refactoring is in progress")
            return
        }
        val document = editor.document
        texts = ranges.map { document.getText(it) }
        markers = ranges.map { range -> document.createRangeMarker(range).apply { isGreedyToLeft = true; isGreedyToRight = true } }
        primary = markers[ranges.indexOf(primaryRange).coerceAtLeast(0)]
        val container = PsiTreeUtil.findCommonParent(file.findElementAt(ranges.first().startOffset), file.findElementAt(ranges.last().endOffset - 1)) ?: file
        val base = container.textRange.startOffset
        val builder = TemplateBuilderImpl(container)
        builder.replaceElement(container, primaryRange.shiftLeft(base), PRIMARY, TextExpression(document.getText(primaryRange)), true)
        ranges.filter { it != primaryRange }.forEachIndexed { i, range -> builder.replaceElement(container, range.shiftLeft(base), "$OTHER$i", PRIMARY, false) }
        val caret = editor.caretModel.offset
        WriteCommandAction.writeCommandAction(project).withName("Rename").run<RuntimeException> {
            val template = builder.buildInlineTemplate()
            template.isToShortenLongNames = false
            template.isToReformat = false
            editor.caretModel.moveToOffset(base)
            TemplateManager.getInstance(project).startTemplate(editor, template, object : TemplateEditingAdapter() {
                override fun templateFinished(template: Template, brokenOff: Boolean) = finish(mark, apply = true)
                override fun templateCancelled(template: Template?) = finish(mark, apply = false)
            })
        }
        if (TemplateManager.getInstance(project).getActiveTemplate(editor) != null) editor.caretModel.moveToOffset(caret.coerceIn(primary!!.startOffset, primary!!.endOffset))
    }

    /**
     * Back to the old name everywhere; then, on Enter, the rename itself. Later, not inside the listener of the template: the rename may ask
     * about conflicts in a dialog and writes the file, which needs a write-safe context of the editor's modality (TransactionGuard).
     */
    private fun finish(mark: StartMarkAction?, apply: Boolean) {
        if (done) return
        done = true
        ApplicationManager.getApplication().invokeLater({ complete(mark, apply) }, ModalityState.stateForComponent(editor.contentComponent), project.disposed)
    }

    private fun complete(mark: StartMarkAction?, apply: Boolean) {
        if (editor.isDisposed) return markers.forEach(RangeMarker::dispose)
        val document = editor.document
        val name = primary?.takeIf { it.isValid }?.let { document.getText(it.textRange) }
        try {
            WriteCommandAction.writeCommandAction(project).withName("Rename").run<RuntimeException> {
                // each occurrence back to its own text (`@Name` stays `@Name`, the short name of an attribute stays short)
                for ((marker, text) in markers.zip(texts).filter { it.first.isValid }.sortedByDescending { it.first.startOffset }) document.replaceString(marker.startOffset, marker.endOffset, text)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            if (apply && name != null && name != oldName) this.apply(name)
        } finally {
            markers.forEach(RangeMarker::dispose)
            FinishMarkAction.finish(project, editor, mark)
        }
    }

    private companion object {
        const val PRIMARY = "NAME"
        const val OTHER = "OTHER"
    }
}
