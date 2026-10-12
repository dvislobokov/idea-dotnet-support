package io.github.dotnetsupport.ef

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.templates.ItemCreator
import io.github.dotnetsupport.templates.ItemTemplates

/**
 * `IDesignTimeDbContextFactory<T>`: with it `dotnet ef` creates the context itself instead of starting the application,
 * which is the way out when the host cannot start at design time (secrets, a broker, a cache that is not there).
 */
object EfDesignTimeFactory {
    const val TEMPLATE_ID = "designTimeFactory"

    /** The provider-specific parts of the template: the `Use...` call and a connection string of a local database. */
    fun variables(provider: String?, databaseName: String, connectionName: String = "Default"): Map<String, String> {
        val (use, connection) = when (provider?.lowercase()) {
            "npgsql.entityframeworkcore.postgresql" -> "UseNpgsql(connectionString)" to "Host=localhost;Database=$databaseName;Username=postgres;Password=postgres"
            "microsoft.entityframeworkcore.sqlite" -> "UseSqlite(connectionString)" to "Data Source=$databaseName.db"
            "pomelo.entityframeworkcore.mysql" -> "UseMySql(connectionString, ServerVersion.AutoDetect(connectionString))" to "Server=localhost;Database=$databaseName;User=root;Password=root"
            "mysql.entityframeworkcore" -> "UseMySQL(connectionString)" to "Server=localhost;Database=$databaseName;User=root;Password=root"
            "oracle.entityframeworkcore" -> "UseOracle(connectionString)" to "User Id=system;Password=oracle;Data Source=localhost:1521/XEPDB1"
            "microsoft.entityframeworkcore.sqlserver" -> "UseSqlServer(connectionString)" to "Server=localhost;Database=$databaseName;Trusted_Connection=True;TrustServerCertificate=True"
            else -> "UseSqlServer(connectionString) // TODO: the database provider of the project was not recognized" to "Server=localhost;Database=$databaseName;Trusted_Connection=True;TrustServerCertificate=True"
        }
        return mapOf("EF_USE_PROVIDER" to use, "EF_CONNECTION_STRING" to connection, "EF_CONNECTION_NAME" to connectionName)
    }

    /** For the project of [projectFile]: its provider, and the first connection string name of the application that uses it. */
    fun variables(project: Project, projectFile: VirtualFile?): Map<String, String> {
        if (projectFile == null) return variables(null, "app")
        val connectionName = EfProjects.connectionStringReferences(EfProjects.defaultStartupProject(project, projectFile)).firstOrNull()?.substringAfterLast(':')
        return variables(EfProjects.provider(project, projectFile), databaseName(projectFile.nameWithoutExtension), connectionName ?: "Default")
    }

    private val LAYER_NAMES = setOf("data", "persistence", "infrastructure", "dal", "database", "db", "ef", "efcore", "entityframework", "migrations", "storage")

    /** `Company.Shop.Data` -> `shop`: the layer suffixes of a project name say nothing about the database. */
    fun databaseName(projectName: String): String {
        val segments = projectName.split('.').filter { it.isNotBlank() }
        return (segments.lastOrNull { it.lowercase() !in LAYER_NAMES } ?: segments.lastOrNull() ?: "app").lowercase()
    }

    /** Creates `<DbContext>Factory.cs` next to the context and opens it; an existing one is just opened. */
    fun create(project: Project, contextFile: VirtualFile, dbContext: String) {
        val directory = contextFile.parent ?: return
        val existing = directory.findChild("${dbContext}Factory.cs")
        val file = existing ?: try {
            ItemCreator.create(project, directory, ItemTemplates.byId(TEMPLATE_ID), dbContext).firstOrNull()
        } catch (e: Exception) {
            return DotNetCli.notifyError(project, "Design-Time DbContext Factory", e.message.orEmpty())
        }
        if (file != null) FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** For a failed command: finds the class of [dbContext] (or the only context) in the sources of the migrations project. */
    fun createFor(project: Project, migrationsProject: VirtualFile, dbContext: String?) {
        val found = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<Pair<VirtualFile, String>>, RuntimeException>({
            EfSources.dbContextFiles(migrationsProject)
        }, "Looking for DbContext Classes", true, project)
        val wanted = dbContext?.substringAfterLast('.')
        val (file, name) = found.firstOrNull { it.second == wanted } ?: found.singleOrNull()
            ?: return Messages.showInfoMessage(project, "Use the gutter icon of the DbContext class to create its factory.", "Design-Time DbContext Factory")
        create(project, file, name)
    }

}
