// __MODE__ = open: opens Settings on the configurable with id __ID__ (returns at once); dump: prints the options of the shown page
// (labels, check boxes with state, radio buttons, combo values, text fields, tabs; every tab of every tabbed pane when __TABS__ = all);
// close: cancels the dialog.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var mode = "__MODE__"
var out = new java.lang.StringBuilder("@@@")
function settingsDialog() {
    var ws = java.awt.Window.getWindows()
    for (var i = 0; i < ws.length; i++) if (ws[i].isShowing() && ws[i] instanceof java.awt.Dialog && String(ws[i].getTitle()).indexOf("Settings") == 0) return ws[i]
    return null
}
function clean(s) { return s == null ? "" : String(s).replace(/<[^>]*>/g, "").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/\s+/g, " ").trim() }
function pad(d) { var s = ""; for (var i = 0; i < d; i++) s += " "; return s }
var seenText = {}
function walk(c, d, inTree) {
    if (!c.isVisible()) return
    var cls = String(c.getClass().getName()).replace(/^.*\./, "")
    if (cls == "SettingsTreeView" || cls.indexOf("SettingsSearch") >= 0 || cls == "Banner" && false) return
    var line = null
    try {
        if (c instanceof javax.swing.JCheckBox) line = "[" + (c.isSelected() ? "x" : " ") + "] " + clean(c.getText()) + (c.isEnabled() ? "" : " (disabled)")
        else if (c instanceof javax.swing.JRadioButton) line = "(" + (c.isSelected() ? "o" : " ") + ") " + clean(c.getText())
        else if (c instanceof javax.swing.JComboBox) { var it = c.getSelectedItem(); var n = c.getItemCount(); var opts = []; for (var i = 0; i < n && i < 12; i++) opts.push(clean(String(c.getItemAt(i)))); line = "<combo: " + clean(String(it)) + ">" + (n > 1 ? " of {" + opts.join(" / ") + (n > 12 ? " …" : "") + "}" : "") }
        else if (c instanceof javax.swing.JLabel) { var t = clean(c.getText()); if (t.length > 0) line = t }
        else if (c instanceof javax.swing.text.JTextComponent) { var t2 = String(c.getText()); if (t2.length > 0 && t2.length < 200 && c.isEditable()) line = "<field: " + clean(t2) + ">"; else if (!c.isEditable() && t2.length > 0) line = clean(t2).substring(0, 300) }
        else if (c instanceof javax.swing.AbstractButton) { var t3 = clean(c.getText()); if (t3.length > 0) line = "<button: " + t3 + ">" }
        else if (c instanceof com.intellij.ui.SimpleColoredComponent) { var t4 = clean(c.getCharSequence(false)); if (t4.length > 0) line = t4 }
        else if (c instanceof javax.swing.JTree) {
            var rows = c.getRowCount(); var acc = []
            for (var r = 0; r < rows && r < 400; r++) { var path = c.getPathForRow(r); var node = path.getLastPathComponent(); acc.push(pad(path.getPathCount() * 2) + clean(String(node))) }
            line = "<tree rows " + rows + ">\n" + acc.join("\n")
        }
        else if (c instanceof javax.swing.JTable) {
            var m = c.getModel(); var acc2 = []
            for (var r = 0; r < m.getRowCount() && r < 200; r++) { var cells = []; for (var k = 0; k < m.getColumnCount(); k++) cells.push(clean(String(m.getValueAt(r, k)))); acc2.push("    " + cells.join(" | ")) }
            line = "<table rows " + m.getRowCount() + ">\n" + acc2.join("\n")
        }
    } catch (e) { line = "?" + e }
    if (line != null && line.length > 0) out.append(pad(d) + line + "\n")
    if (c instanceof javax.swing.JTabbedPane) {
        var titles = []
        for (var i = 0; i < c.getTabCount(); i++) titles.push((i == c.getSelectedIndex() ? "*" : "") + clean(c.getTitleAt(i)))
        out.append(pad(d) + "<tabs: " + titles.join(" | ") + ">\n")
    }
    if (c instanceof javax.swing.JTree || c instanceof javax.swing.JTable || c instanceof javax.swing.JComboBox) return
    if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) walk(ch[i], d + 1) }
}
if (mode == "open") {
    app.invokeLater(new java.lang.Runnable({ run: function () {
        com.intellij.ide.actions.ShowSettingsUtilImpl.showSettingsDialog(project, "__ID__", "")
    } }))
    out.append("opening __ID__")
} else if (mode == "dump") {
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        var dlg = settingsDialog()
        if (dlg == null) { out.append("no Settings dialog\n"); return }
        out.append("dialog: " + dlg.getTitle() + "\n")
        var tabbed = []
        function findTabs(c) { if (c instanceof javax.swing.JTabbedPane && c.isShowing()) tabbed.push(c); if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) findTabs(ch[i]) } }
        findTabs(dlg)
        if ("__TABS__" == "all" && tabbed.length > 0) {
            var tp = tabbed[0]
            for (var t = 0; t < tp.getTabCount(); t++) {
                tp.setSelectedIndex(t)
                tp.validate()
                out.append("\n======== tab: " + clean(tp.getTitleAt(t)) + "\n")
                walk(tp.getComponentAt(t), 0)
            }
        } else walk(dlg.getContentPane(), 0)
    } }), ModalityState.any())
} else if (mode == "close") {
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        var dlg = settingsDialog()
        if (dlg == null) { out.append("no dialog"); return }
        var w = com.intellij.openapi.ui.DialogWrapper.findInstance(dlg.getContentPane())
        if (w != null) w.doCancelAction(); else dlg.dispose()
        out.append("closed")
    } }), ModalityState.any())
}
out.toString()
