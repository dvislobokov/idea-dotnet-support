// Rename as Shift+F6 does it, to the end: the caret inside the first __AT__, the rename handler of the platform started with the context
// of the editor, the inline template given the name __NEW__ and finished as Enter would. Then the name of the file of the editor.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.codeInsight.template.impl.TemplateManagerImpl)
importClass(com.intellij.refactoring.rename.RenameHandlerRegistry)
importClass(com.intellij.ide.DataManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const report = new java.lang.StringBuilder()
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    const at = String(editor.getDocument().getText()).indexOf("__AT__")
    if (at < 0) { report.append("no __AT__ in the editor\n"); return }
    editor.getCaretModel().moveToOffset(at + 1)
    const context = DataManager.getInstance().getDataContext(editor.getContentComponent())
    const handler = RenameHandlerRegistry.getInstance().getRenameHandler(context)
    report.append("handler: " + (handler == null ? "none" : handler.getClass().getSimpleName()) + "\n")
    if (handler != null) handler.invoke(project, editor, FileDocumentManager.getInstance().getFile(editor.getDocument()) == null ? null :
        com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()), context)
} }))
// prepareRename goes to the server first
for (let i = 0; i < 30 && report.indexOf("template") < 0; i++) {
    java.lang.Thread.sleep(200)
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
        const state = TemplateManagerImpl.getTemplateState(editor)
        if (state == null) return
        report.append("template on: " + editor.getDocument().getText(state.getVariableRange(state.getTemplate().getVariableNameAt(0))) + "\n")
        const range = state.getVariableRange(state.getTemplate().getVariableNameAt(0))
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () {
            editor.getDocument().replaceString(range.getStartOffset(), range.getEndOffset(), "__NEW__")
        } }))
        // Enter finishes the template inside a command of the editor
        com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, new java.lang.Runnable({ run: function () { state.gotoEnd(false) } }), "Rename", null)
    } }))
}
java.lang.Thread.sleep(__WAIT__)
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const file = FileEditorManager.getInstance(project).getSelectedFiles()[0]
    report.append("file now: " + file.getName() + "\n")
} }))
report.toString()
