// Ctrl + mouse over the first __TEXT__ of the selected editor, with the AWT robot inside the IDE; after a pause the highlighters of the
// editor at that place are listed, among them the link the platform paints for a Go to Declaration target.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(java.awt.event.KeyEvent)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var editor = ReadAction.compute(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() })
var document = editor.getDocument()
var offset = String(document.getText()).indexOf("__TEXT__") + 2
var located = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    var point = editor.offsetToXY(offset)
    var screen = editor.getContentComponent().getLocationOnScreen()
    located.complete([screen.x + point.x, screen.y + point.y + editor.getLineHeight() / 2])
} }))
var at = located.get(10, TimeUnit.SECONDS)
// `Robot` alone is ambiguous with the one of the robot server: the AWT class by its full name
var robot = new java.awt.Robot()
robot.mouseMove(at[0] - 40, at[1] + 60)
java.lang.Thread.sleep(300)
robot.keyPress(KeyEvent.VK_CONTROL)
robot.mouseMove(at[0], at[1])
java.lang.Thread.sleep(200)
robot.mouseMove(at[0] + 2, at[1])
java.lang.Thread.sleep(2500)
var out = ReadAction.compute(function () {
    var highlighters = editor.getMarkupModel().getAllHighlighters()
    var text = "editor highlighters at the place: "
    var n = 0
    for (var i = 0; i < highlighters.length; i++) {
        var h = highlighters[i]
        if (h.getStartOffset() <= offset && offset <= h.getEndOffset()) { n++; text += "\n  " + h.getStartOffset() + "-" + h.getEndOffset() + " '" + String(document.getText()).substring(h.getStartOffset(), h.getEndOffset()) + "' attrs=" + h.getTextAttributes(editor.getColorsScheme()) }
    }
    return text + (n == 0 ? "none" : "")
})
robot.keyRelease(KeyEvent.VK_CONTROL)
robot.mouseMove(at[0] - 40, at[1] + 60)
out
