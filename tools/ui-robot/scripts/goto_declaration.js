// Go to Declaration (Ctrl+B, action GotoDeclaration) in __FILE__ (forward slashes) with the caret on the first character of the first
// __AT__ (an anchor that starts at the name, e.g. `total + parsed`). Prints who answers (NativeCSharpNavigation.serves: the built-in tree of
// CSharpFeature.NAVIGATION, else the server), the targets of the built-in tree for the name (file:line and the text of the line; "none" — it
// leaves the name to the server), then performs the action and prints where the caret is: file:line and the text of the line, or that it
// stayed (a list of several targets, or nothing to go to; the list is closed with Escape). Switch «Navigation and usages» with
// feature_source.js (`NAVIGATION`) and compare; scenarios: debug-playground/Console/Editor/Navigation.cs (markers `TYPE:nav-*`).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.ide.DataManager)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
const navigationClass = loader.loadClass("io.github.dotnetsupport.lang.NativeCSharpNavigation")
const navigation = navigationClass.getField("INSTANCE").get(null)
const psiElementClass = loader.loadClass("com.intellij.psi.PsiElement")
const out = new java.lang.StringBuilder()
const app = ApplicationManager.getApplication()
const state = { editor: null, before: null }
function edt(body) { app.invokeAndWait(new java.lang.Runnable({ run: body }), ModalityState.any()) }

function where(file, offset) {
    const document = FileDocumentManager.getInstance().getDocument(file)
    const line = document.getLineNumber(offset)
    const text = String(document.getText()).substring(document.getLineStartOffset(line), document.getLineEndOffset(line)).trim()
    return file.getName() + ":" + (line + 1) + "  " + text
}

edt(function () {
    const file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    const editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0), true)
    const at = String(editor.getDocument().getText()).indexOf("__AT__")
    if (at < 0) { out.append("__AT__ not found\n"); return }
    editor.getCaretModel().moveToOffset(at)
    state.editor = editor
    state.before = where(file, at)
})

if (state.editor != null) {
    const report = ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: function () {
        const psi = PsiDocumentManager.getInstance(project).getPsiFile(state.editor.getDocument())
        const leaf = psi.findElementAt(state.editor.getCaretModel().getOffset())
        const serves = navigationClass.getMethod("serves", loader.loadClass("com.intellij.psi.PsiFile")).invoke(navigation, psi)
        let text = "token: " + leaf.getText() + "\nanswers: " + (String(serves) == "true" ? "built-in tree (NAVIGATION native)" : "server") + "\n"
        const targets = navigationClass.getMethod("targets", psiElementClass).invoke(navigation, leaf)
        if (targets == null) text += "tree: none\n"
        else for (let i = 0; i < targets.size(); i++) text += "tree: " + where(targets.get(i).getContainingFile().getVirtualFile(), targets.get(i).getTextOffset()) + "\n"
        return text
    } }))
    out.append(report)
    out.append("from: " + state.before + "\n")
    edt(function () {
        ActionUtil.invokeAction(ActionManager.getInstance().getAction("GotoDeclaration"), DataManager.getInstance().getDataContext(state.editor.getContentComponent()), "unknown", null, null)
    })
    // the server answers asynchronously; the built-in tree has answered already
    java.lang.Thread.sleep(1500)
    edt(function () {
        const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
        const file = FileDocumentManager.getInstance().getFile(editor.getDocument())
        const now = where(file, editor.getCaretModel().getOffset())
        out.append(now == state.before ? "to: (caret stayed — a list of targets or nowhere to go)\n" : "to: " + now + "\n")
    })
}
out.toString()
