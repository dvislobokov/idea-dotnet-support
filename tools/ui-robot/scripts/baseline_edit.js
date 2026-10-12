// The editing session of tools/ui-robot/baseline.py before the second memory snapshot: in __FILE__, on the empty line under __MARKER__,
// types __TEXT__ (`\n` for a new line) __TIMES__ times, a character every __DELAY__ ms, the way the keyboard does; a lookup that opens
// is closed before a character that would choose from it. Then waits __SETTLE__ ms for the server and the daemon, and puts the text of
// the file back as it was (the document is not saved). Returns how many characters were typed and how long it took.
importClass(com.intellij.openapi.application.ModalityState)
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
const text = "__TEXT__".split("\\n").join("\n")
let editor = null
let original = null
const started = java.lang.System.nanoTime()
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0, 0), true)
    const document = editor.getDocument()
    original = document.getText()
    const marker = String(original).indexOf("__MARKER__")
    var line = document.getLineNumber(marker) + 1
    while (line < document.getLineCount() && String(document.getText(new com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))).trim().length > 0) line++
    editor.getCaretModel().moveToOffset(document.getLineEndOffset(line))
    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
    editor.getContentComponent().requestFocusInWindow()
} }), ModalityState.nonModal())
let count = 0
for (var round = 0; round < __TIMES__; round++) {
    for (var c = 0; c < text.length; c++) {
        var ch = text.charAt(c)
        ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
            if (!java.lang.Character.isJavaIdentifierPart(ch)) LookupManager.getInstance(project).hideActiveLookup()
            TypedAction.getInstance().actionPerformed(editor, ch, DataManager.getInstance().getDataContext(editor.getContentComponent()))
        } }), ModalityState.nonModal())
        count++
        java.lang.Thread.sleep(__DELAY__)
    }
}
java.lang.Thread.sleep(__SETTLE__)
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    LookupManager.getInstance(project).hideActiveLookup()
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { editor.getDocument().setText(original) } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
} }), ModalityState.nonModal())
"typed=" + count + " seconds=" + Math.round((java.lang.System.nanoTime() - started) / 1e9)
