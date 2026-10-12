package io.github.dotnetsupport

import com.intellij.execution.RunManager
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetConfigurationType
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.run.HotReloadConsoleFilter
import io.github.dotnetsupport.run.HotReloadOutput
import io.github.dotnetsupport.run.HotReloadState
import io.github.dotnetsupport.run.HotReloadStatus
import io.github.dotnetsupport.run.HotReloadTracker

/** The Hot Reload state of a `dotnet watch` session, read from its output. */
class HotReloadTest : BasePlatformTestCase() {
    // the output of `dotnet watch run` on SDK 10.0.401 (captured): start, hot reload, a runtime failure after a signature change, a compilation
    // error, a rude edit; the build lines and the output of the application in between
    private val sdk10 = listOf(
        "dotnet watch 🔥 Hot reload enabled. For a list of supported edits, see https://aka.ms/dotnet/hot-reload.",
        "dotnet watch 💡 Press Ctrl+R to restart.",
        "  App -> C:\\src\\App\\bin\\Debug\\net10.0\\App.dll",
        "Build succeeded.",
        "dotnet watch ⌚ Loading projects ...",
        "dotnet watch ⌚ Loaded 1 project(s) in 0,3s.",
        "dotnet watch ⌚ Waiting for changes",
        "dotnet watch ⌚ File updated: .\\Program.cs",
        "dotnet watch 🔥 C# and Razor changes applied in 1148ms.",
        "dotnet watch ⚠ [App (net10.0)] Attempted to invoke a deleted method implementation. Exited and restarting.",
        "dotnet watch 🔥 [App (net10.0)] Restarting application ...",
        "dotnet watch ⌚ [App (net10.0)] Exited",
        "dotnet watch ⌚ Waiting for changes",
        "dotnet watch 🔥 Unable to apply changes due to compilation errors.",
        "dotnet watch ❌ C:\\src\\App\\Program.cs(9,51): error CS0103: The name 'Restarting' does not exist in the current context.",
        "dotnet watch 🔥 C# and Razor changes applied in 55ms.",
        "dotnet watch 🔥 Restart is needed to apply the changes.",
        "dotnet watch ❌ [App (net10.0)] C:\\src\\App\\Program.cs(7,1): error ENC0004: Updating the modifiers of class requires restarting the application.",
        "  ❔ Do you want to restart your app? Yes (y) / No (n) / Always (a) / Never (v)",
    )

    fun testStatesOfSdk10() {
        assertEquals(
            listOf(
                HotReloadState.BUILDING, HotReloadState.RUNNING, HotReloadState.APPLIED, HotReloadState.RESTARTING, HotReloadState.RUNNING,
                HotReloadState.BUILD_FAILED, HotReloadState.APPLIED, HotReloadState.RESTART_NEEDED,
            ),
            transitions(sdk10),
        )
    }

    fun testStatesOfSdk9() {
        // SDK 9.0.301 (captured): a crash of the application, a failed build, a rude edit
        val lines = listOf(
            "dotnet watch 🔥 Hot reload enabled. For a list of supported edits, see https://aka.ms/dotnet/hot-reload.",
            "  💡 Press \"Ctrl + R\" to restart.",
            "dotnet watch ⌚ Building C:\\src\\App\\App.csproj ...",
            "dotnet watch 🔨 Build succeeded: C:\\src\\App\\App.csproj",
            "dotnet watch 🔥 [App (net9.0)] Hot reload succeeded.",
            "Unhandled exception. System.Runtime.CompilerServices.HotReloadException: Build failed. Restarting.",
            "dotnet watch ❌ [App (net9.0)] Exited with error code -532462766",
            "dotnet watch ⏳ Waiting for a file to change before restarting ...",
            "dotnet watch ⌚ Building C:\\src\\App\\App.csproj ...",
            "dotnet watch 🔨 Build failed: C:\\src\\App\\App.csproj",
            "dotnet watch 🔨   Determining projects to restore...",
            "dotnet watch ❌ C:\\src\\App\\Program.cs(9,51): error CS0103: The name 'undefinedThing' does not exist in the current context [C:\\src\\App\\App.csproj]",
            "dotnet watch ⏳ Waiting for a file to change before restarting ...",
            "dotnet watch ⌚ Building C:\\src\\App\\App.csproj ...",
            "dotnet watch 🔨 Build succeeded: C:\\src\\App\\App.csproj",
            "dotnet watch ⌚ Unable to apply hot reload, restart is needed to apply the changes.",
            "  ❔ Do you want to restart your app? Yes (y) / No (n) / Always (a) / Never (v)",
        )
        assertEquals(
            listOf(
                HotReloadState.BUILDING, HotReloadState.RUNNING, HotReloadState.APPLIED, HotReloadState.EXITED, HotReloadState.BUILDING,
                HotReloadState.BUILD_FAILED, HotReloadState.BUILDING, HotReloadState.RUNNING, HotReloadState.RESTART_NEEDED,
            ),
            transitions(lines),
        )
    }

