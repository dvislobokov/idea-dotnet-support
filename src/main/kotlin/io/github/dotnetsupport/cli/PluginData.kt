package io.github.dotnetsupport.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import io.github.dotnetsupport.settings.DotNetSettings
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the plugin keeps everything it writes: the helpers it builds from source and runs with `dotnet`, the indexes of assemblies,
 * generated and decompiled files, caches, the ML model it maps, the pages of the guide, its temporary files and — when moved — its logs.
 * One place because on corporate Linux machines (fapolicyd, a `noexec` mount, an SELinux policy) programs and loadable code may run from
 * one directory only, typically `/home/work/<user>`, and fapolicyd scans the directories it watches all the time; see [PluginDataCheck].
 *
 * The root, in order: `-Ddotnet.support.root` (the dataset export runs many IDEs over one folder of indexes), the directory of
 * Settings | .NET → Plugin data (chosen by the user or by [PluginDataCheck]), `<IDE system dir>/dotnet-support`. The logs stay in
 * `~/idea-dotnet-logs` unless the setting moved the data: then they are `<root>/logs`, as is everything else.
 */
object PluginData {
    const val ROOT_PROPERTY = "dotnet.support.root"

    /** Under the work home: `/home/work/<user>/.cache/dotnet-support`. */
    const val FALLBACK = ".cache/dotnet-support"

    /** The names under the root that are not data to keep: what [tmp] holds, and the logs when they live there. */
    const val TMP = "tmp"
    const val LOGS = "logs"

    @Volatile private var testRoot: Path? = null

    /** Makes [root] the chosen (relocated) data directory for a test; null restores the real one. */
    @TestOnly
    fun setRootForTests(root: Path?) { testRoot = root }

    fun defaultRoot(): Path = Path.of(PathManager.getSystemPath(), "dotnet-support")

    fun root(): Path = testRoot ?: resolve(System.getProperty(ROOT_PROPERTY), setting(), defaultRoot())

    /** Whether the data was moved by the setting (by the user or by [PluginDataCheck]): then the logs and the temporary files move too. */
    val isRelocated: Boolean get() = testRoot != null || (System.getProperty(ROOT_PROPERTY).isNullOrBlank() && setting().isNotBlank())

    fun logs(): Path = if (isRelocated) root().resolve(LOGS) else defaultLogs()

    fun defaultLogs(): Path = if (ApplicationManager.getApplication()?.isUnitTestMode == true) Path.of(PathManager.getTempPath(), "idea-dotnet-logs")
    else Path.of(System.getProperty("user.home"), "idea-dotnet-logs")

    /** The temporary directory of the plugin and of the processes it starts when relocated; the system one is not used then. */
    fun tmp(): Path = root().resolve(TMP)

