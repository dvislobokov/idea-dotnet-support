// Picks the solution the Roslyn server loads, as a click in the chooser does (RoslynWorkspace.solutionChosen): __SOLUTION__ = a file name of
// the opened folder, e.g. `DebugPlayground.sln` (the playground has two solutions and the server waits in CHOOSING_SOLUTION). Prints the phase
// of the workspace before the choice and whether it is loaded; run again (with the same name) until `loaded: true` — a choice made in another
// phase is not repeated. Closes the chooser popup if it is still open.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.extensions.ExtensionPointName)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var providers = ExtensionPointName.create("com.intellij.platform.lsp.integrationProvider").getExtensionList()
var text = "project: " + project.getName() + "\n"
for (var i = 0; i < providers.size(); i++) {
    var provider = providers.get(i)
    if (provider.getClass().getName().indexOf("Roslyn") < 0) continue
    var workspace = project.getService(provider.getClass().getClassLoader().loadClass("io.github.dotnetsupport.roslyn.RoslynWorkspace"))
    var phase = String(workspace.getPhase().name())
    text += "phase: " + phase + ", loaded: " + workspace.isLoaded() + ", chosen: " + workspace.getState().getSolution() + "\n"
    if (phase == "CHOOSING_SOLUTION") {
        ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
            workspace.solutionChosen("__SOLUTION__")
            var popups = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().getChildPopups(com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project).getRootPane())
            for (var q = 0; q < popups.size(); q++) popups.get(q).cancel()
        } }), ModalityState.nonModal())
        text += "chose __SOLUTION__\n"
    }
}
text
