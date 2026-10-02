package io.github.dotnetsupport

import com.intellij.execution.ExecutionException
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.DailyLog
import io.github.dotnetsupport.cli.DotNetLogs
import io.github.dotnetsupport.cli.LogEntry
import io.github.dotnetsupport.cli.LogLevel
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.cli.PluginLogsToolWindowFactory
import io.github.dotnetsupport.lsp.RoslynPolicy
import io.github.dotnetsupport.sdk.DotNetRuntimes
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The journal of the plugin: one readable line per event, without stack traces for the failures of external programs, in a file the
 * user can send and in the Plugin Logs tool window.
 */
class PluginLogTest : BasePlatformTestCase() {
    fun testLineFormat() {
        val time = LocalDateTime.of(2026, 10, 2, 11, 22, 49, 613_000_000)
        assertEquals("11:22:49.613 WARN  [roslyn] server stderr: You must install or update .NET", PluginLog.line(time, LogLevel.WARN, "roslyn", "server stderr: You must install or update .NET"))
        // the lines of a multi-line text stay under the first one, so that grep by the category and the eye both find them
        assertEquals("11:22:49.613 ERROR [sdk] first\n" + " ".repeat(25) + "second", PluginLog.line(time, LogLevel.ERROR, "sdk", "first\nsecond"))
    }

    fun testExpectedFailuresAreTheirMessageOnly() {
        assertEquals("The 'dotnet' executable is not found", PluginLog.describe(ExecutionException("The 'dotnet' executable is not found")))
        assertEquals("Cannot run program \"x\": error=2", PluginLog.describe(java.io.IOException("Cannot run program \"x\": error=2")))
        // a wrapped expected failure is still expected, and the cause adds what the wrapper does not say
        assertEquals("build failed: Stream closed", PluginLog.describe(RuntimeException("build failed", java.io.IOException("Stream closed"))))
        assertTrue(PluginLog.isExpected(java.util.concurrent.ExecutionException(java.io.IOException("Stream closed"))))
    }

    fun testUnexpectedFailuresNameTheClassAndTheFrameOfThePlugin() {
        val described = PluginLog.describe(IllegalStateException("no client"))
        assertTrue(described, described.startsWith("IllegalStateException: no client at PluginLogTest."))
        assertTrue("one frame, not a trace", described.lines().size == 1)
        assertEquals("NullPointerException", PluginLog.describe(NullPointerException()).substringBefore(" at "))
    }

    fun testTheJournalIsWrittenAndReplayed() {
        PluginLog.warn("test", "something failed", ExecutionException("exit code 150"))
        PluginLog.info("test", "two\nlines")
        val file = DotNetLogs.pluginDirectory.resolve("plugin-${java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDate.now())}.log")
        val text = Files.readString(file)
        assertTrue(text, text.lines().any { it.matches(Regex("""\d\d:\d\d:\d\d\.\d{3} WARN  \[test] something failed: exit code 150""")) })
        assertTrue(text, text.contains("[test] two\n") && text.contains(" ".repeat(26) + "lines\n"))

        val seen = ArrayList<LogEntry>()
        val disposable = Disposer.newDisposable()
        try {
            PluginLog.subscribe(disposable) { seen += it }
            assertTrue("what was logged before is replayed", seen.any { it.category == "test" && it.text == "two\nlines" })
            val before = seen.size
            PluginLog.error("test", "live")
            assertEquals(before + 1, seen.size)
            assertEquals(LogLevel.ERROR, seen.last().level)
        } finally {
            Disposer.dispose(disposable)
        }
        val after = seen.size
        PluginLog.info("test", "after dispose")
        assertEquals("a disposed subscriber hears nothing", after, seen.size)
        assertEquals("plugin-20260901.log", DailyLog("plugin", "plugin-").outdated(listOf("plugin-20260901.log", "plugin-20260920.log", "commands-20260901.log"), LocalDate.of(2026, 9, 22)).single())
    }

    fun testCommandResultsGoToTheJournal() {
        DotNetLogs.commandFinished("Build", "exit code 1 in 2.0 s", failed = true, lastLines = "error CS1002: ; expected")
        val entry = PluginLog.entries.last { it.category == DotNetLogs.CATEGORY_COMMANDS }
        assertEquals(LogLevel.WARN, entry.level)
        assertEquals("Build: exit code 1 in 2.0 s\nerror CS1002: ; expected", entry.text)
    }

