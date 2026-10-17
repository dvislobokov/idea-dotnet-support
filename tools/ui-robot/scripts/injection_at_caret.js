// Injected fragments around the caret of the selected editor (offsets caret-2 … caret+1): completion and the editor actions go into an injected
// file when the caret is inside one of its hosts. Prints the injected leaf, its language and the host range of the fragment.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.lang.injection.InjectedLanguageManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var result = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    var file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
    var manager = InjectedLanguageManager.getInstance(project)
    var offset = editor.getCaretModel().getOffset()
    var out = "caret " + offset + "\n"
    for (var at = offset - 2; at <= offset + 1; at++) {
        var injected = manager.findInjectedElementAt(file, at)
        out += at + ": " + (injected == null ? "none" : injected.getLanguage() + " '" + String(injected.getText()).substring(0, 40) + "' hosts " + manager.getInjectedPsiFiles != null) + "\n"
        if (injected != null) {
            var ranges = manager.intersectWithAllEditableFragments(injected.getContainingFile(), new com.intellij.openapi.util.TextRange(0, injected.getContainingFile().getTextLength()))
            var hostRange = manager.injectedToHost(injected, injected.getTextRange())
            out += "   host range of the leaf: " + hostRange + "\n"
        }
    }
    var editorInjected = com.intellij.injected.editor.InjectedEditorUtil ? "" : ""
    var completionEditor = com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil.getEditorForInjectedLanguageNoCommit(editor, file)
    out += "completion editor: " + (completionEditor == editor ? "the host editor" : "an injected editor") + "\n"
    result.set(out)
} }))
result.get()
