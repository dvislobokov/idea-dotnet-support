// Lists the open projects (name, base path); __CLOSE__ = a substring of the base path of the projects to close (empty: close nothing).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
var projects = ProjectManager.getInstance().getOpenProjects()
var text = "open: " + projects.length + "\n"
var close = "__CLOSE__"
for (var i = 0; i < projects.length; i++) {
    var p = projects[i]
    text += "project: " + p.getName() + " at " + p.getBasePath() + "\n"
    if (close != "" && String(p.getBasePath()).indexOf(close) >= 0) {
        var target = p
        ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { ProjectManager.getInstance().closeAndDispose(target) } }), ModalityState.nonModal())
        text += "closing " + p.getName() + "\n"
    }
}
text
