package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.probe.PlatformApiProbe
import io.github.dotnetsupport.probe.PlatformApiProbeDialog
import io.github.dotnetsupport.probe.ProbeCheck
import io.github.dotnetsupport.probe.ProbeReport
import io.github.dotnetsupport.probe.ProbeStatus

class PlatformApiProbeTest : BasePlatformTestCase() {
    @Suppress("unused")
    open class Sample(val name: String) {
        @JvmField val field = 1
        protected fun guarded(a: Int, b: Int) = a + b
        fun plain() = Unit
        private fun hidden() = Unit
    }

    fun testMembersAreSpelledAsInTheExpectedFile() {
        assertEquals(setOf("field", "getName/0", "guarded/2", "plain/0", "<init>/1"), PlatformApiProbe.members(Sample::class.java))
        assertEquals(listOf("gone/1", "plain/3"), PlatformApiProbe.missingMembers(Sample::class.java, listOf("plain/0", "gone/1", "field", "plain/3")))
    }

    fun testExpectedFileCoversTheDapModule() {
        val areas = PlatformApiProbe.expected().getAsJsonObject("areas")
        assertEquals(setOf("dap"), areas.keySet())
        val dap = areas.getAsJsonObject("dap")
        assertTrue(dap.getAsJsonArray("extensionPoints").any { it.asJsonObject.get("name").asString == "com.intellij.platform.dap.debugAdapterSupportProvider" })
        assertTrue("createXDebugProcess" in dap.getAsJsonObject("classes").getAsJsonObject("com.intellij.platform.dap.DebugAdapterDescriptor").getAsJsonArray("members").toString())
    }

    /** Whatever the test IDE has, the probe answers for every expected item and never throws. */
    fun testProbeRunsHere() {
        val report = PlatformApiProbe.run(project)
        val expected = PlatformApiProbe.expected().getAsJsonObject("areas")
        for (area in listOf("dap")) {
            assertEquals(expected.getAsJsonObject(area).getAsJsonObject("classes").size(), report.of(area, PlatformApiProbe.CLASS).size)
            assertTrue(report.of(area, PlatformApiProbe.EXTENSION_POINT).isNotEmpty())
        }
        // the runner is looked up by its id, which is not the id of its extension in the descriptor of the module
        if (report.of("dap", PlatformApiProbe.EXTENSION_POINT).all { it.status == ProbeStatus.OK }) {
            assertEquals(listOf(ProbeStatus.OK), report.of("dap", PlatformApiProbe.RUNNER).map { it.status })
        }
        assertEquals(com.intellij.openapi.application.ApplicationInfo.getInstance().build.asString(), report.ide["build"])
        println("Platform API probe in tests: " + PlatformApiProbeDialog.summary(report))
    }

    /**
     * The debugger is the plugin's own DAP client on XDebugger and needs no module of the platform: IntelliJ IDEA Community and its forks
     * (GIGA IDE) have no DAP module at all; the client of the C# language server, which needed the LSP module, is gone (0.1.262).
     */
    fun testDebuggerNeedsNoModuleOfThePlatform() {
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        assertFalse("intellij.platform.dap" in pluginXml)
        assertFalse("io.github.dotnetsupport.dap" in pluginXml)
        assertNull(javaClass.getResource("/io.github.dotnetsupport.dap.xml"))
        assertTrue("io.github.dotnetsupport.debugger.DotNetDebugRunner" in pluginXml)
        assertFalse("io.github.dotnetsupport.roslyn" in pluginXml)
        assertFalse("intellij.platform.lsp" in pluginXml)
    }

    fun testJsonIsCompact() {
        val report = ProbeReport(
            mapOf("name" to "Some IDE", "build" to "XX-1"), "IU-1",
            listOf(
                ProbeCheck("dap", PlatformApiProbe.EXTENSION_POINT, "a.point", ProbeStatus.MISSING),
                ProbeCheck("dap", PlatformApiProbe.CLASS, "a.Ok", ProbeStatus.OK, "not visible to the plugin without a dependency on the module"),
                ProbeCheck("dap", PlatformApiProbe.CLASS, "a.Gone", ProbeStatus.MISSING),
                ProbeCheck("dap", PlatformApiProbe.CLASS, "a.Changed", ProbeStatus.PARTIAL, "1 of 5 members are missing", listOf("launch/2")),
            ),
        )
        val json = JsonParser.parseString(PlatformApiProbe.toJson(report)).asJsonObject
        assertEquals("XX-1", json.getAsJsonObject("ide").get("build").asString)
        assertEquals("IU-1", json.get("comparedWith").asString)
        val dap = json.getAsJsonObject("dap")
        assertEquals("MISSING", dap.getAsJsonObject("extension_points").get("a.point").asString)
        val classes = dap.getAsJsonObject("classes")
        assertEquals(listOf(3, 1, 1), listOf("expected", "ok", "notVisibleToThePlugin").map { classes.get(it).asInt })
        assertEquals("[\"a.Gone\"]", classes.getAsJsonArray("missing").toString())
        assertEquals("[\"launch/2\"]", classes.getAsJsonObject("partial").getAsJsonArray("a.Changed").toString())

        assertTrue("module is absent" in PlatformApiProbeDialog.summary(report))
    }

    fun testActionIsInTheMenu() {
        val actions = ActionManager.getInstance()
        val menu = actions.getAction("DotNet.MainMenu") as com.intellij.openapi.actionSystem.DefaultActionGroup
        assertTrue(menu.childActionsOrStubs.any { actions.getId(it) == "DotNet.PlatformApiProbe" })
    }
}
