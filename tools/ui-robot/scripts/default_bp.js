importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(com.intellij.xdebugger.breakpoints.XBreakpointType)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const type = XBreakpointType.EXTENSION_POINT_NAME.getExtensionList().stream().filter(function (t) { return t.getId() == "dotnet-exception" }).findFirst().orElse(null)
const manager = XDebuggerManager.getInstance(project).getBreakpointManager()
const all = manager.getBreakpoints(type).toArray()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    for (let i = 0; i < all.length; i++) if (manager.isDefaultBreakpoint(all[i])) all[i].setEnabled(__ENABLED__)
} }))
"ok"
