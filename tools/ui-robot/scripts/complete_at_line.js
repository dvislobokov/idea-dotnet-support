// Opens __FILE__ (a path with forward slashes), types __TYPE__ at the end of its line __LINE__ (1-based) and invokes completion; the items are captured the moment
// the lookup shows (a listener on LookupManagerListener.TOPIC), because the popup closes as soon as the sandbox window loses focus —
// between two commands of the robot it is gone. Reports the first __LIMIT__ items; __UNDO__ = "yes" removes the typed text afterwards.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.completion.CodeCompletionHandlerBase)
importClass(com.intellij.codeInsight.completion.CompletionType)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.codeInsight.lookup.LookupManagerListener)
importClass(com.intellij.codeInsight.lookup.LookupEvent)
importClass(com.intellij.codeInsight.lookup.LookupListener)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.openapi.util.Disposer)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const holder = new java.util.concurrent.atomic.AtomicReference("no lookup")
const disposable = Disposer.newDisposable()
const typed = "__TYPE__"
let start = 0
let editor = null
function snapshot(lookup) {
    const items = lookup.getItems()
    let text = "items: " + items.size() + "\n"
    // __PRESENT__ = "yes": the text the list shows too, when it is not the lookup string (items of the server may have an empty one);
    // `var`, not `let` / `const`: Rhino keeps the first value of a block-scoped variable in a loop
    for (var i = 0; i < items.size() && i < __LIMIT__; i++) {
        var shown = ""
        var itemText = "__PRESENT__" == "yes" ? com.intellij.codeInsight.lookup.LookupElementPresentation.renderElement(items.get(i)).getItemText() : null
        if (itemText != null && String(itemText) != String(items.get(i).getLookupString())) shown = " [" + itemText + "]"
        text += "  " + items.get(i).getLookupString() + shown + "\n"
    }
    // __FIND__: names whose position in the list is wanted, separated by commas
    const wanted = "__FIND__".split(",")
    for (let w = 0; w < wanted.length; w++) {
        if (wanted[w].length == 0) continue
        let position = -1
        for (let i = 0; i < items.size(); i++) if (String(items.get(i).getLookupString()) == wanted[w]) { position = i + 1; break }
        text += "  position of " + wanted[w] + ": " + position + "\n"
    }
    holder.set(text)
}
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    // by path, not the selected editor: with a split or a second window the selected one is whichever has the focus
    const file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, __LINE__ - 1, 0), true)
    start = editor.getDocument().getLineEndOffset(__LINE__ - 1)
    if (typed.length > 0) WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { editor.getDocument().insertString(start, typed) } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    editor.getCaretModel().moveToOffset(start + typed.length)
    project.getMessageBus().connect(disposable).subscribe(LookupManagerListener.TOPIC, new LookupManagerListener({ activeLookupChanged: function (oldLookup, newLookup) {
        if (newLookup == null) return
        snapshot(newLookup)
        newLookup.addLookupListener(new LookupListener({ uiRefreshed: function () { snapshot(newLookup) }, itemSelected: function (e) {}, lookupCanceled: function (e) {}, currentItemChanged: function (e) {}, lookupShown: function (e) { snapshot(newLookup) }, focusDegreeChanged: function () {}, beforeItemSelected: function (e) { return true } }))
    } }))
    // the popup lives only in a focused window: bring the sandbox to the front first
    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
    editor.getContentComponent().requestFocusInWindow()
    new CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true).invokeCompletion(project, editor)
} }))
// the items arrive after the popup: poll while the lookup is there, keep the fullest list seen
for (let tick = 0; tick < __WAIT__ / 200; tick++) {
    java.lang.Thread.sleep(200)
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        const lookup = LookupManager.getInstance(project).getActiveLookup()
        if (lookup != null && lookup.getItems().size() > 0) snapshot(lookup)
    } }))
}
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const lookup = LookupManager.getInstance(project).getActiveLookup()
    if (lookup != null) { snapshot(lookup); LookupManager.getInstance(project).hideActiveLookup() }
    Disposer.dispose(disposable)
    if ("__UNDO__" == "yes" && typed.length > 0) WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { editor.getDocument().deleteString(start, start + typed.length) } }))
} }))
holder.get()
