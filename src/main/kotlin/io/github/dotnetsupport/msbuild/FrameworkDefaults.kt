package io.github.dotnetsupport.msbuild

/**
 * What the .NET SDK derives from a target framework for the compiler, for the projects MsBuildHost has not evaluated (yet, or at all):
 * the implicit preprocessor symbols and the default `LangVersion`. MsBuildHost gives the same from MSBuild itself
 * ([CompilationOptions.fromEvaluation]); this is the static copy of the SDK logic, checked against its answers in `CompilationOptionsTest`.
 *
 * Sources (SDK 10.0.401): `Sdks/Microsoft.NET.Sdk/targets/Microsoft.NET.Sdk.BeforeCommon.targets` (targets
 * `GenerateTargetFrameworkDefineConstants`, `GenerateTargetPlatformDefineConstants`, `GenerateNETCompatibleDefineConstants`,
 * `AddImplicitDefineConstants`), `Microsoft.NET.SupportedTargetFrameworks.props` (the versions the `_OR_GREATER` symbols go through),
 * `Roslyn/Microsoft.CSharp.Core.targets` (`_MaxSupportedLangVersion`, the default `LangVersion`).
 */
object FrameworkDefaults {
    const val NET_CORE_APP = ".NETCoreApp"
    const val NET_FRAMEWORK = ".NETFramework"
    const val NET_STANDARD = ".NETStandard"

    /** A target framework as MSBuild splits it: `net8.0-windows10.0.19041` is `.NETCoreApp` `8.0`, platform `windows` `10.0.19041`. */
    data class Framework(val identifier: String, val version: String, val platform: String? = null, val platformVersion: String? = null)

    private val MODERN = Regex("""^net(\d+)\.(\d+)(?:-([a-z]+)([\d.]*))?$""")
    private val CORE = Regex("""^netcoreapp(\d+)\.(\d+)$""")
    private val STANDARD = Regex("""^netstandard(\d+)\.(\d+)$""")
    private val FRAMEWORK = Regex("""^net(\d)(\d)(\d)?$""")

    /** `net10.0`, `net8.0-windows`, `netcoreapp3.1`, `netstandard2.0`, `net48`, `net472`; null for what is not one of them. */
    fun parse(tfm: String?): Framework? {
        val text = tfm?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        MODERN.find(text)?.let { match ->
            val (major, minor, platform, platformVersion) = match.destructured
            if (major.toInt() < 5) return null // net4.0 and the like are not TFMs of the SDK
            return Framework(NET_CORE_APP, "$major.$minor", platform.ifEmpty { null }, platformVersion.trimEnd('.').ifEmpty { null })
        }
        CORE.find(text)?.let { return Framework(NET_CORE_APP, "${it.groupValues[1]}.${it.groupValues[2]}") }
        STANDARD.find(text)?.let { return Framework(NET_STANDARD, "${it.groupValues[1]}.${it.groupValues[2]}") }
        FRAMEWORK.find(text)?.let { match -> return Framework(NET_FRAMEWORK, match.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString(".")) }
        return null
    }

    /** A project of the old format: `TargetFrameworkIdentifier` (empty: .NET Framework) and `TargetFrameworkVersion` (`v4.7.2`). */
    fun of(identifier: String?, version: String?): Framework? {
        val v = version?.trim()?.trimStart('v', 'V')?.takeIf { it.isNotEmpty() && it[0].isDigit() } ?: return null
        return Framework(identifier?.trim()?.takeIf { it.isNotEmpty() } ?: NET_FRAMEWORK, v)
    }

