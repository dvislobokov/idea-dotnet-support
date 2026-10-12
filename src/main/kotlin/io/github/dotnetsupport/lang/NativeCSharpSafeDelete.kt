package io.github.dotnetsupport.lang

import com.intellij.idea.ActionsBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.actions.SafeDeleteAction
import com.intellij.refactoring.ui.ConflictsDialog
import com.intellij.util.containers.MultiMap
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpSearchTarget
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch
import org.jetbrains.annotations.TestOnly

/**
 * Safe Delete of a C# declaration without the language server (Jellyfin comparison, № 50; 0.1.174), as Rider: a type (every part, the file
 * of a type alone in it), a member (method, constructor, property, event, field — the declarator of a field of several), an enum member,
 * a parameter of a method, constructor or local function (with its argument at every call, in the whole hierarchy of the method, and its
 * `<param>` line of the doc comment), a local, a local function, a type parameter (with its type argument where the owner is named with
 * them). Usages left after the deletion are conflicts: shown before anything changes, "Continue" deletes anyway, as in Rider. The usages are
 * those of Find Usages ([CSharpSolutionSearch], [NativeCSharpResolver]); a member of a hierarchy (it overrides, implements or is overridden)
 * is a conflict too — the other members of the hierarchy are not deleted.
 */
object NativeCSharpSafeDelete {
    enum class Kind { TYPE, MEMBER, ENUM_MEMBER, PARAMETER, LOCAL, LOCAL_FUNCTION, TYPE_PARAMETER }

    /** What is deleted: [declarations] (the parts of a partial type, else one), named [name]. */
    class Target(val kind: Kind, val name: String, val declarations: List<PsiElement>) {
        val primary: PsiElement get() = declarations.first()
    }

    /** The text edits per file, the files deleted, the conflicts (a usage left or a hierarchy), by the element they are about. */
    class Plan(val target: Target) {
        val edits = LinkedHashMap<PsiFile, MutableList<Pair<TextRange, String>>>()
        val deletedFiles = LinkedHashSet<PsiFile>()
        val conflicts = MultiMap<PsiElement, String>()

        fun delete(file: PsiFile, range: TextRange) = replace(file, range, "")
        fun replace(file: PsiFile, range: TextRange, text: String) { edits.getOrPut(file) { ArrayList() } += range to text }
    }

    @Volatile private var deleteFileAnswerForTests: Boolean? = null

    @TestOnly
    fun answerDeleteFileForTests(answer: Boolean?) {
        deleteFileAnswerForTests = answer
    }

    // ---- the target at the caret

    /** The declaration Safe Delete takes at [offset]: named there, or the one a name there stands for; null where nothing can be deleted. */
    fun targetAt(file: CSharpFile, offset: Int): Target? {
        if (file.compilationUnit == null) return null
        val leaf = NativeCSharpFindUsages.identifierAt(file, offset) ?: return null
        localTarget(file, leaf)?.let { return it }
        val target = CSharpSolutionSearch.targetAt(leaf, CSharpSemanticSession(file.project)) ?: return null
        if (target.library != null) return null
        val declarations = target.declarations.filter { it.containingFile is CSharpFile && it.isWritable && it.isPhysical }
        if (declarations.isEmpty()) return null
        return when (target.kind) {
            CSharpSearchTarget.Kind.TYPE -> Target(Kind.TYPE, target.name, declarations)
            CSharpSearchTarget.Kind.ENUM_MEMBER -> Target(Kind.ENUM_MEMBER, target.name, declarations.take(1))
            CSharpSearchTarget.Kind.INDEXER -> null
            else -> declarations.first().takeUnless { it is CSharpOperatorDeclaration || it is CSharpConversionOperatorDeclaration || it is CSharpDestructorDeclaration }
                ?.let { Target(Kind.MEMBER, target.name, listOf(it)) }
        }
    }

