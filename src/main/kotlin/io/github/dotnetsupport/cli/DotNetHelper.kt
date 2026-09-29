package io.github.dotnetsupport.cli

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.security.MessageDigest

/**
 * A program on .NET the plugin needs next to itself (the indexer of assemblies, the watcher of allocations). The plugin carries its
 * source and builds it on the machine of the user, once per version of the source and per SDK: whatever SDK is there, 9 or 10, the
 * program is built for the framework of that SDK, whose reference pack comes with it, so a build that needs no packages asks nothing
 * of the network. The result lies in the caches of the IDE; nothing is put next to the projects of the user.
 *
 * [folder] is where the sources are in the resources of the plugin and the name of the folder in the caches, [assembly] the name of
 * the project file and of the dll, [property] the MSBuild property the project takes its framework from.
 */
class DotNetHelper(private val folder: String, val assembly: String, private val property: String) {
    /** Null until built; the failure of a build is remembered for the session, so it is not tried again at every use. */
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
        val sources = Sources.read(folder, listOf("Program.cs", "$assembly.csproj")) ?: return fail("The source of $assembly is not in the plugin")
        val work = File(root(), folder).apply { mkdirs() }
        return java.io.RandomAccessFile(File(work, ".lock"), "rw").use { access -> access.channel.lock().use { build(sources, work) } }
    }

    private fun build(sources: Sources, work: File): File? {
        val sdk = sdkVersion(work) ?: return fail("No .NET SDK: `dotnet --version` gave nothing")
        val framework = framework(sdk) ?: return fail("$assembly needs the SDK of .NET $MINIMAL_SDK or newer, `dotnet --version` says $sdk")
        val directory = File(work, "${sources.hash}-$framework")
        val dll = File(directory, "bin/$assembly.dll")
        if (dll.isFile && File(directory, "bin/$assembly.runtimeconfig.json").isFile) return dll.also { built = it }

        val source = File(directory, "src").apply { mkdirs() }
        for ((name, text) in sources.files) File(source, name).writeText(text)
        val command = DotNetCli.commandLine(source.path, "build", "$assembly.csproj", "-c", "Release", "-o", File(directory, "bin").path, "-nologo", "-v", "q",
            "-p:$property=$framework")
        val result = try {
            DotNetCli.execute(command.withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en"), BUILD_TIMEOUT_MS)
        } catch (e: Exception) {
            return fail("$assembly could not be built: ${e.message}")
        }
        if (result.exitCode != 0 || !dll.isFile) {
            return fail("$assembly could not be built (exit code ${result.exitCode}): " + (result.stdout + "\n" + result.stderr).trim().takeLast(ERROR_TAIL))
        }
        LOG.info("$assembly is built for $framework (SDK $sdk): $dll")
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

    class Sources(val files: Map<String, String>, val hash: String) {
        companion object {
            fun read(folder: String, names: List<String>): Sources? {
                val files = LinkedHashMap<String, String>()
                for (name in names) files[name] = DotNetHelper::class.java.getResourceAsStream("/$folder/$name")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
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
        private val LOG = logger<DotNetHelper>()
        const val MINIMAL_SDK = 8
        private const val BUILD_TIMEOUT_MS = 240_000
        private const val VERSION_TIMEOUT_MS = 20_000
        private const val ERROR_TAIL = 1_500

        /** Everything the plugin keeps of its helpers: what is built and what they have made. */
        fun root(): File = File(PathManager.getSystemPath(), "dotnet-support")

        /** `10.0.401` -> `net10.0`, `9.0.301` -> `net9.0`; null for what is older than the helpers can be built with. */
        fun framework(sdkVersion: String): String? {
            val major = sdkVersion.trim().substringBefore('.').toIntOrNull() ?: return null
            return if (major >= MINIMAL_SDK) "net$major.0" else null
        }
    }
}
