// Find Usages of the built-in search (ReferencesSearch over CSharpSolutionSearch, task C4b) against `textDocument/references` of the server
// for many names of the solution: __ROOT__ — the folder of the solution (forward slashes); __CASES__ — `path:line:name` or `path:line:name#n`
// separated by `;` (path under the root, line from 1, the n-th whole-word occurrence on the line, the first by default). Per case: the counts
// of both, then the places only one of them has (`file:line:column`). The declarations are left out of both. Waits __WAIT__ ms for the server.
// __MODE__ = `references` (Find Usages) or `implementation` (Ctrl+Alt+B: DefinitionsScopedSearch against `textDocument/implementation`, the
// places of the names of the declarations found).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.psi.PsiManager)
importClass(com.intellij.psi.search.GlobalSearchScope)
importClass(com.intellij.psi.search.searches.ReferencesSearch)
importClass(com.intellij.openapi.extensions.ExtensionPointName)
importClass(com.intellij.platform.lsp.api.LspClientManager)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var findUsages = loader.loadClass("io.github.dotnetsupport.lang.NativeCSharpFindUsages").getField("INSTANCE").get(null)
var providers = ExtensionPointName.create("com.intellij.platform.lsp.integrationProvider").getExtensionList()
var client = null
for (var p = 0; p < providers.size(); p++) {
    if (providers.get(p).getClass().getName().indexOf("Roslyn") < 0) continue
    var clients = LspClientManager.getInstance(project).getClients(providers.get(p).getClass()).toArray()
    if (clients.length > 0) client = clients[0]
}
var out = new java.lang.StringBuilder()
var root = "__ROOT__"

function place(file, document, offset) {
    var line = document.getLineNumber(offset)
    return file.getPath().substring(root.length + 1) + ":" + (line + 1) + ":" + (offset - document.getLineStartOffset(line) + 1)
}

