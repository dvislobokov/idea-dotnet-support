// The text of the consoles of the run / debug tabs that are alive, newest last (test consoles included).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.execution.ui.RunContentManager)
importClass(com.intellij.util.ui.UIUtil)
importClass(com.intellij.openapi.editor.impl.EditorComponentImpl)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const descriptors = RunContentManager.getInstance(project).getAllDescriptors()
let text = ""
for (let i = 0; i < descriptors.size(); i++) {
    const d = descriptors.get(i)
    text += "=== " + d.getDisplayName() + "\n"
    const editors = UIUtil.findComponentsOfType(d.getComponent(), EditorComponentImpl)
    for (let e = 0; e < editors.size(); e++) { const t = String(editors.get(e).getEditor().getDocument().getText()); text += t.substring(Math.max(0, t.length - 1500)) + "\n" }
}
text
