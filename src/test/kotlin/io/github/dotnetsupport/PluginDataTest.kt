package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.DotNetExecutionProbe
import io.github.dotnetsupport.cli.DotNetExecutionProbe.Outcome
import io.github.dotnetsupport.cli.DotNetExecutionProbe.Result
import io.github.dotnetsupport.cli.DotNetHelper
import io.github.dotnetsupport.cli.DotNetLogs
import io.github.dotnetsupport.cli.PluginData
import io.github.dotnetsupport.cli.PluginDataDecision
import io.github.dotnetsupport.cli.PluginDataRule
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Where the plugin keeps its data, and when it moves it: the rule of a machine with an execution policy (fapolicyd, `noexec`). */
class PluginDataTest : BasePlatformTestCase() {
    private val default = Path.of("/home/u/.cache/JetBrains/IC2026.1/dotnet-support")
    private val workHome = Path.of("/home/work/u")
    private val fallback = workHome.resolve(PluginData.FALLBACK)
    private val ok = Outcome(Result.OK)
    private val denied = Outcome(Result.NOT_EXECUTABLE, "a program there exited with 126")

    private fun decide(windows: Boolean = false, property: String? = null, setting: String = "", probe: (Path) -> Outcome = { ok },
                       homes: List<Path> = listOf(workHome), usable: (Path) -> String? = { null }, owned: (Path) -> Boolean = { true }) =
        PluginDataRule.decide(windows, property, setting, default, probe, homes, usable, {}, owned)

    fun testTheRootComesFromThePropertyThenTheSettingThenTheSystemDirectory() {
        assertEquals(Path.of("/p"), PluginData.resolve("/p", "/s", default))
        assertEquals(Path.of("/s"), PluginData.resolve(" ", "/s", default))
        assertEquals(default, PluginData.resolve(null, "  ", default))
    }

    fun testNothingIsDecidedOnWindowsOrUnderTheProperty() {
        assertEquals(PluginDataDecision.Keep, decide(windows = true, probe = { denied }))
        assertEquals(PluginDataDecision.Keep, decide(property = "/shared/helpers", probe = { denied }))
    }

    fun testTheDefaultStaysWhereProgramsRun() {
        assertEquals(PluginDataDecision.Keep, decide(probe = { ok }))
        assertEquals("an unknown probe is no reason to move", PluginDataDecision.Keep, decide(probe = { Outcome(Result.UNKNOWN, "no /bin/true") }))
    }

    fun testTheDataMovesToTheWorkHomeWhenProgramsCannotRunInTheDefault() {
        val probed = ArrayList<Path>()
        val decision = decide(probe = { probed.add(it); if (it == default) denied else ok })
        assertEquals(PluginDataDecision.Relocate(default, fallback, denied.reason), decision)
        assertEquals("the fallback is probed before the move", listOf(default, fallback), probed)
    }

    fun testWithoutAUsableWorkHomeTheUserIsTold() {
        assertEquals(PluginDataDecision.Notify(default, denied.reason), decide(probe = { denied }, homes = emptyList()))
        assertEquals("our directory under it is someone else's", PluginDataDecision.Notify(default, denied.reason, listOf("$fallback: not the user's own, or writable by others"), fallback),
            decide(probe = { if (it == default) denied else ok }, owned = { false }))
        assertEquals("programs cannot run in the work home either", PluginDataDecision.Notify(default, denied.reason, listOf("$fallback: ${denied.reason}"), fallback),
            decide(probe = { denied }))
        assertEquals("a work home that is not there is no suggestion", PluginDataDecision.Notify(default, denied.reason, listOf("$workHome: does not exist"), null),
            decide(probe = { denied }, usable = { "does not exist" }))
        assertEquals("the first work home that takes it is the one", PluginDataDecision.Relocate(default, Path.of("/home/work/u@corp").resolve(PluginData.FALLBACK), denied.reason),
            decide(probe = { if (it == default) denied else ok }, homes = listOf(workHome, Path.of("/home/work/u@corp")), owned = { it != fallback }))
    }

    /**
     * A colleague's machine (0.1.220): the work home is the group's, or its owner is named otherwise than the process sees itself — the data
     * did not move ("choose another directory"), though a directory chosen there by hand worked. Only our own directory under it is checked.
     */
    fun testTheWorkHomeNeedNotBeTheUsersOwn() {
        val checked = ArrayList<Path>()
        assertEquals(PluginDataDecision.Relocate(default, fallback, denied.reason),
            decide(probe = { if (it == default) denied else ok }, owned = { checked.add(it); true }))
        assertEquals("ownership is asked of our directory, not of the work home", listOf(fallback), checked)
    }

    fun testTheWorkHomesInTheListingOfHomeWork() {
        assertEquals(listOf(Path.of("/home/work/ivan"), Path.of("/home/work/Ivan"), Path.of("/home/work/ivan@CORP.LOCAL")),
            PluginDataRule.workHomes("ivan", "/home/ivan", listOf("petr", "ivan@CORP.LOCAL", "Ivan", "CORP.LOCAL@admin")))
    }

    fun testAChosenDirectoryIsKeptWithoutTheDefaultBeingProbed() {
        val probed = ArrayList<Path>()
        assertEquals(PluginDataDecision.Keep, decide(setting = fallback.toString(), probe = { probed.add(it); ok }))
        assertEquals(listOf(fallback), probed)
        assertEquals(PluginDataDecision.Notify(Path.of("/opt/x"), denied.reason), decide(setting = "/opt/x", probe = { denied }))
    }

