// The Roslyn LSP client of the last opened project: state of the server, and what the daemon shows in the selected editor.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.extensions.ExtensionPointName)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.platform.lsp.api.LspClientManager)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
importClass(com.intellij.lang.annotation.HighlightSeverity)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const providers = ExtensionPointName.create("com.intellij.platform.lsp.integrationProvider").getExtensionList()
let text = "project: " + project.getName() + "\n"
for (let i = 0; i < providers.size(); i++) {
    const provider = providers.get(i)
    if (provider.getClass().getName().indexOf("Roslyn") < 0) continue
    const clients = LspClientManager.getInstance(project).getClients(provider.getClass()).toArray()
    text += "clients: " + clients.length + "\n"
    for (let c = 0; c < clients.length; c++) {
        const result = clients[c].getInitializeResult()
        text += "  " + clients[c].getState() + " " + (result == null || result.getServerInfo() == null ? "?" : result.getServerInfo().getName() + " " + result.getServerInfo().getVersion()) + "\n"
    }
    const workspace = project.getService(provider.getClass().getClassLoader().loadClass("io.github.dotnetsupport.roslyn.RoslynWorkspace"))
    text += "loaded: " + workspace.isLoaded() + ", solutions: " + workspace.getKnownSolutions().size() + ", chosen: " + workspace.getState().getSolution() + "\n"
}
// Rhino: a `let` / `const` declared inside the `else` block below kept its first value on every iteration, hence everything at the top level
const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
const infos = editor == null ? new java.util.ArrayList() : com.intellij.openapi.application.ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: function () {
    return DaemonCodeAnalyzerImpl.getHighlights(editor.getDocument(), HighlightSeverity.WEAK_WARNING, project)
} }))
text += editor == null ? "editor: none\n" : "highlights (weak warning and above): " + infos.size() + "\n"
for (let i = 0; i < infos.size() && i < __LIMIT__; i++) {
    text += "  " + infos.get(i).getSeverity() + " line " + (editor.getDocument().getLineNumber(infos.get(i).getStartOffset()) + 1) + ": " + infos.get(i).getDescription() + "\n"
}
text