    /** The error stream of a tool built for .NET 10 started by a host that has only .NET 8 (idea.log of 2026-10-02). */
    fun testMissingFrameworkIsReadFromTheHost() {
        val stderr = """
            You must install or update .NET to run this application.

            App: /home/user/.dotnet/tools/.store/roslyn-language-server/5.12.0/tools/net10.0/linux-x64/roslyn-language-server
            Architecture: x64
            Framework: 'Microsoft.NETCore.App', version '10.0.0' (x64)
            .NET location: /usr/share/dotnet-sdk-8.8.403

            The following frameworks were found:
              8.0.10 at [/usr/share/dotnet-sdk-8.8.403/shared/Microsoft.NETCore.App]

            Learn more:
            https://aka.ms/dotnet/app-launch-failed
        """.trimIndent()
        val missing = RoslynPolicy.missingFramework(stderr)!!
        assertEquals("Microsoft.NETCore.App 10.0.0", missing.required)
        assertEquals("/usr/share/dotnet-sdk-8.8.403", missing.location)
        assertEquals(listOf("8.0.10 at [/usr/share/dotnet-sdk-8.8.403/shared/Microsoft.NETCore.App]"), missing.found)
        assertEquals("The server needs Microsoft.NETCore.App 10.0.0; the dotnet host at /usr/share/dotnet-sdk-8.8.403 has only: 8.0.10 at [/usr/share/dotnet-sdk-8.8.403/shared/Microsoft.NETCore.App].", missing.describe())
        assertNull("another crash is not a missing runtime", RoslynPolicy.missingFramework("Unhandled exception. System.IO.IOException: broken pipe"))
        assertNull(RoslynPolicy.missingFramework(""))
    }

    fun testDotNetRootForTheServer() {
        assertEquals("/opt/dotnet10", RoslynPolicy.dotnetRoot("/opt/dotnet10", "/usr/share/dotnet/dotnet"))
        assertEquals(java.io.File("/usr/share/dotnet/dotnet").absoluteFile.parent, RoslynPolicy.dotnetRoot("", "/usr/share/dotnet/dotnet"))
        assertEquals(java.io.File("C:\\Program Files\\dotnet\\dotnet.exe").absoluteFile.parent, RoslynPolicy.dotnetRoot(null, "C:\\Program Files\\dotnet\\dotnet.exe"))
        assertNull(RoslynPolicy.dotnetRoot(null, null))
    }

    fun testRuntimesAreParsed() {
        val runtimes = DotNetRuntimes.parse("""
            Microsoft.AspNetCore.App 8.0.10 [/usr/share/dotnet-sdk-8.8.403/shared/Microsoft.AspNetCore.App]
            Microsoft.NETCore.App 8.0.10 [/usr/share/dotnet-sdk-8.8.403/shared/Microsoft.NETCore.App]
            Microsoft.NETCore.App 10.0.0-rc.1.25451.107 [C:\Program Files\dotnet\shared\Microsoft.NETCore.App]
            not a runtime line
        """.trimIndent())
        assertEquals(listOf("Microsoft.AspNetCore.App 8.0.10", "Microsoft.NETCore.App 8.0.10", "Microsoft.NETCore.App 10.0.0-rc.1.25451.107"), runtimes.map { "${it.name} ${it.version}" })
        assertEquals("/usr/share/dotnet-sdk-8.8.403/shared/Microsoft.NETCore.App", runtimes[1].location)
        assertTrue(DotNetRuntimes.hasNetCoreApp(runtimes, 10))
        assertTrue(DotNetRuntimes.hasNetCoreApp(runtimes, 8))
        assertFalse("ASP.NET Core alone is not the base runtime", DotNetRuntimes.hasNetCoreApp(runtimes.take(1), 8))
        assertFalse(DotNetRuntimes.hasNetCoreApp(runtimes, 9))
    }

    fun testMenuAndToolWindow() {
        val actions = ActionManager.getInstance()
        val menu = (actions.getAction("DotNet.MainMenu") as DefaultActionGroup).childActionsOrStubs.map { actions.getId(it) }
        assertTrue(menu.toString(), menu.indexOf("DotNet.PluginLogs") in 0 until menu.indexOf("DotNet.ShowPluginLogs"))
        assertEquals("Plugin Logs", actions.getAction("DotNet.PluginLogs").templatePresentation.text)
        assertEquals("Open Logs Folder", actions.getAction("DotNet.ShowPluginLogs").templatePresentation.text)
        // not on the stripe until asked for: a log is for the day something does not work
        assertFalse(PluginLogsToolWindowFactory().shouldBeAvailable(project))
        PluginLogsToolWindowFactory.show(project)
    }
}
