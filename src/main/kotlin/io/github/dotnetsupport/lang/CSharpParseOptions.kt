package io.github.dotnetsupport.lang

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.fileEditor.FileEditorManagerListener
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
 * the toolbar flips `#if NET48`); off the EDT, since the model may read project files. The files whose values changed and that have PSI
 * are parsed again on the EDT, with the native tree only: the heuristic one ignores both keys. Not in the project (a loose file): no keys,
 * the parser's defaults.
 */
@Service(Service.Level.PROJECT)
class CSharpParseOptions(private val project: Project) : Disposable {
    /**
     * The files [fill] changed that are not parsed again yet. Not the result of the read action: [fill] has side effects (the keys, the reindex),
     * and a non-blocking read action is restarted by a write action and cancelled by a newer [refresh] after them; the run that finishes sees the
     * keys already set, nothing changed, and the PSI built with the old keys stayed against the new index (Go to Class gave `Net9Only` for
     * `Net10Only`).
     */
    private val pending: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()

    /** Recomputes [files] (null: every C# file of the project) in the background, then reparses the changed ones. */
    fun refresh(files: Collection<VirtualFile>?) {
        if (project.isDisposed || ApplicationManager.getApplication().isUnitTestMode && !CSharpSyntaxTreeSwitch.reactInTests) return
        var task = ReadAction.nonBlocking(Callable { fill(files ?: allFiles()) }).expireWith(this)
        if (files == null) task = task.inSmartMode(project).coalesceBy(this)
        task.finishOnUiThread(ModalityState.nonModal()) { reparsePending() }.submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * Puts the keys on [files] (a read action); the ones whose values changed. Their stubs are built again where the parse may differ from the
     * one the index has (step 8): the indexer parses with the keys there are at that time, and a stub tree that does not match the AST is an
     * error of the platform. The tree depends on the symbols only through `#if`, on the version only through version-gated syntax.
     */
    fun fill(files: Collection<VirtualFile>): List<VirtualFile> {
        val model = CompilationModel.getInstance(project)
        val changed = files.filter { file -> file.isValid && !project.isDisposed && put(file, model.symbolsFor(file), model.languageVersionFor(file)) }
        if (CSharpSyntaxTrees.nativeTree()) changed.filter(::mayParseDifferently).forEach(FileBasedIndex.getInstance()::requestReindex)
        pending.addAll(changed)
        return changed
    }

    private fun mayParseDifferently(file: VirtualFile): Boolean {
        val level = file.getUserData(CSharpLanguageLevel.KEY)
        if (level != null && level.effective() != CSharpLanguageLevel.IDE_DEFAULT.effective()) return true
        val text = FileDocumentManager.getInstance().getCachedDocument(file)?.charsSequence ?: runCatching { LoadTextUtil.loadText(file) }.getOrNull() ?: return true
        return CONDITIONAL.containsMatchIn(text)
    }

    /** [fill], then [reparsePending], at once: for the tests and for whoever is already in the right place (the EDT, files of an open project). */
    fun update(files: Collection<VirtualFile>) {
        ReadAction.compute<List<VirtualFile>, RuntimeException> { fill(files) }
        reparsePending()
    }

    /**
     * On the EDT: what the native tree has parsed (an AST or a stub tree) with the old values is parsed again, every file [fill] changed since
     * the last call. The PSI is looked up here, not in the read action: a PSI made after it holds the new keys and the reindexed stubs.
     */
    fun reparsePending() {
        val files = pending.toList().also(pending::removeAll)
        if (files.isEmpty() || project.isDisposed || !CSharpSyntaxTrees.nativeTree()) return
        withPsi(files.filter { it.isValid }).takeIf { it.isNotEmpty() }?.let { FileContentUtilCore.reparseFiles(it) }
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
        private val CONDITIONAL = Regex("""^\s*#\s*(if|elif)\b""", RegexOption.MULTILINE)

        fun getInstance(project: Project): CSharpParseOptions = project.service()

        /** Sets both keys of [file]; true when either changed. [languageVersion] is MSBuild's `LangVersion`; an unknown one is the default. */
        fun put(file: VirtualFile, symbols: Set<String>?, languageVersion: String?): Boolean {
            val level = languageVersion?.let(CSharpLanguageVersion::parse)
            val changed = file.getUserData(CSharpPreprocessorSymbols.KEY) != symbols || file.getUserData(CSharpLanguageLevel.KEY) != level
            file.putUserData(CSharpPreprocessorSymbols.KEY, symbols)
            file.putUserData(CSharpLanguageLevel.KEY, level)
            return changed
        }
    }
}
