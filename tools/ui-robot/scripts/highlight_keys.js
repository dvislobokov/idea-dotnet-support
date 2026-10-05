// The colors of identifiers on screen (CSharpColors, CSHARP_PSI_MIGRATION.md, task A4): opens __FILE__ (forward slashes) and prints, for the
// lines __LINE__..__END__ (from 1; __END__ empty = __LINE__ alone), every range with a key of the palette: `line: text -> KEY (where)`, where
// is `daemon` (annotators: HighlightInfo.forcedTextAttributesKey or the key of its type) or `markup` (range highlighters of the editor and the
// document: the semantic tokens of the language server may land there; a range and key printed from the daemon is not repeated) or `lexer`
// (the editor's highlighter: escapes, format of holes since 0.1.71). The plain lexer colors (keywords, strings, comments, punctuation) are left out. Highlighting needs a moment after an open or a switch of «Colors of
// identifiers» (feature_source.js SEMANTIC_COLORS): run again until the output settles.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx)
importClass(com.intellij.lang.annotation.HighlightSeverity)

var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
var first = java.lang.Integer.parseInt("__LINE__") - 1
var last = "__END__" == "" ? first : java.lang.Integer.parseInt("__END__") - 1
var result = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var text = new java.lang.StringBuilder()
    try {
        var editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vfile), false)
        var document = editor.getDocument()
        var start = document.getLineStartOffset(first)
        var end = document.getLineEndOffset(Math.min(last, document.getLineCount() - 1))
        var rows = new java.util.TreeMap()
        var put = function (from, to, key, where) {
            if (key == null || from < start || to > end || from >= to) return
            var name = String(key.getExternalName())
            if (name.indexOf("CSHARP_") != 0 || /CSHARP_(KEYWORD|STRING|NUMBER|LINE_COMMENT|BLOCK_COMMENT|DOC_COMMENT|PREPROCESSOR|BRACES|PARENTHESES|BRACKETS|OPERATOR|DOT|COMMA|SEMICOLON|BAD_CHARACTER)$/.test(name)) return
            var line = document.getLineNumber(from) + 1
            var row = line + ": " + document.getText(new com.intellij.openapi.util.TextRange(from, to)) + " -> " + name + " (" + where + ")"
            var id = java.lang.String.format("%08d %08d %s", new java.lang.Integer(from), new java.lang.Integer(to), name)
            if (!rows.containsKey(id)) rows.put(id, row)
        }
        DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.INFORMATION, start, end, function (info) {
            var key = info.forcedTextAttributesKey != null ? info.forcedTextAttributesKey : info.type.getAttributesKey()
            put(info.startOffset, info.endOffset, key, "daemon")
            return true
        })
        var tokens = editor.getHighlighter().createIterator(start)
        while (!tokens.atEnd() && tokens.getStart() < end) {
            var keys = tokens.getTextAttributesKeys()
            for (var k = 0; k < keys.length; k++) put(tokens.getStart(), tokens.getEnd(), keys[k], "lexer")
            tokens.advance()
        }
        var models = [editor.getMarkupModel(), com.intellij.openapi.editor.impl.DocumentMarkupModel.forDocument(document, project, false)]
        for (var m = 0; m < models.length; m++) {
            if (models[m] == null) continue
            var highlighters = models[m].getAllHighlighters()
            for (var h = 0; h < highlighters.length; h++) {
                var highlighter = highlighters[h]
                if (highlighter.getTextAttributesKey() != null) put(highlighter.getStartOffset(), highlighter.getEndOffset(), highlighter.getTextAttributesKey(), "markup")
            }
        }
        text.append("## " + vfile.getName() + " lines " + (first + 1) + "-" + (last + 1) + ": " + rows.size() + " colored ranges\n")
        var it = rows.values().iterator()
        while (it.hasNext()) text.append(it.next() + "\n")
    } catch (error) {
        text.append("ERROR: " + error + "\n")
    }
    result.set(text.toString())
} }), ModalityState.any())
result.get()
