importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(com.intellij.xdebugger.breakpoints.XBreakpointType)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const type = XBreakpointType.EXTENSION_POINT_NAME.getExtensionList().stream().filter(function (t) { return t.getId() == "dotnet-exception" }).findFirst().orElse(null)
if (type == null) throw new java.lang.IllegalStateException("no dotnet-exception breakpoint type")
const TYPES = "__TYPES__"
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    ApplicationManager.getApplication().runWriteAction(new java.lang.Runnable({ run: function () {
        const manager = XDebuggerManager.getInstance(project).getBreakpointManager()
        // one "thrown" breakpoint for the robot: drop the ones made by earlier runs
        const old = manager.getBreakpoints(type).toArray()
        for (let i = 0; i < old.length; i++) if (!manager.isDefaultBreakpoint(old[i])) manager.removeBreakpoint(old[i])
        if (TYPES != "-") {
            const added = type.addBreakpoint(project, null)
            added.getProperties().setTypes(TYPES)
        }
    } }))
} }))
"ok"
