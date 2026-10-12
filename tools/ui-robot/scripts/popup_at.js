// Quick documentation (__ACTION__ = `QuickJavaDoc`) or Parameter Info (`ParameterInfo`) as a person asks for it: opens __FILE__ (absolute
// path), puts the caret __SHIFT__ characters into the first __AT__ after __AFTER__ (empty: from the start), performs the action with the
// data context of the editor, waits __WAIT__ ms and prints the texts of what popped up: the documentation popup (its HTML, tags dropped) and
// the parameter info hint (its rows, the highlighted parameter in [brackets] is not visible here — take `screen.sh shot` for that). Escape
// closes the popup afterwards. Performed from here the actions may fail on the data context: then __ACTION__ = `none` (only the caret is
// placed), the keys go by `screen.sh key ctrl+q` / `ctrl+p`, and a second run with __FILE__ = `none` prints the popup.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.editor.ScrollType)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.ide.DataManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder()
var editorRef = new java.util.concurrent.atomic.AtomicReference(null)
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
    if ("__FILE__" == "none") return
    var file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    if (file == null) { out.append("no file __FILE__"); return }
    var editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0), true)
    var text = String(editor.getDocument().getText())
    var from = "__AFTER__" == "" ? 0 : text.indexOf("__AFTER__")
    var at = text.indexOf("__AT__", Math.max(from, 0))
    if (at < 0) { out.append("__AT__ not found"); return }
    editor.getCaretModel().moveToOffset(at + __SHIFT__)
    editor.getScrollingModel().scrollToCaret(ScrollType.CENTER)
    editorRef.set(editor)
    if ("__ACTION__" != "none") ActionUtil.invokeAction(ActionManager.getInstance().getAction("__ACTION__"), DataManager.getInstance().getDataContext(editor.getContentComponent()), "unknown", null, null)
} }), ModalityState.nonModal())
java.lang.Thread.sleep(__WAIT__)
function strip(html) {
    return String(html).replace(/<head>[\s\S]*?<\/head>/g, "").replace(/<br\s*\/?>/g, "\n").replace(/<\/(p|div|tr|li|h\d)>/g, "\n")
        .replace(/<[^>]+>/g, "").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&").replace(/&nbsp;|&#160;/g, " ")
        .replace(/[ \t]+/g, " ").replace(/\n\s*\n+/g, "\n").trim()
}
function walk(component, inPopup, depth) {
    if (component == null || !component.isShowing()) return
    var name = String(component.getClass().getName())
    var popup = inPopup || name.indexOf("ParameterInfo") >= 0 || name.indexOf("Documentation") >= 0 || name.indexOf("LightweightHint") >= 0
    if (popup && component instanceof javax.swing.JEditorPane) out.append("[" + name.substring(name.lastIndexOf(".") + 1) + "]\n" + strip(component.getText()) + "\n")
    else if (popup && component instanceof javax.swing.JLabel && component.getText() != null && String(component.getText()).trim() != "") {
        // `1/2`: several targets; the arrow to the next one is right of the label
        var where = component.getLocationOnScreen()
        out.append("label: " + strip(component.getText()) + " (next at " + Math.round(where.x + component.getWidth() + 14) + " " + Math.round(where.y + component.getHeight() / 2) + ")\n")
    }
    if (component instanceof java.awt.Container) {
        var children = component.getComponents()
        for (var i = 0; i < children.length; i++) walk(children[i], popup, depth + 1)
    }
}
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var windows = java.awt.Window.getWindows()
    for (var w = 0; w < windows.length; w++) {
        var window = windows[w]
        if (!window.isShowing()) continue
        // the documentation popup is a window of its own; the parameter info hint lives in the layered pane of the frame
        if (!(window instanceof javax.swing.RootPaneContainer)) continue
        walk(window.getRootPane(), String(window.getClass().getName()).indexOf("IdeFrame") < 0 && window.getOwner() != null, 0)
    }
} }), ModalityState.any())
var result = String(out.length() == 0 ? "nothing popped up" : "popup\n" + out.toString())
result
