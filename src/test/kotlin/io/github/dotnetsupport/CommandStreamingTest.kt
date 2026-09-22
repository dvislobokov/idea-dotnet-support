package io.github.dotnetsupport

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.CommandOutput
import io.github.dotnetsupport.cli.DotNetCli

class CommandStreamingTest : BasePlatformTestCase() {
    private class Recorder : CommandOutput {
        val events = ArrayList<String>()
        val text = StringBuilder()

        @Synchronized override fun commandStarted(command: GeneralCommandLine) { events += "started " + command.parametersList.list.joinToString(" ") }
        @Synchronized override fun text(text: String, isError: Boolean) { this.text.append(text) }
        @Synchronized override fun commandFinished(exitCode: Int) { events += "finished " + (exitCode == 0) }
        @Synchronized override fun finished(succeeded: Boolean) { events += "all done $succeeded" }
    }

    /** Background tasks run synchronously in tests; the success callback is queued to EDT. */
    private fun run(vararg commands: List<String>): Pair<Recorder, Boolean> {
        val recorder = Recorder()
        var succeeded = false
        val commandLines = commands.map { DotNetCli.commandLine(null, *it.toTypedArray()) }
        DotNetCli.runInBackground(project, "test", commandLines, output = recorder) { succeeded = true }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return recorder to succeeded
    }

    fun testOutputIsStreamedCommandByCommand() {
        if (DotNetCli.findExecutable() == null) return
        val (recorder, succeeded) = run(listOf("--version"), listOf("--list-sdks"))
        assertEquals(listOf("started --version", "finished true", "started --list-sdks", "finished true", "all done true"), recorder.events)
        // the version, then the list of SDKs with their locations
        assertTrue(recorder.text.toString(), Regex("""\d+\.\d+\.\d+""").containsMatchIn(recorder.text) && recorder.text.contains("["))
        assertTrue(succeeded)
    }

    fun testFailureStopsTheRun() {
        if (DotNetCli.findExecutable() == null) return
        val (recorder, succeeded) = run(listOf("no-such-command-of-dotnet"), listOf("--version"))
        // the second command is not started, the success callback is not called
        assertEquals(listOf("started no-such-command-of-dotnet", "finished false", "all done false"), recorder.events)
        assertTrue(recorder.text.isNotBlank())
        assertFalse(succeeded)
    }
}