    private fun localTarget(file: CSharpFile, leaf: PsiElement): Target? {
        val symbol = NativeCSharpResolver(file).symbolAt(leaf)?.takeUnless { it.isMember } ?: return null
        val name = symbol.name
        val node = symbol.declaration.parent ?: return null
        return when (symbol.kind) {
            LocalSymbolKind.PARAMETER -> {
                val parameter = node as? CSharpParameter ?: return null
                val owner = parameter.parent?.parent ?: return null
                if (owner !is CSharpMethodDeclaration && owner !is CSharpConstructorDeclaration && owner !is CSharpLocalFunctionStatement) return null
                // the receiver of an extension method is no parameter to delete
                if (parameter.modifiers.any { it.text == "this" }) return null
                Target(Kind.PARAMETER, name, listOf(parameter))
            }
            LocalSymbolKind.LOCAL -> {
                val declarator = node as? CSharpVariableDeclarator ?: return null
                if (declarator.parent?.parent !is CSharpLocalDeclarationStatement) return null
                Target(Kind.LOCAL, name, listOf(declarator))
            }
            LocalSymbolKind.LOCAL_FUNCTION -> (node as? CSharpLocalFunctionStatement)?.let { Target(Kind.LOCAL_FUNCTION, name, listOf(it)) }
            LocalSymbolKind.TYPE_PARAMETER -> (node as? CSharpTypeParameter)?.let { Target(Kind.TYPE_PARAMETER, name, listOf(it)) }
            else -> null
        }
    }

    // ---- the plan

    /** What deleting [target] changes and what it breaks. In a read action, never on the EDT for a type or member (the search of the solution). */
    fun plan(project: Project, target: Target): Plan {
        val plan = Plan(target)
        when (target.kind) {
            Kind.TYPE, Kind.MEMBER, Kind.ENUM_MEMBER -> planDeclaration(project, plan)
            Kind.PARAMETER -> planParameter(project, plan)
            Kind.LOCAL -> planLocal(plan)
            Kind.LOCAL_FUNCTION -> planLocalFunction(plan)
            Kind.TYPE_PARAMETER -> planTypeParameter(project, plan)
        }
        return plan
    }

    private fun inside(leaf: PsiElement, roots: List<PsiElement>): Boolean = roots.any { PsiTreeUtil.isAncestor(it, leaf, false) }

    private fun conflict(plan: Plan, usage: PsiElement, what: String) {
        val file = usage.containingFile
        val document = PsiDocumentManager.getInstance(usage.project).getDocument(file)
        val line = document?.let { it.getLineNumber(usage.textRange.startOffset) + 1 }
        plan.conflicts.putValue(usage, "$what is used in ${file.name}${line?.let { ":$it" }.orEmpty()}")
    }

    private fun kindName(target: Target): String = when (val d = target.primary) {
        is CSharpBaseTypeDeclaration, is CSharpDelegateDeclaration -> "Type"
        is CSharpMethodDeclaration -> "Method"
        is CSharpConstructorDeclaration -> "Constructor"
        is CSharpPropertyDeclaration -> "Property"
        is CSharpEventDeclaration, is CSharpEventFieldDeclaration -> "Event"
        is CSharpEnumMemberDeclaration -> "Enum member"
        is CSharpBaseFieldDeclaration -> "Field"
        is CSharpVariableDeclarator -> if (d.parent?.parent is CSharpEventFieldDeclaration) "Event" else "Field"
        is CSharpParameter -> "Parameter"
        is CSharpLocalFunctionStatement -> "Local function"
        is CSharpTypeParameter -> "Type parameter"
        else -> "Variable"
    } + " '${target.name}'"

    private fun planDeclaration(project: Project, plan: Plan) {
        val target = plan.target
        val search = CSharpSolutionSearch.targetOf(target.primary) ?: return
        val session = CSharpSemanticSession(project)
        val what = kindName(target)
        CSharpSolutionSearch.processUsages(project, search, GlobalSearchScope.projectScope(project), session) { usage ->
            if (!inside(usage.leaf, target.declarations)) conflict(plan, usage.leaf, what)
            true
        }
        // a member of a hierarchy: the others stay, and they lose their base or their implementation
        if (target.kind == Kind.MEMBER) {
            val hierarchy = CSharpSolutionSearch.withHierarchy(project, search, session).declarations.filter { it !in target.declarations }
            for (other in hierarchy) {
                val owner = PsiTreeUtil.getParentOfType(other, CSharpBaseTypeDeclaration::class.java)?.let { CSharpDeclarationNames.nameElement(it)?.text }
                plan.conflicts.putValue(other, "$what is in a hierarchy with the member of '${owner ?: other.containingFile.name}'")
            }
        }
        for (declaration in target.declarations) {
            val file = declaration.containingFile
            if (target.kind == Kind.TYPE && aloneInFile(declaration)) plan.deletedFiles += file
            else plan.delete(file, declarationRange(declaration))
        }
    }

