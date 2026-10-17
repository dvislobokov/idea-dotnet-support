// The PSI of the selected editor around its caret, in every language of the file's view (Razor's HTML tree is not the Razor one): for each language
// the leaf before the caret, its element type, its parents up to the file (class and the first 60 characters of their text).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var result = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    var file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
    var offset = editor.getCaretModel().getOffset()
    var out = "caret " + offset + " in " + file.getName() + "\n"
    var languages = file.getViewProvider().getLanguages().toArray()
    for (var i = 0; i < languages.length; i++) {
        var leaf = file.getViewProvider().findElementAt(Math.max(0, offset - 1), languages[i])
        out += "[" + languages[i].getID() + "] "
        var depth = 0
        for (var e = leaf; e != null && depth < 6; e = e.getParent(), depth++) {
            var text = String(e.getText()).replace(/\n/g, "\\n")
            out += (depth == 0 ? "" : " < ") + e.getClass().getSimpleName() + (e.getNode() != null ? "(" + e.getNode().getElementType() + ")" : "") + " '" + text.substring(0, 60) + "'"
            if (e instanceof com.intellij.psi.PsiFile) break
        }
        out += "\n"
    }
    result.set(out)
} }))
result.get()
