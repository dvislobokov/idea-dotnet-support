// The completion list open now in the selected editor (after real typing: screen.sh type / key ctrl+space): how many items, the first
// __LIMIT__ as `lookup string  [type text]`, and the line of the caret (`|` marks it). "no lookup" when none is shown.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.codeInsight.lookup.LookupElementPresentation)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var result = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var document = editor.getDocument()
    var offset = editor.getCaretModel().getOffset()
    var line = document.getLineNumber(offset)
    var text = String(document.getText())
    var out = "line " + (line + 1) + ": " + text.substring(document.getLineStartOffset(line), offset) + "|" + text.substring(offset, document.getLineEndOffset(line)) + "\n"
    var lookup = LookupManager.getActiveLookup(editor)
    if (lookup == null) { result.set(out + "no lookup"); return }
    var items = lookup.getItems()
    out += "items: " + items.size() + "\n"
    for (var i = 0; i < Math.min(items.size(), __LIMIT__); i++) {
        var p = LookupElementPresentation.renderElement(items.get(i))
        out += "  " + items.get(i).getLookupString() + (p.getTypeText() ? "  [" + p.getTypeText() + "]" : "") + "\n"
    }
    result.set(out)
} }))
result.get()
