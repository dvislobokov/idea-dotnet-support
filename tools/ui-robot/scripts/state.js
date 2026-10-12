importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(com.intellij.execution.RunManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const session = XDebuggerManager.getInstance(project).getCurrentSession()
let text = "configurations: " + RunManager.getInstance(project).getAllSettings().stream().map(function (s) { return s.getName() }).toArray().join(", ") + "\n"
if (session == null) text += "session: none"
else {
    const position = session.getCurrentPosition()
    text += "session: " + session.getSessionName() + " suspended=" + session.isSuspended() + " stopped=" + session.isStopped() +
        " at " + (position == null ? "?" : position.getFile().getName() + ":" + (position.getLine() + 1))
}
text
