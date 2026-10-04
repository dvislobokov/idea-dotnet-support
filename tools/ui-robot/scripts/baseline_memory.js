// Memory for tools/ui-robot/baseline.py: the heap of the IDE after a full GC, the PID of the IDE and of the language server with all
// the processes it has started (the build host of MSBuild): their resident memory is read from outside (Get-Process).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.extensions.ExtensionPointName)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const bean = java.lang.management.ManagementFactory.getMemoryMXBean()
for (var g = 0; g < 3; g++) { java.lang.System.gc(); java.lang.Thread.sleep(300) }
const heap = bean.getHeapMemoryUsage()
let text = "heapUsedMb=" + Math.round(heap.getUsed() / 1048576) + "\nheapCommittedMb=" + Math.round(heap.getCommitted() / 1048576) + "\nheapMaxMb=" + Math.round(heap.getMax() / 1048576) + "\n"
text += "idePid=" + java.lang.ProcessHandle.current().pid() + "\n"
const providers = ExtensionPointName.create("com.intellij.platform.lsp.integrationProvider").getExtensionList()
for (var i = 0; i < providers.size(); i++) {
    if (providers.get(i).getClass().getName().indexOf("Roslyn") < 0) continue
    var workspace = project.getService(providers.get(i).getClass().getClassLoader().loadClass("io.github.dotnetsupport.roslyn.RoslynWorkspace"))
    var server = workspace.getServerProcess()
    if (server == null) continue
    text += "serverPid=" + server.pid() + " " + server.info().command().orElse("?") + "\n"
    var children = server.descendants().toArray()
    for (var p = 0; p < children.length; p++) text += "serverPid=" + children[p].pid() + " " + children[p].info().command().orElse("?") + "\n"
}
text
