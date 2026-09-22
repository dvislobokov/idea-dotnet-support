// In the selected editor: puts the caret after the first __AFTER__, optionally types __TYPE__, then __WHAT__ = "complete" (items of the
// popup) or "goto" (where Go to Declaration lands). For checking the language server through the editor, as a user would.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.completion.CodeCompletionHandlerBase)
importClass(com.intellij.codeInsight.completion.CompletionType)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.codeInsight.navigation.actions.GotoDeclarationAction)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.openapi.actionSystem.ActionPlaces)
importClass(com.intellij.ide.DataManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const what = "__WHAT__"
const holder = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    const at = String(editor.getDocument().getText()).indexOf("__AFTER__")
    if (at < 0) { holder.set("no __AFTER__ in the editor"); return }
    let offset = at + "__AFTER__".length
    const typed = "__TYPE__"
    if (typed.length > 0) {
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { editor.getDocument().insertString(offset, typed) } }))
        offset += typed.length
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    editor.getCaretModel().moveToOffset(offset)
    if (what == "complete") new CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true).invokeCompletion(project, editor)
    else {
        const action = ActionManager.getInstance().getAction("GotoDeclaration")
        ActionUtil.invokeAction(action, DataManager.getInstance().getDataContext(editor.getContentComponent()), ActionPlaces.UNKNOWN, null, null)
    }
} }))
java.lang.Thread.sleep(__WAIT__)
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    if (what == "complete") {
        const lookup = LookupManager.getInstance(project).getActiveLookup()
        if (lookup == null) { holder.set("no lookup"); return }
        const items = lookup.getItems()
        let text = "items: " + items.size() + "\n"
        for (let i = 0; i < items.size() && i < 15; i++) text += "  " + items.get(i).getLookupString() + "\n"
        holder.set(text)
        LookupManager.getInstance(project).hideActiveLookup()
    } else {
        const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
        const file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.getDocument())
        holder.set("now at " + file.getName() + ":" + (editor.getCaretModel().getLogicalPosition().line + 1))
    }
} }))
holder.get()
