// Opens Settings on the page __CONFIGURABLE__ (a class name) and lists the components of the page whose preferred width exceeds
// __LIMIT__ pixels, after the dialog is resized to __WIDTH__ pixels: who makes a settings page wider than its dialog (seen live: a horizontal scroll bar under Tools | .NET).
// The dialog is modal: it is opened with invokeLater and left open; close it with `click "//div[@class='MyDialog']//div[@text='Cancel']"`.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.options.ShowSettingsUtil)
importClass(com.intellij.openapi.extensions.ExtensionPointName)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
let loader = null
const providers = ExtensionPointName.create("com.intellij.platform.lsp.integrationProvider").getExtensionList()
for (let i = 0; i < providers.size(); i++) if (providers.get(i).getClass().getName().indexOf("Roslyn") >= 0) loader = providers.get(i).getClass().getClassLoader()
const configurable = loader.loadClass("__CONFIGURABLE__")
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { ShowSettingsUtil.getInstance().showSettingsDialog(project, configurable) } }))
java.lang.Thread.sleep(__WAIT__)
const report = new java.lang.StringBuilder()
function describe(component) {
    let text = ""
    if (component.getText) try { text = String(component.getText()) } catch (e) {}
    if (text.length > 60) text = text.substring(0, 60) + "..."
    return component.getClass().getSimpleName() + " pref=" + component.getPreferredSize().width + " actual=" + component.getWidth() + " [" + text.replace(/\n/g, " ") + "]"
}
function walk(component, depth, out) {
    if (component.getPreferredSize().width > __LIMIT__ && !(component instanceof javax.swing.JScrollPane) && !(component instanceof javax.swing.JViewport)) {
        let line = ""
        for (let d = 0; d < depth; d++) line += " "
        out.append(line + describe(component) + "\n")
    }
    if (component instanceof java.awt.Container) { const children = component.getComponents(); for (let i = 0; i < children.length; i++) walk(children[i], depth + 1, out) }
}
// Rhino: `const` inside a loop body and `continue` do not mix (the README of the robot), hence `var` and an `if` around the body
var windows = java.awt.Window.getWindows()
var i = 0
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    for (i = 0; i < windows.length; i++) {
        var w = windows[i]
        if (w.isShowing() && String(w.getClass().getName()).indexOf("MyDialog") >= 0) {
            // the size of the user's dialog: a wide screen hides the problem
            w.setSize(__WIDTH__, 760)
            w.validate()
            report.append("dialog " + w.getTitle() + " " + w.getWidth() + "x" + w.getHeight() + "\n")
            walk(w.getContentPane(), 0, report)
        }
    }
} }), ModalityState.any()) // the dialog is modal: without `any` the runnable waits for it to close, and so does the robot
report.toString()
