package io.github.dotnetsupport.ef

/**
 * The options every `dotnet ef` command takes. The migrations project holds the `DbContext` and the migrations,
 * the startup project is the application EF runs to get the configured context; they are the same in small solutions.
 */
data class EfContext(
    val project: String,
    val startupProject: String? = null,
    val dbContext: String? = null,
    val configuration: String? = null,
    val framework: String? = null,
    /** `ASPNETCORE_ENVIRONMENT` / `DOTNET_ENVIRONMENT` of the startup project: picks the `appsettings.*.json`, i.e. the database. */
    val environment: String? = null,
    val noBuild: Boolean = false,
    val verbose: Boolean = false,
    /** Passed to the application after `--`. */
    val applicationArguments: List<String> = emptyList(),
)

sealed class EfCommand(val title: String) {
    data class AddMigration(val name: String, val outputDir: String? = null, val namespace: String? = null) : EfCommand("Add Migration")

    /** [force] reverts the migration in the database first when it is applied there. */
    data class RemoveMigration(val force: Boolean = false) : EfCommand("Remove Last Migration")

    /** [target] is a migration name or id, `0` for "before the first one"; null is the last migration. */
    data class UpdateDatabase(val target: String? = null, val connection: String? = null) : EfCommand("Update Database")

    data class Script(
        val from: String? = null,
        val to: String? = null,
        val idempotent: Boolean = false,
        val noTransactions: Boolean = false,
        val output: String? = null,
    ) : EfCommand("Generate SQL Script")

    /**
     * Reverse engineering: entity classes and a `DbContext` from an existing database. [connection] is a connection string or
     * `Name=ConnectionStrings:Default`, which makes the tool read the configuration of the startup project instead.
     */
    data class Scaffold(
        val connection: String,
        val provider: String,
        val outputDir: String? = null,
        val contextName: String? = null,
        val contextDir: String? = null,
        val tables: List<String> = emptyList(),
        val schemas: List<String> = emptyList(),
        val dataAnnotations: Boolean = false,
        val useDatabaseNames: Boolean = false,
        val noOnConfiguring: Boolean = false,
        val noPluralize: Boolean = false,
        val force: Boolean = false,
    ) : EfCommand("Scaffold DbContext")

    /** An executable that applies the migrations: what a deployment runs instead of the SDK and the sources. */
    data class Bundle(val output: String? = null, val selfContained: Boolean = false, val runtime: String? = null, val force: Boolean = false) : EfCommand("Create Migration Bundle")

    /** Always with `--force`: there is no console to answer the confirmation of the tool in, the IDE asks instead. */
    data object DropDatabase : EfCommand("Drop Database")

    data class ListMigrations(val connection: String? = null, val noConnect: Boolean = false) : EfCommand("List Migrations")
    data object ListContexts : EfCommand("List DbContext Types")
    data object ContextInfo : EfCommand("DbContext Info")

    /** EF 8+: fails when the model has changes that no migration holds. */
    data object HasPendingModelChanges : EfCommand("Check for Model Changes")
}

