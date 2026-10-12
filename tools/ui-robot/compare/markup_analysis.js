// Rider's side of file_analysis.js: in Rider the problems come from the backend as range highlighters of the document markup model, not as
// HighlightInfo of the daemon. Opens __FILE__, polls the number of highlighters every 100 ms until it has not changed for __STABLE__ ms
// (at most __TIMEOUT__ s) and prints when that was, then the ones whose attributes key names an error (ERRORS_ATTRIBUTES, ReSharper ERROR).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.editor.impl.DocumentMarkupModel)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var now = function () { return java.lang.System.nanoTime() / 1e6 }
var editor = null
var start = now()
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, 0), true)
} }), ModalityState.any())
var opened = now()
var last = -1, stable = 0, firstProblem = -1
var collect = function () {
    var r = { total: 0, problems: [] }
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        var doc = editor.getDocument()
        var hs = DocumentMarkupModel.forDocument(doc, project, true).getAllHighlighters()
        r.total = hs.length
        for (var i = 0; i < hs.length; i++) {
            var key = hs[i].getTextAttributesKey()
            var name = key == null ? "" : String(key.getExternalName())
            if (name.indexOf("ERROR") >= 0 && name.indexOf("UNRESOLVED") < 0 || name.indexOf("UNRESOLVED") >= 0) {
                var s = hs[i].getStartOffset()
                var tip = ""
                try { var t = hs[i].getErrorStripeTooltip(); tip = String(t.getDescription ? t.getDescription() : t).replace(/<[^>]*>/g, "") } catch (e) {}
                r.problems.push("E " + (doc.getLineNumber(s) + 1) + " [" + String(doc.getText(new com.intellij.openapi.util.TextRange(s, Math.min(hs[i].getEndOffset(), s + 60)))) + "] " + name + " " + tip.substring(0, 140))
            }
        }
    } }), ModalityState.any())
    return r
}
while (now() - start < __TIMEOUT__ * 1000) {
    var r = collect()
    if (r.total > 0 && firstProblem < 0) firstProblem = now()
    if (r.total == last && r.total > 0) stable += 100; else stable = 0
    last = r.total
    if (stable >= __STABLE__) break
    java.lang.Thread.sleep(100)
}
var r = collect()
"@@@open=" + Math.round(opened - start) + " firstHighlights=" + (firstProblem < 0 ? "never" : Math.round(firstProblem - start)) + " stableAt=" + Math.round(now() - start - stable) +
    " highlighters=" + r.total + "\nerrors=" + r.problems.length + "\n" + r.problems.slice(0, __LIMIT__).join("\n") + "\n"
