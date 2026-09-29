package io.github.dotnetsupport.lang

/**
 * What goes after the name of a method that is chosen in the completion list, by the text around it: shared by the items of the
 * language server and by the ones of the index of assemblies.
 */
object CSharpCalls {
    /** What is inserted after the name, and where the caret lands in it. */
    class Call(val text: String, val caret: Int)

    private val CHAIN = Regex("""(?:(?:this|base|[A-Za-z_]\w*)\??\.)+$""")
    private val AWAIT = Regex("""\bawait\s+$""")
    private val RETURN = Regex("""(?:^|[\s({;])return\s+$""")
    private val ARROW = Regex("""=>\s*$""")
    private val ASSIGNMENT = Regex("""(?:^|[^=!<>])=\s*$""")
    private val DECLARED = Regex("""(?:^|[\s({;])[A-Za-z_][\w.<>,\[\]? ]*\s+[A-Za-z_]\w*\s*=\s*$""")
    private val BLOCK_WORDS = setOf("else", "try", "do", "finally", "checked", "unchecked", "unsafe", "get", "set", "init", "add", "remove")

    /**
     * `()` with the caret inside; `();` for a method that returns nothing or whose call ends the statement, when nothing follows on
     * the line; `<>()` with the caret between the angle brackets when the type arguments have to be written. A method that takes no
     * arguments in any of its overloads gets the caret after the call.
     */
    fun call(returnsNothing: Boolean, takesArguments: Boolean, typeArguments: Boolean, endsStatement: Boolean, restOfLine: CharSequence): Call {
        val statement = (returnsNothing || endsStatement) && restOfLine.isBlank()
        val text = (if (typeArguments) "<>" else "") + (if (statement) "();" else "()")
        return Call(text, if (typeArguments || takesArguments) 1 else text.length)
    }

    /**
     * Whether the call whose name begins at [nameStart] is the last thing of its statement, whatever it returns: the value of a
     * declaration (`decimal sum = Total(|);`), of an assignment, of `return`, of an expression body. An assignment in the braces of an
     * object initializer ends with a comma, not with a semicolon.
     */
    fun endsStatement(text: CharSequence, nameStart: Int): Boolean {
        if (nameStart !in 0..text.length) return false
        val lineStart = text.lastIndexOf('\n', nameStart - 1) + 1
        val line = text.subSequence(lineStart, nameStart).toString().replace(CHAIN, "").replace(AWAIT, "")
        if (RETURN.containsMatchIn(line) || ARROW.containsMatchIn(line)) return true
        if (!ASSIGNMENT.containsMatchIn(line)) return false
        return DECLARED.containsMatchIn(line) || !inInitializer(text, lineStart)
    }

    /** The brace that is open at [offset] belongs to `new Order { ... }` or `with { ... }`, not to a block of statements. */
    fun inInitializer(text: CharSequence, offset: Int): Boolean {
        var depth = 0
        var i = offset - 1
        while (i >= 0) {
            when (text[i]) {
                '}' -> depth++
                '{' -> if (depth == 0) break else depth--
            }
            i--
        }
        if (i < 0) return false
        var before = i - 1
        while (before >= 0 && text[before].isWhitespace()) before--
        if (before < 0) return false
        // a block follows `)` of a header, `=>` of a lambda, `else`, `try`, `do`, `finally`; an initializer follows a type or `with`
        if (text[before] == ')' || (text[before] == '>' && before > 0 && text[before - 1] == '=')) return false
        val word = text.subSequence(0, before + 1).takeLastWhile { it.isLetter() }.toString()
        return word !in BLOCK_WORDS
    }

    fun restOfLine(text: CharSequence, offset: Int): CharSequence {
        if (offset !in 0..text.length) return ""
        val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return text.subSequence(offset, end)
    }

    /**
     * Whether the type arguments of a generic method have to be written: one of them is in no parameter, so nothing infers it
     * (`Array.Empty<T>()`, `Convert<TSource, TResult>(TSource value)`). [typeParameters] are their names, [parameterTypes] the types
     * of the parameters, the one a method extends among them.
     */
    fun needsTypeArguments(typeParameters: List<String>, parameterTypes: List<String>): Boolean {
        val inferredFrom = parameterTypes.joinToString(" ")
        return typeParameters.any { parameter -> !Regex("""(?<![\w.])""" + Regex.escape(parameter) + """(?!\w)""").containsMatchIn(inferredFrom) }
    }
}

/** The `using` directives of a file, and the one that is added when something of a namespace that is not imported is chosen. */
object CSharpUsings {
    private val USING = Regex("""(?m)^[ \t]*(global\s+)?using\s+(static\s+)?(?:([A-Za-z_]\w*)\s*=\s*)?([A-Za-z_][\w.]*)\s*;""")
    private val GENERATED = Regex("""(?m)^[ \t]*global\s+using\s+(?:global::)?([A-Za-z_][\w.]*)\s*;""")
    private val NAMESPACE = Regex("""(?m)^[ \t]*namespace\s+([A-Za-z_][\w.]*)""")

