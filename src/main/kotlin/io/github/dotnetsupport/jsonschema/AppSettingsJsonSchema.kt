package io.github.dotnetsupport.jsonschema

import com.google.gson.JsonObject
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.JsonFileType
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonElementVisitor
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.impl.http.FileDownloadingAdapter
import com.intellij.openapi.vfs.impl.http.HttpVirtualFile
import com.intellij.openapi.vfs.impl.http.RemoteFileState
import com.intellij.psi.PsiElementVisitor
import com.intellij.testFramework.LightVirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion
import com.jetbrains.jsonSchema.remote.JsonFileResolver
import io.github.dotnetsupport.appsettings.AppSettingsSchemaListener
import io.github.dotnetsupport.appsettings.AppSettingsSchemaService
import io.github.dotnetsupport.appsettings.AppSettingsSchemas
import io.github.dotnetsupport.cli.PluginLog
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A schema per .NET project for its `appsettings*.json`. The provider of a schema has no file to go by, only `isAvailable(file)`,
 * so each project gets one, and each appsettings file is claimed by the provider of its project. Claiming a file turns SchemaStore's
 * catalog off for it: the schema takes SchemaStore's appsettings schema in itself instead ([AppSettingsSchemaFiles], [AppSettingsSchemas]).
 */
class AppSettingsSchemaProviderFactory : JsonSchemaProviderFactory, DumbAware {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> =
        AppSettingsSchemaService.getInstance(project).projects().map { AppSettingsSchemaProvider(project, it) }
}

class AppSettingsSchemaProvider(private val project: Project, private val projectFile: VirtualFile) : JsonSchemaFileProvider {
    override fun isAvailable(file: VirtualFile): Boolean = AppSettingsSchemaService.getInstance(project).projectOf(file) == projectFile

    override fun getName(): String = "appsettings.json of ${projectFile.nameWithoutExtension}"

    override fun getPresentableName(): String = "appsettings.json (.NET, sections of ${projectFile.nameWithoutExtension} from its code)"

    override fun getSchemaFile(): VirtualFile = project.service<AppSettingsSchemaFiles>().file(projectFile)

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema

    override fun getSchemaVersion(): JsonSchemaVersion = JsonSchemaVersion.SCHEMA_7

    override fun equals(other: Any?): Boolean = other is AppSettingsSchemaProvider && other.projectFile == projectFile
    override fun hashCode(): Int = projectFile.hashCode()
}

/** The schema of each project as a file in memory: a new file when the text changes, so nothing cached for the old one is reused. */
@Service(Service.Level.PROJECT)
class AppSettingsSchemaFiles(private val project: Project) {
    private val files = ConcurrentHashMap<String, LightVirtualFile>()
    @Volatile private var schemaStore: Pair<Long, JsonObject?>? = null
    private val waiting = AtomicBoolean()
    @Volatile private var schemaStoreForTests: JsonObject? = null

    fun file(projectFile: VirtualFile): VirtualFile {
        val text = schema(projectFile).toString()
        files[projectFile.path]?.takeIf { it.content.toString() == text }?.let { return it }
        return LightVirtualFile("appsettings-${projectFile.nameWithoutExtension}.schema.json", JsonFileType.INSTANCE, text).also { files[projectFile.path] = it }
    }

    fun schema(projectFile: VirtualFile): JsonObject = AppSettingsSchemaService.getInstance(project).schema(projectFile, schemaStore())

