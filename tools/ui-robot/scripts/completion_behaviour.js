// How the completion list behaves while typing (0.1.91): opens __FILE__ (forward slashes), puts the caret on the line under the first line
// with __MARKER__ (its end), types __TEXT__ character by character as the keyboard does (typed handlers, auto-popup, char filters run),
// waits __WAIT__ ms and reports the list: shown, focus degree (UNFOCUSED = suggestion mode), the selected item, the first __LIMIT__ items,
// the positions of __FIND__ (names separated by commas). Then types __THEN__ (commit characters) and reports the line; __DOC__ = "yes"
// asks the lookup documentation providers about the selected item (what Ctrl+Q in the list shows). The text is restored at the end.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.editor.actionSystem.TypedAction)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.ide.DataManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const report = new java.lang.StringBuilder()
const typed = "__TEXT__"
const then = "__THEN__"
let editor = null
let original = null
let lineStart = 0
function app() { return ApplicationManager.getApplication() }
function edt(body) { app().invokeAndWait(new java.lang.Runnable({ run: body })) }
function type(ch) {
    edt(function () { TypedAction.getInstance().actionPerformed(editor, ch, DataManager.getInstance().getDataContext(editor.getContentComponent())) })
}
function line() {
    const document = editor.getDocument()
    const number = document.getLineNumber(editor.getCaretModel().getOffset())
    return String(document.getText().substring(document.getLineStartOffset(number), document.getLineEndOffset(number)))
}
edt(function () {
    const file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, 0, 0), true)
    original = editor.getDocument().getText()
    const marker = String(original).indexOf("__MARKER__")
    if (marker < 0) { report.append("no __MARKER__"); return }
    const document = editor.getDocument()
    // the first empty line after the marker's comment
    let number = document.getLineNumber(marker) + 1
    while (number < document.getLineCount() && String(document.getText().substring(document.getLineStartOffset(number), document.getLineEndOffset(number))).trim().length > 0) number++
    lineStart = document.getLineStartOffset(number)
    editor.getCaretModel().moveToOffset(document.getLineEndOffset(number))
    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
    editor.getContentComponent().requestFocusInWindow()
})
if (report.length() == 0) {
    for (let i = 0; i < typed.length; i++) {
        type(typed.charAt(i))
        java.lang.Thread.sleep(__PAUSE__)
    }
    // __EXPLICIT__ = "yes": then Ctrl+Space
    if ("__EXPLICIT__" == "yes") edt(function () {
        new com.intellij.codeInsight.completion.CodeCompletionHandlerBase(com.intellij.codeInsight.completion.CompletionType.BASIC, true, false, true).invokeCompletion(project, editor)
    })
    java.lang.Thread.sleep(__WAIT__)
    edt(function () {
        const lookup = LookupManager.getInstance(project).getActiveLookup()
        report.append("line: [" + line() + "]\n")
        if (lookup == null) { report.append("list: none\n"); return }
        const items = lookup.getItems()
        report.append("list: " + items.size() + " items, focus " + lookup.getLookupFocusDegree() + ", selected " + (lookup.getCurrentItem() == null ? "-" : lookup.getCurrentItem().getLookupString()) + "\n")
        let first = ""
        for (var i = 0; i < items.size() && i < __LIMIT__; i++) first += items.get(i).getLookupString() + " "
        report.append("first: " + first + "\n")
        const wanted = "__FIND__".split(",")
        for (var w = 0; w < wanted.length; w++) {
            if (wanted[w].length == 0) continue
            var position = -1
            for (var j = 0; j < items.size(); j++) if (String(items.get(j).getLookupString()) == wanted[w]) { position = j + 1; break }
            report.append("position of " + wanted[w] + ": " + position + "\n")
        }
        if ("__DOC__" == "yes" && lookup.getCurrentItem() != null) {
            const psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
            const providers = new com.intellij.openapi.extensions.ExtensionPointName("com.intellij.platform.backend.documentation.lookupElementTargetProvider").getExtensionList()
            const item = lookup.getCurrentItem()
            const offset = editor.getCaretModel().getOffset()
            app().runReadAction(new java.lang.Runnable({ run: function () {
                for (var p = 0; p < providers.size(); p++) {
                    var target = null
                    try { target = providers.get(p).documentationTarget(psiFile, item, offset) } catch (e) { report.append("doc error: " + e + "\n") }
                    if (target == null) continue
                    let text = ""
                    try { text = String(target.getDoc().getHtml()) } catch (e) { text = String(target) }
                    report.append("doc (" + providers.get(p).getClass().getSimpleName() + "): " + text.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").substring(0, 300) + "\n")
                }
            } }))
        }
    })
    if (then.length > 0) {
        for (let i = 0; i < then.length; i++) {
            type(then.charAt(i))
            java.lang.Thread.sleep(__PAUSE__)
        }
        java.lang.Thread.sleep(300)
        edt(function () { report.append("after '" + then + "': [" + line() + "]\n") })
    }
    edt(function () {
        const lookup = LookupManager.getInstance(project).getActiveLookup()
        if (lookup != null) lookup.hideLookup(true)
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { editor.getDocument().setText(original) } }))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    })
}
report.toString()
