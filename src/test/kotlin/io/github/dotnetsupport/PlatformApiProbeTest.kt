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

    fun testExpectedFileCoversBothModules() {
        val areas = PlatformApiProbe.expected().getAsJsonObject("areas")
        assertEquals(setOf("lsp", "dap"), areas.keySet())
        val dap = areas.getAsJsonObject("dap")
        assertTrue(dap.getAsJsonArray("extensionPoints").any { it.asJsonObject.get("name").asString == "com.intellij.platform.dap.debugAdapterSupportProvider" })
        assertTrue("createXDebugProcess" in dap.getAsJsonObject("classes").getAsJsonObject("com.intellij.platform.dap.DebugAdapterDescriptor").getAsJsonArray("members").toString())
        assertTrue(areas.getAsJsonObject("lsp").getAsJsonObject("classes").has("com.intellij.platform.lsp.api.LspClientDescriptor"))
    }

    /** Whatever the test IDE has, the probe answers for every expected item and never throws. */
    fun testProbeRunsHere() {
        val report = PlatformApiProbe.run(project)
        val expected = PlatformApiProbe.expected().getAsJsonObject("areas")
        for (area in listOf("lsp", "dap")) {
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

    /** What needs the DAP module of the platform is a content module: the plugin loads without it where there is no DAP. */
    fun testDapPartIsAContentModule() {
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        // the descriptor the tests see went through patchPluginXml, which reformats it
        assertTrue(Regex("""<module name="io\.github\.dotnetsupport\.dap"\s*/>""").containsMatchIn(pluginXml))
        assertFalse("intellij.platform.dap" in pluginXml)
        val module = javaClass.getResource("/io.github.dotnetsupport.dap.xml")!!.readText()
        assertTrue("package=\"io.github.dotnetsupport.dap\"" in module)
        assertTrue(Regex("""<module name="intellij\.platform\.dap"\s*/>""").containsMatchIn(module))
        // every class the module registers is in its package, and nothing outside of it refers to the package
        Regex("implementation=\"([^\"]+)\"").findAll(module).forEach { assertTrue(it.groupValues[1], it.groupValues[1].startsWith("io.github.dotnetsupport.dap.")) }
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
