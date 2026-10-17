// Runs the assembly index refresh for __PROJECT__ on this thread (refreshForTests) and prints what it found or threw.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var service = project.getService(loader.loadClass("io.github.dotnetsupport.index.AssemblyIndexService"))
var vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath("__PROJECT__")
var list = new java.util.ArrayList()
list.add(vfile)
var text = ""
try {
    service.refreshForTests(list)
    text += "refreshed\n"
} catch (e) {
    text += "threw: " + e + "\n"
    var t = e.javaException || e.rhinoException || null
    if (t != null) { var frames = t.getStackTrace(); for (var i = 0; i < frames.length && i < 12; i++) text += "   " + frames[i] + "\n" }
}
text += "complete: " + service.isComplete(vfile) + ", indexes: " + service.symbols(vfile).getIndexes().size() + "\n"
text
