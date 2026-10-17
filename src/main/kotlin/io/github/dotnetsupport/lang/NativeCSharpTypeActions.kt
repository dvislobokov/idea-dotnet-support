package io.github.dotnetsupport.lang

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetConfigurationType
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.testing.TestTarget
import io.github.dotnetsupport.testing.testTargets
import org.jetbrains.annotations.TestOnly

/**
 * The edits of Rider's context actions on members and types (0.1.173, the comparison with Rider on Jellyfin № 28, 42, 43, 56): the
 * `<exception>` documentation of what a member throws, `using static` for the statics of a type, a derived type with the members to
 * implement, a copy of a type in a new file, a method inlined at a call or at all its calls. Pure functions on the tree and the text.
 */
object NativeCSharpTypeEdits {
    private fun present(token: PsiElement?): Boolean = (token?.textLength ?: 0) > 0

    fun lineStart(text: CharSequence, offset: Int): Int = if (offset <= 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    fun lineEnd(text: CharSequence, offset: Int): Int = text.indexOf('\n', offset).let { if (it < 0) text.length else it }

    /** Where [member] begins, its attributes included, comments and whitespace before them left out. */
    fun memberStart(member: PsiElement): Int =
        generateSequence(member.firstChild) { it.nextSibling }.firstOrNull { it !is PsiWhiteSpace && it !is PsiComment && it.textLength > 0 }?.textRange?.startOffset
            ?: member.textRange.startOffset

    /** The start of the `///` lines right above [lineStart] (that line itself when there are none). */
    fun docStart(text: CharSequence, lineStart: Int): Int {
        var top = lineStart
        while (top > 0) {
            val previous = lineStart(text, top - 1)
            if (text.subSequence(previous, top - 1).trim().startsWith("///")) top = previous else break
        }
        return top
    }

    // ---- Add exception documentation

    class ExceptionDoc(val member: CSharpMemberDeclaration, val cref: String)

    /** `throw new X(…)` at [offset], thrown out of a member (not out of a lambda or a local function): the member and the `cref` of `X`. */
    fun exceptionAt(file: PsiFile, offset: Int): ExceptionDoc? {
        val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
        val thrower = PsiTreeUtil.getParentOfType(leaf, CSharpThrowStatement::class.java, CSharpThrowExpression::class.java) ?: return null
        val thrown = when (thrower) {
            is CSharpThrowStatement -> thrower.expression
            is CSharpThrowExpression -> thrower.expression
            else -> null
        }
        val creation = NativeCSharpContextEdits.unwrap(thrown) as? CSharpObjectCreationExpression ?: return null
        val type = creation.type?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var current: PsiElement? = thrower.parent
        while (current != null && current !is CSharpMemberDeclaration) {
            if (current is CSharpAnonymousFunctionExpression || current is CSharpLocalFunctionStatement) return null
            current = current.parent
        }
        val member = current as? CSharpMemberDeclaration ?: return null
        if (member is CSharpBaseTypeDeclaration || member is CSharpBaseNamespaceDeclaration) return null
        return ExceptionDoc(member, type.replace('<', '{').replace('>', '}').removePrefix("global::"))
    }

    /** `/// <exception cref="X"></exception>` after the documentation of the member (alone when it has none); null when `X` is documented. */
    fun addExceptionDoc(doc: ExceptionDoc, text: CharSequence): CSharpTextEdit? {
        val start = memberStart(doc.member)
        val line = lineStart(text, start)
        val indent = text.subSequence(line, start).takeIf { it.isBlank() }?.toString() ?: return null
        val existing = text.subSequence(docStart(text, line), line)
        val simple = doc.cref.substringAfterLast('.')
        if (Regex("""<exception\s+cref\s*=\s*"(?:T:)?(?:[\w.{}]*\.)?${Regex.escape(simple)}"""").containsMatchIn(existing)) return null
        val tag = "$indent/// <exception cref=\"${doc.cref}\">"
        return CSharpTextEdit(TextRange(line, line), "$tag</exception>\n", tag.length)
    }

    // ---- Import static members

    /** `using static [typeName];` and the member accesses of the file to write without their type. */
    class StaticImport(val typeName: String, val uses: List<CSharpMemberAccessExpression>)

    /** The access to a static member of a type at [offset] (`Math.Max`, `string.Format`) and every such access of the file to the same type. */
    fun staticImportAt(file: CSharpFile, offset: Int, r: CSharpNameResolver): StaticImport? {
        val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
        val access = generateSequence(leaf) { it.parent }.takeWhile { it !is CSharpStatement && it !is CSharpMemberDeclaration }
            .filterIsInstance<CSharpMemberAccessExpression>().firstOrNull { staticTypeName(it, r) != null } ?: return null
        if (PsiTreeUtil.getParentOfType(access, CSharpUsingDirective::class.java) != null) return null
        val typeName = staticTypeName(access, r) ?: return null
        val uses = PsiTreeUtil.findChildrenOfType(file, CSharpMemberAccessExpression::class.java)
            .filter { PsiTreeUtil.getParentOfType(it, CSharpUsingDirective::class.java) == null && staticTypeName(it, r) == typeName && !clashes(it) }
        if (access !in uses) return null
        return StaticImport(typeName, uses)
    }

    /** The full name of the type [access] names a static member of (`System.Math`); null for a value, a generic type, a nested type. */
    private fun staticTypeName(access: CSharpMemberAccessExpression, r: CSharpNameResolver): String? {
        val qualifier = access.expression ?: return null
        val type = (r.qualifier(qualifier) as? CSharpNameResolver.Qualifier.Type)?.type ?: return null
        val name = r.definitionName(type)?.takeIf { '`' !in it && it != "[]" }?.replace('+', '.') ?: return null
        val leaf = (access.nameElement as? CSharpSimpleName)?.identifier ?: return null
        val symbols = r.resolve(leaf)?.symbols?.takeIf { it.isNotEmpty() } ?: return null
        return if (symbols.all(::isStaticMember)) name else null
    }

    private fun isStaticMember(symbol: CSharpSymbol): Boolean = when (symbol) {
        is CSharpSymbol.LibraryMember -> symbol.member.isStatic || symbol.member.kind == IndexedMemberKind.CONSTANT || symbol.member.kind == IndexedMemberKind.ENUM_MEMBER
        is CSharpSymbol.SourceMember -> {
            val owner = PsiTreeUtil.getParentOfType(symbol.element, CSharpMemberDeclaration::class.java, false)
            owner is CSharpEnumMemberDeclaration || owner != null && owner !is CSharpBaseTypeDeclaration && owner.modifiers.any { it.text == "static" || it.text == "const" }
        }
        else -> false
    }

    /** A member of the same name in a type around [access]: the bare name would mean that one. */
    private fun clashes(access: CSharpMemberAccessExpression): Boolean {
        val name = (access.nameElement as? CSharpSimpleName)?.identifier?.text ?: return true
        return generateSequence<PsiElement>(access) { it.parent }.filterIsInstance<CSharpTypeDeclaration>().any { type ->
            type.members.any { member -> memberNames(member).contains(name) }
        }
    }

    private fun memberNames(member: CSharpMemberDeclaration): List<String> = when (member) {
        is CSharpMethodDeclaration -> listOfNotNull(member.identifier?.text)
        is CSharpBaseFieldDeclaration -> member.declaration?.variables.orEmpty().mapNotNull { it.identifier?.text }
        is CSharpBaseTypeDeclaration -> listOfNotNull(member.identifier?.text)
        is CSharpPropertyDeclaration -> listOfNotNull(member.identifier?.text)
        is CSharpEventDeclaration -> listOfNotNull(member.identifier?.text)
        else -> emptyList()
    }

    fun importStatic(import: StaticImport, text: CharSequence): CSharpEdits {
        val edits = ArrayList<Pair<TextRange, String>>()
        for (use in import.uses) edits += use.textRange to (use.nameElement?.text ?: continue)
        if (import.typeName !in CSharpUsings.importedStatically(text)) {
            CSharpUsings.insertion(text, "static ${import.typeName}")?.let { edits += TextRange(it.offset, it.offset) to it.text }
        }
        return edits
    }

    // ---- Inline Method

    /** The call [call] of a method of the same type with the text that replaces it: the method's value, or its statements. */
    class InlineCall(val call: CSharpInvocationExpression, val method: CSharpMethodDeclaration, val range: TextRange, val text: String)

    /** The name called at [offset] (`Total(…)`, `this.Total(…)`), not a place in the arguments. */
    fun callAt(file: PsiFile, offset: Int): CSharpInvocationExpression? {
        val leaf = file.findElementAt(offset) ?: return null
        val call = PsiTreeUtil.getParentOfType(leaf, CSharpInvocationExpression::class.java, false) ?: return null
        val name = calleeName(call) ?: return null
        return call.takeIf { name.textRange.startOffset <= offset && offset <= name.textRange.endOffset }
    }

    private fun calleeName(call: CSharpInvocationExpression): CSharpIdentifierName? = when (val callee = call.expression) {
        is CSharpIdentifierName -> callee
        is CSharpMemberAccessExpression -> (callee.nameElement as? CSharpIdentifierName)?.takeIf { callee.expression is CSharpThisExpression }
        else -> null
    }

    /** The method [call] calls, when it can be inlined there (a method of the same type, its value one expression or its statements a call's). */
    fun inlineCall(call: CSharpInvocationExpression, r: CSharpNameResolver, text: CharSequence): InlineCall? {
        val name = calleeName(call)?.identifier ?: return null
        val method = (r.resolve(name)?.single as? CSharpSymbol.SourceMember)?.element as? CSharpMethodDeclaration ?: return null
        if (method.containingFile != call.containingFile || method.parent != PsiTreeUtil.getParentOfType(call, CSharpTypeDeclaration::class.java)) return null
        if (method.typeParameterList != null || PsiTreeUtil.isAncestor(method, call, false)) return null
        if (method.modifiers.any { it.text in setOf("virtual", "abstract", "override", "extern", "partial", "async") }) return null
        if (PsiTreeUtil.findChildOfAnyType(method, CSharpYieldStatement::class.java, CSharpAwaitExpression::class.java) != null) return null
        val parameters = method.parameterList?.parameters.orEmpty()
        if (parameters.any { it.modifiers.isNotEmpty() }) return null
        val arguments = call.argumentList?.arguments.orEmpty()
        if (arguments.size > parameters.size || arguments.any { it.nameColon != null || it.refKindKeyword != null }) return null
        val values = parameters.mapIndexed { i, p -> arguments.getOrNull(i)?.expression ?: p.default?.value ?: return null }
        val block = method.body
        val value = method.expressionBody?.expression ?: (block?.statements?.singleOrNull() as? CSharpReturnStatement)?.expression
        val statements = if (value == null && method.returnType?.text == "void" && block != null &&
            PsiTreeUtil.findChildOfType(block, CSharpReturnStatement::class.java) == null) block.statements.takeIf { it.isNotEmpty() } else null
        val body: TextRange = value?.textRange ?: statements?.let { TextRange(it.first().textRange.startOffset, it.last().textRange.endOffset) } ?: return null
        // the parameters' uses in the body get the arguments; an argument that does something is used exactly once
        val locals = NativeCSharpResolver(method.containingFile as CSharpFile)
        val replacements = ArrayList<Pair<TextRange, String>>()
        for ((i, parameter) in parameters.withIndex()) {
            val symbol = parameter.identifier?.let(locals::symbolAt) ?: return null
            if (symbol.isWritten) return null
            val uses = locals.references(symbol).filter { body.contains(it.textRange) }
            val argument = values[i]
            if (uses.size != 1 && !sideEffectFree(argument)) return null
            val written = if (atomic(argument)) argument.text else "(${argument.text})"
            uses.forEach { replacements += it.textRange to written }
        }
        var substituted = text.subSequence(body.startOffset, body.endOffset).toString()
        for ((range, written) in replacements.sortedByDescending { it.first.startOffset }) {
            val from = range.startOffset - body.startOffset
            substituted = substituted.substring(0, from) + written + substituted.substring(from + range.length)
        }
        if (value != null) {
            val wrapped = if (atomic(value) || wholeSlot(call)) substituted else "($substituted)"
            return InlineCall(call, method, call.textRange, wrapped)
        }
        // a `void` method of statements: in place of the statement that calls it, re-indented
        val statement = call.parent as? CSharpExpressionStatement ?: return null
        val from = NativeCSharpUsingEdits.indentOf(text, body.startOffset).orEmpty()
        val to = NativeCSharpUsingEdits.indentOf(text, statement.textRange.startOffset).orEmpty()
        val lines = substituted.lines().mapIndexed { i, line -> if (i > 0 && line.startsWith(from)) to + line.substring(from.length) else line }
        return InlineCall(call, method, statement.textRange, lines.joinToString("\n"))
    }

    /** The private method declared at [offset] whose every use in its type is a call that [inlineCall] can inline. */
    fun allCallsAt(file: CSharpFile, offset: Int, r: CSharpNameResolver, text: CharSequence): List<InlineCall>? {
        val leaf = file.findElementAt(offset) ?: return null
        val method = PsiTreeUtil.getParentOfType(leaf, CSharpMethodDeclaration::class.java) ?: return null
        val header = method.body?.textRange?.startOffset ?: method.expressionBody?.textRange?.startOffset ?: return null
        if (offset >= header) return null
        val type = method.parent as? CSharpTypeDeclaration ?: return null
        if (type.modifiers.any { it.text == "partial" }) return null
        val access = method.modifiers.map { it.text }
        if (access.any { it in setOf("public", "protected", "internal") }) return null
        val name = method.identifier?.text ?: return null
        val uses = PsiTreeUtil.findChildrenOfType(type, CSharpIdentifierName::class.java).filter { use ->
            use.identifier?.text == name && (r.resolve(use.identifier!!)?.single as? CSharpSymbol.SourceMember)?.element == method
        }
        if (uses.isEmpty()) return null
        return uses.map { use ->
            val call = (use.parent as? CSharpInvocationExpression)?.takeIf { it.expression == use }
                ?: ((use.parent as? CSharpMemberAccessExpression)?.parent as? CSharpInvocationExpression)?.takeIf { (it.expression as? CSharpMemberAccessExpression)?.nameElement == use }
                ?: return null
            inlineCall(call, r, text) ?: return null
        }
    }

    /** Every call replaced, the method removed with its documentation and the blank line before it. */
    fun inlineAll(calls: List<InlineCall>, text: CharSequence): CSharpEdits {
        val method = calls.first().method
        val start = docStart(text, lineStart(text, memberStart(method)))
        var removeFrom = start
        val previous = lineStart(text, (start - 1).coerceAtLeast(0))
        if (start > 0 && text.subSequence(previous, start).isBlank()) removeFrom = previous
        val end = lineEnd(text, method.textRange.endOffset).let { if (it < text.length) it + 1 else it }
        return calls.map { it.range to it.text } + (TextRange(removeFrom, end) to "")
    }

    private fun atomic(e: CSharpExpression): Boolean = when (e) {
        is CSharpSimpleName, is CSharpMemberAccessExpression, is CSharpInvocationExpression, is CSharpElementAccessExpression, is CSharpLiteralExpression,
        is CSharpParenthesizedExpression, is CSharpThisExpression, is CSharpBaseExpression, is CSharpTypeOfExpression, is CSharpDefaultExpression,
        is CSharpInterpolatedStringExpression, is CSharpObjectCreationExpression -> true
        else -> false
    }

    private fun wholeSlot(call: CSharpExpression): Boolean = when (val parent = call.parent) {
        is CSharpArgument, is CSharpEqualsValueClause, is CSharpReturnStatement, is CSharpExpressionStatement, is CSharpArrowExpressionClause, is CSharpParenthesizedExpression -> true
        is CSharpAssignmentExpression -> parent.right == call
        else -> false
    }

    private fun sideEffectFree(value: CSharpExpression): Boolean =
        PsiTreeUtil.findChildrenOfType(value, CSharpExpression::class.java).plus(value).none { e ->
            e is CSharpInvocationExpression || e is CSharpAwaitExpression || e is CSharpAssignmentExpression || e is CSharpElementAccessExpression ||
                e is CSharpBaseObjectCreationExpression || e is CSharpAnonymousObjectCreationExpression || e is CSharpArrayCreationExpression ||
                (e is CSharpPrefixUnaryExpression || e is CSharpPostfixUnaryExpression) && e.text.let { "++" in it || "--" in it }
        }

    // ---- Create derived type, Copy type

    /** The caret on the header of a class (not static or sealed) or an interface of [file], not generic. */
    fun typeHeaderAt(file: PsiFile, offset: Int): CSharpTypeDeclaration? {
        val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
        val type = PsiTreeUtil.getParentOfType(leaf, CSharpTypeDeclaration::class.java, false) ?: return null
        val open = type.openBraceToken?.takeIf(::present) ?: return null
        if (offset < memberStart(type) || offset > open.textRange.startOffset || type.identifier == null) return null
        return type
    }

    /** A free name of [file] for a type: [wanted], else [wanted] with a number. */
    fun freeName(text: CharSequence, wanted: String): String {
        fun taken(name: String) = Regex("""\b(class|struct|interface|record|enum|delegate)\s+${Regex.escape(name)}\b""").containsMatchIn(text)
        if (!taken(wanted)) return wanted
        return (1..99).map { "$wanted$it" }.first { !taken(it) }
    }

    /** `public class OrderService : IOrderService { }` after [type]: the name without the `I` of an interface, `…Impl` otherwise. */
    fun derivedType(type: CSharpTypeDeclaration, text: CharSequence): Pair<Int, String>? {
        if (type.typeParameterList != null) return null
        val interfaceType = type is CSharpInterfaceDeclaration
        if (!interfaceType && type !is CSharpClassDeclaration) return null
        val modifiers = type.modifiers.map { it.text }
        if ("static" in modifiers || "sealed" in modifiers) return null
        if (!interfaceType) {
            val constructors = type.members.filterIsInstance<CSharpConstructorDeclaration>().filter { c -> c.modifiers.none { it.text == "static" } }
            if (constructors.isNotEmpty() && constructors.none { c -> c.modifiers.none { it.text == "private" } && c.parameterList?.parameters.orEmpty().all { it.default != null } }) return null
        }
        val name = type.identifier?.text ?: return null
        val wanted = if (interfaceType && name.length > 1 && name[0] == 'I' && name[1].isUpperCase()) name.substring(1) else name + "Impl"
        val derived = freeName(text, wanted)
        val indent = NativeCSharpUsingEdits.indentOf(text, memberStart(type)).orEmpty()
        val access = modifiers.firstOrNull { it == "public" || it == "internal" }?.let { "$it " }.orEmpty()
        val end = (type.closeBraceToken?.takeIf(::present) ?: return null).textRange.endOffset.let { e ->
            type.semicolonToken?.takeIf(::present)?.textRange?.endOffset?.takeIf { it > e } ?: e
        }
        return end to "\n\n$indent${access}class $derived : $name\n$indent{\n$indent}"
    }

    /** The text of a file holding a copy of the top-level [type] named [copy]: the file's `using` directives and namespace around it. */
    fun copyFileText(type: CSharpTypeDeclaration, copy: String, text: CharSequence): String? {
        val name = type.identifier?.text ?: return null
        val namespace = type.parent as? CSharpBaseNamespaceDeclaration
        if (namespace == null && type.parent !is CSharpCompilationUnit) return null
        val unit = PsiTreeUtil.getParentOfType(type, CSharpCompilationUnit::class.java) ?: return null
        val usings = (unit.usings + namespace?.usings.orEmpty()).joinToString("\n") { it.text.trim() }
        val start = docStart(text, lineStart(text, memberStart(type)))
        val indent = NativeCSharpUsingEdits.indentOf(text, memberStart(type)).orEmpty()
        val renamed = text.subSequence(start, type.textRange.endOffset).toString().lines()
            .joinToString("\n") { if (it.startsWith(indent)) it.substring(indent.length) else it.trimStart() }
            .replace(Regex("""\b${Regex.escape(name)}\b"""), copy)
        val head = if (usings.isEmpty()) "" else "$usings\n\n"
        return when (namespace) {
            is CSharpFileScopedNamespaceDeclaration -> "${head}namespace ${namespace.nameElement?.text};\n\n$renamed\n"
            null -> "$head$renamed\n"
            else -> "${head}namespace ${namespace.nameElement?.text}\n{\n" + renamed.lines().joinToString("\n") { if (it.isBlank()) "" else "    $it" } + "\n}\n"
        }
    }

    /** One edit standing for [edits] (the whole span between the first and the last, the text in between kept), for the availability check. */
    fun merged(edits: CSharpEdits, text: CharSequence): CSharpTextEdit? {
        if (edits.isEmpty()) return null
        val start = edits.minOf { it.first.startOffset }
        val end = edits.maxOf { it.first.endOffset }
        var result = text.subSequence(start, end).toString()
        for ((range, replacement) in edits.sortedByDescending { it.first.startOffset }) {
            result = result.substring(0, range.startOffset - start) + replacement + result.substring(range.endOffset - start)
        }
        return CSharpTextEdit(TextRange(start, end), result)
    }
}

/** Alt+Enter on `throw new X(…)` in a member: `/// <exception cref="X"></exception>` in its documentation (Rider: "Add exception documentation"). */
class NativeCSharpAddExceptionDocumentationIntention : NativeCSharpContextAction("Add exception documentation") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpTypeEdits.exceptionAt(file, editor.caretModel.offset)?.let { NativeCSharpTypeEdits.addExceptionDoc(it, editor.document.charsSequence) }
}

/** Alt+Enter on `Math.Max(…)`: `using static System.Math;` and every static member of the type in the file without its name (Rider: "Import static members"). */
class NativeCSharpImportStaticMembersIntention : NativeCSharpContextAction("Import static members", needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val text = editor.document.charsSequence
        val import = NativeCSharpTypeEdits.staticImportAt(file, editor.caretModel.offset, resolver(file)) ?: return null
        return NativeCSharpTypeEdits.merged(NativeCSharpTypeEdits.importStatic(import, text), text)
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        val import = NativeCSharpTypeEdits.staticImportAt(file, editor.caretModel.offset, resolver(file)) ?: return
        NativeCSharpContextEdits.apply(editor, NativeCSharpTypeEdits.importStatic(import, editor.document.charsSequence), editor.caretModel.offset)
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
    }
}

/** Alt+Enter (and Refactor This) on a call of a method of the same type: its value or its statements in place of the call (Rider: "Inline Method"). */
class NativeCSharpInlineMethodIntention : NativeCSharpContextAction("Inline Method", needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val call = NativeCSharpTypeEdits.callAt(file, editor.caretModel.offset) ?: return null
        return NativeCSharpTypeEdits.inlineCall(call, resolver(file), editor.document.charsSequence)?.let { CSharpTextEdit(it.range, it.text) }
    }
}

