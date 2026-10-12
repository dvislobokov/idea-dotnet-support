// Performs the action __ACTION__ in the selected editor with the caret inside the first __AT__: with the data context of the editor,
// which the plain `robot.py action` does not give (Rename, Show Usages, Go to Declaration do nothing without it).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.ide.DataManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const holder = new java.util.concurrent.atomic.AtomicReference("performed __ACTION__")
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    const at = String(editor.getDocument().getText()).indexOf("__AT__")
    if (at < 0) return
    editor.getCaretModel().moveToOffset(at + 1)
    ActionUtil.invokeAction(ActionManager.getInstance().getAction("__ACTION__"), DataManager.getInstance().getDataContext(editor.getContentComponent()), "unknown", null, null)
} }))
holder.get()
