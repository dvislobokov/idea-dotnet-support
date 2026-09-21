package io.github.dotnetsupport.ef

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** [applied] is null when the tool could not ask the database (`--no-connect`, or the server is down). */
data class EfMigration(val id: String, val name: String, val applied: Boolean?)

data class EfDbContextType(val fullName: String, val name: String)

data class EfContextInfo(val type: String?, val providerName: String?, val databaseName: String?, val dataSource: String?, val options: String?)

/** Failures the IDE can help with, recognized by the message of the tool. */
enum class EfProblem {
    TOOL_MISSING, TOOL_NOT_RESTORED, DESIGN_PACKAGE_MISSING, PROVIDER_MISSING, CANNOT_CREATE_CONTEXT, NO_CONTEXT, MULTIPLE_CONTEXTS, MIGRATION_APPLIED, BUILD_FAILED,
}

/** What `dotnet ef` prints; pure functions over the captured text. */
object EfOutputParser {
    private val PREFIX = Regex("""^(data|info|warn|error|verbose):\s""")
    private const val PREFIX_WIDTH = 9 // "data:    ", "verbose: "

    /** The JSON of a `--json --prefix-output` run: the `data:` lines. */
    fun json(output: String): JsonElement? {
        val data = output.lineSequence().filter { it.startsWith("data:") }.joinToString("\n") { it.drop(PREFIX_WIDTH) }
        return runCatching { JsonParser.parseString(data) }.getOrNull()?.takeIf { !it.isJsonNull }
    }

    fun migrations(output: String): List<EfMigration> = (json(output) as? JsonArray).orEmpty().mapNotNull { element ->
        val migration = element as? JsonObject ?: return@mapNotNull null
        val id = migration.string("id") ?: return@mapNotNull null
        val applied = migration.get("applied")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        EfMigration(id, migration.string("name") ?: id.substringAfter('_'), applied)
    }

    fun contexts(output: String): List<EfDbContextType> = (json(output) as? JsonArray).orEmpty().mapNotNull { element ->
        val type = element as? JsonObject ?: return@mapNotNull null
        val fullName = type.string("fullName") ?: return@mapNotNull null
        EfDbContextType(fullName, type.string("name") ?: fullName.substringAfterLast('.'))
    }

    fun contextInfo(output: String): EfContextInfo? = (json(output) as? JsonObject)?.let {
        EfContextInfo(it.string("type"), it.string("providerName"), it.string("databaseName"), it.string("dataSource"), it.string("options"))
    }

    /** The `error:` lines of a prefixed run, or the whole text of a plain one: what to show when a command has failed. */
    fun errorText(output: String): String {
        val lines = output.lines()
        val errors = lines.filter { it.startsWith("error:") }.map { it.drop(PREFIX_WIDTH) }
        return (errors.ifEmpty { lines.filterNot { PREFIX.containsMatchIn(it) } }).joinToString("\n").trim()
    }

    fun diagnose(output: String): EfProblem? = when {
        "dotnet tool restore" in output -> EfProblem.TOOL_NOT_RESTORED
        "dotnet-ef does not exist" in output || "specified command or file was not found" in output -> EfProblem.TOOL_MISSING
        "doesn't reference Microsoft.EntityFrameworkCore.Design" in output -> EfProblem.DESIGN_PACKAGE_MISSING
        // scaffolding with a provider the project does not reference
        "Unable to find provider assembly" in output -> EfProblem.PROVIDER_MISSING
        // EF 7+: "Unable to create a 'DbContext' of type 'X'", before: "Unable to create an object of type 'X'"
        "Unable to create a 'DbContext' of type" in output || "Unable to create an object of type" in output -> EfProblem.CANNOT_CREATE_CONTEXT
        "More than one DbContext was found" in output -> EfProblem.MULTIPLE_CONTEXTS
        "No DbContext was found" in output -> EfProblem.NO_CONTEXT
        "has already been applied to the database" in output -> EfProblem.MIGRATION_APPLIED
        "Build failed." in output -> EfProblem.BUILD_FAILED
        else -> null
    }

    /**
     * `migrations has-pending-model-changes`: "Changes have been made to the model since the last migration" or
     * "No changes have been made ...". Null when it is neither: a tool before EF 8 does not know the command.
     */
    fun pendingModelChanges(output: String): Boolean? = when {
        "No changes have been made to the model" in output -> false
        "Changes have been made to the model" in output -> true
        else -> null
    }

    private val OUTDATED =Regex("""tools version '([^']+)' is older than that of the runtime '([^']+)'""")

    /** "The Entity Framework tools version '7.0.0' is older than that of the runtime '8.0.1'": the tool and the runtime versions. */
    fun outdatedTool(output: String): Pair<String, String>? = OUTDATED.find(output)?.let { it.groupValues[1] to it.groupValues[2] }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonArray?.orEmpty(): Iterable<JsonElement> = this ?: emptyList()
}
