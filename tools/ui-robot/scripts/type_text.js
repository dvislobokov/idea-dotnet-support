// Types __TEXT__ in the selected editor, character by character the way the keyboard does, starting where the first __AT__ is (the
// marker is removed first), and after every character reports the completion popup: is it there, how many items, which one is selected.
// For "the popup shows for the first word and not for the next ones".
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.editor.actionSystem.TypedAction)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.codeInsight.completion.CompletionPhase)
importClass(com.intellij.codeInsight.completion.impl.CompletionServiceImpl)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.ide.DataManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const typed = "__TEXT__"
const report = new java.lang.StringBuilder()
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    const at = String(editor.getDocument().getText()).indexOf("__AT__")
    if (at < 0) { report.append("no __AT__ in the editor"); return }
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { editor.getDocument().deleteString(at, at + "__AT__".length) } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    editor.getCaretModel().moveToOffset(at)
    editor.getContentComponent().requestFocusInWindow()
} }))
for (let i = 0; i < typed.length; i++) {
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
        TypedAction.getInstance().actionPerformed(editor, typed.charAt(i), DataManager.getInstance().getDataContext(editor.getContentComponent()))
    } }))
    java.lang.Thread.sleep(__PAUSE__)
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        const lookup = LookupManager.getInstance(project).getActiveLookup()
        report.append("'" + typed.substring(0, i + 1) + "' -> ")
        report.append(lookup == null ? "no popup" : "popup, " + lookup.getItems().size() + " items, selected " + (lookup.getCurrentItem() == null ? "-" : lookup.getCurrentItem().getLookupString()))
        report.append("  [phase " + CompletionServiceImpl.getCompletionPhase() + "]\n")
    } }))
}
report.toString()
