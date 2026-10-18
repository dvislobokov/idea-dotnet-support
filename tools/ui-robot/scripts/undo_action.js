// Runs the Undo action in the selected editor the way the IDE does (not blocking: a dialog it opens is seen by `robot.py windows`), then
// prints what the undo manager says before: available or not, the command's name.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.command.undo.UndoManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.ide.DataManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder()
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var manager = UndoManager.getInstance(project)
    var editor = FileEditorManager.getInstance(project).getSelectedEditor()
    var available = manager.isUndoAvailable(editor)
    out.append(editor.getFile().getName() + ": undo " + (available ? "available: " + manager.getUndoActionNameAndDescription(editor).first : "not available") + "\n")
    var textEditor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
        ActionUtil.invokeAction(ActionManager.getInstance().getAction("$Undo"), DataManager.getInstance().getDataContext(textEditor.getContentComponent()), "unknown", null, null)
    } }), ModalityState.nonModal())
    out.append("undo invoked\n")
} }), ModalityState.any())
out.toString()
