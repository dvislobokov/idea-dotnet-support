// Dumps action groups recursively: text (after update() with the selected editor's data context, falling back to the template), id, shortcut,
// and whether the action is visible/enabled in that context. __GROUPS__ — ids separated by commas. __CTX__ = editor | frame.
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ActionGroup)
importClass(com.intellij.openapi.actionSystem.DefaultActionGroup)
importClass(com.intellij.openapi.actionSystem.Separator)
importClass(com.intellij.openapi.actionSystem.AnActionEvent)
importClass(com.intellij.openapi.actionSystem.ActionPlaces)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.ide.DataManager)
importClass(com.intellij.openapi.keymap.KeymapUtil)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.application.ApplicationManager)
var am = ActionManager.getInstance()
var out = new java.lang.StringBuilder()
var count = 0
var ctx = null
function pad(n) { var s = ""; for (var k = 0; k < n; k++) s += "  "; return s }
function eventFor(a) {
    var e = AnActionEvent.createFromDataContext("__PLACE__", a.getTemplatePresentation().clone(), ctx)
    try { a.update(e) } catch (x) {}
    return e
}
function children(g, e) {
    try { if (g instanceof DefaultActionGroup) return g.getChildActionsOrStubs() } catch (x) {}
    try { return g.getChildren(e) } catch (x) { return null }
}
function dump(a, depth, seen) {
    if (depth > 12) return
    if (a instanceof Separator) { var t = a.getText(); out.append(pad(depth) + "-----" + (t ? " " + t : "") + "\n"); return }
    var id = am.getId(a)
    var e = eventFor(a)
    var p = e.getPresentation()
    var text = p.getText()
    if (text == null || text == "") text = a.getTemplatePresentation().getText()
    var sc = id ? KeymapUtil.getFirstKeyboardShortcutText(id) : ""
    var isGroup = a instanceof ActionGroup
    var flags = (p.isVisible() ? "" : " [hidden]") + (p.isEnabled() ? "" : " [disabled]")
    out.append(pad(depth) + (isGroup ? "[+] " : "") + (text == null || text == "" ? "<no text>" : text) + "  {" + (id || a.getClass().getSimpleName()) + "}" + (sc ? "  " + sc : "") + flags + "\n")
    count++
    if (isGroup) {
        if (id && seen.indexOf("|" + id + "|") >= 0) { out.append(pad(depth + 1) + "(recursion)\n"); return }
        var ch = children(a, e)
        if (ch == null) { out.append(pad(depth + 1) + "(dynamic group)\n"); return }
        for (var i = 0; i < ch.length; i++) dump(ch[i], depth + 1, seen + "|" + id + "|")
    }
}
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var ps = ProjectManager.getInstance().getOpenProjects()
    var project = ps[ps.length - 1]
    var ed = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var comp = ("__CTX__" == "editor" && ed != null) ? ed.getContentComponent() : com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project).getRootPane()
    ctx = DataManager.getInstance().getDataContext(comp)
    var groups = "__GROUPS__".split(",")
    for (var gi = 0; gi < groups.length; gi++) {
        var g = am.getAction(groups[gi])
        out.append("##### " + groups[gi] + (g == null ? " (not found)" : "") + "\n")
        if (g != null) dump(g, 0, "")
        out.append("\n")
    }
} }))
"@@@" + out.toString() + "actions dumped: " + count + "\n"
