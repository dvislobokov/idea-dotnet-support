package io.github.dotnetsupport.msbuild

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.solution.SolutionService
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * What a C# file is compiled with ([CompilationOptions]): the project it belongs to, the configuration and the target framework of the
 * toolbar (`Debug | .NET 9.0`; a multi-targeted project without a choice: its first framework, as the debug launch takes it), and what
 * MSBuild makes of them — `DefineConstants` with the symbols of the framework, `LangVersion` with the default of the SDK, the global usings.
 *
 * For csharp-psi (step 10 of `CSHARP_PSI_MIGRATION.md`): a file's `CSharpPreprocessorSymbols.KEY` is [symbolsFor], its
 * `CSharpLanguageLevel.KEY` is `CSharpLanguageVersion.parse(languageVersionFor(file))`; [CHANGED] says when to set them again and
 * reparse. Never blocking: the first answer is read from the project file ([CompilationOptionsReader]); MsBuildHost is asked in the
 * background, and its answer replaces it ([CHANGED] then). Evaluated again when the project, an import of it or a file under it changes
 * on disk ([MsBuildEvaluation.Listener]), on Reload Project, and for another configuration or framework of the toolbar.
 */
@Service(Service.Level.PROJECT)
class CompilationModel(private val project: Project) : Disposable {
    /** One evaluation: the project (normalized, lower case), the global properties with `Configuration`, the framework chosen in the toolbar. */
    private data class Key(val projectPath: String, val globals: Map<String, String>, val selectedFramework: String?)

    /** An answer of MsBuildHost; [options] null: the evaluation failed, the project is read statically until something changes. */
    private class Evaluated(val projectFile: VirtualFile, val options: CompilationOptions?, val imports: Set<String>, @Volatile var stale: Boolean = false)

    private class Static(val stamp: String, val options: CompilationOptions)

    private val evaluated = ConcurrentHashMap<Key, Evaluated>()
    private val statics = ConcurrentHashMap<Key, Static>()
    private val pending: MutableSet<Key> = ConcurrentHashMap.newKeySet()
    /** Paths changed on disk the helper has to forget before it evaluates again (a new file under a project changes its globs). */
    private val invalidated = ConcurrentLinkedQueue<String>()
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("CompilationModel", 1)
    @Volatile private var disposed = false
    @Volatile private var testEvaluator: ((String, Map<String, String>) -> MsBuildEvaluationResult)? = null

    /** The `#if` symbols of [file]; null when it is not in a C# project (the parser keeps its default then). */
    fun symbolsFor(file: VirtualFile): Set<String>? = optionsFor(file)?.preprocessorSymbols

    /** `LangVersion` of [file] for `CSharpLanguageVersion.parse` (`7.3`, `14.0`, `latest`, `default`...); null when it is not in a C# project. */
    fun languageVersionFor(file: VirtualFile): String? = optionsFor(file)?.languageVersion

    fun optionsFor(file: VirtualFile): CompilationOptions? = projectOf(file)?.let(::options)

    /**
     * The C# project [file] is compiled in: the project of its directory (or the one above) when that one does not leave it out, else an
     * evaluated project that lists it (`<Compile Include="..\Shared\X.cs" />`), else still the project of its directory.
     */
    fun projectOf(file: VirtualFile): VirtualFile? = projectOf(file, strict = false)

    /** As [projectOf], but null for a file no project compiles (`<Compile Remove>`, `DefaultItemExcludes`): the solution-wide pass skips it, as the compiler does. */
    fun compiledIn(file: VirtualFile): VirtualFile? = projectOf(file, strict = true)

