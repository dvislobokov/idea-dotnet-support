// Drops the unsaved edits of __FILE__: the document is reloaded from disk.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
var vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
var result = "no file"
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var document = FileDocumentManager.getInstance().getDocument(vfile)
    var modified = FileDocumentManager.getInstance().isDocumentUnsaved(document)
    if (modified) FileDocumentManager.getInstance().reloadFromDisk(document)
    result = "reloaded: " + modified + ", length " + document.getTextLength()
} }))
result