/** Builds the arguments of `dotnet ef`; pure, so that the dialogs can preview exactly what will run. */
object EfCommandBuilder {
    /** Everything after `dotnet ef`. */
    fun arguments(command: EfCommand, context: EfContext): List<String> = buildList {
        when (command) {
            is EfCommand.AddMigration -> {
                add("migrations"); add("add"); add(command.name)
                option("--output-dir", command.outputDir)
                option("--namespace", command.namespace)
            }
            is EfCommand.RemoveMigration -> {
                add("migrations"); add("remove")
                if (command.force) add("--force")
            }
            is EfCommand.UpdateDatabase -> {
                add("database"); add("update")
                command.target?.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
                option("--connection", command.connection)
            }
            is EfCommand.Script -> {
                add("migrations"); add("script")
                val from = command.from?.trim().orEmpty()
                val to = command.to?.trim().orEmpty()
                // FROM is positional: it cannot be left out when TO is given
                if (from.isNotEmpty() || to.isNotEmpty()) add(from.ifEmpty { "0" })
                if (to.isNotEmpty()) add(to)
                if (command.idempotent) add("--idempotent")
                if (command.noTransactions) add("--no-transactions")
                option("--output", command.output)
            }
            is EfCommand.Scaffold -> {
                add("dbcontext"); add("scaffold"); add(command.connection.trim()); add(command.provider.trim())
                option("--output-dir", command.outputDir)
                // the name of the class to generate, not the context to work with as everywhere else
                option("--context", command.contextName)
                option("--context-dir", command.contextDir)
                command.schemas.forEach { option("--schema", it) }
                command.tables.forEach { option("--table", it) }
                if (command.dataAnnotations) add("--data-annotations")
                if (command.useDatabaseNames) add("--use-database-names")
                if (command.noOnConfiguring) add("--no-onconfiguring")
                if (command.noPluralize) add("--no-pluralize")
                if (command.force) add("--force")
            }
            is EfCommand.Bundle -> {
                add("migrations"); add("bundle")
                option("--output", command.output)
                if (command.selfContained) add("--self-contained")
                option("--target-runtime", command.runtime)
                if (command.force) add("--force")
            }
            EfCommand.DropDatabase -> {
                add("database"); add("drop"); add("--force")
            }
            is EfCommand.ListMigrations -> {
                add("migrations"); add("list")
                option("--connection", command.connection)
                if (command.noConnect) add("--no-connect")
                machineReadable()
            }
            EfCommand.ListContexts -> {
                add("dbcontext"); add("list")
                machineReadable()
            }
            EfCommand.ContextInfo -> {
                add("dbcontext"); add("info")
                machineReadable()
            }
            EfCommand.HasPendingModelChanges -> {
                add("migrations"); add("has-pending-model-changes")
            }
        }
        add("--project"); add(context.project)
        option("--startup-project", context.startupProject?.takeIf { it != context.project })
        // the list of contexts is what the option is chosen from
        if (command != EfCommand.ListContexts && command !is EfCommand.Scaffold) option("--context", context.dbContext)
        option("--configuration", context.configuration)
        option("--framework", context.framework)
        if (context.noBuild) add("--no-build")
        if (context.verbose) add("--verbose")
        if (context.applicationArguments.isNotEmpty()) {
            add("--"); addAll(context.applicationArguments)
        }
    }

    /** Both variables: a generic host reads `DOTNET_ENVIRONMENT`, ASP.NET Core prefers its own. */
    fun environment(context: EfContext): Map<String, String> {
        val name = context.environment?.trim().orEmpty()
        return if (name.isEmpty()) emptyMap() else mapOf("ASPNETCORE_ENVIRONMENT" to name, "DOTNET_ENVIRONMENT" to name)
    }

    /** The command as one would type it in a terminal, with paths made relative to [baseDirectory] where possible. */
    fun preview(command: EfCommand, context: EfContext, baseDirectory: String? = null): String {
        val base = baseDirectory?.replace('\\', '/')?.trimEnd('/')
        fun shorten(argument: String): String {
            val path = argument.replace('\\', '/')
            return if (base != null && path.startsWith("$base/")) path.removePrefix("$base/") else argument
        }
        val environment = context.environment?.trim()?.takeIf { it.isNotEmpty() }?.let { "ASPNETCORE_ENVIRONMENT=$it " }.orEmpty()
        return environment + (listOf("dotnet", "ef") + arguments(command, context).map(::shorten)).joinToString(" ") { if (' ' in it || it.isEmpty()) "\"$it\"" else it }
    }

    /** `--flag "a b" c` of the "additional arguments" field. */
    fun splitArguments(text: String): List<String> =
        Regex(""""([^"]*)"|(\S+)""").findAll(text).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

    private fun MutableList<String>.option(name: String, value: String?) {
        val text = value?.trim().orEmpty()
        if (text.isNotEmpty()) {
            add(name); add(text)
        }
    }

    /** JSON on the `data:` lines, apart from the build chatter and the log of the application. */
    private fun MutableList<String>.machineReadable() {
        add("--json"); add("--prefix-output")
    }
}
