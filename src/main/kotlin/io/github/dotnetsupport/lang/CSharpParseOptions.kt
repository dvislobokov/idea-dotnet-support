package io.github.dotnetsupport.lang

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.FileContentUtilCore
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.indexing.FileBasedIndex
import io.github.dotnetsupport.csharp.lang.CSharpLanguageLevel
import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion
import io.github.dotnetsupport.csharp.lang.CSharpPreprocessorSymbols
import io.github.dotnetsupport.msbuild.CompilationModel
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

/**
 * What the native parser takes per file, as Roslyn's `CSharpParseOptions` (CSHARP_PSI_MIGRATION.md, steps 7 and 10): the `#if` symbols
 * ([CSharpPreprocessorSymbols.KEY]) and the language version ([CSharpLanguageLevel.KEY]) of the project, configuration and framework of
 * the toolbar ([CompilationModel]), put on the `VirtualFile` of every C# file. Set when the project opens (every C# file of the project,
 * in smart mode), when a file is opened in an editor (files created later), and again on [CompilationModel.CHANGED] (another framework in
 * the toolbar flips `#if NET48`); the values are computed off the EDT, since the model may read project files, and put on the EDT in one
 * write action together with the reindex and the reparse of the files with PSI ([applyPending]), with the native tree only: the heuristic
 * one ignores both keys. Not in the project (a loose file): no keys, the parser's defaults.
 *
 * Why one write action (0.1.256, seen on a Unity project of 18 projects evaluated one after another): the keys used to be put in the
 * background read action and the PSI parsed again later on the EDT; a highlighting pass in between loaded the AST of a stub-based PSI with
 * the new symbols against its stubs of the old ones — "Stub count (214) doesn't match stubbed node length (215)", an IDE error on files with
 * many `#if`. No read action sees the new keys with the old PSI or the old index now.
 */
@Service(Service.Level.PROJECT)
class CSharpParseOptions(private val project: Project) : Disposable {
    /** What [fill] computed for a file: the keys to put and whether the parse may differ (the stubs are built again then). */
    private class Values(val symbols: Set<String>?, val level: CSharpLanguageVersion?, val reindex: Boolean)

    /**
     * The values [fill] computed that differ from the file's keys and are not put yet. Not the result of the read action: a non-blocking read
     * action is restarted by a write action and cancelled by a newer [refresh]; the files done before a restart keep their values here.
     */
    private val pending = ConcurrentHashMap<VirtualFile, Values>()

