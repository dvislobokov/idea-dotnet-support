// Which tree a C# file has and what the syntax features make of it (CSHARP_PSI_MIGRATION.md, step 7). __SOURCE__ = NATIVE / ROSLYN switches
// "Structure, folding and breadcrumbs" (CSharpFeature.SYNTAX_TREE) as the settings page does and announces it, `keep` leaves it. Then
// opens __FILE__ (forward slashes) and prints: the tree (native = CompilationUnit under the file), the Structure view outline, breadcrumbs
// at the first code character of each line listed in __LINES__ (comma-separated, from 1), fold regions, errors and run markers in the gutter.
// Highlighting needs a moment after a switch or an open: run the script again with __SOURCE__ = keep to read the settled state.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
importClass(com.intellij.lang.annotation.HighlightSeverity)
importClass(com.intellij.ide.structureView.impl.StructureViewFactoryImpl)
importClass(com.intellij.lang.LanguageStructureViewBuilder)
importClass(com.intellij.ui.breadcrumbs.BreadcrumbsUtil)

var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var source = "__SOURCE__"
var out = new java.lang.StringBuilder()

if (source != "keep") {
    var settingsClass = loader.loadClass("io.github.dotnetsupport.lsp.RoslynLanguageServerSettings")
    var settings = ApplicationManager.getApplication().getService(settingsClass)
    var feature = java.lang.Enum.valueOf(loader.loadClass("io.github.dotnetsupport.lang.CSharpFeature"), "SYNTAX_TREE")
    var featureSource = java.lang.Enum.valueOf(loader.loadClass("io.github.dotnetsupport.lang.CSharpFeatureSource"), source)
    settings.setSource(feature, featureSource)
    var topicField = settingsClass.getDeclaredField("CHANGED")
    topicField.setAccessible(true)
    var topic = topicField.get(null)
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        ApplicationManager.getApplication().getMessageBus().syncPublisher(topic).settingsChanged(false)
    } }), ModalityState.nonModal())   // a write-safe context, as the settings page: the switch reparses at once
    out.append("SYNTAX_TREE -> " + settings.source(feature) + "\n")
}
var trees = loader.loadClass("io.github.dotnetsupport.lang.CSharpSyntaxTrees")
var instance = trees.getField("INSTANCE").get(null)
out.append("nativeTree() = " + trees.getMethod("nativeTree").invoke(instance) + "\n")

var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
var lines = "__LINES__".split(",")
var result = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var text = new java.lang.StringBuilder()
    try {
        var editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vfile), true)
        var document = editor.getDocument()
        var psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
        var unit = psi.getClass().getMethod("getCompilationUnit").invoke(psi)
        text.append("file: " + psi.getClass().getSimpleName() + ", tree: " + (unit != null ? "native (" + unit.getClass().getSimpleName() + ")" : "heuristic") + "\n")

        var builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psi)
        var model = builder.createStructureViewModel(editor)
        var outline = function (element, indent) {
            var children = element.getChildren()
            for (var c = 0; c < children.length; c++) {
                text.append(indent + children[c].getPresentation().getPresentableText() + "\n")
                outline(children[c], indent + "  ")
            }
        }
        text.append("## structure\n")
        outline(model.getRoot(), "  ")
        com.intellij.openapi.util.Disposer.dispose(model)

        var crumbs = BreadcrumbsUtil.getInfoProvider(psi.getLanguage())
        text.append("## breadcrumbs\n")
        for (var l = 0; l < lines.length; l++) {
            if (lines[l] == "") continue
            var line = java.lang.Integer.parseInt(lines[l]) - 1
            var start = document.getLineStartOffset(line)
            var lineText = document.getText(new com.intellij.openapi.util.TextRange(start, document.getLineEndOffset(line)))
            var offset = start + Math.max(0, lineText.search(/\S/))
            var path = new java.util.ArrayList()
            for (var e = psi.findElementAt(offset); e != null && e != psi; e = e.getParent()) if (crumbs.acceptElement(e)) path.add(0, crumbs.getElementInfo(e))
            text.append("  " + (line + 1) + ": " + java.lang.String.join(" > ", path) + "\n")
        }

        var regions = editor.getFoldingModel().getAllFoldRegions()
        text.append("## folding: " + regions.length + " regions\n")
        for (var r = 0; r < regions.length; r++) {
            var first = document.getLineNumber(regions[r].getStartOffset()) + 1
            var last = document.getLineNumber(regions[r].getEndOffset()) + 1
            text.append("  " + first + "-" + last + " " + regions[r].getPlaceholderText() + (regions[r].isExpanded() ? "" : " (collapsed)") + "\n")
        }

        var infos = DaemonCodeAnalyzerImpl.getHighlights(document, HighlightSeverity.ERROR, project)
        text.append("## errors: " + infos.size() + "\n")
        for (var i = 0; i < infos.size() && i < 10; i++) text.append("  " + (document.getLineNumber(infos.get(i).getStartOffset()) + 1) + ": " + infos.get(i).getDescription() + "\n")

        var markers = DaemonCodeAnalyzerImpl.getLineMarkers(document, project)
        text.append("## gutter markers: " + markers.size() + "\n")
        for (var m = 0; m < markers.size(); m++) {
            var marker = markers.get(m)
            var tooltip = marker.getLineMarkerTooltip()
            text.append("  " + (document.getLineNumber(marker.startOffset) + 1) + ": " + (tooltip == null ? "" : String(tooltip).replace(/<[^>]*>/g, "").replace(/\s+/g, " ").substring(0, 80)) + "\n")
        }
    } catch (error) {
        text.append("ERROR: " + error + "\n")
    }
    result.set(text.toString())
} }), ModalityState.any())
out.append(result.get())
out.toString()
