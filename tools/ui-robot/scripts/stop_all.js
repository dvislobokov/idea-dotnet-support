importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.xdebugger.XDebuggerManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const sessions = XDebuggerManager.getInstance(project).getDebugSessions()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { for (let i = 0; i < sessions.length; i++) sessions[i].stop() } }))
"stopping " + sessions.length