    /** A top-level type that is the only type of its file (a file-scoped or one block namespace around it at most). */
    private fun aloneInFile(type: PsiElement): Boolean {
        val unit = (type.containingFile as? CSharpFile)?.compilationUnit ?: return false
        val members = unit.members
        if (members.size != 1) return false
        val only = members.single()
        if (only == type) return true
        val namespace = only as? CSharpBaseNamespaceDeclaration ?: return false
        return namespace.members.singleOrNull() == type
    }

    /** The text of a declaration to delete: a member, an enum member with its comma, the declarator of a field of several with its comma. */
    private fun declarationRange(declaration: PsiElement): TextRange {
        val text = declaration.containingFile.viewProvider.contents
        return when (declaration) {
            is CSharpEnumMemberDeclaration -> {
                val owner = declaration.parent as CSharpEnumDeclaration
                lineCleanup(text, listItemRange(declaration, owner.members, owner.membersSeparators))
            }
            is CSharpVariableDeclarator -> {
                val variables = declaration.parent as CSharpVariableDeclaration
                if (variables.variables.size == 1) memberRange(text, variables.parent ?: declaration)
                else listItemRange(declaration, variables.variables, variables.variablesSeparators)
            }
            else -> memberRange(text, declaration)
        }
    }

    private fun planParameter(project: Project, plan: Plan) {
        val parameter = plan.target.primary as CSharpParameter
        val list = parameter.parent as? CSharpParameterList ?: return
        val index = list.parameters.indexOf(parameter)
        val owner = list.parent ?: return
        val what = kindName(plan.target)
        val isParams = parameter.modifiers.any { it.text == "params" }
        // the method and every member of its hierarchy lose the parameter at that position
        val owners: List<PsiElement> = if (owner is CSharpLocalFunctionStatement) listOf(owner) else {
            val search = CSharpSolutionSearch.targetOf(owner)
            if (search == null) listOf(owner) else CSharpSolutionSearch.withHierarchy(project, search, CSharpSemanticSession(project)).declarations
                .filter { it.containingFile is CSharpFile && it.isWritable }.ifEmpty { listOf(owner) }
        }
        val calls = LinkedHashSet<PsiElement>()
        for (member in owners) {
            val parameters = (member as? CSharpBaseMethodDeclaration)?.parameterList ?: (member as? CSharpLocalFunctionStatement)?.parameterList ?: continue
            val own = parameters.parameters.getOrNull(index) ?: continue
            val file = member.containingFile
            // its uses in the body stay: conflicts
            val resolver = (file as? CSharpFile)?.let(::NativeCSharpResolver)
            val ownName = own.identifier
            if (resolver != null && ownName != null) {
                resolver.symbolAt(ownName)?.let { symbol -> for (use in resolver.references(symbol)) conflict(plan, use, what) }
            }
            plan.delete(file, listItemRange(own, parameters.parameters, parameters.parametersSeparators))
            docLine(member, "param", own.identifier?.text)?.let { plan.delete(file, it) }
        }
        // the argument at every call
        if (owner is CSharpLocalFunctionStatement) {
            val file = owner.containingFile as CSharpFile
            val resolver = NativeCSharpResolver(file)
            owner.identifier?.let(resolver::symbolAt)?.let { symbol -> calls += resolver.references(symbol) }
        } else {
            val search = CSharpSolutionSearch.targetOf(owner)
            if (search != null) {
                val session = CSharpSemanticSession(project)
                val all = CSharpSolutionSearch.withHierarchy(project, search, session)
                CSharpSolutionSearch.processUsages(project, all, GlobalSearchScope.projectScope(project), session) { calls += it.leaf; true }
            }
        }
        for (leaf in calls) {
            val arguments = argumentListOf(leaf)
            if (arguments == null) {
                // a method group (`items.Select(Parse)`): its delegate type does not change with the method
                if (owner !is CSharpConstructorDeclaration) conflict(plan, leaf, kindName(Target(Kind.MEMBER, CSharpDeclarationNames.nameElement(owner)?.text ?: "", listOf(owner))) + " as a method group")
                continue
            }
            val receiverShift = if (isExtensionCall(owner, leaf)) 1 else 0
            val position = index - receiverShift
            val named = arguments.arguments.indexOfFirst { it.nameColon?.nameElement?.identifier?.text == parameter.identifier?.text }
            val file = arguments.containingFile
            when {
                named >= 0 -> plan.delete(file, listItemRange(arguments.arguments[named], arguments.arguments, arguments.argumentsSeparators))
                position < 0 -> {}
                isParams && position < arguments.arguments.size && arguments.arguments.drop(position).all { it.nameColon == null } -> {
                    val first = arguments.arguments[position]
                    val last = arguments.arguments.last()
                    val start = if (position > 0) arguments.argumentsSeparators[position - 1].textRange.startOffset else first.textRange.startOffset
                    plan.delete(file, TextRange(start, last.textRange.endOffset))
                }
                position < arguments.arguments.size && arguments.arguments[position].nameColon == null ->
                    plan.delete(file, listItemRange(arguments.arguments[position], arguments.arguments, arguments.argumentsSeparators))
            }
        }
    }

