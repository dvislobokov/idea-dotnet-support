// Opens __FILE__ (forward slashes), types __TYPE__ at the end of line __LINE__ (1-based) and presses Tab the way the editor does
// (TemplateManager.startTemplate with '\t': live templates and postfix templates alike), then finishes the template at its end. Prints the
// lines __LINE__ .. __LINE__ + __LINES__ with <caret>, whether a template was started, the selection, and restores the text (`restored`).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.template.TemplateManager)
importClass(com.intellij.codeInsight.template.impl.TemplateManagerImpl)
importClass(com.intellij.psi.PsiDocumentManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const typed = "__TYPE__"
let out = ""
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    const editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, __LINE__ - 1, 0), true)
    const document = editor.getDocument()
    const original = document.getText()
    const start = document.getLineEndOffset(__LINE__ - 1)
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { document.insertString(start, typed) } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    editor.getCaretModel().moveToOffset(start + typed.length)
    let started = false
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () {
        started = TemplateManager.getInstance(project).startTemplate(editor, '\t')
    } }))
    const state = TemplateManagerImpl.getTemplateState(editor)
    out += "started: " + started + ", template active: " + (state != null) + "\n"
    if (state != null) {
        const lookup = com.intellij.codeInsight.lookup.LookupManager.getInstance(project).getActiveLookup()
        if (lookup != null) {
            let names = ""
            for (var i = 0; i < lookup.getItems().size() && i < 12; i++) names += lookup.getItems().get(i).getLookupString() + " "
            out += "lookup at the stop: " + names + "\n"
        }
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { state.gotoEnd(false) } }))
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    const caret = editor.getCaretModel().getOffset()
    const text = document.getText()
    const shown = text.substring(0, caret) + "<caret>" + text.substring(caret)
    const lines = shown.split("\n")
    for (var l = __LINE__ - 1 - __BEFORE__; l < Math.min(lines.length, __LINE__ + __LINES__); l++) out += (l + 1) + ": " + lines[l] + "\n"
    if (editor.getSelectionModel().hasSelection()) out += "selection: " + editor.getSelectionModel().getSelectedText() + "\n"
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { document.setText(original) } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    out += document.getText() == original ? "restored\n" : "NOT restored\n"
} }))
out