    /**
     * SchemaStore's appsettings schema as the IDE keeps it (`httpFileSystem` of the system folder, with the proxy of the IDE), when the IDE
     * may go to the network for schemas. The first time it is only asked for: our own base serves until it has come, then the schemas are
     * made again.
     */
    private fun schemaStore(): JsonObject? {
        schemaStoreForTests?.let { return it }
        if (!JsonFileResolver.isRemoteEnabled(project)) return null
        val file = JsonFileResolver.urlToFile(AppSettingsSchemas.SCHEMA_STORE_URL) as? HttpVirtualFile ?: return null
        val info = file.fileInfo ?: return null
        when (info.state) {
            RemoteFileState.DOWNLOADED -> {
                val local = info.localFile ?: return null
                val stamp = local.modificationStamp
                schemaStore?.takeIf { it.first == stamp }?.let { return it.second }
                val parsed = runCatching { AppSettingsSchemas.parseSchema(VfsUtilCore.loadText(local)) }.getOrNull()
                schemaStore = stamp to parsed
                return parsed
            }
            RemoteFileState.DOWNLOADING_NOT_STARTED, RemoteFileState.DOWNLOADING_IN_PROGRESS -> {
                if (waiting.compareAndSet(false, true)) {
                    info.addDownloadingListener(object : FileDownloadingAdapter() {
                        override fun fileDownloaded(localFile: VirtualFile) {
                            info.removeDownloadingListener(this)
                            waiting.set(false)
                            AppSettingsSchemaReset.reset(project)
                        }

                        override fun errorOccurred(errorMessage: String) {
                            info.removeDownloadingListener(this)
                            PluginLog.warn(AppSettingsSchemaService.LOG_CATEGORY, "SchemaStore's appsettings schema could not be downloaded, the plugin's own base is used: $errorMessage")
                        }
                    })
                    JsonFileResolver.startFetchingHttpFileIfNeeded(file, project)
                }
                return null
            }
            else -> return null
        }
    }

    @TestOnly
    fun useSchemaStore(schema: JsonObject, disposable: Disposable) {
        schemaStoreForTests = schema
        Disposer.register(disposable) { schemaStoreForTests = null }
    }
}

/** The code of a project has given another schema: the JSON support makes its list of schemas again and highlights anew. */
class AppSettingsSchemaReset(private val project: Project) : AppSettingsSchemaListener {
    override fun schemaChanged(projectFile: VirtualFile) = reset(project)

    companion object {
        fun reset(project: Project) {
            val application = ApplicationManager.getApplication()
            val run = { if (!project.isDisposed) JsonSchemaService.Impl.get(project).reset() }
            if (application.isDispatchThread) run() else application.invokeLater(run, project.disposed)
        }
    }
}

/** An appsettings file of a project nobody asked about yet (not in an open solution): its project gets a schema from now on. */
class AppSettingsFileOpened(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val service = AppSettingsSchemaService.getInstance(project)
        val projectFile = service.projectOf(file) ?: return
        if (service.activate(projectFile)) AppSettingsSchemaReset.reset(project)
    }
}

/**
 * A key inside a section whose C# class is known, which that class does not have: a typo, or a key nothing reads. Weak: the schema
 * says what the code binds, while the file may well feed something else too (another library, `IConfiguration` read by hand).
 */
class AppSettingsUnknownKeyInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? JsonFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val virtualFile = file.originalFile.virtualFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val service = AppSettingsSchemaService.getInstance(holder.project)
        val projectFile = service.projectOf(virtualFile) ?: return PsiElementVisitor.EMPTY_VISITOR
        if (service.codeSchema(projectFile) == null) return PsiElementVisitor.EMPTY_VISITOR
        val schema = holder.project.service<AppSettingsSchemaFiles>().schema(projectFile)
        return object : JsonElementVisitor() {
            override fun visitProperty(property: JsonProperty) {
                val path = ArrayList<String?>()
                var element = property.parent?.parent
                while (element != null && element !is JsonFile) {
                    when (element) {
                        is JsonProperty -> path += element.name
                        is JsonArray -> path += null
                    }
                    element = element.parent
                }
                path.reverse()
                val type = AppSettingsSchemas.unknownKey(schema, path, property.name) ?: return
                holder.registerProblem(property.nameElement, "${type.substringAfterLast('.')} has no property ${property.name}")
            }
        }
    }
}