    fun testStatesOfSdk8() {
        assertEquals(HotReloadState.RUNNING, HotReloadOutput.parse("dotnet watch 🚀 Started"))
        assertEquals(HotReloadState.APPLIED, HotReloadOutput.parse("dotnet watch 🔥 Hot reload of changes succeeded."))
        assertEquals(HotReloadState.RESTART_NEEDED, HotReloadOutput.parse("dotnet watch ⌚ Unable to apply hot reload because of a rude edit."))
        assertEquals(HotReloadState.BUILD_FAILED, HotReloadOutput.parse("dotnet watch ❌ Unable to apply hot reload due to compilation errors."))
        assertEquals(HotReloadState.RESTARTING, HotReloadOutput.parse("dotnet watch 🔄 Restart requested."))
        assertEquals(HotReloadState.EXITED, HotReloadOutput.parse("dotnet watch ❌ Exited with error code 1"))
    }

    fun testOtherLinesSayNothing() {
        // the build, the application, diagnostics with any words in them, the hint without "dotnet watch"
        for (line in listOf("Build succeeded.", "Restarting the worker", "info: Microsoft.Hosting.Lifetime[0]", "  💡 Press Ctrl+R to restart.",
            "dotnet watch ❌ C:\\src\\Program.cs(1,1): error CS1002: Exited expected", "dotnet watch ⌚ File updated: .\\Program.cs", "")) {
            assertNull(line, HotReloadOutput.parse(line))
        }
        // emojis suppressed (DOTNET_WATCH_SUPPRESS_EMOJIS) or replaced with question marks by a console without Unicode
        assertEquals(HotReloadState.APPLIED, HotReloadOutput.parse("dotnet watch : C# and Razor changes applied in 5ms."))
        assertEquals(HotReloadState.RUNNING, HotReloadOutput.parse("dotnet watch ?? Waiting for changes"))
        assertEquals("Restarting application ...", HotReloadOutput.message("dotnet watch 🔥 [App (net10.0)] Restarting application ..."))
    }

    fun testTheStatusIsKeptOnTheProcess() {
        val handler = NopProcessHandler()
        HotReloadTracker.attach(handler, null)
        handler.startNotify()
        handler.notifyTextAvailable("dotnet watch ⌚ Waiting for changes\n", ProcessOutputTypes.STDOUT)
        assertEquals(HotReloadState.RUNNING, handler.getUserData(HotReloadTracker.KEY)?.state)
        // a colored process handler hands over pieces of a line
        handler.notifyTextAvailable("dotnet watch 🔥 Restart is ", ProcessOutputTypes.STDOUT)
        assertEquals(HotReloadState.RUNNING, handler.getUserData(HotReloadTracker.KEY)?.state)
        handler.notifyTextAvailable("needed to apply the changes.\r\n  ❔ Do you want to restart your app? Yes (y) / No (n)\n", ProcessOutputTypes.STDOUT)
        assertEquals(HotReloadStatus(HotReloadState.RESTART_NEEDED, "Restart is needed to apply the changes."), handler.getUserData(HotReloadTracker.KEY))
    }

    fun testConsoleColorsStateLinesOnly() {
        val filter = HotReloadConsoleFilter(null)
        val line = "dotnet watch 🔥 Unable to apply changes due to compilation errors.\n"
        val result = filter.applyFilter(line, 100 + line.length)!!.resultItems.single()
        assertEquals("Unable to apply changes due to compilation errors.", (line.substring(result.highlightStartOffset - 100, result.highlightEndOffset - 100)))
        assertNull(filter.applyFilter("Hello, World!\n", 14))
        // building is not colored, and without a session there is nothing to link
        assertNull(filter.applyFilter("dotnet watch ⌚ Building C:\\src\\App\\App.csproj ...\n", 50))
    }

    fun testWatchOutputIsInEnglish() {
        if (DotNetCli.findExecutable() == null) return
        val configuration = RunManager.getInstance(project).createConfiguration("hr", DotNetConfigurationType.instance.factory).configuration as DotNetRunConfiguration
        configuration.options.projectPath = "C:/src/HotReload/App.csproj"
        configuration.options.command = DotNetCommand.WATCH
        assertEquals("en", configuration.buildCommandLine().environment["DOTNET_CLI_UI_LANGUAGE"])
        // the user's own value wins
        configuration.options.environment = mutableMapOf("DOTNET_CLI_UI_LANGUAGE" to "ru")
        assertEquals("ru", configuration.buildCommandLine().environment["DOTNET_CLI_UI_LANGUAGE"])
        configuration.options.environment = mutableMapOf()
        configuration.options.command = DotNetCommand.RUN
        assertNull(configuration.buildCommandLine().environment["DOTNET_CLI_UI_LANGUAGE"])
    }

    private fun transitions(lines: List<String>): List<HotReloadState> {
        var status: HotReloadStatus? = null
        val states = mutableListOf<HotReloadState>()
        for (line in lines) {
            val next = HotReloadOutput.next(status, line)
            if (next != null && next.state != status?.state) states += next.state
            status = next
        }
        return states
    }
}