    /** The data root by its sources, the first given wins. Pure, for tests. */
    fun resolve(property: String?, setting: String, default: Path): Path =
        property?.takeIf { it.isNotBlank() }?.let { Path.of(it.trim()) } ?: setting.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) } ?: default

    private fun setting(): String =
        if (ApplicationManager.getApplication() == null) "" else runCatching { DotNetSettings.getInstance().pluginDataDirectory }.getOrDefault("")

    /**
     * What the processes the plugin starts for itself get on top of the environment of the IDE: `TMPDIR` under the data root when it
     * was moved (fapolicyd may deny `/tmp` as well; .NET on Unix takes its temporary directory from `TMPDIR`). Nothing on Windows.
     */
    fun processEnvironment(): Map<String, String> = environment(SystemInfo.isWindows, isRelocated) { tmp().also { runCatching { Files.createDirectories(it) } } }

    fun environment(windows: Boolean, relocated: Boolean, tmp: () -> Path): Map<String, String> =
        if (windows || !relocated) emptyMap() else mapOf("TMPDIR" to tmp().toString())

    /** A temporary file of the plugin: in [tmp] when relocated, else in the system temporary directory as before. Deleted on exit. */
    fun tempFile(prefix: String, suffix: String): File =
        if (isRelocated) FileUtil.createTempFile(Files.createDirectories(tmp()).toFile(), prefix, suffix, true, true) else FileUtil.createTempFile(prefix, suffix, true)

    fun tempDirectory(prefix: String): File =
        if (isRelocated) FileUtil.createTempDirectory(Files.createDirectories(tmp()).toFile(), prefix, null, true) else FileUtil.createTempDirectory(prefix, null, true)

    /** Where the data and the logs are: what [move] carries from one place to another. */
    data class Layout(val data: Path, val logs: Path)

    fun layout(): Layout = Layout(root(), logs())

    /**
     * Moves what [from] holds into [to]: every entry of the data directory but [TMP] (and the logs when they live inside it), then every
     * entry of the logs directory. A rename when both are on one file system, else a copy and a delete; the copy does not keep the
     * executable bits, which costs nothing — the helpers run as `dotnet <helper>.dll`, the native library of ML is extracted again by
     * its checksum. Returns the entries that could not be moved: they are rebuilt (the helpers by the hash of their source, the
     * indexes by the indexer) or are old logs.
     */
    fun move(from: Layout, to: Layout): List<String> {
        val failed = ArrayList<String>()
        fun entries(directory: Path, skip: Set<Path>): List<Path> =
            runCatching { Files.list(directory).use { stream -> stream.toList().filter { it.normalize() !in skip } } }.getOrDefault(emptyList())
        val fromData = from.data.normalize()
        val toData = to.data.normalize()
        if (fromData != toData) {
            // a target inside the source would copy into itself
            if (toData.startsWith(fromData)) failed += fromData.toString()
            else for (entry in entries(fromData, setOf(fromData.resolve(TMP), from.logs.normalize(), toData))) if (!moveEntry(entry, toData.resolve(entry.fileName))) failed += entry.fileName.toString()
        }
        val fromLogs = from.logs.normalize()
        val toLogs = to.logs.normalize()
        if (fromLogs != toLogs && !toLogs.startsWith(fromLogs)) {
            for (entry in entries(fromLogs, setOf(toData))) if (!moveEntry(entry, toLogs.resolve(entry.fileName))) failed += "logs/" + entry.fileName
        }
        return failed
    }

    private fun moveEntry(source: Path, target: Path): Boolean {
        if (!Files.exists(target)) {
            val renamed = runCatching { Files.createDirectories(target.parent); Files.move(source, target, StandardCopyOption.ATOMIC_MOVE) }.isSuccess
            if (renamed) return true
        }
        val copied = runCatching { source.toFile().copyRecursively(target.toFile(), overwrite = true) { _, _ -> OnErrorAction.SKIP } }.getOrDefault(false)
        if (copied) source.toFile().deleteRecursively()
        return copied
    }
}

/**
 * Whether the plugin can run what it builds in a directory. Two steps, both without building anything:
 *  1. a copy of `/bin/true` is made there, marked executable and run: a `noexec` mount, fapolicyd's `perm=execute` rules and SELinux
 *     deny it (as the Go plugin checks the same, seen live there);
 *  2. if a helper of an earlier session is already there (the indexer, which answers `--version` and exits), the `dotnet` host runs it
 *     from there: this is what the plugin really does, and it is the only way to see a policy that lets the host start but denies it
 *     to open and map a managed assembly from that directory (fapolicyd's `perm=open` rules by file type). A failure counts only when
 *     the host says the access was denied — a missing runtime or a broken build is not a property of the directory.
 * Building a probe assembly instead would take seconds and the network-free SDK at every start, and a prebuilt one cannot be shipped
 * (the plugin carries no binaries). So on a fresh machine only step 1 tells, and a policy of step 2 alone shows as a helper that does
 * not start (logged by [HelperConnection] / [DotNetHelper]) until the next start probes the helper built meanwhile. Windows is not checked.
 */
