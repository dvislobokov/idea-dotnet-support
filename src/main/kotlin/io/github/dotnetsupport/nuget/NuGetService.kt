package io.github.dotnetsupport.nuget

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.HttpRequests
import io.github.dotnetsupport.cli.CommandOutput
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** NuGet V3 feeds. [fetch] is the HTTP GET, replaceable in tests. Every method blocks: call from a background thread. */
class NuGetClient(private val fetch: (String) -> String = { HttpRequests.request(it).connectTimeout(10_000).readTimeout(20_000).readString() }) {
    private val indexes = ConcurrentHashMap<String, NuGetResponses.ServiceIndex>()

    private fun index(source: String): NuGetResponses.ServiceIndex? =
        runCatching { indexes.getOrPut(source) { NuGetResponses.parseServiceIndex(fetch(source)) } }.getOrNull()

    /** Packages from all [sources]; a package found in several feeds is taken from the first one. */
    fun search(query: String, includePrerelease: Boolean, sources: List<String>, take: Int = 40): List<NuGetPackageInfo> =
        sources.flatMap { source ->
            val url = index(source)?.searchUrl ?: return@flatMap emptyList()
            runCatching {
                NuGetResponses.parseSearch(fetch("$url?q=${URLEncoder.encode(query, Charsets.UTF_8)}&take=$take&prerelease=$includePrerelease&semVerLevel=2.0.0"))
            }.getOrDefault(emptyList())
        }.distinctBy { it.id.lowercase() }

    /** All published versions of a package, oldest first; empty when no feed has it. */
    fun versions(packageId: String, sources: List<String>): List<String> =
        sources.firstNotNullOfOrNull { source ->
            val base = index(source)?.packageBaseUrl ?: return@firstNotNullOfOrNull null
            runCatching { NuGetResponses.parseVersions(fetch("$base${packageId.lowercase()}/index.json")) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }.orEmpty()

    /** Description, license and dependencies of a concrete version; null when no feed has its `.nuspec`. */
    fun details(packageId: String, version: String, sources: List<String>): NuGetPackageDetails? {
        val id = packageId.lowercase()
        return sources.firstNotNullOfOrNull { source ->
            val base = index(source)?.packageBaseUrl ?: return@firstNotNullOfOrNull null
            runCatching { NuGetResponses.parseNuspec(fetch("$base$id/${version.lowercase()}/$id.nuspec")) }.getOrNull()
        }
    }
}

class InstalledPackage(
    val id: String,
    /** As written in the project file (may be floating: `6.*`), or centrally managed. */
    val declaredVersion: String?,
    /** What restore resolved it to; null until the project is restored. */
    val resolvedVersion: String?,
) {
    val version: String? get() = resolvedVersion ?: declaredVersion
}

/**
 * Text of the "Log" tab: the commands of the NuGet window and what they print, as it arrives.
 * Kept here so that nothing is lost while the tab is not created yet.
 */
class NuGetLog : CommandOutput {
    private val entries = ArrayList<Pair<String, Boolean>>()
    private val listeners = ArrayList<(String, Boolean) -> Unit>()

    /** [isError] text is printed in the error color. */
    @Synchronized fun print(text: String, isError: Boolean = false) {
        entries += text to isError
        if (entries.size > MAX_ENTRIES) entries.subList(0, entries.size - MAX_ENTRIES).clear()
        listeners.forEach { it(text, isError) }
    }

    override fun commandStarted(command: GeneralCommandLine) = print("> ${command.commandLineString}\n")
    override fun text(text: String, isError: Boolean) = print(text, isError)

    override fun commandFinished(exitCode: Int) =
        print(if (exitCode == 0) "Done.\n\n" else "Failed with exit code $exitCode.\n\n", isError = exitCode != 0)

    /** Replays what has been printed so far, then follows. */
    @Synchronized fun subscribe(listener: (String, Boolean) -> Unit) {
        entries.forEach { listener(it.first, it.second) }
        listeners += listener
    }

    @Synchronized fun clear() = entries.clear()

    private companion object {
        const val MAX_ENTRIES = 2000
    }
}

@Service(Service.Level.PROJECT)
class NuGetService(private val project: Project) {
    val client = NuGetClient()

    /** Commands run on behalf of the NuGet window and their output: the "Log" tab. */
    val log = NuGetLog()

