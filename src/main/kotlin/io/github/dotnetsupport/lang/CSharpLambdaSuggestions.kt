package io.github.dotnetsupport.lang

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons

/** A lambda for a delegate type: its parameter names, and the two ways to insert it. */
class LambdaSuggestion(val parameters: List<String>) {
    /** `x => `, `(a, b) => `, `() => ` */
    val head: String get() = when (parameters.size) {
        1 -> "${parameters.single()} => "
        else -> "(${parameters.joinToString(", ")}) => "
    }

    fun inlineElement(): LookupElementBuilder = LookupElementBuilder.create(head).withPresentableText(head.trimEnd()).withIcon(AllIcons.Nodes.Lambda).withTypeText("lambda", true)
        .withInsertHandler { context, _ -> context.editor.caretModel.moveToOffset(context.tailOffset) }

    /** `x =>` + a block below, the caret on the empty line inside. */
    fun blockElement(indent: String): LookupElementBuilder {
        val unit = "    "
        val text = "${head.trimEnd()}\n$indent{\n$indent$unit\n$indent}"
        return LookupElementBuilder.create(text).withPresentableText("${head.trimEnd()} { ... }").withIcon(AllIcons.Nodes.Lambda).withTypeText("lambda", true)
            .withInsertHandler { context, _ -> context.editor.caretModel.moveToOffset(context.startOffset + head.trimEnd().length + 1 + indent.length + 2 + indent.length + unit.length) }
    }

    override fun toString(): String = head
}

/**
 * The names of the parameters of a lambda for a delegate type written as text (`Func<IServiceProvider, object>`), as Rider names them: from
 * the types (`serviceProvider`, `i` for `int`, `x` for a bare type parameter), `(sender, e)` for an event handler, `(x, y)` for a comparison.
 * Shared by the lambdas of the server's signature help (module `roslyn`) and the native ones ([NativeCSharpLambdas]).
 */
object CSharpLambdaNames {
    private val DELEGATE = Regex("""^(?:params\s+|ref\s+|in\s+|out\s+)?(?:System\.)?(?:Linq\.Expressions\.)?(Expression<(.+)>|Func<(.+)>|Action<(.+)>|Action|Predicate<(.+)>|Comparison<(.+)>|Converter<(.+)>|EventHandler<(.+)>|EventHandler|Func|Predicate)\??$""")
    private val WELL_KNOWN = mapOf("int" to "i", "long" to "l", "string" to "s", "bool" to "b", "double" to "d", "float" to "f", "decimal" to "d", "char" to "c", "object" to "o", "byte" to "b")

    /** `Func<IServiceProvider, object> implementationFactory` -> `serviceProvider => `; null when the type is not a delegate the names are known for. */
    fun forParameter(declared: String): LambdaSuggestion? {
        val type = declared.trim().let { if (it.contains(' ') && !it.endsWith(">")) it.substringBeforeLast(' ') else it }.trim()
        val match = DELEGATE.matchEntire(type) ?: return null
        val kind = match.groupValues[1]
        val inner = match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }
        if (kind.startsWith("Expression<")) return forParameter(inner.orEmpty())
        val arguments = inner?.let(::splitGenericArguments).orEmpty()
        val parameterTypes = when {
            kind.startsWith("Func") -> arguments.dropLast(1)
            kind.startsWith("Converter") -> arguments.take(1)
            kind.startsWith("EventHandler") -> return LambdaSuggestion(listOf("sender", "e"))
            kind.startsWith("Comparison") -> return LambdaSuggestion(listOf("x", "y"))
            else -> arguments // Action, Predicate
        }
        return LambdaSuggestion(names(parameterTypes))
    }

    fun splitGenericArguments(text: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        for ((i, c) in text.withIndex()) {
            when (c) {
                '<', '(', '[' -> depth++
                '>', ')', ']' -> depth--
                ',' -> if (depth == 0) { parts += text.substring(start, i).trim(); start = i + 1 }
            }
        }
        parts += text.substring(start).trim()
        return parts.filter { it.isNotEmpty() }
    }

    /** `IServiceProvider` -> `serviceProvider`, `TService` -> `service`, `int` -> `i`, `Order[]` -> `orders`, `List<Order>` -> `orders`; repeats get numbers. */
    fun names(types: List<String>): List<String> {
        val result = ArrayList<String>()
        for (type in types) {
            val base = nameOf(type)
            var name = base
            var n = 1
            while (name in result) name = base + (++n)
            result += name
        }
        return result
    }

    private fun nameOf(type: String): String {
        var t = type.trim().removeSuffix("?")
        val array = t.endsWith("[]")
        t = t.removeSuffix("[]").substringAfterLast('.')
        val collection = t.matches(Regex("""(?:I?(?:List|Enumerable|Collection|ReadOnlyList|ReadOnlyCollection|Set|HashSet|Array|Queue|Stack))<.+>"""))
        if (collection) t = t.substringAfter('<').substringBeforeLast('>').substringAfterLast('.')
        t = t.substringBefore('<')
        WELL_KNOWN[t]?.let { return if (array || collection) it + "s" else it }
        if (t.length > 1 && t[0] == 'I' && t[1].isUpperCase()) t = t.substring(1)
        if (t.length > 1 && t[0] == 'T' && t[1].isUpperCase()) t = t.substring(1)
        if (t.isEmpty() || t == "T") return "x"
        val name = t.replaceFirstChar { it.lowercase() }
        return if (array || collection) name + "s" else name
    }

    /** Right after `(` or `,` of a call (whitespace aside), where an argument begins. */
    fun atArgumentStart(text: CharSequence, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t' || text[i] == '\n' || text[i] == '\r')) i--
        return i >= 0 && (text[i] == '(' || text[i] == ',')
    }
}
