package io.github.dotnetsupport.frameworks.razor

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.razor.lang.discovery.tagHelperKey
import io.github.dotnetsupport.razor.lang.syntax.TagHelperCollection
import java.util.concurrent.ConcurrentHashMap

/**
 * The tag helpers and components of the projects with Razor files (RAZOR_PLAN.md, R5d), as the Razor editor asks for them: discovered in the
 * background ([RazorTagHelpers.discover]), kept per project and computed again a moment after a C# or Razor file of the solution changes (a new
 * `[Parameter]`, a new component). The editor takes the last result — the C# document of a Razor file is made without descriptors until the
 * first one is ready — and [tracker] says when a result changed, so the documents made with the old one are made again.
 */
@Service(Service.Level.PROJECT)
class RazorTagHelperService(private val project: Project) : Disposable {
    private class Entry(val projectFile: VirtualFile) {
        @Volatile var result: TagHelperCollection? = null
        @Volatile var key: String? = null
        /** The set of assemblies the result was made with: another one (a restore, another framework) makes it stale. */
        @Volatile var assemblies: Any? = null
        @Volatile var dirty = true
        val running = java.util.concurrent.atomic.AtomicBoolean()
        val cache = RazorTagHelpers.Cache()
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    /** Changes when the descriptors of a project change: what the C# documents of Razor files depend on. */
    val tracker = SimpleModificationTracker()

    init {
        PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
            override fun childrenChanged(event: PsiTreeChangeEvent) = changed(event)
            override fun childAdded(event: PsiTreeChangeEvent) = changed(event)
            override fun childRemoved(event: PsiTreeChangeEvent) = changed(event)
            override fun childReplaced(event: PsiTreeChangeEvent) = changed(event)
            override fun childMoved(event: PsiTreeChangeEvent) = changed(event)
            override fun propertyChanged(event: PsiTreeChangeEvent) = changed(event)
        }, this)
    }

    private fun changed(event: PsiTreeChangeEvent) {
        // a file of the solution: not the light declarations of components the discovery itself makes
        val file = event.file?.takeIf { it.isPhysical } ?: return
        // the markup of a Razor file is its HTML file of the same view: a change there is a change of the Razor file
        if (file !is CSharpFile && file.viewProvider.baseLanguage != RazorLanguage) return
        if (entries.isEmpty()) return
        // a component of one project is used by another: every project's descriptors are stale; computed again when asked
        for (entry in entries.values) entry.dirty = true
        alarm.cancelAllRequests()
        alarm.addRequest({ for (entry in entries.values) if (entry.dirty) schedule(entry) }, DEBOUNCE_MS)
    }

    /** The last descriptors of [projectFile]; null before the first are ready. Asks for new ones when they are stale. */
    fun tagHelpers(projectFile: VirtualFile): TagHelperCollection? {
        // a project file made again at the same path (deleted and restored) is another project
        val entry = entries.compute(projectFile.path) { _, old -> old?.takeIf { it.projectFile == projectFile && it.projectFile.isValid } ?: Entry(projectFile) }!!
        val assemblies = AssemblyIndexService.getInstance(project).symbols(projectFile)
        if (entry.assemblies !== assemblies) entry.dirty = true
        if (entry.dirty) schedule(entry)
        return entry.result
    }

    /** Discovers [entry]'s descriptors in the background — one run at a time: a change during a run makes another after it. */
    private fun schedule(entry: Entry) {
        if (!entry.running.compareAndSet(false, true)) return
        entry.dirty = false
        val projectFile = entry.projectFile
        ReadAction.nonBlocking<Pair<TagHelperCollection, Any>?> {
            if (!projectFile.isValid) return@nonBlocking null
            val assemblies = AssemblyIndexService.getInstance(project).symbols(projectFile)
            val started = System.nanoTime()
            val found = RazorTagHelpers.discover(project, projectFile, cache = entry.cache)
            if (LOG.isDebugEnabled) LOG.debug("tag helpers of ${projectFile.name}: ${found.size} in ${(System.nanoTime() - started) / 1_000_000} ms")
            found to assemblies
        }
            .inSmartMode(project)
            .coalesceBy(this, projectFile)
            .expireWith(this)
            .submit(AppExecutorUtil.getAppExecutorService())
            // applied as soon as it is there (onProcessed may run before a callback on the EDT would, and a run it starts must not lose it)
            .onSuccess { done ->
                val (found, assemblies) = done ?: return@onSuccess
                entry.assemblies = assemblies
                val key = found.joinToString("\u0001") { tagHelperKey(it) }
                if (key == entry.key) return@onSuccess
                entry.result = found
                entry.key = key
                tracker.incModificationCount()
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({ DaemonCodeAnalyzer.getInstance(project).restart() }, ModalityState.any(), project.disposed)
            }
            .onProcessed {
                entry.running.set(false)
                if (entry.dirty && !project.isDisposed) schedule(entry)
            }
    }

    override fun dispose() = Unit

    companion object {
        private val LOG = logger<RazorTagHelperService>()
        private const val DEBOUNCE_MS = 700

        fun getInstance(project: Project): RazorTagHelperService = project.service()
    }
}