object DotNetExecutionProbe {
    enum class Result { OK, NOT_EXECUTABLE, UNKNOWN }

    data class Outcome(val result: Result, val reason: String = "")

    private val results = ConcurrentHashMap<Path, Outcome>()

    /** An earlier [check] of [dir] in this session, or null. */
    fun cached(dir: Path): Outcome? = results[dir.normalize()]

    /** Blocking (a fraction of a second, a second more when a helper is run): not on the EDT. */
    fun check(dir: Path): Outcome {
        val outcome = if (SystemInfo.isWindows) Outcome(Result.OK) else probe(dir)
        results[dir.normalize()] = outcome
        return outcome
    }

    private fun probe(dir: Path): Outcome {
        val sample = listOf("/bin/true", "/usr/bin/true").map(::File).firstOrNull { it.isFile && it.canExecute() }
        val native = if (sample == null) Outcome(Result.UNKNOWN, "no /bin/true to copy") else native(dir, sample)
        if (native.result == Result.NOT_EXECUTABLE) return native
        return managed(dir) ?: native
    }

    private fun native(dir: Path, sample: File): Outcome {
        val copy = try {
            Files.createDirectories(dir)
            Files.copy(sample.toPath(), dir.resolve(".exec-probe-${ProcessHandle.current().pid()}"), StandardCopyOption.REPLACE_EXISTING).toFile()
        } catch (e: Exception) {
            return Outcome(Result.NOT_EXECUTABLE, "cannot write there: ${PluginLog.describe(e)}")
        }
        return try {
            if (!copy.setExecutable(true)) return Outcome(Result.NOT_EXECUTABLE, "cannot mark a file executable there")
            val output = CapturingProcessHandler(GeneralCommandLine(copy.path).withWorkDirectory(dir.toFile())).runProcess(5_000)
            if (output.exitCode == 0 && !output.isTimeout) Outcome(Result.OK)
            else Outcome(Result.NOT_EXECUTABLE, "a program there exited with ${output.exitCode} ${output.stderr.trim()}".trim())
        } catch (e: Exception) {
            Outcome(Result.NOT_EXECUTABLE, "a program there cannot start: ${PluginLog.describe(e)}")
        } finally {
            copy.delete()
        }
    }

    /** Step 2: null when there is no built indexer or no `dotnet`, or when the run tells nothing about the directory. */
    private fun managed(dir: Path): Outcome? {
        val dll = builtIndexer(dir) ?: return null
        val dotnet = DotNetCli.findExecutable() ?: return null
        val output = try {
            CapturingProcessHandler(GeneralCommandLine(dotnet, "exec", dll.path, "--version").withWorkDirectory(dir.toFile()).withEnvironment("DOTNET_NOLOGO", "1")).runProcess(20_000)
        } catch (e: Exception) {
            return null
        }
        val text = output.stdout + "\n" + output.stderr
        return if (deniedByPolicy(output.exitCode, text)) Outcome(Result.NOT_EXECUTABLE, "the dotnet host cannot load ${dll.name} from there: ${text.trim().takeLast(300)}")
        else if (output.exitCode == 0) Outcome(Result.OK) else null
    }

    private fun builtIndexer(dir: Path): File? = File(dir.toFile(), "indexer").listFiles().orEmpty().asSequence()
        .map { File(it, "bin/${io.github.dotnetsupport.index.IndexerTool.ASSEMBLY}.dll") }
        .firstOrNull { it.isFile && File(it.parentFile, "${io.github.dotnetsupport.index.IndexerTool.ASSEMBLY}.runtimeconfig.json").isFile }

    /** A failed run of the host that was refused access (EACCES / EPERM: what fapolicyd and SELinux answer), as opposed to any other failure. Pure. */
    fun deniedByPolicy(exitCode: Int, output: String): Boolean =
        exitCode != 0 && DENIED.any { output.contains(it, ignoreCase = true) }