    private fun projectOf(file: VirtualFile, strict: Boolean): VirtualFile? {
        if (file.isDirectory) return null
        // a file a source generator made, in the caches of the IDE (D4): compiled in the project whose generator made it
        project.getServiceIfCreated(io.github.dotnetsupport.codeanalysis.CodeAnalysisService::class.java)?.projectOfGenerated(file)?.let { return it }
        val owning = DotNetProjects.findOwningProject(file)?.takeIf { it.extension.equals("csproj", ignoreCase = true) }
        val path = EvaluatedFiles.normalize(file.path)
        if (owning != null) {
            val compiles = options(owning).compilesNormalized(path)
            val relative = VfsUtilCore.getRelativePath(file, owning.parent, '/')
            val included = compiles ?: (relative == null || !ProjectContent(SolutionService.getInstance(project).msBuildProject(owning)).isExcluded(relative))
            if (included) return owning
        }
        val linking = evaluated.values.firstOrNull { it.projectFile != owning && it.projectFile.isValid && it.options?.compilesNormalized(path) == true }?.projectFile
        return linking ?: owning.takeUnless { strict }
    }

    /** The options of [projectFile] in the configuration and framework of the toolbar: the evaluated ones once MsBuildHost has answered. */
    fun options(projectFile: VirtualFile): CompilationOptions {
        val key = key(projectFile)
        val known = evaluated[key]
        if (known == null || known.stale) schedule(projectFile, key)
        evaluated[key]?.options?.let { return it }
        return staticOptions(projectFile, key)
    }

    /** The framework of the toolbar counts for a project that targets it among others (or whose frameworks come from elsewhere, an import). */
    private fun key(projectFile: VirtualFile): Key {
        val selected = DotNetBuildSettings.getInstance(project).framework?.takeIf { framework ->
            val frameworks = SolutionService.getInstance(project).msBuildProject(projectFile).targetFrameworks
            frameworks.isEmpty() || (frameworks.size > 1 && frameworks.any { it.equals(framework, ignoreCase = true) })
        }
        return Key(normalizedKey(projectFile.path), MsBuildEvaluation.getInstance(project).globals(), selected)
    }

    private fun staticOptions(projectFile: VirtualFile, key: Key): CompilationOptions {
        val props = directoryBuildProps(projectFile)
        val stamp = "${stamp(projectFile)}:${props?.path}:${props?.let(::stamp)}"
        statics[key]?.takeIf { it.stamp == stamp }?.let { return it.options }
        val options = CompilationOptionsReader.read(
            CompilationOptionsReader.Input(
                projectPath = projectFile.path,
                projectText = text(projectFile),
                directoryBuildProps = props?.let(::text),
                configuration = key.globals["Configuration"] ?: DotNetBuildSettings.DEFAULT_CONFIGURATION,
                globalProperties = key.globals.filterKeys { it != "Configuration" },
                selectedFramework = key.selectedFramework,
            ),
        )
        statics[key] = Static(stamp, options)
        return options
    }

    private fun schedule(projectFile: VirtualFile, key: Key) {
        if (disposed) return
        val evaluator = testEvaluator
        if (evaluator == null && (ApplicationManager.getApplication().isUnitTestMode || !TrustedProjects.isProjectTrusted(project))) return
        if (!pending.add(key)) return
        val job = Runnable {
            try {
                evaluate(projectFile, key, evaluator)
            } finally {
                pending.remove(key)
            }
        }
        // a test gets the answer at once
        if (evaluator != null && ApplicationManager.getApplication().isUnitTestMode) job.run() else executor.execute(job)
    }

    private fun evaluate(projectFile: VirtualFile, key: Key, testEvaluator: ((String, Map<String, String>) -> MsBuildEvaluationResult)?) {
        if (disposed || !projectFile.isValid) return
        val evaluation = MsBuildEvaluation.getInstance(project)
        val evaluate = testEvaluator ?: { path, globals -> evaluation.evaluate(path, globals, CompilationOptions.PROPERTIES, CompilationOptions.ITEM_TYPES, targets = CompilationOptions.TARGETS) }
        val previous = evaluated[key]
        val result = try {
            if (testEvaluator == null) generateSequence { invalidated.poll() }.toList().takeIf { it.isNotEmpty() }?.let(evaluation::invalidate)
            val outer = evaluate(projectFile.path, key.globals)
            if (outer.targetFrameworks.size > 1 && outer.property("TargetFramework") == null) {
                // the outer evaluation of a multi-targeted project knows no framework: the one of the toolbar, else the first, as the debug launch
                val active = key.selectedFramework?.let { s -> outer.targetFrameworks.firstOrNull { it.equals(s, ignoreCase = true) } } ?: outer.targetFrameworks.first()
                evaluate(projectFile.path, key.globals + ("TargetFramework" to active)).let { if (it.targetFrameworks.isEmpty()) it.copy(targetFrameworks = outer.targetFrameworks) else it }
            } else outer
        } catch (e: HelperException) {
            PluginLog.warn(MsBuildEvaluation.LOG_CATEGORY, "${projectFile.name}: the options of the compiler are read from the project file, its evaluation failed: ${e.message}")
            evaluated[key] = Evaluated(projectFile, null, previous?.imports.orEmpty())
            return
        }
        val options = CompilationOptions.fromEvaluation(projectFile.path, key.globals["Configuration"] ?: DotNetBuildSettings.DEFAULT_CONFIGURATION, result)
        evaluated[key] = Evaluated(projectFile, options, result.imports.mapTo(HashSet()) { normalizedKey(it) })
        if (previous?.options != options && !project.isDisposed) project.messageBus.syncPublisher(CHANGED).optionsChanged(listOf(projectFile.path))
    }

