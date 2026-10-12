// Invokes the Alt+Enter item whose text is __TEXT__ with the caret inside the first __AT__ of the selected editor.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.daemon.impl.ShowIntentionsPass)
importClass(com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler)
importClass(com.intellij.psi.PsiDocumentManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    editor.getCaretModel().moveToOffset(String(editor.getDocument().getText()).indexOf("__AT__") + __INSIDE__)
} }))
java.lang.Thread.sleep(4000)
const psiFile = com.intellij.openapi.application.ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: function () { return PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()) } }))
const info = com.intellij.openapi.application.ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: function () { return ShowIntentionsPass.getActionsToShow(editor, psiFile) } }))
const all = new java.util.ArrayList()
all.addAll(info.errorFixesToShow); all.addAll(info.inspectionFixesToShow); all.addAll(info.intentionsToShow)
const found = new java.util.concurrent.atomic.AtomicReference(null)
for (let i = 0; i < all.size(); i++) if (String(all.get(i).getAction().getText()) == "__TEXT__" && found.get() == null) found.set(all.get(i).getAction())
if (found.get() != null) ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    ShowIntentionActionsHandler.chooseActionAndInvoke(psiFile, editor, found.get(), "__TEXT__")
} }))
found.get() == null ? "no such item among " + all.size() : "invoked __TEXT__"
