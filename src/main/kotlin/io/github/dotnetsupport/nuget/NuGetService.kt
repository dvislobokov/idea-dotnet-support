package io.github.dotnetsupport.nuget

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.HttpRequests
import io.github.dotnetsupport.build.BuildViewCommandOutput
import io.github.dotnetsupport.cli.CommandOutput
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.msbuild.PackagesConfigEntry
import io.github.dotnetsupport.solution.SOLUTION_EXTENSIONS
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * NuGet V3 feeds. Every request and every answer goes to the journal (category [LOG_CATEGORY]) and to [onEvent] (the Log tab of the
 * NuGet window): the URL, the route the IDE takes to it (direct or which proxy, with or without stored credentials), the size and the
 * time of the answer, and every failure by class, cause and the setting of the IDE that usually fixes it ([NuGetNetwork]) — the
 * feeds are read by the HTTP client of the IDE, not by the CLI, so "the CLI works" says nothing about them. Nothing is said once
 * per session: a feed that is down says so on every request, that is what a journal is for. [fetch] is the HTTP GET, replaceable in
 * tests (the last parameter, so that a trailing lambda is the fetch); [route] describes the route of a URL. Every method blocks:
 * call from a background thread.
 *
 * [fallback] is the second way to a feed, the .NET helper ([NuGetHelper]): it searches and lists versions of a local folder feed, which
 * has no HTTP API, and of a feed the IDE has failed to reach by a fault of its route ([NuGetNetwork.isRouteFailure]: proxy, certificate,
 * unknown host, credentials). Such a feed goes to the helper straight away for [HELPER_FIRST_MS], then the IDE is tried again.
 */
