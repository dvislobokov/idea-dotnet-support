// The gutter of __FILE__ (forward slashes): opens it, waits __WAIT__ ms for the daemon, prints every line marker as `line: tooltip` (the
// icons of overrides / implementations of NativeCSharpInheritanceLineMarkerProvider among them, task C4b).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var document = null
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    document = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0), true).getDocument()
} }), ModalityState.nonModal())
java.lang.Thread.sleep(__WAIT__)
var out = new java.lang.StringBuilder()
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var markers = DaemonCodeAnalyzerImpl.getLineMarkers(document, project)
    var rows = []
    for (var i = 0; i < markers.size(); i++) {
        var marker = markers.get(i)
        var line = document.getLineNumber(marker.startOffset) + 1
        var tooltip = marker.getLineMarkerTooltip()
        rows.push(line + ": " + tooltip + "  [" + document.getText(new com.intellij.openapi.util.TextRange(marker.startOffset, marker.endOffset)) + "]")
    }
    rows.sort(function (a, b) { return parseInt(a) - parseInt(b) })
    out.append(rows.join("\n"))
} }), ModalityState.nonModal())
out.toString()
