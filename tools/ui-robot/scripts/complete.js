// Types __TEXT__ into the Evaluate field of the Debug tool window, invokes completion and returns the items of the popup.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.wm.WindowManager)
importClass(com.intellij.openapi.editor.ex.EditorEx)
importClass(com.intellij.ui.EditorTextField)
importClass(com.intellij.util.ui.UIUtil)
importClass(com.intellij.codeInsight.completion.CodeCompletionHandlerBase)
importClass(com.intellij.codeInsight.completion.CompletionType)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.psi.PsiDocumentManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const holder = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const frame = WindowManager.getInstance().getFrame(project)
    const fields = UIUtil.findComponentsOfType(frame.getRootPane(), EditorTextField)
    let field = null
    for (let i = 0; i < fields.size(); i++) if (fields.get(i).isShowing() && fields.get(i).getEditor() != null) { field = fields.get(i); break }
    if (field == null) { holder.set("no expression field is showing (" + fields.size() + " editor fields)"); return }
    const editor = field.getEditor()
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { editor.getDocument().setText("__TEXT__") } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    editor.getCaretModel().moveToOffset(editor.getDocument().getTextLength())
    const file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
    holder.set("field: " + (file == null ? "no psi" : file.getLanguage().getID() + " / " + file.getName()) + "\n")
    new CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true).invokeCompletion(project, editor)
} }))
let items = ""
for (let attempt = 0; attempt < 40 && items == ""; attempt++) {
    java.lang.Thread.sleep(250)
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
        const lookup = LookupManager.getInstance(project).getActiveLookup()
        if (lookup == null) return
        const list = lookup.getItems()
        for (let i = 0; i < list.size(); i++) items += list.get(i).getLookupString() + " "
    } }))
}
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () { LookupManager.getInstance(project).hideActiveLookup() } }))
holder.get() + "__TEXT__ -> " + (items == "" ? "<no popup>" : items)
