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

    private const val WINDOWS_GUI = 2

    /** `Subsystem` of the optional header: 2 a window program (`WinExe`), 3 a console one; null for what is not a PE file. */
    fun subsystem(header: ByteArray): Int? {
        fun u16(at: Int) = if (at + 2 > header.size) -1 else (header[at].toInt() and 0xFF) or ((header[at + 1].toInt() and 0xFF) shl 8)
        if (u16(0) != 0x5A4D) return null // MZ
        val pe = u16(0x3C) or (u16(0x3E) shl 16)
        if (pe <= 0 || u16(pe) != 0x4550 || u16(pe + 2) != 0) return null // PE\0\0
        // at the same offset in PE32 and PE32+
        return u16(pe + 24 + 68).takeIf { it >= 0 }
    }

    /** A window program: it has no console, so Ctrl+C, the soft stop of the IDE, never reaches it. */
    fun isWindowsGui(file: java.io.File): Boolean =
        runCatching { file.inputStream().use { it.readNBytes(4096) } }.getOrNull()?.let(::subsystem) == WINDOWS_GUI

    /** Why a program runs as a 32-bit process on 64-bit Windows. */
    enum class ThirtyTwoBit {
        /** AnyCPU with "Prefer 32-bit": what MSBuild makes of a program of .NET Framework 4.5+ unless the project says `Prefer32Bit` false. */
        PREFER_32_BIT,
        /** Built for x86 (`PlatformTarget`, and the default of an SDK-style program of .NET Framework), or native / mixed 32-bit code. */
        X86,
    }

    private const val IL_ONLY = 0x1L
    private const val REQUIRES_32_BIT = 0x2L
    private const val PREFERS_32_BIT = 0x20000L

    /**
     * How [image] (the start of a PE file, enough to hold its CLI header: [readStart]) runs on 64-bit Windows: null for a 64-bit
     * process (PE32+, or IL-only AnyCPU) and for what cannot be read. The CLI header lives in a section, so its flags are found through
     * the section table; a PE32 file without one is native 32-bit code (an apphost for x86).
     */
    fun thirtyTwoBit(image: ByteArray): ThirtyTwoBit? {
        fun u16(at: Int) = if (at < 0 || at + 2 > image.size) -1 else (image[at].toInt() and 0xFF) or ((image[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int): Long = if (at < 0 || at + 4 > image.size) -1 else (u16(at).toLong() or (u16(at + 2).toLong() shl 16))
        if (u16(0) != 0x5A4D) return null // MZ
        val pe = u32(0x3C).toInt()
        if (pe <= 0 || u32(pe) != 0x4550L) return null // PE\0\0
        val optional = pe + 24
        if (u16(optional) != 0x10B) return null // PE32+ (or not a PE file): 64-bit
        val cliRva = if (u32(optional + 92) > CLI_HEADER_DIRECTORY) u32(optional + 96 + CLI_HEADER_DIRECTORY * 8) else 0L
        if (cliRva <= 0) return ThirtyTwoBit.X86
        val sections = optional + u16(pe + 20)
        val cliHeader = (0 until u16(pe + 6)).firstNotNullOfOrNull { i ->
            val section = sections + i * 40
            val (virtualSize, virtualAddress) = u32(section + 8) to u32(section + 12)
            val (rawSize, rawPointer) = u32(section + 16) to u32(section + 20)
            if (virtualAddress < 0 || rawPointer < 0) return null
            if (cliRva >= virtualAddress && cliRva < virtualAddress + maxOf(virtualSize, rawSize)) (cliRva - virtualAddress + rawPointer).toInt() else null
        } ?: return null
        val flags = u32(cliHeader + 16).takeIf { it >= 0 } ?: return null
        return when {
            flags and IL_ONLY == 0L -> ThirtyTwoBit.X86 // mixed C++/CLI code of a PE32 file
            flags and REQUIRES_32_BIT != 0L && flags and PREFERS_32_BIT != 0L -> ThirtyTwoBit.PREFER_32_BIT
            flags and REQUIRES_32_BIT != 0L -> ThirtyTwoBit.X86
            else -> null
        }
    }

    /** [thirtyTwoBit] of a file on disk; the CLI header of a managed program is near the start of its first section. */
    fun thirtyTwoBit(file: java.io.File): ThirtyTwoBit? = readStart(file)?.let(::thirtyTwoBit)

    private fun readStart(file: java.io.File): ByteArray? = runCatching { file.inputStream().use { it.readNBytes(64 * 1024) } }.getOrNull()
}
