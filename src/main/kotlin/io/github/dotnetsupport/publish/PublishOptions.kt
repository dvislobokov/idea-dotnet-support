package io.github.dotnetsupport.publish

import io.github.dotnetsupport.sdk.SdkVersion
import java.io.File

/** What the Publish dialog and a ".NET Publish" run configuration hold: one `dotnet publish` of one project. */
data class PublishOptions(
    val projectPath: String,
    val configuration: String = "Release",
    /** Null: the only framework of the project; a multi-targeted project cannot be published without one. */
    val framework: String? = null,
    /** Null: portable (no `-r`), which leaves out everything that needs a runtime: self-contained, single file, trimming, ReadyToRun. */
    val runtime: String? = null,
    val selfContained: Boolean = false,
    val singleFile: Boolean = false,
    val trimmed: Boolean = false,
    val readyToRun: Boolean = false,
    /** Null: the folder the SDK picks, see [PublishCommand.defaultOutput]; relative paths are relative to the project directory. */
    val outputDir: String? = null,
    /** File name without `.pubxml` of a profile in `Properties/PublishProfiles`. */
    val profile: String? = null,
    /** `-t:PublishContainer`: an image in the local container daemon instead of only a folder. */
    val container: Boolean = false,
    val containerRepository: String? = null,
    val containerTag: String? = null,
) {
    val projectDirectory: File get() = File(projectPath).absoluteFile.parentFile
    val projectName: String get() = File(projectPath).nameWithoutExtension

    /** Stored in a run configuration and in the memory of the dialog under the names of the MSBuild properties they set. */
    fun toProperties(): Map<String, String> = buildMap {
        put(CONFIGURATION, configuration)
        framework?.let { put(FRAMEWORK, it) }
        runtime?.let { put(RUNTIME, it) }
        put(SELF_CONTAINED, selfContained.toString())
        put(SINGLE_FILE, singleFile.toString())
        put(TRIMMED, trimmed.toString())
        put(READY_TO_RUN, readyToRun.toString())
        outputDir?.let { put(OUTPUT, it) }
        profile?.let { put(PROFILE, it) }
        put(CONTAINER, container.toString())
        containerRepository?.let { put(CONTAINER_REPOSITORY, it) }
        containerTag?.let { put(CONTAINER_TAG, it) }
    }

    companion object {
        const val CONFIGURATION = "Configuration"
        const val FRAMEWORK = "TargetFramework"
        const val RUNTIME = "RuntimeIdentifier"
        const val SELF_CONTAINED = "SelfContained"
        const val SINGLE_FILE = "PublishSingleFile"
        const val TRIMMED = "PublishTrimmed"
        const val READY_TO_RUN = "PublishReadyToRun"
        const val OUTPUT = "PublishDir"
        const val PROFILE = "PublishProfile"
        const val CONTAINER = "PublishContainer"
        const val CONTAINER_REPOSITORY = "ContainerRepository"
        const val CONTAINER_TAG = "ContainerImageTag"

        fun fromProperties(projectPath: String, properties: Map<String, String>): PublishOptions {
            fun text(name: String) = properties[name]?.trim()?.ifEmpty { null }
            fun flag(name: String) = properties[name].toBoolean()
            return PublishOptions(
                projectPath, text(CONFIGURATION) ?: "Release", text(FRAMEWORK), text(RUNTIME), flag(SELF_CONTAINED), flag(SINGLE_FILE), flag(TRIMMED),
                flag(READY_TO_RUN), text(OUTPUT), text(PROFILE), flag(CONTAINER), text(CONTAINER_REPOSITORY), text(CONTAINER_TAG),
            )
        }
    }
}

/** `dotnet publish` from [PublishOptions]: pure, so the dialog shows the very command that runs. */
object PublishCommand {
    /** The runtime identifiers of the Target runtime list of Rider; any other can be typed. */
    val COMMON_RUNTIMES = listOf(
        "win-x64", "win-x86", "win-arm64", "linux-x64", "linux-arm", "linux-arm64", "linux-musl-x64", "linux-musl-arm64", "osx-x64", "osx-arm64",
    )

    /** The SDK band where `PublishContainer` works for any project; before it only for web / worker ones or with `EnableSdkContainerSupport`. */
    private val CONTAINERS_FOR_EVERY_PROJECT = SdkVersion.parse("8.0.200")!!

