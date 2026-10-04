// Step 0 of CSHARP_PSI_MIGRATION.md (driven by tools/ui-robot/baseline.py): opens the project __DIR__ and, as soon as it is open, the file
// __FILE__ (forward slashes), and records inside the IDE when each stage is reached. Returns at once; the recording goes on in a thread of
// the IDE and is read by baseline_status.js. All times are System.nanoTime() in milliseconds, kept in a map under the system property
// "dotnet.baseline": polling from outside cannot change them. __COLD__ = "yes" deletes the cache of semantic tokens of the plugin first.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.PathManager)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.project.DumbService)
importClass(com.intellij.openapi.extensions.ExtensionPointName)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.psi.PsiManager)
importClass(com.intellij.ide.impl.ProjectUtil)
const dir = "__DIR__"
const path = "__FILE__"
const timeoutMs = __TIMEOUT__ * 1000
const now = function () { return java.lang.Double.valueOf(java.lang.System.nanoTime() / 1e6) }
const old = java.lang.System.getProperties().get("dotnet.baseline")
if (old != null) old.put("_stop", "yes")
const rec = new java.util.concurrent.ConcurrentHashMap()
const daemon = new java.util.concurrent.CopyOnWriteArrayList()
rec.put("_daemon", daemon)
// what the editor shows, each time it changes: "ms:count" of highlights with a color of their own (identifiers: the heuristics of the
// plugin, then the semantic tokens of the server) and of problems (warning and above)
const colors = new java.util.concurrent.CopyOnWriteArrayList()
const problems = new java.util.concurrent.CopyOnWriteArrayList()
rec.put("_colors", colors)
rec.put("_problems", problems)
java.lang.System.getProperties().put("dotnet.baseline", rec)
if ("__COLD__" == "yes") com.intellij.openapi.util.io.FileUtil.delete(new java.io.File(PathManager.getSystemPath(), "dotnet-support/lsp-cache/semantic-tokens"))
const mark = function (key) { rec.putIfAbsent(key, now()) }

const findProject = function () {
    const open = ProjectManager.getInstance().getOpenProjects()
    for (var p = 0; p < open.length; p++) if (open[p].getBasePath() != null && String(open[p].getBasePath()).toLowerCase() == dir.toLowerCase()) return open[p]
    return null
}
mark("t0")
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { ProjectUtil.openOrImport(dir, null, true) } }))

new java.lang.Thread(new java.lang.Runnable({ run: function () {
    try {
        const t0 = rec.get("t0")
        let project = null
        while (project == null || !project.isInitialized()) {
            if (now() - t0 > timeoutMs) { rec.put("_error", "the project did not open"); return }
            java.lang.Thread.sleep(10)
            project = findProject()
        }
        mark("projectOpen")
        const file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
        if (file == null) { rec.put("_error", "no file " + path); return }
        ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
            mark("fileOpenStart")
            FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0, 0), true)
            // the editor is created with the highlighter of the lexer: its colors are there from the first paint
            mark("fileOpenEnd")
        } }))
        ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { mark("firstEdtAfterOpen") } }))
        let workspace = null, stats = null
        const providers = ExtensionPointName.create("com.intellij.platform.lsp.integrationProvider").getExtensionList()
        for (var i = 0; i < providers.size(); i++) {
            if (providers.get(i).getClass().getName().indexOf("Roslyn") < 0) continue
            var loader = providers.get(i).getClass().getClassLoader()
            workspace = project.getService(loader.loadClass("io.github.dotnetsupport.roslyn.RoslynWorkspace"))
            stats = project.getService(loader.loadClass("io.github.dotnetsupport.roslyn.RoslynRequestStats"))
        }
        if (workspace == null) { rec.put("_error", "no Roslyn module"); return }
        const read = function (f) { return ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: f })) }
        const psi = read(function () { return PsiManager.getInstance(project).findFile(file) })
        const daemonCodeAnalyzer = DaemonCodeAnalyzerEx.getInstanceEx(project)
        // a pass of the daemon over the file has ended: its "finished" goes from false to true (a listener of DAEMON_EVENT_TOPIC
        // made in Rhino is never called)
        let analyzed = false
        let tick = 0, lastColors = -1, lastProblems = -1
        const document = read(function () { return com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file) })
        let doneAt = -1
        while (now() - t0 < timeoutMs && rec.get("_stop") == null) {
            var finished = read(function () { return daemonCodeAnalyzer.isErrorAnalyzingFinished(psi) })
            if (finished && !analyzed) daemon.add(now())
            analyzed = finished
            if (tick++ % 5 == 0) {
                var infos = read(function () { return com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl.getHighlights(document, null, project) })
                var colored = 0, problematic = 0
                for (var h = 0; h < infos.size(); h++) {
                    if (infos.get(h).forcedTextAttributesKey != null) colored++
                    if (infos.get(h).getSeverity().compareTo(com.intellij.lang.annotation.HighlightSeverity.WARNING) >= 0) problematic++
                }
                var at = Math.round(now() - rec.get("fileOpenStart"))
                if (colored != lastColors) { colors.add(at + ":" + colored); lastColors = colored }
                if (problematic != lastProblems) { problems.add(at + ":" + problematic); lastProblems = problematic }
            }
            if (!DumbService.isDumb(project)) mark("smart")
            if (workspace.getServerProcess() != null) mark("serverProcess")
            var phase = String(workspace.getPhase().name())
            if (phase != "STARTING") mark("phase" + phase)
            if (workspace.isLoaded()) mark("ready")
            var methods = stats.snapshot()
            for (var m = 0; m < methods.size(); m++) {
                var method = methods.get(m)
                // a request of the platform made while the plugin warms the server up is counted as "(warm-up) ..." too
                var name = String(method.getName()).replace("(warm-up) ", "")
                var calls = method.getDurations().size()
                if (name == "textDocument/semanticTokens/full") {
                    if (method.getFromCache() > 0) mark("tokensCache")
                    if (calls > method.getFromCache()) mark("tokensServer")
                }
                if (name == "textDocument/diagnostic" && calls > method.getFromCache()) mark("diagnostics")
            }
            if (rec.get("ready") != null && rec.get("tokensServer") != null && rec.get("diagnostics") != null && doneAt < 0) doneAt = now()
            // a moment more for the passes of the daemon that show what has come
            if (doneAt > 0 && now() - doneAt > 3000) break
            java.lang.Thread.sleep(20)
        }
        mark("_finished")
    } catch (e) {
        rec.put("_error", String(e))
    }
} })).start()
"started"
