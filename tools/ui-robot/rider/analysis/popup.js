// Opens __FILE__, puts the caret at the first __AT__ + __OFF__, performs __ACTION__ with the editor's data context, waits __WAIT__ ms and
// dumps every showing popup window: lists (rendered rows), trees and labels. __CLOSE__ = yes closes popups afterwards.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.ide.DataManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var out = new java.lang.StringBuilder("@@@")
var editor = null
if ("__AT__".length > 0) app.invokeAndWait(new java.lang.Runnable({ run: function () {
    if ("__FILE__".length > 0) {
        var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
        editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, 0), true)
    } else editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var text = String(editor.getDocument().getText())
    var at = text.indexOf("__AT__")
    if (at < 0) { out.append("anchor not found\n"); return }
    editor.getCaretModel().moveToOffset(at + __OFF__)
    editor.getScrollingModel().scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
    var line = editor.getDocument().getLineNumber(at + __OFF__)
    out.append("caret line " + (line + 1) + ": " + text.substring(editor.getDocument().getLineStartOffset(line), editor.getDocument().getLineEndOffset(line)).trim() + "\n")
} }))
java.lang.Thread.sleep("__AT__".length > 0 ? 1500 : 0)
if (editor == null) app.invokeAndWait(new java.lang.Runnable({ run: function () { editor = FileEditorManager.getInstance(project).getSelectedTextEditor() } }))
var keys = "__KEYS__"
if (keys.length > 0) {
    var rb = new java.awt.Robot()
    var codes = keys.split("+")
    for (var k = 0; k < codes.length; k++) rb.keyPress(java.awt.event.KeyEvent["VK_" + codes[k]])
    for (var k = codes.length - 1; k >= 0; k--) rb.keyRelease(java.awt.event.KeyEvent["VK_" + codes[k]])
}
app.invokeLater(new java.lang.Runnable({ run: function () {
    if ("__ACTION__".length > 0) ActionManager.getInstance().tryToExecute(ActionManager.getInstance().getAction("__ACTION__"), null, editor.getContentComponent(), "EditorPopup", true)
} }))
java.lang.Thread.sleep(__WAIT__)
function texts(c, acc) {
    try {
        if (c instanceof com.intellij.ui.SimpleColoredComponent) { var s = String(c.getCharSequence(false)); if (s.length > 0) acc.push(s) }
        else if (c instanceof javax.swing.JLabel) { var t = c.getText(); if (t != null && String(t).length > 0) acc.push(String(t).replace(/<[^>]*>/g, "")) }
        else if (c instanceof javax.swing.text.JTextComponent) { var t2 = c.getText(); if (t2 != null && String(t2).length > 0) acc.push("[" + String(t2) + "]") }
    } catch (e) {}
    if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) texts(ch[i], acc) }
    return acc
}
function row(list, i) {
    var v = list.getModel().getElementAt(i)
    var acc = []
    try {
        var comp = list.getCellRenderer().getListCellRendererComponent(list, v, i, false, false)
        texts(comp, acc)
    } catch (e) { acc.push("?" + e) }
    var sep = ""
    try { var m = list.getModel(); if (m.getSeparatorAbove) { var sa = m.getSeparatorAbove(v); if (sa != null && sa.length > 0) sep = "--- " + sa + "\n" } } catch (e) {}
    var u = acc.join(" | ")
    if (u.length == 0) u = String(v)
    return sep + u
}
function lists(c, acc) {
    if (c instanceof javax.swing.JList) acc.push(c)
    if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) lists(ch[i], acc) }
    return acc
}
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var ws = java.awt.Window.getWindows()
    for (var w = 0; w < ws.length; w++) {
        var win = ws[w]
        if (!win.isShowing() || win instanceof java.awt.Frame) continue
        out.append("== window " + win.getClass().getSimpleName() + " " + win.getWidth() + "x" + win.getHeight() + "\n")
        var ls = lists(win, [])
        for (var li = 0; li < ls.length; li++) {
            var list = ls[li]
            out.append("  list " + list.getClass().getSimpleName() + " rows " + list.getModel().getSize() + "\n")
            for (var i = 0; i < list.getModel().getSize() && i < __LIMIT__; i++) out.append("    " + row(list, i).replace(/\n/g, "\n    ") + "\n")
        }
        if (ls.length == 0) { var t = texts(win, []); out.append("  texts: " + t.join(" | ").substring(0, 3000) + "\n") }
    }
} }), ModalityState.any())
if ("__CLOSE__" == "yes") app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var ws = java.awt.Window.getWindows()
    for (var w = 0; w < ws.length; w++) if (ws[w].isShowing() && !(ws[w] instanceof java.awt.Frame) && !(ws[w] instanceof java.awt.Dialog)) ws[w].setVisible(false)
    var es = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
    var pops = com.intellij.ui.popup.AbstractPopup.all ? null : null
    com.intellij.codeInsight.lookup.LookupManager.getInstance(project).hideActiveLookup()
} }), ModalityState.any())
out.toString()
