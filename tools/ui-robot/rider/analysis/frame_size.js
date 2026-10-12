importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.project.ProjectManager)
var r = "@@@"
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var ps = ProjectManager.getInstance().getOpenProjects()
    var f = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(ps[ps.length - 1])
    f.setExtendedState(0)
    f.setBounds(0, 0, __W__, __H__)
    f.validate()
    // close balloons / notifications
    try { com.intellij.notification.NotificationsManager.getNotificationsManager() } catch (e) {}
    r += "frame " + f.getBounds()
} }))
r
