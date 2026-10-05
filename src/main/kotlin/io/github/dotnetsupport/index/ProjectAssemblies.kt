package io.github.dotnetsupport.index

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.util.SystemInfo
import io.github.dotnetsupport.cli.LocalPaths
import io.github.dotnetsupport.msbuild.MsBuildProject
import java.io.File

/**
 * What a project is compiled against, which is what its code may name ([References]): the `compile` assets of its packages (the
 * transitive ones are among them, the private ones of a package are not), the reference packs of the frameworks it refers to, the
 * reference assemblies of .NET Framework, the assemblies of `<Reference><HintPath>`, and the projects it refers to — those are
 * sources, their built assemblies are listed apart for what has nothing but metadata to look at. From `obj/project.assets.json`,
 * where restore has written all of it down, and the project file as it is written; nothing is resolved here, no MSBuild runs.
 */
object ProjectAssemblies {
    class Request(
        /** `obj/project.assets.json` as text; null for a project that has none: one of the old format, one not restored. */
        val assets: CharSequence?,
        /** The directory of the project file. */
        val projectDirectory: File,
        /** Where `dotnet` is installed: `packs` with the reference assemblies is under it. */
        val dotnetRoot: File?,
        /** The framework chosen in the toolbar; null or unknown to the project: its first one. */
        val framework: String? = null,
        /** The project file read statically: `Reference` with `HintPath`, `TargetFrameworkVersion` and `ProjectReference` of the old format. */
        val msbuild: MsBuildProject? = null,
        /** `Reference Assemblies/Microsoft/Framework/.NETFramework` of Visual Studio and the targeting packs, with `v4.7.2` and the like in it. */
        val netFrameworkRoot: File? = defaultNetFrameworkRoot(),
    )

    /**
     * The references of a project for one of its frameworks ([framework]): [assemblies] to read the metadata of, [projects] (project
     * files) whose sources are a part of what the code sees, and [projectOutputs] — the assemblies those have built, when they have.
     */
    class References(
        val framework: String?, val assemblies: List<File>, val projects: List<File>, val projectOutputs: List<File>,
        /** [assemblies] by where they come from: a package, a framework pack, an assembly named by its path — the External Libraries of the IDE. */
        val libraries: List<Library> = emptyList(),
    ) {
        /** What the indexer of assemblies is given: until the semantics reads the sources of [projects], their outputs stand for them. */
        val forIndex: List<File> get() = (assemblies + projectOutputs).distinct()

        companion object {
            val NONE = References(null, emptyList(), emptyList(), emptyList())
        }
    }

    enum class LibraryKind { FRAMEWORK, PACKAGE, ASSEMBLY }

    /** `Newtonsoft.Json` `13.0.3`, `Microsoft.NETCore.App.Ref` `10.0.12`, `.NETFramework` `4.7.2`, `Vendor` (by `HintPath`, no version). */
    data class Library(val name: String, val version: String?, val kind: LibraryKind, val assemblies: List<File>) {
        val key: String get() = "$kind:$name:${version.orEmpty()}"
        val presentableName: String get() = if (version == null) name else "$name $version"
    }

    fun of(request: Request): List<File> = references(request).forIndex

    fun references(request: Request): References {
        val root = request.assets?.let { text -> runCatching { JsonParser.parseString(text.toString().removePrefix("﻿")) as? JsonObject }.getOrNull() }
        return if (root != null) restored(request, root) else unrestored(request)
    }