// `@file`: the cases in a file (a long list does not fit a command line)
var casesText = "__CASES__"
if (casesText.charAt(0) == "@") casesText = String(new java.lang.String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(casesText.substring(1))), "UTF-8")).trim()
var cases = casesText.split(";")
var totalSame = 0, totalNative = 0, totalServer = 0
for (var c = 0; c < cases.length; c++) {
    var spec = cases[c].trim()
    if (spec.length == 0) continue
    var parts = spec.split(":")
    var path = parts[0], lineNo = parseInt(parts[1]), name = parts[2], nth = 1
    if (name.indexOf("#") > 0) { nth = parseInt(name.substring(name.indexOf("#") + 1)); name = name.substring(0, name.indexOf("#")) }
    var file = LocalFileSystem.getInstance().refreshAndFindFileByPath(root + "/" + path)
    var document = FileDocumentManager.getInstance().getDocument(file)
    var lineText = String(document.getText().substring(document.getLineStartOffset(lineNo - 1), document.getLineEndOffset(lineNo - 1)))
    var re = new RegExp("\\b" + name + "\\b", "g")
    var m = null, k = 0, column = -1
    while ((m = re.exec(lineText)) != null) { k++; if (k == nth) { column = m.index; break } }
    if (column < 0) { out.append(spec + ": not on the line\n"); continue }
    var offset = document.getLineStartOffset(lineNo - 1) + column
    var started = java.lang.System.nanoTime()
    var nativePlaces = ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: function () {
        var psi = PsiManager.getInstance(project).findFile(file)
        var target = findUsages.targetElement(psi.findElementAt(offset))
        var set = new java.util.TreeSet()
        if (target == null) return set
        if ("__MODE__" == "implementation") {
            // Ctrl+Alt+B lists the element itself unless it is abstract (the evaluator says), as the server does
            var evaluator = loader.loadClass("io.github.dotnetsupport.lang.CSharpTargetElementEvaluator").getDeclaredConstructor().newInstance()
            if (evaluator.includeSelfInGotoImplementation(target)) {
                var self = target.getContainingFile().getVirtualFile()
                set.add(place(self, FileDocumentManager.getInstance().getDocument(self), target.getTextOffset()))
            }
            var definitions = com.intellij.psi.search.searches.DefinitionsScopedSearch.search(target).findAll().iterator()
            while (definitions.hasNext()) {
                var declaration = definitions.next()
                var vfd = declaration.getContainingFile().getVirtualFile()
                set.add(place(vfd, FileDocumentManager.getInstance().getDocument(vfd), declaration.getTextOffset()))
            }
            return set
        }
        var found = ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).findAll().iterator()
        while (found.hasNext()) {
            var element = found.next().getElement()
            var vf = element.getContainingFile().getVirtualFile()
            set.add(place(vf, FileDocumentManager.getInstance().getDocument(vf), element.getTextRange().getStartOffset()))
        }
        return set
    } }))
    var nativeMs = Math.round((java.lang.System.nanoTime() - started) / 1e6)
    var serverPlaces = new java.util.TreeSet()
    if (client != null) {
        var position = new org.eclipse.lsp4j.Position(lineNo - 1, column)
        var params = "__MODE__" == "implementation" ? new org.eclipse.lsp4j.ImplementationParams(client.getDocumentIdentifier(file), position)
            : new org.eclipse.lsp4j.ReferenceParams(client.getDocumentIdentifier(file), position, new org.eclipse.lsp4j.ReferenceContext(false))
        // a Kotlin lambda through a proxy: Rhino does not see the package `kotlin` of the platform
        var functionClass = loader.loadClass("kotlin.jvm.functions.Function1")
        var request = java.lang.reflect.Proxy.newProxyInstance(loader, [functionClass], new java.lang.reflect.InvocationHandler({
            invoke: function (proxy, method, args) {
                if (method.getName() == "invoke") return "__MODE__" == "implementation" ? args[0].getTextDocumentService().implementation(params) : args[0].getTextDocumentService().references(params)
                if (method.getName() == "hashCode") return java.lang.Integer.valueOf(1)
                if (method.getName() == "equals") return java.lang.Boolean.FALSE
                return "references"
            }
        }))
        var answer = client.sendRequestSync(__WAIT__, request)
        // `implementation` answers Either<List<Location>, List<LocationLink>>
        if (answer != null && answer.getClass().getName().indexOf("Either") >= 0) answer = answer.isLeft() ? answer.getLeft() : answer.getRight()
        if (answer != null) {
            var it = answer.iterator()
            while (it.hasNext()) {
                var location = it.next()
                var isLink = location.getClass().getName().indexOf("LocationLink") >= 0
                var uri = isLink ? location.getTargetUri() : location.getUri()
                var vf = client.getDescriptor().findFileByUri(uri)
                if (vf == null) { serverPlaces.add("(outside) " + uri); continue }
                var doc = FileDocumentManager.getInstance().getDocument(vf)
                var start = isLink ? location.getTargetSelectionRange().getStart() : location.getRange().getStart()
                serverPlaces.add(place(vf, doc, doc.getLineStartOffset(start.getLine()) + start.getCharacter()))
            }
        }
    }
    var same = 0
    var onlyNative = [], onlyServer = []
    var n = nativePlaces.iterator()
    while (n.hasNext()) { var x = n.next(); if (serverPlaces.contains(x)) same++; else onlyNative.push(x) }
    var s = serverPlaces.iterator()
    while (s.hasNext()) { var y = s.next(); if (!nativePlaces.contains(y)) onlyServer.push(y) }
    totalSame += same; totalNative += nativePlaces.size(); totalServer += serverPlaces.size()
    out.append(spec + ": native " + nativePlaces.size() + " (" + nativeMs + " ms), server " + serverPlaces.size() + ", same " + same + (onlyNative.length + onlyServer.length == 0 ? "  OK" : "") + "\n")
    for (var i = 0; i < onlyNative.length; i++) out.append("    only native: " + onlyNative[i] + "\n")
    for (var j = 0; j < onlyServer.length; j++) out.append("    only server: " + onlyServer[j] + "\n")
}
out.append("total: native " + totalNative + ", server " + totalServer + ", same " + totalSame + "\n")
out.toString()
