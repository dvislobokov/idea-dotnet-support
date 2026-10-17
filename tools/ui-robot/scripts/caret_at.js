// Opens __FILE__ (forward slashes) in an editor, puts the caret at the end of line __LINE__ (1-based) and focuses the editor: real typing
// (screen.sh type / key) goes there next.
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
    var offset = document.getLineEndOffset(__LINE__ - 1)
    editor.getCaretModel().moveToOffset(offset)
    editor.getScrollingModel().scrollToCaret(ScrollType.CENTER)
    editor.getContentComponent().requestFocusInWindow()
    result.set("caret at " + offset + " of " + vfile.getName())
} }))
result.get()
