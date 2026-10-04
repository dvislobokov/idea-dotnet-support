// The end of a run of tools/ui-robot/baseline.py: stops the recording of baseline_start.js and closes the projects under __DIR__ (their server
// stops with it) without saving the editors. The IDE goes to the Welcome screen when it was the only project.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.project.ex.ProjectManagerEx)
const rec = java.lang.System.getProperties().get("dotnet.baseline")
if (rec != null) rec.put("_stop", "yes")
// the scripts have put the text of the files back: saving writes what is on disk already, and the next copy of the target at the same
// path does not meet an unsaved document ("Changes have been made to ... in memory and on disk")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments() } }))
const open = ProjectManager.getInstance().getOpenProjects()
let closed = "not open"
for (var p = 0; p < open.length; p++) {
    if (open[p].getBasePath() == null || String(open[p].getBasePath()).toLowerCase().indexOf("__DIR__".toLowerCase()) != 0) continue
    var project = open[p]
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () { ProjectManagerEx.getInstanceEx().forceCloseProject(project) } }))
    closed = "closed"
}
closed