    /**
     * Files have changed on disk ([MsBuildEvaluation.changed]): the evaluations that depend on them are made again when next asked. True
     * when there was one: MsBuildHost has to forget them too.
     */
    fun changed(paths: Collection<String>): Boolean {
        if (evaluated.isEmpty() || paths.isEmpty()) return false
        val keys = paths.map(::normalizedKey)
        var any = false
        for ((key, value) in evaluated) {
            if (!value.stale && keys.any { MsBuildEvaluation.dependsOn(key.projectPath, value.imports, it) }) {
                value.stale = true
                any = true
            }
        }
        if (any) invalidated += paths
        return any
    }

    /** Reload Project / Solution: what was known of [projectFile] (of every project for null) is forgotten. */
    fun reload(projectFile: VirtualFile?) {
        if (projectFile == null) {
            evaluated.keys.forEach { invalidated += it.projectPath }
            evaluated.clear()
            statics.clear()
        } else {
            val path = normalizedKey(projectFile.path)
            evaluated.keys.removeIf { it.projectPath == path }
            statics.keys.removeIf { it.projectPath == path }
            invalidated += projectFile.path
        }
        if (!project.isDisposed) project.messageBus.syncPublisher(CHANGED).optionsChanged(projectFile?.let { listOf(it.path) })
    }

    override fun dispose() {
        disposed = true
        evaluated.clear()
        statics.clear()
    }

    /** Answers MsBuildHost would give, for the tests (the helper never runs in them); evaluated at once, on the thread that asks. */
    @TestOnly
    fun setEvaluatorForTests(evaluator: ((projectPath: String, globals: Map<String, String>) -> MsBuildEvaluationResult)?) {
        testEvaluator = evaluator
        evaluated.clear()
        statics.clear()
    }

    /** Told when the options of projects may have changed: an answer of MsBuildHost came, a reload, another configuration or framework. */
    fun interface Listener {
        /** [projectFiles] null: every project. */
        fun optionsChanged(projectFiles: Collection<String>?)
    }

    companion object {
        @JvmField val CHANGED: Topic<Listener> = Topic.create("DotNet compilation options changed", Listener::class.java)

        fun getInstance(project: Project): CompilationModel = project.service()

        private fun normalizedKey(path: String): String = EvaluatedFiles.normalize(path).lowercase()

        /** The nearest `Directory.Build.props` above the project (MSBuild's `GetDirectoryNameOfFileAbove` from the project directory). */
        internal fun directoryBuildProps(projectFile: VirtualFile): VirtualFile? =
            generateSequence(projectFile.parent) { it.parent }.firstNotNullOfOrNull { it.findChild("Directory.Build.props")?.takeIf { file -> !file.isDirectory } }

        private fun stamp(file: VirtualFile): Long = FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: file.modificationStamp

        private fun text(file: VirtualFile): CharSequence = FileDocumentManager.getInstance().getCachedDocument(file)?.immutableCharSequence ?: try {
            VfsUtilCore.loadText(file)
        } catch (_: IOException) {
            ""
        }
    }
}
