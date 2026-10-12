// Sets the hit count __HITS__ and the log message __LOG__ of the .NET line breakpoint at __FILE__:__LINE__ (1-based), creating it if needed.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(com.intellij.xdebugger.breakpoints.XBreakpointType)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const type = XBreakpointType.EXTENSION_POINT_NAME.getExtensionList().stream().filter(function (t) { return t.getId() == "dotnet-line" }).findFirst().get()
const file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
const manager = XDebuggerManager.getInstance(project).getBreakpointManager()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    ApplicationManager.getApplication().runWriteAction(new java.lang.Runnable({ run: function () {
        let breakpoint = manager.findBreakpointAtLine(type, file, __LINE__ - 1)
        if (breakpoint == null) breakpoint = manager.addLineBreakpoint(type, file.getUrl(), __LINE__ - 1, type.createBreakpointProperties(file, __LINE__ - 1))
        breakpoint.getProperties().setHitCondition("__HITS__")
        breakpoint.getProperties().setLogMessage("__LOG__")
        breakpoint.fireBreakpointChanged()
    } }))
} }))
"ok"
