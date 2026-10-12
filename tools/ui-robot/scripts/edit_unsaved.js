// Replaces __OLD__ with __NEW__ in the document of __FILE__ and leaves the document unsaved.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const document = FileDocumentManager.getInstance().getDocument(file)
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () {
        const text = String(document.getText())
        const at = text.indexOf("__OLD__")
        if (at < 0) throw new java.lang.IllegalStateException("no __OLD__ in the document")
        document.replaceString(at, at + "__OLD__".length, "__NEW__")
    } }))
} }))
"unsaved: " + FileDocumentManager.getInstance().isFileModified(file)
