// What ProjectAssemblies.references gives for the project __PROJECT__ without an assets file, and whether the indexer tool is built.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var solutions = project.getService(loader.loadClass("io.github.dotnetsupport.solution.SolutionService"))
var vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath("__PROJECT__")
var msbuild = solutions.msBuildProject(vfile)
var requestClass = loader.loadClass("io.github.dotnetsupport.index.ProjectAssemblies$Request")
var ctor = requestClass.getConstructors()[0]
var text = "ctor params: " + ctor.getParameterCount() + "\n"
var request = ctor.newInstance(null, new java.io.File(vfile.getParent().getPath()), null, "net471", msbuild, null)
var assembliesClass = loader.loadClass("io.github.dotnetsupport.index.ProjectAssemblies")
var instance = assembliesClass.getField("INSTANCE").get(null)
try {
    var refs = instance.references(request)
    text += "framework: " + refs.getFramework() + ", assemblies: " + refs.getAssemblies().size() + ", projects: " + refs.getProjects().size() + ", outputs: " + refs.getProjectOutputs().size() + ", forIndex: " + refs.getForIndex().size() + "\n"
    var a = refs.getAssemblies()
    for (var i = 0; i < 3 && i < a.size(); i++) text += "  " + a.get(i) + " exists=" + a.get(i).isFile() + "\n"
} catch (e) {
    text += "references threw: " + e + "\n"
    var t = e.javaException || null
    if (t != null) { var frames = t.getStackTrace(); for (var i = 0; i < frames.length && i < 10; i++) text += "   " + frames[i] + "\n" }
}
text
