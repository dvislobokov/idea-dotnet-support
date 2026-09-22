importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.xdebugger.XDebuggerManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const sessions = XDebuggerManager.getInstance(projects[projects.length - 1]).getDebugSessions()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { for (let i = 0; i < sessions.length; i++) if (sessions[i].isSuspended()) sessions[i].resume() } }))
"resumed"
