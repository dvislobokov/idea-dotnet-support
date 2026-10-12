// Where on the screen a piece of text is, for real mouse input (tools/ui-robot/wsl/screen.sh move / click): opens __FILE__ (absolute
// path), finds the first __AT__ after __AFTER__ (empty: from the start), scrolls it into view and prints `at x y` of the middle of its first
// character in screen coordinates, then the line:column. The IDE window should be the only thing on the screen (the Xvfb one of WSL).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.editor.ScrollType)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder()
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    if (file == null) { out.append("no file __FILE__"); return }
    var editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0), true)
    var text = String(editor.getDocument().getText())
    var from = "__AFTER__" == "" ? 0 : text.indexOf("__AFTER__")
    var at = text.indexOf("__AT__", Math.max(from, 0))
    if (at < 0) { out.append("__AT__ not found"); return }
    editor.getCaretModel().moveToOffset(at)
    editor.getScrollingModel().scrollToCaret(ScrollType.CENTER)
    editor.getScrollingModel().runActionOnScrollingFinished(new java.lang.Runnable({ run: function () {} }))
    var point = editor.offsetToXY(at)
    var component = editor.getContentComponent()
    var screen = component.getLocationOnScreen()
    var x = screen.x + point.x + 4
    var y = screen.y + point.y + Math.floor(editor.getLineHeight() / 2)
    var position = editor.offsetToLogicalPosition(at)
    out.append("at " + String(x) + " " + String(y) + " " + (position.line + 1) + ":" + (position.column + 1))
} }), ModalityState.nonModal())
out.toString()
