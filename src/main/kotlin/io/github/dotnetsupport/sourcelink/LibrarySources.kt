package io.github.dotnetsupport.sourcelink

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.vfs.WritingAccessProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.util.io.HttpRequests
import io.github.dotnetsupport.cli.DotNetHelper
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynOptions
import io.github.dotnetsupport.nuget.NuGetHelper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.JComponent

/** Where a library source came from: the [url] of Source Link, or embedded in the PDB of [assembly] (url null); [document] as the PDB names it. */
data class LibrarySourceOrigin(val url: String?, val assembly: String, val assemblyName: String, val document: String) {
    val isEmbedded: Boolean get() = url == null
}

/**
 * The original source of a type of a library in the editor, as Rider's "Navigated to source from Source Link": C# (colors, folding, navigation
 * inside), read-only, of its own file system ([LibrarySourceFileSystem]) so that its URL leads back to it after the tab is closed or the IDE
 * restarted ([LibrarySourceFiles] keeps the text on disk). [key] is `<algorithm>-<hash>` of the document in the PDB: the same file of
 * two packages is one file.
 */
class LibrarySourceFile(val key: String, val origin: LibrarySourceOrigin, text: String) :
    LightVirtualFile(origin.document.substringAfterLast('/').substringAfterLast('\\').ifEmpty { "Source.cs" }, CSharpFileType, text) {
    init {
        isWritable = false
    }

    override fun getFileSystem(): VirtualFileSystem = LibrarySourceFileSystem.getInstance() ?: super.getFileSystem()
    override fun getPath(): String = "$key/$name"
    override fun getUrl(): String = VirtualFileManager.constructUrl(LibrarySourceFileSystem.PROTOCOL, path)
    override fun getPresentableUrl(): String = origin.url ?: "${origin.assemblyName}: ${origin.document}"
    override fun getParent(): VirtualFile? = null
    override fun toString(): String = "LibrarySourceFile($path)"
}

/**
 * The library sources the IDE has: in memory (one instance per key, so an editor and the history agree) and on disk under the caches of the
 * IDE (`dotnet-support/sources/<key>.cs` with `<key>.json` saying where it came from): a file downloaded once by its hash is never downloaded
 * again, whatever assembly asks for it.
 */
object LibrarySourceFiles {
    private val memory = ConcurrentHashMap<String, LibrarySourceFile>()

    @Volatile var diskRoot: File? = null

    private fun disk(): File = diskRoot ?: File(DotNetHelper.root(), "sources")

    /** `sha256-<hex>`: the key of a document. */
    fun key(algorithm: String, hash: String): String = algorithm.lowercase().filter { it.isLetterOrDigit() } + "-" + hash.lowercase().filter { it.isLetterOrDigit() }

    fun find(key: String): LibrarySourceFile? {
        memory[key]?.let { return it }
        val meta = File(disk(), "$key.json").takeIf { it.isFile } ?: return null
        val text = File(disk(), "$key.cs").takeIf { it.isFile } ?: return null
        val origin = runCatching {
            val json = JsonParser.parseString(meta.readText()).asJsonObject
            LibrarySourceOrigin(json.get("url")?.takeIf { it.isJsonPrimitive }?.asString, json.get("assembly").asString, json.get("assemblyName").asString, json.get("document").asString)
        }.getOrNull() ?: return null
        return remember(key, origin, text.readText())
    }

    /** By the path of a file (`<key>/<Name>.cs`), for the file system. */
    fun findByPath(path: String): LibrarySourceFile? = find(path.trimStart('/').substringBefore('/'))

    fun put(key: String, origin: LibrarySourceOrigin, text: String): LibrarySourceFile {
        val file = remember(key, origin, text)
        runCatching {
            val root = disk().apply { mkdirs() }
            File(root, "$key.cs").writeText(text)
            File(root, "$key.json").writeText(JsonObject().apply {
                addProperty("url", origin.url); addProperty("assembly", origin.assembly); addProperty("assemblyName", origin.assemblyName); addProperty("document", origin.document)
            }.toString())
        }.onFailure { PluginLog.warn(LibrarySources.LOG_CATEGORY, "The source $key cannot be cached on disk: ${it.message}") }
        return file
    }

    private fun remember(key: String, origin: LibrarySourceOrigin, text: String): LibrarySourceFile =
        memory[key]?.takeIf { it.content.toString() == text } ?: LibrarySourceFile(key, origin, text).also { memory[key] = it }

    fun clearMemory() = memory.clear()
}

