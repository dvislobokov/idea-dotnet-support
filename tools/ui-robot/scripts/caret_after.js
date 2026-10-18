// Opens __FILE__ (forward slashes) in an editor, puts the caret right after the __OCCURRENCE__-th (1-based) occurrence of __AT__ and focuses
// the editor: real typing (screen.sh type / key) goes there next — inside a tag (`<a ` → after it), on a name (Shift+F6, Ctrl+B).
// Prints the offset and the text of the line around the caret (`|` marks it).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.editor.ScrollType)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
var result = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vfile), true)
    var document = editor.getDocument()
    var text = String(document.getText())
    var at = -1
    for (var n = 0; n < __OCCURRENCE__; n++) at = text.indexOf("__AT__", at + 1)
    if (at < 0) { result.set("no __AT__ in " + vfile.getName()); return }
    var offset = at + "__AT__".length
    editor.getCaretModel().moveToOffset(offset)
    editor.getScrollingModel().scrollToCaret(ScrollType.CENTER)
    editor.getContentComponent().requestFocusInWindow()
    var line = document.getLineNumber(offset)
    var start = document.getLineStartOffset(line)
    var end = document.getLineEndOffset(line)
    result.set("caret at " + offset + " (line " + (line + 1) + "): " + text.substring(start, offset) + "|" + text.substring(offset, end))
} }))
result.get()
