// Breakpoints at __FILE__ (forward slashes): __MASTER__ (1-based line) "Remove once hit", __SLAVE__ "Disable until hitting" the master,
// as the checkboxes of the breakpoint dialog. With __WHAT__ = list it only lists the .NET line breakpoints of the file and their state.
// (Rhino: `var`, not `const`, inside loops - a `const` there keeps its first value)
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
const dependent = manager.getDependentBreakpointManager()
function list() {
    let text = ""
    const all = manager.getBreakpoints(type)
    for (let i = 0; i < all.size(); i++) {
        var b = all.get(i)
        if (b.getFileUrl() != file.getUrl()) continue
        var master = dependent.getMasterBreakpoint(b)
        text += "line " + (b.getLine() + 1) + " enabled=" + b.isEnabled() + " temporary=" + b.isTemporary() +
            (master == null ? "" : " master=line " + (master.getLine() + 1)) + "\n"
    }
    return text || "no breakpoints\n"
}
if ("__WHAT__" == "list") list()
else {
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        ApplicationManager.getApplication().runWriteAction(new java.lang.Runnable({ run: function () {
            function at(line) {
                return manager.findBreakpointAtLine(type, file, line - 1) || manager.addLineBreakpoint(type, file.getUrl(), line - 1, type.createBreakpointProperties(file, line - 1))
            }
            const master = at(__MASTER__), slave = at(__SLAVE__)
            master.setTemporary(true)
            dependent.setMasterBreakpoint(slave, master, false)
        } }))
    } }))
    list()
}
