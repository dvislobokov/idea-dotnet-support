package io.github.dotnetsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStub

/**
 * `override |` and `partial |` at the start of a member, as Rider completes them (docs/rider-analysis, section 4): the members of the base
 * classes that can be overridden (virtual, abstract or an override, not sealed, static or private) and are not overridden yet — of the
 * solution, of the files the build generates (`Greeter.GreeterBase` of Grpc.Tools) and of the assemblies (`object`, `ControllerBase`,
 * `BackgroundService`, `DbContext`), found by the semantics of Generate ([NativeCSharpInheritedMembers.overrideItems]). The item inserts
 * the whole member — `public override double Area()` with a body: `throw new NotImplementedException();` for an abstract one, the call of
 * `base` for a virtual one (`return base.Describe(x);`, `return await base.X(...)` after a typed `async`), the types written as the file
 * names them and the `using` directives they need added. The accessibility of the base member is put before `override` when none is
 * typed. `partial |`: the partial methods declared without a body in another part of the type, with their signature and an empty body.
 * Without the index of assemblies (no SDK yet) `Equals`, `GetHashCode` and `ToString` of `object` are still offered.
 */
object NativeCSharpOverrides {
    private val ACCESS = setOf("public", "protected", "internal", "private")

    /**
     * One member to write: [header] — the lines before `{` (the whole member when [body] is null: `int Count { get; set; }`), [body] —
     * the lines inside the braces in 4-space levels; [usings]: the namespaces its types need; [base]: the type it comes from.
     */
    class Candidate(
        val name: String, val access: String, val header: String, val body: List<String>?, val tail: String, val type: String?, val property: Boolean,
        val usings: Set<String> = emptySet(), val base: String? = null,
    )

    fun overrides(type: CSharpTypeDeclaration, file: CSharpFile, place: NativeCSharpCompletionPlace): List<LookupElement> =
        overrideCandidates(type, file, place.offset, "async" in place.modifiers).map { element(it, place) }

    fun partialMethods(type: CSharpTypeDeclaration, resolver: NativeCSharpResolver, place: NativeCSharpCompletionPlace): List<LookupElement> =
        partialCandidates(type, resolver).map { element(it, place) }

    /** What `override |` offers in [type] at [offset] of [file]; [async]: `async` is typed, so the base is awaited. */
    fun overrideCandidates(type: CSharpTypeDeclaration, file: CSharpFile, offset: Int, async: Boolean = false): List<Candidate> {
        if (type is CSharpInterfaceDeclaration) return emptyList()
        val site = CSharpGenerateSite.at(file, offset)?.takeIf { it.type == type } ?: return emptyList()
        if (site.isStatic) return emptyList()
        val result = ArrayList<Candidate>()
        for (item in NativeCSharpInheritedMembers(site).overrideItems()) {
            val lines = item.text.lines()
            val open = lines.indexOfFirst { it.trim() == "{" }
            result += if (open <= 0 || lines.last().trim() != "}") {
                // `{ get; set; }` of an abstract property: one line, no block
                Candidate(item.name, item.access, item.text, null, item.tail, item.type, item.property, item.usings, item.base)
            } else {
                val body = lines.subList(open + 1, lines.size - 1).map { it.removePrefix("    ") }.map { if (async) awaited(it, item.type) else it }
                Candidate(item.name, item.access, lines.subList(0, open).joinToString("\n"), body, item.tail, item.type, item.property, item.usings, item.base)
            }
        }
        // no index of assemblies: `object` is not known, its three members still are
        if (site.resolver.libraryType("System.Object") == null) {
            val done = HashSet<String>()
            for (part in site.parts) for (member in part.members) {
                if (member is CSharpMethodDeclaration && member.modifiers.any { it.text == "override" }) member.identifier?.text?.let { done += it }
            }
            for (candidate in OBJECT_MEMBERS) {
                if (site.isRecord && candidate.name != "ToString") continue
                if (candidate.name in done || result.any { it.name == candidate.name }) continue
                result += candidate
            }
        }
        return result
    }

