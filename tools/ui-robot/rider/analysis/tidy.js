importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.wm.ToolWindowManager)
var r = "@@@"
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var ps = ProjectManager.getInstance().getOpenProjects()
    var project = ps[ps.length - 1]
    var ns = com.intellij.notification.NotificationsManager.getNotificationsManager().getNotificationsOfType(com.intellij.notification.Notification, project)
    for (var i = 0; i < ns.length; i++) ns[i].expire()
    r += "expired " + ns.length + "\n"
    var twm = ToolWindowManager.getInstance(project)
    var ids = twm.getToolWindowIds()
    for (var i = 0; i < ids.length; i++) { var tw = twm.getToolWindow(ids[i]); if (tw.isVisible() && ids[i] != "__KEEP__") { tw.hide(null); r += "hid " + ids[i] + "\n" } }
    r += "tool windows: " + java.lang.String.join(", ", ids) + "\n"
} }))
r
