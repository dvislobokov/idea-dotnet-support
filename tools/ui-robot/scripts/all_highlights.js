// The errors and warnings on screen (DIAGNOSTICS, CSHARP_PSI_MIGRATION.md, task A3): opens __FILE__ (forward slashes) and prints every
// highlight of severity WARNING or higher, in the order of the text: `line:column-line:column [SEVERITY] text | description`, where text is the
// highlighted text (`<eol>` for one shown after the end of its line, `<empty>` for a zero-width one) and description the message
// (`CS1002: ; expected` from the plugin's tree or from the language server, which also gives the code). Who reported it is not on the
// highlight: compare a run with feature_source.js DIAGNOSTICS NATIVE and one with ROSLYN. Highlighting needs a moment after an open, an edit
// or a switch: run again until the output settles.
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
var result = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var text = new java.lang.StringBuilder()
    try {
        var editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vfile), false)
        var document = editor.getDocument()
        var rows = new java.util.TreeMap()
        var position = function (offset) {
            var line = document.getLineNumber(offset)
            return (line + 1) + ":" + (offset - document.getLineStartOffset(line) + 1)
        }
        DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.INFORMATION, 0, document.getTextLength(), function (info) {
            // a reflected java.lang.Boolean is always truthy in Rhino: compare its text
            var afterEnd = String(info.isAfterEndOfLine()) == "true"
            var shown = afterEnd ? "<eol>" : info.startOffset == info.endOffset ? "<empty>" : String(document.getText(new com.intellij.openapi.util.TextRange(info.startOffset, info.endOffset)))
            var row = position(info.startOffset) + "-" + position(info.endOffset) + " [" + info.getSeverity().getName() + "] " + shown + " | " + info.getDescription()
            var id = java.lang.String.format("%08d %08d %s", new java.lang.Integer(info.startOffset), new java.lang.Integer(info.endOffset), String(info.getDescription()))
            if (!rows.containsKey(id)) rows.put(id, row)
            return true
        })
        text.append("## " + vfile.getName() + ": " + rows.size() + " errors and warnings\n")
        var it = rows.values().iterator()
        while (it.hasNext()) text.append(it.next() + "\n")
    } catch (error) {
        text.append("ERROR: " + error + "\n")
    }
    result.set(text.toString())
} }), ModalityState.any())
result.get()
