// Presses __KEY__ (a java.awt.event.KeyEvent VK_ name without the prefix: TAB, ENTER, ESCAPE, DOWN, END...) with the AWT robot of the IDE,
// after bringing the project window to the front and the focus into the selected editor: the key goes through the IDE's own dispatch.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var popupShowing = com.intellij.codeInsight.lookup.LookupManager.getActiveLookup(editor) != null
    if (!popupShowing) editor.getContentComponent().requestFocusInWindow()
} }), ModalityState.any())
java.lang.Thread.sleep(150)
var rb = new java.awt.Robot()
var code = java.awt.event.KeyEvent["VK___KEY__"]
rb.keyPress(code)
rb.keyRelease(code)
"@@@pressed __KEY__"
