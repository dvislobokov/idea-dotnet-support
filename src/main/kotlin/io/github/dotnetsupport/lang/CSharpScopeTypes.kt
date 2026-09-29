package io.github.dotnetsupport.lang

/**
 * What a statement can see and of what type it is, by the tokens of the file: the parameters of the member, the locals declared
 * above, the fields, properties and methods of the types around. No semantics: the type is the one that is written (`var` gives one
 * only where the initializer names it). It is what the order of the completion list is made from while the server tells the name and
 * the kind of an item and nothing of its type.
 */
object CSharpScopeTypes {
    class Symbol(val type: String?, /** A local or a parameter of the member. */ val local: Boolean, /** The line of the declaration, from 0. */ val line: Int)

    private val TYPE_KEYWORDS = setOf("var", "int", "string", "bool", "double", "long", "object", "char", "byte", "float", "decimal", "short", "uint", "ulong", "ushort", "sbyte", "dynamic")
    private val PARAMETER_MODIFIERS = Regex("""^(?:(?:this|ref|out|in|params|scoped|readonly)\s+)+""")
    private val ATTRIBUTES = Regex("""^(?:\[[^\]]*\]\s*)+""")

    fun at(text: CharSequence, offset: Int): Map<String, Symbol> {
        val symbols = LinkedHashMap<String, Symbol>()
        val path = CSharpDeclarations.scan(text).pathTo(offset)
        for (type in path.filter { it.kind.isType }) {
            for (member in type.children) {
                if (member.kind != DeclarationKind.FIELD && member.kind != DeclarationKind.PROPERTY && member.kind != DeclarationKind.METHOD) continue
                symbols[member.name] = Symbol(member.type, local = false, line = lineOf(text, member.nameRange.startOffset))
            }
        }
        val member = path.lastOrNull { !it.kind.isType && it.kind != DeclarationKind.NAMESPACE }
        member?.parameters?.let { list ->
            val line = lineOf(text, member.nameRange.startOffset)
            for ((name, type) in parameters(list)) symbols[name] = Symbol(type, local = true, line = line)
        }
        val from = member?.body?.startOffset ?: 0
        if (from < offset) for ((name, local) in locals(text, from, offset)) symbols[name] = local
        return symbols
    }

