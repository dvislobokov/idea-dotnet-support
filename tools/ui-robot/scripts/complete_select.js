// Completion with a choice: opens __FILE__ (forward slashes), types __TYPE__ at the end of line __LINE__ (from 1), invokes basic completion,
// waits up to __WAIT__ ms for an item whose lookup string is __ITEM__ (with __SHOWN__ non-empty: whose shown text starts so — the server's
// `override` members have an empty lookup string), chooses it as Enter does and prints lines __FROM__..__TO__ of the result with `<caret>`.
// Then puts the whole text of the file back as it was (one write command, so the playground keeps compiling). For «Completion» = Built-in
// vs Language server (feature_source.js `COMPLETION`): `await` making the method `async` (CommonCalls.cs, TYPE:complete-await-async),
// `override` members, `Task.FromResult` after `return `. Prints `no item` with the first lookup strings when the item never came.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.codeInsight.completion.CodeCompletionHandlerBase)
importClass(com.intellij.codeInsight.completion.CompletionType)
importClass(com.intellij.codeInsight.lookup.Lookup)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.codeInsight.lookup.LookupElementPresentation)
importClass(com.intellij.psi.PsiDocumentManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var state = { editor: null, original: null, item: null, seen: "", text: "" }
function edt(body) { app.invokeAndWait(new java.lang.Runnable({ run: body }), ModalityState.nonModal()) }

edt(function () {
    var file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    var editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, __LINE__ - 1, 0), true)
    state.editor = editor
    state.original = editor.getDocument().getText()
    var start = editor.getDocument().getLineEndOffset(__LINE__ - 1)
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { editor.getDocument().insertString(start, "__TYPE__") } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    editor.getCaretModel().moveToOffset(start + "__TYPE__".length)
    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
    editor.getContentComponent().requestFocusInWindow()
    new CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true).invokeCompletion(project, editor)
})
for (var tick = 0; tick < __WAIT__ / 200 && state.item == null; tick++) {
    java.lang.Thread.sleep(200)
    edt(function () {
        var lookup = LookupManager.getInstance(project).getActiveLookup()
        if (lookup == null) return
        var items = lookup.getItems()
        state.seen = ""
        for (var i = 0; i < items.size(); i++) {
            var item = items.get(i)
            if (i < 8) state.seen += item.getLookupString() + ", "
            var shown = String(LookupElementPresentation.renderElement(item).getItemText())
            var match = "__SHOWN__" != "" ? shown.indexOf("__SHOWN__") == 0 : String(item.getLookupString()) == "__ITEM__"
            if (match) { state.item = item; break }
        }
    })
}
edt(function () {
    var lookup = LookupManager.getInstance(project).getActiveLookup()
    if (state.item == null || lookup == null) {
        state.text = "no item; the list began with: " + state.seen + "\n"
        if (lookup != null) LookupManager.getInstance(project).hideActiveLookup()
    } else {
        lookup.setCurrentItem(state.item)
        lookup.finishLookup(Lookup.NORMAL_SELECT_CHAR)
    }
})
// insert handlers may finish later (a write action of their own)
java.lang.Thread.sleep(500)
edt(function () {
    var document = state.editor.getDocument()
    var caret = state.editor.getCaretModel().getOffset()
    var text = String(document.getText())
    var out = new java.lang.StringBuilder(state.text)
    for (var line = __FROM__ - 1; line <= __TO__ - 1 && line < document.getLineCount(); line++) {
        var from = document.getLineStartOffset(line), to = document.getLineEndOffset(line)
        var row = caret >= from && caret <= to ? text.substring(from, caret) + "<caret>" + text.substring(caret, to) : text.substring(from, to)
        out.append((line + 1) + ": " + row + "\n")
    }
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { document.setText(state.original) } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    out.append(String(document.getText()) == String(state.original) ? "restored\n" : "NOT restored\n")
    state.text = out.toString()
})
state.text
