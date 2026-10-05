// Go to Class / Go to Symbol of the plugin alone (CSharpGotoClassContributor / CSharpGotoSymbolContributor, the stub indexes of the built-in
// tree or CSharpDeclarationIndex of the heuristic one): __KIND__ = `class` | `symbol`, __NAMES__ = names joined by `,`. Prints, per name, the
// rows as the popup shows them (presentable text, location) with file:line. Waits for smart mode. Files covered by a ready language server
// are skipped by the contributors, so turn the server off first (`server_enabled.js`) to see the plugin's own answer for the whole project.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.project.DumbService)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.navigation.ChooseByNameContributor)
importClass(com.intellij.util.indexing.FindSymbolParameters)
importClass(com.intellij.psi.PsiDocumentManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder()
DumbService.getInstance(project).waitForSmartMode()
var ep = "__KIND__" == "class" ? ChooseByNameContributor.CLASS_EP_NAME : ChooseByNameContributor.SYMBOL_EP_NAME
var contributor = null
var all = ep.getExtensionList()
for (var i = 0; i < all.size(); i++) if (String(all.get(i).getClass().getName()).indexOf("io.github.dotnetsupport.lang.CSharpGoto") == 0) contributor = all.get(i)

// a function per name: Rhino's `const` in a loop body keeps its first value inside closures
function rowsOf(name) {
    return ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: function () {
        var found = new java.util.ArrayList()
        contributor.processElementsWithName(name, new com.intellij.util.Processor({ process: function (item) { found.add(item); return true } }),
            FindSymbolParameters.simple(project, false))
        var lines = new java.util.ArrayList()
        for (var k = 0; k < found.size(); k++) {
            var item = found.get(k)
            var p = item.getPresentation()
            var where = ""
            var file = item.getContainingFile ? item.getContainingFile() : null
            if (file != null) {
                var doc = PsiDocumentManager.getInstance(project).getDocument(file)
                where = file.getName() + (doc != null ? ":" + (doc.getLineNumber(item.getTextOffset()) + 1) : "")
            }
            lines.add("  " + (p != null ? p.getPresentableText() + "  (" + p.getLocationString() + ")" : item) + "  " + where)
        }
        return lines
    } }))
}

var names = "__NAMES__".split(",")
for (var n = 0; n < names.length; n++) {
    var rows = rowsOf(names[n])
    out.append(names[n] + ":\n")
    if (rows.size() == 0) out.append("  (none)\n")
    for (var r = 0; r < rows.size(); r++) out.append(rows.get(r) + "\n")
}
out.toString()
