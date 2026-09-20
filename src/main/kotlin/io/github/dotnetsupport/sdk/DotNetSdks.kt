package io.github.dotnetsupport.sdk

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.cli.DotNetCli
import java.io.StringReader

/** `10.0.401`, `9.0.100-rc.1.24452.12`: major.minor.patch where the hundreds of the patch are the feature band. */
class SdkVersion private constructor(val major: Int, val minor: Int, val patch: Int, val prerelease: String, val text: String) : Comparable<SdkVersion> {
    val featureBand: Int get() = patch / 100
    val isPrerelease: Boolean get() = prerelease.isNotEmpty()

    override fun compareTo(other: SdkVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).takeIf { it != 0 }
            // a release is newer than its previews
            ?: when {
                prerelease == other.prerelease -> 0
                prerelease.isEmpty() -> 1
                other.prerelease.isEmpty() -> -1
                else -> prerelease.compareTo(other.prerelease)
            }

    override fun equals(other: Any?): Boolean = other is SdkVersion && compareTo(other) == 0
    override fun hashCode(): Int = listOf(major, minor, patch, prerelease).hashCode()
    override fun toString(): String = text

    companion object {
        fun parse(text: String): SdkVersion? {
            val trimmed = text.trim()
            val numbers = trimmed.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
            if (numbers.size != 3) return null
            return SdkVersion(numbers[0], numbers[1], numbers[2], trimmed.substringAfter('-', ""), trimmed)
        }
    }
}

class InstalledSdk(val version: SdkVersion, val location: String)

object DotNetSdks {
    /** Lines of `dotnet --list-sdks`: `10.0.401 [C:\Program Files\dotnet\sdk]`. */
    fun parse(output: String): List<InstalledSdk> = output.lineSequence().mapNotNull { line ->
        val version = SdkVersion.parse(line.substringBefore('[')) ?: return@mapNotNull null
        InstalledSdk(version, line.substringAfter('[', "").substringBefore(']').trim())
    }.sortedByDescending { it.version }.toList()

    /** SDKs known to [executable] (the configured CLI by default). Blocking; empty when the CLI is not found. */
    fun installed(executable: String? = null): List<InstalledSdk> = try {
        val commandLine = if (executable == null) DotNetCli.commandLine(null, "--list-sdks")
        else GeneralCommandLine(executable, "--list-sdks").withCharset(Charsets.UTF_8).withEnvironment("DOTNET_NOLOGO", "1")
        parse(DotNetCli.execute(commandLine, 30_000).stdout)
    } catch (_: Exception) {
        emptyList()
    }
}

/** What the SDK that will run a command in a directory can do. */
object SdkFeatures {
    private val RUN_ENVIRONMENT_OPTION = SdkVersion.parse("9.0.200")!!
    private var cached: Pair<Long, List<SdkVersion>>? = null

    /** The installed SDKs, asked from the CLI at most once in a minute. Blocking on a miss. */
    @Synchronized fun installedVersions(): List<SdkVersion> {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - it.first < 60_000 && it.second.isNotEmpty() }?.let { return it.second }
        return DotNetSdks.installed().map { it.version }.also { cached = now to it }
    }

    /** `dotnet run -e NAME=VALUE` appeared in SDK 9.0.200; an older CLI fails on the unknown option. */
    fun supportsRunEnvironmentOption(sdk: SdkVersion?): Boolean = sdk != null && sdk >= RUN_ENVIRONMENT_OPTION

    /** The SDK `dotnet` picks in [directory]: `global.json` decides, otherwise the newest one. */
    fun sdkFor(directory: VirtualFile?, installed: List<SdkVersion> = installedVersions()): SdkVersion? {
        val globalJson = GlobalJson.find(directory)?.second
        return if (globalJson != null) globalJson.resolve(installed) else installed.maxOrNull()
    }
}

/** The `sdk` section of `global.json`: the SDK a repository asks for and how far from it the CLI may roll forward. */
class GlobalJson(val version: SdkVersion?, val rollForward: String, val allowPrerelease: Boolean) {

    /** The newest installed SDK that the policy accepts, or null: then `dotnet` refuses to work in the directory. */
    fun resolve(installed: List<SdkVersion>): SdkVersion? {
        val candidates = installed.filter { allowPrerelease || !it.isPrerelease || it == version }
        val required = version ?: return candidates.maxOrNull()
        return candidates.filter { it >= required && accepts(required, it) }.maxOrNull()
    }

    private fun accepts(required: SdkVersion, candidate: SdkVersion): Boolean = when (rollForward.lowercase()) {
        "disable" -> candidate == required
        "patch", "latestpatch" -> candidate.major == required.major && candidate.minor == required.minor && candidate.featureBand == required.featureBand
        "feature", "latestfeature" -> candidate.major == required.major && candidate.minor == required.minor
        "minor", "latestminor" -> candidate.major == required.major
        "major", "latestmajor" -> true
        else -> candidate.major == required.major && candidate.minor == required.minor && candidate.featureBand == required.featureBand
    }

    companion object {
        /** What the CLI assumes when a version is given without a policy. */
        const val DEFAULT_ROLL_FORWARD = "latestPatch"

        /** Null when there is no usable `sdk` section (the file may exist only for `msbuild-sdks`). */
        fun parse(text: String): GlobalJson? {
            val root = try {
                // comments are allowed in global.json
                JsonParser.parseReader(JsonReader(StringReader(text)).apply { isLenient = true }) as? JsonObject
            } catch (_: Exception) {
                null
            }
            val sdk = root?.get("sdk") as? JsonObject ?: return null
            fun string(name: String) = sdk.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            val version = string("version")?.let(SdkVersion::parse)
            return GlobalJson(
                version,
                // without a version the newest SDK is used, whatever its major is
                rollForward = string("rollForward") ?: if (version == null) "latestMajor" else DEFAULT_ROLL_FORWARD,
                allowPrerelease = sdk.get("allowPrerelease")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
            )
        }

        /** The nearest `global.json` from [directory] upwards, the way the CLI looks for it. */
        fun find(directory: VirtualFile?): Pair<VirtualFile, GlobalJson>? {
            var current = directory
            while (current != null) {
                current.findChild("global.json")?.let { file ->
                    return runCatching { parse(VfsUtilCore.loadText(file)) }.getOrNull()?.let { file to it }
                }
                current = current.parent
            }
            return null
        }
    }
}