    /** An SDK project after restore: everything is in the assets file, but what `HintPath` names. */
    private fun restored(request: Request, root: JsonObject): References {
        val targets = root.obj("targets") ?: return unrestored(request)
        // `net9.0` and not `net9.0/win-x64`: the target of a runtime has the same assemblies to compile against
        val names = targets.keySet().filter { '/' !in it }
        val target = names.firstOrNull { it.equals(request.framework, ignoreCase = true) } ?: names.firstOrNull() ?: return References.NONE
        val libraries = root.obj("libraries")
        // never a network folder a cloned assets file names (LocalPaths)
        val folders = root.obj("packageFolders")?.keySet().orEmpty().filter(LocalPaths::isReadablePackagesFolder).map(::File)
        val assemblies = LinkedHashSet<File>()
        val projects = LinkedHashSet<File>()
        val outputs = LinkedHashSet<File>()
        val found = ArrayList<Library>()
        var netFrameworkPack: File? = null

        for ((id, value) in targets.obj(target)?.entrySet().orEmpty()) {
            val library = value as? JsonObject ?: continue
            val compiled = library.obj("compile")?.keySet().orEmpty().filter { it.endsWith(".dll", ignoreCase = true) }
            when (library.get("type")?.asString) {
                "package" -> {
                    val path = libraries?.obj(id)?.get("path")?.asString ?: id.lowercase()
                    val files = compiled.mapNotNull { relative -> folders.map { File(File(it, path), relative) }.firstOrNull { it.isFile } }
                    assemblies += files
                    if (files.isNotEmpty()) found += Library(id.substringBefore('/'), id.substringAfter('/', "").ifEmpty { null }, LibraryKind.PACKAGE, files)
                    // `Microsoft.NETFramework.ReferenceAssemblies.net472`: the reference assemblies of .NET Framework come as a package
                    if (id.substringBefore('/').startsWith(NET_FRAMEWORK_PACKAGE, ignoreCase = true)) {
                        netFrameworkPack = folders.map { File(File(it, path), "build/.NETFramework") }.firstOrNull { it.isDirectory }
                    }
                }
                "project" -> {
                    val project = libraries?.obj(id)?.get("path")?.asString ?: continue
                    val file = File(request.projectDirectory, project).normalize()
                    projects += file
                    val directory = file.parentFile ?: continue
                    for (relative in compiled) built(directory, File(relative).name, target)?.let(outputs::add)
                }
            }
        }

        val frameworks = root.obj("project")?.obj("frameworks")
        val declared = frameworks?.keySet()?.firstOrNull { it.equals(target, ignoreCase = true) } ?: frameworks?.keySet()?.firstOrNull()
        val declaredFramework = declared?.let { frameworks?.obj(it) }
        for (reference in declaredFramework?.obj("frameworkReferences")?.keySet().orEmpty()) {
            // a pack the SDK has, else the one restore has downloaded for a framework the SDK has no pack of
            val pack = referencePack(request.dotnetRoot, reference, target).ifEmpty { downloadedPack(folders, declaredFramework, reference, target) }
            assemblies += pack
            // `<pack>/10.0.12/ref/net10.0/System.Runtime.dll`: the version is the folder above `ref`
            if (pack.isNotEmpty()) found += Library("$reference.Ref", pack.first().parentFile?.parentFile?.parentFile?.name, LibraryKind.FRAMEWORK, pack)
        }
        netFrameworkVersion(target)?.let { version ->
            val framework = netFrameworkAssemblies(netFrameworkPack?.let { File(it, version) } ?: request.netFrameworkRoot?.let { File(it, version) }, request.msbuild, sdkProject = true)
            assemblies += framework
            netFrameworkLibrary(version, framework)?.let(found::add)
        }
        val hinted = hintPaths(request)
        assemblies += hinted
        found += hinted.map(::assemblyLibrary)
        return References(target, assemblies.toList(), projects.toList(), outputs.toList(), found)
    }

