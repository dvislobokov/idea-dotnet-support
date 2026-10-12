package io.github.dotnetsupport.lang

/**
 * Names for a variable that holds an expression, as Rider's `.var` / `.foreach` make them (dump 28d: `order.Total.var` → `orderTotal`): a
 * member of a simple name joins the two (`orderTotal`, then `total`), a call gives the noun of its method (`GetOrders()` → `orders`), `new T()`
 * and the type give [CSharpVariableNames.forType]. An element of a collection gets the singular (`orders` → `order`), else `item`.
 */
object CSharpExpressionNames {
    private val IDENTIFIER = Regex("""@?[A-Za-z_]\w*""")
    private val CREATION = Regex("""^new\s+([\w.:]+(?:<.*>)?)\s*[({]""")
    private val IRREGULAR = mapOf("people" to "person", "children" to "child", "men" to "man", "women" to "woman", "mice" to "mouse", "data" to "data")
    private const val MAX = 4

    /** Names for `var x = [expression];`, the best first; never empty. [type]: the type of the expression, when it is known. */
    fun forExpression(expression: String, type: String? = null, taken: Set<String> = emptySet()): List<String> {
        val text = clean(expression)
        val names = ArrayList<String>()
        val creation = CREATION.find(text)
        when {
            creation != null -> names += CSharpVariableNames.forType(creation.groupValues[1])
            text.endsWith(")") -> {
                val open = matching(text, text.length - 1, '(', ')')
                val callee = if (open > 0) text.substring(0, open).trimEnd() else ""
                val method = IDENTIFIER.findAll(callee.substringBeforeLast('<')).lastOrNull()?.value
                method?.let(CSharpUsingNames::ofMethod)?.let { names += camel(it) }
            }
            text.endsWith("]") -> {
                val open = matching(text, text.length - 1, '[', ']')
                if (open > 0) names += forElement(text.substring(0, open)).filter { it != "item" }
            }
            else -> {
                val parts = text.split(Regex("""\??\.""")).map { it.trim() }
                val last = parts.last()
                if (IDENTIFIER.matches(last)) {
                    val qualifier = parts.getOrNull(parts.size - 2)
                    if (qualifier != null && IDENTIFIER.matches(qualifier) && qualifier !in setOf("this", "base") && qualifier[0].isLowerCase()) {
                        names += camel(qualifier.removePrefix("@").removePrefix("_")) + last.removePrefix("@").replaceFirstChar { it.uppercase() }
                    }
                    // a plain local already has its name: the type's names come first then (`count.var` is not `count` again)
                    if (parts.size > 1 || last[0].isUpperCase()) names += camel(last.removePrefix("@").removePrefix("_"))
                }
            }
        }
        type?.let { names += CSharpVariableNames.forType(it) }
        if (names.isEmpty()) names += "value"
        return finish(names, taken)
    }

    /** Names for the element of a collection: of the element [type] when known, the singular of the collection's name, else `item`. */
    fun forElement(collection: String, type: String? = null, taken: Set<String> = emptySet()): List<String> {
        val names = ArrayList<String>()
        type?.let { names += CSharpVariableNames.forType(it) }
        val text = clean(collection)
        val word = when {
            text.endsWith(")") -> {
                val open = matching(text, text.length - 1, '(', ')')
                IDENTIFIER.findAll(if (open > 0) text.substring(0, open) else "").lastOrNull()?.value?.let(CSharpUsingNames::ofMethod)
            }
            else -> IDENTIFIER.findAll(text).lastOrNull()?.value?.removePrefix("@")?.removePrefix("_")
        }
        word?.let(::camel)?.let { name -> singular(name).takeIf { it != name }?.let { names += it } }
        names += "item"
        return finish(names, taken)
    }

    /** `orders` → `order`, `categories` → `category`, `boxes` → `box`, `people` → `person`; the word itself when it is not a plural. */
    fun singular(word: String): String {
        val split = word.indexOfLast { it.isUpperCase() }.coerceAtLeast(0)
        val head = word.substring(0, split)
        val tail = word.substring(split)
        val lower = tail.lowercase()
        IRREGULAR[lower]?.let { return head + if (tail[0].isUpperCase()) it.replaceFirstChar { c -> c.uppercase() } else it }
        val single = when {
            lower.endsWith("ies") && lower.length > 3 -> tail.dropLast(3) + "y"
            lower.endsWith("sses") || lower.endsWith("xes") || lower.endsWith("ches") || lower.endsWith("shes") || lower.endsWith("zes") -> tail.dropLast(2)
            lower.endsWith("ss") || lower.endsWith("us") || lower.endsWith("is") -> tail
            lower.endsWith("s") && lower.length > 1 -> tail.dropLast(1)
            else -> tail
        }
        return head + single
    }

    /** Identifiers of [text] (a body around the place): names a new variable must not take. */
    fun identifiersIn(text: CharSequence): Set<String> = IDENTIFIER.findAll(text).map { it.value.removePrefix("@") }.toSet()

    private fun finish(names: List<String>, taken: Set<String>): List<String> =
        names.filter { it.isNotEmpty() && it[0].isLetter() || it.startsWith("@") }
            .map { escaped(it) }.map { CSharpVariableNames.unique(it, taken) }.distinct().take(MAX).ifEmpty { listOf(CSharpVariableNames.unique("value", taken)) }

    private fun escaped(name: String): String = if (name in NativeCSharpCompletionPlace.RESERVED) "@$name" else name

    private fun clean(expression: String): String {
        var text = expression.trim()
        while (true) {
            val next = text.removePrefix("await ").removePrefix("!").trim().removeSuffix("!").removeSuffix("?")
            if (next == text) break
            text = next
        }
        if (text.startsWith("(") && text.endsWith(")") && matching(text, text.length - 1, '(', ')') == 0) return clean(text.substring(1, text.length - 1))
        return text
    }

    private fun camel(word: String): String =
        if (word.length > 1 && word.all { it.isUpperCase() || it.isDigit() }) word.lowercase() else word.replaceFirstChar { it.lowercase() }

    /** The offset of the bracket that opens the one closing at [close]; -1 when there is none. Strings are not looked into: names only. */
    private fun matching(text: String, close: Int, open: Char, closing: Char): Int {
        var depth = 0
        for (i in close downTo 0) {
            when (text[i]) {
                closing -> depth++
                open -> if (--depth == 0) return i
            }
        }
        return -1
    }
}
