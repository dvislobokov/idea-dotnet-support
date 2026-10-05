package io.github.dotnetsupport.roslyn

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import io.github.dotnetsupport.lang.CSharpFile
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TypeHierarchyItem
import javax.swing.Icon

/*
 * Type and call hierarchies of C# come from the server (`textDocument/prepareTypeHierarchy`, `typeHierarchy/supertypes` / `subtypes`,
 * `textDocument/prepareCallHierarchy`, `callHierarchy/incomingCalls` / `outgoingCalls`): the platform draws its Hierarchy tool window
 * around PSI elements, so every item of the server becomes a light element that knows its file and position. The shapes are the ones
 * of `src/test/resources/roslyn/capture-5.12` (39-44).
 */

/** One row of a hierarchy: what the server said about a symbol, with a place to go to. */
class HierarchyItem(val name: String, val kind: SymbolKind?, val detail: String?, val uri: String, val range: Range, val selectionRange: Range, val data: Any?) {
    fun toTypeItem(): TypeHierarchyItem = TypeHierarchyItem(name, kind ?: SymbolKind.Class, uri, range, selectionRange).also { it.detail = detail; it.data = data }
    fun toCallItem(): CallHierarchyItem = CallHierarchyItem(name, kind ?: SymbolKind.Method, uri, range, selectionRange).also { it.detail = detail; it.data = data }

    /** `Playground.LspCapture` of a method, `Playground` of a type: the container the server names. */
    val container: String? get() = detail?.takeIf { it.isNotBlank() }

    val icon: Icon get() = when (kind) {
        SymbolKind.Interface -> AllIcons.Nodes.Interface
        SymbolKind.Enum -> AllIcons.Nodes.Enum
        SymbolKind.Struct -> AllIcons.Nodes.Record
        SymbolKind.Method, SymbolKind.Function, SymbolKind.Constructor -> AllIcons.Nodes.Method
        SymbolKind.Property -> AllIcons.Nodes.Property
        SymbolKind.Field, SymbolKind.Variable -> AllIcons.Nodes.Field
        SymbolKind.Event -> AllIcons.Nodes.Favorite
        else -> AllIcons.Nodes.Class
    }

    val isInterface: Boolean get() = kind == SymbolKind.Interface

    /** Same symbol: the server keys it by its `data`, the name and the place are the fallback. */
    fun sameAs(other: HierarchyItem): Boolean =
        if (data != null && other.data != null) data.toString() == other.data.toString() else name == other.name && uri == other.uri && selectionRange == other.selectionRange

    companion object {
        fun of(item: TypeHierarchyItem) = HierarchyItem(item.name, item.kind, item.detail, item.uri, item.range, item.selectionRange, item.data)
        fun of(item: CallHierarchyItem) = HierarchyItem(item.name, item.kind, item.detail, item.uri, item.range, item.selectionRange, item.data)
    }
}

/** The element the Hierarchy tool window holds for an item of the server: valid as long as the project is, navigates to the item. */
class HierarchyElement(private val project: Project, val client: LspClient, val item: HierarchyItem) : FakePsiElement() {
    val file: VirtualFile? get() = client.descriptor.findFileByUri(item.uri)

    override fun getProject(): Project = project
    override fun getParent(): PsiElement? = containingFile
    override fun getContainingFile(): PsiFile? = file?.let { PsiManager.getInstance(project).findFile(it) }
    override fun getName(): String = item.name
    override fun isValid(): Boolean = !project.isDisposed
    override fun getIcon(open: Boolean): Icon = item.icon
    override fun canNavigate(): Boolean = file != null
    override fun canNavigateToSource(): Boolean = canNavigate()
    override fun isEquivalentTo(another: PsiElement?): Boolean = another is HierarchyElement && another.item.sameAs(item)

    override fun navigate(requestFocus: Boolean) {
        val file = file ?: return
        val start = item.selectionRange.start
        OpenFileDescriptor(project, file, start.line, start.character).navigate(requestFocus)
    }

    /** The name with its container, as the Hierarchy window shows a row. */
    val rowText: String get() = item.name
    val locationText: String? get() = item.container ?: file?.name
}

