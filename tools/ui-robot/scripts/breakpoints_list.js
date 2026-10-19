// The line breakpoints of the sandbox project: file:line (1-based), enabled, the type's display text (a lambda's says "lambda N"),
// the highlighted range of a lambda's breakpoint. __REMOVE__ = true removes them all afterwards, line — those of whole lines.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(com.intellij.xdebugger.breakpoints.XLineBreakpoint)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder()
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var manager = XDebuggerManager.getInstance(project).getBreakpointManager()
    var all = manager.getAllBreakpoints()
    for (var i = 0; i < all.length; i++) {
        var b = all[i]
        if (!(b instanceof XLineBreakpoint)) continue
        var range = b.getType().getHighlightRange(b)
        out.append(b.getShortFilePath() + ":" + (b.getLine() + 1) + " enabled=" + b.isEnabled() + " | " + b.getType().getDisplayText(b) +
            (range == null ? "" : " | range " + range) + "\n")
    }
    if ("__REMOVE__" == "true") com.intellij.openapi.application.WriteAction.run(new com.intellij.util.ThrowableRunnable({ run: function () {
        for (var k = 0; k < all.length; k++) if (all[k] instanceof XLineBreakpoint) manager.removeBreakpoint(all[k])
    } }))
    if ("__REMOVE__" == "line") com.intellij.openapi.application.WriteAction.run(new com.intellij.util.ThrowableRunnable({ run: function () {
        for (var k = 0; k < all.length; k++) if (all[k] instanceof XLineBreakpoint && all[k].getType().getHighlightRange(all[k]) == null) manager.removeBreakpoint(all[k])
    } }))
} }), ModalityState.any())
out.toString()
