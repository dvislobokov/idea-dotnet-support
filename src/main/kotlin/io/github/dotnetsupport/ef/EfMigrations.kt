package io.github.dotnetsupport.ef

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetTool
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class EfMigrationStatus { APPLIED, PENDING, UNKNOWN }

/** A migration of the EF Core window: what the sources have, with what the database says about it. */
class EfMigrationRow(val id: String, val name: String, val status: EfMigrationStatus, /** Null: known to the tool only, e.g. removed since. */ val file: EfMigrationFile?)

/** What `migrations list` has answered for one `DbContext`. */
sealed class EfDatabaseStatus {
    data object Loading : EfDatabaseStatus()
    class Loaded(val migrations: List<EfMigration>, /** Null: the tool cannot tell (before EF 8). */ val modelChanged: Boolean?) : EfDatabaseStatus()
    class Failed(val message: String) : EfDatabaseStatus()
}

object EfMigrationsModel {
    /** `DbContext` name -> its migration files; the contexts without migrations are there too, [declared] come from the sources. */
    fun byContext(files: List<EfMigrationFile>, declared: List<String>): Map<String, List<EfMigrationFile>> {
        val result = LinkedHashMap<String, List<EfMigrationFile>>()
        files.groupBy { it.migration.dbContext.orEmpty() }.forEach { (context, migrations) -> result[context] = migrations }
        declared.forEach { result.putIfAbsent(it, emptyList()) }
        return result
    }

    /**
     * Newest first. A file the tool has not listed is a migration added after the status was loaded: pending for sure.
     * A listed migration without a file stays in the list: it may live in another assembly.
     */
    fun rows(files: List<EfMigrationFile>, listed: List<EfMigration>?): List<EfMigrationRow> {
        val byId = files.associateBy { it.migration.id }
        if (listed == null) return files.map { EfMigrationRow(it.migration.id, it.migration.name, EfMigrationStatus.UNKNOWN, it) }.sortedByDescending { it.id }
        val known = listed.map { migration ->
            val status = when (migration.applied) {
                true -> EfMigrationStatus.APPLIED
                false -> EfMigrationStatus.PENDING
                null -> EfMigrationStatus.UNKNOWN
            }
            EfMigrationRow(migration.id, migration.name, status, byId[migration.id])
        }
        val listedIds = listed.mapTo(HashSet()) { it.id }
        val added = files.filter { it.migration.id !in listedIds }.map { EfMigrationRow(it.migration.id, it.migration.name, EfMigrationStatus.PENDING, it) }
        return (known + added).sortedByDescending { it.id }
    }

    /** `20240301090000_AddUsers` -> `2024-03-01 09:00`; null for an id that is not a timestamp. */
    fun timestamp(id: String): String? {
        val digits = id.substringBefore('_')
        if (digits.length != 14 || !digits.all(Char::isDigit)) return null
        return "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)} ${digits.substring(8, 10)}:${digits.substring(10, 12)}"
    }
}

/**
 * The database side of the EF Core window. Asking it means building the startup project and connecting to the database,
 * so it happens on request and after the commands that change the answer, never by itself.
 */
@Service(Service.Level.PROJECT)
class EfMigrationsService(private val project: Project) {
    /** [dbContext] is the short class name, empty for "the only one". */
    data class Key(val projectPath: String, val dbContext: String)

    private val statuses = ConcurrentHashMap<Key, EfDatabaseStatus>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun status(key: Key): EfDatabaseStatus? = statuses[key]

    /** The options the status is asked with: the ones last used in the dialogs for the project. */
    fun context(key: Key): EfContext {
        val settings = EfSettings.getInstance(project).state
        val files = LocalFileSystem.getInstance()
        val startup = settings.startupProjects[key.projectPath]?.takeIf { files.findFileByPath(it) != null }
            ?: files.findFileByPath(key.projectPath)?.let { EfProjects.defaultStartupProject(project, it) }?.path
        return EfContext(
            project = key.projectPath, startupProject = startup, dbContext = key.dbContext.ifEmpty { null },
            configuration = DotNetBuildSettings.getInstance(project).configuration, environment = settings.environment,
        )
    }

