package io.github.dotnetsupport.msbuild

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dotnetsupport.build.DotNetBuildOptions
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.build.VisualStudioToolset
import io.github.dotnetsupport.cli.DotNetHelper
import io.github.dotnetsupport.cli.HelperConnection
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.SolutionViewPane
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * Real MSBuild evaluation of the projects (MsBuildHost, `helpers/msbuildhost`): conditions, properties, imports and globs as the SDK
 * evaluates them, where [MsBuildProject] only reads the project file. The helper runs in the directory of the solution, so it gets the
 * SDK `dotnet` resolves there (global.json respected), and stays running for the project; it keeps the evaluations itself and makes them
 * again when the project or one of its imports changes on disk.
 *
 * Asked by
 *  - the debug launch, for `TargetPath` ([targetPath]; blocking, falls back to `dotnet msbuild -getProperty`);
 *  - the Solution view, for the files of a project of the old format ([content]): never blocking, the answer comes in the background
 *    and the tree is refreshed then; until it comes, or when the helper fails, the project is shown as before;
 *  - [CompilationModel], for what the compiler of a C# project is given (`DefineConstants`, `LangVersion`, usings, `Compile` items).
 */
@Service(Service.Level.PROJECT)
class MsBuildEvaluation(private val project: Project) : Disposable {
    /** The files of a project of the old format, evaluated with [globals], while the project file had [stamp]; [files] null: it failed. */
    private class Files(val stamp: Long, val globals: Map<String, String>, val files: EvaluatedFiles?, val imports: Set<String>, @Volatile var stale: Boolean = false)

    private val files = ConcurrentHashMap<String, Files>()
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("MsBuildEvaluation", 2)
    @Volatile private var connection: HelperConnection? = null
    @Volatile private var started = false
    @Volatile private var disposed = false

    @Synchronized
    private fun connection(): HelperConnection {
        if (disposed || project.isDisposed) throw HelperException("the project is closed")
        connection?.let { return it }
        if (ApplicationManager.getApplication().isUnitTestMode) throw HelperException("MsBuildHost is not started in tests")
        return HelperConnection.of(HELPER, LOG_CATEGORY, ::workDirectory).also { connection = it; Disposer.register(this, it) }
    }

    /** Where `dotnet` resolves the SDK the way a build of the solution does: the directory of the solution, else of the project. */
    private fun workDirectory(): String =
        SolutionService.getInstance(project).solutionFiles().firstOrNull()?.parent?.path ?: project.basePath ?: DotNetHelper.root().path

    /** Evaluates [projectPath]; blocking, not for the EDT. Throws [HelperException] with the message of MSBuild. */
    fun evaluate(projectPath: String, globalProperties: Map<String, String>, properties: List<String> = emptyList(), itemTypes: List<String> = emptyList(),
                 timeoutMs: Long = TIMEOUT_MS, targets: List<String> = emptyList()): MsBuildEvaluationResult {
        val connection = connection()
        started = true
        val globals = withVisualStudio(projectPath, globalProperties)
        return MsBuildEvaluationResult.parse(connection.request("evaluate", MsBuildEvaluationResult.request(projectPath, globals, properties, itemTypes, targets), timeoutMs))
    }

    /**
     * A project of the old format imports the targets of Visual Studio by `$(VSToolsPath)` (web applications, WPF); the MSBuild of the SDK
     * has none, so the path of the newest Visual Studio goes in as a global property. Only the `.targets` are read, no task of Visual Studio runs.
     */
    private fun withVisualStudio(projectPath: String, globalProperties: Map<String, String>): Map<String, String> {
        if (!SystemInfo.isWindows || globalProperties.keys.any { it.equals(VS_TOOLS_PATH, ignoreCase = true) }) return globalProperties
        val file = LocalFileSystem.getInstance().findFileByPath(projectPath) ?: return globalProperties
        if (!SolutionService.getInstance(project).msBuildProject(file).isLegacy) return globalProperties
        val vsToolsPath = VisualStudioToolset.vsToolsPath() ?: return globalProperties
        return LinkedHashMap(globalProperties).apply { put(VS_TOOLS_PATH, vsToolsPath) }
    }

    /** The helper forgets the evaluations [paths] can change; blocking, not for the EDT. Nothing to do before it has evaluated anything. */
    fun invalidate(paths: Collection<String>) {
        if (!started || paths.isEmpty()) return
        try {
            connection().request("invalidate", JsonObject().apply { add("paths", JsonArray().apply { paths.forEach(::add) }) })
        } catch (e: HelperException) {
            PluginLog.warn(LOG_CATEGORY, "invalidate: ${e.message}")
        }
    }