    /** `.NETCoreApp,Version=v1.0` .. `v10.0`: `_NETCoreAppVersionsForDefines` of the SDK 10. */
    private val NET_CORE_APP_VERSIONS = listOf("1.0", "1.1", "2.0", "2.1", "2.2", "3.0", "3.1", "5.0", "6.0", "7.0", "8.0", "9.0", "10.0")
    /** `SupportedNETFrameworkTargetFramework`. */
    private val NET_FRAMEWORK_VERSIONS = listOf("2.0", "3.0", "3.5", "4.0", "4.5", "4.5.1", "4.5.2", "4.6", "4.6.1", "4.6.2", "4.7", "4.7.1", "4.7.2", "4.8", "4.8.1")
    /** `SupportedNETStandardTargetFramework`. */
    private val NET_STANDARD_VERSIONS = listOf("1.0", "1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "2.0", "2.1")

    /**
     * The symbols `AddImplicitDefineConstants` appends to `DefineConstants`, in its order: `NET`, `NET10_0`, `NETCOREAPP`, then every
     * `NETx_y_OR_GREATER` up to the framework (`NETCOREAPPx_y_OR_GREATER` below 5.0); `NETFRAMEWORK`, `NET48`, `NET20_OR_GREATER`..;
     * `NETSTANDARD`, `NETSTANDARD2_0`, `NETSTANDARD1_0_OR_GREATER`..; for a platform (`net8.0-windows`) its name and name with version.
     * Not here: the platform's `_OR_GREATER` symbols (they come from the workload's list of platform versions) and the default platform
     * version when the TFM names none (`WINDOWS7_0` for `net8.0-windows`).
     */
    fun implicitDefines(framework: Framework?): List<String> {
        val fw = framework ?: return emptyList()
        val version = fw.version
        val result = ArrayList<String>()
        val modern = fw.identifier == NET_CORE_APP && compare(version, "5.0") >= 0
        val identifier = when {
            modern || fw.identifier == NET_FRAMEWORK -> "NET"
            else -> fw.identifier.replace(".", "").uppercase()
        }
        // GenerateTargetFrameworkDefineConstants: the versionless symbol is taken before .NETFramework becomes NET
        result += if (modern) "NET" else fw.identifier.replace(".", "").uppercase()
        result += identifier + if (fw.identifier == NET_FRAMEWORK) version.replace(".", "") else version.replace('.', '_')
        if (modern) result += "NETCOREAPP"
        // GenerateTargetPlatformDefineConstants
        if (modern && fw.platform != null) {
            val platform = fw.platform.uppercase()
            result += platform
            fw.platformVersion?.let { result += platform + it.replace('.', '_') }
        }
        // GenerateNETCompatibleDefineConstants
        val compatible = when (fw.identifier) {
            NET_CORE_APP -> versionsUpTo(NET_CORE_APP_VERSIONS, version)
            NET_FRAMEWORK -> versionsUpTo(NET_FRAMEWORK_VERSIONS, version)
            NET_STANDARD -> versionsUpTo(NET_STANDARD_VERSIONS, version)
            else -> emptyList()
        }
        when (fw.identifier) {
            NET_FRAMEWORK -> compatible.mapTo(result) { "NET${it.replace(".", "")}_OR_GREATER" }
            NET_STANDARD -> compatible.mapTo(result) { "NETSTANDARD${it.replace('.', '_')}_OR_GREATER" }
            NET_CORE_APP -> {
                compatible.filter { compare(it, "5.0") >= 0 }.mapTo(result) { "NET${it.replace('.', '_')}_OR_GREATER" }
                compatible.filter { compare(it, "5.0") < 0 }.mapTo(result) { "NETCOREAPP${it.replace('.', '_')}_OR_GREATER" }
            }
        }
        return result
    }

    /** The versions of [known] up to [version]; a .NET newer than the table (`net11.0` with this table) still gets every major in between. */
    private fun versionsUpTo(known: List<String>, version: String): List<String> {
        val result = known.filter { compare(it, version) <= 0 }.toMutableList()
        val last = known.last()
        if (known === NET_CORE_APP_VERSIONS && compare(version, last) > 0) {
            val major = version.substringBefore('.').toIntOrNull() ?: return result
            for (m in last.substringBefore('.').toInt() + 1..major) result += "$m.0"
        }
        return result
    }

    /** The newest C# the compiler of the SDK 10 knows: `_MaxAvailableLangVersion`. */
    const val MAX_AVAILABLE_LANG_VERSION = "14.0"

    /**
     * `_MaxSupportedLangVersion` of `Microsoft.CSharp.Core.targets`, which becomes `LangVersion` when the project sets none:
     *  - .NETCoreApp < 3.0, .NETStandard < 2.1, .NET Framework and anything else: `7.3`;
     *  - .NETCoreApp 3.x, .NETStandard 2.1: `8.0`;
     *  - .NET 5 and newer: `9 + (major - 5)` (`net9.0` → `13.0`, `net10.0` → `14.0`), capped at [MAX_AVAILABLE_LANG_VERSION].
     * Null for no framework (the outer evaluation of a multi-targeted project has none and no `LangVersion` either).
     */
    fun defaultLangVersion(framework: Framework?): String? {
        val fw = framework ?: return null
        return when {
            fw.identifier == NET_CORE_APP && compare(fw.version, "3.0") < 0 -> "7.3"
            fw.identifier == NET_CORE_APP && compare(fw.version, "5.0") < 0 -> "8.0"
            fw.identifier == NET_CORE_APP -> {
                val major = fw.version.substringBefore('.').toIntOrNull() ?: return null
                if (9 + (major - 5) >= MAX_AVAILABLE_LANG_VERSION.substringBefore('.').toInt()) MAX_AVAILABLE_LANG_VERSION else "${9 + (major - 5)}.0"
            }
            fw.identifier == NET_STANDARD && compare(fw.version, "2.1") == 0 -> "8.0"
            else -> "7.3"
        }
    }

    /** Versions compared part by part as numbers: `4.10` > `4.8`, `10.0` > `9.0`. */
    fun compare(first: String, second: String): Int {
        val a = first.split('.').map { it.toIntOrNull() ?: 0 }
        val b = second.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