    /** `bin/<Configuration>/<TFM>/[<RID>/]publish` of the project: where the SDK publishes without `-o`. */
    fun defaultOutput(options: PublishOptions): File =
        File(options.projectDirectory, listOfNotNull("bin", options.configuration, options.framework, options.runtime, "publish").joinToString(File.separator))

    /** The folder the output lands in. */
    fun outputDirectory(options: PublishOptions): File {
        val explicit = options.outputDir ?: return defaultOutput(options)
        val file = File(explicit.replace('\\', File.separatorChar).replace('/', File.separatorChar))
        return if (file.isAbsolute) file else File(options.projectDirectory, file.path)
    }

    /** What is wrong with the options, for the dialog and the run configuration; null when they can run. */
    fun validate(options: PublishOptions): String? = when {
        options.projectPath.isBlank() -> "Project is not specified"
        options.configuration.isBlank() -> "Configuration is not specified"
        options.runtime == null && (options.selfContained || options.singleFile || options.trimmed || options.readyToRun) ->
            "Self-contained, single file, trimming and ReadyToRun need a target runtime"
        options.trimmed && !options.selfContained -> "Trimming needs the Self-Contained deployment mode"
        options.runtime?.any { it.isWhitespace() } == true -> "Target runtime cannot contain spaces"
        options.containerTag?.any { it.isWhitespace() } == true -> "Image tag cannot contain spaces"
        else -> null
    }

    /**
     * The arguments after `dotnet`. With a profile the switches are passed `true` and `false` both: the dialog has loaded the profile
     * into them, so what it shows wins over the profile, as global properties do over the file.
     */
    fun arguments(options: PublishOptions): List<String> = buildList {
        add("publish")
        add(options.projectPath)
        add("-c"); add(options.configuration)
        options.framework?.let { add("-f"); add(it) }
        options.runtime?.let { runtime ->
            add("-r"); add(runtime)
            // the default with -r has changed (self-contained up to SDK 6, framework-dependent since 8): always say which
            add("--self-contained"); add(options.selfContained.toString())
            flag(PublishOptions.SINGLE_FILE, options.singleFile, options.profile != null)
            flag(PublishOptions.TRIMMED, options.trimmed && options.selfContained, options.profile != null)
            flag(PublishOptions.READY_TO_RUN, options.readyToRun, options.profile != null)
        }
        add("-o"); add(outputDirectory(options).path)
        options.profile?.let { add("-p:${PublishOptions.PROFILE}=$it") }
        if (options.container) {
            add("-t:PublishContainer")
            options.containerRepository?.let { add("-p:${PublishOptions.CONTAINER_REPOSITORY}=$it") }
            options.containerTag?.let { add("-p:${PublishOptions.CONTAINER_TAG}=$it") }
        }
        add("-nologo")
        add("-clp:NoSummary") // every diagnostic is printed when it happens, as in a build
    }

    private fun MutableList<String>.flag(name: String, value: Boolean, explicitFalse: Boolean) {
        if (value || explicitFalse) add("-p:$name=$value")
    }

    /** The command as one line, for the preview of the dialog. */
    fun displayString(options: PublishOptions): String =
        (listOf("dotnet") + arguments(options)).joinToString(" ") { if (it.isEmpty() || it.any(Char::isWhitespace)) "\"$it\"" else it }

    /**
     * Why `-t:PublishContainer` cannot be used for the project; null when it can. [sdk] is the SDK the project is built with,
     * [webOrWorker] a web or worker SDK project, [enableSdkContainerSupport] the property of the project file.
     */
    fun containerUnsupportedReason(sdk: SdkVersion?, webOrWorker: Boolean, enableSdkContainerSupport: Boolean): String? = when {
        sdk == null -> "The .NET SDK is not found"
        sdk.major < 8 -> "Needs .NET SDK 8 or newer, the project is built with $sdk"
        sdk < CONTAINERS_FOR_EVERY_PROJECT && !webOrWorker && !enableSdkContainerSupport ->
            "Needs .NET SDK 8.0.200 or newer, or <EnableSdkContainerSupport>true</EnableSdkContainerSupport> in the project"
        else -> null
    }
}
