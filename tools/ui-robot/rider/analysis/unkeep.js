importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var r = "@@@"
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
  com.intellij.codeInsight.lookup.LookupManager.getInstance(project).hideActiveLookup()
  var f = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
  var doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(f)
  com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().reloadFromDisk(doc)
  r += "restored " + doc.getLineCount()
} }))
r
