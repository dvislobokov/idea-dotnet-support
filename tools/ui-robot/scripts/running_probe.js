// What the platform knows of the running configurations (why a second Debug of the same configuration did not ask "Stop and Rerun"):
// every run configuration named __NAME__ with its "Allow multiple instances" (isAllowRunningInParallel), the running descriptors the
// execution manager keeps for it (their process handlers, terminated or not), and the descriptors of the Run / Debug / Services views.
// __STOP__ = true: the execution manager stops the running descriptors of the configuration, as a rerun does (stopProcess).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.execution.RunManager)
importClass(com.intellij.execution.impl.ExecutionManagerImpl)
importClass(com.intellij.execution.ui.RunContentManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder()
var manager = ExecutionManagerImpl.getInstance(project)
var all = RunManager.getInstance(project).getAllSettings()
for (var i = 0; i < all.size(); i++) {
    var s = all.get(i)
    if (s.getName() != "__NAME__") continue
    var c = s.getConfiguration()
    out.append("config " + s.getName() + ": allowRunningInParallel=" + c.isAllowRunningInParallel() + " policy=" + s.getFactory().getSingletonPolicy() + "\n")
    var running = manager.getRunningDescriptors(new com.intellij.openapi.util.Condition({ value: function (x) { return x === s } }))
    out.append("  running descriptors of it: " + running.size() + "\n")
    for (var k = 0; k < running.size(); k++) {
        var h = running.get(k).getProcessHandler()
        out.append("    #" + running.get(k).getId() + " " + h.getClass().getName() + "@" + java.lang.System.identityHashCode(h) + " terminated=" + h.isProcessTerminated() + "\n")
        if ("__STOP__" == "true") ExecutionManagerImpl.Companion.stopProcess(running.get(k))
    }
}
var descriptors = RunContentManager.getInstance(project).getAllDescriptors()
for (var j = 0; j < descriptors.size(); j++) {
    var d = descriptors.get(j)
    var h = d.getProcessHandler()
    out.append("view #" + d.getId() + " " + d.getDisplayName() + ": " + (h == null ? "none" : h.getClass().getName() + "@" + java.lang.System.identityHashCode(h) + " terminated=" + h.isProcessTerminated()) + "\n")
}
out.toString()
