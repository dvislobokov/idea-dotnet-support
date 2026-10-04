package io.github.dotnetsupport.build

import com.google.gson.JsonParser
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.nio.charset.StandardCharsets

/** An installation of Visual Studio or of its Build Tools, as `vswhere` describes it. */
data class VisualStudioInstance(val displayName: String, val version: String, val path: String, val isPrerelease: Boolean = false) {
    private val major: Int? get() = version.substringBefore('.').toIntOrNull()

    /** `Visual Studio Build Tools 2022 (17.14)` */
    val title: String get() = "$displayName (${version.split('.').take(2).joinToString(".")})" + if (isPrerelease) " Preview" else ""

    /** The 64-bit MSBuild, which Visual Studio 2022 builds with itself; the 32-bit one runs out of memory on big solutions. */
    fun msBuild(): File? = MSBUILD_PATHS.map { File(path, it) }.firstOrNull { it.isFile }

    /** `$(VSToolsPath)`: the targets of Visual Studio (WebApplications, ...) that projects of the old format import. */
    fun vsToolsPath(): File? = major?.let { File(path, "MSBuild\\Microsoft\\VisualStudio\\v$it.0") }?.takeIf { it.isDirectory }

    companion object {
        private val MSBUILD_PATHS = listOf("MSBuild\\Current\\Bin\\amd64\\MSBuild.exe", "MSBuild\\Current\\Bin\\MSBuild.exe")
    }
}

/**
 * Which MSBuild builds a project: the one of the .NET SDK (`dotnet build`) or `MSBuild.exe` of Visual Studio / Build Tools. The SDK
 * builds a project of the old format only in part: it skips the XAML of WPF (the build then fails without `Main`), and it has no targets
 * of Visual Studio (web projects, C++). As in Rider, the toolset is chosen in Settings | Tools | .NET | Toolset and Build; "Auto" builds
 * those projects, and the solutions with them, by the newest Visual Studio found, everything else by the SDK.
 */
object VisualStudioToolset {
    /** [DotNetBuildOptions.Settings.msBuild]: auto, the .NET SDK always, or the path of an `MSBuild.exe`. */
    const val AUTO = ""
    const val DOTNET = "dotnet"

    private const val LOG_CATEGORY = "MSBuild"
    private val MISSING_NOTIFIED = Key.create<Boolean>("dotnet.visualStudioMissingNotified")

    @Volatile private var cached: List<VisualStudioInstance>? = null

