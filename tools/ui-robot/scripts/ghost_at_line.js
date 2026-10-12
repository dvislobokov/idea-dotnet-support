// Opens __FILE__ (forward slashes), types __TYPE__ character by character at the end of its line __LINE__ (1-based) the way the keyboard
// does (typed handlers, auto-popup, the gray inline text), waits __WAIT__ ms and reports the inline suggestion that is showing —
// the gray text of CSharpGhostText / RoslynLambdaGhost — and the completion popup, if any. __UNDO__ = "yes" removes the typed text after.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.editor.actionSystem.TypedAction)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.codeInsight.inline.completion.session.InlineCompletionContext)
importClass(com.intellij.ide.DataManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const typed = "__TYPE__"
const report = new java.lang.StringBuilder()
let editor = null
let start = 0
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, __LINE__ - 1, 0), true)
    start = editor.getDocument().getLineEndOffset(__LINE__ - 1)
    editor.getCaretModel().moveToOffset(start)
    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
    editor.getContentComponent().requestFocusInWindow()
} }))
for (let i = 0; i < typed.length; i++) {
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        TypedAction.getInstance().actionPerformed(editor, typed.charAt(i), DataManager.getInstance().getDataContext(editor.getContentComponent()))
    } }))
    java.lang.Thread.sleep(60)
}
let ghost = "none"
for (let tick = 0; tick < __WAIT__ / 250; tick++) {
    java.lang.Thread.sleep(250)
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        const context = InlineCompletionContext.Companion.getOrNull(editor)
        if (context != null && !context.isDisposed() && context.getState().getElements().size() > 0) ghost = "[" + context.textToInsert() + "]"
    } }))
    if (ghost != "none") break
}
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const line = editor.getDocument().getLineNumber(editor.getCaretModel().getOffset())
    const text = editor.getDocument().getText(new com.intellij.openapi.util.TextRange(editor.getDocument().getLineStartOffset(line), editor.getDocument().getLineEndOffset(line)))
    report.append("line now: [" + text + "]\n")
    report.append("ghost: " + ghost + "\n")
    const lookup = LookupManager.getInstance(project).getActiveLookup()
    report.append("popup: " + (lookup == null ? "none" : lookup.getItems().size() + " items, first " + (lookup.getItems().size() == 0 ? "-" : lookup.getItems().get(0).getLookupString())) + "\n")
    if (lookup != null) LookupManager.getInstance(project).hideActiveLookup()
    if ("__UNDO__" == "yes") WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () {
        editor.getDocument().deleteString(editor.getDocument().getLineStartOffset(line), editor.getDocument().getLineEndOffset(line))
    } }))
} }))
report.toString()