    /** The argument list of the call [leaf] names: `M(…)`, `x.M(…)`, `new T(…)`, `: base(…)` / `: this(…)`; null for anything else. */
    private fun argumentListOf(leaf: PsiElement): CSharpBaseArgumentList? {
        PsiTreeUtil.getParentOfType(leaf, CSharpConstructorInitializer::class.java)?.takeIf { it.thisOrBaseKeyword == leaf }?.let { return it.argumentList }
        var name: PsiElement = leaf.parent ?: return null
        while (name.parent is CSharpQualifiedName || name.parent is CSharpAliasQualifiedName) name = name.parent
        val parent = name.parent
        if (parent is CSharpObjectCreationExpression && parent.type == name) return parent.argumentList
        if (parent is CSharpPrimaryConstructorBaseType && parent.type == name) return parent.argumentList
        var callee: PsiElement = name
        if (parent is CSharpMemberAccessExpression && parent.nameElement == name) callee = parent
        if (parent is CSharpMemberBindingExpression) callee = parent
        val invocation = callee.parent as? CSharpInvocationExpression ?: return null
        return invocation.argumentList.takeIf { invocation.expression == callee }
    }

    /** `items.Twice()` of `static int Twice(this List<int> items)`: the receiver stands for the first parameter. */
    private fun isExtensionCall(owner: PsiElement, leaf: PsiElement): Boolean {
        val first = (owner as? CSharpMethodDeclaration)?.parameterList?.parameters?.firstOrNull() ?: return false
        if (first.modifiers.none { it.text == "this" }) return false
        val name = leaf.parent ?: return false
        return name.parent is CSharpMemberAccessExpression || name.parent is CSharpMemberBindingExpression
    }

    private fun planLocal(plan: Plan) {
        val declarator = plan.target.primary as CSharpVariableDeclarator
        val file = declarator.containingFile as CSharpFile
        val resolver = NativeCSharpResolver(file)
        val what = kindName(plan.target)
        declarator.identifier?.let(resolver::symbolAt)?.let { symbol -> for (use in resolver.references(symbol)) conflict(plan, use, what) }
        val variables = declarator.parent as CSharpVariableDeclaration
        val statement = variables.parent as CSharpLocalDeclarationStatement
        val text = file.viewProvider.contents
        if (variables.variables.size > 1) {
            plan.delete(file, listItemRange(declarator, variables.variables, variables.variablesSeparators))
            return
        }
        // a value with an effect stays as a statement: `var x = Save();` → `Save();`
        val value = declarator.initializer?.value
        if (value != null && statement.usingKeyword == null && hasEffect(value)) {
            plan.replace(file, statement.textRange, "${value.text};")
        } else plan.delete(file, memberRange(text, statement))
    }

    private fun hasEffect(value: CSharpExpression): Boolean {
        var e: PsiElement = value
        while (e is CSharpParenthesizedExpression) e = e.expression ?: return false
        return e is CSharpInvocationExpression || e is CSharpAwaitExpression || e is CSharpAssignmentExpression || e is CSharpBaseObjectCreationExpression
    }

