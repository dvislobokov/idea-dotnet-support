// Opens __FILE__ (forward slashes), puts the caret inside the first __AT__ and performs __ACTION__ (TypeHierarchy, CallHierarchy,
// GotoSuperMethod...) with the data context of the editor; after __WAIT__ ms dumps the tree of the Hierarchy tool window, or, for
// a navigation, where the caret is now. For the check of the hierarchy views of the language server.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.openapi.wm.ToolWindowManager)
importClass(com.intellij.ide.DataManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const report = new java.lang.StringBuilder()
let editor = null
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, 0, 0), true)
    const at = String(editor.getDocument().getText()).indexOf("__AT__")
    if (at < 0) { report.append("no __AT__ in the file"); return }
    editor.getCaretModel().moveToOffset(at + 1)
    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
    editor.getContentComponent().requestFocusInWindow()
    ActionUtil.invokeAction(ActionManager.getInstance().getAction("__ACTION__"), DataManager.getInstance().getDataContext(editor.getContentComponent()), "unknown", null, null)
} }))
java.lang.Thread.sleep(__WAIT__)
function dump(tree) {
    const model = tree.getModel()
    let text = ""
    function walk(node, depth) {
        for (let d = 0; d < depth; d++) text += "  "
        let label = String(node) + " <" + node.getClass().getSimpleName() + ">"
        if (node.getUserObject) {
            const descriptor = node.getUserObject()
            if (descriptor != null && descriptor.getHighlightedText) label = String(descriptor.getHighlightedText().getText()) + " <" + descriptor.getClass().getSimpleName() + ">"
            else if (descriptor != null) label = String(descriptor) + " <" + descriptor.getClass().getSimpleName() + ">"
        }
        text += label + "\n"
        const count = model.getChildCount(node)
        for (let i = 0; i < count; i++) walk(model.getChild(node, i), depth + 1)
    }
    walk(model.getRoot(), 0)
    return text
}
function trees(component, found) {
    if (component instanceof javax.swing.JTree) found.add(component)
    if (component instanceof java.awt.Container) { const children = component.getComponents(); for (let i = 0; i < children.length; i++) trees(children[i], found) }
    return found
}
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const current = FileEditorManager.getInstance(project).getSelectedTextEditor()
    const file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(current.getDocument())
    report.append("caret now: " + file.getName() + ":" + (current.getCaretModel().getLogicalPosition().line + 1) + "\n")
    const window = ToolWindowManager.getInstance(project).getToolWindow("Hierarchy")
    if (window == null) { report.append("no Hierarchy tool window\n"); return }
    report.append("Hierarchy visible: " + window.isVisible() + ", contents: " + window.getContentManager().getContentCount() + "\n")
    const content = window.getContentManager().getSelectedContent()
    if (content == null) return
    report.append("content: " + content.getDisplayName() + "\n")
    const found = trees(content.getComponent(), new java.util.ArrayList())
    for (let i = 0; i < found.size(); i++) {
        const tree = found.get(i)
        for (let row = 0; row < tree.getRowCount(); row++) tree.expandRow(row)
    }
} }))
// the children of a hierarchy node are computed in the background after the expansion
java.lang.Thread.sleep(4000)
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const window = ToolWindowManager.getInstance(project).getToolWindow("Hierarchy")
    const content = window.getContentManager().getSelectedContent()
    const found = trees(content.getComponent(), new java.util.ArrayList())
    for (let i = 0; i < found.size(); i++) {
        const tree = found.get(i)
        for (let row = 0; row < tree.getRowCount(); row++) tree.expandRow(row)
        report.append("tree " + i + " showing=" + tree.isShowing() + " (" + tree.getRowCount() + " rows):\n" + dump(tree))
    }
} }))
report.toString()
