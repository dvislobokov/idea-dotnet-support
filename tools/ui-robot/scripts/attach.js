// Attaches the .NET debugger of the plugin to the process __PID__, the way Run | Attach to Process does.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.extensions.ExtensionPointName)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const attachers = ExtensionPointName.create("io.github.dotnetsupport.processAttacher").getExtensionList()
if (attachers.isEmpty()) throw new java.lang.IllegalStateException("no process attacher: the debugger module is not loaded")
attachers.get(0).attach(project, java.lang.Long.valueOf(__PID__), "__TITLE__", false)
"attaching to __PID__"
