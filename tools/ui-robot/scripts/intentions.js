// What Alt+Enter offers in the selected editor with the caret inside the first __AT__: quick fixes of errors, of inspections, intentions.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.daemon.impl.ShowIntentionsPass)
importClass(com.intellij.psi.PsiDocumentManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
const moved = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const at = String(editor.getDocument().getText()).indexOf("__AT__")
    if (at < 0) { moved.set("no __AT__ in the editor\n"); return }
    editor.getCaretModel().moveToOffset(at + 1)
} }))
java.lang.Thread.sleep(__WAIT__)
const info = com.intellij.openapi.application.ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: function () {
    return ShowIntentionsPass.getActionsToShow(editor, PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()))
} }))
const groups = [["error fixes", info.errorFixesToShow], ["inspection fixes", info.inspectionFixesToShow], ["intentions", info.intentionsToShow]]
let text = String(moved.get())
for (let g = 0; g < groups.length; g++) {
    text += groups[g][0] + ": " + groups[g][1].size() + "\n"
    for (let i = 0; i < groups[g][1].size() && i < 12; i++) text += "  " + groups[g][1].get(i).getAction().getText() + "\n"
}
text