    /** `vswhere -format json`, newest first; instances without MSBuild are left out by `-requires` already. */
    fun parse(json: String): List<VisualStudioInstance> {
        val array = runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val instance = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            fun text(name: String): String? = instance.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            val path = text("installationPath") ?: return@mapNotNull null
            VisualStudioInstance(text("displayName") ?: File(path).name, text("installationVersion").orEmpty(), path, instance.get("isPrerelease")?.asBoolean == true)
        }.sortedWith(compareBy<VisualStudioInstance> { it.isPrerelease }.thenByDescending { versionKey(it.version) })
    }

    private fun versionKey(version: String): String = version.split('.').joinToString(".") { it.padStart(8, '0') }

    fun vswhere(): File? {
        if (!SystemInfo.isWindows) return null
        return File(System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)", "Microsoft Visual Studio\\Installer\\vswhere.exe").takeIf { it.isFile }
    }

    /** The installations on this machine; blocking on the first call (a run of `vswhere`, ~0.2 s), not for the EDT. */
    fun instances(): List<VisualStudioInstance> = cached ?: (if (ApplicationManager.getApplication().isUnitTestMode) emptyList() else detect().also { cached = it })

    /** What [instances] has found so far: for the EDT, which must not run `vswhere`. */
    fun knownInstances(): List<VisualStudioInstance> = cached.orEmpty()

    fun refresh(): List<VisualStudioInstance> = detect().also { cached = it }

    @TestOnly
    fun setInstancesForTests(instances: List<VisualStudioInstance>?) {
        cached = instances
    }

    private fun detect(): List<VisualStudioInstance> {
        val vswhere = vswhere() ?: return emptyList()
        val command = GeneralCommandLine(vswhere.path, "-all", "-prerelease", "-products", "*", "-requires", "Microsoft.Component.MSBuild", "-format", "json", "-utf8")
            .withCharset(StandardCharsets.UTF_8)
        return try {
            val output = DotNetCli.execute(command, 30_000)
            parse(output.stdout).filter { it.msBuild() != null }
                .also { PluginLog.info(LOG_CATEGORY, "Visual Studio: ${it.joinToString { i -> "${i.title} at ${i.path}" }.ifEmpty { "none" }}") }
        } catch (e: ExecutionException) {
            PluginLog.warn(LOG_CATEGORY, "vswhere", e)
            emptyList()
        }
    }

    /**
     * The `MSBuild.exe` to build with, null for `dotnet`: [setting] names one, or it is "Auto" and the target [needsVisualStudio]. A setting
     * whose file is gone counts as "Auto". [msBuilds] are the ones of the installations, newest first.
     */
    fun choose(setting: String?, msBuilds: List<File>, needsVisualStudio: Boolean): File? = when {
        setting == DOTNET -> null
        !setting.isNullOrBlank() && File(setting).isFile -> File(setting)
        needsVisualStudio -> msBuilds.firstOrNull()
        else -> null
    }

    /** A project of the old format, or a solution with one: what the .NET SDK cannot build in full. */
    fun needsVisualStudio(project: Project, target: VirtualFile): Boolean {
        val solutions = SolutionService.getInstance(project)
        if (DotNetProjects.isProjectFile(target)) return solutions.msBuildProject(target).isLegacy
        return solutions.solution(target).allProjects.mapNotNull { it.resolveFile(target) }.any { DotNetProjects.isProjectFile(it) && solutions.msBuildProject(it).isLegacy }
    }

    /** The MSBuild for a build of [target]; blocking (`vswhere`, reading the projects of a solution). Says once if Visual Studio is wanted and not there. */
    fun msBuildFor(project: Project, target: VirtualFile): File? {
        val setting = DotNetBuildOptions.getInstance(project).state.msBuild
        if (setting == DOTNET || !SystemInfo.isWindows) return null
        val needs = needsVisualStudio(project, target)
        val chosen = choose(setting, instances().mapNotNull { it.msBuild() }, needs)
        if (chosen == null && needs) notifyMissing(project)
        return chosen
    }

    /** The newest `$(VSToolsPath)` on this machine, for the evaluation of projects of the old format; null when Visual Studio is not there. */
    fun vsToolsPath(): String? = instances().firstNotNullOfOrNull { it.vsToolsPath() }?.path

    private fun notifyMissing(project: Project) {
        if (project.getUserData(MISSING_NOTIFIED) == true) return
        project.putUserData(MISSING_NOTIFIED, true)
        PluginLog.warn(LOG_CATEGORY, "Visual Studio / Build Tools not found: projects of the old format are built by the .NET SDK")
        DotNetCli.notifyError(project, "Visual Studio Build Tools Not Found",
            "Projects of the old format are built by the .NET SDK, which skips WPF markup, web and C++ targets. Install Visual Studio or Build Tools with the .NET desktop or web workload.") {
            addAction(NotificationAction.createSimple("Download Build Tools") { BrowserUtil.browse(BUILD_TOOLS_URL) })
        }
    }

    const val BUILD_TOOLS_URL = "https://visualstudio.microsoft.com/downloads/#build-tools-for-visual-studio-2022"

    /**
     * The arguments of `MSBuild.exe` for a `dotnet build` / `clean` / `msbuild` command; null for any other (`publish`, `restore`), which stays
     * with the SDK. `dotnet build` restores and builds in parallel by default, `MSBuild.exe` does neither unless told; a project with
     * `packages.config` is restored only with `RestorePackagesConfig`. An `msbuild` command is MSBuild syntax already.
     */
    fun translate(arguments: List<String>): List<String>? {
        val verb = arguments.firstOrNull() ?: return null
        if (verb == "msbuild") return arguments.drop(1)
        if (verb != "build" && verb != "clean") return null
        val rest = arguments.drop(1)
        val result = ArrayList<String>()
        var rebuild = false
        var restore = verb == "build"
        var i = 0
        while (i < rest.size) {
            val argument = rest[i]
            val value = rest.getOrNull(i + 1)
            when {
                argument == "--no-incremental" -> rebuild = true
                argument == "--no-restore" -> restore = false
                argument == "--no-dependencies" -> result += "-p:BuildProjectReferences=false"
                argument in VALUE_OPTIONS && value != null -> { result += VALUE_OPTIONS.getValue(argument) + value; i++ }
                argument.startsWith("--property:") -> result += "-p:" + argument.removePrefix("--property:")
                else -> result += argument
            }
            i++
        }
        val target = if (verb == "clean") "Clean" else if (rebuild) "Rebuild" else "Build"
        return buildList {
            add("-t:$target")
            if (restore) { add("-restore"); add("-p:RestorePackagesConfig=true") }
            if (result.none { it.startsWith("-m") || it.startsWith("-maxcpucount") }) add("-m")
            // the default of MSBuild.exe is normal, of `dotnet build` minimal: the Build window would get every task
            if (result.none { it.startsWith("-v:") || it.startsWith("-verbosity:") }) add("-v:m")
            addAll(result)
        }
    }

    private val VALUE_OPTIONS = mapOf(
        "-c" to "-p:Configuration=", "--configuration" to "-p:Configuration=",
        "-f" to "-p:TargetFramework=", "--framework" to "-p:TargetFramework=",
        "-r" to "-p:RuntimeIdentifier=", "--runtime" to "-p:RuntimeIdentifier=",
        "-v" to "-v:", "--verbosity" to "-v:",
    )

    fun commandLine(msBuild: File, workDirectory: String, arguments: List<String>): GeneralCommandLine =
        GeneralCommandLine(msBuild.path).withParameters(arguments).withWorkDirectory(workDirectory).withCharset(StandardCharsets.UTF_8)

    /**
     * The command line of a build of [target]: `MSBuild.exe` when [msBuildFor] picks one and the command is one it runs, else `dotnet`.
     * Blocking, not for the EDT.
     */
    @Throws(ExecutionException::class)
    fun buildCommandLine(project: Project, target: VirtualFile, workDirectory: String, arguments: List<String>): GeneralCommandLine {
        val translated = translate(arguments)
        val msBuild = if (translated != null) msBuildFor(project, target) else null
        if (msBuild == null || translated == null) return DotNetCli.commandLine(workDirectory, *arguments.toTypedArray())
        return commandLine(msBuild, workDirectory, translated + solutionDirectory(project, target, translated))
    }

    /**
     * `SolutionDir` of the solution a project is built from, as Visual Studio passes it: the restore of `packages.config` fails without it
     * ("Solution not found ... /p:SolutionDir"), and projects of the old format refer to `$(SolutionDir)packages\`. Nothing for a solution.
     */
    fun solutionDirectory(project: Project, target: VirtualFile, arguments: List<String>): List<String> {
        if (!DotNetProjects.isProjectFile(target) || arguments.any { it.startsWith("-p:SolutionDir=", ignoreCase = true) }) return emptyList()
        val solutions = SolutionService.getInstance(project)
        val solution = solutions.solutionFiles().firstOrNull { file -> solutions.solution(file).allProjects.any { it.resolveFile(file) == target } } ?: return emptyList()
        return listOf(solutionDirArgument(File(solution.parent.path)))
    }

    /** With the separator at the end, as `$(SolutionDir)` always is in Visual Studio. */
    fun solutionDirArgument(directory: File): String = "-p:SolutionDir=" + directory.path.trimEnd(File.separatorChar) + File.separator

    /** Looks for Visual Studio in the background when a project opens, so neither a build nor the settings page waits for `vswhere`. */
    class Detect : ProjectActivity {
        override suspend fun execute(project: Project) {
            if (SystemInfo.isWindows && !ApplicationManager.getApplication().isUnitTestMode) instances()
        }
    }
}
