// Comparison with Rider on a big project (docs/compare): opens __DIR__ and records, inside the IDE, when the project is open, when the
// IDE leaves dumb mode (indexing done) and when the index of assemblies of the plugin is ready. Returns at once; status.js reads the times.
// Works in the plugin's sandbox only (it asks the plugin's services through the plugin's class loader).
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.project.DumbService)
importClass(com.intellij.ide.impl.ProjectUtil)
const dir = "__DIR__"
const now = function () { return java.lang.Double.valueOf(java.lang.System.nanoTime() / 1e6) }
const rec = new java.util.concurrent.ConcurrentHashMap()
java.lang.System.getProperties().put("dotnet.compare", rec)
const mark = function (key) { rec.putIfAbsent(key, now()) }
mark("t0")
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { ProjectUtil.openOrImport(dir, null, true) } }))
new java.lang.Thread(new java.lang.Runnable({ run: function () {
    try {
        const t0 = rec.get("t0")
        let project = null
        while (project == null || !project.isInitialized()) {
            if (now() - t0 > 600000) { rec.put("_error", "the project did not open"); return }
            java.lang.Thread.sleep(20)
            const open = ProjectManager.getInstance().getOpenProjects()
            for (var p = 0; p < open.length; p++) if (open[p].getBasePath() != null && String(open[p].getBasePath()).toLowerCase() == dir.toLowerCase()) project = open[p]
        }
        mark("projectOpen")
        const plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("io.github.dotnetsupport"))
        const loader = plugin.getPluginClassLoader()
        const solutions = project.getService(loader.loadClass("io.github.dotnetsupport.solution.SolutionService"))
        const assemblies = project.getService(loader.loadClass("io.github.dotnetsupport.index.AssemblyIndexService"))
        let wasDumb = false
        while (now() - t0 < 1800000) {
            if (DumbService.isDumb(project)) { wasDumb = true; mark("dumbStart") }
            else if (wasDumb || now() - t0 > 20000) mark("smart")
            try { if (solutions.solutionFiles().size() > 0) mark("solutionFound") } catch (e) {}
            if (assemblies.isReady()) mark("assembliesReady")
            if (rec.get("smart") != null && rec.get("assembliesReady") != null) break
            java.lang.Thread.sleep(50)
        }
        mark("_finished")
    } catch (e) { rec.put("_error", String(e)) }
} })).start()
"started"
