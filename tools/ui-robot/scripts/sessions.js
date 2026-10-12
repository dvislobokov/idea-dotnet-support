importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.xdebugger.XDebuggerManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const sessions = XDebuggerManager.getInstance(project).getDebugSessions()
let text = "debug sessions: " + sessions.length
for (let i = 0; i < sessions.length; i++) {
    const position = sessions[i].getCurrentPosition()
    text += "\n  " + sessions[i].getSessionName() + " suspended=" + sessions[i].isSuspended() + " at " + (position == null ? "?" : position.getFile().getName() + ":" + (position.getLine() + 1))
}
text
