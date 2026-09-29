package io.github.dotnetsupport.index

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import io.github.dotnetsupport.cli.DotNetCli
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The indexer of assemblies on the machine of the user. The plugin carries its source (`indexer/Program.cs` of the repository) and
 * builds it here, once per version of the source and per SDK: whatever SDK the machine has, 9 or 10, the indexer is built for the
 * framework of that SDK, whose reference pack comes with it, so the build asks nothing of the network. The result lies in the
 * caches of the IDE; nothing is put next to the projects of the user.
 */
@Service(Service.Level.APP)
class IndexerTool {
    /** Null until built; the failure of a build is remembered for the session, so it is not tried at every completion. */
    @Volatile private var built: File? = null
    @Volatile private var failed: String? = null

    val failure: String? get() = failed

    /**
     * The dll to run with `dotnet`, built when it is not there yet. Takes seconds the first time: not for the UI thread. Another IDE
     * may be building it at the same moment, into the same folder: the build is done under a lock between the processes, and the one
     * that waited finds the dll made.
     */
    @Synchronized
    fun ensureBuilt(): File? {
        built?.takeIf { it.isFile }?.let { return it }
        if (failed != null) return null
        val sources = Sources.read() ?: return fail("The source of the indexer is not in the plugin")
        val work = File(root(), "indexer").apply { mkdirs() }
        return locked(File(work, ".lock")) { build(sources, work) }
    }

    /** [action] under a lock that other processes see; the system lets it go when this process ends. */
    private fun <T> locked(file: File, action: () -> T): T =
        java.io.RandomAccessFile(file, "rw").use { access -> access.channel.lock().use { action() } }

    private fun build(sources: Sources, work: File): File? {
        val sdk = sdkVersion(work) ?: return fail("No .NET SDK: `dotnet --version` gave nothing")
        val framework = framework(sdk) ?: return fail("The indexer needs the SDK of .NET $MINIMAL_SDK or newer, `dotnet --version` says $sdk")
        val directory = File(work, "${sources.hash}-$framework")
        val dll = File(directory, "bin/$ASSEMBLY.dll")
        if (dll.isFile && File(directory, "bin/$ASSEMBLY.runtimeconfig.json").isFile) return dll.also { built = it }

        val source = File(directory, "src").apply { mkdirs() }
        for ((name, text) in sources.files) File(source, name).writeText(text)
        val command = DotNetCli.commandLine(source.path, "build", "$ASSEMBLY.csproj", "-c", "Release", "-o", File(directory, "bin").path, "-nologo", "-v", "q",
            "-p:IndexerFramework=$framework")
        val result = try {
            DotNetCli.execute(command.withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en"), BUILD_TIMEOUT_MS)
        } catch (e: Exception) {
            return fail("The indexer could not be built: ${e.message}")
        }
        if (result.exitCode != 0 || !dll.isFile) {
            return fail("The indexer could not be built (exit code ${result.exitCode}): " + (result.stdout + "\n" + result.stderr).trim().takeLast(ERROR_TAIL))
        }
        LOG.info("The indexer of assemblies is built for $framework (SDK $sdk): $dll")
        return dll.also { built = it }
    }

    private fun fail(message: String): File? {
        failed = message
        LOG.warn(message)
        return null
    }

    /** The SDK `dotnet build` would use in [directory]: the newest one, unless a `global.json` above says otherwise (there is none in the caches). */
    private fun sdkVersion(directory: File): String? = try {
        DotNetCli.execute(DotNetCli.commandLine(directory.path, "--version"), VERSION_TIMEOUT_MS).takeIf { it.exitCode == 0 }?.stdout?.trim()?.lineSequence()?.lastOrNull()?.trim()
    } catch (_: Exception) {
        null
    }

    /**
     * Indexes [assemblies] into [output] (what is there already is left alone) and says which file is the index of which assembly.
     * One process for the whole list, and one at a time: the projects of this IDE take their turns here, the other IDEs at the lock
     * the indexer holds on the folder.
     */
    @Synchronized
    fun index(assemblies: List<File>, output: File): Map<File, File> {
        if (assemblies.isEmpty()) return emptyMap()
        val dll = ensureBuilt() ?: return emptyMap()
        output.mkdirs()
        val list = Files.createTempFile("assemblies", ".txt").toFile()
        try {
            list.writeText(assemblies.joinToString("\n") { it.path })
            val command = DotNetCli.commandLine(output.path, dll.path, "--out", output.path, "--list", list.path)
            val result = DotNetCli.execute(command, INDEX_TIMEOUT_MS)
            if (result.exitCode != 0) {
                LOG.warn("The indexer has failed (exit code ${result.exitCode}): " + result.stderr.trim().takeLast(ERROR_TAIL))
                return emptyMap()
            }
            return parse(result.stdout)
        } finally {
            list.delete()
        }
    }

    class Sources(val files: Map<String, String>, val hash: String) {
        companion object {
            private val NAMES = listOf("Program.cs", "$ASSEMBLY.csproj")

            fun read(): Sources? {
                val files = LinkedHashMap<String, String>()
                for (name in NAMES) files[name] = IndexerTool::class.java.getResourceAsStream("/indexer/$name")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
                return Sources(files, hash(files.values))
            }

            /** Twelve hex digits of SHA-256 over the sources, line ends aside: a checkout with CRLF is the same source. */
            fun hash(texts: Collection<String>): String {
                val digest = MessageDigest.getInstance("SHA-256")
                for (text in texts) digest.update(text.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
                return digest.digest().take(6).joinToString("") { String.format("%02x", it) }
            }
        }
    }

    companion object {
        private val LOG = logger<IndexerTool>()
        const val ASSEMBLY = "AssemblyIndexer"
        const val MINIMAL_SDK = 8
        private const val BUILD_TIMEOUT_MS = 180_000
        private const val INDEX_TIMEOUT_MS = 300_000
        private const val VERSION_TIMEOUT_MS = 20_000
        private const val ERROR_TAIL = 1_500

        fun getInstance(): IndexerTool = service()

        /** Everything the plugin keeps for the index: the built indexer and the indexes, by the version of their format. */
        fun root(): File = File(PathManager.getSystemPath(), "dotnet-support")

        fun indexDirectory(): File = File(root(), "index/v${AssemblyIndex.FORMAT_VERSION}")

        /** `10.0.401` -> `net10.0`, `9.0.301` -> `net9.0`; null for what is older than the indexer can be built with. */
        fun framework(sdkVersion: String): String? {
            val major = sdkVersion.trim().substringBefore('.').toIntOrNull() ?: return null
            return if (major >= MINIMAL_SDK) "net$major.0" else null
        }

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