/** `dotnet-source://sha256-<hash>/GzipCompressionProvider.cs`: the files of [LibrarySourceFiles] by their URL. */
class LibrarySourceFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
    override fun getProtocol(): String = PROTOCOL
    override fun findFileByPath(path: String): VirtualFile? = LibrarySourceFiles.findByPath(path)
    override fun refreshAndFindFileByPath(path: String): VirtualFile? = findFileByPath(path)
    override fun refresh(asynchronous: Boolean) = Unit
    override fun isReadOnly(): Boolean = true

    companion object {
        const val PROTOCOL = "dotnet-source"
        fun getInstance(): LibrarySourceFileSystem? = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as? LibrarySourceFileSystem
    }
}

/** A library source is not the code of the project: typing into it asks for nothing and changes nothing. */
class LibrarySourceWritingAccess(@Suppress("unused") private val project: Project) : WritingAccessProvider() {
    override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> = files.filterIsInstance<LibrarySourceFile>()
    override fun isPotentiallyWritable(file: VirtualFile): Boolean = file !is LibrarySourceFile
}

/** `GzipCompressionProvider.cs [Grpc.Net.Common]`: the tab says which assembly the source belongs to, as the decompiled tabs do. */
class LibrarySourceTabTitle : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? = (file as? LibrarySourceFile)?.let { "${it.name} [${it.origin.assemblyName}]" }
}

/** As Rider says above such a file: where it came from and that it cannot be edited; the URL opens in the browser. */
class LibrarySourceBanner : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val origin = (file as? LibrarySourceFile)?.origin ?: return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text = bannerText(origin)
                origin.url?.let { url -> createActionLabel("Open in Browser") { BrowserUtil.browse(url) } }
            }
        }
    }

    companion object {
        fun bannerText(origin: LibrarySourceOrigin): String =
            if (origin.url != null) "Navigated to source from Source Link: ${origin.url}. Read-only"
            else "Embedded source of ${origin.assemblyName}: ${origin.document}. Read-only"
    }
}

/** Who finds the location in the PDB: DotNetHelper ([HelperSourceLocator]), a fake in tests. Blocking, not for the EDT; throws [HelperException]. */
fun interface LibrarySourceLocator {
    fun locate(assembly: String, typeName: String, memberId: String?): SourceLocation
}

/** `sourceLocation` of DotNetHelper (`helpers/dotnethelper/SourceLink.cs`), in the same process as the decompiler and the NuGet client. */
class HelperSourceLocator : LibrarySourceLocator {
    override fun locate(assembly: String, typeName: String, memberId: String?): SourceLocation {
        val params = JsonObject().apply { addProperty("assembly", assembly); addProperty("typeName", typeName); memberId?.let { addProperty("memberId", it) } }
        return SourceLocation.parse(NuGetHelper.getInstance().connection.request("sourceLocation", params, TIMEOUT_MS))
            ?: throw HelperException("the helper gave no source location for $typeName")
    }

    private companion object {
        const val TIMEOUT_MS = 120_000L
    }
}

/**
 * Go to Declaration into the original source of a library, as Rider does with "Navigate to Source Link and embedded sources" on: the PDB of
 * the assembly says which document a type or a member is in and where; an embedded document is taken from the PDB, one with a Source Link
 * URL is downloaded by the HTTP client of the IDE (its proxy settings apply), checked against the hash in the PDB and kept on disk by that
 * hash. Everything is done in the background; what cannot be done (no PDB, no Source Link, no network, a changed file) is remembered for the
 * session, and the caller goes on to the decompiler or the metadata view.
 */
@Service(Service.Level.PROJECT)
class LibrarySources(private val project: Project) {
    @Volatile var locator: LibrarySourceLocator = HelperSourceLocator()
    /** Why a URL is not downloaded ([SourceLinkUrls.refusal]); tests replace the DNS lookup with it. */
    @Volatile var urlRefusal: (url: String) -> String? = { SourceLinkUrls.refusal(it) }
    @Volatile var fetch: (url: String, indicator: ProgressIndicator?) -> ByteArray = { url, indicator ->
        // no redirects: a redirect could lead past the host check of [SourceLinkUrls]; the size is capped as a source file's
        // not `redirectLimit(0)`: HttpRequests rejects it (IllegalArgumentException), and every download failed
        HttpRequests.request(url).productNameAsUserAgent().connectTimeout(CONNECT_TIMEOUT_MS).readTimeout(READ_TIMEOUT_MS).followRedirects(false)
            .connect { request ->
                val code = (request.connection as? java.net.HttpURLConnection)?.responseCode ?: 0
                if (code in 300..399) throw java.io.IOException("redirected ($code), not followed")
                val bytes = request.inputStream.readNBytes(SourceLinkUrls.MAX_BYTES + 1)
                if (bytes.size > SourceLinkUrls.MAX_BYTES) throw java.io.IOException("larger than ${SourceLinkUrls.MAX_BYTES} bytes")
                bytes
            }
    }

