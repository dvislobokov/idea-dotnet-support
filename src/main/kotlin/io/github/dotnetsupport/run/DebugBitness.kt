package io.github.dotnetsupport.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.RunCanceledByUserException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.PluginLog
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.dotnetsupport.run.PortableExecutable.ThirtyTwoBit
import java.io.File

/**
 * The debug adapter (`dotnet-debugger` 0.2) debugs 64-bit processes only and refuses a 32-bit one. Old project templates (and MSBuild
 * itself, for a program of .NET Framework 4.5+) make AnyCPU programs that prefer 32 bits, so most programs of the old format are such:
 * the plugin tells it before the adapter is started, and says which property of the project to change.
 */
object DebugBitness {
    /** Null when [program] is debuggable as far as its bitness goes; otherwise why not, in words for the user. */
    fun refusal(program: File, projectName: String?): String? = PortableExecutable.thirtyTwoBit(program)?.let { message(program.name, projectName, it) }

    /** No angle brackets: the platform and the notifications show the text as HTML, and `<Prefer32Bit>` would vanish as a tag. */
    fun message(programName: String, projectName: String?, reason: ThirtyTwoBit): String {
        val project = projectName ?: "the project"
        return when (reason) {
            ThirtyTwoBit.PREFER_32_BIT -> "$programName runs as a 32-bit process (AnyCPU with \"Prefer 32-bit\", the default of old project templates), " +
                "and the .NET debugger debugs 64-bit processes only. Set Prefer32Bit to false in $project and debug again."
            ThirtyTwoBit.X86 -> "$programName runs as a 32-bit process (built for x86), and the .NET debugger debugs 64-bit processes only. " +
                "Build it for x64 or AnyCPU (PlatformTarget in $project) and debug again."
        }
    }

    fun processMessage(title: String): String = "$title is a 32-bit process, and the .NET debugger debugs 64-bit processes only."

    /**
     * Refuses a 32-bit [program] with a notification: the balloon of the platform for a failed launch is one line wide and cuts the
     * advice off (seen live). The launch ends as cancelled, so that the platform adds no second message.
     */
    @Throws(ExecutionException::class)
    fun check(project: Project?, program: File, projectName: String?) {
        refusal(program, projectName)?.let { refuse(project, it) }
    }

    /** Attach: a process of 32 bits (WOW64) on 64-bit Windows is refused by the adapter, so it is refused here first, in plain words. */
    @Throws(ExecutionException::class)
    fun checkProcess(project: Project?, processId: Long, title: String) {
        if (isWow64(processId) == true) refuse(project, processMessage(title))
    }

    private fun refuse(project: Project?, message: String): Nothing {
        PluginLog.warn(LOG_CATEGORY, message)
        DotNetCli.notifyError(project, "Cannot Debug a 32-bit Process", StringUtil.escapeXmlEntities(message))
        throw RunCanceledByUserException()
    }

    private const val LOG_CATEGORY = "debugger"

    /** Null where it cannot be told: not Windows, no access to the process. */
    fun isWow64(processId: Long): Boolean? {
        if (!SystemInfo.isWindows) return null
        val kernel = kernel32 ?: return null
        val process = kernel.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, processId.toInt()) ?: return null
        return try {
            val result = IntArray(1)
            if (kernel.IsWow64Process(process, result)) result[0] != 0 else null
        } finally {
            kernel.CloseHandle(process)
        }
    }

    private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun OpenProcess(access: Int, inheritHandle: Boolean, processId: Int): Pointer?
        fun IsWow64Process(process: Pointer, wow64: IntArray): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    private val kernel32: Kernel32? by lazy { if (SystemInfo.isWindows) runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull() else null }
}
