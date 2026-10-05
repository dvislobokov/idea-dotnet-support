// Turns roslyn-language-server on or off as the checkbox of the Language Server page does on Apply: __ENABLED__ = `true` | `false`, then
// RoslynLanguageServerSettings.CHANGED with restart from a write-safe EDT context. With the server off every feature is NATIVE
// (CSharpFeatures.native) and nothing is skipped as "covered by the server": the plugin's own answers can be checked alone.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var settingsClass = loader.loadClass("io.github.dotnetsupport.lsp.RoslynLanguageServerSettings")
var settings = ApplicationManager.getApplication().getService(settingsClass)
settings.getState().setEnabled(__ENABLED__)
var topicField = settingsClass.getDeclaredField("CHANGED")
topicField.setAccessible(true)
var topic = topicField.get(null)
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    ApplicationManager.getApplication().getMessageBus().syncPublisher(topic).settingsChanged(true)
} }), ModalityState.nonModal())
"server enabled: " + settings.getState().getEnabled()
