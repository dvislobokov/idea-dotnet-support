// Types the character __CHAR__ in the selected editor after the first __AFTER__, the way the keyboard does (typed handlers run).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.editor.actionSystem.TypedAction)
importClass(com.intellij.ide.DataManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const holder = new java.util.concurrent.atomic.AtomicReference("typed")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    const at = String(editor.getDocument().getText()).indexOf("__AFTER__")
    if (at < 0) { holder.set("no __AFTER__ in the editor"); return }
    editor.getCaretModel().moveToOffset(at + "__AFTER__".length)
    TypedAction.getInstance().actionPerformed(editor, "__CHAR__".charAt(0), DataManager.getInstance().getDataContext(editor.getContentComponent()))
} }))
holder.get()