    /** `(string name, [FromBody] Order order, params int[] rest)` -> name: string, order: Order, rest: int[]. */
    fun parameters(list: String): List<Pair<String, String?>> {
        val inner = list.trim().removePrefix("(").removeSuffix(")")
        return CSharpScopeNames.splitTopLevel(inner).mapNotNull { parameter ->
            val declared = (CSharpScopeNames.splitTopLevel(parameter, '=').firstOrNull() ?: return@mapNotNull null).replace(ATTRIBUTES, "").replace(PARAMETER_MODIFIERS, "").trim()
            val name = declared.takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '@' }
            if (name.isEmpty() || name.length == declared.length) return@mapNotNull null
            name.removePrefix("@") to declared.dropLast(name.length).trim().takeIf { it.isNotEmpty() }
        }
    }

    /** The locals declared in `text[from, to)`, the later declaration of a name over the earlier. */
    fun locals(text: CharSequence, from: Int, to: Int): List<Pair<String, Symbol>> {
        val tokens = CSharpExpressions.tokenize(text.subSequence(from, to))
        val found = ArrayList<Pair<String, Symbol>>()
        for (i in 1 until tokens.size) {
            val name = tokens[i]
            if (!name.isIdentifier) continue
            val after = tokens.getOrNull(i + 1)
            val ends = after == null || after.isOperator("=") || after.type == CSharpTokenTypes.SEMICOLON || after.type == CSharpTokenTypes.COMMA ||
                after.type == CSharpTokenTypes.RPAREN || after.isKeyword("in") ||
                (after.type == CSharpTokenTypes.OPERATOR && (after.text.startsWith("&") || after.text.startsWith("|")))
            if (!ends) continue
            val typeStart = typeStart(tokens, i - 1) ?: continue
            val written = text.subSequence(from + tokens[typeStart].start, from + name.start).toString().trim()
            val type = if (written == "var") inferred(tokens, i + 1) else written
            found += name.text to Symbol(type, local = true, line = lineOf(text, from + name.start))
        }
        return found
    }

    /** The index of the first token of the type that ends at [last], null when what stands there is no type. */
    private fun typeStart(tokens: List<CSharpExpressions.Token>, last: Int): Int? {
        var i = last
        while (i >= 0 && tokens[i].isOperator("?")) i--
        while (i >= 1 && tokens[i].type == CSharpTokenTypes.RBRACKET && tokens[i - 1].type == CSharpTokenTypes.LBRACKET) i -= 2
        if (i < 0) return null
        if (closers(tokens[i]) > 0) {
            var depth = 0
            while (i >= 0) {
                // `List<List<int>>` ends with one token `>>`
                if (closers(tokens[i]) > 0) depth += closers(tokens[i]) else if (tokens[i].isOperator("<")) { depth--; if (depth == 0) break }
                else if (!(tokens[i].isIdentifier || tokens[i].type == CSharpTokenTypes.COMMA || tokens[i].type == CSharpTokenTypes.DOT || tokens[i].isOperator("?") ||
                        tokens[i].type == CSharpTokenTypes.LBRACKET || tokens[i].type == CSharpTokenTypes.RBRACKET ||
                        (tokens[i].type == CSharpTokenTypes.KEYWORD && tokens[i].text in TYPE_KEYWORDS))) return null
                i--
            }
            i--
            if (i < 0) return null
        }
        val head = tokens[i]
        if (!(head.isIdentifier || (head.type == CSharpTokenTypes.KEYWORD && head.text in TYPE_KEYWORDS))) return null
        while (i >= 2 && tokens[i - 1].type == CSharpTokenTypes.DOT && tokens[i - 2].isIdentifier) i -= 2
        // `a.b = 1`, `x.Count > n`: a name after a dot is a member, not a declaration
        if (i >= 1 && tokens[i - 1].type == CSharpTokenTypes.DOT) return null
        return i
    }

    private fun closers(token: CSharpExpressions.Token): Int =
        if (token.type == CSharpTokenTypes.OPERATOR && token.text.isNotEmpty() && token.text.all { it == '>' }) token.text.length else 0

    /** `var x = new Order(...)`, `var s = "text"`, `var n = 5`, `var ok = true`: what the initializer itself says. */
    private fun inferred(tokens: List<CSharpExpressions.Token>, equalsAt: Int): String? {
        if (tokens.getOrNull(equalsAt)?.isOperator("=") != true) return null
        val first = tokens.getOrNull(equalsAt + 1) ?: return null
        return when {
            first.isKeyword("new") -> {
                var i = equalsAt + 2
                val parts = StringBuilder()
                while (i < tokens.size && (tokens[i].isIdentifier || tokens[i].type == CSharpTokenTypes.DOT)) parts.append(tokens[i++].text)
                if (tokens.getOrNull(i)?.isOperator("<") == true) {
                    var depth = 0
                    while (i < tokens.size) {
                        parts.append(tokens[i].text)
                        if (tokens[i].type == CSharpTokenTypes.COMMA) parts.append(' ')
                        if (tokens[i].isOperator("<")) depth++ else if (tokens[i].isOperator(">") && --depth == 0) break
                        i++
                    }
                }
                parts.toString().takeIf { it.isNotEmpty() }
            }
            first.type == CSharpTokenTypes.STRING -> "string"
            first.type == CSharpTokenTypes.CHAR -> "char"
            first.type == CSharpTokenTypes.NUMBER -> if (first.text.any { it == '.' } || first.text.endsWith("d", ignoreCase = true)) "double" else "int"
            first.isKeyword("true", "false") -> "bool"
            else -> null
        }
    }

    fun lineOf(text: CharSequence, offset: Int): Int {
        var line = 0
        for (i in 0 until offset.coerceAtMost(text.length)) if (text[i] == '\n') line++
        return line
    }
}

