// __MODE__ = show: shows the action group __GROUP__ as a popup menu over the editor (as the IDE builds menus, with update());
// dump: prints the items of the shown popup menu; hide: closes it.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var out = new java.lang.StringBuilder("@@@")
function popups() {
    var res = []
    var ws = java.awt.Window.getWindows()
    function walk(c) { if (c instanceof javax.swing.JPopupMenu && c.isShowing()) res.push(c); if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) walk(ch[i]) } }
    for (var i = 0; i < ws.length; i++) if (ws[i].isShowing()) walk(ws[i])
    return res
}
if ("__MODE__" == "show") {
    app.invokeLater(new java.lang.Runnable({ run: function () {
        var g = ActionManager.getInstance().getAction("__GROUP__")
        var menu = ActionManager.getInstance().createActionPopupMenu("__PLACE__", g)
        var ed = FileEditorManager.getInstance(project).getSelectedTextEditor()
        var comp = "__ON__" == "editor" && ed != null ? ed.getContentComponent() : com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project).getRootPane()
        if ("__ON__" == "editor" && ed != null) { var p = ed.visualPositionToXY(ed.getCaretModel().getVisualPosition()); menu.getComponent().show(comp, p.x + 20, p.y + 10) }
        else menu.getComponent().show(comp, 40, 40)
    } }), ModalityState.any())
    out.append("shown")
} else if ("__MODE__" == "dump") {
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        var ps = popups()
        for (var i = 0; i < ps.length; i++) {
            out.append("== popup menu " + ps[i].getWidth() + "x" + ps[i].getHeight() + "\n")
            var ch = ps[i].getComponents()
            for (var k = 0; k < ch.length; k++) {
                var c = ch[k]
                if (c instanceof javax.swing.JSeparator) { out.append("  -----\n"); continue }
                if (c instanceof javax.swing.JMenuItem) {
                    var acc = c.getAccelerator()
                    var sc = ""
                    try { sc = c.getClientProperty("accelerator") || "" } catch (e) {}
                    try { if (c.getFirstShortcutText) sc = c.getFirstShortcutText() } catch (e) {}
                    out.append("  " + (c instanceof javax.swing.JMenu ? "[>] " : "") + c.getText() + (sc ? "   " + sc : "") + (c.isEnabled() ? "" : "  (disabled)") + "\n")
                } else out.append("  <" + c.getClass().getSimpleName() + ">\n")
            }
        }
    } }), ModalityState.any())
} else {
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        var ps = popups()
        for (var i = 0; i < ps.length; i++) ps[i].setVisible(false)
        javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath()
        out.append("hidden " + ps.length)
    } }), ModalityState.any())
}
out.toString()