    /** `return base.X(a);` of an `async` override: `return await base.X(a);`, `await base.X(a);` for a plain task. */
    private fun awaited(line: String, type: String?): String {
        if (type == null || !TASK.matches(type)) return line
        val plain = TASK_ONLY.matches(type)
        return when {
            line.startsWith("return base.") -> if (plain) "await " + line.removePrefix("return ") else "return await " + line.removePrefix("return ")
            line.startsWith("base.") -> "await $line"
            else -> line
        }
    }

    private val TASK = Regex("""^(?:System\.Threading\.Tasks\.)?(?:Task|ValueTask)(?:<.+>)?$""")
    private val TASK_ONLY = Regex("""^(?:System\.Threading\.Tasks\.)?(?:Task|ValueTask)$""")

    fun partialCandidates(type: CSharpTypeDeclaration, resolver: NativeCSharpResolver): List<Candidate> {
        val own = resolver.declaredType(type) ?: return emptyList()
        val members = declarations(own).filter { it.kind == SyntaxKind.MethodDeclaration && "partial" in it.modifiers }
        val implemented = members.filter { (it.element() as? CSharpMethodDeclaration)?.let { m -> m.body != null || m.expressionBody != null } == true }.mapTo(HashSet()) { it.key }
        return members.filter { it.key !in implemented }.mapNotNull { member ->
            val method = member.element() as? CSharpMethodDeclaration ?: return@mapNotNull null
            if (method.body != null || method.expressionBody != null) return@mapNotNull null
            val returnType = method.returnType?.text?.let(CSharpStubsText::collapse) ?: return@mapNotNull null
            val name = method.identifier?.text ?: return@mapNotNull null
            val parameters = CSharpStubsText.collapse(method.parameterList?.text ?: "()")
            val typeParameters = method.typeParameterList?.text.orEmpty()
            Candidate(name, access(member.modifiers), "$returnType $name$typeParameters$parameters", listOf(""), parameters, returnType, property = false)
        }
    }

    /** A member declaration of a type part, from its PSI or its stub (the stub keeps the name, modifiers and parameters; the rest is read when written). */
    private class Declared(val kind: Any, val name: String, val modifiers: List<String>, val parameters: String?, val element: () -> PsiElement?) {
        val key: String get() = name + "(" + parameterTypes(parameters) + ")"

        fun element(): PsiElement? = element.invoke()
    }

    private fun declarations(type: TypeInfo): List<Declared> {
        val result = ArrayList<Declared>()
        for (part in type.parts) when (part) {
            is TypePart.Psi -> (part.declaration as? CSharpTypeDeclaration)?.members?.forEach { member ->
                val modifiers = member.modifiers.map { it.text }
                if (member is CSharpMethodDeclaration) member.identifier?.text?.let { result += Declared(SyntaxKind.MethodDeclaration, it, modifiers, member.parameterList?.text) { member } }
            }
            is TypePart.Stub -> for (child in part.stub.childrenStubs) {
                if (child !is CSharpStub || child.elementType != SyntaxKind.MethodDeclaration) continue
                result += Declared(SyntaxKind.MethodDeclaration, child.name ?: continue, child.modifiers, child.parameters) { child.psi }
            }
        }
        return result
    }

    /** `(int count, string? name = null)` → `int,string?`: overloads told apart by their parameter types. */
    private fun parameterTypes(parameters: String?): String {
        if (parameters == null) return ""
        val inner = parameters.trim().removePrefix("(").removePrefix("[").removeSuffix(")").removeSuffix("]")
        return CSharpScopeNames.splitTopLevel(inner).joinToString(",") { parameter ->
            val head = parameter.substringBefore('=').trim().split(Regex("""\s+""")).filter { it !in setOf("ref", "out", "in", "params", "this", "scoped", "readonly") }
            if (head.size >= 2) head.dropLast(1).joinToString("") else head.joinToString("")
        }
    }

    private fun access(modifiers: List<String>): String = modifiers.filter { it in ACCESS }.joinToString(" ")