    /**
     * Recomputes [files] (null: every C# file of the project) in the background, then reparses the changed ones. The full pass keeps what
     * it has done across the restarts of the read action: a write action cancels it (see [fill]), and a project of hundreds of files
     * would otherwise start over at every save.
     */
    fun refresh(files: Collection<VirtualFile>?) {
        if (project.isDisposed || ApplicationManager.getApplication().isUnitTestMode && !reactInTests) return
        val done: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()
        var task = ReadAction.nonBlocking(Callable { fill(files ?: allFiles(), done) }).expireWith(this)
        if (files == null) task = task.inSmartMode(project).coalesceBy(this)
        task.finishOnUiThread(ModalityState.nonModal()) { applyPending() }.submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * Computes the keys of [files] (a read action) without putting them; the files whose values differ from the keys they have. Whether the
     * parse may differ from the one the index has is decided here too (step 8): the indexer parses with the keys there are at that time, and a
     * stub tree that does not match the AST is an error of the platform. The tree depends on the symbols only through `#if`, on the version
     * only through version-gated syntax. [applyPending] puts what was computed.
     *
     * Checks for cancellation before every file: the project lookup walks the directories and asks every evaluated project, and a
     * non-blocking read action that never checks cannot be interrupted by a write action — the EDT then waits for it (seen live 2026-10-07:
     * freezes of 7–19 s on a repository of 31 projects and 530 files, every thread dump in this pass). Files in [done] are skipped, and
     * every file computed is added to it.
     */
    fun fill(files: Collection<VirtualFile>, done: MutableSet<VirtualFile> = HashSet()): List<VirtualFile> {
        val model = CompilationModel.getInstance(project)
        val native = CSharpSyntaxTrees.nativeTree()
        val changed = ArrayList<VirtualFile>()
        for (file in files) {
            if (file in done) continue
            ProgressManager.checkCanceled()
            if (!file.isValid || project.isDisposed) continue
            val options = model.optionsFor(file)
            val symbols = options?.preprocessorSymbols
            val level = options?.languageVersion?.let(CSharpLanguageVersion::parse)
            if (differs(file, symbols, level)) {
                pending[file] = Values(symbols, level, native && mayParseDifferently(file, level))
                changed += file
            } else {
                // back to what it has (a framework chosen and unchosen before the first apply): nothing to put
                pending.remove(file)
            }
            done += file
        }
        return changed
    }

    private fun mayParseDifferently(file: VirtualFile, level: CSharpLanguageVersion?): Boolean {
        val old = file.getUserData(CSharpLanguageLevel.KEY)
        if (listOf(level, old).any { it != null && it.effective() != CSharpLanguageLevel.IDE_DEFAULT.effective() }) return true
        val text = FileDocumentManager.getInstance().getCachedDocument(file)?.charsSequence ?: runCatching { LoadTextUtil.loadText(file) }.getOrNull() ?: return true
        return CONDITIONAL.containsMatchIn(text)
    }

    /** [fill], then [applyPending], at once: for the tests and for whoever is already in the right place (the EDT, files of an open project). */
    fun update(files: Collection<VirtualFile>) {
        ReadAction.compute<List<VirtualFile>, RuntimeException> { fill(files) }
        applyPending()
    }

    /**
     * On the EDT, in one write action: the keys [fill] computed are put, the files whose parse may differ are reindexed, and what the native
     * tree has parsed (an AST or a stub tree) with the old values is parsed again. Nothing reads in between: no PSI of the old keys meets the
     * new ones, no new PSI meets the old stubs. The PSI is looked up here, not in the read action: a PSI made after it holds the new keys.
     */
    fun applyPending() {
        if (pending.isEmpty() || project.isDisposed) return
        val entries = pending.entries.map { it.key to it.value }
        entries.forEach { (file, values) -> pending.remove(file, values) }
        val native = CSharpSyntaxTrees.nativeTree()
        WriteAction.run<RuntimeException> {
            val reparse = ArrayList<VirtualFile>()
            for ((file, values) in entries) {
                if (!file.isValid || !put(file, values.symbols, values.level) || !native) continue
                if (values.reindex) FileBasedIndex.getInstance().requestReindex(file)
                reparse += file
            }
            withPsi(reparse).takeIf { it.isNotEmpty() }?.let { FileContentUtilCore.reparseFiles(it) }
        }
    }

    private fun withPsi(files: List<VirtualFile>): List<VirtualFile> =
        if (project.isDisposed) emptyList() else PsiManagerEx.getInstanceEx(project).fileManager.let { manager -> files.filter { manager.getCachedPsiFile(it) != null } }

    private fun allFiles(): Collection<VirtualFile> = FileTypeIndex.getFiles(CSharpFileType, GlobalSearchScope.projectScope(project))

    override fun dispose() = Unit

    /** Fills the keys of the files open in editors, of files opened later, and of every C# file of the project again on a change of the model. */
    class Listener(private val project: Project) : CompilationModel.Listener, FileEditorManagerListener {
        override fun optionsChanged(projectFiles: Collection<String>?) = getInstance(project).refresh(null)

        override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
            if (file.fileType == CSharpFileType) getInstance(project).refresh(listOf(file))
        }
    }

    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) = getInstance(project).refresh(null)
    }

    companion object {
        /** In unit tests the background refresh is off unless a test asks: it would reparse the files the other tests share. */
        @Volatile
        var reactInTests: Boolean = false

        private val CONDITIONAL = Regex("""^\s*#\s*(if|elif)\b""", RegexOption.MULTILINE)

        fun getInstance(project: Project): CSharpParseOptions = project.service()

        /** Sets both keys of [file]; true when either changed. [languageVersion] is MSBuild's `LangVersion`; an unknown one is the default. */
        fun put(file: VirtualFile, symbols: Set<String>?, languageVersion: String?): Boolean = put(file, symbols, languageVersion?.let(CSharpLanguageVersion::parse))

        private fun put(file: VirtualFile, symbols: Set<String>?, level: CSharpLanguageVersion?): Boolean {
            val changed = differs(file, symbols, level)
            file.putUserData(CSharpPreprocessorSymbols.KEY, symbols)
            file.putUserData(CSharpLanguageLevel.KEY, level)
            return changed
        }

        private fun differs(file: VirtualFile, symbols: Set<String>?, level: CSharpLanguageVersion?): Boolean =
            file.getUserData(CSharpPreprocessorSymbols.KEY) != symbols || file.getUserData(CSharpLanguageLevel.KEY) != level
    }
}
