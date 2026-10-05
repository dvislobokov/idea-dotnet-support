// Clicks the line marker of the selected editor on the line that contains __TEXT__ (its navigation handler, with a synthetic mouse event on the editor component).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
importClass(java.awt.event.MouseEvent)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var editor = ReadAction.compute(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() })
var document = editor.getDocument()
var found = ReadAction.compute(function () {
    var line = document.getLineNumber(String(document.getText()).indexOf("__TEXT__"))
    var markers = DaemonCodeAnalyzerImpl.getLineMarkers(document, project)
    for (var i = 0; i < markers.size(); i++) {
        var m = markers.get(i)
        if (document.getLineNumber(m.startOffset) == line && m.getNavigationHandler() != null && String(m.getIcon()).indexOf("gutter/run") < 0) return m
    }
    return null
})
if (found == null) "no marker with a navigation handler on that line"
else {
    var done = new CompletableFuture()
    ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
        try {
            var component = editor.getContentComponent()
            var point = editor.offsetToXY(found.startOffset)
            var event = new MouseEvent(component, MouseEvent.MOUSE_CLICKED, java.lang.System.currentTimeMillis(), 0, point.x, point.y, 1, false, MouseEvent.BUTTON1)
            found.getNavigationHandler().navigate(event, found.getElement())
            done.complete("navigated from marker with tooltip: " + found.getLineMarkerTooltip())
        } catch (e) { done.complete("failed: " + e) }
    } }))
    done.get(30, TimeUnit.SECONDS)
}
