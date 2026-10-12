// The lines of the selected editor around the caret (__BEFORE__ above, __AFTER__ below) with <caret>, the selection, the gray inline text
// if one shows, whether a live template is running, and the rows of an open completion list (first 8).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder("@@@")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var document = editor.getDocument()
    var caret = editor.getCaretModel().getOffset()
    var text = String(document.getText())
    var line = document.getLineNumber(caret)
    var from = document.getLineStartOffset(Math.max(0, line - __BEFORE__))
    var to = document.getLineEndOffset(Math.min(document.getLineCount() - 1, line + __AFTER__))
    out.append(text.substring(from, caret) + "<caret>" + text.substring(caret, to) + "\n")
    var model = editor.getSelectionModel()
    if (model.hasSelection()) out.append("selection: [" + model.getSelectedText() + "]\n")
    try {
        var ctx = com.intellij.codeInsight.inline.completion.session.InlineCompletionContext.Companion.getOrNull(editor)
        // the Kotlin accessor is not visible to Rhino: the text is read from the context's toString
        if (ctx != null) { var m = String(ctx).match(/textToInsert=([\s\S]*)\)$/); if (m && m[1].length > 0) out.append("gray: [" + m[1] + "]\n") }
    } catch (e) { out.append("gray: ? " + e + "\n") }
    try {
        var state = com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(editor)
        if (state != null && !state.isFinished()) out.append("template running, stop " + state.getCurrentVariableNumber() + "\n")
    } catch (e) {}
    var lookup = com.intellij.codeInsight.lookup.LookupManager.getActiveLookup(editor)
    if (lookup != null) {
        var items = lookup.getItems()
        var names = []
        for (var i = 0; i < items.size() && i < 8; i++) names.push(String(items.get(i).getLookupString()))
        out.append("list (" + items.size() + "): " + names.join(", ") + (lookup.getCurrentItem() != null ? "  selected=" + lookup.getCurrentItem().getLookupString() : "") + "\n")
    }
} }), ModalityState.any())
out.toString()
