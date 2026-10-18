// Why the completion list does or does not open by itself at the caret of the selected editor: the file the typed-char handlers see
// (injected or the host), the element before the caret, and the answer of every CompletionConfidence of its language (YES = skip).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.psi.util.PsiUtilBase)
importClass(com.intellij.codeInsight.completion.CompletionConfidenceEP)
importClass(com.intellij.lang.injection.InjectedLanguageManager)
importClass(com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder()
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    var host = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
    var offset = editor.getCaretModel().getOffset()
    var injectedEditor = InjectedLanguageUtil.getEditorForInjectedLanguageNoCommit(editor, host, offset)
    var file = PsiUtilBase.getPsiFileInEditor(injectedEditor, project)
    var injectedOffset = injectedEditor.getCaretModel().getOffset()
    out.append("host " + host + ", editor for injection " + (injectedEditor !== editor) + ", file " + file + " (" + file.getLanguage() + ") at " + injectedOffset + "\n")
    var element = file.findElementAt(injectedOffset - 1)
    out.append("element " + element + " '" + (element == null ? "" : element.getText()) + "' " + (element == null ? "" : element.getLanguage()) + "\n")
    var languages = [file.getLanguage(), element == null ? null : element.getLanguage()]
    for (var l = 0; l < languages.length; l++) {
        if (languages[l] == null) continue
        var confidences = CompletionConfidenceEP.forLanguage(languages[l])
        for (var i = 0; i < confidences.size(); i++) {
            var c = confidences.get(i)
            var answer = element == null ? "?" : c.shouldSkipAutopopup(injectedEditor, element, file, injectedOffset)
            out.append("  " + languages[l] + " " + c.getClass().getName() + " -> " + answer + "\n")
        }
    }
} }), ModalityState.any())
out.toString()