/** Alt+Enter (and Refactor This) on the header of a private method: every call inlined and the method removed (Rider's "Inline Method", all usages). */
class NativeCSharpInlineMethodAllIntention : NativeCSharpContextAction("Inline Method", needsTypes = true) {
    override fun getFamilyName(): String = "Inline Method (all calls)"

    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val text = editor.document.charsSequence
        val calls = NativeCSharpTypeEdits.allCallsAt(file, editor.caretModel.offset, resolver(file), text) ?: return null
        return NativeCSharpTypeEdits.merged(NativeCSharpTypeEdits.inlineAll(calls, text), text)
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        val text = editor.document.charsSequence
        val calls = NativeCSharpTypeEdits.allCallsAt(file, editor.caretModel.offset, resolver(file), text) ?: return
        NativeCSharpContextEdits.apply(editor, NativeCSharpTypeEdits.inlineAll(calls, text), calls.first().range.startOffset)
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
    }
}

/**
 * Alt+Enter on the header of a class or an interface: a class deriving from it right after it, with the members it has to implement or
 * override written by the "Missing members" generator, the caret on its name (Rider: "Create derived type").
 */
class NativeCSharpCreateDerivedTypeIntention : NativeCSharpContextAction("Create derived type", needsTypes = true) {
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val type = NativeCSharpTypeEdits.typeHeaderAt(file, editor.caretModel.offset) ?: return null
        val (offset, text) = NativeCSharpTypeEdits.derivedType(type, editor.document.charsSequence) ?: return null
        return CSharpTextEdit(TextRange(offset, offset), text)
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        val type = NativeCSharpTypeEdits.typeHeaderAt(file, editor.caretModel.offset) ?: return
        val document = editor.document
        val (offset, inserted) = NativeCSharpTypeEdits.derivedType(type, document.charsSequence) ?: return
        document.insertString(offset, inserted)
        val documents = PsiDocumentManager.getInstance(project)
        documents.commitDocument(document)
        val nameOffset = offset + inserted.indexOf("class ") + "class ".length
        val derived = file.findElementAt(nameOffset)?.let { PsiTreeUtil.getParentOfType(it, CSharpTypeDeclaration::class.java, false) }
        editor.caretModel.moveToOffset(nameOffset)
        if (derived == null || DumbService.isDumb(project)) return
        val site = runCatching { CSharpGenerateSite.of(derived, CSharpSemanticSession(project).resolver(file)) }.getOrNull() ?: return
        val choices = runCatching { NativeCSharpGenerate.choices(CSharpGenerator.MISSING_MEMBERS, site) }.getOrDefault(emptyList())
        if (choices.isEmpty()) return
        val code = NativeCSharpGenerate.generate(CSharpGenerator.MISSING_MEMBERS, site, choices) ?: return
        val result = NativeCSharpGenerateEdits.apply(document.text, site, code, NativeCSharpContextEdits.unit(file))
        var caret = nameOffset
        for ((at, text) in result.steps) {
            document.insertString(at, text)
            if (at <= caret) caret += text.length
        }
        editor.caretModel.moveToOffset(caret)
        documents.commitDocument(document)
    }
}

