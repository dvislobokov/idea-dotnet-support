// What Ctrl + hover sees on the first __AT__ of the selected editor: the implicit references of the platform for the token there,
// resolved in a background read action the way the mouse handler does it. A reference that resolves is a link under the mouse.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.model.psi.ImplicitReferenceProvider)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
const offset = String(editor.getDocument().getText()).indexOf("__AT__") + 1
const result = com.intellij.openapi.application.ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: function () {
    const element = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()).findElementAt(offset)
    let text = "token: " + element.getText() + "\n"
    const providers = ImplicitReferenceProvider.EP_NAME.getExtensionList()
    // no `const` inside the loop: Rhino keeps its first value on every iteration
    for (let i = 0; i < providers.size(); i++) {
        if (providers.get(i).getImplicitReference(element, offset - element.getTextRange().getStartOffset()) == null) continue
        text += "  " + providers.get(i).getClass().getSimpleName() + " -> " + providers.get(i).getImplicitReference(element, offset - element.getTextRange().getStartOffset()).resolveReference() + "\n"
    }
    return text
} }))
result
