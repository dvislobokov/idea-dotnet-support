package io.github.dotnetsupport.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.sun.jna.Library
import com.sun.jna.Native
import io.github.dotnetsupport.solution.SolutionService
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Run of a project of the old format (without `Sdk`): `dotnet run` does not know such a project, so the program its build has made is
 * started directly, as Visual Studio and Rider do. The path comes from "Build .NET Project" before the launch (MSBuild's `TargetPath`).
 * SDK-style projects, `net48` ones included, stay with `dotnet run`, which runs them fine.
 */
object ExecutableLaunch {
    fun applies(project: Project, options: DotNetRunConfigurationOptions): Boolean {
        if (options.command != DotNetCommand.RUN) return false
        val file = LocalFileSystem.getInstance().findFileByPath(options.projectPath.orEmpty()) ?: return false
        return SolutionService.getInstance(project).msBuildProject(file).isLegacy
    }

    /** [targetPath] null: the project has not been built before the launch, nothing to start. */
    @Throws(ExecutionException::class)
    fun commandLine(targetPath: String?, projectPath: String, programArguments: List<String>, workingDirectory: String?): GeneralCommandLine {
        val project = File(projectPath).name
        if (targetPath.isNullOrBlank()) {
            throw ExecutionException("$project is not built, so there is no program to start: keep \"Build .NET Project\" in Before launch of the configuration")
        }
        if (!targetPath.endsWith(".exe", ignoreCase = true)) throw ExecutionException("$project builds a library, not a program ($targetPath): run the project that uses it")
        val program = File(targetPath)
        // as in Visual Studio: a .NET Framework program starts in its output folder, where its app.config and content files are
        return GeneralCommandLine(program.path).withParameters(programArguments)
            .withWorkDirectory(workingDirectory?.takeIf { it.isNotBlank() } ?: program.parent)
            .withCharset(consoleCharset())
    }

    /**
     * The console of a .NET Framework program writes in the OEM code page of Windows (866 on a Russian one, 437 on an English one), not
     * UTF-8 as `dotnet` does. What that code page cannot hold is `?` already in the program, as in `cmd`.
     */
    fun consoleCharset(): Charset = if (SystemInfo.isWindows) oemCodePage?.let(::charsetOf) ?: Charset.defaultCharset() else StandardCharsets.UTF_8

    fun charsetOf(codePage: Int): Charset? =
        if (codePage == 65001) StandardCharsets.UTF_8 else runCatching { Charset.forName("cp$codePage") }.getOrNull()

    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun GetOEMCP(): Int
    }

    private val oemCodePage: Int? by lazy { runCatching { Native.load("kernel32", Kernel32::class.java).GetOEMCP() }.getOrNull() }
}
