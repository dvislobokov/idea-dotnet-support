package io.github.dotnetsupport.lang

import com.intellij.codeInsight.lookup.LookupElement
import io.github.dotnetsupport.settings.DotNetSettings

/**
 * "Exclude from completion" (task 3.11 of docs/COMPLETION_GAPS.md), as Rider's Exclude from import: types and namespaces the C# list never
 * offers. A pattern is a qualified name (`System.Data.DataTable` — that type; `System.Data` — the namespace and the ones inside it) or a
 * wildcard (`System.Data.*`, `*.Internal.*`, `Foo*`), compared without regard to case. Set on Settings | .NET, one pattern per line.
 */
object CSharpCompletionExclusions {
    /** Patterns from the text of the setting: one per line, blank lines and `#` comments skipped. */
    fun parse(text: String): List<String> = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

    fun patterns(): List<String> = DotNetSettings.getInstance().completionExclusions

    /** Whether [patterns] cover [qualifiedName] (a type or a namespace). */
    fun matches(patterns: List<String>, qualifiedName: String): Boolean = patterns.any { matches(it, qualifiedName) }

    fun matches(pattern: String, qualifiedName: String): Boolean {
        if (pattern.isEmpty()) return false
        if ('*' !in pattern) return qualifiedName.equals(pattern, ignoreCase = true) || qualifiedName.startsWith("$pattern.", ignoreCase = true)
        val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) }
        return Regex(regex, RegexOption.IGNORE_CASE).matches(qualifiedName)
    }

    /** The filter hook of the lists: whether [qualifiedName] is left out now. Cheap when nothing is excluded. */
    fun excludes(qualifiedName: String): Boolean = patterns().let { it.isNotEmpty() && matches(it, qualifiedName) }

    /** The types of the native list carry their qualified name as the object of the element; a row of the not imported extensions, its namespace. */
    fun excludes(element: LookupElement): Boolean {
        val patterns = patterns()
        if (patterns.isEmpty()) return false
        val namespace = element.getUserData(NativeCSharpImportCompletion.NOT_IMPORTED)
        if (namespace != null && namespace.isNotEmpty() && matches(patterns, namespace)) return true
        return (element.`object` as? String)?.takeIf { '.' in it }?.let { matches(patterns, it) } ?: false
    }
}