    /** The location of each `assembly|docId` once asked; null when there is none. */
    private val locations = ConcurrentHashMap<String, Any>()

    /** An assembly without a PDB or without Source Link (and why): not asked again this session. */
    private val assembliesWithout = ConcurrentHashMap<String, String>()

    /** A URL that could not be downloaded or gave another file: not tried again this session (a timeout at every Go to Declaration would be worse). */
    private val failedUrls = ConcurrentHashMap.newKeySet<String>()

    /** The option of the page Settings | .NET | Language Server, as the server reads it; the native navigation reads it the same way. */
    val isEnabled: Boolean get() = RoslynLanguageServerSettings.getInstance().value(OPTION) == "true"

    /** The helper could not be built in this session: nothing to read PDBs with. */
    private val isAvailable: Boolean get() = NuGetHelper.HELPER.failure == null && !(ApplicationManager.getApplication().isUnitTestMode && locator is HelperSourceLocator)

    /**
     * The declaration of [docId] (a type or a member) of [typeName] of [assembly] for Go to Declaration: the element in the source file when
     * it is there already; else, when the source may be had, a target that gets it in the background and opens it, or [fallback] on failure
     * (null means: nothing to get, go to [fallback] at once). Read action.
     */
    fun target(assembly: File, typeName: String, docId: String, presentableName: String, fallback: () -> Unit): PsiElement? {
        if (!isEnabled || !isAvailable || !assembly.isFile) return null
        val path = assembly.path
        if (assembliesWithout.containsKey(path)) return null
        cached(path, docId)?.let { (file, offset) -> return element(file, offset) }
        return LibrarySourceTarget(project, presentableName) { open(assembly, typeName, docId, fallback) }
    }

    /** What is known already, without the helper or the network: the file and the offset of [docId], null when it has to be got. */
    fun cached(assemblyPath: String, docId: String): Pair<LibrarySourceFile, Int>? {
        val location = locations["$assemblyPath|$docId"] as? SourceLocation ?: return null
        val file = LibrarySourceFiles.find(LibrarySourceFiles.key(location.hashAlgorithm, location.hash)) ?: return null
        return file to offset(file, location, docId)
    }

