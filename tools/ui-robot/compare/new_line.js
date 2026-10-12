// Opens __FILE__, adds an empty line after the line holding the first __AT__ (the indent of that line plus __EXTRA__ spaces), puts the
// caret at its end and focuses the editor: the place where the probe types with the real keyboard. Prints the line number (1-based).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder("@@@")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    com.intellij.openapi.vfs.VirtualFileManager.getInstance().syncRefresh()
    var file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    var fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
    var unsaved = fdm.getUnsavedDocuments()
    for (var u = 0; u < unsaved.length; u++) fdm.reloadFromDisk(unsaved[u])
    if (file == null) { out.append("no file\n"); return }
    var editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0), true)
    var document = editor.getDocument()
    var text = String(document.getText())
    var at = text.indexOf("__AT__")
    if (at < 0) { out.append("anchor not found\n"); return }
    var line = document.getLineNumber(at)
    var start = document.getLineStartOffset(line), end = document.getLineEndOffset(line)
    var indent = text.substring(start, end).match(/^\s*/)[0] + "                ".substring(0, __EXTRA__)
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { document.insertString(end, "\n" + indent) } }))
    editor.getCaretModel().moveToOffset(end + 1 + indent.length)
    editor.getScrollingModel().scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
    editor.getContentComponent().requestFocusInWindow()
    out.append("line " + (line + 2) + "\n")
} }), ModalityState.any())
out.toString()
