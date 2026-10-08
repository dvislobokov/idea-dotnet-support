package io.github.dotnetsupport.index

import com.intellij.psi.PsiFile
import io.github.dotnetsupport.decompiler.AssemblyDecompiler
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.util.containers.ContainerUtil
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lsp.RoslynServerStatus
import io.github.dotnetsupport.lsp.RoslynOptions
import io.github.dotnetsupport.sourcelink.LibrarySources
import java.io.File
import java.util.function.Function
import javax.swing.JComponent

/**
 * The files of the metadata view: `dotnet-metadata://v2/<mvid>/<type>/<Name>.cs`. A file system of its own and not a bare [LightVirtualFile]:
 * a tab, the navigation history (Back / Forward, Recent Locations) and the tabs reopened with the project keep the URL and find the file
 * again by it, after the file object has been dropped or the IDE restarted — the index is found by its MVID (an open project's, else the
 * file in the cache of indexes). Nothing is written: no physical file, nothing for the file watchers and the indexes of the IDE to see.
 */
class AssemblyMetadataFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
    override fun getProtocol(): String = PROTOCOL
    override fun findFileByPath(path: String): VirtualFile? = AssemblyNavigation.findFile(path)
    override fun refresh(asynchronous: Boolean) = Unit
    override fun refreshAndFindFileByPath(path: String): VirtualFile? = findFileByPath(path)

    companion object {
        const val PROTOCOL = "dotnet-metadata"
        fun getInstance(): AssemblyMetadataFileSystem = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as AssemblyMetadataFileSystem
    }
}

/** A read-only C# file with the metadata of [type] (an outermost type) of [index]; the native tree, colors and folding as any C# file. */
class AssemblyMetadataFile internal constructor(val index: AssemblyIndex, val type: IndexedType, private val metadataPath: String, val rendered: AssemblyMetadataText.Rendered) :
    LightVirtualFile(type.simpleName + ".cs", CSharpFileType, rendered.text) {
    init {
        isWritable = false
    }

    override fun getFileSystem(): AssemblyMetadataFileSystem = AssemblyMetadataFileSystem.getInstance()
    override fun getPath(): String = metadataPath
    override fun isValid(): Boolean = true

    /** `System.Collections 10.0`. */
    val assemblyTitle: String get() = index.assemblyName + " " + AssemblyMetadataText.shortVersion(index.assemblyVersion)
}

/**
 * Go to a type or a member of a referenced assembly without a decompiler and without the language server ("as in Rider: the metadata of
 * an assembly"): the metadata view of its type ([AssemblyMetadataText]) at the line of its declaration. Used by Go to Class / Go to Symbol
 * over the libraries ([AssemblyGotoClassContributor]) and by the built-in Go to Declaration ([targets]).
 */
object AssemblyNavigation {
    private val files: MutableMap<String, AssemblyMetadataFile> = ContainerUtil.createConcurrentWeakValueMap()

    /** The indexes a metadata file was made of in this session, by MVID: a URL of the history finds its index while no project lists it. */
    private val seen: MutableMap<String, AssemblyIndex> = ContainerUtil.createConcurrentWeakValueMap()

    fun isMetadata(file: VirtualFile?): Boolean = file is AssemblyMetadataFile

    /** `v2/<mvid>/System.Collections.Generic.List`1/List.cs`: the metadata view of the outermost type of [type]. */
    fun path(type: IndexedType): String {
        val outer = AssemblyMetadataText.outermost(type)
        return "v${AssemblyIndex.FORMAT_VERSION}/${outer.index.mvid}/${outer.fullName}/${outer.simpleName}.cs"
    }

    /** The metadata view of the outermost type of [type]: made once per assembly, type and format of the index while a tab or a target holds it. */
    fun file(type: IndexedType): AssemblyMetadataFile {
        val path = path(type)
        files[path]?.let { return it }
        val outer = AssemblyMetadataText.outermost(type)
        seen[outer.index.mvid] = outer.index
        val made = AssemblyMetadataFile(outer.index, outer, path, AssemblyMetadataText.render(outer, assemblyFile(outer.index.mvid)?.path))
        return files.putIfAbsent(path, made) ?: made
    }

