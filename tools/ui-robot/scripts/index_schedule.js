// Asks the assembly index service to refresh now.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var service = project.getService(loader.loadClass("io.github.dotnetsupport.index.AssemblyIndexService"))
service.schedule()
"scheduled for " + project.getName()
