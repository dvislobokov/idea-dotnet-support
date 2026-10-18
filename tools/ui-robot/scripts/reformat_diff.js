// Reformat Code on the whole __FILE__ (forward slashes) through the action, as Ctrl+Alt+L does; prints the lines it changed (`-` before,
// `+` after, numbered from 1) and how many, then undoes it and says whether the text is back. For a file whose own formatter is not the
// C# one (Razor: RazorFormattingService).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.ide.DataManager)
importClass(com.intellij.openapi.command.undo.UndoManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder()
var app = ApplicationManager.getApplication()
var editor = null
var before = null
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0), true)
    before = String(editor.getDocument().getText())
    ActionUtil.invokeAction(ActionManager.getInstance().getAction("ReformatCode"), DataManager.getInstance().getDataContext(editor.getContentComponent()), "unknown", null, null)
} }), ModalityState.any())
java.lang.Thread.sleep(2000)
var after = null
app.invokeAndWait(new java.lang.Runnable({ run: function () { after = String(editor.getDocument().getText()) } }), ModalityState.any())
var a = before.split("\n")
var b = after.split("\n")
var changed = 0
for (var i = 0; i < Math.max(a.length, b.length); i++) {
    if (a[i] === b[i]) continue
    changed++
    if (changed > 60) continue
    out.append("-" + (i + 1) + ": " + String(a[i]).replace(/ /g, "·") + "\n")
    out.append("+" + (i + 1) + ": " + String(b[i]).replace(/ /g, "·") + "\n")
}
out.append("changed lines: " + changed + " of " + a.length + (a.length == b.length ? "" : ", line count " + a.length + " -> " + b.length) + "\n")
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var textEditor = FileEditorManager.getInstance(project).getSelectedEditor()
    var undo = UndoManager.getInstance(project)
    if (changed > 0 && undo.isUndoAvailable(textEditor)) undo.undo(textEditor)
    out.append("undone: " + (String(editor.getDocument().getText()) == before))
} }), ModalityState.any())
out.toString()