    /** The file of a URL of the metadata view (a tab reopened, the history), or null when the index is gone. */
    fun findFile(path: String): AssemblyMetadataFile? {
        files[path]?.let { return it }
        val parts = path.trimStart('/').split('/')
        if (parts.size != 4 || parts[0] != "v${AssemblyIndex.FORMAT_VERSION}") return null
        val index = findIndex(parts[1]) ?: return null
        val type = index.findType(parts[2]) ?: return null
        return file(type)
    }

    private fun findIndex(mvid: String): AssemblyIndex? {
        seen[mvid]?.let { return it }
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            project.getServiceIfCreated(AssemblyIndexService::class.java)?.allIndexes()?.firstOrNull { it.mvid == mvid }?.let { return it }
        }
        val file = File(IndexerTool.indexDirectory(), "$mvid.${AssemblyIndex.EXTENSION}").takeIf { it.isFile } ?: return null
        return runCatching { AssemblyIndex.open(file.toPath()) }.getOrNull()?.also { seen[mvid] = it }
    }

    /** The dll of the index of [mvid], as an open project's indexer has found it. */
    fun assemblyFile(mvid: String): File? = ProjectManager.getInstance().openProjects.asSequence()
        .filter { !it.isDisposed }.mapNotNull { it.getServiceIfCreated(AssemblyIndexService::class.java)?.assemblyFile(mvid) }.firstOrNull()

    /** The offset of the name of [type] in its metadata view. */
    fun offset(type: IndexedType): Int = file(type).rendered.typeOffsets[type.row] ?: 0

    /** The offset of the name of [member] in the metadata view of its type; the type's when the view leaves it out. */
    fun offset(member: IndexedMember): Int = file(member.type).rendered.memberOffsets[member.row] ?: offset(member.type)

    /** The declaration of [type] in its metadata view, for Go to Declaration. Read action. */
    fun target(project: Project, type: IndexedType): PsiElement? = element(project, file(type), offset(type))

    /** The declaration of [member] in the metadata view of its type, for Go to Declaration. Read action. */
    fun target(project: Project, member: IndexedMember): PsiElement? = element(project, file(member.type), offset(member))

    /**
     * Where the built-in Go to Declaration goes for a symbol of an assembly the resolver has found (`CSharpSymbol.LibraryType` /
     * `LibraryMember`, which have no declaration in the sources): its metadata view. Empty for other symbols, and while the language
     * server is ready: its decompiled source has the bodies, and a name the tree leaves unresolved goes on to it. [from] is the file of the
     * name: one the server has not loaded is ours.
     */
    fun targets(project: Project, symbol: CSharpSymbol, from: VirtualFile? = null): List<PsiElement> {
        if (RoslynServerStatus.isReady(project, from)) return emptyList()
        return when (symbol) {
            is CSharpSymbol.LibraryType -> listOfNotNull(original(project, symbol.type, null) ?: decompiled(project, symbol.type, symbol.type.docId) ?: target(project, symbol.type))
            is CSharpSymbol.LibraryMember -> listOfNotNull(original(project, symbol.member.type, symbol.member) ?: decompiled(project, symbol.member.type, symbol.member.docId)
                ?: target(project, symbol.member))
            else -> emptyList()
        }
    }

    /**
     * The original source of [type] or [member] by the PDB of the assembly (Source Link, embedded sources; `LibrarySources`), as Rider goes to it
     * first with the option on: the declaration in the file when it is here already, else a target that gets it in the background and opens it, or
     * the decompiled code / the metadata view when there is none. Null when the option is off or the assembly is known to have no sources. Read action.
     */
    private fun original(project: Project, type: IndexedType, member: IndexedMember?): PsiElement? {
        val assembly = assemblyFile(type.index.mvid) ?: return null
        val docId = member?.docId ?: type.docId
        return LibrarySources.getInstance(project).target(assembly, type.fullName, docId, member?.name ?: type.simpleName) {
            val decompiler = AssemblyDecompiler.getInstance(project)
            if (decompiler.isAvailable) decompiler.open(assembly.path, type.fullName, member?.docId, onFailure = { navigate(project, type, member, true) })
            else navigate(project, type, member, true)
        }
    }

    /**
     * Go to Declaration of the native tree where `NativeCSharpNavigation.targets` gives up: the name [leaf] resolves (C1) to a type or a
     * member of an assembly, which has no declaration in the sources. Null when it is not one, in dumb mode, and while the server is ready.
     */
    fun declarationTargets(leaf: PsiElement): List<PsiElement>? {
        val file = leaf.containingFile as? CSharpFile ?: return null
        val project = file.project
        if (DumbService.isDumb(project) || RoslynServerStatus.isReady(project, file.virtualFile)) return null
        val symbols = CSharpSemanticSession(project).resolver(file).resolve(leaf)?.symbols ?: return null
        return symbols.filter { it.declarations.isEmpty() }.flatMap { targets(project, it, file.virtualFile) }.distinct().ifEmpty { null }
    }

    /**
     * The declaration [docId] in the decompiled code of [type] (bodies, unlike the metadata view) when the decompiler of DotNetHelper has it
     * already; else null and the type is decompiled in the background, so that the next Go to Declaration finds it (the handler cannot wait
     * seconds for the helper). Read action.
     */
    private fun decompiled(project: Project, type: IndexedType, docId: String): PsiElement? {
        // the page of the server decides for the native path too: off, the metadata view as the server's "metadata as source"
        if (!RoslynOptions.isOn("navigation.dotnet_navigate_to_decompiled_sources")) return null
        val decompiler = AssemblyDecompiler.getInstance(project)
        if (!decompiler.isAvailable) return null
        val assembly = assemblyFile(type.index.mvid) ?: return null
        val file = decompiler.cachedFile(assembly.path, type.fullName)
        if (file == null) {
            decompiler.prefetch(assembly.path, type.fullName)
            return null
        }
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        return declarationAt(psi, file.decompiled.offsetOf(docId) ?: 0)
    }

    /** Opens the metadata view of [type] at [member] (or at the type). EDT. */
    fun navigate(project: Project, type: IndexedType, member: IndexedMember?, requestFocus: Boolean) {
        val offset = if (member != null) offset(member) else offset(type)
        OpenFileDescriptor(project, file(type), offset).navigate(requestFocus)
    }

    /**
     * The assemblies to resolve the names of a metadata view against: the ones of a project that refers to its assembly, else the assembly
     * alone; for a decompiled type the ones of a project compiled against its dll. A hook for `CSharpSemanticEnvironment.assemblies` (a
     * metadata file belongs to no project); null for any other file.
     */
    fun assembliesOf(project: Project, file: VirtualFile?): AssemblyIndexSet? {
        // a decompiled type: the names of its text (`List<T>`, `ArgumentNullException`) resolve, so Ctrl+Click goes on from it
        if (file is io.github.dotnetsupport.decompiler.DecompiledFile) return AssemblyIndexService.getInstance(project).symbolsWithAssembly(java.io.File(file.key.assembly))
        val metadata = file as? AssemblyMetadataFile ?: return null
        return AssemblyIndexService.getInstance(project).symbolsWith(metadata.index) ?: AssemblyIndexSet(listOf(metadata.index))
    }

    /** The declaration whose name is at [offset]: the innermost element around the name that says it is there (`getTextOffset`), else the name. */
    private fun element(project: Project, file: AssemblyMetadataFile, offset: Int): PsiElement? =
        PsiManager.getInstance(project).findFile(file)?.let { declarationAt(it, offset) }

    private fun declarationAt(psi: PsiFile, offset: Int): PsiElement {
        val leaf = psi.findElementAt(offset) ?: return psi
        var current: PsiElement? = leaf.parent
        var depth = 0
        while (current != null && current != psi && depth++ < MAX_DEPTH) {
            if (current.textOffset == offset) return current
            current = current.parent
        }
        return leaf
    }

    private const val MAX_DEPTH = 4
}

/** `List.cs [System.Collections 10.0]`: the tab of a metadata view says which assembly it is, as the decompiled sources of the server do. */
class AssemblyMetadataTabTitle : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? =
        (file as? AssemblyMetadataFile)?.let { "${it.name} [${it.assemblyTitle}]" }
}

/** The banner of a metadata view: where it comes from, that it has no bodies and cannot be edited. */
class AssemblyMetadataBanner : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val metadata = file as? AssemblyMetadataFile ?: return null
        val assembly = AssemblyNavigation.assemblyFile(metadata.index.mvid)
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text = "Metadata of ${metadata.index.assemblyName} ${metadata.index.assemblyVersion}: signatures and documentation, no method bodies (no decompiler). Read-only"
                assembly?.takeIf { it.isFile }?.let { dll -> createActionLabel("Show Assembly in ${RevealFileAction.getFileManagerName()}") { RevealFileAction.openFile(dll) } }
            }
        }
    }
}
