// Runs a client command of Roslyn the way a click on a code lens does: roslyn.client.peekReferences for the first __AT__ of the selected editor.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.extensions.ExtensionPointName)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.platform.lsp.api.LspClientManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
const file = FileDocumentManager.getInstance().getFile(editor.getDocument())
const offset = String(editor.getDocument().getText()).indexOf("__AT__")
const line = editor.getDocument().getLineNumber(offset)
const providers = ExtensionPointName.create("com.intellij.platform.lsp.integrationProvider").getExtensionList()
let text = "no Roslyn client"
for (let i = 0; i < providers.size(); i++) {
    if (providers.get(i).getClass().getName().indexOf("Roslyn") < 0) continue
    const clients = LspClientManager.getInstance(project).getClients(providers.get(i).getClass()).toArray()
    if (clients.length == 0) continue
    // plain Java values: the gson classes Rhino sees belong to the robot plugin, not to the platform, and are not JsonElement for the IDE
    const position = new java.util.LinkedHashMap()
    position.put("line", java.lang.Integer.valueOf(line))
    position.put("character", java.lang.Integer.valueOf(offset - editor.getDocument().getLineStartOffset(line)))
    const args = new java.util.ArrayList()
    args.add(String(clients[0].getDescriptor().getFileUri(file)))
    args.add(position)
    const command = new org.eclipse.lsp4j.Command("1 reference", "roslyn.client.peekReferences", args)
    clients[0].getDescriptor().getLspCustomization().getCommandsCustomizer().executeCommand(clients[0], file, command)
    text = "executed for line " + (line + 1)
}
text
