// Which rename handlers the platform finds with the caret inside the first __AT__ of the selected editor: tells who Shift+F6 goes to.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.refactoring.rename.RenameHandlerRegistry)
importClass(com.intellij.ide.DataManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const holder = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    const at = String(editor.getDocument().getText()).indexOf("__AT__")
    if (at < 0) { holder.set("no __AT__ in the editor"); return }
    editor.getCaretModel().moveToOffset(at + 1)
    const handlers = RenameHandlerRegistry.getInstance().getRenameHandlers(DataManager.getInstance().getDataContext(editor.getContentComponent()))
    let text = "handlers: " + handlers.size() + "\n"
    for (let i = 0; i < handlers.size(); i++) text += "  " + handlers.get(i).getClass().getName() + "\n"
    holder.set(text)
} }))
holder.get()
