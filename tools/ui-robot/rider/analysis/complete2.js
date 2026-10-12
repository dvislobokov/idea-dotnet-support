// Completion probe on an anchor: opens __FILE__, goes to the empty line after the line containing "__MARK__", inserts __TYPE__ (document
// insert), then types __TYPED__ char by char as the keyboard does (typed handlers, auto-popup), invokes completion __KIND__
// (BASIC / SMART / AUTO = only what typing opened), prints __LIMIT__ items with presentation; __PICK__ (item text, or "#1") chooses an item
// with __CHAR__ (\n or \t) and prints the resulting lines; the file text is restored at the end.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.codeInsight.completion.CodeCompletionHandlerBase)
importClass(com.intellij.codeInsight.completion.CompletionType)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.codeInsight.lookup.LookupElementPresentation)
importClass(com.intellij.openapi.editor.actionSystem.TypedAction)
importClass(com.intellij.ide.DataManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var typed = "__TYPE__"
var keys = "__TYPED__"
var kind = "__KIND__"
var pick = "__PICK__"
var editor = null
var original = null
var lineNo = 0
var start = 0
var best = "items: 0\n"
var bestCount = 0
var bestItems = null
function iconName(p) {
    try { var ic = p.getIcon(); if (ic == null) return ""; var s = String(ic); var m = s.match(/([A-Za-z0-9_]+)\.(svg|png)/); return (m && m[1].indexOf("Placeholder") < 0) ? " {" + m[1] + "}" : "" } catch (e) { return "" }
}
function snapshot(lookup) {
    var items = lookup.getItems()
    if (items.size() < bestCount) return
    bestCount = items.size()
    bestItems = items
    var text = "items: " + items.size() + "\n"
    for (var i = 0; i < items.size() && i < __LIMIT__; i++) {
        var p = new LookupElementPresentation()
        items.get(i).renderElement(p)
        text += "  " + (p.isItemTextBold() ? "*" : "") + (p.isStrikeout() ? "~" : "") + (p.getItemText() || items.get(i).getLookupString()) + (p.getTailText() || "") + (p.getTypeText() ? "  : " + p.getTypeText() : "") + iconName(p) + "\n"
    }
    best = text
}
function caretLines(from, count) {
    var doc = editor.getDocument()
    var off = editor.getCaretModel().getOffset()
    var t = String(doc.getText())
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
    start = doc.getLineStartOffset(lineNo)
    if (typed.length > 0) WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { doc.insertString(start, typed) } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    editor.getCaretModel().moveToOffset(start + typed.length)
    editor.getScrollingModel().scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
    editor.getContentComponent().requestFocusInWindow()
} }))
java.lang.Thread.sleep(__SYNC__)
for (var k = 0; k < keys.length; k++) {
    var ch = keys.charAt(k)
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        TypedAction.getInstance().actionPerformed(editor, ch, DataManager.getInstance().getDataContext(editor.getContentComponent()))
    } }))
    java.lang.Thread.sleep(150)
}
if (kind != "AUTO") app.invokeAndWait(new java.lang.Runnable({ run: function () {
    new CodeCompletionHandlerBase(CompletionType[kind], true, false, true).invokeCompletion(project, editor, __TIME__)
} }))
for (var tick = 0; tick < __WAIT__ / 250; tick++) {
    java.lang.Thread.sleep(250)
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        var lookup = LookupManager.getInstance(project).getActiveLookup()
        if (lookup != null) snapshot(lookup)
    } }))
}
var picked = ""
var before = ""
app.invokeAndWait(new java.lang.Runnable({ run: function () { before = caretLines(lineNo, 1) } }))
if (pick.length > 0) {
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        var lookup = LookupManager.getInstance(project).getActiveLookup()
        if (lookup == null) { picked = "no lookup to pick from\n"; return }
        var items = lookup.getItems()
        var chosen = null
        if (pick.charAt(0) == "#") chosen = items.get(parseInt(pick.substring(1)) - 1)
        else for (var i = 0; i < items.size(); i++) {
            var p = new LookupElementPresentation(); items.get(i).renderElement(p)
            if ((p.getItemText() || items.get(i).getLookupString()) == pick) { chosen = items.get(i); break }
        }
        if (chosen == null) { picked = "item not found: " + pick + "\n"; return }
        lookup.setCurrentItem(chosen)
        lookup.finishLookup("__CHAR__".charAt(0) == "t" ? "\t" : "\n")
        picked = "picked: " + chosen.getLookupString() + "\n"
    } }))
    java.lang.Thread.sleep(__AFTER__)
    app.invokeAndWait(new java.lang.Runnable({ run: function () { picked += caretLines(lineNo - 1, __SHOW__) } }))
}
if ("__KEEP__" != "yes") app.invokeAndWait(new java.lang.Runnable({ run: function () {
    LookupManager.getInstance(project).hideActiveLookup()
    try {
        var ts = com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(editor)
        if (ts != null) ts.gracefullyFinishTemplate()
    } catch (e) {}
    var cur = String(editor.getDocument().getText()), orig = String(original)
    var a = 0
    while (a < cur.length && a < orig.length && cur.charAt(a) == orig.charAt(a)) a++
    var b = 0
    while (b < cur.length - a && b < orig.length - a && cur.charAt(cur.length - 1 - b) == orig.charAt(orig.length - 1 - b)) b++
    if (cur != orig) WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { editor.getDocument().replaceString(a, cur.length - b, orig.substring(a, orig.length - b)) } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveDocument(editor.getDocument())
} }))
"@@@" + "typed: [" + typed + "|" + keys + "] " + kind + "\n" + "line: " + before + best + picked