class NuGetClient(
    private val onEvent: (text: String, isError: Boolean) -> Unit = { _, _ -> },
    private val route: (url: String, source: String) -> String = NuGetNetwork::routeOf,
    private val fallback: NuGetFallback? = null,
    private val fetch: (url: String, source: String) -> String = ::fetchWithCredentials,
) {
    /** The second way to a feed; it throws with a message for the user when it cannot get there either. */
    interface NuGetFallback {
        fun search(source: String, query: String, includePrerelease: Boolean, take: Int, packageType: String?): List<NuGetPackageInfo>
        fun versions(source: String, packageId: String): List<String>
    }

    private val indexes = ConcurrentHashMap<String, NuGetResponses.ServiceIndex>()

    /** The last failure of every feed by source URL, cleared by the next answer of it: the window says "feed did not answer" by it. */
    private val failures = ConcurrentHashMap<String, String>()

    /** Feeds the IDE could not reach by a fault of its route, with the time and the failure: they go to [fallback] for a while. */
    private val routeFailures = ConcurrentHashMap<String, Pair<Long, String>>()

    /** The network of the IDE is described once, in front of the first request of the client. */
    private val ideNetworkSaid = java.util.concurrent.atomic.AtomicBoolean()

    fun lastFailure(source: String): String? = failures[source]

    /** Why [source] goes to the helper rather than to the HTTP client of the IDE; null when it does not. */
    private fun helperFirst(source: String): String? {
        if (fallback == null) return null
        if (isLocal(source)) return "a local folder feed"
        val (time, failure) = routeFailures[source] ?: return null
        val ago = System.currentTimeMillis() - time
        return if (ago < HELPER_FIRST_MS) "the IDE failed to reach the feed ${ago / 1000} s ago: $failure" else null
    }

    /** Why [source] goes to the helper after the IDE has failed on it; null when that failure is not one the helper can help with. */
    private fun helperAfter(source: String): String? = if (fallback == null) null else routeFailures[source]?.let { "the IDE could not reach the feed: ${it.second}" }

    /** What the helper does instead of the IDE, said in the journal both ways; null when it fails too. */
    private fun <T> viaHelper(source: String, what: String, why: String, request: (NuGetFallback) -> T): T? {
        val helper = fallback ?: return null
        info("$what in $source via the .NET helper: $why")
        val started = System.nanoTime()
        return try {
            request(helper).also {
                failures.remove(source)
                info("$what in $source via the .NET helper: answered in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            failures[source] = (failures[source]?.let { "$it; " }.orEmpty()) + "the .NET helper: $message"
            warn("$what in $source via the .NET helper failed after ${(System.nanoTime() - started) / 1_000_000} ms: $message")
            null
        }
    }

    private fun index(source: String): NuGetResponses.ServiceIndex? = indexes[source] ?: get(source, source, "service index of $source") {
        NuGetResponses.parseServiceIndex(fetch(source, source)).also {
            indexes[source] = it
            info("service index of $source: search ${it.searchUrl ?: "none (no SearchQueryService: this feed cannot be searched)"}, package base ${it.packageBaseUrl ?: "none (no PackageBaseAddress: no versions, no nuspec)"}")
        }
    }

    /** A GET and its parsing, with the request, the answer and a failure in the journal. */
    private fun <T> get(url: String, source: String, what: String, request: () -> T): T? {
        if (ideNetworkSaid.compareAndSet(false, true)) info(NuGetNetwork.ideSummary())
        info("GET $url (${runCatching { route(url, source) }.getOrElse { "route unknown" }})")
        val started = System.nanoTime()
        return runCatching(request).onSuccess {
            failures.remove(source)
            routeFailures.remove(source)
            info("$what: answered in ${(System.nanoTime() - started) / 1_000_000} ms")
        }.onFailure { e ->
            val description = NuGetNetwork.describeFailure(e)
            failures[source] = description
            if (NuGetNetwork.isRouteFailure(e)) routeFailures[source] = System.currentTimeMillis() to description else routeFailures.remove(source)
            warn("$what: GET $url failed after ${(System.nanoTime() - started) / 1_000_000} ms: $description")
        }.getOrNull()
    }

    /** Packages from all [sources]; a package found in several feeds is taken from the first one. [packageType]: `Template`, `DotnetTool`. */
    fun search(query: String, includePrerelease: Boolean, sources: List<String>, take: Int = 40, packageType: String? = null): List<NuGetPackageInfo> {
        val what = "search \"$query\"" + (if (includePrerelease) " with prerelease" else "") + packageType?.let { " of type $it" }.orEmpty()
        if (sources.isEmpty()) warn("$what: no feeds to search")
        return sources.flatMap { source ->
            fun helper(why: String) = viaHelper(source, what, why) { it.search(source, query, includePrerelease, take, packageType) }
                ?.also { info("$what in $source via the .NET helper: ${it.size} packages" + if (it.isEmpty()) "" else ", first ${it.take(3).joinToString(", ") { p -> p.id }}") }
            val first = helperFirst(source)
            val found = if (first != null) helper(first) else if (isLocal(source)) null else {
                val url = index(source)?.searchUrl
                val search = url?.let { "$it?q=${URLEncoder.encode(query, Charsets.UTF_8)}&take=$take&prerelease=$includePrerelease&semVerLevel=2.0.0" + packageType?.let { t -> "&packageType=$t" }.orEmpty() }
                search?.let { get(it, source, "$what in $source") { NuGetResponses.parseSearch(fetch(it, source)) } }
                    ?.also { info("$what in $source: ${it.size} packages" + if (it.isEmpty()) "" else ", first ${it.take(3).joinToString(", ") { p -> p.id }}") }
                    ?: helperAfter(source)?.let(::helper)
            }
            found.orEmpty().onEach { it.source = source }
        }.distinctBy { it.id.lowercase() }
    }

    /** All published versions of a package, oldest first; empty when no feed has it. */
    fun versions(packageId: String, sources: List<String>): List<String> =
        sources.firstNotNullOfOrNull { source ->
            val what = "versions of $packageId"
            fun helper(why: String) = viaHelper(source, what, why) { it.versions(source, packageId) }
                ?.also { info("$what from $source via the .NET helper: ${if (it.isEmpty()) "none" else "${it.size}, latest ${it.last()}"}") }
            val first = helperFirst(source)
            val found = if (first != null) helper(first) else if (isLocal(source)) null else {
                val base = index(source)?.packageBaseUrl
                val url = base?.let { "$it${packageId.lowercase()}/index.json" }
                url?.let { get(it, source, "$what from $source") { NuGetResponses.parseVersions(fetch(it, source)) } }
                    ?.also { info("$what from $source: ${if (it.isEmpty()) "none" else "${it.size}, latest ${it.last()}"}") }
                    ?: helperAfter(source)?.let(::helper)
            }
            found?.takeIf { it.isNotEmpty() }
        }.orEmpty()

    /** Description, license and dependencies of a concrete version; null when no feed has its `.nuspec`. */
    fun details(packageId: String, version: String, sources: List<String>): NuGetPackageDetails? {
        val id = packageId.lowercase()
        return sources.firstNotNullOfOrNull { source ->
            // the helper reads no nuspecs: the card of a package of a feed it answers for has what the search gave
            if (helperFirst(source) != null || isLocal(source)) return@firstNotNullOfOrNull null
            val base = index(source)?.packageBaseUrl ?: return@firstNotNullOfOrNull null
            val url = "$base$id/${version.lowercase()}/$id.nuspec"
            get(url, source, "nuspec of $packageId $version from $source") { NuGetResponses.parseNuspec(fetch(url, source)) }
        }
    }

    private fun info(text: String) {
        PluginLog.info(LOG_CATEGORY, text)
        onEvent(text, false)
    }

    private fun warn(text: String) {
        PluginLog.warn(LOG_CATEGORY, text)
        onEvent(text, true)
    }

    companion object {
        /** The category of the journal of the plugin for NuGet: the feeds and the `dotnet nuget` commands. */
        const val LOG_CATEGORY = "nuget"

        /** How long a feed the IDE has failed to reach goes to the helper without trying the IDE first. */
        const val HELPER_FIRST_MS = 10 * 60_000L

        /** A folder feed (`C:\packages`, `/srv/feed`, `file://...`): no HTTP API, only the helper reads it. */
        fun isLocal(source: String): Boolean = !source.startsWith("http://", ignoreCase = true) && !source.startsWith("https://", ignoreCase = true)
    }
}

/**
 * HTTP GET of a feed resource by the HTTP client of the IDE (its proxy settings and certificates apply); a private feed gets the
 * credentials stored for its source (Basic authentication). The status and the size of the answer go to the journal, a status other
 * than 2xx is an [HttpRequests.HttpStatusException].
 */
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
        .connect { request ->
            val text = request.readString()
            val connection = request.connection as? java.net.HttpURLConnection
            PluginLog.info(NuGetClient.LOG_CATEGORY, "GET $url: HTTP ${connection?.responseCode ?: "?"}, ${text.length} chars" +
                connection?.contentType?.let { ", $it" }.orEmpty() + (connection?.url?.toString()?.takeIf { it != url }?.let { ", redirected to $it" }.orEmpty()))
            text
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
    /** Commands run on behalf of the NuGet window and their output, and every request to a feed: the "Log" tab. */
    val log = NuGetLog()

    /** Tests never start the .NET helper: without it a local feed is not searched and a feed the IDE cannot reach stays silent. */
    private val helperEnabled = !ApplicationManager.getApplication().isUnitTestMode

    /** The feeds of the solution through DotNetHelper, nuget.config looked up from the solution directory as the CLI does. */
    private val helperFallback = object : NuGetClient.NuGetFallback {
        override fun search(source: String, query: String, includePrerelease: Boolean, take: Int, packageType: String?): List<NuGetPackageInfo> =
            single(NuGetHelper.getInstance().search(workDirectory(), query, includePrerelease, take, listOf(source), packageType))

        override fun versions(source: String, packageId: String): List<String> =
            single(NuGetHelper.getInstance().versions(workDirectory(), packageId, prerelease = true, sources = listOf(source)))

        private fun <T> single(answers: List<NuGetHelperResponses.SourceAnswer<T>>): T {
            val answer = answers.firstOrNull() ?: throw HelperException("no answer for the source")
            return answer.result ?: throw HelperException(answer.error ?: "no answer")
        }
    }

    val client = NuGetClient(onEvent = { text, isError -> log.print("$text\n", isError) }, fallback = if (helperEnabled) helperFallback else null)

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
        val references = solutions.msBuildProject(projectFile).packages
            .map { InstalledPackage(it.name, it.version ?: solutions.centralPackageVersion(projectFile, it.name), resolved?.findPackage(it.name)?.version) }
        // packages.config pins exact versions: what is written is what is installed
        val config = solutions.packagesConfig(projectFile).orEmpty().map { InstalledPackage(it.id, it.version, null) }
        return (references + config).distinctBy { it.id.lowercase() }.sortedBy { it.id.lowercase() }
    }

    /** A legacy project with `packages.config`: the window shows its packages, but the CLI cannot install, update or remove them. */
    fun usesPackagesConfig(projectFile: VirtualFile): Boolean = SolutionService.getInstance(project).packagesConfig(projectFile) != null

    /** The projects the CLI can change the packages of; the skipped ones are said in the Log tab. */
    private fun changeable(projectFiles: List<VirtualFile>): List<VirtualFile> {
        val (legacy, rest) = projectFiles.partition(::usesPackagesConfig)
        if (legacy.isNotEmpty()) log.print("Skipped ${legacy.joinToString(", ") { it.name }}: $PACKAGES_CONFIG_NOTE.\n", isError = true)
        return rest
    }

    /**
     * Enabled feeds of all `nuget.config` levels, as the CLI sees them from the solution directory: HTTP ones, and folder ones when the
     * .NET helper is there to read them ([NuGetClient]). Blocking.
     */
    fun sources(): List<String> {
        val directory = SolutionService.getInstance(project).solutionFiles().firstOrNull()?.parent?.path
        val configured = runCatching {
            val output = DotNetCli.execute(DotNetCli.commandLine(directory, "nuget", "list", "source", "--format", "short"), 30_000).stdout
            // E = enabled, D = disabled, as the CLI prints them: what the window searches is the enabled HTTP ones
            PluginLog.info(NuGetClient.LOG_CATEGORY, "sources of `dotnet nuget list source` in ${directory ?: "the home directory"}: " +
                output.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ").ifEmpty { "none" })
            NuGetResponses.parseSources(output)
        }.onFailure { PluginLog.warn(NuGetClient.LOG_CATEGORY, "`dotnet nuget list source` could not run, nuget.org is assumed", it) }.getOrDefault(emptyList())
        // local folder feeds have no search service: only the helper reads them
        val http = configured.filterNot(NuGetClient::isLocal)
        val local = configured - http.toSet()
        if (local.isNotEmpty()) PluginLog.info(NuGetClient.LOG_CATEGORY, "local feeds " + (if (helperEnabled) "are read by the .NET helper" else "are not searched") + ": ${local.joinToString(", ")}")
        if (http.isEmpty()) PluginLog.info(NuGetClient.LOG_CATEGORY, "no enabled HTTP feed is configured: $NUGET_ORG is assumed")
        return http.ifEmpty { listOf(NUGET_ORG) } + if (helperEnabled) local else emptyList()
    }

    /** Installs the package into every project of [projectFiles], or changes its version where it is already referenced. */
    fun install(projectFiles: List<VirtualFile>, packageId: String, version: String, onSuccess: () -> Unit) =
        run("Installing $packageId $version", changeable(projectFiles), onSuccess) { listOf("add", it.path, "package", packageId, "--version", version) }

    /**
     * `dotnet restore` of solutions or projects, with the options of Settings | Tools | .NET | NuGet; the package lists are refreshed
     * afterwards. The packages.config projects among them, or in the solutions, are restored by the .NET helper ([restorePackagesConfig]):
     * `dotnet restore` does not know that format. A packages.config project given by itself is not passed to `dotnet restore`.
     */
    fun restore(targets: List<VirtualFile>, onSuccess: () -> Unit = {}) {
        val legacy = packagesConfigProjects(targets)
        val rest = targets.filterNot { it in legacy }
        restorePackagesConfig(legacy, if (rest.isEmpty()) onSuccess else ({}))
        run("Restoring NuGet packages", rest, { packagesChangedListeners.toList().forEach { it() }; onSuccess() }) {
            listOf("restore", it.path, "-nologo") + NuGetSettings.getInstance().restoreArguments()
        }
    }

    /** The packages.config projects of [targets]: the targets that are such projects, and such projects of the target solutions. */
    fun packagesConfigProjects(targets: List<VirtualFile>): List<VirtualFile> {
        if (!helperEnabled) return emptyList()
        val solutions = SolutionService.getInstance(project)
        return targets.flatMap { target ->
            if (target.extension?.lowercase() in SOLUTION_EXTENSIONS) solutions.solution(target).allProjects.mapNotNull { it.resolveFile(target) } else listOf(target)
        }.distinct().filter(::usesPackagesConfig)
    }

    /** The directory a packages.config project restores relative to: its solution's (the packages folder is shared), else its own. */
    fun solutionDirectoryOf(projectFile: VirtualFile): VirtualFile {
        val solutions = SolutionService.getInstance(project)
        val solution = solutions.solutionFiles().firstOrNull { s -> solutions.solution(s).allProjects.any { it.resolveFile(s) == projectFile } }
        return solution?.parent ?: projectFile.parent
    }

    /** The packages folder by solution directory, with the time it was found: the Dependencies tree asks for every package node it draws. */
    private val packagesFolders = ConcurrentHashMap<String, Pair<Long, String>>()

    /** The packages folder of the solution of a packages.config project (system-independent path; `repositoryPath` of nuget.config respected). */
    fun packagesFolder(projectFile: VirtualFile): String {
        val solutionDirectory = solutionDirectoryOf(projectFile).path
        val now = System.currentTimeMillis()
        packagesFolders[solutionDirectory]?.takeIf { now - it.first < PACKAGES_FOLDER_TTL_MS }?.let { return it.second }
        return PackagesFolder.of(File(solutionDirectory)).invariantSeparatorsPath.also { packagesFolders[solutionDirectory] = now to it }
    }

    /** The packages of the packages.config of [projectFile] that have no folder in the packages folder of its solution; empty for other projects. */
    fun missingPackagesConfig(projectFile: VirtualFile): List<PackagesConfigEntry> {
        val entries = SolutionService.getInstance(project).packagesConfig(projectFile) ?: return emptyList()
        val folder = projectFile.fileSystem.findFileByPath(packagesFolder(projectFile))
        return PackagesFolder.missing(entries, folder?.children.orEmpty().map { it.name.lowercase() }.toSet())
    }

    /**
     * The packages of packages.config projects into the packages folder of their solution, by the .NET helper (NuGet.Client, as nuget.exe
     * does it): what is there already is skipped, a project with nothing missing does not start the helper. The output goes to the Log tab;
     * the Dependencies tree and the window are refreshed afterwards. [onSuccess] when every package is there.
     */
    fun restorePackagesConfig(projectFiles: List<VirtualFile>, onSuccess: () -> Unit = {}) {
        if (projectFiles.isEmpty()) return
        val title = "Restoring packages.config packages"
        notifyOperation(NuGetOperation(title, NuGetOperation.State.RUNNING))
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                var failed = 0
                val refresh = ArrayList<File>()
                try {
                    for (file in projectFiles) {
                        indicator.checkCanceled()
                        indicator.text = "Restoring the packages of ${file.name}"
                        val solutionDirectory = ReadAction.compute<VirtualFile, RuntimeException> { solutionDirectoryOf(file) }
                        val missing = ReadAction.compute<List<PackagesConfigEntry>, RuntimeException> { missingPackagesConfig(file) }
                        log.print("> restore packages.config of ${file.name} into the packages folder of ${solutionDirectory.path} (by the .NET helper)\n")
                        if (missing.isEmpty()) {
                            log.print("Every package is there.\n\n")
                            continue
                        }
                        PluginLog.info(NuGetClient.LOG_CATEGORY, "restoring packages.config of ${file.path}: missing ${missing.joinToString(", ") { "${it.id} ${it.version}" }}")
                        val started = System.nanoTime()
                        val result = try {
                            NuGetHelper.getInstance().restorePackagesConfig(file.path, solutionDirectory.path)
                        } catch (e: HelperException) {
                            failed++
                            log.print("Failed: ${e.message}\n\n", isError = true)
                            PluginLog.warn(NuGetClient.LOG_CATEGORY, "restore of packages.config of ${file.path} by the .NET helper failed: ${e.message}")
                            continue
                        }
                        refresh += File(result.packagesDirectory)
                        for (line in result.lines()) log.print("$line\n", isError = line.contains(": failed: "))
                        failed += result.failed.size
                        val summary = "${result.packages.count { it.state == NuGetHelperResponses.RestoredPackage.State.RESTORED }} restored, " +
                            "${result.packages.count { it.state == NuGetHelperResponses.RestoredPackage.State.PRESENT }} already there, ${result.failed.size} failed " +
                            "into ${result.packagesDirectory} in ${(System.nanoTime() - started) / 1_000_000} ms"
                        log.print((if (result.failed.isEmpty()) "Done: " else "Failed: ") + summary + ".\n\n", isError = result.failed.isNotEmpty())
                        PluginLog.info(NuGetClient.LOG_CATEGORY, "packages.config of ${file.path}: $summary" +
                            result.failed.joinToString("") { "\n  ${it.id} ${it.version}: ${it.message}" })
                    }
                } finally {
                    packagesFolders.clear()
                    refresh.distinct().forEach { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it)?.let { dir -> VfsUtil.markDirtyAndRefresh(true, false, false, dir) } }
                    notifyOperation(NuGetOperation(title, if (failed == 0) NuGetOperation.State.DONE else NuGetOperation.State.FAILED))
                    ApplicationManager.getApplication().invokeLater({
                        if (project.isDisposed) return@invokeLater
                        packagesChangedListeners.toList().forEach { it() }
                        ProjectView.getInstance(project).refresh()
                    }, ModalityState.any())
                }
                if (failed > 0) {
                    DotNetCli.notifyError(project, title, "$failed package(s) of packages.config could not be restored: see the Log tab of the NuGet window")
                } else {
                    ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) onSuccess() }, ModalityState.any())
                }
            }
        }.queue()
    }

    fun remove(projectFiles: List<VirtualFile>, packageId: String, onSuccess: () -> Unit) =
        run("Removing $packageId", changeable(projectFiles), onSuccess) { listOf("remove", it.path, "package", packageId) }

    /** Packages of the solution that have a newer stable version in the feeds. Blocking: asks the feeds for every package. */
    fun outdated(): List<PackageUpgrade> {
        val feeds = sources()
        val latest = HashMap<String, NuGetVersion?>()
        // a packages.config project cannot be upgraded by the CLI
        return projects().filterNot { usesPackagesConfig(it.second) }.flatMap { (name, file) ->
            installed(file).mapNotNull { pkg ->
                // a floating or a missing version is not a version to upgrade from
                val current = pkg.version?.let(NuGetVersion::parse) ?: return@mapNotNull null
                val newest = latest.getOrPut(pkg.id.lowercase()) { NuGetVersion.latest(client.versions(pkg.id, feeds), NuGetSettings.getInstance().includePrerelease)?.let(NuGetVersion::parse) }
                if (newest != null && current < newest) PackageUpgrade(name, file, pkg.id, current.text, newest.text) else null
            }
        }
    }

    /**
     * Vulnerable and deprecated packages of the solution, by lowercased id. Blocking: runs `dotnet list package` twice ( `--vulnerable`
     * and `--deprecated` are mutually exclusive). Needs the solution restored; otherwise the CLI prints an error and the map is empty.
     */
    fun packageWarnings(): Map<String, List<PackageWarning>> {
        val target = SolutionService.getInstance(project).solutionFiles().firstOrNull() ?: return emptyMap()
        fun report(flag: String): Map<String, List<PackageWarning>> = runCatching {
            val result = DotNetCli.execute(DotNetCli.commandLine(target.parent.path, "list", target.path, "package", flag, "--include-transitive", "--format", "json"), 180_000)
            if (result.exitCode == 0) NuGetResponses.parseListReport(result.stdout)
            else emptyMap<String, List<PackageWarning>>().also { PluginLog.warn(NuGetClient.LOG_CATEGORY, "`dotnet list package $flag` exit code ${result.exitCode}: ${DotNetCli.lastLines(result, 1)}") }
        }.onFailure { PluginLog.warn(NuGetClient.LOG_CATEGORY, "`dotnet list package $flag` could not run", it) }.getOrDefault(emptyMap())
        val vulnerable = report("--vulnerable")
        val deprecated = report("--deprecated")
        return (vulnerable.keys + deprecated.keys).associateWith { vulnerable[it].orEmpty() + deprecated[it].orEmpty() }
    }

    /**
     * `dotnet nuget why <target> <packageId>`: the dependency paths that pulled the package in, as the CLI prints them (a tree per target
     * framework). Blocking. Needs .NET SDK 8.0.400+; an older SDK prints an error, which is returned as the text to show.
     */
    fun whyInstalled(target: VirtualFile, packageId: String): String = runCatching {
        val result = DotNetCli.execute(DotNetCli.commandLine(target.parent.path, "nuget", "why", target.path, packageId), 120_000)
        result.stdout.trim().ifBlank { result.stderr.trim() }.ifBlank { "No output from 'dotnet nuget why'." }
    }.getOrElse { "'dotnet nuget why' could not run (needs .NET SDK 8.0.400 or newer): ${it.message}" }

    /**
     * Packages referenced at more than one version across the solution's projects: the upgrades that bring every lower reference up to the
     * highest version already used, so the solution settles on one version per package (as "Consolidate" in Visual Studio / Rider). Blocking.
     */
    fun consolidations(): List<PackageUpgrade> {
        data class Ref(val name: String, val file: VirtualFile, val id: String, val version: NuGetVersion)
        val refs = projects().filterNot { usesPackagesConfig(it.second) }.flatMap { (name, file) ->
            installed(file).mapNotNull { pkg -> pkg.version?.let(NuGetVersion::parse)?.let { Ref(name, file, pkg.id, it) } }
        }
        return refs.groupBy { it.id.lowercase() }
            .filterValues { group -> group.map { it.version.text }.distinct().size > 1 }
            .flatMap { (_, group) ->
                val target = group.maxOf { it.version }
                group.filter { it.version < target }.map { PackageUpgrade(it.name, it.file, it.id, it.version.text, target.text) }
            }
    }

    fun upgrade(all: List<PackageUpgrade>, onSuccess: () -> Unit) {
        val projectFiles = changeable(all.map { it.projectFile }.distinct()).toSet()
        val upgrades = all.filter { it.projectFile in projectFiles }
        if (upgrades.isEmpty()) return
        val title = "Upgrading NuGet packages"
        val commands = DotNetCli.commandLinesOrNotify(project, title) {
            upgrades.map { DotNetCli.commandLine(it.projectFile.parent.path, "add", it.projectFile.path, "package", it.packageId, "--version", it.to) }
        } ?: return
        DotNetCli.runInBackground(project, title, commands, refresh = upgrades.map { File(it.projectFile.parent.path) }.distinct(), output = log, onSuccess = onSuccess)
    }

    /** The folders NuGet keeps packages and caches in: `global-packages`, `http-cache`, `temp`, `plugins-cache`. Blocking. */
    fun localFolders(): List<Pair<String, String>> =
        output("nuget", "locals", "all", "--list", "--force-english-output").lines().mapNotNull { line ->
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

    /** Told what is running and how it ended, so that the window can show it: without this only the progress of the IDE says anything. */
    val operationListeners = ArrayList<(NuGetOperation) -> Unit>()

    private fun notifyOperation(operation: NuGetOperation) =
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) operationListeners.toList().forEach { it(operation) } }, ModalityState.any())

    private fun run(title: String, projectFiles: List<VirtualFile>, onSuccess: () -> Unit, arguments: (VirtualFile) -> List<String>) {
        if (projectFiles.isEmpty()) return
        val commands = DotNetCli.commandLinesOrNotify(project, title) {
            projectFiles.map { DotNetCli.commandLine(it.parent.path, *arguments(it).toTypedArray()) }
        } ?: return
        notifyOperation(NuGetOperation(title, NuGetOperation.State.RUNNING))
        try {
            runCommands(title, commands, projectFiles, onSuccess)
        } catch (e: RuntimeException) {
            // the window must not keep its buttons disabled for a command that never started
            notifyOperation(NuGetOperation(title, NuGetOperation.State.FAILED))
            PluginLog.error("nuget", "$title: cannot start", e)
            throw e
        }
    }

    private fun runCommands(title: String, commands: List<GeneralCommandLine>, projectFiles: List<VirtualFile>, onSuccess: () -> Unit) {
        // `finished` comes in every case (a failure, a cancellation, a command that could not start), so the window never keeps a stale "running"
        // the progress is the one of the IDE at the bottom; the output goes to the Build tool window (it comes up by itself only at a failure,
        // decision of the user) and to the Log tab of the NuGet window
        val build = BuildViewCommandOutput(project, title)
        val output = object : CommandOutput {
            override fun commandStarted(command: GeneralCommandLine) { build.commandStarted(command); log.commandStarted(command) }
            override fun text(text: String, isError: Boolean) { build.text(text, isError); log.text(text, isError) }
            override fun commandFinished(exitCode: Int) { build.commandFinished(exitCode); log.commandFinished(exitCode) }
            override fun finished(succeeded: Boolean) {
                build.finished(succeeded)
                log.finished(succeeded)
                notifyOperation(NuGetOperation(title, if (succeeded) NuGetOperation.State.DONE else NuGetOperation.State.FAILED))
            }
        }
        DotNetCli.runInBackground(project, title, commands, refresh = projectFiles.map { File(it.parent.path) }, output = output, onSuccess = onSuccess)
    }

    private fun workDirectory(): String? = SolutionService.getInstance(project).solutionFiles().firstOrNull()?.parent?.path

    /** What a short `dotnet` command prints in the solution directory; empty when it fails, with the reason in the journal. */
    private fun output(vararg arguments: String): String = try {
        val result = DotNetCli.execute(DotNetCli.commandLine(workDirectory(), *arguments), 30_000)
        if (result.exitCode != 0) PluginLog.warn(NuGetClient.LOG_CATEGORY, "`dotnet ${arguments.joinToString(" ")}` exit code ${result.exitCode}: ${DotNetCli.lastLines(result, 1)}")
        result.stdout
    } catch (e: Exception) {
        PluginLog.warn(NuGetClient.LOG_CATEGORY, "`dotnet ${arguments.joinToString(" ")}` could not run", e)
        ""
    }

    /** Sources of all `nuget.config` levels with their names and state. Blocking. */
    fun sourceList(): List<NuGetSource> =
        NuGetResponses.parseSourceList(output("nuget", "list", "source"), output("nuget", "list", "source", "--format", "short"))

    /** The `nuget.config` files that apply to the solution, the most specific first. Blocking. */
    fun configPaths(): List<String> = output("nuget", "config", "paths").lines().map { it.trim() }.filter { it.isNotEmpty() && File(it).isFile }

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

        /** Why a packages.config project has install, update and remove disabled: `dotnet add package` would write a PackageReference into it. */
        const val PACKAGES_CONFIG_NOTE = "This project uses packages.config; install packages with Visual Studio or migrate to PackageReference"

        /** The packages folder is looked for again after this long: a nuget.config edited meanwhile is seen. */
        private const val PACKAGES_FOLDER_TTL_MS = 5_000L

        fun getInstance(project: Project): NuGetService = project.service()
    }
}
