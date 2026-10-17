package io.github.dotnetsupport

import io.github.dotnetsupport.settings.CSharpOptions
import junit.framework.TestCase
import java.io.File

/**
 * Every option of Settings | .NET | C# Editor is read by a feature of the plugin (rule of the user 2026-10-06: an option on the page works):
 * its section is named somewhere in the main sources besides the list of the page itself.
 */
class CSharpOptionsTest : TestCase() {
    private val sources: List<Pair<String, String>> by lazy {
        val root = File("src/main/kotlin/io/github/dotnetsupport")
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace('\\', '/') to it.readText() }
            .filterNot { (path, _) -> path == "settings/CSharpEditorSettings.kt" }
            .toList()
    }

    fun testEveryOptionIsReadByAFeature() {
        val unread = CSharpOptions.ALL.filter { option -> sources.none { (_, text) -> "\"${option.section}\"" in text } }
        assertEquals("on the page and read by nothing", emptyList<String>(), unread.map { it.section })
    }

    fun testTheSectionsAreUnique() {
        assertEquals(CSharpOptions.ALL.size, CSharpOptions.ALL.map { it.section }.toSet().size)
    }
}
