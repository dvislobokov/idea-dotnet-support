// Turns the debug log of the C# analysis (Settings | .NET, DotNetSettings.semanticDebugLog) __ON__ (true / false) and prints the state:
// the `semantic` category of the plugin's log (~/idea-dotnet-logs/plugin/plugin-<day>.log) then says where the semantics gave up.
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var settingsClass = loader.loadClass("io.github.dotnetsupport.settings.DotNetSettings")
var settings = settingsClass.getMethod("getInstance").invoke(null)
settingsClass.getMethod("setSemanticDebugLog", java.lang.Boolean.TYPE).invoke(settings, java.lang.Boolean.valueOf("__ON__"))
"semanticDebugLog: " + settingsClass.getMethod("getSemanticDebugLog").invoke(settings)
