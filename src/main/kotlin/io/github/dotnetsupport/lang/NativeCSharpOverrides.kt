package io.github.dotnetsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStub

/**
 * `override |` and `partial |` at the start of a member, as Rider completes them (docs/rider-analysis, section 4): the members of the base
 * classes of the solution that can be overridden (virtual, abstract or an override, not sealed, static or private) and are not overridden
 * yet, plus `Equals`, `GetHashCode` and `ToString` of `object`; the item inserts the whole member — `public override double Area()` with a
 * body: `throw new NotImplementedException();` for an abstract one, the call of `base` for a virtual one (`return base.Describe(x);`). The
 * accessibility of the base member is put before `override` when none is typed. `partial |`: the partial methods declared without a body
 * in another part of the type, with their signature and an empty body. Bases from referenced assemblies are not seen (stubs of the
 * solution only): their overrides are the server's items, which the merge keeps.
 */
object NativeCSharpOverrides {
    private val ACCESS = setOf("public", "protected", "internal", "private")

    /** One member to write: its name, the header after the modifiers and the body lines. */
    class Candidate(val name: String, val access: String, val header: String, val body: List<String>, val tail: String, val type: String?, val property: Boolean)

    fun overrides(type: CSharpTypeDeclaration, resolver: NativeCSharpResolver, place: NativeCSharpCompletionPlace): List<LookupElement> =
        overrideCandidates(type, resolver).map { element(it, place) }

    fun partialMethods(type: CSharpTypeDeclaration, resolver: NativeCSharpResolver, place: NativeCSharpCompletionPlace): List<LookupElement> =
        partialCandidates(type, resolver).map { element(it, place) }

