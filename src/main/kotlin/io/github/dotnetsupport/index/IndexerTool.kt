package io.github.dotnetsupport.index

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetHelper
import io.github.dotnetsupport.cli.PluginLog
import java.io.File
import java.nio.file.Files

/**
 * The indexer of assemblies on the machine of the user: built there from the source the plugin carries ([DotNetHelper]), run with
 * the `dotnet` that is there.
 */
@Service(Service.Level.APP)
class IndexerTool {
    private val helper = DotNetHelper("indexer", ASSEMBLY, "IndexerFramework")

    val failure: String? get() = helper.failure

    /** The dll to run with `dotnet`, built when it is not there yet. Takes seconds the first time: not for the UI thread. */
    fun ensureBuilt(): File? = helper.ensureBuilt()

    private val buildFailureReported = java.util.concurrent.atomic.AtomicBoolean()

    /** Once per session: without the indexer the completion has no types of the packages and the framework, and nothing else says so. */
    private fun reportBuildFailure() {
        val reason = failure ?: return
        if (!buildFailureReported.compareAndSet(false, true)) return
        DotNetCli.notifyError(com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull(), "Indexer of assemblies",
            "The completion of types from packages and the framework is off: the indexer could not be built.\n" + reason.lines().first())
    }

    /**
     * Indexes [assemblies] into [output] (what is there already is left alone) and says which file is the index of which assembly.
     * One process for the whole list, and one at a time: the projects of this IDE take their turns here, the other IDEs at the lock
     * the indexer holds on the folder.
     */
    @Synchronized
    fun index(assemblies: List<File>, output: File): Map<File, File> {
        if (assemblies.isEmpty()) return emptyMap()
        val dll = ensureBuilt() ?: run { reportBuildFailure(); return emptyMap() }
        output.mkdirs()
        val list = Files.createTempFile("assemblies", ".txt").toFile()
        try {
            list.writeText(assemblies.joinToString("\n") { it.path })
            val command = DotNetCli.commandLine(output.path, dll.path, "--out", output.path, "--list", list.path)
            val result = DotNetCli.execute(command, INDEX_TIMEOUT_MS)
            if (result.exitCode != 0) {
                PluginLog.warn(DotNetHelper.LOG_CATEGORY, "the indexer has failed (exit code ${result.exitCode}): " + result.stderr.trim().takeLast(ERROR_TAIL))
                return emptyMap()
            }
            return parse(result.stdout)
        } finally {
            list.delete()
        }
    }

    /** The source of the indexer as the plugin carries it. */
    object Sources {
        fun read(): DotNetHelper.Sources? = DotNetHelper.Sources.read("indexer", listOf("Program.cs", "$ASSEMBLY.csproj"))
        fun hash(texts: Collection<String>): String = DotNetHelper.Sources.hash(texts)
    }

    companion object {
        const val ASSEMBLY = "AssemblyIndexer"
        private const val INDEX_TIMEOUT_MS = 300_000
        private const val ERROR_TAIL = 1_500

        fun getInstance(): IndexerTool = service()

        /** Everything the plugin keeps for the index: the built indexer and the indexes, by the version of their format. */
        fun root(): File = DotNetHelper.root()

        fun indexDirectory(): File = File(root(), "index/v${AssemblyIndex.FORMAT_VERSION}")

        /** `10.0.401` -> `net10.0`, `9.0.301` -> `net9.0`; null for what is older than the indexer can be built with. */
        fun framework(sdkVersion: String): String? = DotNetHelper.framework(sdkVersion)

        /** The lines of JSON the indexer prints: an assembly and the file of its index. */
        fun parse(output: String): Map<File, File> {
            val found = LinkedHashMap<File, File>()
            for (line in output.lineSequence()) {
                if (!line.startsWith("{")) continue
                val row = runCatching { JsonParser.parseString(line) as? JsonObject }.getOrNull() ?: continue
                if (row.has("error") || row.has("summary")) continue
                val path = row.get("path")?.asString ?: continue
                val index = row.get("index")?.asString ?: continue
                found[File(path)] = File(index)
            }
            return found
        }
    }
}