/** What is wanted where the caret is: `int total = |` wants an `int`, and a thing called `total` is likely the one. */
class CSharpExpected(val type: String?, val name: String?) {
    override fun toString(): String = "${type ?: "?"} ${name ?: "?"}"
}

object CSharpExpectations {
    private const val TYPE = """(?:global::)?[A-Za-z_][\w.]*(?:<[^;={}()]*>)?(?:\[[,\s]*\])*\??"""
    private val DECLARATION = Regex("""(?:^|[\s({;])($TYPE)\s+([A-Za-z_]\w*)\s*=\s*$""")
    private val ASSIGNMENT = Regex("""(?:^|[\s({;])((?:this\.)?[A-Za-z_][\w.]*)\s*[+\-*/]?=\s*$""")
    private val RETURN = Regex("""(?:^|[\s{;])return\s+$""")
    private val CHAIN = Regex("""(?:(?:this|base|[A-Za-z_]\w*)\??\.)+$""")
    private val NOT_TYPES = setOf("var", "return", "new", "await", "throw", "case", "else", "in", "is", "as", "out", "ref", "yield", "typeof", "nameof")

    /**
     * By the text of the line before [start], where the name that is being completed begins. An argument of a call is not seen here:
     * its parameter comes from the signature help of the server.
     */
    fun at(text: CharSequence, start: Int, symbols: Map<String, CSharpScopeTypes.Symbol> = emptyMap()): CSharpExpected? {
        if (start < 0 || start > text.length) return null
        val lineStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
        // `int amount = order.|`: what is wanted is wanted of the whole chain, and the chain begins before the dot
        val line = text.subSequence(lineStart, start).toString().replace(CHAIN, "")
        if (line.trimEnd().endsWith("==") || line.trimEnd().endsWith("!=") || line.trimEnd().endsWith("=>") || line.trimEnd().endsWith("<=") || line.trimEnd().endsWith(">=")) return null
        DECLARATION.find(line)?.let { match ->
            val type = match.groupValues[1]
            if (type.substringBefore('<') !in NOT_TYPES) return CSharpExpected(type, match.groupValues[2])
            if (type == "var") return CSharpExpected(null, match.groupValues[2])
        }
        if (RETURN.containsMatchIn(line)) return returned(text, start)
        ASSIGNMENT.find(line)?.let { match ->
            val target = match.groupValues[1].removePrefix("this.")
            val name = target.substringAfterLast('.')
            val type = if ('.' in target) null else symbols[name]?.type
            return CSharpExpected(type, name)
        }
        return null
    }

    /** `return |`: the type of the member, the `T` of `Task<T>` in an async one. */
    private fun returned(text: CharSequence, offset: Int): CSharpExpected? {
        val member = CSharpDeclarations.scan(text).pathTo(offset).lastOrNull { it.kind == DeclarationKind.METHOD || it.kind == DeclarationKind.PROPERTY } ?: return null
        val type = member.type?.takeIf { it != "void" } ?: return null
        val awaited = Regex("""^(?:System\.Threading\.Tasks\.)?(?:Task|ValueTask)<(.+)>$""").matchEntire(type)?.groupValues?.get(1)
        if ("async" in member.modifiers) return awaited?.let { CSharpExpected(it, null) }
        return CSharpExpected(type, null)
    }
}

/** Whether a value of one written type goes where another is wanted: by the text of the types, generously to `List<T>` for `IEnumerable<T>`. */
object CSharpTypeNames {
    private val ALIASES = mapOf(
        "String" to "string", "Int32" to "int", "Int64" to "long", "Boolean" to "bool", "Double" to "double", "Decimal" to "decimal", "Object" to "object",
        "Single" to "float", "Char" to "char", "Byte" to "byte", "Int16" to "short",
    )
    private val SEQUENCES = setOf("IEnumerable", "ICollection", "IList", "IReadOnlyList", "IReadOnlyCollection")
    private val LISTS = setOf("List", "IList", "ICollection", "IReadOnlyList", "IReadOnlyCollection", "HashSet", "ISet", "Collection", "IEnumerable")
    private val TYPE_PARAMETER = Regex("""^T(?:[A-Z]\w*)?$""")
    private val QUALIFIED = Regex("""(?:global::)?(?:[A-Za-z_]\w*\.)+([A-Za-z_]\w*)""")

