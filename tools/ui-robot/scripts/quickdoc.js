// Quick Documentation at the caret put inside the first __TEXT__ of the selected editor: which documentation target providers of the
// platform answer there (the PSI provider of go-psi-ide or the LSP one of gopls) and the presentable text of their targets.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.codeInsight.TargetElementUtil)
importClass(com.intellij.platform.backend.documentation.DocumentationTargetProvider)
importClass(com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var editor = ReadAction.compute(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() })
var document = editor.getDocument()
var moved = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { editor.getCaretModel().moveToOffset(String(document.getText()).indexOf("__TEXT__") + 1); moved.complete(true) } }))
moved.get(10, TimeUnit.SECONDS)
function present(t) { try { return String(t.computePresentation().getPresentableText()) } catch (e) { return "error: " + e } }
ReadAction.compute(function () {
    var psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
    var offset = editor.getCaretModel().getOffset()
    var out = ""
    var providers = DocumentationTargetProvider.EP_NAME.getExtensionList()
    for (var i = 0; i < providers.size(); i++) {
        var targets = providers.get(i).documentationTargets(psi, offset)
        if (targets.size() > 0) {
            out += providers.get(i).getClass().getName() + ": " + targets.size() + "\n"
            for (var j = 0; j < targets.size(); j++) out += "  " + targets.get(j).getClass().getName() + ": " + present(targets.get(j)) + "\n"
        }
    }
    var target = TargetElementUtil.getInstance().findTargetElement(editor, TargetElementUtil.getInstance().getAllAccepted(), offset)
    var original = psi.findElementAt(offset)
    out += "target element: " + target + "\n"
    if (target != null) {
        var psiProviders = PsiDocumentationTargetProvider.EP_NAME.getExtensionList()
        for (var k = 0; k < psiProviders.size(); k++) {
            var t = psiProviders.get(k).documentationTarget(target, original)
            if (t != null) out += psiProviders.get(k).getClass().getName() + ": " + t.getClass().getName() + ": " + present(t) + "\n"
        }
    }
    return out == "" ? "no documentation target" : out
})
