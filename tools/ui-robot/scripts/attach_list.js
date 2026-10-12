// What Run | Attach to Process offers the .NET debugger for: the local processes whose executable name contains __NAME__ (case-insensitive,
// empty: all of them that are offered), each with its pid and the debuggers of the plugin's provider. Run it twice when hosts of the desktop
// CLR matter: the first call starts the diagnostics helper, whose answer the list does not wait for long.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.xdebugger.attach.LocalAttachHost)
importClass(com.intellij.xdebugger.attach.XAttachDebuggerProvider)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var needle = "__NAME__".toLowerCase()
var providers = XAttachDebuggerProvider.EP.getExtensionList()
var provider = null
for (var i = 0; i < providers.size(); i++) if (String(providers.get(i).getClass().getName()).indexOf("DotNetAttachDebuggerProvider") >= 0) provider = providers.get(i)
var processes = LocalAttachHost.INSTANCE.getProcessList()
var report = new java.lang.StringBuilder("provider: " + (provider == null ? "none" : provider.getClass().getSimpleName()) + "\n")
var offered = 0
for (var i = 0; i < processes.size(); i++) {
    var info = processes.get(i)
    var name = String(info.getExecutableName()).toLowerCase()
    if (needle.length > 0 && name.indexOf(needle) < 0) continue
    var debuggers = provider.getAvailableDebuggers(project, LocalAttachHost.INSTANCE, info, new com.intellij.openapi.util.UserDataHolderBase())
    if (needle.length == 0 && debuggers.isEmpty()) continue
    offered++
    report.append("  " + info.getPid() + " " + info.getExecutableName() + " [" + info.getExecutableCannonicalPath().orElse("no path") + "] -> " + (debuggers.isEmpty() ? "not offered" : debuggers.get(0).getDebuggerDisplayName()) + "\n")
}
report.append("listed: " + offered)
String(report)
