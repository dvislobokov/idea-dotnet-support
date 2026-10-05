// Typing assist probe: opens __FILE__, goes to the empty line after "__MARK__", inserts __TYPE__ (document insert), then performs the key
// sequence __KEYS__ as the keyboard does: plain chars go through TypedAction; {ENTER} {TAB} {BS} {CSE} (Ctrl+Shift+Enter) {ESC} are actions.
// Prints the lines around the caret after each step (__STEPS__ = yes) or at the end, then restores the file.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.openapi.editor.actionSystem.TypedAction)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.ide.DataManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var typed = "__TYPE__"
var keys = "__KEYS__"
var editor = null, original = null, lineNo = 0
var out = new java.lang.StringBuilder("@@@")
function caretLines(from, count) {
    var off = editor.getCaretModel().getOffset()
    var t = String(editor.getDocument().getText())
    var sel = editor.getSelectionModel()
    t = t.substring(0, off) + "<caret>" + t.substring(off)
    var lines = t.split("\n")
    var r = ""
    for (var i = from; i < from + count && i < lines.length; i++) r += "  | " + lines[i] + "\n"
    return r
}
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, 0), true)
    var doc = editor.getDocument()
    original = doc.getText()
    var at = String(original).indexOf("__MARK__")
    lineNo = doc.getLineNumber(at) + 1
    var start = doc.getLineStartOffset(lineNo)
    if (typed.length > 0) WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { doc.insertString(start, typed) } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    editor.getCaretModel().moveToOffset(start + typed.length)
    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
    editor.getContentComponent().requestFocusInWindow()
} }))
java.lang.Thread.sleep(__SYNC__)
var tokens = []
var re = /\{[A-Z]+\}|[\s\S]/g
var m
while ((m = re.exec(keys)) != null) tokens.push(m[0])
var actions = { "{ENTER}": "EditorEnter", "{TAB}": "EditorTab", "{BS}": "EditorBackSpace", "{CSE}": "EditorCompleteStatement", "{ESC}": "EditorEscape" }
for (var k = 0; k < tokens.length; k++) {
    var tok = tokens[k]
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        var ctx = DataManager.getInstance().getDataContext(editor.getContentComponent())
        if (actions[tok]) {
            LookupManager.getInstance(project).hideActiveLookup()
            ActionManager.getInstance().tryToExecute(ActionManager.getInstance().getAction(actions[tok]), null, editor.getContentComponent(), "EditorPopup", true)
        } else TypedAction.getInstance().actionPerformed(editor, tok.charAt(0), ctx)
    } }))
    java.lang.Thread.sleep(__PAUSE__)
    if ("__STEPS__" == "yes") app.invokeAndWait(new java.lang.Runnable({ run: function () { out.append("after [" + tok + "]:\n" + caretLines(lineNo, 1)) } }))
}
java.lang.Thread.sleep(__AFTER__)
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var l = LookupManager.getInstance(project).getActiveLookup()
    out.append("result" + (l != null ? " (lookup open, " + l.getItems().size() + " items)" : "") + ":\n" + caretLines(lineNo - 1, __SHOW__))
    LookupManager.getInstance(project).hideActiveLookup()
    try { var ts = com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(editor); if (ts != null) ts.gracefullyFinishTemplate() } catch (e) {}
    var cur = String(editor.getDocument().getText()), orig = String(original)
    var a = 0
    while (a < cur.length && a < orig.length && cur.charAt(a) == orig.charAt(a)) a++
    var b = 0
    while (b < cur.length - a && b < orig.length - a && cur.charAt(cur.length - 1 - b) == orig.charAt(orig.length - 1 - b)) b++
    if (cur != orig) WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { editor.getDocument().replaceString(a, cur.length - b, orig.substring(a, orig.length - b)) } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveDocument(editor.getDocument())
} }))
out.toString()
