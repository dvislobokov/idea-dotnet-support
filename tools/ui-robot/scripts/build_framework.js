// The target framework of the toolbar (DotNetBuildSettings.framework), as choosing it in the combo box: __FRAMEWORK__ = `net10.0`, or empty
// for the default (the first framework of a multi-targeted project). C# files get other `#if` symbols (CompilationModel.CHANGED).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var settings = project.getService(loader.loadClass("io.github.dotnetsupport.build.DotNetBuildSettings"))
var framework = "__FRAMEWORK__"
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    settings.setFramework(framework == "" ? null : framework)
} }), ModalityState.nonModal())
"framework: " + settings.getFramework() + ", available: " + settings.availableFrameworks()
