// The client capabilities the LSP client of the platform sends to the Roslyn server in `initialize`, as JSON: saved as
// tools/roslyn-lsp/client-capabilities.json they make capture.py ask the server the way the IDE does.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.extensions.ExtensionPointName)
importClass(com.intellij.platform.lsp.api.LspClientManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const providers = ExtensionPointName.create("com.intellij.platform.lsp.integrationProvider").getExtensionList()
const gson = new org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler(java.util.Collections.emptyMap()).getGson()
let text = "no Roslyn client is running"
for (let i = 0; i < providers.size(); i++) {
    if (providers.get(i).getClass().getName().indexOf("Roslyn") < 0) continue
    const clients = LspClientManager.getInstance(project).getClients(providers.get(i).getClass()).toArray()
    if (clients.length > 0) text = "CAPABILITIES" + gson.toJson(clients[0].getDescriptor().createInitializeParams().getCapabilities())
}
text
