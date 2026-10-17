// The components and tag helpers of a Razor file as the plugin sees them (RAZOR_PLAN.md, R5e): __FILE__ = a `.razor` / `.cshtml` path. Prints the
// project file, how many descriptors RazorTagHelperService has for it (null = not discovered yet), the descriptors in scope of the file's C#
// document (its TagHelperDocumentContext) and the components among them. Classes of the content module come through the loader of its contributor.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.psi.PsiManager)
importClass(com.intellij.codeInsight.completion.CompletionContributor)
importClass(com.intellij.lang.html.HTMLLanguage)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
var contributors = CompletionContributor.forLanguage(HTMLLanguage.INSTANCE)
var loader = null
var names = []
for (var i = 0; i < contributors.size(); i++) {
    var name = String(contributors.get(i).getClass().getName())
    names.push(name.substring(name.lastIndexOf('.') + 1))
    if (name.indexOf("RazorTagHelperCompletionContributor") >= 0) loader = contributors.get(i).getClass().getClassLoader()
}
var text = "file: " + vfile + "\nHTML contributors: " + names.join(", ") + "\n"
if (loader == null) { text += "no RazorTagHelperCompletionContributor\n" } else {
    var result = new java.util.concurrent.atomic.AtomicReference("")
    ApplicationManager.getApplication().runReadAction(new java.lang.Runnable({ run: function () {
        var out = ""
        var psi = PsiManager.getInstance(project).findFile(vfile)
        out += "view base: " + psi.getViewProvider().getBaseLanguage() + ", languages: " + psi.getViewProvider().getLanguages() + "\n"
        var editorClass = loader.loadClass("io.github.dotnetsupport.frameworks.razor.RazorTagHelperEditor")
        var editor = editorClass.getField("INSTANCE").get(null)
        var razor = editor.razorFile(psi)
        out += "razor file: " + razor + "\n"

        var projects2 = loader.loadClass("io.github.dotnetsupport.msbuild.DotNetProjects"); var projectFile = projects2.getField("INSTANCE").get(null).findOwningProject(vfile)
        out += "project file: " + projectFile + "\n"
        var serviceClass = loader.loadClass("io.github.dotnetsupport.frameworks.razor.RazorTagHelperService")
        var service = project.getService(serviceClass)
        if (projectFile != null) {
            var found = service.tagHelpers(projectFile)
            out += "service: " + (found == null ? "null" : found.size()) + "\n"
        }
        if (razor != null) {
            var context = editor.context(razor)
            if (context == null) { out += "context: null\n" } else {
                var helpers = context.getTagHelpers()
                out += "context: " + helpers.size() + " in scope, prefix " + context.getPrefix() + "\n"
                var components = []
                for (var k = 0; k < helpers.size(); k++) {
                    var d = helpers.get(k)
                    if (String(d.getKind()) == "Component") components.push(d.getName() + (d.isFullyQualifiedNameMatch() ? " (fq)" : ""))
                }
                var kinds = {}
                for (var k = 0; k < helpers.size(); k++) { var kind = String(helpers.get(k).getKind()); kinds[kind] = (kinds[kind] || 0) + 1 }
                var kindText = []
                for (var kind in kinds) kindText.push(kind + " " + kinds[kind])
                out += "kinds: " + kindText.join(", ") + "\n"
                var binds = []
                for (var k = 0; k < helpers.size(); k++) {
                    var d = helpers.get(k)
                    var kind = String(d.getKind())
                    if (kind != "Bind" && kind != "EventHandler") continue
                    var rules = d.getTagMatchingRules(), attributes = d.getBoundAttributes(), names = []
                    for (var a = 0; a < attributes.size(); a++) names.push(attributes.get(a).getName())
                    if (binds.length < 12) binds.push(kind + " " + d.getName() + " tag " + rules.get(0).getTagName() + " [" + names.join(" ") + "]")
                }
                out += "bind / event: " + binds.join("; ") + "\n"
                out += "components: " + components.slice(0, 20).join(", ") + (components.length > 20 ? " ... " + components.length : "") + "\n"
            }
        }
        result.set(out)
    } }))
    text += result.get()
}
text