/** Alt+Enter on the header of a top-level type: a copy named `Name1` in a new file of the same folder, opened (Rider: "Copy type…", without its dialog). */
class NativeCSharpCopyTypeIntention : IntentionAction, PriorityAction, DumbAware {
    override fun getText(): String = "Copy type"
    override fun getFamilyName(): String = text
    override fun startInWriteAction(): Boolean = true
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.LOW
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    private fun type(editor: Editor, file: PsiFile): CSharpTypeDeclaration? {
        if (file !is CSharpFile || file.compilationUnit == null || file.containingDirectory == null) return null
        return NativeCSharpTypeEdits.typeHeaderAt(file, editor.caretModel.offset)?.takeIf { it.parent is CSharpBaseNamespaceDeclaration || it.parent is CSharpCompilationUnit }
    }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = editor != null && file != null && type(editor, file) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
        val type = type(editor, file) ?: return
        val directory = file.containingDirectory ?: return
        val name = type.identifier?.text ?: return
        val copy = (1..99).map { "$name$it" }.first { directory.findFile("$it.cs") == null && !Regex("""\b$it\b""").containsMatchIn(file.text) }
        val content = NativeCSharpTypeEdits.copyFileText(type, copy, editor.document.charsSequence) ?: return
        val created = directory.add(PsiFileFactory.getInstance(project).createFileFromText("$copy.cs", CSharpFileType, content)) as? PsiFile ?: return
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val virtualFile = created.virtualFile ?: return
        ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) OpenFileDescriptor(project, virtualFile).navigate(true) }
    }
}

