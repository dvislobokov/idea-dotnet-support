package io.github.dotnetsupport.frameworks.razor

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.codeanalysis.CodeAnalysisService
import io.github.dotnetsupport.codeanalysis.GeneratedState
import java.io.File

/**
 * The class of a Razor file made, renamed or moved is there at once, as a renamed Unity script is (MsBuildEvaluation.moved): the plugin
 * writes what the SDK's source generator makes of it — its own port of the generator (R3b, the same text as the compiler's on the corpus) —
 * where the helper puts the generator's output, and removes the output of the old path. The C# naming the new class resolves now, not when
 * the helper has listed the files of the project again and run the generators (seconds; 15 s in MudBlazor before 0.1.280); the helper
 * writes the same file again later. Only in projects whose Razor generator the helper ran: their output folder is known.
 */
object RazorGeneratedAhead {
    /** A Razor file that went from [oldPath] (null: made) to [newFile] (null: deleted). */
    class Change(val oldPath: String?, val newFile: VirtualFile?)

    /** The generator's own folder for a project before it made any file: `<output>/<assembly>/<type>`, as the helper names it. */
    private const val GENERATOR_ASSEMBLY = "Microsoft.CodeAnalysis.Razor.Compiler"

    /**
     * The hint name of the generator for [relativePath] (`Components/Pages/Counter.razor` → `Components/Pages/Counter_razor.g.cs`):
     * `RazorSourceGenerator.GetIdentifierFromPath` — a separator stays `/` (doubled ones and a leading one become `_`), any other character
     * that is no letter or digit becomes `_`.
     */
    fun hintName(relativePath: String): String = buildString {
        for ((i, c) in relativePath.withIndex()) {
            val separator = c == '\\' || c == '/'
            when {
                separator && i + 1 < relativePath.length && relativePath[i + 1].let { it == '\\' || it == '/' } -> append('_')
                separator && i > 0 -> append('/')
                !c.isLetterOrDigit() -> append('_')
                else -> append(c)
            }
        }
        append(".g.cs")
    }

    /** Writes the output of [changes] (not on the EDT): the old outputs deleted, the new ones written, the VFS and the highlighting told. */
    fun apply(project: Project, changes: List<Change>) {
        val service = CodeAnalysisService.getInstance(project)
        val touched = LinkedHashSet<File>()
        for (change in changes) {
            change.oldPath?.let { old -> outputOf(service, old)?.let { output -> if (delete(output)) touched += output } }
            val file = change.newFile?.takeIf { it.isValid } ?: continue
            val output = outputOf(service, file.path) ?: continue
            val text = ReadAction.nonBlocking<String?> {
                if (project.isDisposed || !file.isValid) return@nonBlocking null
                (PsiManager.getInstance(project).findFile(file) as? RazorFile)?.let(RazorCSharpDocument::compilerOutput)
            }.executeSynchronously() ?: continue
            write(output, text)
            touched += output
            PluginLog.info(LOG_CATEGORY, "${file.name}: its class written ahead of the generator (${output.name})")
        }
        if (touched.isEmpty()) return
        // the folders only, not recursively: the generator's folder of a project holds a thousand files in MudBlazor.Docs
        val folders = touched.mapNotNull { it.parentFile }.distinct().mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
        if (folders.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, false, true, *folders.toTypedArray())
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("Razor classes written ahead of the generator")
        }, ModalityState.nonModal())
    }

    /** Where the helper puts the generator's output of the Razor file at [path]; null when the helper has not run Razor's generator for its project. */
    fun outputOf(service: CodeAnalysisService, path: String): File? {
        val state = service.generatedStateUnder(path) ?: return null
        if (RazorCodeGenerators.GENERATOR !in state.run.generators || !service.takesFilesLike(path)) return null
        val folder = state.projectPath.replace('\\', '/').substringBeforeLast('/')
        val relative = path.replace('\\', '/').takeIf { it.startsWith("$folder/", ignoreCase = true) }?.substring(folder.length + 1) ?: return null
        return File(generatorFolder(state), hintName(relative))
    }

    private fun generatorFolder(state: GeneratedState): File {
        val assembly = state.run.files.firstOrNull { it.generatorType == RazorCodeGenerators.GENERATOR }?.generatorAssembly ?: GENERATOR_ASSEMBLY
        return File(File(state.output, assembly), RazorCodeGenerators.GENERATOR)
    }

    /** As the helper writes them: UTF-8 without a BOM, read-only (an edit would be lost at the next run). */
    private fun write(output: File, text: String) {
        output.parentFile.mkdirs()
        if (output.exists()) output.setWritable(true)
        output.writeText(text, Charsets.UTF_8)
        output.setReadOnly()
    }

    private fun delete(output: File): Boolean {
        if (!output.exists()) return false
        output.setWritable(true)
        return output.delete()
    }

    private const val LOG_CATEGORY = CodeAnalysisService.LOG_CATEGORY
}

/** Razor files made, deleted, renamed or moved in the IDE: [RazorGeneratedAhead] writes their classes in the background. */
class RazorGeneratedAheadListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed || project.getServiceIfCreated(CodeAnalysisService::class.java)?.generatedCount == 0) return
        val changes = ArrayList<RazorGeneratedAhead.Change>()
        for (event in events) {
            val change = when (event) {
                is VFileCreateEvent -> RazorGeneratedAhead.Change(null, event.file)
                is VFileCopyEvent -> RazorGeneratedAhead.Change(null, event.findCreatedFile())
                is VFileDeleteEvent -> RazorGeneratedAhead.Change(event.path, null)
                is VFileMoveEvent -> RazorGeneratedAhead.Change(event.oldPath, event.file)
                is VFilePropertyChangeEvent -> if (event.isRename) RazorGeneratedAhead.Change(event.oldPath, event.file) else null
                else -> null
            } ?: continue
            if (isRazor(change.oldPath) || isRazor(change.newFile?.path)) changes += change
        }
        if (changes.isEmpty() || project.getServiceIfCreated(CodeAnalysisService::class.java) == null) return
        AppExecutorUtil.getAppExecutorService().execute {
            if (!project.isDisposed) runCatching { RazorGeneratedAhead.apply(project, changes) }.onFailure { PluginLog.warn(CodeAnalysisService.LOG_CATEGORY, "Razor classes ahead of the generator: $it") }
        }
    }

    private fun isRazor(path: String?): Boolean = path != null && (path.endsWith(".razor", ignoreCase = true) || path.endsWith(".cshtml", ignoreCase = true))
}
