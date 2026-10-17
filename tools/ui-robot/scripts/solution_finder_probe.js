// What the plugin's solution finder sees in the folder of the open project (DotNetModuleSetup asks it whether to make a module): the
// solutions and the project files it finds, and what DotNetModuleSetup.needsModule answers for them.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
importClass(com.intellij.openapi.module.ModuleManager)

var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
ReadAction.compute(function () {
    var out = new java.lang.StringBuilder()
    var root = com.intellij.openapi.project.ProjectUtil.guessProjectDir(project)
    out.append("root: " + root + "\n")
    var finder = loader.loadClass("io.github.dotnetsupport.solution.SolutionFinder").getField("INSTANCE").get(null)
    var found = finder.find(root, true)
    out.append("solutions: " + found.getSolutions() + "\nprojects: " + found.getProjects() + "\n")
    out.append("modules: " + ModuleManager.getInstance(project).getModules().length + "\n")
    return out.toString()
})