/**
 * Alt+Enter on the header of a test method or class: the test run with coverage, as the "Run Selected Tests with Coverage" of the Unit
 * Tests window does (Rider: "Cover" among Run / Debug of a test).
 */
class NativeCSharpCoverTestIntention : IntentionAction, PriorityAction, DumbAware {
    override fun getText(): String = "Cover"
    override fun getFamilyName(): String = text
    override fun startInWriteAction(): Boolean = false
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.HIGH
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    /** The test whose name is on the caret's line. */
    fun target(editor: Editor, file: PsiFile): TestTarget? {
        if (file !is CSharpFile) return null
        val document = editor.document
        val line = document.getLineNumber(editor.caretModel.offset)
        return testTargets(file).firstOrNull { document.getLineNumber(it.nameRange.startOffset) == line }
    }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file != null && target(editor, file) != null && file.virtualFile?.let(DotNetProjects::findOwningProject) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
        val target = target(editor, file) ?: return
        val projectFile = file.virtualFile?.let(DotNetProjects::findOwningProject) ?: return
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration("${target.displayName} with Coverage", DotNetConfigurationType.instance.factory)
        (settings.configuration as DotNetRunConfiguration).options.apply {
            projectPath = projectFile.path
            command = DotNetCommand.TEST
            testFilter = target.filter
            collectCoverage = true
        }
        runManager.setTemporaryConfiguration(settings)
        val run = runForTests
        if (run != null) run(settings) else ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    companion object {
        /** Receives the configuration instead of running it, in tests. */
        @TestOnly
        @Volatile
        var runForTests: ((RunnerAndConfigurationSettings) -> Unit)? = null
    }
}
