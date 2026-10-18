// The tree of the selected Usages view as text (after a real Alt+F7: caret_after.js + screen.sh key alt+F7), one row per node, indented by
// depth, every row expanded. "no usage view" when there is none.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.usages.UsageViewManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder()
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var view = UsageViewManager.getInstance(project).getSelectedUsageView()
    if (view == null) { out.append("no usage view"); return }
    out.append("usages: " + view.getUsagesCount() + "\n")
    var tree = view.getTree()
    for (var row = 0; row < tree.getRowCount(); row++) tree.expandRow(row)
    for (row = 0; row < tree.getRowCount(); row++) {
        var path = tree.getPathForRow(row)
        for (var depth = 1; depth < path.getPathCount(); depth++) out.append("  ")
        out.append(view.getNodeText(path.getLastPathComponent()) + "\n")
    }
} }), ModalityState.any())
out.toString()