    /** Gets the source in the background (a progress, cancellable) and opens it at the declaration; [fallback] on the EDT when there is none. */
    fun open(assembly: File, typeName: String, docId: String, fallback: () -> Unit) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Looking for the source of ${typeName.substringAfterLast('.').replace('+', '.')}", true) {
            private var found: Pair<LibrarySourceFile, Int>? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                found = source(assembly, typeName, docId, indicator)
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                val (file, offset) = found ?: return fallback()
                OpenFileDescriptor(project, file, offset).navigate(true)
            }

            override fun onThrowable(error: Throwable) {
                PluginLog.warn(LOG_CATEGORY, "The source of $docId of ${assembly.name} could not be opened: ${PluginLog.describe(error)}")
                if (!project.isDisposed) fallback()
            }
        })
    }

    /**
     * The source file of [docId] and the offset of its declaration, from the cache, the PDB or the network; null when the library has none
     * (remembered: the next Go to Declaration of this assembly does not ask again). Blocking, not for the EDT.
     */
    fun source(assembly: File, typeName: String, docId: String, indicator: ProgressIndicator? = null): Pair<LibrarySourceFile, Int>? {
        val path = assembly.path
        cached(path, docId)?.let { return it }
        if (assembliesWithout.containsKey(path)) return null
        val key = "$path|$docId"
        val location = locations.getOrPut(key) {
            try {
                locator.locate(path, typeName, docId.takeUnless { it.startsWith("T:") })
            } catch (e: HelperException) {
                // no PDB at all is a fact about the assembly; no sequence points of this type may be about the type alone
                if (e.message?.contains("no PDB", ignoreCase = true) == true || e.message?.contains("not a PDB") == true || e.message?.contains("Windows PDB") == true) assembliesWithout[path] = e.message!!
                PluginLog.info(LOG_CATEGORY, "No source location of $docId in ${assembly.name}: ${e.message}")
                NONE
            }
        } as? SourceLocation ?: return null
        val file = text(assembly, location, indicator) ?: return null
        return file to offset(file, location, docId)
    }

    private fun text(assembly: File, location: SourceLocation, indicator: ProgressIndicator?): LibrarySourceFile? {
        val key = LibrarySourceFiles.key(location.hashAlgorithm, location.hash)
        LibrarySourceFiles.find(key)?.let { return it }
        location.embedded?.let { return LibrarySourceFiles.put(key, LibrarySourceOrigin(null, location.assembly, location.assemblyName, location.document), it) }
        val url = location.url
        if (url == null) {
            val why = if (location.sourceLink == null) "its PDB has no Source Link" else "the Source Link of its PDB has no URL for ${location.document}"
            assembliesWithout[assembly.path] = why
            PluginLog.info(LOG_CATEGORY, "No source for ${location.document} of ${assembly.name}: $why (the PDB does not embed it either)")
            return null
        }
        // the URL comes from a PDB of any package: never let it make the IDE talk to the machine itself or to the local network
        urlRefusal(url)?.let { why ->
            PluginLog.warn(LOG_CATEGORY, "The Source Link of ${assembly.name} maps ${location.document} to $url: not followed, $why")
            return null
        }
        if (url in failedUrls) return null
        indicator?.text = "Downloading ${location.fileName}"
        val bytes = try {
            fetch(url, indicator)
        } catch (e: Exception) {
            if (e is ControlFlowException) throw e
            failedUrls += url
            PluginLog.warn(LOG_CATEGORY, "The source of ${location.document} of ${assembly.name} could not be downloaded from $url: ${PluginLog.describe(e)} (not tried again this session)")
            return null
        }
        if (!DocumentChecksums.matches(bytes, location.hashAlgorithm, location.hash)) {
            failedUrls += url
            PluginLog.warn(LOG_CATEGORY, "$url is not the file ${assembly.name} was built from: its ${location.hashAlgorithm} hash differs from the one in the PDB (not shown)")
            return null
        }
        val text = decode(bytes)
        PluginLog.info(LOG_CATEGORY, "The source of ${location.document} of ${assembly.name} came from $url (${bytes.size} bytes)")
        return LibrarySourceFiles.put(key, LibrarySourceOrigin(url, location.assembly, location.assemblyName, location.document), text)
    }

    private fun offset(file: LibrarySourceFile, location: SourceLocation, docId: String): Int {
        // `T:Ns.Outer.Inner` -> `Inner`; `M:Ns.T`1.Describe(...)` -> `T`
        val id = docId.substringAfter(':').substringBefore('(')
        val typeName = (if (docId.startsWith("T:")) id else id.substringBeforeLast('.')).substringAfterLast('.').substringBefore('`')
        val name = if (docId.startsWith("T:")) typeName else DeclarationFinder.memberName(docId, typeName)
        return DeclarationFinder.offset(file.content.toString(), location.line, location.column, name, isType = docId.startsWith("T:")) ?: 0
    }

    private fun element(file: LibrarySourceFile, offset: Int): PsiElement? {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        return declarationAt(psi, offset)
    }

    /** The declaration whose name is at [offset]: the innermost element around the name that says it is there (`getTextOffset`), else the name. */
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

    /** Why nothing is asked of [assembly] again this session; null while it may have sources. For tests and the journal. */
    fun reasonWithout(assembly: File): String? = assembliesWithout[assembly.path]

    fun forget() {
        locations.clear()
        assembliesWithout.clear()
        failedUrls.clear()
    }

    companion object {
        const val LOG_CATEGORY = "sourcelink"
        private const val MAX_DEPTH = 4
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private val NONE = Any()

        /** The option of the page Settings | .NET | Language Server that the server and the plugin both honour. */
        val OPTION = RoslynOptions.ALL.first { it.section == "navigation.dotnet_navigate_to_source_link_and_embedded_sources" }

        fun getInstance(project: Project): LibrarySources = project.service()

        /** UTF-8 with or without a BOM, as the compiler reads a source file; `\r\n` to `\n` as a document of the IDE wants it. */
        fun decode(bytes: ByteArray): String {
            val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
            return String(bytes, start, bytes.size - start, Charsets.UTF_8).replace("\r\n", "\n")
        }
    }
}

/**
 * A target of Go to Declaration that is not there yet: the source of a library that has to be read from the PDB and maybe downloaded, which
 * the handler cannot wait for. Navigating to it does that in the background and opens the file; Ctrl+hover underlines the name meanwhile.
 */
class LibrarySourceTarget(private val targetProject: Project, private val targetName: String, private val go: () -> Unit) : FakePsiElement() {
    override fun getParent(): PsiElement? = null
    override fun getContainingFile(): PsiFile? = null
    override fun getProject(): Project = targetProject
    override fun getName(): String = targetName
    override fun getText(): String = targetName
    override fun isValid(): Boolean = true
    override fun canNavigate(): Boolean = true
    override fun canNavigateToSource(): Boolean = true
    override fun navigate(requestFocus: Boolean) = go()
    override fun toString(): String = "LibrarySourceTarget($targetName)"
}
