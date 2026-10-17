package io.github.dotnetsupport.index

/**
 * An attribute of the index with its arguments (format 7): the full name of its type, the arguments of its constructor and the named
 * ones, decoded from the string `MetadataScanner.AttributeValues` of indexer/Program.cs writes (`Microsoft.AspNetCore.Components.EventHandlerAttribute(s7:onclickt…)`).
 */
class IndexedAttribute(val typeName: String, val arguments: List<IndexedConstant>, val namedArguments: List<Pair<String, IndexedConstant>>) {
    override fun toString(): String = typeName + arguments.joinToString(", ", "(", ")") +
        if (namedArguments.isEmpty()) "" else namedArguments.joinToString(", ", "{", "}") { "${it.first}=${it.second}" }

    companion object {
        fun parse(text: String): IndexedAttribute {
            val nameEnd = text.indexOfFirst { it == '(' || it == '{' }.let { if (it < 0) text.length else it }
            val parser = Parser(text, nameEnd)
            val arguments = ArrayList<IndexedConstant>()
            val named = ArrayList<Pair<String, IndexedConstant>>()
            if (parser.peek() == '(') {
                parser.at++
                while (parser.peek() != ')') arguments += parser.constant()
                parser.at++
            }
            if (parser.peek() == '{') {
                parser.at++
                while (parser.peek() != '}') {
                    val name = parser.counted()
                    named += name to parser.constant()
                }
                parser.at++
            }
            require(parser.at == text.length) { "Not an attribute of the index: $text" }
            return IndexedAttribute(text.substring(0, nameEnd), arguments, named)
        }
    }

    private class Parser(val text: String, var at: Int) {
        fun peek(): Char? = text.getOrNull(at)

        fun constant(): IndexedConstant = when (val tag = text[at++]) {
            'n' -> IndexedConstant(IndexedConstant.Kind.PRIMITIVE, null, null)
            'T' -> IndexedConstant(IndexedConstant.Kind.PRIMITIVE, null, true)
            'F' -> IndexedConstant(IndexedConstant.Kind.PRIMITIVE, null, false)
            'i' -> IndexedConstant(IndexedConstant.Kind.PRIMITIVE, null, until(';').toLong())
            'u' -> IndexedConstant(IndexedConstant.Kind.PRIMITIVE, null, until(';').toULong().toLong())
            'd' -> IndexedConstant(IndexedConstant.Kind.PRIMITIVE, null, until(';').toDouble())
            's' -> IndexedConstant(IndexedConstant.Kind.PRIMITIVE, null, counted())
            't' -> IndexedConstant(IndexedConstant.Kind.TYPE, null, ref())
            'e' -> {
                val type = ref()
                require(text[at++] == 'i') { "An enum value expected at $at of $text" }
                IndexedConstant(IndexedConstant.Kind.ENUM, type, until(';').toLong())
            }
            'a' -> {
                val count = until(':').toInt()
                IndexedConstant(IndexedConstant.Kind.ARRAY, null, List(count) { constant() })
            }
            'A' -> IndexedConstant(IndexedConstant.Kind.ARRAY, null, null)
            else -> error("Not a constant of an attribute: '$tag' at ${at - 1} of $text")
        }

        /** `length ':' text`. */
        fun counted(): String {
            val length = until(':').toInt()
            return text.substring(at, at + length).also { at += length }
        }

        private fun ref(): IndexedTypeRef {
            val (ref, end) = IndexedTypeRef.parsePrefix(text, at)
            at = end
            return ref
        }

        private fun until(end: Char): String {
            val stop = text.indexOf(end, at)
            require(stop >= 0) { "An attribute cut short: $text" }
            return text.substring(at, stop).also { at = stop + 1 }
        }
    }
}

/**
 * A constant of an attribute: [value] is a String, Boolean, Long (an integer, a char, an enum's value), Double, [IndexedTypeRef] (a
 * `typeof`), a list of constants (an array) or null; [enumType] — the type of an enum.
 */
class IndexedConstant(val kind: Kind, val enumType: IndexedTypeRef?, val value: Any?) {
    enum class Kind { PRIMITIVE, ENUM, TYPE, ARRAY }

    override fun toString(): String = when (value) {
        is String -> "\"$value\""
        is List<*> -> value.joinToString(", ", "[", "]")
        null -> "null"
        else -> value.toString()
    }
}
