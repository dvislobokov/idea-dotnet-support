// The timings of the requests to the Roslyn server of the last opened project (menu .NET | Language Server Timings), plus where the
// server is: loaded or not, how many files are colored by tokens from the cache. Poll it to see what happens while a solution loads.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.extensions.ExtensionPointName)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const providers = ExtensionPointName.create("com.intellij.platform.lsp.integrationProvider").getExtensionList()
let text = "no Roslyn provider"
for (let i = 0; i < providers.size(); i++) {
    if (providers.get(i).getClass().getName().indexOf("Roslyn") < 0) continue
    const loader = providers.get(i).getClass().getClassLoader()
    const workspace = project.getService(loader.loadClass("io.github.dotnetsupport.roslyn.RoslynWorkspace"))
    const stats = project.getService(loader.loadClass("io.github.dotnetsupport.roslyn.RoslynRequestStats"))
    const status = project.getService(loader.loadClass("io.github.dotnetsupport.lsp.RoslynServerStatus"))
    text = "t=" + java.lang.System.currentTimeMillis() + " loaded=" + workspace.isLoaded() + " coloredFromCache=" + status.getColoredFromCache().size() + "\n"
    if ("__TABLE__" == "yes") text += stats.report()
}
text
