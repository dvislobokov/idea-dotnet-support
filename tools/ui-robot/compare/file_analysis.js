// Opens __FILE__ (absolute, forward slashes) in the last project, waits until the daemon has finished on it (at most __TIMEOUT__ s) and
// prints the time from the opening to the first finished pass, then every highlight of severity ERROR and WARNING with its line, code
// text and description. Works in any IntelliJ IDE (Rider's frontend too: there the highlights come from the backend).
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.psi.PsiManager)
importClass(com.intellij.lang.annotation.HighlightSeverity)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const now = function () { return java.lang.System.nanoTime() / 1e6 }
const read = function (f) { return ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: f })) }
const file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
let out = ""
if (file == null) out = "no file\n"
else {
    const start = now()
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0, 0), true)
    } }))
    const opened = now()
    const psi = read(function () { return PsiManager.getInstance(project).findFile(file) })
    const analyzer = DaemonCodeAnalyzerEx.getInstanceEx(project)
    const document = read(function () { return com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file) })
    let finishedAt = -1, stable = 0, last = -1
    while (now() - start < __TIMEOUT__ * 1000) {
        var finished = read(function () { return analyzer.isErrorAnalyzingFinished(psi) })
        if (finished && finishedAt < 0) finishedAt = now()
        var count = read(function () { return DaemonCodeAnalyzerImpl.getHighlights(document, HighlightSeverity.WARNING, project).size() })
        // Rider's backend sends its highlights after the frontend's daemon is done: wait until the number has not moved for 3 s
        if (finished && count == last) stable += 100; else stable = 0
        last = count
        if (finished && stable >= __STABLE__) break
        java.lang.Thread.sleep(100)
    }
    out += "open=" + Math.round(opened - start) + " daemonFinished=" + (finishedAt < 0 ? "never" : Math.round(finishedAt - start)) + " stableAt=" + Math.round(now() - start - stable) + "\n"
    const infos = read(function () { return DaemonCodeAnalyzerImpl.getHighlights(document, HighlightSeverity.WARNING, project) })
    let errors = 0, warnings = 0
    const lines = []
    for (var i = 0; i < infos.size(); i++) {
        var h = infos.get(i)
        var error = h.getSeverity().compareTo(HighlightSeverity.ERROR) >= 0
        if (error) errors++; else warnings++
        if (lines.length < __LIMIT__ && (error || "__WARNINGS__" == "yes")) {
            var line = document.getLineNumber(h.getStartOffset()) + 1
            var code = String(document.getText(new com.intellij.openapi.util.TextRange(h.getStartOffset(), Math.min(h.getEndOffset(), h.getStartOffset() + 60)))).replace(/\n/g, " ")
            lines.push((error ? "E " : "W ") + line + " [" + code + "] " + String(h.getDescription()).substring(0, 160))
        }
    }
    out += "errors=" + errors + " warnings=" + warnings + "\n" + lines.join("\n") + "\n"
}
out
