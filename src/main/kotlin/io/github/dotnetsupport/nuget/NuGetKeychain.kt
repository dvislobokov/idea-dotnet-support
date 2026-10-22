package io.github.dotnetsupport.nuget

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.credentialStore.Credentials
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetHelper
import io.github.dotnetsupport.cli.PluginLog
import java.io.File
import java.io.StringReader

/**
 * The passwords of private feeds in the keychain of the OS, through the NuGet credential provider of the plugin (`helpers/credentialprovider`):
 * nothing in `nuget.config`, and every `dotnet restore` finds them — of the IDE, of the DotNetHelper, of any terminal — because NuGet asks
 * the providers in `~/.nuget/plugins/netcore/` after a 401 of the feed.
 *
 * The provider is built from its source for the SDK of the machine ([DotNetHelper]) and copied into that folder; the copy is refreshed when
 * the source or the SDK changes. Where it cannot work — no SDK 8+, no Secret Service on Linux, NuGet told to look elsewhere for its plugins
 * — [availability] says why, and the credentials go to `nuget.config` as before.
 */
object NuGetKeychain {
    const val ASSEMBLY = "CredentialProvider.DotNetSupport"
    private val provider = DotNetHelper("credentialprovider", ASSEMBLY, "HelperFramework", listOf("Program.cs", "NuGetPlugin.cs", "Keyring.cs"))
    private const val TIMEOUT_MS = 60_000
    private const val NOT_FOUND = 3

    sealed interface Availability {
        /** The provider is in NuGet's folder and the keychain answers. */
        data class Ready(val dll: File) : Availability

        data class Unavailable(val reason: String) : Availability
    }

    @Volatile private var decided: Availability? = null

    /** Builds and installs the provider and asks the keychain, once per session. Seconds the first time: not for the EDT. */
    @Synchronized
    fun availability(): Availability {
        decided?.let { return it }
        return decide().also {
            decided = it
            when (it) {
                is Availability.Ready -> PluginLog.info(NuGetClient.LOG_CATEGORY, "NuGet credentials go to the keychain of the OS through ${it.dll}")
                is Availability.Unavailable -> PluginLog.warn(NuGetClient.LOG_CATEGORY, "NuGet credentials cannot go to the keychain of the OS: ${it.reason}")
            }
        }
    }

    fun isReady(): Boolean = availability() is Availability.Ready

    private fun decide(): Availability {
        pluginPathsOverride(System.getenv())?.let { (name, value) ->
            return Availability.Unavailable("$name=$value tells NuGet where its plugins are, so it does not look into ${pluginFolder()}; add " +
                "${File(pluginFolder(), "$ASSEMBLY.dll")} to it")
        }
        val built = provider.ensureBuilt() ?: return Availability.Unavailable(provider.failure ?: "the credential provider could not be built")
        val installed = try {
            install(built.parentFile, pluginFolder())
        } catch (e: Exception) {
            return Availability.Unavailable("the credential provider could not be copied to ${pluginFolder()}: ${e.message}")
        }
        val check = try {
            run(installed, "check", null)
        } catch (e: Exception) {
            return Availability.Unavailable("the credential provider could not run: ${e.message}")
        }
        return if (check.exitCode == 0) Availability.Ready(installed) else Availability.Unavailable(check.stderr.trim().ifEmpty { "exit code ${check.exitCode}" })
    }

    /** `~/.nuget/plugins/netcore/CredentialProvider.DotNetSupport`: NuGet runs the dll of the same name as the folder. */
    fun pluginFolder(): File = File(System.getProperty("user.home"), ".nuget/plugins/netcore/$ASSEMBLY")

    /** NuGet looks only where these say when they are set, `NUGET_NETCORE_PLUGIN_PATHS` before `NUGET_PLUGIN_PATHS`; null when neither is. */
    fun pluginPathsOverride(environment: Map<String, String>): Pair<String, String>? =
        listOf("NUGET_NETCORE_PLUGIN_PATHS", "NUGET_PLUGIN_PATHS").firstNotNullOfOrNull { name -> environment[name]?.takeIf { it.isNotBlank() }?.let { name to it } }

    /**
     * Copies the output of the build (the dll, its runtimeconfig and deps) into [target], unless the copy there came from the same build
     * (`.build` names it: the hash of the source and the framework). Gives the dll in [target].
     */
    fun install(binary: File, target: File): File {
        val build = binary.parentFile.name
        val marker = File(target, ".build")
        val dll = File(target, "$ASSEMBLY.dll")
        if (dll.isFile && marker.isFile && marker.readText().trim() == build) return dll
        target.mkdirs()
        for (file in binary.listFiles().orEmpty().filter { it.isFile }) file.copyTo(File(target, file.name), overwrite = true)
        marker.writeText(build)
        PluginLog.info(NuGetClient.LOG_CATEGORY, "the NuGet credential provider is installed: $target ($build)")
        return dll
    }

    /** Keeps the user and the password of [source] in the keychain; throws with the reason when it cannot. */
    fun store(source: String, user: String, password: String) {
        val result = run(ready(), "store", JsonObject().apply { addProperty("source", source); addProperty("username", user); addProperty("password", password) })
        if (result.exitCode != 0) throw NuGetKeychainException(failure("store the password of $source", result))
    }

    /** What the keychain has for [source]; null when nothing. Throws with the reason when the keychain cannot be read. */
    fun get(source: String): Credentials? {
        val result = run(ready(), "get", JsonObject().apply { addProperty("source", source) })
        if (result.exitCode == NOT_FOUND) return null
        if (result.exitCode != 0) throw NuGetKeychainException(failure("read the password of $source", result))
        val answer = JsonParser.parseReader(StringReader(result.stdout)) as? JsonObject ?: return null
        return Credentials(answer.get("username")?.asString, answer.get("password")?.asString)
    }

    fun erase(source: String) {
        val result = run(ready(), "erase", JsonObject().apply { addProperty("source", source) })
        if (result.exitCode != 0) throw NuGetKeychainException(failure("erase the password of $source", result))
    }

    private fun ready(): File = when (val state = availability()) {
        is Availability.Ready -> state.dll
        is Availability.Unavailable -> throw NuGetKeychainException(state.reason)
    }

    private fun failure(what: String, result: ProcessOutput) = "The keychain could not $what: " + result.stderr.trim().ifEmpty { "exit code ${result.exitCode}" }

    /** `dotnet <provider> <command>` with [request] on stdin: the password never goes to the command line, where the list of processes shows it. */
    private fun run(dll: File, command: String, request: JsonObject?): ProcessOutput {
        val handler = CapturingProcessHandler(DotNetCli.commandLine(null, dll.path, command).withCharset(Charsets.UTF_8))
        handler.processInput.use { input -> request?.let { input.write(it.toString().toByteArray(Charsets.UTF_8)) } }
        return handler.runProcess(TIMEOUT_MS)
    }
}

class NuGetKeychainException(message: String) : Exception(message)