    fun testWorkHomesOfLocalAndDomainAccounts() {
        assertEquals(listOf(Path.of("/home/work/ivan")), PluginDataRule.workHomes("ivan", "/home/ivan"))
        assertEquals(listOf(Path.of("/home/work/corp@ivan"), Path.of("/home/work/ivan@corp")), PluginDataRule.workHomes("corp@ivan", "/home/corp@ivan"))
        assertEquals(listOf(Path.of("/home/work/CORP\\ivan"), Path.of("/home/work/ivan@CORP"), Path.of("/home/work/ivan")),
            PluginDataRule.workHomes("CORP\\ivan", "/home/ivan"))
    }

    fun testTheProcessesGetTheTemporaryDirectoryOfThePluginOnlyWhenMoved() {
        val tmp = Path.of("/home/work/u/.cache/dotnet-support/tmp")
        assertEquals(mapOf("TMPDIR" to tmp.toString()), PluginData.environment(windows = false, relocated = true) { tmp })
        assertEquals(emptyMap<String, String>(), PluginData.environment(windows = false, relocated = false) { tmp })
        assertEquals(emptyMap<String, String>(), PluginData.environment(windows = true, relocated = true) { tmp })
    }

    fun testADeniedAccessIsTheDirectoryAndOtherFailuresAreNot() {
        assertTrue(DotNetExecutionProbe.deniedByPolicy(134, "Failed to map file: /home/u/x/AssemblyIndexer.dll: Operation not permitted"))
        assertTrue(DotNetExecutionProbe.deniedByPolicy(1, "Permission denied"))
        assertFalse(DotNetExecutionProbe.deniedByPolicy(150, "You must install or update .NET to run this application."))
        assertFalse(DotNetExecutionProbe.deniedByPolicy(0, "6"))
    }

    fun testEveryLocationIsUnderTheChosenRoot() {
        val root = Files.createTempDirectory("plugin-data").toRealPath()
        PluginData.setRootForTests(root)
        try {
            assertTrue(PluginData.isRelocated)
            fun under(file: File) = assertTrue("$file is not under $root", file.toPath().normalize().startsWith(root))
            under(DotNetHelper.root())
            under(io.github.dotnetsupport.index.IndexerTool.root())
            under(io.github.dotnetsupport.index.IndexerTool.indexDirectory())
            assertEquals(root.resolve(PluginData.LOGS), DotNetLogs.root)
            under(DotNetLogs.pluginDirectory.toFile())
            under(DotNetLogs.commandsDirectory.toFile())
            under(io.github.dotnetsupport.debugger.DotNetDebuggerLogs.directory.toFile())
            under(io.github.dotnetsupport.lsp.RoslynLanguageServerConfigurable.defaultLogDirectory())
            under(io.github.dotnetsupport.build.DotNetBuildOptions.defaultLogFolder())
            under(io.github.dotnetsupport.roslyn.RoslynTokensCache().store.directory)
            under(io.github.dotnetsupport.monitor.MemoryDumps.dumpFile("App (1234)", 1234))
            under(PluginData.tempFile("probe", ".txt").also { it.delete() })
            under(PluginData.tempDirectory("probe").also { it.delete() })
        } finally {
            PluginData.setRootForTests(null)
            root.toFile().deleteRecursively()
        }
        assertFalse(PluginData.isRelocated)
        assertFalse(DotNetHelper.root().toPath().startsWith(root))
    }

    fun testTheDataAndTheLogsMoveTogetherAndBack() {
        val base = Files.createTempDirectory("plugin-data-move")
        try {
            val before = PluginData.Layout(base.resolve("system/dotnet-support"), base.resolve("home/idea-dotnet-logs"))
            write(before.data.resolve("index/v6/a.idx"), "index")
            write(before.data.resolve("tmp/scratch.txt"), "scratch")
            write(before.logs.resolve("plugin/plugin-20261016.log"), "log")
            val work = base.resolve("work/.cache/dotnet-support")
            val after = PluginData.Layout(work, work.resolve(PluginData.LOGS))

            assertEquals(emptyList<String>(), PluginData.move(before, after))
            assertEquals("index", Files.readString(work.resolve("index/v6/a.idx")))
            assertEquals("log", Files.readString(work.resolve("logs/plugin/plugin-20261016.log")))
            assertFalse(Files.exists(before.data.resolve("index")))
            assertFalse(Files.exists(before.logs.resolve("plugin")))
            assertTrue("temporary files are not data", Files.exists(before.data.resolve("tmp/scratch.txt")))
            assertFalse(Files.exists(work.resolve("tmp")))

            assertEquals(emptyList<String>(), PluginData.move(after, before))
            assertEquals("index", Files.readString(before.data.resolve("index/v6/a.idx")))
            assertEquals("the logs go back to their own folder, not into the data", "log", Files.readString(before.logs.resolve("plugin/plugin-20261016.log")))
            assertFalse(Files.exists(before.data.resolve(PluginData.LOGS)))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    fun testATargetInsideTheSourceIsRefused() {
        val base = Files.createTempDirectory("plugin-data-nested")
        try {
            write(base.resolve("data/index/a"), "x")
            val from = PluginData.Layout(base.resolve("data"), base.resolve("logs"))
            val failed = PluginData.move(from, PluginData.Layout(base.resolve("data/inner"), base.resolve("data/inner/logs")))
            assertEquals(listOf(base.resolve("data").normalize().toString()), failed)
            assertTrue(Files.exists(base.resolve("data/index/a")))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }
}
