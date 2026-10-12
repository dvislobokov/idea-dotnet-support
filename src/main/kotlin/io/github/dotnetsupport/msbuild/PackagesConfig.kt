package io.github.dotnetsupport.msbuild

import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.VirtualFile

/** `<package id="Newtonsoft.Json" version="13.0.3" targetFramework="net48" />` of a `packages.config`. */
data class PackagesConfigEntry(val id: String, val version: String?, val targetFramework: String?, val developmentDependency: Boolean = false)

/**
 * `packages.config`: the package list of legacy (non-SDK) projects, restored by `nuget.exe` / Visual Studio into a `packages` folder
 * next to the solution. It has no `project.assets.json` and no transitive tree: the file lists every package, the dependencies too.
 * `dotnet add package` does not know the format (it would write a PackageReference into the project), so the plugin only reads it.
 */
object PackagesConfig {
    const val FILE_NAME = "packages.config"

    /** Packages in the order of the file; a broken file is an empty list. */
    fun parse(text: CharSequence): List<PackagesConfigEntry> {
        val root = try {
            JDOMUtil.load(text)
        } catch (_: Exception) {
            return emptyList()
        }
        return root.children.filter { it.name == "package" }.mapNotNull { element ->
            val id = element.getAttributeValue("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            PackagesConfigEntry(
                id,
                element.getAttributeValue("version")?.trim()?.takeIf { it.isNotEmpty() },
                element.getAttributeValue("targetFramework")?.trim()?.takeIf { it.isNotEmpty() },
                element.getAttributeValue("developmentDependency").equals("true", ignoreCase = true),
            )
        }.distinctBy { it.id.lowercase() }
    }

    /**
     * The `packages.config` of a project: `packages.<ProjectName>.config` first, as NuGet looks for it (several projects in one
     * directory), then `packages.config` next to the project file.
     */
    fun find(projectFile: VirtualFile): VirtualFile? {
        val directory = projectFile.parent ?: return null
        val named = directory.findChild("packages.${projectFile.nameWithoutExtension}.config")
        return (named ?: directory.findChild(FILE_NAME))?.takeIf { !it.isDirectory }
    }
}
