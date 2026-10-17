// The modules of the open project and their content roots, and how the workspace file index sees __FILE__ (forward slashes): whether it is
// content, indexable, and of which kind. A folder opened in IDEA without a module is content that is not indexed (DotNetModuleSetup).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.module.ModuleManager)
importClass(com.intellij.openapi.roots.ModuleRootManager)

var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
ReadAction.compute(function () {
    var out = new java.lang.StringBuilder()
    out.append("project: " + project.getName() + " at " + project.getBasePath() + "\n")
    var modules = ModuleManager.getInstance(project).getModules()
    out.append("modules: " + modules.length + "\n")
    for (var i = 0; i < modules.length; i++) {
        var roots = ModuleRootManager.getInstance(modules[i]).getContentRoots()
        var paths = []
        for (var j = 0; j < roots.length; j++) paths.push(roots[j].getPath())
        out.append("  " + modules[i].getName() + " (" + modules[i].getModuleFilePath() + "): " + paths.join(", ") + "\n")
    }
    var index = com.intellij.workspaceModel.core.fileIndex.WorkspaceFileIndex.getInstance(project)
    out.append("indexable: " + index.isIndexable(vfile) + ", in content: " + index.isInContent(vfile) + "\n")
    return out.toString()
})
