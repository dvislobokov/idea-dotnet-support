importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(com.intellij.xdebugger.breakpoints.XBreakpointType)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const type = XBreakpointType.EXTENSION_POINT_NAME.getExtensionList().stream().filter(function (t) { return t.getId() == "dotnet-exception" }).findFirst().orElse(null)
const all = XDebuggerManager.getInstance(project).getBreakpointManager().getBreakpoints(type).toArray()
let text = ""
for (let i = 0; i < all.length; i++) text += (all[i].isEnabled() ? "[x] " : "[ ] ") + type.getDisplayText(all[i]) + "\n"
text
