// The source of a C# feature (CSharpFeature, CSHARP_PSI_MIGRATION.md): __FEATURE__ = `USAGE_KINDS`, `SYNTAX_TREE`, ...; __SOURCE__ = `NATIVE` /
// `ROSLYN` / `keep`. Set as the Language Server page does on Apply (the settings, then RoslynLanguageServerSettings.CHANGED from a write-safe
// EDT context). Prints the stored choice and the answer of CSharpFeatures.native for the last open project.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var settingsClass = loader.loadClass("io.github.dotnetsupport.lsp.RoslynLanguageServerSettings")
var settings = ApplicationManager.getApplication().getService(settingsClass)
var feature = java.lang.Enum.valueOf(loader.loadClass("io.github.dotnetsupport.lang.CSharpFeature"), "__FEATURE__")
var source = "__SOURCE__"
if (source != "keep") {
    settings.setSource(feature, java.lang.Enum.valueOf(loader.loadClass("io.github.dotnetsupport.lang.CSharpFeatureSource"), source))
    var topicField = settingsClass.getDeclaredField("CHANGED")
    topicField.setAccessible(true)
    var topic = topicField.get(null)
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        ApplicationManager.getApplication().getMessageBus().syncPublisher(topic).settingsChanged(false)
    } }), ModalityState.nonModal())
}
var projects = ProjectManager.getInstance().getOpenProjects()
var features = loader.loadClass("io.github.dotnetsupport.lang.CSharpFeatures")
var native = features.getMethod("native", feature.getClass(), loader.loadClass("com.intellij.openapi.project.Project"))
    .invoke(features.getField("INSTANCE").get(null), feature, projects[projects.length - 1])
"__FEATURE__: source " + settings.source(feature) + ", native " + native
