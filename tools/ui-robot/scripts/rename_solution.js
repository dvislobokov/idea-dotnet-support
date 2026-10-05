// Rename of a type or member across the solution (task C4b): opens __FILE__ (forward slashes), the caret on the first character of the first
// __AT__, runs the rename handler Shift+F6 finds with the new name __NEW__ given in the data context (as the platform's Rename dialog passes
// it: no inline template, no question about the hierarchy — that one is answered __HIERARCHY__ = `true` / `false`), then prints every line
// of the C# files under __ROOT__ whose text changed (`file:line: text`) and the files renamed. No undo (an undo over several files asks the
// user): the changed documents are reloaded from the disk afterwards, so the copy must not be saved in between.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.vfs.VfsUtilCore)
importClass(com.intellij.openapi.actionSystem.impl.SimpleDataContext)
importClass(com.intellij.refactoring.rename.RenameHandlerRegistry)
importClass(com.intellij.refactoring.rename.PsiElementRenameHandler)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.ide.DataManager)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var rename = loader.loadClass("io.github.dotnetsupport.lang.NativeCSharpSolutionRename").getField("INSTANCE").get(null)
rename.answerHierarchyForTests(java.lang.Boolean.valueOf("__HIERARCHY__" == "true"))
var app = ApplicationManager.getApplication()
var out = new java.lang.StringBuilder()
var root = LocalFileSystem.getInstance().refreshAndFindFileByPath("__ROOT__")
// the text of every C# file before
var before = new java.util.LinkedHashMap()
VfsUtilCore.iterateChildrenRecursively(root, null, new com.intellij.openapi.roots.ContentIterator({ processFile: function (f) {
    if (!f.isDirectory() && f.getName().endsWith(".cs") && f.getPath().indexOf("/obj/") < 0 && f.getPath().indexOf("/bin/") < 0) {
        before.put(f, String(FileDocumentManager.getInstance().getDocument(f).getText()))
    }
    return true
} }))
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    var editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0), true)
    var at = String(editor.getDocument().getText()).indexOf("__AT__")
    editor.getCaretModel().moveToOffset(at)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    var context = SimpleDataContext.builder().setParent(DataManager.getInstance().getDataContext(editor.getContentComponent()))
        .add(PsiElementRenameHandler.DEFAULT_NAME, "__NEW__").build()
    var handler = RenameHandlerRegistry.getInstance().getRenameHandler(context)
    out.append("handler: " + (handler == null ? "none" : handler.getClass().getSimpleName()) + "\n")
    var started = java.lang.System.nanoTime()
    if (handler != null) handler.invoke(project, editor, PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()), context)
    out.append("took: " + Math.round((java.lang.System.nanoTime() - started) / 1e6) + " ms\n")
} }), ModalityState.nonModal())
java.lang.Thread.sleep(1500)
rename.answerHierarchyForTests(null)
var changed = []
var it = before.entrySet().iterator()
while (it.hasNext()) {
    var e = it.next()
    var f = e.getKey()
    var old = String(e.getValue()).split("\n")
    var document = FileDocumentManager.getInstance().getDocument(f)
    var now = String(document.getText()).split("\n")
    var rel = String(f.getPath()).substring("__ROOT__".length + 1)
    if (!f.isValid()) { out.append("gone: " + rel + "\n"); continue }
    var any = false
    for (var i = 0; i < Math.max(old.length, now.length); i++) {
        if (old[i] !== now[i]) { out.append(rel + ":" + (i + 1) + ": " + (now[i] || "").trim() + "\n"); any = true }
    }
    if (any) changed.push(document)
}
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    for (var c = 0; c < changed.length; c++) FileDocumentManager.getInstance().reloadFromDisk(changed[c])
} }), ModalityState.nonModal())
out.append("reloaded: " + changed.length + "\n")
out.toString()
