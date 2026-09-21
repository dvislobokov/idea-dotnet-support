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
}
