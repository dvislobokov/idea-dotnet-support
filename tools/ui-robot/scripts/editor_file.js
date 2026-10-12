// The file of the selected editor as the user sees it: path, title of the tab, whether it may be edited, the banners above it, the caret.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil)
importClass(com.intellij.openapi.vfs.WritingAccessProvider)
importClass(com.intellij.ui.EditorNotificationPanel)
importClass(com.intellij.util.ui.UIUtil)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const holder = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const manager = FileEditorManager.getInstance(project)
    const file = manager.getSelectedFiles()[0]
    const editor = manager.getSelectedTextEditor()
    let text = "file: " + file.getPath() + "\n"
    text += "tab: " + EditorTabPresentationUtil.getEditorTabTitle(project, file) + "\n"
    text += "writable: " + WritingAccessProvider.isPotentiallyWritable(file, project) + "\n"
    text += "caret line: " + (editor == null ? "-" : editor.getCaretModel().getLogicalPosition().line + 1) + "\n"
    const fileEditor = manager.getSelectedEditor(file)
    const panels = UIUtil.findComponentsOfType(fileEditor.getComponent().getParent().getParent(), EditorNotificationPanel)
    for (let i = 0; i < panels.size(); i++) text += "banner: " + panels.get(i).getText() + "\n"
    holder.set(text)
} }))
holder.get()
