// Find Usages (Alt+F7) with the caret on the last character of the first __AT__ of __FILE__ (forward slashes), then the tree of the Usages view as text, one
// row per node, indented by depth. __GROUPS__ — the toggles of the view to set before the tree is printed, as `name=true|false` joined by `,`:
// UsageType, Module, FileStructure, DirectoryStructure, Package, Merge (Merge Usages on the Same Line). They are set the way the toolbar of
// the view sets them: UsageViewSettings, then RULES_CHANGED. __WAIT__ — ms to wait for the search. Not on the EDT: polls with sleeps.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.ide.DataManager)
importClass(com.intellij.usages.UsageViewManager)
importClass(com.intellij.usages.UsageViewSettings)
importClass(com.intellij.usages.rules.UsageFilteringRuleProvider)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const out = new java.lang.StringBuilder()
const app = ApplicationManager.getApplication()
// the usage view manager is for the EDT only
function selectedView() {
    var found = null
    app.invokeAndWait(new java.lang.Runnable({ run: function () { found = UsageViewManager.getInstance(project).getSelectedUsageView() } }), ModalityState.any())
    return found
}
const before = selectedView()
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    const file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    const editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0), true)
    const at = String(editor.getDocument().getText()).indexOf("__AT__")
    // on the last character: __AT__ may start with context (`int Counter`) to pick the right occurrence
    editor.getCaretModel().moveToOffset(at + "__AT__".length - 1)
    ActionUtil.invokeAction(ActionManager.getInstance().getAction("FindUsages"), DataManager.getInstance().getDataContext(editor.getContentComponent()), "unknown", null, null)
} }), ModalityState.any())
var view = null
var waited = 0
while (waited < __WAIT__) {
    java.lang.Thread.sleep(500)
    waited += 500
    view = selectedView()
    if (view != null && view !== before && !view.isSearchInProgress() && view.getUsagesCount() > 0) break
}
if (view == null || view === before) {
    out.append("no new usage view after " + waited + " ms")
} else {
    const settings = UsageViewSettings.getInstance()
    const groups = "__GROUPS__".split(",")
    var g = 0
    for (g = 0; g < groups.length; g++) {
        var pair = groups[g].split("=")
        if (pair.length != 2) continue
        var on = pair[1] == "true"
        if (pair[0] == "UsageType") settings.setGroupByUsageType(on)
        if (pair[0] == "Module") settings.setGroupByModule(on)
        if (pair[0] == "FileStructure") settings.setGroupByFileStructure(on)
        if (pair[0] == "DirectoryStructure") settings.setGroupByDirectoryStructure(on)
        if (pair[0] == "Package") settings.setGroupByPackage(on)
        if (pair[0] == "Merge") settings.setFilterDuplicatedLine(on)
    }
    app.invokeAndWait(new java.lang.Runnable({ run: function () { project.getMessageBus().syncPublisher(UsageFilteringRuleProvider.RULES_CHANGED).run() } }), ModalityState.any())
    java.lang.Thread.sleep(1500)
    out.append("usages: " + view.getUsagesCount() + "\n")
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        const tree = view.getTree()
        var row = 0
        for (row = 0; row < tree.getRowCount(); row++) tree.expandRow(row)
        for (row = 0; row < tree.getRowCount(); row++) {
            var path = tree.getPathForRow(row)
            var node = path.getLastPathComponent()
            var depth = 0
            for (depth = 1; depth < path.getPathCount(); depth++) out.append("  ")
            out.append(view.getNodeText(node) + "\n")
        }
    } }), ModalityState.any())
}
out.toString()
