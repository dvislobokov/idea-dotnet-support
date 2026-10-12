// Completion latency (docs/compare): rider_complete.js with times. Types the text at the end of the line (on a new line with NEWLINE=yes),
// waits, invokes completion and polls every 20 ms: `first=` ms until the list has items, `stable=` ms until its size last changed, and the
// first items. Platform API only: the same in the plugin's sandbox and in Rider.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.codeInsight.completion.CodeCompletionHandlerBase)
importClass(com.intellij.codeInsight.completion.CompletionType)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.codeInsight.lookup.LookupElementPresentation)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var typed = ("__NEWLINE__" == "yes" ? "\n            " : "") + "__TYPE__"
var start = 0
var editor = null
var best = "items: 0\n"
var bestCount = 0
var t0 = 0, first = -1, stable = -1, lastSize = -1
function snapshot(lookup) {
    var items = lookup.getItems()
    if (items.size() < bestCount) return
    bestCount = items.size()
    var text = "items: " + items.size() + "\n"
    for (var i = 0; i < items.size() && i < __LIMIT__; i++) {
        var p = new LookupElementPresentation()
        items.get(i).renderElement(p)
        text += "  " + (p.isItemTextBold() ? "*" : "") + (p.getItemText() || items.get(i).getLookupString()) + (p.getTailText() || "") + (p.getTypeText() ? "  : " + p.getTypeText() : "") + "\n"
    }
    best = text
}
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, __LINE__ - 1, 0), true)
    start = editor.getDocument().getLineEndOffset(__LINE__ - 1)
    if (typed.length > 0) WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { editor.getDocument().insertString(start, typed) } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    editor.getCaretModel().moveToOffset(start + typed.length)
    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
    editor.getContentComponent().requestFocusInWindow()
} }))
java.lang.Thread.sleep(__SYNC__)
var ghost = ""
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    try {
        var session = com.intellij.codeInsight.inline.completion.session.InlineCompletionSession.Companion.getOrNull(editor)
        if (session != null && session.getContext().getTextToInsert) ghost = String(session.getContext().getTextToInsert())
    } catch (e) { ghost = "?" }
    t0 = java.lang.System.nanoTime()
    new CodeCompletionHandlerBase(CompletionType.__KIND__, true, false, true).invokeCompletion(project, editor)
} }))
for (var tick = 0; tick < __WAIT__ / 20; tick++) {
    java.lang.Thread.sleep(20)
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        var lookup = LookupManager.getInstance(project).getActiveLookup()
        if (lookup == null) return
        var size = lookup.getItems().size()
        var ms = Math.round((java.lang.System.nanoTime() - t0) / 1e6)
        if (size > 0 && first < 0) first = ms
        if (size != lastSize) { stable = ms; lastSize = size }
        if (tick % 10 == 0) snapshot(lookup)
    } }))
}
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var lookup = LookupManager.getInstance(project).getActiveLookup()
    if (lookup != null) { snapshot(lookup); LookupManager.getInstance(project).hideActiveLookup() }
    if (typed.length > 0) WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { editor.getDocument().deleteString(start, start + typed.length) } }))
} }))
var result = "first=" + first + " stable=" + stable + "\n" + (ghost.length > 0 ? "gray before completion: [" + ghost + "]\n" : "") + best
result
