package io.github.dotnetsupport.nuget

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
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
class NuGetClient(private val fetch: (url: String, source: String) -> String = ::fetchWithCredentials) {
    private val indexes = ConcurrentHashMap<String, NuGetResponses.ServiceIndex>()

    private fun index(source: String): NuGetResponses.ServiceIndex? =
        runCatching { indexes.getOrPut(source) { NuGetResponses.parseServiceIndex(fetch(source, source)) } }.getOrNull()

    /** Packages from all [sources]; a package found in several feeds is taken from the first one. [packageType]: `Template`, `DotnetTool`. */
    fun search(query: String, includePrerelease: Boolean, sources: List<String>, take: Int = 40, packageType: String? = null): List<NuGetPackageInfo> =
        sources.flatMap { source ->
            val url = index(source)?.searchUrl ?: return@flatMap emptyList()
            runCatching {
                NuGetResponses.parseSearch(fetch("$url?q=${URLEncoder.encode(query, Charsets.UTF_8)}&take=$take&prerelease=$includePrerelease&semVerLevel=2.0.0" + packageType?.let { "&packageType=$it" }.orEmpty(), source))
            }.getOrDefault(emptyList())
        }.distinctBy { it.id.lowercase() }

    /** All published versions of a package, oldest first; empty when no feed has it. */
    fun versions(packageId: String, sources: List<String>): List<String> =
        sources.firstNotNullOfOrNull { source ->
            val base = index(source)?.packageBaseUrl ?: return@firstNotNullOfOrNull null
            runCatching { NuGetResponses.parseVersions(fetch("$base${packageId.lowercase()}/index.json", source)) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }.orEmpty()

    /** Description, license and dependencies of a concrete version; null when no feed has its `.nuspec`. */
    fun details(packageId: String, version: String, sources: List<String>): NuGetPackageDetails? {
        val id = packageId.lowercase()
        return sources.firstNotNullOfOrNull { source ->
            val base = index(source)?.packageBaseUrl ?: return@firstNotNullOfOrNull null
            runCatching { NuGetResponses.parseNuspec(fetch("$base$id/${version.lowercase()}/$id.nuspec", source)) }.getOrNull()
        }
    }
}

/** HTTP GET of a feed resource; a private feed gets the credentials stored for its source (Basic authentication). */
private fun fetchWithCredentials(url: String, source: String): String {
    val credentials = NuGetCredentialStore.get(source)
    return HttpRequests.request(url).connectTimeout(10_000).readTimeout(20_000)
        .tuner { connection ->
            val password = credentials?.getPasswordAsString()
            if (credentials?.userName != null && password != null) {
                val token = java.util.Base64.getEncoder().encodeToString("${credentials.userName}:$password".toByteArray())
                connection.setRequestProperty("Authorization", "Basic $token")
            }
        }
        .readString()
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

/** A package of [projectFile] that can go from the version [from] to the newer [to]. */
class PackageUpgrade(val projectName: String, val projectFile: VirtualFile, val packageId: String, val from: String, val to: String)

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

    override fun commandStarted(command: GeneralCommandLine) = print("> ${DotNetCli.displayString(command)}\n")
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

    /** "Manage NuGet Packages for Solution": the window is asked for the solution scope; reset once shown. */
    var solutionRequested = false
    val requestListeners = ArrayList<() -> Unit>()

    /** Called on EDT when packages were changed from outside of the window, e.g. by "Upgrade Packages in Solution". */
    val packagesChangedListeners = ArrayList<() -> Unit>()

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

    /** Packages of the solution that have a newer stable version in the feeds. Blocking: asks the feeds for every package. */
    fun outdated(): List<PackageUpgrade> {
        val feeds = sources()
        val latest = HashMap<String, NuGetVersion?>()
        return projects().flatMap { (name, file) ->
            installed(file).mapNotNull { pkg ->
                // a floating or a missing version is not a version to upgrade from
                val current = pkg.version?.let(NuGetVersion::parse) ?: return@mapNotNull null
                val newest = latest.getOrPut(pkg.id.lowercase()) { NuGetVersion.latest(client.versions(pkg.id, feeds), includePrerelease = false)?.let(NuGetVersion::parse) }
                if (newest != null && current < newest) PackageUpgrade(name, file, pkg.id, current.text, newest.text) else null
            }
        }
    }

    fun upgrade(upgrades: List<PackageUpgrade>, onSuccess: () -> Unit) {
        if (upgrades.isEmpty()) return
        val title = "Upgrading NuGet packages"
        val commands = DotNetCli.commandLinesOrNotify(project, title) {
            upgrades.map { DotNetCli.commandLine(it.projectFile.parent.path, "add", it.projectFile.path, "package", it.packageId, "--version", it.to) }
        } ?: return
        DotNetCli.runInBackground(project, title, commands, refresh = upgrades.map { File(it.projectFile.parent.path) }.distinct(), output = log, onSuccess = onSuccess)
    }

    /** The folders NuGet keeps packages and caches in: `global-packages`, `http-cache`, `temp`, `plugins-cache`. Blocking. */
    fun localFolders(): List<Pair<String, String>> =
        runCatching { DotNetCli.execute(DotNetCli.commandLine(workDirectory(), "nuget", "locals", "all", "--list", "--force-english-output"), 30_000).stdout }
            .getOrDefault("").lines().mapNotNull { line ->
                val name = line.substringBefore(": ", "").trim()
                val path = line.substringAfter(": ", "").trim()
                if (name.isEmpty() || path.isEmpty()) null else name to path
            }

    /** `dotnet nuget locals <name> --clear` */
    fun clearLocalFolder(name: String, onSuccess: () -> Unit) {
        val title = "Clearing NuGet $name"
        val commands = DotNetCli.commandLinesOrNotify(project, title) { listOf(DotNetCli.commandLine(workDirectory(), "nuget", "locals", name, "--clear")) } ?: return
        DotNetCli.runInBackground(project, title, commands, output = log, onSuccess = onSuccess)
    }

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

    /** `dotnet nuget add | update | remove | enable | disable source ...`, one argument list per command. */
    fun changeSources(title: String, commands: List<List<String>>, onSuccess: () -> Unit) {
        val commandLines = DotNetCli.commandLinesOrNotify(project, title) { commands.map { DotNetCli.commandLine(workDirectory(), "nuget", *it.toTypedArray()) } } ?: return
        DotNetCli.runInBackground(project, title, commandLines, output = log, onSuccess = onSuccess)
    }

    private fun declaringConfig(sourceName: String): File? =
        configPaths().map(::File).firstOrNull { NuGetConfigEditor.declares(it.readText(), sourceName) }

    /** "Allow insecure connections" and "Disable TLS certificate validation" of a source, from the config file that declares it. Blocking. */
    fun sourceFlags(sourceName: String): Pair<Boolean, Boolean> {
        val config = declaringConfig(sourceName)?.readText() ?: return false to false
        return NuGetConfigEditor.flag(config, sourceName, NuGetConfigEditor.ALLOW_INSECURE) to NuGetConfigEditor.flag(config, sourceName, NuGetConfigEditor.DISABLE_TLS)
    }

    /** Adds or updates a source: the CLI for what it supports, then the attributes it has no options for, then the IDE copy of the credentials. */
    fun saveSource(settings: NuGetSourceSettings, existing: NuGetSource?, onSuccess: () -> Unit) {
        val commands = buildList {
            add(settings.cliArguments(isNew = existing == null))
            if (settings.isEnabled != (existing?.isEnabled ?: true)) add(listOf(if (settings.isEnabled) "enable" else "disable", "source", settings.name))
        }
        val title = (if (existing == null) "Adding" else "Updating") + " NuGet feed ${settings.name}"
        changeSources(title, commands) {
            ApplicationManager.getApplication().executeOnPooledThread {
                declaringConfig(settings.name)?.let { file ->
                    val text = file.readText()
                    val updated = NuGetConfigEditor.setFlag(
                        NuGetConfigEditor.setFlag(text, settings.name, NuGetConfigEditor.ALLOW_INSECURE, settings.allowInsecureConnections),
                        settings.name, NuGetConfigEditor.DISABLE_TLS, settings.disableTlsCertificateValidation,
                    )
                    if (updated != text) {
                        file.writeText(updated)
                        VfsUtil.markDirtyAndRefresh(true, false, false, file)
                    }
                }
                // an edit that leaves the credentials empty keeps the stored ones
                if (settings.password != null || existing == null) NuGetCredentialStore.set(settings.url, settings.user, settings.password)
                ApplicationManager.getApplication().invokeLater(onSuccess, project.disposed)
            }
        }
    }

    companion object {
        const val NUGET_ORG = "https://api.nuget.org/v3/index.json"

        fun getInstance(project: Project): NuGetService = project.service()
    }
}
