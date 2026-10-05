// In the open Settings dialog on Code Style | C#: clicks every section label (__SECTIONS__, separated by ;) and dumps the option table.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
var app = ApplicationManager.getApplication()
var out = new java.lang.StringBuilder("@@@")
var sections = "__SECTIONS__".split(";")
function dialog() {
    var ws = java.awt.Window.getWindows()
    for (var i = 0; i < ws.length; i++) if (ws[i].isShowing() && ws[i] instanceof java.awt.Dialog) return ws[i]
    return null
}
function find(c, text, acc) {
    try { if (c.isShowing() && c.getText && String(c.getText()) == text) acc.push(c) } catch (e) {}
    if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) find(ch[i], text, acc) }
    return acc
}
function tables(c, acc) {
    if (c instanceof javax.swing.JTable && c.isShowing()) acc.push(c)
    if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) tables(ch[i], acc) }
    return acc
}
function clean(s) { return String(s).replace(/EnumWithValue\(value=\d+, presentation=(.*)\)$/, "$1") }
for (var s = 0; s < sections.length; s++) {
    var name = sections[s]
    var found = 0
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        var d = dialog()
        var tabsList = []
        function ft(c) { if (c instanceof com.intellij.ui.tabs.JBTabs && c.isShowing()) tabsList.push(c); if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) ft(ch[i]) } }
        ft(d)
        for (var q = 0; q < tabsList.length; q++) {
            var infos = tabsList[q].getTabs()
            for (var j = 0; j < infos.size(); j++) if (String(infos.get(j).getText()) == name) { tabsList[q].select(infos.get(j), false); found = 1 }
        }
    } }), ModalityState.any())
    java.lang.Thread.sleep(2500)
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        out.append("\n======== section: " + name + (found ? "" : " (label not found)") + "\n")
        var ts = tables(dialog(), [])
        for (var i = 0; i < ts.length; i++) {
            var m = ts[i].getModel()
            for (var r = 0; r < m.getRowCount(); r++) {
                var v = m.getValueAt(r, 1)
                out.append(v == null ? "  # " + m.getValueAt(r, 0) + "\n" : "    " + m.getValueAt(r, 0) + " = " + clean(v) + "\n")
            }
        }
    } }), ModalityState.any())
}
out.toString()