    private val OBJECT_MEMBERS: List<Candidate> = listOf(
        Candidate("Equals", "public", "bool Equals(object? obj)", listOf("return base.Equals(obj);"), "(object? obj)", "bool", property = false),
        Candidate("GetHashCode", "public", "int GetHashCode()", listOf("return base.GetHashCode();"), "()", "int", property = false),
        Candidate("ToString", "public", "string? ToString()", listOf("return base.ToString();"), "()", "string?", property = false),
    )

    private fun element(candidate: Candidate, place: NativeCSharpCompletionPlace): LookupElement {
        val tail = candidate.tail + " { ... }"
        var builder = LookupElementBuilder.create(candidate.name).withIcon(if (candidate.property) AllIcons.Nodes.Property else AllIcons.Nodes.Method)
            .withTailText(tail + (candidate.base?.let { " ($it)" } ?: ""), true).bold()
        if (candidate.type != null) builder = builder.withTypeText(candidate.type)
        builder = builder.withInsertHandler(InsertHandler { context, _ -> insert(context, candidate, place) })
        builder.putUserData(NativeCSharpCompletion.NATIVE, true)
        // the members of the real base above those of `object` (SayHello before Equals)
        val priority = NativeCSharpCompletion.DECLARATION + if (candidate.base != null && candidate.base != "object") 1.0 else 0.0
        return PrioritizedLookupElement.withPriority(builder, priority).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
    }

    /**
     * The member in place of the typed name: the header, `{`, the body indented by the code style, `}` (Allman, as the defaults of .NET);
     * the caret at the end of the body's first line. The accessibility of the base member before the modifiers when none was typed; the
     * `using` directives of its types.
     */
    fun insert(context: InsertionContext, candidate: Candidate, place: NativeCSharpCompletionPlace?, from: Int = context.startOffset) {
        val document = context.document
        val text = document.charsSequence
        val start = from
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val indent = text.subSequence(lineStart, start).takeWhile { it == ' ' || it == '\t' }.toString()
        val options = CodeStyle.getIndentOptions(context.file)
        val unit = if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE.coerceAtLeast(1))
        val header = NativeCSharpGenerateEdits.reindent(candidate.header, indent, unit).removePrefix(indent)
        val body = candidate.body
        val member = if (body == null) header else {
            val lines = body.joinToString("\n") { if (it.isEmpty()) "$indent$unit" else NativeCSharpGenerateEdits.reindent(it, "$indent$unit", unit) }
            "$header\n$indent{\n$lines\n$indent}"
        }
        // a `;` or `{ }` the list was opened before stays after the member
        document.replaceString(start, context.tailOffset, member)
        var caret = if (body == null) start + member.length else {
            val first = start + header.length + 1 + indent.length + 2
            first + (document.charsSequence.indexOf('\n', first).takeIf { it >= 0 }?.minus(first) ?: 0)
        }
        val typedAccess = place?.modifiers?.any { it in ACCESS } == true
        if (!typedAccess && candidate.access.isNotEmpty()) {
            // before the first typed modifier: `override` / `partial` and the others stay as typed
            val firstModifier = firstModifierOffset(document.charsSequence, lineStart, start)
            if (firstModifier != null) {
                document.insertString(firstModifier, candidate.access + " ")
                caret += candidate.access.length + 1
            }
        }
        val file = PsiDocumentManager.getInstance(context.project).getPsiFile(document) as? CSharpFile
        for (namespace in candidate.usings) {
            val current = document.charsSequence
            if (CSharpUsings.isVisible(namespace, current) || file != null && NativeCSharpGenerateEdits.globallyImported(file, namespace)) continue
            val insertion = CSharpUsings.insertion(current, namespace) ?: continue
            document.insertString(insertion.offset, insertion.text)
            if (insertion.offset <= caret) caret += insertion.text.length
        }
        context.editor.caretModel.moveToOffset(caret)
        context.commitDocument()
    }

    /** Where the modifiers before [nameStart] begin on its line. */
    private fun firstModifierOffset(text: CharSequence, lineStart: Int, nameStart: Int): Int? {
        var i = lineStart
        while (i < nameStart && (text[i] == ' ' || text[i] == '\t')) i++
        return if (i < nameStart) i else null
    }
}
