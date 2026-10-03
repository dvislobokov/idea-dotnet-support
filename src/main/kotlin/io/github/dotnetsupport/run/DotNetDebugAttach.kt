package io.github.dotnetsupport.run

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Attaches a debugger to a running .NET process. The plugin itself has no debugger: the implementation comes from the module that has
 * one (`io.github.dotnetsupport.dap`), and where that module is not loaded there is none, which is what [find] tells.
 */
interface DotNetProcessAttacher {
    /**
     * [title] names the debug session: "Tests of Shop.Tests", a process name. Called on any thread.
     * [skipInitialBreak]: the process calls `Debugger.Break()` as soon as a debugger is there, and nobody wants to stop at that (a test host).
     */
    fun attach(project: Project, processId: Long, title: String, skipInitialBreak: Boolean = false)

    companion object {
        val EP_NAME: ExtensionPointName<DotNetProcessAttacher> = ExtensionPointName.create("io.github.dotnetsupport.processAttacher")

        fun find(): DotNetProcessAttacher? = EP_NAME.extensionList.firstOrNull()
    }
}

/** `dotnet test` with `VSTEST_HOST_DEBUG=1`: the test host prints its process id and waits until a debugger is attached. */
object TestHostDebug {
    const val VARIABLE = "VSTEST_HOST_DEBUG"

    // "Process Id: 12345, Name: testhost"; the words are localized, the shape is not
    private val PROCESS_ID = Regex("""(?i)(?:process\s*id|pid)\s*:\s*(\d+)""")
    private val ANY_LANGUAGE = Regex("""^[^:\d]{3,40}:\s*(\d{2,9})\s*,\s*[^:\d]{2,20}:\s*(?:testhost|dotnet)""")

    /** How the debug adapter describes the stop at the `Debugger.Break()` a test host greets its debugger with. */
    fun isInitialBreak(reason: String?, description: String?): Boolean = reason == "pause" && description == "Debugger.Break"

    /** The process id in a chunk of the output of `dotnet test`, or null. */
    fun processId(text: String): Long? = text.lineSequence().map { it.trim() }.firstNotNullOfOrNull { line ->
        (PROCESS_ID.find(line) ?: ANY_LANGUAGE.find(line))?.groupValues?.get(1)?.toLongOrNull()
    }
}

/** Which processes of the machine "Attach to Process" offers the .NET debugger for. */
object DotNetProcesses {
    private val HOSTS = setOf("dotnet", "dotnet.exe", "testhost", "testhost.exe")

    // attaching a second debugger kills the process on Windows, and the debug adapter reports success for a process that is not .NET
    private val NEVER = setOf("dotnet-debugger", "dotnet-debugger.exe")

    /**
     * A .NET application is `dotnet App.dll`, or an apphost: an executable with `App.dll` / `App.runtimeconfig.json` next to it.
     * [siblingExists] looks next to the executable, so that the rule can be checked without a file system.
     */
    fun isDotNet(executableName: String, commandLine: String, siblingExists: (String) -> Boolean): Boolean {
        val name = executableName.substringAfterLast('/').substringAfterLast('\\')
        if (name.lowercase() in NEVER) return false
        if (name.lowercase() in HOSTS) return true
        val base = name.removeSuffix(".exe")
        return siblingExists("$base.runtimeconfig.json") || (siblingExists("$base.dll") && siblingExists("$base.deps.json")) ||
            Regex("""(?i)\bdotnet(\.exe)?"?\s+(exec\s+)?\S+\.dll""").containsMatchIn(commandLine)
    }

    fun isDotNet(executablePath: String?, executableName: String, commandLine: String): Boolean {
        val directory = executablePath?.let { File(it).parentFile }
        return isDotNet(executableName, commandLine) { directory != null && File(directory, it).isFile }
    }

    /** Offer .NET Framework processes too: off until the debug adapter can debug the desktop CLR. */
    const val NET_FRAMEWORK_KEY = "dotnet.debugger.attach.netFramework"

    private val managedExecutables = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /**
     * A .NET Framework application: a managed `.exe` (the PE file has a CLI header) without `runtimeconfig.json` next to it. An apphost of
     * .NET is native, so the header alone tells them apart; hosts that load the CLR themselves (`w3wp.exe`, Office) are not found this way.
     */
    fun isNetFramework(executablePath: String?): Boolean {
        val file = executablePath?.let(::File)?.takeIf { it.name.endsWith(".exe", ignoreCase = true) && it.isFile } ?: return false
        if (File(file.parentFile, file.name.dropLast(4) + ".runtimeconfig.json").isFile) return false
        val modified = file.lastModified()
        managedExecutables[file.path]?.takeIf { it.first == modified }?.let { return it.second }
        val managed = runCatching { file.inputStream().use { PortableExecutable.isManaged(it.readNBytes(4096)) } }.getOrDefault(false)
        managedExecutables[file.path] = modified to managed
        return managed
    }
}

/** Just enough of the PE format to tell a managed executable from a native one. */
object PortableExecutable {
    private const val CLI_HEADER_DIRECTORY = 14

    /** Whether the start of a PE file ([header], the first few KB) has a CLI header in its data directories. */
    fun isManaged(header: ByteArray): Boolean {
        fun u16(at: Int) = if (at + 2 > header.size) -1 else (header[at].toInt() and 0xFF) or ((header[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int): Long = if (at + 4 > header.size) -1 else (u16(at).toLong() or (u16(at + 2).toLong() shl 16))
        if (u16(0) != 0x5A4D) return false // MZ
        val pe = u32(0x3C).toInt()
        if (pe <= 0 || u32(pe) != 0x4550L) return false // PE\0\0
        val optional = pe + 24
        val (count, directories) = when (u16(optional)) {
            0x10B -> u32(optional + 92) to optional + 96   // PE32
            0x20B -> u32(optional + 108) to optional + 112 // PE32+
            else -> return false
        }
        if (count <= CLI_HEADER_DIRECTORY) return false
        val entry = directories + CLI_HEADER_DIRECTORY * 8
        return u32(entry) > 0 && u32(entry + 4) > 0
    }
}