    /**
     * The assembly a build of [projectFile] makes, as MSBuild says (an `OutputPath` of a condition, `AssemblyName` from `Directory.Build.props`);
     * null when the helper cannot tell (it failed, or a project with several frameworks is asked without one). Blocking.
     */
    fun targetPath(projectFile: VirtualFile, configuration: String, framework: String?, globalProperties: Map<String, String> = emptyMap()): String? {
        val globals = LinkedHashMap(globalProperties).apply {
            put("Configuration", configuration)
            framework?.let { put("TargetFramework", it) }
        }
        return try {
            evaluate(projectFile.path, globals, listOf("TargetPath")).property("TargetPath")
                ?.takeIf { it.endsWith(".dll", ignoreCase = true) || it.endsWith(".exe", ignoreCase = true) }
                .also { if (it == null) PluginLog.info(LOG_CATEGORY, "${projectFile.name} [${describe(globals)}] has no TargetPath") }
        } catch (e: HelperException) {
            PluginLog.warn(LOG_CATEGORY, "TargetPath of ${projectFile.name} [${describe(globals)}]: ${e.message}")
            null
        }
    }

    /**
     * What of the files on disk is a part of [projectFile], for the Solution view. A project of the old format gets the evaluated items
     * once they are there (asked in the background on the first call); every other project, and an old one until then, is read statically.
     */
    fun content(projectFile: VirtualFile): ProjectContent {
        val parsed = SolutionService.getInstance(project).msBuildProject(projectFile)
        return ProjectContent(parsed, if (parsed.isLegacy) evaluatedFiles(projectFile) else null)
    }

    /** The evaluated files of a project of the old format: the last answer, while a new one is asked for when the project has changed. */
    private fun evaluatedFiles(projectFile: VirtualFile): EvaluatedFiles? {
        val key = projectFile.path
        val known = files[key]
        val globals = globals()
        if (known == null || known.stale || known.stamp != projectFile.modificationStamp || known.globals != globals) schedule(projectFile, globals)
        return known?.files
    }

    /** The global properties the tree evaluates with: the configuration of the toolbar and the global properties of the build options. */
    internal fun globals(): Map<String, String> =
        LinkedHashMap(MsBuildEvaluationResult.globalProperties(DotNetBuildOptions.getInstance(project).state.globalProperties)).apply {
            put("Configuration", DotNetBuildSettings.getInstance(project).configuration)
        }

    private fun schedule(projectFile: VirtualFile, globals: Map<String, String>) {
        // evaluation runs the project's MSBuild logic (imports, property functions): not on its own for a project the user has not trusted
        if (disposed || (ApplicationManager.getApplication().isUnitTestMode && connection == null) || !TrustedProjects.isProjectTrusted(project)) return
        if (!pending.add(projectFile.path)) return
        executor.execute {
            try {
                evaluateFiles(projectFile, globals)
            } finally {
                pending.remove(projectFile.path)
            }
        }
    }

    private fun evaluateFiles(projectFile: VirtualFile, globals: Map<String, String>) {
        if (disposed || !projectFile.isValid) return
        val stamp = projectFile.modificationStamp
        val previous = files[projectFile.path]
        val result = try {
            evaluate(projectFile.path, globals, itemTypes = EvaluatedFiles.ITEM_TYPES)
        } catch (e: HelperException) {
            PluginLog.warn(LOG_CATEGORY, "${projectFile.name} is shown with the files on disk: its evaluation failed: ${e.message}")
            files[projectFile.path] = Files(stamp, globals, null, previous?.imports.orEmpty())
            if (previous?.files != null) refreshTree()
            return
        }
        val evaluated = EvaluatedFiles.of(projectFile.parent.path, result)
        files[projectFile.path] = Files(stamp, globals, evaluated, result.imports.mapTo(HashSet()) { key(it) })
        PluginLog.info(LOG_CATEGORY, "${projectFile.name}: the files of the project are the evaluated items (${result.items.values.sumOf { it.size }}), ${result.milliseconds} ms" +
            if (result.reused) ", the evaluation reused" else "")
        refreshTree()
    }

    private fun refreshTree() {
        ApplicationManager.getApplication().invokeLater({
            ProjectView.getInstance(project).getProjectViewPaneById(SolutionViewPane.ID)?.updateFromRoot(true)
        }, project.disposed)
    }

