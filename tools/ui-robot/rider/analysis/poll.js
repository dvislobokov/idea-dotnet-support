importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var out = new java.lang.StringBuilder("@@@")
app.invokeLater(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var cb = ActionManager.getInstance().tryToExecute(ActionManager.getInstance().getAction("__ACTION__"), null, editor.getContentComponent(), "EditorPopup", true)
    out.append("executed\n")
} }))
for (var t = 0; t < 16; t++) {
    java.lang.Thread.sleep(250)
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        var ws = java.awt.Window.getWindows(); var n = 0; var names = ""
        for (var w = 0; w < ws.length; w++) if (ws[w].isShowing() && !(ws[w] instanceof java.awt.Frame)) { n++; names += ws[w].getClass().getSimpleName() + " " }
        var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
        var pops = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().getChildPopups(frame.getRootPane())
        var lp = frame.getLayeredPane().getComponentsInLayer(javax.swing.JLayeredPane.POPUP_LAYER)
        out.append(t + ": windows " + n + " " + names + " popups " + pops.size() + " popupLayer " + lp.length + "\n")
    } }), ModalityState.any())
}
out.toString()
