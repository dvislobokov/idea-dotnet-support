// The text of the selected editor with the caret as `|`, lines numbered from 1 — what real typing (screen.sh type / key) did to it.
// __FROM__ / __TO__: the lines to print (1-based, inclusive; 0 0 — all).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder()
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var document = editor.getDocument()
    var text = String(document.getText())
    var caret = editor.getCaretModel().getOffset()
    var marked = text.substring(0, caret) + "|" + text.substring(caret)
    var lines = marked.split("\n")
    var from = __FROM__ > 0 ? __FROM__ : 1
    var to = __TO__ > 0 ? Math.min(__TO__, lines.length) : lines.length
    for (var i = from; i <= to; i++) out.append(i + ": " + lines[i - 1].replace(/ /g, "·") + "\n")
} }), ModalityState.any())
out.toString()
