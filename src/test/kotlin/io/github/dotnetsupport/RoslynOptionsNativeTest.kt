package io.github.dotnetsupport

import io.github.dotnetsupport.lsp.NativeSupport
import io.github.dotnetsupport.lsp.RoslynOptions
import junit.framework.TestCase
import java.io.File

/**
 * Every option of Settings | .NET | Language Server works for the plugin's own C# as for the server (rule of the user 2026-10-06), or says
 * why not: [NativeSupport.READ] ones are read by their section somewhere in the main sources outside the server's packages (`lsp/`,
 * `roslyn/`), [NativeSupport.SERVER_ONLY] ones are about the process of the server or a thing the plugin owns elsewhere (NuGet restore),
 * [NativeSupport.PENDING] ones are the debt: the list here is the one to shorten, never to grow.
 */
class RoslynOptionsNativeTest : TestCase() {
    private val sources: List<Pair<String, String>> by lazy {
        val root = File("src/main/kotlin/io/github/dotnetsupport")
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace('\\', '/') to it.readText() }
            .filterNot { (path, _) -> path.startsWith("lsp/") || path.startsWith("roslyn/") }
            .toList()
    }

    fun testEveryReadOptionIsReadByNativeCode() {
        val unread = RoslynOptions.ALL.filter { it.native == NativeSupport.READ }.filter { option -> sources.none { (_, text) -> "\"${option.section}\"" in text } }
        assertEquals("marked READ and read by nothing outside lsp/ and roslyn/", emptyList<String>(), unread.map { it.section })
    }

    fun testNoOtherOptionIsReadByNativeCode() {
        val read = RoslynOptions.ALL.filter { it.native != NativeSupport.READ }.filter { option -> sources.any { (_, text) -> "\"${option.section}\"" in text } }
        assertEquals("read by native code: mark it READ", emptyList<String>(), read.map { it.section })
    }

    fun testServerOnlyOptionsAreAboutTheServerItself() {
        assertEquals(
            listOf(
                "projects.dotnet_enable_automatic_restore",
                "projects.dotnet_enable_file_based_programs",
                "projects.dotnet_enable_file_based_programs_when_ambiguous",
                "projects.dotnet_binary_log_path",
            ),
            RoslynOptions.ALL.filter { it.native == NativeSupport.SERVER_ONLY }.map { it.section },
        )
    }

    /** The debt, by group; a native implementation that starts reading one of them turns it READ and takes it off this list. */
    fun testPendingOptionsAreTheKnownOnes() {
        val pending = RoslynOptions.ALL.filter { it.native == NativeSupport.PENDING }.map { it.section }
        // none since 0.1.122: every option the server reads is read by the plugin too
        val known = listOf<String>()
        assertEquals(known.sorted(), pending.sorted())
    }
}