    /** `System.Collections.Generic.List<System.String>?` -> `List<string>`. */
    fun normalize(type: String): String {
        val bare = QUALIFIED.replace(type.replace(" ", ""), "$1").removePrefix("global::").removeSuffix("?")
        return Regex("""[A-Za-z_]\w*""").replace(bare) { ALIASES[it.value] ?: it.value }
    }

    /** Nothing can be said of `object`, `dynamic`, `var` and of a type parameter: anything goes there. */
    fun isSpecific(type: String?): Boolean {
        val normalized = normalize(type ?: return false)
        return normalized.isNotEmpty() && normalized != "object" && normalized != "dynamic" && normalized != "var" && !TYPE_PARAMETER.matches(normalized)
    }

    fun matches(expected: String?, actual: String?): Boolean {
        if (!isSpecific(expected) || actual == null) return false
        val wanted = normalize(expected!!)
        val given = normalize(actual)
        if (wanted == given) return true
        val wantedBase = wanted.substringBefore('<')
        if (wantedBase !in SEQUENCES || '<' !in wanted) return false
        val element = wanted.substringAfter('<').removeSuffix(">")
        if (given == "$element[]") return true
        return given.substringBefore('<') in LISTS && given.substringAfter('<', "").removeSuffix(">") == element
    }
}

/** How alike two names are: `order` and `_order` are one, `customerName` and `Name` share their end. */
object CSharpNameLikeness {
    enum class Likeness { NONE, PARTIAL, EXACT }

    fun of(wanted: String?, candidate: String?): Likeness {
        val a = bare(wanted ?: return Likeness.NONE)
        val b = bare(candidate ?: return Likeness.NONE)
        if (a.isEmpty() || b.isEmpty()) return Likeness.NONE
        if (a.equals(b, ignoreCase = true)) return Likeness.EXACT
        // `i`, `x`, `id`: too short to mean anything as a part
        if (a.length < 3 || b.length < 3) return Likeness.NONE
        val first = words(a)
        val second = words(b)
        val shorter = if (first.size <= second.size) first else second
        val longer = if (first.size <= second.size) second else first
        return if (longer.takeLast(shorter.size) == shorter) Likeness.PARTIAL else Likeness.NONE
    }

    private fun bare(name: String): String = name.removePrefix("@").removePrefix("m_").trimStart('_')

    /** `customerName` -> customer, name; `HTTPClient` -> http, client. */
    fun words(name: String): List<String> =
        Regex("""[A-Z]+(?![a-z])|[A-Z]?[a-z0-9]+""").findAll(name).map { it.value.lowercase() }.toList()
}

/**
 * The call the caret is in, when the method is one of this very file: its parameters are written a few lines away, and there is no
 * need to wait for the server to say them. `Save(|` with one `Save` in the class -> its parameters and 0; `Save(order, |` -> 1.
 * Several overloads, a method of another type, a constructor of another class: null, the server is asked.
 */
object CSharpLocalCalls {
    class Call(val name: String, /** `RankedOrder order`, as a signature of the server has them. */ val parameters: List<String>, val active: Int)

