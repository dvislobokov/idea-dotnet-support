package io.github.dotnetsupport.lang

/**
 * Names for a variable, a parameter or a field of a type, as Rider offers them after the type (`StringBuilder |` → `builder`,
 * `stringBuilder`; `List<Order> |` → `orders`; `IOrderService |` → `service`, `orderService`): the type's words by camel humps, the
 * shortest ending first, the `I` of an interface dropped, the element of a collection or an array in the plural. A private field gets
 * `_`, a public member or a constant a capital first letter. A name that is a keyword gets `@`.
 */
object CSharpVariableNames {
    private val COLLECTIONS = setOf(
        "List", "IList", "IEnumerable", "ICollection", "IReadOnlyList", "IReadOnlyCollection", "HashSet", "ISet", "IReadOnlySet", "Collection",
        "ObservableCollection", "Queue", "Stack", "LinkedList", "SortedSet", "ImmutableArray", "ImmutableList", "ImmutableHashSet", "IQueryable",
        "IAsyncEnumerable", "IOrderedEnumerable", "IOrderedQueryable", "ConcurrentBag", "ConcurrentQueue", "ConcurrentStack", "BlockingCollection",
        "Span", "ReadOnlySpan", "Memory", "ReadOnlyMemory", "DbSet", "IGrouping",
    )
    private val IRREGULAR = mapOf("Person" to "People", "Child" to "Children", "Man" to "Men", "Woman" to "Women", "Mouse" to "Mice", "Data" to "Data", "Info" to "Infos")
    private val WORDS = Regex("""[A-Z]+(?![a-z])|[A-Z]?[a-z0-9]+""")
    private const val MAX = 4

    fun forType(type: String, style: NativeCSharpCompletionPlace.NameStyle = NativeCSharpCompletionPlace.NameStyle.LOCAL): List<String> =
        bases(type).map { styled(it, style) }.distinct().take(MAX)

    /** `builder` → `builder1` when taken. */
    fun unique(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        var i = 1
        while ("$name$i" in taken) i++
        return "$name$i"
    }

    /** camelCase names, unstyled. */
    private fun bases(type: String): List<String> {
        var text = type.trim().removePrefix("global::").removeSuffix("?").trim()
        if (text.endsWith("]")) {
            val element = text.substring(0, text.indexOf('['))
            return plural(element)
        }
        val open = text.indexOf('<')
        val head = simple(if (open < 0) text else text.substring(0, open))
        if (open >= 0) {
            val arguments = CSharpScopeNames.splitTopLevel(text.substring(open + 1, text.lastIndexOf('>').takeIf { it > open } ?: text.length))
            if (head in COLLECTIONS && arguments.size == 1) return plural(arguments[0])
            text = head
        } else {
            text = head
        }
        if (text in NativeCSharpCompletionPlace.PREDEFINED_TYPES || text.isEmpty() || !text[0].isLetter()) return emptyList()
        return suffixes(withoutInterfacePrefix(text))
    }

    private fun plural(element: String): List<String> {
        val names = bases(element)
        return names.map { name ->
            val words = WORDS.findAll(name).map { it.value }.toList()
            if (words.isEmpty()) name else {
                val last = words.last()
                val capital = last.replaceFirstChar { it.uppercase() }
                val plural = IRREGULAR[capital]?.let { if (last[0].isUpperCase()) it else it.lowercase() } ?: pluralize(last)
                words.dropLast(1).joinToString("") + plural
            }
        }
    }

    private fun simple(name: String): String = name.trim().substringAfterLast('.').substringAfterLast(':')

    private fun withoutInterfacePrefix(name: String): String =
        if (name.length > 2 && name[0] == 'I' && name[1].isUpperCase() && name[2].isLowerCase()) name.substring(1) else name

    /** `HttpClientFactory` → `factory`, `clientFactory`, `httpClientFactory`. */
    private fun suffixes(name: String): List<String> {
        val words = WORDS.findAll(name).map { it.value }.toList()
        if (words.isEmpty()) return emptyList()
        return (words.indices.reversed()).map { start -> camel(words.subList(start, words.size)) }
    }

    private fun camel(words: List<String>): String = words.mapIndexed { i, word ->
        if (i == 0) {
            if (word.length > 1 && word.all { it.isUpperCase() || it.isDigit() }) word.lowercase() else word.replaceFirstChar { it.lowercase() }
        } else {
            if (word.length > 1 && word.all { it.isUpperCase() || it.isDigit() }) word.lowercase().replaceFirstChar { it.uppercase() } else word.replaceFirstChar { it.uppercase() }
        }
    }.joinToString("")

    private fun pluralize(word: String): String {
        val lower = word.lowercase()
        return when {
            lower.endsWith("y") && lower.length > 1 && lower[lower.length - 2] !in "aeiou" -> word.dropLast(1) + "ies"
            lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("z") || lower.endsWith("ch") || lower.endsWith("sh") -> word + "es"
            else -> word + "s"
        }
    }

    private fun styled(name: String, style: NativeCSharpCompletionPlace.NameStyle): String = when (style) {
        NativeCSharpCompletionPlace.NameStyle.LOCAL -> escaped(name)
        NativeCSharpCompletionPlace.NameStyle.PRIVATE_FIELD -> "_$name"
        NativeCSharpCompletionPlace.NameStyle.PUBLIC_MEMBER -> name.replaceFirstChar { it.uppercase() }
    }

    private fun escaped(name: String): String = if (name in NativeCSharpCompletionPlace.RESERVED) "@$name" else name
}
