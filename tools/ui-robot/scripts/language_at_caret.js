// Which language and which PSI file of the view the platform takes at the caret of the selected editor — what completion and the editor actions
// run in (PsiUtilBase.getLanguageInEditor / getPsiFileInEditor) — and the completion contributors of that language.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.psi.util.PsiUtilBase)
importClass(com.intellij.psi.util.PsiUtilCore)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var result = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    var file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
    var offset = editor.getCaretModel().getOffset()
    var language = PsiUtilBase.getLanguageInEditor(editor, project)
    var inEditor = PsiUtilBase.getPsiFileInEditor(editor, project)
    var out = "caret " + offset + ": language in editor " + language + ", file in editor " + inEditor + " (" + inEditor.getLanguage() + ")\n"
    out += "language at offset-1: " + PsiUtilCore.getLanguageAtOffset(file, offset - 1) + ", at offset: " + PsiUtilCore.getLanguageAtOffset(file, offset) + "\n"
    var leaf = file.getViewProvider().findElementAt(offset - 1)
    out += "view findElementAt(offset-1): " + leaf + " " + (leaf == null ? "" : leaf.getLanguage() + " in " + leaf.getContainingFile()) + "\n"
    result.set(out)
} }))
result.get()
