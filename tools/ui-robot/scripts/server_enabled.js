// Turns roslyn-language-server on or off as the checkbox of the Language Server page does on Apply: __ENABLED__ = `true` | `false` | `keep`,
// then RoslynLanguageServerSettings.CHANGED with restart from a write-safe EDT context. With the server off every feature is NATIVE
// (CSharpFeatures.native) and nothing is skipped as "covered by the server": the plugin's own answers can be checked alone. Prints whether it
// is enabled and whether the server of the last open project is ready (RoslynServerStatus.isReady; tools/diag/check_errors.py waits for it).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var settingsClass = loader.loadClass("io.github.dotnetsupport.lsp.RoslynLanguageServerSettings")
var settings = ApplicationManager.getApplication().getService(settingsClass)
var wanted = "__ENABLED__"
if (wanted != "keep") {
    settings.getState().setEnabled(wanted == "true")
    var topicField = settingsClass.getDeclaredField("CHANGED")
    topicField.setAccessible(true)
    var topic = topicField.get(null)
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        ApplicationManager.getApplication().getMessageBus().syncPublisher(topic).settingsChanged(true)
    } }), ModalityState.nonModal())
}
var projects = ProjectManager.getInstance().getOpenProjects()
var ready = "?"
try {
    var status = loader.loadClass("io.github.dotnetsupport.lsp.RoslynServerStatus")
    var companion = status.getField("Companion").get(null)
    ready = companion.getClass().getMethod("isReady", loader.loadClass("com.intellij.openapi.project.Project")).invoke(companion, projects[projects.length - 1])
} catch (e) { ready = "? " + e }
"server enabled " + settings.getState().getEnabled() + ", ready " + ready
