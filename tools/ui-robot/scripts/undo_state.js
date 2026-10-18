// What Ctrl+Z would do in the selected editor: whether undo is available there and the name of the command it would take back
// (`Undo Rename`), and the same for every open editor — to see which documents a global command (a rename over many files) still holds.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.command.undo.UndoManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder()
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var manager = UndoManager.getInstance(project)
    var editors = FileEditorManager.getInstance(project).getAllEditors()
    var selected = FileEditorManager.getInstance(project).getSelectedEditor()
    for (var i = 0; i < editors.length; i++) {
        var editor = editors[i]
        var available = manager.isUndoAvailable(editor)
        var name = available ? manager.getUndoActionNameAndDescription(editor).first : "-"
        out.append((editor === selected ? "* " : "  ") + editor.getFile().getName() + ": undo " + (available ? "available: " + name : "not available") + "\n")
    }
} }), ModalityState.any())
out.toString()
