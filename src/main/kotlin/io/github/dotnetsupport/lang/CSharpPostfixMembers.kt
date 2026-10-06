package io.github.dotnetsupport.lang

/**
 * The edits of the postfix templates that reach outside the expression (COMPLETION_GAPS 2.2): `.field` / `.prop` declare a member of the
 * type around, `.inject` adds a parameter of a type to its constructor (the primary one when the type has no other, as Rider does). Pure
 * functions of the text and its [CSharpSyntaxModel] declarations, so they work on either tree.
 */
object CSharpPostfixMembers {
    /** Replace [length] characters at [offset] by [text]. */
    class Edit(val offset: Int, val length: Int, val text: String)

    /** The type around [offset] and the member of it the offset is in (null: right in the type's body, between members). */
    class Place(val type: CSharpDeclarationInfo, val member: CSharpDeclarationInfo?)

    private val TYPES_WITH_MEMBERS = setOf(DeclarationKind.CLASS, DeclarationKind.STRUCT, DeclarationKind.RECORD)

    fun place(text: CharSequence, offset: Int): Place? {
        val path = CSharpSyntaxModel.current.declarations(text).pathTo(offset)
        val typeAt = path.indexOfLast { it.kind in TYPES_WITH_MEMBERS }
        if (typeAt < 0) return null
        val type = path[typeAt]
        if (type.body?.let { offset > it.startOffset && offset < it.endOffset } != true) return null
        return Place(type, path.getOrNull(typeAt + 1))
    }

    /** True where a member may be declared: in the body of a type, outside its members. */
    fun atMemberLevel(text: CharSequence, offset: Int): Boolean {
        val last = CSharpSyntaxModel.current.declarations(text).pathTo(offset).lastOrNull() ?: return false
        return last.kind.isType && last.body?.let { offset > it.startOffset && offset < it.endOffset } == true
    }

    /** Names the members of [type] have: a new one must not take them. */
    fun memberNames(type: CSharpDeclarationInfo): Set<String> = type.children.map { it.name }.toSet()

    /** `private readonly T _name;` after the last field of the type (at the top of its body when there is none). */
    fun declareField(text: CharSequence, type: CSharpDeclarationInfo, declaration: String, unit: String): Edit =
        declare(text, type, declaration, unit, listOf(DeclarationKind.FIELD))

    /** `public T Name { get; set; }` after the last property, else after the last field, else at the top of the body. */
    fun declareProperty(text: CharSequence, type: CSharpDeclarationInfo, declaration: String, unit: String): Edit =
        declare(text, type, declaration, unit, listOf(DeclarationKind.PROPERTY, DeclarationKind.FIELD))

    private fun declare(text: CharSequence, type: CSharpDeclarationInfo, declaration: String, unit: String, after: List<DeclarationKind>): Edit {
        val indent = memberIndent(text, type, unit)
        // "at the end" of the page of the server: after the last member of any kind
        val previous = if (CSharpGenerationOptions.atEnd) type.children.lastOrNull() else after.firstNotNullOfOrNull { kind -> type.children.lastOrNull { it.kind == kind } }
        // a property after the fields starts a group of its own: a blank line between
        if (previous != null) return Edit(previous.range.endOffset, 0, (if (previous.kind == after.first()) "\n" else "\n\n") + "$indent$declaration")
        val open = type.body!!.startOffset + 1
        return Edit(open, 0, "\n$indent$declaration\n")
    }

    private fun memberIndent(text: CharSequence, type: CSharpDeclarationInfo, unit: String): String =
        type.children.firstOrNull()?.let { CSharpExpressions.indentAt(text, it.range.startOffset) }
            ?: (CSharpExpressions.indentAt(text, type.range.startOffset) + unit)

    /**
     * `IOrderService.inject` in the body of a type: a constructor parameter `IOrderService orderService`. With a constructor, the parameter
     * goes to the first one and a field `_orderService` keeps it; without one, to the primary constructor (made when there is none).
     */
    fun inject(text: CharSequence, type: CSharpDeclarationInfo, typeName: String, unit: String): List<Edit>? {
        val name = CSharpVariableNames.forType(typeName).lastOrNull() ?: return null
        val taken = memberNames(type)
        val constructor = type.children.firstOrNull { it.kind == DeclarationKind.CONSTRUCTOR && !it.name.startsWith("~") && "static" !in it.modifiers }
        if (constructor == null) {
            val after = afterNameAndTypeParameters(text, type.nameRange.endOffset)
            var at = after
            while (at < text.length && text[at].isWhitespace()) at++
            if (at < text.length && text[at] == '(') {
                val close = closing(text, at) ?: return null
                return listOf(Edit(close, 0, separatorBefore(text, at, close) + "$typeName $name"))
            }
            return listOf(Edit(after, 0, "($typeName $name)"))
        }
        val open = (constructor.nameRange.endOffset until (constructor.body?.startOffset ?: text.length)).firstOrNull { text[it] == '(' } ?: return null
        val close = closing(text, open) ?: return null
        val parameterNames = CSharpDocComments.parameterNames(text.subSequence(open, close + 1).toString()).toSet()
        val parameter = CSharpVariableNames.unique(name, parameterNames)
        val field = CSharpVariableNames.unique("_$name", taken)
        val edits = ArrayList<Edit>()
        edits += Edit(close, 0, separatorBefore(text, open, close) + "$typeName $parameter")
        edits += declareField(text, type, "private readonly $typeName $field;", unit)
        constructor.body?.let { body ->
            val brace = body.endOffset - 1
            var last = brace - 1
            while (last > body.startOffset && text[last].isWhitespace()) last--
            val indent = CSharpExpressions.indentAt(text, constructor.nameRange.startOffset)
            edits += Edit(last + 1, brace - last - 1, "\n$indent$unit$field = $parameter;\n$indent")
        }
        return edits
    }

    private fun separatorBefore(text: CharSequence, open: Int, close: Int): String = if (text.subSequence(open + 1, close).isBlank()) "" else ", "

    /** After `Name` and its `<T, U>`, where a primary constructor's list goes. */
    private fun afterNameAndTypeParameters(text: CharSequence, nameEnd: Int): Int {
        var at = nameEnd
        while (at < text.length && text[at].isWhitespace()) at++
        if (at >= text.length || text[at] != '<') return nameEnd
        var depth = 0
        while (at < text.length) {
            when (text[at]) {
                '<' -> depth++
                '>' -> if (--depth == 0) return at + 1
            }
            at++
        }
        return nameEnd
    }

    private fun closing(text: CharSequence, open: Int): Int? {
        var depth = 0
        for (i in open until text.length) when (text[i]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return i
        }
        return null
    }
}