    fun at(text: CharSequence, offset: Int): Call? {
        if (offset < 0 || offset > text.length) return null
        var depth = 0
        var commas = 0
        var open = -1
        var i = offset - 1
        while (i >= 0) {
            when (text[i]) {
                ')', ']', '}' -> depth++
                '[' -> if (depth == 0) return null else depth--
                '{' -> if (depth == 0) return null else depth--
                '(' -> if (depth == 0) { open = i; break } else depth--
                ',' -> if (depth == 0) commas++
                ';' -> if (depth == 0) return null
            }
            i--
        }
        if (open < 0) return null
        var end = open
        while (end > 0 && text[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        if (start == end) return null
        val name = text.subSequence(start, end).toString()
        // `other.Save(`: a method of something else, unless it is `this.`
        var before = start
        while (before > 0 && text[before - 1].isWhitespace()) before--
        if (before > 0 && text[before - 1] == '.' && !text.subSequence(0, before - 1).trimEnd().endsWith("this")) return null
        val type = CSharpDeclarations.scan(text).pathTo(offset).lastOrNull { it.kind.isType } ?: return null
        val method = type.children.filter { it.kind == DeclarationKind.METHOD && it.name == name }.singleOrNull() ?: return null
        val parameters = CSharpScopeTypes.parameters(method.parameters ?: return null).map { (parameter, written) -> listOfNotNull(written, parameter).joinToString(" ") }
        return Call(name, parameters, commas)
    }
}

/**
 * The value that is wanted, offered as gray text without being asked for: `int amount = ` with one `int` at hand gives `count;`.
 * From the names of the file and the types written there ([CSharpScopeTypes]); a method is never offered, its arguments are
 * anybody's guess. Silent unless one candidate stands clear of the others: by its type, then by its name, then by being a local
 * declared nearby.
 */
object CSharpValueGhost {
    private const val TYPE = 25
    private const val NAME_EXACT = 30
    private const val NAME_PARTIAL = 12
    private const val LOCAL = 3
    private const val NEARBY = 1
    private const val NEARBY_LINES = 5

    /** By how much the best has to be ahead of the second. */
    private const val MARGIN = 3

    fun suggest(text: CharSequence, offset: Int): String? {
        if (offset < 0 || offset > text.length) return null
        var lineEnd = offset
        while (lineEnd < text.length && text[lineEnd] != '\n') { if (!text[lineEnd].isWhitespace()) return null; lineEnd++ }
        var start = offset
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        val typed = text.subSequence(start, offset).toString()
        // after a dot the names of the file are not what is listed
        var before = start
        while (before > 0 && (text[before - 1] == ' ' || text[before - 1] == '\t')) before--
        if (before > 0 && text[before - 1] == '.') return null
        val symbols = CSharpScopeTypes.at(text, start)
        val expected = CSharpExpectations.at(text, start, symbols) ?: return null
        val line = CSharpScopeTypes.lineOf(text, start)
        val typeKnown = CSharpTypeNames.isSpecific(expected.type)
        val methods = CSharpDeclarations.scan(text).pathTo(start).filter { it.kind.isType }
            .flatMap { type -> type.children.filter { it.kind == DeclarationKind.METHOD }.map { it.name } }.toSet()

        val scored = symbols.entries.mapNotNull { (name, symbol) ->
            if (name == expected.name || name in methods || !name.startsWith(typed)) return@mapNotNull null
            val fits = CSharpTypeNames.matches(expected.type, symbol.type)
            // the type is known and this is of another one (or of nobody knows which)
            if (typeKnown && !fits) return@mapNotNull null
            val likeness = CSharpNameLikeness.of(expected.name, name)
            if (!typeKnown && likeness == CSharpNameLikeness.Likeness.NONE) return@mapNotNull null
            var score = if (fits) TYPE else 0
            score += when (likeness) {
                CSharpNameLikeness.Likeness.EXACT -> NAME_EXACT
                CSharpNameLikeness.Likeness.PARTIAL -> NAME_PARTIAL
                CSharpNameLikeness.Likeness.NONE -> 0
            }
            if (symbol.local) score += LOCAL + (if (line - symbol.line in 0..NEARBY_LINES) NEARBY else 0)
            name to score
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        if (scored.size > 1 && best.second - scored[1].second < MARGIN) return null
        if (best.first == typed) return null
        val lineStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
        val head = text.subSequence(lineStart, start)
        // `for (int i = `, `using (var x = `: the statement does not end here
        val inside = head.count { it == '(' } > head.count { it == ')' }
        return best.first.substring(typed.length) + (if (inside) "" else ";")
    }
}

