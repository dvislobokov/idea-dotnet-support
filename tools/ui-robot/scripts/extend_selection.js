// Extend Selection (Ctrl+W, action EditorSelectWord) in __FILE__ (forward slashes) with the caret on the second character of the first __AT__,
// pressed __TIMES__ times; prints each selection (line breaks as `\n`, long ones cut in the middle). Switch «Typing assistance» with
// feature_source.js (`EDITING`) and compare: the scenarios are in debug-playground/Console/Editor/ExtendSelection.cs (`TYPE:extend-selection-*`).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.ide.DataManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const out = new java.lang.StringBuilder()
function shown(text) {
    var s = String(text).replace(/\r/g, "").replace(/\n/g, "\\n")
    return s.length > 160 ? s.substring(0, 75) + " … " + s.substring(s.length - 75) : s
}
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    const editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0), true)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    const psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
    out.append("tree: " + psi.getClass().getSimpleName() + "\n")
    const at = String(editor.getDocument().getText()).indexOf("__AT__")
    if (at < 0) { out.append("__AT__ not found"); return }
    editor.getSelectionModel().removeSelection()
    editor.getCaretModel().moveToOffset(at + 1)
    const action = ActionManager.getInstance().getAction("EditorSelectWord")
    var i = 0
    for (i = 1; i <= __TIMES__; i++) {
        ActionUtil.invokeAction(action, DataManager.getInstance().getDataContext(editor.getContentComponent()), "unknown", null, null)
        out.append(i + ": [" + shown(editor.getSelectionModel().getSelectedText()) + "]\n")
    }
    editor.getSelectionModel().removeSelection()
} }), ModalityState.any())
out.toString()
