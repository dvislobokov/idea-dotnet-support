// Complete Statement (Ctrl+Shift+Enter, action EditorCompleteStatement) in __FILE__ (forward slashes): __TYPE__ is typed on the first empty line
// after the first __AT__ (a marker, `// TYPE:complete-call `) with the indent of that line — or, when __TYPE__ is empty, the caret goes to the
// end of the line of __AT__ and nothing is typed. Prints the lines from there with `<caret>`, then undoes both (Ctrl+Z) and says whether the
// text is back. Switch «Typing assistance» with feature_source.js (`EDITING`) and compare; scenarios: debug-playground/Console/Editor/CompleteStatement.cs.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.command.undo.UndoManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.ide.DataManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const out = new java.lang.StringBuilder()
const app = ApplicationManager.getApplication()
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    const file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    const manager = FileEditorManager.getInstance(project)
    const editor = manager.openTextEditor(new OpenFileDescriptor(project, file, 0), true)
    const document = editor.getDocument()
    const original = String(document.getText())
    const marker = original.indexOf("__AT__")
    if (marker < 0) { out.append("__AT__ not found"); return }
    const typed = "__TYPE__"
    const markerLine = document.getLineNumber(marker)
    var line = markerLine
    var caret
    if (typed.length == 0) {
        caret = document.getLineEndOffset(markerLine)
    } else {
        while (line < document.getLineCount() - 1 && String(original.substring(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim().length > 0) line++
        const start = document.getLineStartOffset(line)
        const indent = String(original.substring(document.getLineStartOffset(markerLine), marker)).replace(/\S.*$/, "")
        WriteCommandAction.runWriteCommandAction(project, "Type " + typed, null, new java.lang.Runnable({ run: function () {
            document.replaceString(start, document.getLineEndOffset(line), indent + typed)
        } }))
        caret = start + indent.length + typed.length
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    editor.getCaretModel().moveToOffset(caret)
    ActionUtil.invokeAction(ActionManager.getInstance().getAction("EditorCompleteStatement"), DataManager.getInstance().getDataContext(editor.getContentComponent()), "unknown", null, null)
    const text = String(document.getText())
    const at = editor.getCaretModel().getOffset()
    const marked = text.substring(0, at) + "<caret>" + text.substring(at)
    const lines = marked.split("\n")
    var i = 0
    for (i = line; i < Math.min(lines.length, line + 6); i++) out.append((i + 1) + ": " + lines[i].replace(/\r/g, "") + "\n")
    // back to what the file was: Complete Statement, then the typing
    const fileEditor = manager.getSelectedEditor(file)
    const undo = UndoManager.getInstance(project)
    // until the text is back (at most 8): the typing, Complete Statement and its caret move are separate steps — 3 with either source (robot)
    var steps = 8
    while (steps-- > 0 && undo.isUndoAvailable(fileEditor) && String(document.getText()) != original) undo.undo(fileEditor)
    out.append(String(document.getText()) == original ? "undone\n" : "NOT undone: the file differs, Ctrl+Z by hand\n")
} }), ModalityState.any())
out.toString()