    /** Project to show when the tool window is opened from the Solution view. */
    var requestedProject: VirtualFile? = null
    val requestListeners = ArrayList<() -> Unit>()

    fun projects(): List<Pair<String, VirtualFile>> {
        val solutions = SolutionService.getInstance(project)
        return solutions.solutionFiles()
            .flatMap { solutionFile -> solutions.solution(solutionFile).allProjects.mapNotNull { p -> p.resolveFile(solutionFile)?.let { p.name to it } } }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    fun installed(projectFile: VirtualFile): List<InstalledPackage> {
        val solutions = SolutionService.getInstance(project)
        val resolved = solutions.assets(projectFile).targets.firstOrNull()
        return solutions.msBuildProject(projectFile).packages
            .map { InstalledPackage(it.name, it.version ?: solutions.centralPackageVersion(projectFile, it.name), resolved?.findPackage(it.name)?.version) }
            .sortedBy { it.id.lowercase() }
    }

    /** Enabled HTTP feeds of all `nuget.config` levels, as the CLI sees them from the solution directory. Blocking. */
    fun sources(): List<String> {
        val directory = SolutionService.getInstance(project).solutionFiles().firstOrNull()?.parent?.path
        val configured = runCatching {
            NuGetResponses.parseSources(DotNetCli.execute(DotNetCli.commandLine(directory, "nuget", "list", "source", "--format", "short"), 30_000).stdout)
        }.getOrDefault(emptyList())
        // local folder feeds have no search service
        return configured.filter { it.startsWith("http://") || it.startsWith("https://") }.ifEmpty { listOf(NUGET_ORG) }
    }

    /** Installs the package into every project of [projectFiles], or changes its version where it is already referenced. */
    fun install(projectFiles: List<VirtualFile>, packageId: String, version: String, onSuccess: () -> Unit) =
        run("Installing $packageId $version", projectFiles, onSuccess) { listOf("add", it.path, "package", packageId, "--version", version) }

    fun remove(projectFiles: List<VirtualFile>, packageId: String, onSuccess: () -> Unit) =
        run("Removing $packageId", projectFiles, onSuccess) { listOf("remove", it.path, "package", packageId) }

    private fun run(title: String, projectFiles: List<VirtualFile>, onSuccess: () -> Unit, arguments: (VirtualFile) -> List<String>) {
        if (projectFiles.isEmpty()) return
        val commands = DotNetCli.commandLinesOrNotify(project, title) {
            projectFiles.map { DotNetCli.commandLine(it.parent.path, *arguments(it).toTypedArray()) }
        } ?: return
        DotNetCli.runInBackground(project, title, commands, refresh = projectFiles.map { File(it.parent.path) }, output = log, onSuccess = onSuccess)
    }

    private fun workDirectory(): String? = SolutionService.getInstance(project).solutionFiles().firstOrNull()?.parent?.path

    /** Sources of all `nuget.config` levels with their names and state. Blocking. */
    fun sourceList(): List<NuGetSource> {
        fun output(vararg arguments: String) = runCatching { DotNetCli.execute(DotNetCli.commandLine(workDirectory(), *arguments), 30_000).stdout }.getOrDefault("")
        return NuGetResponses.parseSourceList(output("nuget", "list", "source"), output("nuget", "list", "source", "--format", "short"))
    }

    /** The `nuget.config` files that apply to the solution, the most specific first. Blocking. */
    fun configPaths(): List<String> =
        runCatching { DotNetCli.execute(DotNetCli.commandLine(workDirectory(), "nuget", "config", "paths"), 30_000).stdout }
            .getOrDefault("").lines().map { it.trim() }.filter { it.isNotEmpty() && File(it).isFile }

    /** `dotnet nuget add | remove | enable | disable source ...` */
    fun changeSources(title: String, vararg arguments: String, onSuccess: () -> Unit) {
        val commands = DotNetCli.commandLinesOrNotify(project, title) { listOf(DotNetCli.commandLine(workDirectory(), "nuget", *arguments)) } ?: return
        DotNetCli.runInBackground(project, title, commands, output = log, onSuccess = onSuccess)
    }

    companion object {
        const val NUGET_ORG = "https://api.nuget.org/v3/index.json"

        fun getInstance(project: Project): NuGetService = project.service()
    }
}
