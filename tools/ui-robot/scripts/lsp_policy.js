// Who does what in the selected editor: the language server or the heuristics of the plugin. Colors of identifiers by key, fold regions
// (and how many of them share a range: two builders at once). Whether the server is ready: lsp_state.js.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
importClass(com.intellij.lang.annotation.HighlightSeverity)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
const infos = com.intellij.openapi.application.ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: function () {
    return DaemonCodeAnalyzerImpl.getHighlights(editor.getDocument(), null, project)
} }))
const byKey = new java.util.TreeMap()
for (let i = 0; i < infos.size(); i++) {
    if (infos.get(i).forcedTextAttributesKey == null) continue
    byKey.merge(String(infos.get(i).forcedTextAttributesKey.getExternalName()), java.lang.Integer.valueOf(1), function (a, b) { return java.lang.Integer.valueOf(a + b) })
}
const holder = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const regions = editor.getFoldingModel().getAllFoldRegions()
    const ranges = new java.util.HashSet()
    for (let r = 0; r < regions.length; r++) ranges.add(regions[r].getStartOffset() + "-" + regions[r].getEndOffset())
    holder.set("fold regions: " + regions.length + ", distinct ranges: " + ranges.size())
} }))
"colors by key: " + byKey + "\n" + holder.get()
