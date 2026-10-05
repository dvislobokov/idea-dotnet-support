importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.project.ProjectManager)
var r = "@@@"
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var ps = ProjectManager.getInstance().getOpenProjects()
    var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(ps[ps.length - 1]).getToolWindow("__ID__")
    if ("__HIDE__" == "yes") tw.hide(null); else tw.show(null)
    r += "ok " + tw.getStripeTitle()
} }))
r
