// The usages lenses of __FILE__ (forward slashes) as the plugin computes them: `line: text` of each, in the order of the text.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.psi.PsiManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var lenses = loader.loadClass("io.github.dotnetsupport.lang.NativeCSharpCodeLens").getField("INSTANCE").get(null)
var vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
ReadAction.compute(function () {
    var file = PsiManager.getInstance(project).findFile(vfile)
    var document = file.getViewProvider().getDocument()
    var list = lenses.referenceLenses$default(lenses, file, null, 2, null)
    var text = "lenses: " + list.size() + "\n"
    for (var i = 0; i < list.size(); i++) {
        var lens = list.get(i)
        text += (document.getLineNumber(lens.getRange().getStartOffset()) + 1) + ": " + lens.getText() + "\n"
    }
    return text
})