    private val DENIED = listOf("Permission denied", "Operation not permitted", "EACCES", "EPERM", "Access to the path")
}

sealed interface PluginDataDecision {
    /** The data stays where it is. */
    data object Keep : PluginDataDecision

    /** Programs cannot run in the default directory, [target] in the work home can: move there and remember it. */
    data class Relocate(val from: Path, val target: Path, val reason: String) : PluginDataDecision

    /**
     * Programs cannot run in [root] and no work home took the data: tell the user, who chooses one. [rejected]: each work home tried and
     * why it was not taken (the journal says it); [suggestion]: the directory under an existing work home to offer as the choice.
     */
    data class Notify(val root: Path, val reason: String, val rejected: List<String> = emptyList(), val suggestion: Path? = null) : PluginDataDecision
}

object PluginDataRule {
    fun decide(
        windows: Boolean, property: String?, setting: String, defaultRoot: Path, probe: (Path) -> DotNetExecutionProbe.Outcome,
        workHomes: List<Path>, usable: (Path) -> String?, prepare: (Path) -> Unit, owned: (Path) -> Boolean,
    ): PluginDataDecision {
        if (windows || !property.isNullOrBlank()) return PluginDataDecision.Keep
        val chosen = setting.trim()
        if (chosen.isNotEmpty()) {
            val outcome = probe(Path.of(chosen))
            return if (outcome.result == DotNetExecutionProbe.Result.NOT_EXECUTABLE) PluginDataDecision.Notify(Path.of(chosen), outcome.reason) else PluginDataDecision.Keep
        }
        val outcome = probe(defaultRoot)
        if (outcome.result != DotNetExecutionProbe.Result.NOT_EXECUTABLE) return PluginDataDecision.Keep
        // the work home itself only has to exist and take our files: on corporate machines it is often the group's, or its owner is named
        // otherwise than the process sees itself (a colleague's machine, 0.1.220: no move, "choose another directory"); what must be ours
        // alone is our own directory under it, made by us with rwx------ before it is probed and checked
        val rejected = ArrayList<String>()
        var suggestion: Path? = null
        for (home in workHomes) {
            usable(home)?.let { rejected += "$home: $it"; continue }
            val fallback = home.resolve(PluginData.FALLBACK)
            if (suggestion == null) suggestion = fallback
            runCatching { prepare(fallback) }.onFailure { rejected += "$fallback: cannot be made (${it.message})"; continue }
            val there = probe(fallback)
            if (there.result != DotNetExecutionProbe.Result.OK) { rejected += "$fallback: ${there.reason.ifEmpty { there.result.name }}"; continue }
            if (!owned(fallback)) { rejected += "$fallback: not the user's own, or writable by others"; continue }
            return PluginDataDecision.Relocate(defaultRoot, fallback, outcome.reason)
        }
        return PluginDataDecision.Notify(defaultRoot, outcome.reason, rejected, suggestion)
    }

    fun workHomes(userName: String?, userHome: String?, listing: List<String> = emptyList()): List<Path> {
        val names = LinkedHashSet<String>()
        userName?.takeIf { it.isNotBlank() }?.let { names += it; names += swapped(it) }
        userHome?.let { runCatching { Path.of(it).fileName?.toString() }.getOrNull() }?.takeIf { it.isNotBlank() }?.let { names += it; names += swapped(it) }
        // what `/home/work` really holds: an entry named by the account in another form (`ivan@CORP.LOCAL`, `Ivan`) — matched by the
        // whole name, or by the user part of it, never by the domain alone
        val users = names.flatMap { userParts(it) }.mapTo(HashSet()) { it.lowercase() }
        for (entry in listing.sorted()) {
            if (entry.lowercase() in names.mapTo(HashSet()) { it.lowercase() } || userParts(entry).any { it.lowercase() in users }) names += entry
        }
        return names.filter { '/' !in it }.map { Path.of("/home/work", it) }
    }

