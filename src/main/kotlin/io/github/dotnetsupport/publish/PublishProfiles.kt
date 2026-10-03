package io.github.dotnetsupport.publish

import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.text.StringUtil
import java.io.File
import java.util.TreeMap

/**
 * The `.pubxml` files of `Properties/PublishProfiles`: the publish profiles of Visual Studio and Rider, applied by
 * `dotnet publish -p:PublishProfile=<name>`. Read into [PublishOptions] for the dialog and written from it by "Save as Profile".
 */
object PublishProfiles {
    const val EXTENSION = "pubxml"

    fun directory(projectDirectory: File): File = File(projectDirectory, "Properties" + File.separator + "PublishProfiles")

    fun file(projectDirectory: File, name: String): File = File(directory(projectDirectory), "$name.$EXTENSION")

    /** Names of the profiles of the project, without the extension. */
    fun list(projectDirectory: File): List<String> =
        directory(projectDirectory).listFiles { file -> file.isFile && file.extension.equals(EXTENSION, ignoreCase = true) }
            ?.map { it.nameWithoutExtension }?.sortedWith(String.CASE_INSENSITIVE_ORDER).orEmpty()

    /** The properties of every `PropertyGroup`, the later ones winning as in MSBuild; conditions are not evaluated. Empty when the file is broken. */
    fun parse(text: String): Map<String, String> {
        val root = try {
            JDOMUtil.load(text)
        } catch (_: Exception) {
            return emptyMap()
        }
        val properties = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        // Visual Studio 2017-2019 wrote the MSBuild namespace on <Project>, 2022 does not: names are compared without it
        for (group in root.children.filter { it.name == "PropertyGroup" }) {
            for (property in group.children) properties[property.name] = property.textTrim
        }
        return properties
    }

    /**
     * [base] with what the profile [name] says. A profile describes a whole publish: a runtime or a switch it leaves out is portable / off;
     * the configuration and the framework stay when it does not name them. Visual Studio writes the folder of a web project as
     * `PublishUrl`, which `dotnet publish` ignores: it becomes the output folder.
     */
    fun apply(base: PublishOptions, name: String, properties: Map<String, String>): PublishOptions {
        fun text(key: String) = properties[key]?.trim()?.ifEmpty { null }
        fun flag(key: String) = properties[key]?.trim().toBoolean()
        val runtime = text(PublishOptions.RUNTIME)
        return base.copy(
            configuration = text(PublishOptions.CONFIGURATION) ?: text("LastUsedBuildConfiguration") ?: base.configuration,
            framework = text(PublishOptions.FRAMEWORK) ?: base.framework,
            runtime = runtime,
            selfContained = runtime != null && flag(PublishOptions.SELF_CONTAINED),
            singleFile = runtime != null && flag(PublishOptions.SINGLE_FILE),
            trimmed = runtime != null && flag(PublishOptions.TRIMMED),
            readyToRun = runtime != null && flag(PublishOptions.READY_TO_RUN),
            outputDir = text(PublishOptions.OUTPUT) ?: text("PublishUrl"),
            profile = name,
            containerRepository = text(PublishOptions.CONTAINER_REPOSITORY) ?: base.containerRepository,
            containerTag = text(PublishOptions.CONTAINER_TAG) ?: base.containerTag,
        )
    }

    /** Reads the profile [name] of the project of [base] into it; [base] itself when the file is not there. */
    fun load(base: PublishOptions, name: String): PublishOptions {
        val file = file(base.projectDirectory, name)
        return if (file.isFile) apply(base, name, parse(file.readText())) else base.copy(profile = name)
    }

    /**
     * A folder profile as Visual Studio writes it (`_TargetId` Folder, `PublishProtocol` FileSystem, the folder relative to the project
     * with backslashes), so that it opens in Visual Studio and Rider too. The container switches are not a part of a profile.
     */
    fun write(options: PublishOptions): String {
        val properties = buildList {
            add(PublishOptions.CONFIGURATION to options.configuration)
            add(PublishOptions.OUTPUT to relativeFolder(options))
            add("PublishProtocol" to "FileSystem")
            add("_TargetId" to "Folder")
            options.framework?.let { add(PublishOptions.FRAMEWORK to it) }
            options.runtime?.let { add(PublishOptions.RUNTIME to it) }
            add(PublishOptions.SELF_CONTAINED to (options.runtime != null && options.selfContained).toString())
            if (options.runtime != null) {
                add(PublishOptions.SINGLE_FILE to options.singleFile.toString())
                add(PublishOptions.READY_TO_RUN to options.readyToRun.toString())
                add(PublishOptions.TRIMMED to (options.trimmed && options.selfContained).toString())
            }
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<!--\nhttps://go.microsoft.com/fwlink/?LinkID=208121.\n-->\n")
            append("<Project>\n  <PropertyGroup>\n")
            for ((name, value) in properties) append("    <$name>${StringUtil.escapeXmlEntities(value)}</$name>\n")
            append("  </PropertyGroup>\n</Project>\n")
        }
    }

    /** The output folder relative to the project when it is inside it, the way Visual Studio writes it: `bin\Release\net9.0\publish\`. */
    private fun relativeFolder(options: PublishOptions): String {
        val folder = PublishCommand.outputDirectory(options).normalize()
        val projectDirectory = options.projectDirectory.normalize()
        val relative = if (folder.startsWith(projectDirectory) && folder != projectDirectory) folder.relativeTo(projectDirectory).path else null
        return relative?.replace('/', '\\')?.plus('\\') ?: folder.path
    }
}
