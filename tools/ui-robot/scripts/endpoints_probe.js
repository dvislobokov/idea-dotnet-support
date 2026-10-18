// What the Endpoints tool window lists (EndpointsModel.discover, the same call it makes): per project, `METHOD route  file`.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var model = loader.loadClass("io.github.dotnetsupport.endpoints.EndpointsModel")
var instance = model.getField("INSTANCE").get(null)
var discover = model.getMethod("discover", loader.loadClass("com.intellij.openapi.project.Project"))
var found = ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: function () { return discover.invoke(instance, project) } }))
var out = new java.lang.StringBuilder()
for (var i = 0; i < found.size(); i++) {
    var p = found.get(i)
    out.append(p.getName() + "\n")
    var endpoints = p.getEndpoints()
    for (var j = 0; j < endpoints.size(); j++) {
        var e = endpoints.get(j)
        out.append("  " + e.getEndpoint().getMethod() + " " + e.getEndpoint().getRoute() + "  " + e.getFile().getName() + "\n")
    }
}
out.toString()