/** What is under the caret of a C# editor whose server is loaded: the caller of the hierarchy actions. */
object RoslynHierarchies {
    class Target(val project: Project, val client: LspClient, val file: VirtualFile, val position: Position, val word: String)

    fun target(dataContext: DataContext): Target? {
        val project = dataContext.getData(CommonDataKeys.PROJECT) ?: return null
        val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return null
        val psiFile = dataContext.getData(CommonDataKeys.PSI_FILE) as? CSharpFile ?: return null
        return target(project, editor, psiFile)
    }

    /**
     * The target of a hierarchy action. Its update asks for it in a background read action (the editor's menu, Navigate To), where a
     * modal progress must not run: there a loaded server is enough to enable the action, and the request waits for the click on the EDT.
     */
    fun element(dataContext: DataContext, prepare: (Target) -> List<HierarchyItem>): PsiElement? {
        val target = target(dataContext) ?: return null
        if (!ApplicationManager.getApplication().isDispatchThread) return dataContext.getData(CommonDataKeys.PSI_FILE)
        val item = prepare(target).firstOrNull() ?: return null
        return HierarchyElement(target.project, target.client, item)
    }

    fun target(project: Project, editor: Editor, psiFile: PsiFile): Target? {
        val file = psiFile.virtualFile ?: return null
        val workspace = project.service<RoslynWorkspace>()
        val client = workspace.clients.firstOrNull()?.takeIf { workspace.isLoaded } ?: return null
        val offset = editor.caretModel.offset
        return Target(project, client, file, RoslynNavigation.position(editor.document, offset), RoslynNavigation.wordAt(editor.document.immutableCharSequence, offset))
    }

    fun prepareType(target: Target): List<HierarchyItem> = withProgress(target.project, "Preparing Type Hierarchy of ${target.word}") { prepareTypeNow(target) }

    /** [prepareType] on the current thread, which is not the EDT: for a search that already runs under a progress. */
    fun prepareTypeNow(target: Target): List<HierarchyItem> {
        val params = org.eclipse.lsp4j.TypeHierarchyPrepareParams(target.client.getDocumentIdentifier(target.file), target.position)
        return target.client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.prepareTypeHierarchy(params) }.orEmpty().map(HierarchyItem::of)
    }

    fun supertypes(client: LspClient, item: HierarchyItem): List<HierarchyItem> =
        client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.typeHierarchySupertypes(org.eclipse.lsp4j.TypeHierarchySupertypesParams(item.toTypeItem())) }.orEmpty().map(HierarchyItem::of)

    fun subtypes(client: LspClient, item: HierarchyItem): List<HierarchyItem> =
        client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.typeHierarchySubtypes(org.eclipse.lsp4j.TypeHierarchySubtypesParams(item.toTypeItem())) }.orEmpty().map(HierarchyItem::of)

    fun prepareCall(target: Target): List<HierarchyItem> = withProgress(target.project, "Preparing Call Hierarchy of ${target.word}") {
        val params = org.eclipse.lsp4j.CallHierarchyPrepareParams(target.client.getDocumentIdentifier(target.file), target.position)
        target.client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.prepareCallHierarchy(params) }.orEmpty().map(HierarchyItem::of)
    }

    fun callers(client: LspClient, item: HierarchyItem): List<HierarchyItem> =
        client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.callHierarchyIncomingCalls(org.eclipse.lsp4j.CallHierarchyIncomingCallsParams(item.toCallItem())) }.orEmpty().map { HierarchyItem.of(it.from) }

    fun callees(client: LspClient, item: HierarchyItem): List<HierarchyItem> =
        client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.callHierarchyOutgoingCalls(org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams(item.toCallItem())) }.orEmpty().map { HierarchyItem.of(it.to) }

    fun <T> withProgress(project: Project, title: String, compute: () -> T): T =
        ProgressManager.getInstance().runProcessWithProgressSynchronously<T, RuntimeException>({ compute() }, title, true, project)

    /** The document is unsaved: the server works on the text of the editor, the item ranges are of that text. */
    fun documentOf(file: VirtualFile) = FileDocumentManager.getInstance().getDocument(file)

    private const val TIMEOUT_MS = 30_000
}