    /** [noBuild]: the build output is known to be fresh, e.g. right after `database update`. */
    fun refresh(keys: List<Key>, noBuild: Boolean = false) {
        if (keys.isEmpty()) return
        keys.forEach { statuses[it] = EfDatabaseStatus.Loading }
        changed()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading the status of EF migrations", true) {
            override fun run(indicator: ProgressIndicator) {
                val built = HashSet<String>()
                for (key in keys) {
                    indicator.text2 = File(key.projectPath).nameWithoutExtension + key.dbContext.let { if (it.isEmpty()) "" else " / $it" }
                    // one build per startup project is enough for all its contexts
                    val context = context(key)
                    statuses[key] = if (indicator.isCanceled) EfDatabaseStatus.Failed("Cancelled") else load(context.copy(noBuild = noBuild || !built.add(context.startupProject ?: context.project)), indicator)
                    changed()
                }
            }
        })
    }

    private fun load(context: EfContext, indicator: ProgressIndicator): EfDatabaseStatus {
        val tool = EfTool.find(File(context.project).parentFile) ?: run {
            DotNetTool.EF.offerInstallation(project, "EF Core")
            return EfDatabaseStatus.Failed("dotnet-ef is not installed")
        }
        fun execute(command: EfCommand, options: EfContext): Pair<Int, String> {
            val result = CapturingProcessHandler(tool.commandLine(command, options)).runProcessWithProgressIndicator(indicator, TIMEOUT_MS, true)
            return result.exitCode to result.stdout + "\n" + result.stderr
        }
        return try {
            val (exitCode, output) = execute(EfCommand.ListMigrations(), context)
            val json = EfOutputParser.json(output)
            if (exitCode != 0 || json == null) return EfDatabaseStatus.Failed(failure(output))
            val modelChanged = EfOutputParser.pendingModelChanges(execute(EfCommand.HasPendingModelChanges, context.copy(noBuild = true)).second)
            EfDatabaseStatus.Loaded(EfOutputParser.migrations(output), modelChanged)
        } catch (e: Exception) {
            EfDatabaseStatus.Failed(e.message.orEmpty())
        }
    }

    private fun failure(output: String): String = when (EfOutputParser.diagnose(output)) {
        EfProblem.TOOL_MISSING, EfProblem.TOOL_NOT_RESTORED -> "dotnet-ef is not available"
        EfProblem.DESIGN_PACKAGE_MISSING -> "the startup project does not reference ${EfProjects.DESIGN_PACKAGE}"
        EfProblem.CANNOT_CREATE_CONTEXT -> "the DbContext cannot be created: check the startup project and the environment"
        EfProblem.MULTIPLE_CONTEXTS -> "more than one DbContext"
        EfProblem.NO_CONTEXT -> "no DbContext found by the tool"
        EfProblem.BUILD_FAILED -> "build failed"
        else -> EfOutputParser.errorText(output).lines().lastOrNull { it.isNotBlank() }?.trim() ?: "failed"
    }

    /** A command of the plugin has succeeded: bring the statuses that are shown for its project up to date. */
    fun commandFinished(command: EfCommand, context: EfContext) {
        val shown = statuses.keys.filter { it.projectPath == context.project }
        when (command) {
            // the new migration shows as pending without asking, see EfMigrationsModel.rows
            is EfCommand.AddMigration -> shown.forEach { key -> (statuses[key] as? EfDatabaseStatus.Loaded)?.let { statuses[key] = EfDatabaseStatus.Loaded(it.migrations, false) } }
            is EfCommand.UpdateDatabase, EfCommand.DropDatabase -> return refresh(shown, noBuild = true)
            is EfCommand.RemoveMigration -> return refresh(shown)
            else -> return
        }
        changed()
    }

    private fun changed() = ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) listeners.forEach { it() } }, ModalityState.any())

    companion object {
        private const val TIMEOUT_MS = 5 * 60 * 1000

        fun getInstance(project: Project): EfMigrationsService = project.service()
    }
}
