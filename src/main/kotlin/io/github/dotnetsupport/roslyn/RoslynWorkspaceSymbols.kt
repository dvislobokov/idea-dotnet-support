package io.github.dotnetsupport.roslyn

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.customization.LspWorkspaceSymbolSupport
import com.intellij.psi.PsiManager
import io.github.dotnetsupport.lang.CSharpSyntaxModel
import org.eclipse.lsp4j.WorkspaceSymbol

/**
 * Go to Symbol / Go to Class through `workspace/symbol` of the server. The platform shows a symbol by its name and file only (Roslyn
 * sends the type in `containerName`: `in BaseShape (project Console (net9.0))`), so `Area` of an interface, of its implementations and
 * of their overrides were four identical rows "Area … GoToBase.cs" (reported). A symbol in a C# file of the project becomes the
 * declaration of the plugin at the same place: the row of the plugin's own Go to (`Area()`, its type in gray, the file on the right,
 * as in Java), the same row whether the server is ready or not. Any other symbol keeps the platform's row with the type of
 * `containerName` in gray ([RoslynSymbolItem]).
 */
class RoslynWorkspaceSymbolSupport : LspWorkspaceSymbolSupport() {
    override fun createNavigationItem(lspClient: LspClient, workspaceSymbol: WorkspaceSymbol): NavigationItem? =
        declarationOf(lspClient, workspaceSymbol) ?: withContainer(super.createNavigationItem(lspClient, workspaceSymbol), workspaceSymbol)

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun createNavigationItem(lspServer: LspServer, workspaceSymbol: WorkspaceSymbol): NavigationItem? =
        declarationOf(lspServer as LspClient, workspaceSymbol) ?: withContainer(super.createNavigationItem(lspServer, workspaceSymbol), workspaceSymbol)

    private fun withContainer(item: NavigationItem?, symbol: WorkspaceSymbol): NavigationItem? {
        val container = containerOf(symbol.containerName) ?: return item
        return item?.let { RoslynSymbolItem(it, container) }
    }

    private fun declarationOf(client: LspClient, symbol: WorkspaceSymbol): NavigationItem? {
        val location = symbol.location?.takeIf { it.isLeft }?.left ?: return null
        return ReadAction.compute<NavigationItem?, RuntimeException> {
            if (client.project.isDisposed) return@compute null
            val file = client.descriptor.findFileByUri(location.uri)?.takeIf(::isCSharpSource) ?: return@compute null
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@compute null
            val offset = RoslynCtrlHoverReferenceProvider.offset(document, location.range.start) ?: return@compute null
            val psiFile = PsiManager.getInstance(client.project).findFile(file) ?: return@compute null
            declarationNamedAt(psiFile, offset)
        }
    }

    companion object {
        private val CONTAINER = Regex("""^in (.+?) \(project .*\)$""")

        /** `BaseShape` of `in BaseShape (project Console (net9.0))`; nothing for a type, whose container is the project alone. */
        fun containerOf(containerName: String?): String? = containerName?.let { CONTAINER.matchEntire(it)?.groupValues?.get(1) }

        /** The declaration whose name starts at [offset]: where Roslyn puts the location of a symbol. */
        fun declarationNamedAt(file: com.intellij.psi.PsiFile, offset: Int): NavigationItem? {
            val model = CSharpSyntaxModel.current
            val element = model.declarationElementAt(file, offset) ?: return null
            return element.takeIf { model.declarationOf(it)?.nameRange?.startOffset == offset } as? NavigationItem
        }
    }
}

/** The row of the platform for a symbol the plugin has no declaration of, with the type it is in shown in gray. */
class RoslynSymbolItem(private val delegate: NavigationItem, private val container: String) : NavigationItem by delegate {
    override fun getPresentation(): com.intellij.navigation.ItemPresentation? {
        val presentation = delegate.presentation ?: return null
        return object : com.intellij.navigation.ItemPresentation, com.intellij.navigation.LocationPresentation {
            override fun getPresentableText(): String? = presentation.presentableText
            override fun getLocationString(): String = container
            override fun getIcon(unused: Boolean): javax.swing.Icon? = presentation.getIcon(unused)
            override fun getLocationPrefix(): String = " "
            override fun getLocationSuffix(): String = ""
        }
    }
}
