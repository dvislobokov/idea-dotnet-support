// In the popup list showing now (Alt+Enter, Refactor This), selects the first row whose rendered text contains __TEXT__ (case-insensitive)
// and prints the rows; the caller presses Enter with the real keyboard. Prints "no row" when nothing matches.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
var out = new java.lang.StringBuilder("@@@")
function texts(c, acc) {
    try {
        if (c instanceof com.intellij.ui.SimpleColoredComponent) { var s = String(c.getCharSequence(false)); if (s.length > 0) acc.push(s) }
        else if (c instanceof javax.swing.JLabel) { var t = c.getText(); if (t != null && String(t).length > 0) acc.push(String(t).replace(/<[^>]*>/g, "")) }
    } catch (e) {}
    if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) texts(ch[i], acc) }
    return acc
}
function lists(c, acc) {
    if (c instanceof javax.swing.JList) acc.push(c)
    if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) lists(ch[i], acc) }
    return acc
}
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var wanted = "__TEXT__".toLowerCase()
    var ws = java.awt.Window.getWindows()
    var done = false
    for (var w = 0; w < ws.length && !done; w++) {
        if (!ws[w].isShowing() || ws[w] instanceof java.awt.Frame) continue
        var ls = lists(ws[w], [])
        for (var li = 0; li < ls.length && !done; li++) {
            var list = ls[li]
            for (var i = 0; i < list.getModel().getSize(); i++) {
                var v = list.getModel().getElementAt(i)
                var comp = list.getCellRenderer().getListCellRendererComponent(list, v, i, false, false)
                var row = texts(comp, []).join(" | ")
                if (row.length == 0) row = String(v)
                // Rider's rows are HTML-escaped (`To &#39;if&#39; statements`)
                row = row.replace(/&#39;/g, "'").replace(/&quot;/g, "\"").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&")
                if (!done && row.toLowerCase().indexOf(wanted) >= 0) {
                    list.setSelectedIndex(i); list.ensureIndexIsVisible(i); list.requestFocusInWindow()
                    out.append("selected: " + row + "\n"); done = true
                }
            }
        }
    }
    if (!done) out.append("no row\n")
} }), ModalityState.any())
out.toString()
