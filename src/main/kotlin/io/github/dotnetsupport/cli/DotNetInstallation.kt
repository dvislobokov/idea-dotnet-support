package io.github.dotnetsupport.cli

import java.io.File

/** Layout of the .NET installation the `dotnet` executable belongs to: `sdk/<version>/Sdks/...`, `packs/<name>.Ref/<version>/ref/...`. */
object DotNetInstallation {
    fun root(): File? = DotNetCli.findExecutable()?.let { runCatching { File(it).canonicalFile.parentFile }.getOrNull() }

    /** `Sdk.props` and `Sdk.targets` of an MSBuild SDK (`Microsoft.NET.Sdk.Web`) in the newest installed .NET SDK. */
    fun sdkImports(sdkName: String, root: File? = root()): List<File> {
        val sdk = newest(File(root ?: return emptyList(), "sdk")) ?: return emptyList()
        val directory = File(sdk, "Sdks/$sdkName/Sdk")
        return listOf("Sdk.props", "Sdk.targets").map { File(directory, it) }.filter { it.isFile }
    }

    /** Reference assemblies of a shared framework (`Microsoft.AspNetCore.App`) for a framework version like `9.0`. */
    fun frameworkAssemblies(frameworkReference: String, frameworkVersion: String, root: File? = root()): List<String> {
        val pack = File(root ?: return emptyList(), "packs/$frameworkReference.Ref")
        val version = newest(pack) { it.name.startsWith("$frameworkVersion.") } ?: return emptyList()
        return File(version, "ref/net$frameworkVersion").listFiles { file -> file.extension.equals("dll", ignoreCase = true) }
            .orEmpty().map { it.nameWithoutExtension }.sortedBy { it.lowercase() }
    }

    private fun newest(directory: File, filter: (File) -> Boolean = { true }): File? =
        directory.listFiles { file -> file.isDirectory && filter(file) }.orEmpty().maxWithOrNull(compareBy(VERSION_ORDER) { it.name })

    /** `10.0.100` is newer than `9.0.301`; previews (`-rc.1`) are older than the release. */
    private val VERSION_ORDER = Comparator<String> { first, second ->
        val a = first.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val b = second.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val difference = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (difference != 0) return@Comparator difference
        }
        ('-' !in first).compareTo('-' !in second)
    }
}
