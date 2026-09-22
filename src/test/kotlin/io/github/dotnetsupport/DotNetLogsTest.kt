package io.github.dotnetsupport

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.DotNetLogs
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime

/** The logs of the plugin: one folder, a line per event with its time, so that a command that hangs shows where it stood still. */
class DotNetLogsTest : BasePlatformTestCase() {
    fun testTheFolderIsNotTheHomeDirectoryInTests() {
        assertEquals("idea-dotnet-logs", DotNetLogs.root.fileName.toString())
        assertFalse("tests must not write into the home directory", DotNetLogs.root.startsWith(System.getProperty("user.home") + "/idea-dotnet-logs"))
        assertEquals(DotNetLogs.root.resolve("dotnet-debugger"), io.github.dotnetsupport.debugger.DotNetDebuggerLogs.directory)
    }

    fun testCommandLines() {
        assertEquals("09:05:03.042 [Installing X 1.0] > dotnet add", DotNetLogs.line(LocalTime.of(9, 5, 3, 42_000_000), "Installing X 1.0", "> dotnet add"))
        DotNetLogs.commandStarted("test", GeneralCommandLine("dotnet", "nuget", "add", "source", "https://feed", "--password", "secret"))
        DotNetLogs.command("test | err", "line one\nline two\n")
        val text = Files.readString(DotNetLogs.commandsDirectory.resolve(DotNetLogs.commandLogName(LocalDate.now())))
        assertTrue(text, text.contains("[test] > dotnet nuget add source https://feed --password ********"))
        assertFalse("passwords are masked", text.contains("secret"))
        assertTrue("every line has its time", text.lines().any { it.matches(Regex("""\d\d:\d\d:\d\d\.\d{3} \[test \| err] line two""")) })
    }

    fun testOldLogsGo() {
        val today = LocalDate.of(2026, 9, 22)
        assertEquals(listOf("commands-20260901.log"),
            DotNetLogs.outdated(listOf("commands-20260901.log", "commands-20260908.log", "commands-20260922.log", "other.txt"), today))
    }
}
