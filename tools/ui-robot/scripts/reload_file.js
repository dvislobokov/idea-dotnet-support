// Reloads __FILE__ (forward slashes) from disk into its document: after a script left a change the IDE did not save, or saved.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
var result = ""
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var f = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    f.refresh(false, false)
    var doc = FileDocumentManager.getInstance().getDocument(f)
    FileDocumentManager.getInstance().reloadFromDisk(doc)
    result = "reloaded, " + doc.getLineCount() + " lines"
} }), ModalityState.nonModal())
result