    fun overrideCandidates(type: CSharpTypeDeclaration, resolver: NativeCSharpResolver): List<Candidate> {
        val own = resolver.declaredType(type) ?: return emptyList()
        val done = HashSet<String>()
        for (member in declarations(own)) if ("override" in member.modifiers) done += member.key
        val result = ArrayList<Candidate>()
        val seen = HashSet<String>()
        for (base in NativeCSharpMembers.baseTypes(own, resolver)) {
            for (member in declarations(base)) {
                if (!seen.add(member.key)) continue
                val modifiers = member.modifiers
                if (modifiers.none { it == "virtual" || it == "abstract" || it == "override" }) continue
                if ("sealed" in modifiers || "static" in modifiers || "private" in modifiers && "protected" !in modifiers) continue
                if (member.key in done) continue
                candidate(member)?.let { result += it }
            }
        }
        if (type is CSharpClassDeclaration || type is CSharpStructDeclaration || type is CSharpRecordDeclaration) {
            val record = type is CSharpRecordDeclaration
            for (candidate in OBJECT_MEMBERS) {
                if (record && candidate.name != "ToString") continue
                // by name: `Equals(object obj)` overrides `Equals(object? obj)`
                if ((done + seen).any { it.startsWith(candidate.name + "(") }) continue
                result += candidate
            }
        }
        return result
    }

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
                when (member) {
                    is CSharpMethodDeclaration -> member.identifier?.text?.let { result += Declared(SyntaxKind.MethodDeclaration, it, modifiers, member.parameterList?.text) { member } }
                    is CSharpPropertyDeclaration -> member.identifier?.text?.let { result += Declared(SyntaxKind.PropertyDeclaration, it, modifiers, null) { member } }
                    is CSharpIndexerDeclaration -> result += Declared(SyntaxKind.IndexerDeclaration, "this", modifiers, member.parameterList?.text) { member }
                    else -> {}
                }
            }
            is TypePart.Stub -> for (child in part.stub.childrenStubs) {
                if (child !is CSharpStub) continue
                val kind = child.elementType
                if (kind != SyntaxKind.MethodDeclaration && kind != SyntaxKind.PropertyDeclaration && kind != SyntaxKind.IndexerDeclaration) continue
                val name = if (kind == SyntaxKind.IndexerDeclaration) "this" else child.name ?: continue
                result += Declared(kind, name, child.modifiers, child.parameters) { child.psi }
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

    private fun candidate(member: Declared): Candidate? {
        val element = member.element() ?: return null
        val abstract = "abstract" in member.modifiers
        val access = access(member.modifiers)
        return when (element) {
            is CSharpMethodDeclaration -> {
                val returnType = element.returnType?.text?.let(CSharpStubsText::collapse) ?: return null
                val name = element.identifier?.text ?: return null
                val parameters = CSharpStubsText.collapse(element.parameterList?.text ?: "()")
                val typeParameters = element.typeParameterList?.text.orEmpty()
                val arguments = element.parameterList?.parameters.orEmpty().joinToString(", ") { parameter ->
                    val modifier = parameter.modifiers.firstOrNull { it.text == "ref" || it.text == "out" || it.text == "in" }?.text
                    listOfNotNull(modifier, parameter.identifier?.text).joinToString(" ")
                }
                val call = "base.$name$typeParameters($arguments)"
                val body = when {
                    abstract -> "throw new NotImplementedException();"
                    returnType == "void" || TASK_ONLY.matches(returnType) && "async" in member.modifiers -> "$call;"
                    else -> "return $call;"
                }
                Candidate(name, access, "$returnType $name$typeParameters$parameters", listOf(body), parameters, returnType, property = false)
            }
            is CSharpPropertyDeclaration -> {
                val type = element.type?.text?.let(CSharpStubsText::collapse) ?: return null
                val name = element.identifier?.text ?: return null
                val accessors = element.accessorList?.accessors?.mapNotNull { it.keyword?.text }.orEmpty().ifEmpty { listOf("get") }
                val lines = accessors.map { accessor ->
                    when {
                        abstract -> "$accessor => throw new NotImplementedException();"
                        accessor == "get" -> "get => base.$name;"
                        else -> "$accessor => base.$name = value;"
                    }
                }
                Candidate(name, access, "$type $name", lines, "", type, property = true)
            }
            else -> null
        }
    }

    private val TASK_ONLY = Regex("""^(?:System\.Threading\.Tasks\.)?(?:Task|ValueTask)$""")

    private fun access(modifiers: List<String>): String = modifiers.filter { it in ACCESS }.joinToString(" ")

    private val OBJECT_MEMBERS: List<Candidate> = listOf(
        Candidate("Equals", "public", "bool Equals(object? obj)", listOf("return base.Equals(obj);"), "(object? obj)", "bool", property = false),
        Candidate("GetHashCode", "public", "int GetHashCode()", listOf("return base.GetHashCode();"), "()", "int", property = false),
        Candidate("ToString", "public", "string? ToString()", listOf("return base.ToString();"), "()", "string?", property = false),
    )

    private fun element(candidate: Candidate, place: NativeCSharpCompletionPlace): LookupElement {
        var builder = LookupElementBuilder.create(candidate.name).withIcon(if (candidate.property) AllIcons.Nodes.Property else AllIcons.Nodes.Method)
            .withTailText(if (candidate.property) " { ... }" else candidate.tail + " { ... }", true).bold()
        if (candidate.type != null) builder = builder.withTypeText(candidate.type)
        builder = builder.withInsertHandler(InsertHandler { context, _ -> insert(context, candidate, place) })
        builder.putUserData(NativeCSharpCompletion.NATIVE, true)
        return PrioritizedLookupElement.withPriority(builder, NativeCSharpCompletion.DECLARATION).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
    }

    /**
     * The member in place of the typed name: the header, `{`, the body indented by one level of the code style, `}` (Allman, as the
     * defaults of .NET); the caret at the end of the body's first line. The accessibility of the base member before the modifiers when
     * none was typed.
     */
    fun insert(context: InsertionContext, candidate: Candidate, place: NativeCSharpCompletionPlace) {
        val document = context.document
        val text = document.charsSequence
        val start = context.startOffset
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val indent = text.subSequence(lineStart, start).takeWhile { it == ' ' || it == '\t' }.toString()
        val options = CodeStyle.getIndentOptions(context.file)
        val unit = if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE.coerceAtLeast(1))
        val body = candidate.body.joinToString("\n") { if (it.isEmpty()) "$indent$unit" else "$indent$unit$it" }
        val member = "${candidate.header}\n$indent{\n$body\n$indent}"
        document.replaceString(start, context.tailOffset, member)
        val firstBodyLine = start + candidate.header.length + 1 + indent.length + 2 + indent.length + unit.length + candidate.body.first().length
        var caret = firstBodyLine
        val typedAccess = place.modifiers.any { it in ACCESS }
        if (!typedAccess && candidate.access.isNotEmpty()) {
            // before the first typed modifier: `override` / `partial` and the others stay as typed
            val firstModifier = firstModifierOffset(text, lineStart, start)
            if (firstModifier != null) {
                document.insertString(firstModifier, candidate.access + " ")
                caret += candidate.access.length + 1
            }
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
