package io.github.dotnetsupport

import com.google.gson.JsonParser
import io.github.dotnetsupport.debugger.DotNetDebugModules
import junit.framework.TestCase

/** The Modules tab: rows from the adapter's `module` events (as `dotnet-debugger` sends them, `dap-probe/logs`), in the order of loading. */
class DebugModulesTest : TestCase() {
    private fun event(text: String) = JsonParser.parseString(text).asJsonObject

    fun testModulesComeAndGoByTheirEvents() {
        val modules = DotNetDebugModules()
        modules.event(event("""{"reason":"new","module":{"id":1,"name":"System.Private.CoreLib.dll","path":"/usr/share/dotnet/System.Private.CoreLib.dll","symbolStatus":"Skipped loading symbols."}}"""))
        modules.event(event("""{"reason":"new","module":{"id":2,"name":"App.dll","path":"/src/App/bin/App.dll","symbolStatus":"Symbols loaded.","isUserCode":true,"version":"1.0.0.0"}}"""))
        modules.event(event("""{"reason":"new","module":{"id":3,"name":"Plugin.dll","path":"/src/Plugin.dll"}}"""))
        assertEquals(listOf("System.Private.CoreLib.dll", "App.dll", "Plugin.dll"), modules.all().map { it.name })
        val app = modules.all()[1]
        assertEquals(DotNetDebugModules.Module("2", 2, "App.dll", "/src/App/bin/App.dll", "Symbols loaded.", "1.0.0.0", true), app)
        // symbols found later: the row changes in place, keeps its order
        modules.event(event("""{"reason":"changed","module":{"id":3,"name":"Plugin.dll","path":"/src/Plugin.dll","symbolStatus":"Symbols loaded."}}"""))
        assertEquals("Symbols loaded.", modules.all()[2].symbols)
        assertEquals(3, modules.all()[2].order)
        modules.event(event("""{"reason":"removed","module":{"id":3}}"""))
        assertEquals(listOf("System.Private.CoreLib.dll", "App.dll"), modules.all().map { it.name })
    }
}