    private fun planLocalFunction(plan: Plan) {
        val function = plan.target.primary as CSharpLocalFunctionStatement
        val file = function.containingFile as CSharpFile
        val resolver = NativeCSharpResolver(file)
        val what = kindName(plan.target)
        function.identifier?.let(resolver::symbolAt)?.let { symbol ->
            for (use in resolver.references(symbol)) if (!inside(use, listOf(function))) conflict(plan, use, what)
        }
        plan.delete(file, memberRange(file.viewProvider.contents, function))
    }

    private fun planTypeParameter(project: Project, plan: Plan) {
        val parameter = plan.target.primary as CSharpTypeParameter
        val list = parameter.parent as? CSharpTypeParameterList ?: return
        val owner = list.parent ?: return
        val file = parameter.containingFile as CSharpFile
        val resolver = NativeCSharpResolver(file)
        val what = kindName(plan.target)
        val index = list.parameters.indexOf(parameter)
        val arity = list.parameters.size
        parameter.identifier?.let(resolver::symbolAt)?.let { symbol ->
            for (use in resolver.references(symbol)) if (!inside(use, constraintsOf(owner).filter { it.nameElement?.identifier?.text == plan.target.name })) conflict(plan, use, what)
        }
        plan.delete(file, if (arity == 1) list.textRange else listItemRange(parameter, list.parameters, list.parametersSeparators))
        constraintsOf(owner).firstOrNull { it.nameElement?.identifier?.text == plan.target.name }?.let { clause ->
            val text = file.viewProvider.contents
            var start = clause.textRange.startOffset
            while (start > 0 && text[start - 1].isWhitespace()) start--
            plan.delete(file, TextRange(start, clause.textRange.endOffset))
        }
        docLine(owner, "typeparam", plan.target.name)?.let { plan.delete(file, it) }
        // `Box<int>` / `Make<int>()`: the type argument of that position goes too
        val search = if (owner is CSharpLocalFunctionStatement) null else CSharpSolutionSearch.targetOf(owner)
        val leaves = ArrayList<PsiElement>()
        if (search != null) {
            CSharpSolutionSearch.processUsages(project, search, GlobalSearchScope.projectScope(project), CSharpSemanticSession(project)) { leaves += it.leaf; true }
        } else if (owner is CSharpLocalFunctionStatement) {
            owner.identifier?.let(resolver::symbolAt)?.let { leaves += resolver.references(it) }
        }
        for (leaf in leaves) {
            val generic = leaf.parent as? CSharpGenericName ?: continue
            val arguments = generic.typeArgumentList ?: continue
            if (arguments.arguments.size != arity || arguments.arguments.any { it is CSharpOmittedTypeArgument }) continue
            val range = if (arity == 1) arguments.textRange else listItemRange(arguments.arguments[index], arguments.arguments, arguments.argumentsSeparators)
            plan.delete(leaf.containingFile, range)
        }
    }

    private fun constraintsOf(owner: PsiElement): List<CSharpTypeParameterConstraintClause> = when (owner) {
        is CSharpTypeDeclaration -> owner.constraintClauses
        is CSharpMethodDeclaration -> owner.constraintClauses
        is CSharpDelegateDeclaration -> owner.constraintClauses
        is CSharpLocalFunctionStatement -> owner.constraintClauses
        else -> emptyList()
    }

    // ---- the ranges

