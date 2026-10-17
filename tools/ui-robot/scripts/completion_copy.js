// The file copy completion makes at the caret of the selected editor (CompletionInitializationUtil: a copy of the file in the editor's language,
// the dummy identifier inserted at the caret, committed): the leaf at the caret in the copy and its parents, as the contributors see them.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.psi.util.PsiUtilBase)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var result = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var documents = PsiDocumentManager.getInstance(project)
    documents.commitAllDocuments()
    var file = PsiUtilBase.getPsiFileInEditor(editor, project)
    var offset = editor.getCaretModel().getOffset()
    var copy = file.copy()
    var document = copy.getViewProvider().getDocument()
    ApplicationManager.getApplication().runWriteAction(new java.lang.Runnable({ run: function () {
        document.insertString(offset, "IntellijIdeaRulezzz ")
        documents.commitDocument(document)
    } }))
    copy = copy.getViewProvider().getPsi(copy.getLanguage())
    var out = "copy " + copy + " of view " + copy.getViewProvider().getClass().getSimpleName() + " languages " + copy.getViewProvider().getLanguages() + ", original " + copy.getOriginalFile() + "\n"
    var leaf = copy.findElementAt(offset)
    for (var e = leaf, depth = 0; e != null && depth < 5; e = e.getParent(), depth++) {
        out += (depth == 0 ? "" : " < ") + e.getClass().getSimpleName() + (e.getNode() != null ? "(" + e.getNode().getElementType() + ")" : "") + " '" + String(e.getText()).replace(/\n/g, "\\n").substring(0, 50) + "'"
        if (e instanceof com.intellij.psi.PsiFile) break
    }
    var razor = copy.getViewProvider().getPsi(com.intellij.lang.Language.findLanguageByID("Razor"))
    var rleaf = razor == null ? null : razor.findElementAt(offset)
    out += "\nrazor copy: "
    for (var e = rleaf, depth = 0; e != null && depth < 4; e = e.getParent(), depth++) out += (depth == 0 ? "" : " < ") + (e.getNode() != null ? e.getNode().getElementType() : e) + " '" + String(e.getText()).replace(/\n/g, "\\n").substring(0, 50) + "'"
    result.set(out)
} }))
result.get()