    /** What `<ImplicitUsings>enable</ImplicitUsings>` imports, by the SDK of the project. */
    private val IMPLICIT_BASE = listOf("System", "System.Collections.Generic", "System.IO", "System.Linq", "System.Net.Http", "System.Threading", "System.Threading.Tasks")
    private val IMPLICIT_WEB = listOf(
        "System.Net.Http.Json", "Microsoft.AspNetCore.Builder", "Microsoft.AspNetCore.Hosting", "Microsoft.AspNetCore.Http", "Microsoft.AspNetCore.Routing",
        "Microsoft.Extensions.Configuration", "Microsoft.Extensions.DependencyInjection", "Microsoft.Extensions.Hosting", "Microsoft.Extensions.Logging",
    )
    private val IMPLICIT_WORKER = listOf("Microsoft.Extensions.Configuration", "Microsoft.Extensions.DependencyInjection", "Microsoft.Extensions.Hosting", "Microsoft.Extensions.Logging")

    fun implicit(sdk: String?, enabled: Boolean): Set<String> {
        if (!enabled) return emptySet()
        val name = sdk.orEmpty().substringBefore('/')
        return when {
            name.equals("Microsoft.NET.Sdk.Web", ignoreCase = true) || name.equals("Microsoft.NET.Sdk.Razor", ignoreCase = true) -> (IMPLICIT_BASE + IMPLICIT_WEB).toSet()
            name.equals("Microsoft.NET.Sdk.Worker", ignoreCase = true) -> (IMPLICIT_BASE + IMPLICIT_WORKER).toSet()
            else -> IMPLICIT_BASE.toSet()
        }
    }

    /** The namespaces of `global using X;` in a file: the one the build generates (`obj/.../App.GlobalUsings.g.cs`) or one written by hand. */
    fun global(text: CharSequence): Set<String> = GENERATED.findAll(text).mapTo(LinkedHashSet()) { it.groupValues[1] }

    /** The namespaces the file imports itself; `using static` and aliases are not imports of a namespace. */
    fun imported(text: CharSequence): Set<String> =
        USING.findAll(text).filter { it.groupValues[2].isEmpty() && it.groupValues[3].isEmpty() }.mapTo(LinkedHashSet()) { it.groupValues[4] }

    /** The types `using static` brings the members of: `System.Console`. */
    fun importedStatically(text: CharSequence): Set<String> =
        USING.findAll(text).filter { it.groupValues[2].isNotEmpty() }.mapTo(LinkedHashSet()) { it.groupValues[4] }

    /** A namespace is seen without a `using` from itself and from what is nested in it. */
    fun isVisible(namespace: String, text: CharSequence, alsoImported: Set<String> = emptySet()): Boolean {
        if (namespace.isEmpty() || namespace in alsoImported || namespace in imported(text)) return true
        return NAMESPACE.findAll(text).any { declared -> declared.groupValues[1] == namespace || declared.groupValues[1].startsWith("$namespace.") }
    }

    class Insertion(val offset: Int, val text: String)

    /**
     * Where `using [namespace];` goes and with what around it: among the directives of the file in their order — `System` first, as
     * `dotnet format` sorts them, the rest by the alphabet — or at the top of the file, a blank line after it. Null when it is there.
     */
    fun insertion(text: CharSequence, namespace: String): Insertion? {
        if (namespace.isEmpty() || namespace in imported(text)) return null
        val directives = USING.findAll(text).filter { it.groupValues[1].isEmpty() }.toList()
        val line = "using $namespace;"
        if (directives.isEmpty()) {
            val start = headerEnd(text)
            return Insertion(start, line + "\n" + (if (text.startsWith("\n", start) || start >= text.length) "" else "\n"))
        }
        val plain = directives.filter { it.groupValues[2].isEmpty() && it.groupValues[3].isEmpty() }
        val after = plain.lastOrNull { order(it.groupValues[4]) <= order(namespace) }
        if (after == null) {
            val first = directives.first()
            return Insertion(lineStart(text, first.range.first), line + "\n")
        }
        return Insertion(lineEnd(text, after.range.last), "\n" + line)
    }

    /** `System` and what is under it first, then the alphabet, the case of the letters aside. */
    private fun order(namespace: String): String = (if (namespace == "System" || namespace.startsWith("System.")) "0" else "1") + namespace.lowercase()

    /** After the comments and the directives of the preprocessor the file begins with. */
    private fun headerEnd(text: CharSequence): Int {
        var offset = 0
        while (offset < text.length) {
            val end = lineEnd(text, offset)
            val line = text.subSequence(offset, end).trim()
            if (!(line.startsWith("//") || line.startsWith("#nullable") || line.startsWith("#pragma"))) break
            offset = (end + 1).coerceAtMost(text.length)
            if (end >= text.length) break
        }
        return offset
    }

    private fun lineStart(text: CharSequence, offset: Int): Int = if (offset <= 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    private fun lineEnd(text: CharSequence, offset: Int): Int = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
}