    private fun lineStart(text: CharSequence, offset: Int): Int = if (offset <= 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
    private fun lineEnd(text: CharSequence, offset: Int): Int = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
    private fun lineText(text: CharSequence, start: Int): CharSequence = text.subSequence(start, lineEnd(text, start))
    private fun CharSequence.indexOf(c: Char, from: Int): Int { for (i in from until length) if (this[i] == c) return i; return -1 }
    private fun CharSequence.lastIndexOf(c: Char, from: Int): Int { for (i in from downTo 0) if (this[i] == c) return i; return -1 }

    /**
     * A member or a statement on its own lines: its lines with the `///` lines above it, and one blank line of those around it so that no
     * two blank lines meet and none is left under `{` or over `}`.
     */
    fun memberRange(text: CharSequence, element: PsiElement): TextRange {
        var start = element.textRange.startOffset
        var end = element.textRange.endOffset
        val firstLine = lineStart(text, start)
        if (text.subSequence(firstLine, start).isNotBlank()) return inline(text, start, end)
        start = firstLine
        while (start > 0) {
            val previous = lineStart(text, start - 1)
            if (!lineText(text, previous).trimStart().startsWith("///")) break
            start = previous
        }
        val lastLineEnd = lineEnd(text, end)
        if (text.subSequence(end, lastLineEnd).isNotBlank()) return inline(text, start, end)
        end = if (lastLineEnd < text.length) lastLineEnd + 1 else lastLineEnd
        val before = if (start > 0) lineStart(text, start - 1) else -1
        val beforeBlank = before >= 0 && lineText(text, before).isBlank()
        val afterLine = if (end < text.length) lineText(text, end).toString() else null
        val afterBlank = afterLine != null && afterLine.isBlank()
        val beforeOpens = before >= 0 && run {
            var b = before
            while (b > 0 && lineText(text, b).isBlank()) b = lineStart(text, b - 1)
            lineText(text, b).trimEnd().endsWith("{")
        }
        when {
            beforeBlank && (afterBlank || afterLine == null || afterLine.trim().startsWith("}")) -> start = before
            afterBlank && (before < 0 || beforeOpens) -> end = lineEnd(text, end).let { if (it < text.length) it + 1 else it }
        }
        return TextRange(start, end)
    }

    /** A declaration amid other text on its line: with the spaces after it when spaces stand before it too (no two spaces left). */
    private fun inline(text: CharSequence, start: Int, end: Int): TextRange {
        var to = end
        if (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) while (to < text.length && (text[to] == ' ' || text[to] == '\t')) to++
        return TextRange(start, to)
    }

    /** One item of a comma-separated list with the separator that goes with it: the next one, or the previous one for the last item. */
    private fun listItemRange(item: PsiElement, items: List<PsiElement>, separators: List<PsiElement>): TextRange {
        val index = items.indexOf(item)
        return when {
            index < items.size - 1 -> TextRange(item.textRange.startOffset, items[index + 1].textRange.startOffset)
            index > 0 -> TextRange(separators[index - 1].textRange.startOffset, item.textRange.endOffset)
            separators.isNotEmpty() -> TextRange(item.textRange.startOffset, separators.first().textRange.endOffset)
            else -> item.textRange
        }
    }

    /** [range] widened to whole lines when nothing else is left on them. */
    private fun lineCleanup(text: CharSequence, range: TextRange): TextRange {
        val start = lineStart(text, range.startOffset)
        val end = lineEnd(text, range.endOffset)
        if (text.subSequence(start, range.startOffset).isBlank() && text.subSequence(range.endOffset, end).isBlank()) {
            return TextRange(start, if (end < text.length) end + 1 else end)
        }
        return range
    }

    /** The `/// <param name="x">…` (or `typeparam`) line of the doc comment above [member], when it is on one line. */
    private fun docLine(member: PsiElement, tag: String, name: String?): TextRange? {
        name ?: return null
        val text = member.containingFile.viewProvider.contents
        var line = lineStart(text, member.textRange.startOffset)
        val pattern = Regex("""^\s*///\s*<$tag\s+name\s*=\s*"${Regex.escape(name)}"\s*>.*</$tag>\s*$""")
        // the doc comment may be inside the member's range (leading trivia) or right above it
        val firstLine = lineStart(text, member.textRange.startOffset)
        var start = firstLine
        while (start > 0) {
            val previous = lineStart(text, start - 1)
            if (!lineText(text, previous).trimStart().startsWith("///")) break
            start = previous
        }
        line = start
        while (line < member.textRange.endOffset && line < text.length) {
            val content = lineText(text, line)
            if (!content.trimStart().startsWith("///")) { if (line >= firstLine) break }
            else if (pattern.matches(content)) return TextRange(line, lineEnd(text, line).let { if (it < text.length) it + 1 else it })
            line = lineEnd(text, line) + 1
        }
        return null
    }

    // ---- running

    /** Safe Delete at the caret of [editor]: the search under a progress, the conflicts asked about, the edits in one command. */
    fun invoke(project: Project, editor: Editor, file: CSharpFile) {
        val offset = editor.caretModel.offset
        val target = ReadAction.compute<Target?, RuntimeException> { targetAt(file, offset) }
        if (target == null) {
            com.intellij.refactoring.util.CommonRefactoringUtil.showErrorHint(project, editor, "Cannot perform refactoring.\nThe caret should be on a declaration or a name of one", "Safe Delete", null)
            return
        }
        val plan = ProgressManager.getInstance().runProcessWithProgressSynchronously<Plan, RuntimeException>(
            { ReadAction.compute<Plan, RuntimeException> { plan(project, target) } }, "Looking for usages of '${target.name}'", true, project,
        ) ?: return
        if (!plan.conflicts.isEmpty) {
            if (ApplicationManager.getApplication().isUnitTestMode) {
                if (!BaseRefactoringProcessor.ConflictsInTestsException.isTestIgnore()) throw BaseRefactoringProcessor.ConflictsInTestsException(plan.conflicts.values())
            } else if (!ConflictsDialog(project, plan.conflicts).showAndGet()) return
        }
        if (plan.deletedFiles.isNotEmpty() && !confirmFiles(project, plan)) {
            // the type alone in its file, the file kept: the declaration goes
            for (file in plan.deletedFiles) plan.delete(file, memberRange(file.viewProvider.contents, target.declarations.first { it.containingFile == file }))
            plan.deletedFiles.clear()
        }
        apply(project, plan)
    }

    private fun confirmFiles(project: Project, plan: Plan): Boolean {
        deleteFileAnswerForTests?.let { return it }
        if (ApplicationManager.getApplication().isUnitTestMode) return true
        val names = plan.deletedFiles.joinToString(", ") { it.name }
        return Messages.showYesNoDialog(project, "'${plan.target.name}' is the only type of $names. Delete the file too?", "Safe Delete", Messages.getQuestionIcon()) == Messages.YES
    }

    /** The edits of [plan], from the end of each file (no range moves another), and its files deleted: one undoable command. */
    fun apply(project: Project, plan: Plan) {
        val files = (plan.edits.keys + plan.deletedFiles).toTypedArray()
        WriteCommandAction.writeCommandAction(project, *files).withName("Safe Delete '${plan.target.name}'").run<RuntimeException> {
            val documents = PsiDocumentManager.getInstance(project)
            for ((file, edits) in plan.edits) {
                if (file in plan.deletedFiles) continue
                val document = documents.getDocument(file) ?: continue
                documents.doPostponedOperationsAndUnblockDocument(document)
                var last = Int.MAX_VALUE
                for ((range, text) in edits.distinctBy { it.first }.sortedByDescending { it.first.startOffset }) {
                    if (range.endOffset > last) continue  // inside a range already deleted
                    document.replaceString(range.startOffset, range.endOffset, text)
                    last = range.startOffset
                }
                documents.commitDocument(document)
            }
            for (file in plan.deletedFiles) file.virtualFile?.delete(this)
        }
    }
}

/**
 * Safe Delete (Alt+Delete, Refactor This): the native one in a C# editor ([NativeCSharpSafeDelete]), the platform's everywhere else (the
 * Project view deleting files included — no editor there).
 */
class CSharpSafeDeleteAction : AnAction(), DumbAware {
    private val platform = SafeDeleteAction()

    init {
        templatePresentation.setText(ActionsBundle.actionText(ID))
        templatePresentation.description = ActionsBundle.actionDescription(ID)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun native(e: AnActionEvent): CSharpFile? {
        if (e.getData(CommonDataKeys.EDITOR) == null) return null
        val file = e.getData(CommonDataKeys.PSI_FILE) as? CSharpFile ?: return null
        return file.takeIf { it.compilationUnit != null && NativeCSharpNavigation.serves(it) }
    }

    override fun update(e: AnActionEvent) {
        val file = native(e) ?: return platform.update(e)
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isVisible = true
        e.presentation.isEnabled = project != null && editor != null && !DumbService.isDumb(project) &&
            runCatching { NativeCSharpSafeDelete.targetAt(file, editor.caretModel.offset) != null }.getOrDefault(false)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val file = native(e) ?: return platform.actionPerformed(e)
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        NativeCSharpSafeDelete.invoke(project, editor, file)
    }

    companion object {
        const val ID = "SafeDelete"
    }
}