    /**
     * A project with no assets file: one of the old format (.NET Framework, `packages.config`), whose references are its `Reference`
     * items — by `HintPath`, else from the reference assemblies of its `TargetFrameworkVersion` — and its `ProjectReference` items.
     * An SDK project that is not restored gets what it names by `HintPath` only.
     */
    private fun unrestored(request: Request): References {
        val msbuild = request.msbuild ?: return References.NONE
        val framework = msbuild.targetFrameworks.firstOrNull { it.equals(request.framework, ignoreCase = true) } ?: msbuild.targetFrameworks.firstOrNull()
        val assemblies = LinkedHashSet<File>()
        val found = ArrayList<Library>()
        if (msbuild.isLegacy) {
            val version = framework?.let(::netFrameworkVersion)
            if (version != null) {
                val reference = netFrameworkAssemblies(request.netFrameworkRoot?.let { File(it, version) }, msbuild, sdkProject = false)
                assemblies += reference
                netFrameworkLibrary(version, reference)?.let(found::add)
            }
        }
        val hinted = hintPaths(request)
        assemblies += hinted
        found += hinted.map(::assemblyLibrary)
        val projects = msbuild.projectReferences.filter { '$' !in it }.map { File(request.projectDirectory, it).normalize() }
        val outputs = if (framework == null) emptyList() else projects.mapNotNull { project ->
            built(project.parentFile ?: return@mapNotNull null, project.nameWithoutExtension + ".dll", framework) ?: builtLegacy(project.parentFile, project.nameWithoutExtension + ".dll")
        }
        return References(framework, assemblies.toList(), projects, outputs, found)
    }

    /** `.NETFramework 4.7.2`, as the reference assemblies are named in the folder of Visual Studio. */
    private fun netFrameworkLibrary(version: String, assemblies: List<File>): Library? =
        if (assemblies.isEmpty()) null else Library(".NETFramework", version.removePrefix("v"), LibraryKind.FRAMEWORK, assemblies)

    /**
     * An assembly named by its path (`HintPath`): a library of its own, by its name; one of a package of `packages.config`
     * (`packages/Newtonsoft.Json.13.0.1/lib/net45/Newtonsoft.Json.dll`) is that package.
     */
    fun assemblyLibrary(file: File): Library {
        var folder: File? = file.parentFile
        while (folder != null && folder.parentFile?.name?.equals("packages", ignoreCase = true) != true) folder = folder.parentFile
        val match = folder?.let { PACKAGES_CONFIG_FOLDER.matchEntire(it.name) }
        return if (match != null) Library(match.groupValues[1], match.groupValues[2], LibraryKind.PACKAGE, listOf(file))
        else Library(file.nameWithoutExtension, null, LibraryKind.ASSEMBLY, listOf(file))
    }

    /** `Newtonsoft.Json.13.0.1`, `Serilog.4.0.0-beta.1`: the folder `nuget restore` of `packages.config` makes for a package. */
    private val PACKAGES_CONFIG_FOLDER = Regex("""^(.+?)\.(\d+(?:\.\d+){1,3}(?:-[0-9A-Za-z.-]+)?)$""")

    private fun hintPaths(request: Request): List<File> = request.msbuild?.hintPaths?.values.orEmpty()
        .filter { '$' !in it }
        .map { File(request.projectDirectory, it).normalize() }
        .filter { it.isFile }

    /**
     * The reference assemblies of .NET Framework in [directory] (`v4.7.2`): `mscorlib` and what the project names by `Reference`
     * without a `HintPath`; an SDK project gets the ones its SDK adds by itself too (`Microsoft.NET.Sdk.FrameworkReferenceResolution`).
     */
    fun netFrameworkAssemblies(directory: File?, msbuild: MsBuildProject?, sdkProject: Boolean): List<File> {
        if (directory == null || !directory.isDirectory) return emptyList()
        val names = LinkedHashSet<String>()
        names += "mscorlib"
        if (sdkProject) names += SDK_NET_FRAMEWORK_REFERENCES
        msbuild?.assemblies.orEmpty().filter { it !in msbuild?.hintPaths.orEmpty() }.forEach { names += it }
        return names.mapNotNull { name -> File(directory, "$name.dll").takeIf { it.isFile } ?: File(directory, "Facades/$name.dll").takeIf { it.isFile } }
    }

    /** `net472` -> `v4.7.2`, `net48` -> `v4.8`, `net481` -> `v4.8.1`; null for what is not .NET Framework 4.x (`net8.0`, `netstandard2.0`). */
    fun netFrameworkVersion(framework: String): String? {
        val match = Regex("""^net(4)(\d)(\d)?$""").find(framework.lowercase()) ?: return null
        return "v" + match.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString(".")
    }

