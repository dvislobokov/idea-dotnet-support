// A rename the way the LSP client of the platform does it, without its inline template (which needs the focus of a real window):
// `textDocument/rename` through the Roslyn client (so through the wrapper of the plugin), then the text edits of the answer applied in one
// command, as the platform applies them. The caret is on __NAME__ in the first __AT__ of the selected editor, the new name is __NEW__.
// Before: every open document is reloaded from disk, to start from a clean state.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.openapi.extensions.ExtensionPointName)
importClass(com.intellij.platform.lsp.api.LspClientManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const report = new java.lang.StringBuilder()
const clientHolder = new java.util.concurrent.atomic.AtomicReference(null)
const providers = ExtensionPointName.create("com.intellij.platform.lsp.integrationProvider").getExtensionList()
for (let i = 0; i < providers.size(); i++) {
    if (providers.get(i).getClass().getName().indexOf("Roslyn") < 0) continue
    const clients = LspClientManager.getInstance(project).getClients(providers.get(i).getClass()).toArray()
    if (clients.length > 0) clientHolder.set(clients[0])
}
const paramsHolder = new java.util.concurrent.atomic.AtomicReference(null)
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    const documents = FileDocumentManager.getInstance()
    const unsaved = documents.getUnsavedDocuments()
    for (let i = 0; i < unsaved.length; i++) documents.reloadFromDisk(unsaved[i])
    const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    // the caret goes to the start of __NAME__ inside the first __AT__
    const around = String(editor.getDocument().getText()).indexOf("__AT__")
    const at = around + "__AT__".indexOf("__NAME__")
    const line = editor.getDocument().getLineNumber(at)
    const file = documents.getFile(editor.getDocument())
    paramsHolder.set(new org.eclipse.lsp4j.RenameParams(clientHolder.get().getDocumentIdentifier(file),
        new org.eclipse.lsp4j.Position(line, at - editor.getDocument().getLineStartOffset(line)), "__NEW__"))
} }))
const edit = clientHolder.get().sendRequestSync(20000, new Packages.kotlin.jvm.functions.Function1({
    invoke: function (server) { return server.getTextDocumentService().rename(paramsHolder.get()) }
}))
const changes = edit.getDocumentChanges()
report.append("edited documents: " + changes.size() + "\n")
// a function per document and per edit: Rhino keeps the first value of a `const` declared inside a loop
function applyEdit(document, edit) {
    const range = edit.getRange()
    const start = document.getLineStartOffset(range.getStart().getLine()) + range.getStart().getCharacter()
    const end = document.getLineStartOffset(range.getEnd().getLine()) + range.getEnd().getCharacter()
    document.replaceString(start, end, edit.getNewText())
}
function applyChange(change) {
    const file = clientHolder.get().getDescriptor().findFileByUri(change.getTextDocument().getUri())
    const document = FileDocumentManager.getInstance().getDocument(file)
    const edits = new java.util.ArrayList(change.getEdits())
    // from the end, so that the offsets of the edits before stay right
    for (let e = edits.size() - 1; e >= 0; e--) applyEdit(document, edits.get(e))
    report.append("  " + file.getName() + ": " + edits.size() + " edits\n")
}
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    WriteCommandAction.runWriteCommandAction(project, "Rename", null, new java.lang.Runnable({ run: function () {
        for (let i = 0; i < changes.size(); i++) applyChange(changes.get(i).getLeft())
    } }))
} }))
java.lang.Thread.sleep(__WAIT__)
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    report.append("file of the editor now: " + FileEditorManager.getInstance(project).getSelectedFiles()[0].getName() + "\n")
} }))
report.toString()
