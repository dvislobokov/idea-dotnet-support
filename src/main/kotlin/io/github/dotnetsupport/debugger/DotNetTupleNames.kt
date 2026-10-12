package io.github.dotnetsupport.debugger

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dotnetsupport.csharp.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpFrameLocals
import io.github.dotnetsupport.lang.semantic.SemanticType
import java.util.concurrent.CompletableFuture

/**
 * The element names a tuple was declared with, by position ([names], null where an element has none), and the same for the elements that
 * are tuples themselves ([elements]).
 */
class TupleShape(val names: List<String?>, val elements: List<TupleShape?>)

/**
 * Names of tuple elements in the debugger, as in Rider: the adapter shows `Item1`, `Item2` (a `ValueTuple` has no names at runtime), the
 * names are in the declared type of the variable in the source.
 */
object DotNetTupleNames {
    private val ITEM = Regex("Item([1-7])")
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The names of [type] when it is a tuple that names an element somewhere (a nested tuple included); null otherwise. */
    fun shape(type: SemanticType?): TupleShape? {
        val tuple = type as? SemanticType.Library ?: return null
        if (!tuple.fullName.startsWith("System.ValueTuple`") || tuple.arguments.size !in 2..7) return null
        val names = tuple.arguments.indices.map { i -> tuple.tupleNames?.getOrNull(i)?.takeIf { it.isNotEmpty() } }
        val elements = tuple.arguments.map(::shape)
        return if (names.all { it == null } && elements.all { it == null }) null else TupleShape(names, elements)
    }

    /** A value the adapter types as a tuple (`System.ValueTuple<int, string>`): only those are worth a look into the source. */
    fun isTupleType(type: String?): Boolean = type != null && (type.startsWith("System.ValueTuple") || type.startsWith("("))

    /** A whole expression that is a plain name: Evaluate of `tuple` finds its declaration, of `Get()` does not. */
    fun isName(expression: String): Boolean = IDENTIFIER.matches(expression.trim())

    /** The names to show for the children [names] (of the adapter) of a value of [shape]: `ItemN` becomes its declared name. */
    fun names(names: List<String>, shape: TupleShape?): List<String> = names.map { child(it, shape).first }

    /** One child: the name to show and the shape of its own value. */
    fun child(name: String, shape: TupleShape?): Pair<String, TupleShape?> {
        if (shape == null) return name to null
        val index = ITEM.matchEntire(name)?.groupValues?.get(1)?.toInt()?.minus(1)?.takeIf { it < shape.names.size } ?: return name to null
        return (shape.names[index] ?: name) to shape.elements[index]
    }

    /** `(1, "tuple")` of the adapter → `(Id: 1, Name: "tuple")`, nested tuples too; [value] as it is when it does not split into the elements. */
    fun summary(value: String, shape: TupleShape): String {
        val parts = split(value) ?: return value
        if (parts.size != shape.names.size) return value
        return parts.mapIndexed { i, part ->
            val shown = shape.elements[i]?.let { summary(part, it) } ?: part
            shape.names[i]?.let { "$it: $shown" } ?: shown
        }.joinToString(", ", "(", ")")
    }

    /** The top-level elements of `(a, b, …)`, skipping commas in strings, chars and brackets; null for anything else. */
    private fun split(value: String): List<String>? {
        val text = value.trim()
        if (text.length < 2 || text.first() != '(' || text.last() != ')') return null
        val parts = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        var start = 1
        var i = 1
        while (i < text.length - 1) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (--depth < 0) return null
                ',' -> if (depth == 0) { parts += text.substring(start, i).trim(); start = i + 1 }
            }
            i++
        }
        if (quote != null || depth != 0) return null
        parts += text.substring(start, text.length - 1).trim()
        return parts
    }
}

/** The source position of a stopped frame: what its variables were declared as. */
class DotNetFrameSource(private val project: Project, private val file: VirtualFile, private val line: Int) {
    /** The tuple shapes of the variables [names] that have one; off the thread of the adapter, under a read action. */
    fun shapes(names: Collection<String>): CompletableFuture<Map<String, TupleShape>> = CompletableFuture.supplyAsync({
        ReadAction.compute<Map<String, TupleShape>, RuntimeException> {
            if (project.isDisposed || !file.isValid) return@compute emptyMap()
            val psi = PsiManager.getInstance(project).findFile(file) as? CSharpFile ?: return@compute emptyMap()
            CSharpFrameLocals.declaredTypes(psi, line, names).mapNotNull { (name, type) -> DotNetTupleNames.shape(type)?.let { name to it } }.toMap()
        }
    }, AppExecutorUtil.getAppExecutorService())
}