    /**
     * `packs/Microsoft.NETCore.App.Ref/10.0.12/ref/net10.0/` of the framework [reference] for `net10.0`: the newest patch of that
     * version. A project for a framework whose pack is not installed gets nothing here: restore has downloaded it ([downloadedPack]).
     */
    fun referencePack(dotnetRoot: File?, reference: String, target: String): List<File> {
        val version = Regex("""^net(\d+\.\d+)""").find(target.lowercase())?.groupValues?.get(1) ?: return emptyList()
        val packs = File(dotnetRoot ?: return emptyList(), "packs/$reference.Ref")
        val newest = packs.listFiles { file -> file.isDirectory && (file.name == version || file.name.startsWith("$version.")) }
            ?.maxWithOrNull(compareBy<File> { patch(it.name) }.thenBy { it.name }) ?: return emptyList()
        return dlls(File(newest, "ref/net$version"))
    }

    /**
     * The reference pack restore has put into the package folder (`downloadDependencies` of the framework in the assets file:
     * `Microsoft.NETCore.App.Ref` `[8.0.11]` for `net8.0` built with the SDK 10, which has no pack of 8.0).
     */
    private fun downloadedPack(folders: List<File>, framework: JsonObject?, reference: String, target: String): List<File> {
        val version = Regex("""^net(\d+\.\d+)""").find(target.lowercase())?.groupValues?.get(1) ?: return emptyList()
        val dependency = framework?.get("downloadDependencies")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it as? JsonObject }?.firstOrNull { it.get("name")?.asString.equals("$reference.Ref", ignoreCase = true) } ?: return emptyList()
        val packVersion = dependency.get("version")?.asString?.trim('[', ']', ' ')?.substringBefore(',') ?: return emptyList()
        return folders.map { dlls(File(it, "${reference.lowercase()}.ref/$packVersion/ref/net$version")) }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private fun dlls(directory: File): List<File> =
        directory.listFiles { file -> file.isFile && file.extension.equals("dll", ignoreCase = true) }?.sortedBy { it.name }.orEmpty()

    private fun patch(version: String): Int = version.split('.').getOrNull(2)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

    /** The reference assembly a build has left in `obj` (`obj/Debug/net9.0/ref/Lib.dll`), else the assembly itself in `bin`; the newest. */
    fun built(projectDirectory: File, assembly: String, target: String): File? {
        val candidates = ArrayList<File>()
        for ((folder, inner) in listOf("obj" to "ref/", "bin" to "")) {
            for (configuration in File(projectDirectory, folder).listFiles { file -> file.isDirectory }.orEmpty()) {
                File(configuration, "$target/$inner$assembly").takeIf { it.isFile }?.let(candidates::add)
            }
            if (candidates.isNotEmpty()) break
        }
        return candidates.maxByOrNull { it.lastModified() }
    }

    /** A project of the old format builds into `bin/<configuration>/` without a folder of the framework. */
    private fun builtLegacy(projectDirectory: File, assembly: String): File? =
        File(projectDirectory, "bin").listFiles { file -> file.isDirectory }.orEmpty().map { File(it, assembly) }.filter { it.isFile }.maxByOrNull { it.lastModified() }

    fun defaultNetFrameworkRoot(): File? {
        if (!SystemInfo.isWindows) return null
        val programFiles = System.getenv("ProgramFiles(x86)") ?: System.getenv("ProgramFiles") ?: return null
        return File(programFiles, "Reference Assemblies/Microsoft/Framework/.NETFramework").takeIf { it.isDirectory }
    }

    private const val NET_FRAMEWORK_PACKAGE = "Microsoft.NETFramework.ReferenceAssemblies."

    /** What the .NET SDK references by itself in a project for .NET Framework (`_SDKImplicitReference` of `Microsoft.NET.Sdk.FrameworkReferenceResolution.targets`). */
    private val SDK_NET_FRAMEWORK_REFERENCES = listOf(
        "System", "System.Data", "System.Drawing", "System.Xml", "System.Core", "System.Runtime.Serialization", "System.Xml.Linq", "System.Numerics",
        "System.IO.Compression.FileSystem",
    )

    private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject
}
