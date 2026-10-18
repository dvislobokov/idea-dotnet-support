// Selects in the selected editor the text from the first __FROM__ (its start) to the end of the first __TO__ after it, then runs the action
// __ACTION__ with the editor's data context later on the EDT (not waiting: a dialog it opens is answered by screen.sh type / key).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.ide.DataManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var result = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var text = String(editor.getDocument().getText())
    var from = text.indexOf("__FROM__")
    var to = from < 0 ? -1 : text.indexOf("__TO__", from)
    if (from < 0 || to < 0) { result.set("not found"); return }
    editor.getSelectionModel().setSelection(from, to + "__TO__".length)
    result.set("selected " + from + ".." + (to + "__TO__".length))
    ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
        ActionUtil.invokeAction(ActionManager.getInstance().getAction("__ACTION__"), DataManager.getInstance().getDataContext(editor.getContentComponent()), "unknown", null, null)
    } }), ModalityState.nonModal())
} }), ModalityState.any())
result.get()
