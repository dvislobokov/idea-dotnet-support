// The state of CodeAnalysisService (0.1.77, D3 / D4): which projects have generated files and how many, whether they are fresh, which files
// have analyzer diagnostics and which ones. __ACTION__: `state` (just print), `refresh` (Refresh Generated Files of every C# project of the
// solution), `analyze:<project path>` (Run Code Analysis of that project). Run again until the output settles: the helper answers in the
// background (its first build takes up to a minute).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
importClass(com.intellij.openapi.vfs.LocalFileSystem)

var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var service = project.getService(loader.loadClass("io.github.dotnetsupport.codeanalysis.CodeAnalysisService"))
var action = "__ACTION__"
var text = "project: " + project.getName() + ", root: " + service.outputRoot() + ", analyzers active: " + service.getAnalyzersActive() + "\n"
if (action == "refresh") {
    var solutions = project.getService(loader.loadClass("io.github.dotnetsupport.solution.SolutionService"))
    var list = new java.util.ArrayList()
    var model = loader.loadClass("io.github.dotnetsupport.msbuild.CompilationModel")
    ApplicationManager.getApplication().runReadAction(new java.lang.Runnable({ run: function () {
        var files = com.intellij.psi.search.FilenameIndex.getAllFilesByExt(project, "csproj", com.intellij.psi.search.GlobalSearchScope.projectScope(project))
        var it = files.iterator()
        while (it.hasNext()) { var f = it.next(); if (String(f.getPath()).indexOf("/Broken/") < 0 && String(f.getPath()).indexOf("/NetFramework/") < 0) list.add(f) }
    } }))
    service.refreshGenerated(list)
    text += "refresh asked for " + list.size() + " projects\n"
} else if (action.indexOf("analyze:") == 0) {
    var file = LocalFileSystem.getInstance().refreshAndFindFileByPath(action.substring(8))
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () { service.runCodeAnalysis(java.util.List.of(file)) } }), ModalityState.nonModal())
    text += "Run Code Analysis of " + file.getName() + "\n"
}
var csproj = com.intellij.openapi.application.ReadAction.compute(function () {
    return com.intellij.psi.search.FilenameIndex.getAllFilesByExt(project, "csproj", com.intellij.psi.search.GlobalSearchScope.projectScope(project))
})
var it = csproj.iterator()
while (it.hasNext()) {
    var f = it.next()
    var state = service.generatedState(f)
    if (state == null) continue
    var run = state.getRun()
    text += f.getName() + ": " + run.getFiles().size() + " generated files (" + run.getFramework() + ", " + run.getMilliseconds() + " ms, helper " + (run.getWorkingSet() / 1048576).toFixed(0) +
        " MB), fresh " + service.isGeneratedFresh(f) + (run.getErrors().isEmpty() ? "" : ", errors " + run.getErrors()) + "\n"
}
text
