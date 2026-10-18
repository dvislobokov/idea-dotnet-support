// The highlights of __FILE__ (forward slashes) that force a color key (annotators' textAttributes: RAZOR_COMPONENT_ELEMENT, …), from the
// markup model of its open editor: `line:column text -> KEY (foreground of the scheme)`, to see what an annotator colored and how it looks.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.codeInsight.daemon.impl.HighlightInfo)
importClass(com.intellij.openapi.editor.colors.EditorColorsManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder()
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    var editors = FileEditorManager.getInstance(project).openFile(file, true)
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var document = editor.getDocument()
    var scheme = EditorColorsManager.getInstance().getGlobalScheme()
    var highlighters = editor.getMarkupModel().getAllHighlighters()
    var docModel = com.intellij.openapi.editor.impl.DocumentMarkupModel.forDocument(document, project, true).getAllHighlighters()
    var all = [highlighters, docModel]
    for (var a = 0; a < all.length; a++) {
        for (var i = 0; i < all[a].length; i++) {
            var h = all[a][i]
            var info = HighlightInfo.fromRangeHighlighter(h)
            if (info == null || info.forcedTextAttributesKey == null) continue
            var key = info.forcedTextAttributesKey
            var line = document.getLineNumber(h.getStartOffset())
            var column = h.getStartOffset() - document.getLineStartOffset(line)
            var attributes = scheme.getAttributes(key)
            out.append((line + 1) + ":" + (column + 1) + " " + document.getText(new com.intellij.openapi.util.TextRange(h.getStartOffset(), h.getEndOffset())) +
                " -> " + key.getExternalName() + " (" + (attributes == null ? "none" : attributes.getForegroundColor()) + ")\n")
        }
    }
} }), ModalityState.any())
out.toString()