    /** The user part of an account name: `ivan` of `ivan`, `CORP\ivan`, `ivan@corp`; both parts of `corp@ivan` (either order is seen). */
    private fun userParts(name: String): List<String> = when {
        '\\' in name -> listOf(name.substringAfterLast('\\'))
        name.count { it == '@' } == 1 -> listOf(name.substringBefore('@'), name.substringAfter('@'))
        else -> listOf(name)
    }.filter { it.length >= 2 }

    /** `domain@user` ↔ `user@domain`; `DOMAIN\user` → `user@domain`; a name without either stays. */
    private fun swapped(name: String): String = when {
        name.count { it == '@' } == 1 -> name.substringAfter('@') + "@" + name.substringBefore('@')
        name.count { it == '\\' } == 1 -> name.substringAfter('\\') + "@" + name.substringBefore('\\')
        else -> name
    }

    /** A directory owned by the user of this process and not writable by others; false when that cannot be told. */
    fun ownedByMe(directory: Path): Boolean = runCatching {
        if (!Files.isDirectory(directory)) return false
        val me = ProcessHandle.current().info().user().orElse(null) ?: System.getProperty("user.name") ?: return false
        if (Files.getOwner(directory).name != me) return false
        val permissions = Files.getPosixFilePermissions(directory)
        PosixFilePermission.OTHERS_WRITE !in permissions && PosixFilePermission.GROUP_WRITE !in permissions
    }.getOrDefault(false)

    /** Why [home] cannot take the data: it does not exist, is no directory or not writable by this process; null when it can. */
    fun usable(home: Path): String? = when {
        !Files.exists(home) -> "does not exist"
        !Files.isDirectory(home) -> "is no directory"
        !Files.isWritable(home) -> "is not writable"
        else -> null
    }

    /** Makes our own directory under a work home, readable and writable by the user only (whatever the umask). */
    fun prepare(directory: Path) {
        Files.createDirectories(directory)
        runCatching { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")) }
    }
}

/**
 * Once a session: can the plugin run what it builds in its data directory ([PluginData])? When not and a work home exists, the data
 * moves there by itself and the setting remembers it; otherwise a notification explains why and leads to the setting. The decision is
 * taken by whoever needs it first — the start of a .NET project, or the first helper to be built ([DotNetHelper.ensureBuilt]), so that
 * a helper of the first session is built where it can run.
 */
object PluginDataCheck {
    @Volatile private var decision: PluginDataDecision? = null
    private val notified = AtomicBoolean()

    /** Blocking (the probe runs processes; a move may copy files): not on the EDT, where it returns what is known so far. */
    fun ensureDecided(): PluginDataDecision {
        decision?.let { return it }
        val application = ApplicationManager.getApplication()
        if (application == null || application.isUnitTestMode || application.isDispatchThread || SystemInfo.isWindows) return PluginDataDecision.Keep
        return synchronized(this) { decision ?: decide().also { decision = it } }
    }

    private fun decide(): PluginDataDecision {
        val listing = runCatching { File("/home/work").list()?.toList() }.getOrNull().orEmpty()
        val homes = PluginDataRule.workHomes(System.getProperty("user.name"), System.getProperty("user.home"), listing)
        val result = PluginDataRule.decide(SystemInfo.isWindows, System.getProperty(PluginData.ROOT_PROPERTY), DotNetSettings.getInstance().pluginDataDirectory,
            PluginData.defaultRoot(), DotNetExecutionProbe::check, homes, PluginDataRule::usable, PluginDataRule::prepare, PluginDataRule::ownedByMe)
        when (result) {
            is PluginDataDecision.Keep -> PluginLog.info(LOG_CATEGORY, "plugin data: ${PluginData.root()}, logs: ${PluginData.logs()}")
            is PluginDataDecision.Notify -> PluginLog.warn(LOG_CATEGORY, "Programs cannot run in the plugin data directory ${result.root}: ${result.reason}; " +
                "work homes tried: ${homes.joinToString().ifEmpty { "none" }}" + result.rejected.joinToString("") { "\n  not taken — $it" })
            is PluginDataDecision.Relocate -> {
                PluginLog.warn(LOG_CATEGORY, "Programs cannot run in the plugin data directory ${result.from}: ${result.reason}; the data moves to ${result.target}")
                moveTo(result.target.toString())
            }
        }
        return result
    }

