package io.github.dotnetsupport.aspire

/**
 * Which processes DCP (the orchestrator of Aspire) has started for the projects of the solution. Seen on Aspire 13.6 (Windows):
 * ```
 * Probe.AppHost.exe                                   (the AppHost, under the debugger)
 * dcp.exe start-apiserver --monitor <AppHost pid> …   (not a child of the AppHost: its parent has exited)
 *   dcp.exe run-controllers --monitor <apiserver pid> …
 *     dotnet run --project …\Probe.ApiService.csproj --no-build --configuration Debug --no-launch-profile
 *       …\Probe.ApiService\bin\Debug\net10.0\Probe.ApiService.exe     <- the service: the debugger attaches here
 *     dcp.exe monitor-process --child <dotnet run pid> …
 *     Aspire.Dashboard.exe
 * ```
 * So the AppHost is found in the command line of the API server of DCP, and a service by the path of its executable (the apphost of
 * .NET in the output of a project), or by the `.dll` that `dotnet [exec]` runs where there is no apphost.
 */
object AspireProcesses {
    /** A process of the machine; [commandLine] may be empty (Java cannot read it on Windows), [executablePath] may be null. */
    data class Row(val pid: Long, val parentPid: Long?, val executablePath: String?, val commandLine: String)

    /** A service of the solution: [projectPath] is the project file. */
    data class Service(val pid: Long, val projectPath: String) {
        val name: String get() = projectPath.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
    }

    private val MONITOR = Regex("""--monitor[\s=]+"?(\d+)""")
    // a quoted path, or an absolute one without spaces
    private val DLL = Regex("""(?i)"([^"]+?\.dll)"|(?:^|\s)((?:[A-Za-z]:[\\/]|/)\S+?\.dll)(?=\s|$)""")

    fun fileName(path: String?): String = path.orEmpty().replace('\\', '/').substringAfterLast('/').lowercase()

    fun isDcp(row: Row): Boolean = fileName(row.executablePath).removeSuffix(".exe") == "dcp"

    private fun isDotNetHost(row: Row): Boolean = fileName(row.executablePath).removeSuffix(".exe") == "dotnet"

    /** The API server of DCP that watches [appHostPid]: started by the AppHost and gone with it. A DCP process the AppHost started itself counts too. */
    fun isDcpRootOf(row: Row, appHostPid: Long): Boolean =
        isDcp(row) && (row.parentPid == appHostPid || MONITOR.find(row.commandLine)?.groupValues?.get(1)?.toLongOrNull() == appHostPid)

    /** A process whose command line is needed to place it: a DCP process without a DCP parent, or a `dotnet` host. */
    fun needsCommandLine(row: Row, parentIsDcp: Boolean): Boolean = isDcp(row) && !parentIsDcp || isDotNetHost(row)

    /**
     * The services among [rows] (a snapshot of the processes, or just the trees of the DCP processes): the processes under the DCP of
     * [appHostPid] that run the output of one of [projects] (project file paths). `dotnet run` itself, DCP and the dashboard are not.
     */
    fun services(rows: List<Row>, appHostPid: Long, projects: Collection<String>): List<Service> {
        val children = rows.groupBy { it.parentPid }
        val roots = rows.filter { isDcpRootOf(it, appHostPid) }
        val directories = projects.map { normalize(it).substringBeforeLast('/') + "/" to it }.sortedByDescending { it.first.length }
        fun projectOf(path: String?): String? = path?.let(::normalize)?.let { file -> directories.firstOrNull { file.startsWith(it.first) }?.second }

        val result = ArrayList<Service>()
        val seen = HashSet<Long>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val row = queue.removeFirst()
            if (!seen.add(row.pid)) continue
            children[row.pid]?.let(queue::addAll)
            if (isDcp(row)) continue
            val project = when {
                isDotNetHost(row) -> DLL.findAll(row.commandLine).firstNotNullOfOrNull { match -> projectOf(match.groupValues[1].ifEmpty { match.groupValues[2] }) }
                else -> projectOf(row.executablePath)
            }
            if (project != null) result += Service(row.pid, project)
        }
        return result
    }

    private fun normalize(path: String): String = path.replace('\\', '/').lowercase()
}
