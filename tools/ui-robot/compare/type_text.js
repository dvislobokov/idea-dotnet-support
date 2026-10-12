// Types __TEXT__ into the selected editor character by character through the editor's typed action (as a keystroke does: typed handlers,
// auto-popup of completion, pair braces), __DELAY__ ms apart. Independent of the keyboard layout of Windows, unlike SendKeys.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.editor.actionSystem.TypedAction)
importClass(com.intellij.openapi.command.CommandProcessor)
importClass(com.intellij.ide.DataManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var text = "__TEXT__"
var editor = null
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () { editor = FileEditorManager.getInstance(project).getSelectedTextEditor() } }), ModalityState.any())
for (var i = 0; i < text.length; i++) {
    var c = text.charAt(i)
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        CommandProcessor.getInstance().executeCommand(project, new java.lang.Runnable({ run: function () {
            TypedAction.getInstance().actionPerformed(editor, c, DataManager.getInstance().getDataContext(editor.getContentComponent()))
        } }), "Typing", null)
    } }), ModalityState.any())
    java.lang.Thread.sleep(__DELAY__)
}
"@@@typed"
