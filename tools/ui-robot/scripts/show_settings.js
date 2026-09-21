// Opens the Settings dialog on the page with the display name __PAGE__ (the dialog is modal: the call returns at once).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.options.ShowSettingsUtil)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { ShowSettingsUtil.getInstance().showSettingsDialog(project, "__PAGE__") } }))
"ok"
