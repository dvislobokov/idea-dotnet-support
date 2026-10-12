// Completion latency for tools/ui-robot/baseline.py. In __FILE__ (forward slashes), on the empty line under the line with __MARKER__, puts
// __PREFIX__ silently and then types __CHAR__ the way the keyboard does (typed handlers, the autopopup of completion); measures from that
// key to the first lookup on screen with items (`first`) and to the lookup that has __EXPECT__ among its items (`expected`). __REPS__
// times, the line restored after each; at most __WAIT__ ms per repetition. One line per repetition: `rep=N first=MS expected=MS items=N`
// (-1: not reached). The autopopup needs the focus of the sandbox window: the script brings it to the front.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.editor.actionSystem.TypedAction)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.ide.DataManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const prefix = "__PREFIX__"
const typed = "__CHAR__"
const expected = "__EXPECT__"
const out = new java.lang.StringBuilder()
const state = new java.util.concurrent.ConcurrentHashMap()
const now = function () { return java.lang.System.nanoTime() / 1e6 }
let editor = null
let start = 0
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0, 0), true)
    const document = editor.getDocument()
    const marker = String(document.getText()).indexOf("__MARKER__")
    // the line under the marker and its comment lines: the first empty one
    var line = document.getLineNumber(marker) + 1
    while (line < document.getLineCount() && String(document.getText(new com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))).trim().length > 0) line++
    start = document.getLineEndOffset(line)
} }))
// the window of the sandbox has just been brought to the front: the first autopopup was lost while the focus was on its way
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
    editor.getContentComponent().requestFocusInWindow()
} }))
java.lang.Thread.sleep(1000)
for (var rep = 1; rep <= __REPS__; rep++) {
    state.clear()
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { editor.getDocument().insertString(start, prefix) } }))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        editor.getCaretModel().moveToOffset(start + prefix.length)
        com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
        editor.getContentComponent().requestFocusInWindow()
    } }))
    // let the focus arrive and the daemon see the prefix, as between two keys of a person
    java.lang.Thread.sleep(300)
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        state.put("t0", java.lang.Double.valueOf(now()))
        TypedAction.getInstance().actionPerformed(editor, typed.charAt(0), DataManager.getInstance().getDataContext(editor.getContentComponent()))
    } }))
    var t0 = state.get("t0")
    while (now() - t0 < __WAIT__ && state.get("expected") == null) {
        java.lang.Thread.sleep(5)
        ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
            var lookup = LookupManager.getInstance(project).getActiveLookup()
            if (lookup == null || !lookup.isShown()) return
            var items = lookup.getItems()
            if (items.size() == 0) return
            state.putIfAbsent("first", java.lang.Double.valueOf(now()))
            state.put("items", java.lang.Integer.valueOf(items.size()))
            for (var i = 0; i < items.size(); i++) if (String(items.get(i).getLookupString()) == expected) { state.putIfAbsent("expected", java.lang.Double.valueOf(now())); break }
        } }))
    }
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        LookupManager.getInstance(project).hideActiveLookup()
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () {
            var document = editor.getDocument()
            document.deleteString(start, document.getLineEndOffset(document.getLineNumber(start)))
        } }))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    } }))
    var first = state.get("first"), hit = state.get("expected"), count = state.get("items")
    out.append("rep=" + rep + " first=" + (first == null ? -1 : Math.round(first - t0)) + " expected=" + (hit == null ? -1 : Math.round(hit - t0)) + " items=" + (count == null ? 0 : count) + "\n")
    java.lang.Thread.sleep(700)
}
String(out)
