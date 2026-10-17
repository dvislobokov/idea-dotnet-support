// The plugin's static model of the project file __PROJECT__: legacy?, frameworks, HintPath count and a few, assemblies, the index request's list size.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var solutions = project.getService(loader.loadClass("io.github.dotnetsupport.solution.SolutionService"))
var vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath("__PROJECT__")
var m = solutions.msBuildProject(vfile)
var text = "legacy: " + m.isLegacy() + ", sdk: " + m.getSdk() + ", frameworks: " + m.getTargetFrameworks() + "\n"
var hints = m.getHintPaths()
text += "hintPaths: " + hints.size() + "\n"
var it = hints.entrySet().iterator()
for (var i = 0; i < 3 && it.hasNext(); i++) { var e = it.next(); text += "  " + e.getKey() + " -> " + e.getValue() + "\n" }
text += "assemblies: " + m.getAssemblies().size() + ", projectReferences: " + m.getProjectReferences().size() + "\n"
text += "solutions: " + solutions.solutionFiles() + "\n"
var sol = solutions.solutionFiles().iterator()
while (sol.hasNext()) { var s = sol.next(); text += "  projects of " + s.getName() + ": " + solutions.solution(s).getAllProjects().size() + "\n" }
text
