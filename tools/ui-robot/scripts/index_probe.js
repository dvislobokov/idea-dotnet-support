// The plugin's own assembly index of the project file __PROJECT__: complete?, and whether __TYPES__ (full names, comma-separated) are found.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var service = project.getService(loader.loadClass("io.github.dotnetsupport.index.AssemblyIndexService"))
var vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath("__PROJECT__")
var text = "project: " + vfile + "\ncomplete: " + service.isComplete(vfile) + "\n"
var set = service.symbols(vfile)
text += "indexes: " + set.getIndexes().size() + "\n"
var names = "__TYPES__".split(",")
for (var i = 0; i < names.length; i++) text += names[i] + ": " + set.findType(names[i]) + "\n"
var model = project.getService(loader.loadClass("io.github.dotnetsupport.msbuild.CompilationModel"))
var cs = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
text += "projectOf(" + cs.getName() + "): " + model.projectOf(cs) + "\n"
text
