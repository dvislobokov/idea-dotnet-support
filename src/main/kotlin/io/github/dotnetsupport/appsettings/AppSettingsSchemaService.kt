package io.github.dotnetsupport.appsettings

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import io.github.dotnetsupport.cli.HelperConnection
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.msbuild.ProjectContent
import io.github.dotnetsupport.nuget.NuGetHelper
import io.github.dotnetsupport.solution.SolutionService
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The schema of the appsettings files of a project has changed: the JSON part of the plugin (module `jsonschema`) resets the schemas. */
fun interface AppSettingsSchemaListener {
    fun schemaChanged(projectFile: VirtualFile)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<AppSettingsSchemaListener> = Topic(AppSettingsSchemaListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** Where the sections bound in code come from: DotNetHelper, a fake in the tests. Blocking; null when there is no answer. */
fun interface CodeSchemaSource {
    fun compute(files: List<String>, overlays: Map<String, String>): JsonElement?
}

/**
 * The schema of `appsettings*.json` per .NET project ([AppSettingsSchemas]): the code part is asked of DotNetHelper in the background
 * and kept until a `.cs` file of the project (or of a project it references) changes — on disk or in an editor, without saving —
 * after which it is asked again, a moment later, once for a burst of changes. Only projects whose appsettings the JSON support of the
 * IDE has asked about are followed ([activate]); without the JSON plugin nothing here runs.
 */
@Service(Service.Level.PROJECT)
class AppSettingsSchemaService(private val project: Project) : Disposable {
    /** A merged schema and what it was made of: made again only when a part is another object. Shared: not to be changed. */
    private class Merged(val code: CodeSchema?, val packages: List<AppSettingsSchemas.PackageSchema>, val schemaStore: JsonObject?, val schema: JsonObject)

    private val active = ConcurrentHashMap.newKeySet<VirtualFile>()
    private val code = ConcurrentHashMap<String, CodeSchema>()
    private val packages = ConcurrentHashMap<String, Pair<Long, AppSettingsSchemas.PackageSchema?>>()
    private val merged = ConcurrentHashMap<String, Merged>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val failures = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var source: CodeSchemaSource = if (ApplicationManager.getApplication().isUnitTestMode) CodeSchemaSource { _, _ -> null } else HelperSource

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (active.isNotEmpty() && events.any { relevant(it.path) }) schedule(DISK_DELAY_MS)
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (active.isEmpty()) return
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.extension.equals("cs", ignoreCase = true)) schedule(TYPING_DELAY_MS)
            }
        }, this)
    }

    private fun relevant(path: String): Boolean =
        path.endsWith(".cs", ignoreCase = true) || path.endsWith("proj", ignoreCase = true) || path.endsWith("project.assets.json", ignoreCase = true)

    /** The .NET project an appsettings file belongs to; null for any other file. */
    fun projectOf(file: VirtualFile): VirtualFile? =
        if (!file.isDirectory && AppSettingsSchemas.isAppSettings(file.name)) DotNetProjects.findOwningProject(file) else null

    /** Projects to give a schema to: the ones already asked about, and the projects of the open solutions that have appsettings files. */
    fun projects(): Set<VirtualFile> {
        val result = LinkedHashSet<VirtualFile>(active.filter { it.isValid })
        val solutions = SolutionService.getInstance(project)
        for (solutionFile in solutions.solutionFiles()) {
            for (entry in solutions.solution(solutionFile).allProjects) {
                val file = solutionFile.parent?.findFileByRelativePath(entry.path) ?: continue
                if (file.parent?.children?.any { !it.isDirectory && AppSettingsSchemas.isAppSettings(it.name) } == true) result += file
            }
        }
        return result
    }

    /** Starts following [projectFile]; true when it was not followed yet (the list of schemas of the IDE has to be made again then). */
    fun activate(projectFile: VirtualFile): Boolean {
        if (!active.add(projectFile)) return false
        alarm.addRequest({ refresh(projectFile) }, 0)
        return true
    }

    /**
     * The schema of the appsettings files of [projectFile] with [schemaStore] (SchemaStore's appsettings schema, when the IDE has it) as
     * the base. Shared between callers: not to be changed.
     */
    fun schema(projectFile: VirtualFile, schemaStore: JsonObject?): JsonObject {
        activate(projectFile)
        val codeSchema = code[projectFile.path]
        val packageSchemas = packageSchemas(projectFile)
        merged[projectFile.path]?.takeIf {
            it.code === codeSchema && it.schemaStore === schemaStore && it.packages.size == packageSchemas.size && it.packages.indices.all { i -> it.packages[i] === packageSchemas[i] }
        }?.let { return it.schema }
        return AppSettingsSchemas.merge(codeSchema?.schema, packageSchemas, schemaStore).also { merged[projectFile.path] = Merged(codeSchema, packageSchemas, schemaStore, it) }
    }

    /** What the code of [projectFile] binds, as last computed; null until the helper has answered. */
    fun codeSchema(projectFile: VirtualFile): CodeSchema? = code[projectFile.path]

    private fun packageSchemas(projectFile: VirtualFile): List<AppSettingsSchemas.PackageSchema> {
        val assets = SolutionService.getInstance(project).assets(projectFile)
        return AppSettingsSchemas.packageSchemaFiles(assets).mapNotNull { (id, file) ->
            val stamp = file.lastModified()
            val cached = packages[file.path]
            if (cached != null && cached.first == stamp) return@mapNotNull cached.second
            val parsed = runCatching { AppSettingsSchemas.parsePackageSchema(id, file.readText()) }.getOrNull()
            packages[file.path] = stamp to parsed
            parsed
        }
    }

    private fun schedule(delayMs: Int) {
        alarm.cancelAllRequests()
        alarm.addRequest({ active.toList().forEach(::refresh) }, delayMs)
    }

    /** Asks for the code part of [projectFile] again, blocking; tells the listeners when the schema has changed. Not for the EDT. */
    fun refresh(projectFile: VirtualFile) {
        if (project.isDisposed || !projectFile.isValid) return
        val files = sourceFiles(projectFile)
        val paths = files.mapTo(HashSet()) { it.path }
        val overlays = runReadAction {
            FileDocumentManager.getInstance().unsavedDocuments.mapNotNull { document ->
                val file = FileDocumentManager.getInstance().getFile(document) ?: return@mapNotNull null
                if (file.path in paths) File(file.path).path to document.text else null
            }.toMap()
        }
        val answer = try {
            source.compute(files.map { File(it.path).path }, overlays)
        } catch (e: HelperException) {
            if (failures.add(e.message.orEmpty())) PluginLog.warn(LOG_CATEGORY, "The sections of appsettings.json of ${projectFile.name} are not known: ${e.message}")
            return
        }
        val parsed = AppSettingsResponses.parse(answer) ?: return
        val previous = code.put(projectFile.path, parsed)
        if (previous?.schema == parsed.schema) {
            code[projectFile.path] = previous // the same: keep the instance, so the merged schema is not made again
            return
        }
        if (!project.isDisposed) project.messageBus.syncPublisher(AppSettingsSchemaListener.TOPIC).schemaChanged(projectFile)
    }

    /**
     * The `.cs` files of the project as the SDK globs them (what the project file removes left out, bin / obj skipped), and those of the
     * projects it references, whose types the options classes may use.
     */
    fun sourceFiles(projectFile: VirtualFile): List<VirtualFile> {
        val result = LinkedHashSet<VirtualFile>()
        val seen = HashSet<VirtualFile>()
        fun collect(file: VirtualFile, depth: Int) {
            if (!seen.add(file) || depth > MAX_REFERENCE_DEPTH) return
            val root = file.parent ?: return
            val msBuild = SolutionService.getInstance(project).msBuildProject(file)
            val content = ProjectContent(msBuild)
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
                override fun visitFile(child: VirtualFile): Boolean {
                    if (child == root) return true
                    val relative = VfsUtilCore.getRelativePath(child, root, '/') ?: return false
                    if (child.isDirectory) {
                        return child.name.lowercase() !in SKIPPED && !content.isExcludedDirectory(relative) &&
                            child.children.none { DotNetProjects.isProjectFile(it) } // another project's folder is its own
                    }
                    if (child.extension.equals("cs", ignoreCase = true) && !content.isExcluded(relative)) result += child
                    return true
                }
            })
            for (reference in msBuild.projectReferences) root.findFileByRelativePath(reference.replace('\\', '/'))?.let { collect(it, depth + 1) }
        }
        collect(projectFile, 0)
        return result.toList()
    }

    @TestOnly
    fun useSource(fake: CodeSchemaSource, disposable: Disposable) {
        val old = source
        source = fake
        Disposer.register(disposable) { source = old; active.clear(); code.clear(); merged.clear() }
    }

    override fun dispose() {}

    /** DotNetHelper (`helpers/dotnethelper/AppSettings.cs`), started on the first request and kept running. */
    private object HelperSource : CodeSchemaSource {
        override fun compute(files: List<String>, overlays: Map<String, String>): JsonElement =
            service<AppSettingsHelper>().request(files, overlays)
    }

    companion object {
        const val LOG_CATEGORY = "appsettings"
        private const val DISK_DELAY_MS = 500
        private const val TYPING_DELAY_MS = 1_000
        private const val MAX_REFERENCE_DEPTH = 8
        private val SKIPPED = setOf("bin", "obj", "node_modules", ".git", ".vs", ".idea")

        fun getInstance(project: Project): AppSettingsSchemaService = project.service()
    }
}

/** The schemas by DotNetHelper: the same process as the NuGet client and the IL viewer ([NuGetHelper.connection]), one per IDE. */
@Service(Service.Level.APP)
class AppSettingsHelper {
    private val connection get() = NuGetHelper.getInstance().connection

    fun request(files: List<String>, overlays: Map<String, String>): JsonElement {
        val params = JsonObject().apply {
            add("files", JsonArray().apply { files.forEach(::add) })
            add("overlays", JsonArray().apply { overlays.forEach { (path, text) -> add(JsonObject().apply { addProperty("path", path); addProperty("text", text) }) } })
        }
        return connection.request("appsettingsSchema", params, TIMEOUT_MS)
    }

    private companion object {
        /** The first request builds the helper (seconds) and starts it; parsing a big project takes a second or two. */
        const val TIMEOUT_MS = 120_000L
    }
}