    /**
     * Files have changed on disk: the projects of the old format that depend on them (the project file, an import, a file appearing under
     * the project, a `Directory.Build.props` above it) are evaluated again, and the helper forgets what it knew of them.
     */
    fun changed(paths: Collection<String>) {
        // the options of the compiler (CompilationModel) make their evaluations again themselves, telling the helper first
        project.getServiceIfCreated(CompilationModel::class.java)?.changed(paths)
        if (files.isEmpty() || paths.isEmpty()) return
        val keys = paths.map(::key)
        val affected = files.filter { (projectPath, known) -> keys.any { dependsOn(projectPath, known, it) } }.keys
        if (affected.isEmpty()) return
        affected.forEach { files[it]?.stale = true }
        executor.execute {
            invalidate(paths)
            for (path in affected) LocalFileSystem.getInstance().findFileByPath(path)?.let { schedule(it, globals()) }
        }
    }

    /** Reload Project: the evaluation of [projectFile] (all of them for null) is made again from the files on disk. */
    fun reload(projectFile: VirtualFile?) {
        project.getServiceIfCreated(CompilationModel::class.java)?.reload(projectFile)
        changed(if (projectFile == null) files.keys.toList() else listOf(projectFile.path))
    }

    override fun dispose() {
        disposed = true
        files.clear()
    }

    /** Puts an answer of the helper for [projectFile] as if it had come from it: for the tests of the tree, which never start the helper. */
    @TestOnly
    fun putForTests(projectFile: VirtualFile, result: MsBuildEvaluationResult?) {
        if (result == null) files.remove(projectFile.path)
        else files[projectFile.path] = Files(projectFile.modificationStamp, globals(), EvaluatedFiles.of(projectFile.parent.path, result), result.imports.mapTo(HashSet()) { key(it) })
    }

    /** Refreshes the evaluations a change of files on disk can affect. */
    class Listener(private val project: Project) : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
            if (project.isDisposed) return
            val evaluation = project.getServiceIfCreated(MsBuildEvaluation::class.java) ?: return
            val paths = events.flatMap { event ->
                when (event) {
                    // the content of a source does not change what the project is made of; of a project, props or targets it does
                    is VFileContentChangeEvent -> if (event.file.extension?.lowercase() in MSBUILD_EXTENSIONS) listOf(event.path) else emptyList()
                    is VFileMoveEvent -> listOf(event.oldPath, event.path)
                    is VFilePropertyChangeEvent -> if (event.isRename) listOf(event.oldPath, event.path) else emptyList()
                    else -> listOf(event.path)
                }
            }
            evaluation.changed(paths)
        }
    }

    companion object {
        /** The category of the journal of the plugin: the helper starting, the evaluations and what failed. */
        const val LOG_CATEGORY = "msbuild"
        private const val TIMEOUT_MS = 60_000L
        private const val VS_TOOLS_PATH = "VSToolsPath"
        private val MSBUILD_EXTENSIONS = setOf("csproj", "fsproj", "vbproj", "proj", "props", "targets")

        val HELPER = DotNetHelper("msbuildhost", "MsBuildHost", "HelperFramework", listOf("Program.cs", "Protocol.cs"))

        fun getInstance(project: Project): MsBuildEvaluation = project.service()

        private fun key(path: String): String = EvaluatedFiles.normalize(path).lowercase()

        private fun describe(globals: Map<String, String>): String = globals.entries.joinToString(";") { "${it.key}=${it.value}" }

        /**
         * Whether a change of [changed] (normalized, lower case) can change the evaluation of [projectPath]: the project itself, one of its
         * imports, a file under its directory but not in `bin` / `obj`, or a props / targets file in a directory above it.
         */
        internal fun dependsOn(projectPath: String, imports: Set<String>, changed: String): Boolean {
            val project = key(projectPath)
            if (changed == project || changed in imports) return true
            val directory = project.substringBeforeLast('/')
            if (changed.startsWith("$directory/")) {
                val inside = changed.substring(directory.length + 1)
                return !(inside.startsWith("bin/") || inside.startsWith("obj/") || inside == "bin" || inside == "obj")
            }
            val changedDirectory = changed.substringBeforeLast('/')
            return changed.substringAfterLast('.') in setOf("props", "targets") && directory.startsWith("$changedDirectory/")
        }

        private fun dependsOn(projectPath: String, known: Files, changed: String): Boolean = dependsOn(projectPath, known.imports, changed)
    }
}