    /** Makes [directory] the data directory (empty: the default) and moves what the old one holds; blocking. */
    private fun moveTo(directory: String) {
        val from = PluginData.layout()
        DotNetSettings.getInstance().pluginDataDirectory = directory
        val to = PluginData.layout()
        val failed = PluginData.move(from, to)
        PluginLog.info(LOG_CATEGORY, "Plugin data moved from ${from.data} to ${to.data}, logs from ${from.logs} to ${to.logs}" +
            if (failed.isEmpty()) "" else "; not moved (rebuilt when needed): ${failed.joinToString()}")
    }

    /**
     * Settings | .NET → Plugin data: makes [directory] the data directory in the background, after probing it. A directory where programs
     * cannot run is refused with a notification.
     */
    fun relocate(project: Project?, directory: String) {
        object : Task.Backgroundable(project, "Moving the data of C# Project Support", false) {
            override fun run(indicator: ProgressIndicator) {
                val target = directory.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) } ?: PluginData.defaultRoot()
                val outcome = DotNetExecutionProbe.check(target)
                if (outcome.result == DotNetExecutionProbe.Result.NOT_EXECUTABLE) {
                    DotNetCli.notifyError(project, TITLE, "Programs cannot run in $target (${outcome.reason}): the data of the plugin stays in ${PluginData.root()}.")
                    return
                }
                moveTo(directory.trim())
                decision = PluginDataDecision.Keep
                project?.let { DotNetCli.notifyInfo(it, "C# Project Support keeps its data in ${PluginData.root()}", "Logs: ${PluginData.logs()}") }
            }
        }.queue()
    }

    /** At the start of a project with a solution or a project file: the decision, and a notification about it once a session. */
    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) {
            if (SystemInfo.isWindows || ApplicationManager.getApplication().isUnitTestMode || project.isDefault) return
            val root = project.guessProjectDir() ?: return
            val found = readAction { if (root.isValid) io.github.dotnetsupport.solution.SolutionFinder.find(root, includeProjects = true) else null } ?: return
            if (found.solutions.isEmpty() && found.projects.isEmpty()) return
            val result = ensureDecided()
            if (result is PluginDataDecision.Keep || !notified.compareAndSet(false, true)) return
            val group = NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            val notification = when (result) {
                is PluginDataDecision.Relocate -> group.createNotification(TITLE,
                    "Programs cannot run in ${result.from} (${result.reason}). The plugin keeps its data, temporary files and logs in ${result.target} now.", NotificationType.INFORMATION)
                is PluginDataDecision.Notify -> group.createNotification(TITLE,
                    "Programs cannot run in ${result.root} (${result.reason}), so the helpers of the plugin cannot start there. Choose a directory where they can " +
                        "(Settings | .NET → Plugin data), for example one an execution policy allows.", NotificationType.WARNING)
                is PluginDataDecision.Keep -> return
            }
            // a work home exists but did not take the data by itself: offer it, the probe decides again when it is chosen
            (result as? PluginDataDecision.Notify)?.suggestion?.let { suggested ->
                notification.addAction(NotificationAction.createSimpleExpiring("Use $suggested") { relocate(project, suggested.toString()) })
            }
            notification.addAction(NotificationAction.createSimple("Choose Directory...") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, io.github.dotnetsupport.settings.DotNetSettingsConfigurable::class.java)
            })
            notification.notify(project)
        }
    }

    const val LOG_CATEGORY = "plugin-data"
    private const val TITLE = "The data directory of C# Project Support"
}
