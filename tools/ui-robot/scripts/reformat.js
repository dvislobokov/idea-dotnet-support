// Reformat Code (Ctrl+Alt+L, action ReformatCode) in __FILE__ (forward slashes): with __AT__ and __END__ the selection goes from the start of
// the line of the first __AT__ to the end of the line of the first __END__ after it; with __END__ empty — the whole file (no selection).
// Prints who formats (NativeCSharpFormatting.engaged: the built-in formatter of CSharpFeature.FORMATTING, else the server / dotnet format /
// CSharpier), the resulting lines of the range (or of the whole file), then undoes (Ctrl+Z) and says whether the text is back. The server and
// `dotnet format` answer asynchronously: the script waits up to 20 s for the text to change. Switch «Formatting» with feature_source.js
// (`FORMATTING`) and compare; scenarios: debug-playground/Console/Editor/Formatting.cs (markers `TYPE:format-*`).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.command.undo.UndoManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.ide.DataManager)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var nativeFormatting = loader.loadClass("io.github.dotnetsupport.lang.NativeCSharpFormatting")
var out = new java.lang.StringBuilder()
var app = ApplicationManager.getApplication()
var state = { original: null, editor: null, file: null, first: 0, last: -1, linesAfter: 0 }
function edt(body) { app.invokeAndWait(new java.lang.Runnable({ run: body }), ModalityState.any()) }

edt(function () {
    var file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    var editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0), true)
    var document = editor.getDocument()
    var original = String(document.getText())
    state.original = original; state.editor = editor; state.file = file
    var psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
    var engaged = nativeFormatting.getMethod("engaged", loader.loadClass("com.intellij.psi.PsiFile")).invoke(nativeFormatting.getField("INSTANCE").get(null), psi)
    out.append("formatter: " + (String(engaged) == "true" ? "built-in (FORMATTING native)" : "server / dotnet format / CSharpier") + "\n")
    var end = "__END__"
    editor.getSelectionModel().removeSelection()
    if (end.length > 0) {
        var at = original.indexOf("__AT__")
        var until = at < 0 ? -1 : original.indexOf(end, at)
        if (at < 0 || until < 0) { out.append("__AT__ or __END__ not found\n"); state.original = null; return }
        state.first = document.getLineNumber(at)
        state.last = document.getLineNumber(until + end.length)
        state.linesAfter = document.getLineCount() - state.last
        editor.getSelectionModel().setSelection(document.getLineStartOffset(state.first), document.getLineEndOffset(state.last))
    }
    editor.getCaretModel().moveToOffset(end.length > 0 ? document.getLineStartOffset(state.first) : 0)
    ActionUtil.invokeAction(ActionManager.getInstance().getAction("ReformatCode"), DataManager.getInstance().getDataContext(editor.getContentComponent()), "unknown", null, null)
})

if (state.original != null) {
    // the server and dotnet format answer later; the built-in formatter has answered already
    var waited = 0
    while (waited < 20000 && String(state.editor.getDocument().getText()) == state.original) { java.lang.Thread.sleep(250); waited += 250 }
    java.lang.Thread.sleep(300)
    edt(function () {
        var document = state.editor.getDocument()
        var text = String(document.getText())
        if (text == state.original) out.append("(nothing changed)\n")
        var lines = text.split("\n")
        var from = state.last < 0 ? 0 : state.first
        // the selection keeps its lines before it; its last line is where as many lines are left after it as before
        var to = state.last < 0 ? lines.length - 1 : lines.length - state.linesAfter
        for (var i = from; i <= to && i < lines.length; i++) out.append((i + 1) + ": " + lines[i].replace(/\r/g, "") + "\n")
        var fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(state.file)
        var undo = UndoManager.getInstance(project)
        var steps = 4
        while (steps-- > 0 && undo.isUndoAvailable(fileEditor) && String(document.getText()) != state.original) undo.undo(fileEditor)
        out.append(String(document.getText()) == state.original ? "undone\n" : "NOT undone: the file differs, Ctrl+Z by hand\n")
    })
}
out.toString()
