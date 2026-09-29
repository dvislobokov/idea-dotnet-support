package io.github.dotnetsupport.index

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * The assemblies a project is compiled against, which is what its code may name: the `compile` assets of its packages (the
 * transitive ones are among them, the private ones of a package are not), the reference packs of the frameworks it refers to, the
 * reference assemblies of the projects of the solution it depends on, when they are built. From `obj/project.assets.json`, where
 * restore has written all of it down; nothing is resolved here.
 */
object ProjectAssemblies {
    class Request(
        /** `obj/project.assets.json` as text. */
        val assets: CharSequence,
        /** The directory of the project file. */
        val projectDirectory: File,
        /** Where `dotnet` is installed: `packs` with the reference assemblies is under it. */
        val dotnetRoot: File?,
        /** The framework chosen in the toolbar; null or unknown to the project: its first one. */
        val framework: String? = null,
    )

    fun of(request: Request): List<File> {
        val root = runCatching { JsonParser.parseString(request.assets.toString()) as? JsonObject }.getOrNull() ?: return emptyList()
        val targets = root.obj("targets") ?: return emptyList()
        // `net9.0` and not `net9.0/win-x64`: the target of a runtime has the same assemblies to compile against
        val names = targets.keySet().filter { '/' !in it }
        val target = names.firstOrNull { it.equals(request.framework, ignoreCase = true) } ?: names.firstOrNull() ?: return emptyList()
        val libraries = root.obj("libraries")
        val folders = root.obj("packageFolders")?.keySet().orEmpty().map(::File)
        val found = LinkedHashSet<File>()

        for ((id, value) in targets.obj(target)?.entrySet().orEmpty()) {
            val library = value as? JsonObject ?: continue
            val compiled = library.obj("compile")?.keySet().orEmpty().filter { it.endsWith(".dll", ignoreCase = true) }
            when (library.get("type")?.asString) {
                "package" -> {
                    val path = libraries?.obj(id)?.get("path")?.asString ?: id.lowercase()
                    for (relative in compiled) folders.map { File(File(it, path), relative) }.firstOrNull { it.isFile }?.let(found::add)
                }
                "project" -> {
                    val project = libraries?.obj(id)?.get("path")?.asString ?: continue
                    val directory = File(request.projectDirectory, project).parentFile ?: continue
                    for (relative in compiled) built(directory, File(relative).name, target)?.let(found::add)
                }
            }
        }

        val frameworks = root.obj("project")?.obj("frameworks")
        val declared = frameworks?.keySet()?.firstOrNull { it.equals(target, ignoreCase = true) } ?: frameworks?.keySet()?.firstOrNull()
        val references = declared?.let { frameworks?.obj(it)?.obj("frameworkReferences")?.keySet() }.orEmpty()
        for (reference in references) found += referencePack(request.dotnetRoot, reference, target)
        return found.toList()
    }

    /**
     * `packs/Microsoft.NETCore.App.Ref/10.0.12/ref/net10.0/` of the framework [reference] for `net10.0`: the newest patch of that
     * version. A project for a framework whose pack is not installed (it is restored into the package folder then) gets nothing here.
     */
    fun referencePack(dotnetRoot: File?, reference: String, target: String): List<File> {
        val version = Regex("""^net(\d+\.\d+)""").find(target.lowercase())?.groupValues?.get(1) ?: return emptyList()
        val packs = File(dotnetRoot ?: return emptyList(), "packs/$reference.Ref")
        val newest = packs.listFiles { file -> file.isDirectory && (file.name == version || file.name.startsWith("$version.")) }
            ?.maxWithOrNull(compareBy<File> { patch(it.name) }.thenBy { it.name }) ?: return emptyList()
        return File(newest, "ref/net$version").listFiles { file -> file.isFile && file.extension.equals("dll", ignoreCase = true) }?.sortedBy { it.name }.orEmpty()
    }

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

    private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject
}
