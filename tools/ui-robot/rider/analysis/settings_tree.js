// The whole Settings tree: configurable display names, ids, classes (nested).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil)
importClass(com.intellij.openapi.options.SearchableConfigurable)
importClass(com.intellij.openapi.options.Configurable)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder("@@@")
var n = 0
function pad(d) { var s = ""; for (var i = 0; i < d; i++) s += "  "; return s }
function walk(c, d) {
    if (d > 10) return
    var id = ""
    try { if (c instanceof SearchableConfigurable) id = c.getId() } catch (e) {}
    var cls = c.getClass().getName()
    try { if (c.getOriginalClass) cls = c.getOriginalClass().getName() } catch (e) {}
    out.append(pad(d) + c.getDisplayName() + "  {" + id + "}  " + String(cls).replace(/^.*\./, "") + "\n")
    n++
    if (c instanceof Configurable.Composite) {
        var ch = null
        try { ch = c.getConfigurables() } catch (e) { out.append(pad(d + 1) + "(children failed: " + e + ")\n") }
        if (ch != null) for (var i = 0; i < ch.length; i++) walk(ch[i], d + 1)
    }
}
app = ApplicationManager.getApplication()
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var g = ConfigurableExtensionPointUtil.getConfigurableGroup(project, true)
    var top = g.getConfigurables()
    for (var i = 0; i < top.length; i++) walk(top[i], 0)
} }), ModalityState.any())
out.append("configurables: " + n + "\n")
out.toString()
