// Adds a .NET function breakpoint on the method __NAME__ (Namespace.Class.Method, Class.Method or Method), as "+" in the Breakpoints dialog
// does after its input dialog, and prints the function breakpoints there are.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(com.intellij.xdebugger.breakpoints.XBreakpointType)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder()
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var types = XBreakpointType.EXTENSION_POINT_NAME.getExtensionList(), type = null
    for (var t = 0; t < types.size(); t++) if (types.get(t).getId() == "dotnet-function") type = types.get(t)
    var properties = type.createProperties()
    properties.setName("__NAME__")
    var manager = XDebuggerManager.getInstance(project).getBreakpointManager()
    com.intellij.openapi.application.WriteAction.run(new com.intellij.util.ThrowableRunnable({ run: function () { manager.addBreakpoint(type, properties) } }))
    var all = manager.getBreakpoints(type)
    for (var i = 0; i < all.size(); i++) out.append("function breakpoint: " + type.getDisplayText(all.get(i)) + " enabled=" + all.get(i).isEnabled() + "\n")
} }), ModalityState.any())
out.toString()
