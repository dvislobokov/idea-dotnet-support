// Rename end to end and back: opens __FILE__ (forward slashes), puts the caret inside the first __AT__ (an anchor that starts at the name;
// the last one with __LAST__ = yes),
// runs the rename handler of the platform as Shift+F6 does (prints which one: NativeCSharpRenameHandler for RENAME = Built-in, the LSP one
// for the server), gives the inline template the name __NEW__ and finishes it as Enter would; waits __WAIT__ ms (the server answers late),
// prints the lines of the file that changed (`line: text`) and a dialog if one showed (a conflict), then undoes until the text is back.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.command.CommandProcessor)
importClass(com.intellij.openapi.command.undo.UndoManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.codeInsight.template.impl.TemplateManagerImpl)
importClass(com.intellij.refactoring.rename.RenameHandlerRegistry)
importClass(com.intellij.ide.DataManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var out = new java.lang.StringBuilder()
var state = { editor: null, original: null, file: null, done: false }
function edt(body) { app.invokeAndWait(new java.lang.Runnable({ run: body }), ModalityState.any()) }
// edits as the user makes them: in the non-modal state (writes from ModalityState.any() are not write-safe, TransactionGuard logs an error)
function edtSafe(body) { app.invokeAndWait(new java.lang.Runnable({ run: body }), ModalityState.nonModal()) }

// the handler may open a modal dialog (a conflict): start it later, not inside invokeAndWait
edtSafe(function () {
    var file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    var editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0), true)
    state.editor = editor; state.file = file
    state.original = String(editor.getDocument().getText())
    // __LAST__ = yes: the last occurrence (the comments of the markers come before the code and name the same things)
    var at = "__LAST__" == "yes" ? state.original.lastIndexOf("__AT__") : state.original.indexOf("__AT__")
    if (at < 0) { out.append("__AT__ not found\n"); return }
    editor.getCaretModel().moveToOffset(at + 1)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    var context = DataManager.getInstance().getDataContext(editor.getContentComponent())
    var handler = RenameHandlerRegistry.getInstance().getRenameHandler(context)
    out.append("handler: " + (handler == null ? "none" : handler.getClass().getSimpleName()) + "\n")
    if (handler == null) return
    app.invokeLater(new java.lang.Runnable({ run: function () {
        handler.invoke(project, editor, PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()), context)
    } }))
})
for (var i = 0; i < 40 && !state.done && state.original != null; i++) {
    java.lang.Thread.sleep(200)
    edtSafe(function () {
        var template = TemplateManagerImpl.getTemplateState(state.editor)
        if (template == null) return
        var range = template.getVariableRange(template.getTemplate().getVariableNameAt(0))
        out.append("template on: " + state.editor.getDocument().getText(range) + "\n")
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () {
            state.editor.getDocument().replaceString(range.getStartOffset(), range.getEndOffset(), "__NEW__")
        } }))
        CommandProcessor.getInstance().executeCommand(project, new java.lang.Runnable({ run: function () { template.gotoEnd(false) } }), "Rename", null)
        state.done = true
    })
}
java.lang.Thread.sleep(__WAIT__)
// a modal dialog of a conflict: say what it shows and cancel it
edt(function () {
    var windows = java.awt.Window.getWindows()
    for (var w = 0; w < windows.length; w++) {
        var window = windows[w]
        if (!(window instanceof javax.swing.JDialog) || !window.isShowing()) continue
        out.append("dialog: " + window.getTitle() + "\n")
        var wrapper = com.intellij.openapi.ui.DialogWrapper.findInstance(window.getContentPane())
        if (wrapper != null) wrapper.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE)
    }
})
java.lang.Thread.sleep(300)
edtSafe(function () {
    if (state.original == null) return
    var text = String(state.editor.getDocument().getText())
    var before = state.original.split("\n"), after = text.split("\n")
    if (text == state.original) out.append("(nothing changed)\n")
    else for (var k = 0; k < after.length; k++) if (k >= before.length || before[k] != after[k]) out.append((k + 1) + ": " + after[k].replace(/\r/g, "").trim() + "\n")
    var fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(state.file)
    var undo = UndoManager.getInstance(project)
    var steps = 6
    while (steps-- > 0 && undo.isUndoAvailable(fileEditor) && String(state.editor.getDocument().getText()) != state.original) undo.undo(fileEditor)
    out.append(String(state.editor.getDocument().getText()) == state.original ? "undone (" + (5 - steps) + " step(s))\n" : "NOT undone\n")
})
out.toString()
