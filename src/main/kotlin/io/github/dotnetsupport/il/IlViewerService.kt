package io.github.dotnetsupport.il

import com.intellij.execution.ExecutionException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.build.DotNetBuildOptions
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.msbuild.MsBuildEvaluation
import io.github.dotnetsupport.msbuild.MsBuildEvaluationResult
import io.github.dotnetsupport.run.MsBuildTargetPath
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Where the build of a project puts its assembly; null when it cannot be told. Blocking, not for the EDT. */
fun interface IlAssemblyLocator {
    fun assembly(projectFile: VirtualFile): String?
}

/** The caret in a C# editor, taken on the EDT: the viewer works on this copy in the background. [line] is 1-based. */
class IlCaret(val file: VirtualFile, val text: CharSequence, val offset: Int, val line: Int, val unsaved: Boolean)

/**
 * The IL of the code at the caret, as the IL Viewer of Rider shows it: the project that owns the file, its assembly in the configuration
 * and the framework of the toolbar (the ones Debug launches), the names of the type and the member for the helper.
 * [source] and [locator] are replaced in tests.
 */
@Service(Service.Level.PROJECT)
class IlViewerService(private val project: Project) {
    @Volatile var source: IlSource = IlHelperSource()
    @Volatile var locator: IlAssemblyLocator = IlAssemblyLocator(::targetPath)

    /** What the viewer shows for [caret]; blocking (MSBuild, the helper), not for the EDT. */
    fun compute(caret: IlCaret): IlViewState {
        if (!caret.file.name.endsWith(".cs", ignoreCase = true)) return IlViewState.Empty(IlViewState.NOT_CSHARP)
        val projectFile = ReadAction.computeBlocking<VirtualFile?, RuntimeException> { if (caret.file.isValid) DotNetProjects.findOwningProject(caret.file) else null }
            ?: return IlViewState.Empty(IlViewState.NO_PROJECT)
        val assembly = locator.assembly(projectFile)?.takeIf { File(it).isFile } ?: return IlViewState.NotBuilt(projectFile.path)
        val (typeName, memberName) = IlViewerLogic.names(caret.text, caret.offset)
        val request = IlRequest(assembly, FileUtil.toSystemDependentName(caret.file.path), caret.line, typeName, memberName)
        val answer = try {
            source.il(request)
        } catch (e: HelperException) {
            PluginLog.warn(LOG_CATEGORY, "IL of ${caret.file.name}:${caret.line} in ${File(assembly).name}: ${e.message}")
            return IlViewState.Failed(e.message ?: "The IL helper has failed", projectFile.path)
        } catch (e: RuntimeException) {
            PluginLog.error(LOG_CATEGORY, "IL of ${caret.file.name}:${caret.line} in ${File(assembly).name}", e)
            return IlViewState.Failed(PluginLog.describe(e), projectFile.path)
        }
        val stale = IlViewerLogic.isStale(answer.assemblyModified, caret.file.timeStamp, projectFile.timeStamp, caret.unsaved)
        return IlViewState.Shown(request, answer, projectFile.path, stale)
    }

    // MSBuild is asked once per project, configuration and state of the project file, not at every move of the caret
    private val targetPaths = ConcurrentHashMap<String, String>()

    /** MsBuildHost, then `dotnet msbuild -getProperty`, as the build before Debug finds the assembly. */
    private fun targetPath(projectFile: VirtualFile): String? {
        val settings = DotNetBuildSettings.getInstance(project)
        val configuration = settings.configuration
        val framework = settings.launchFramework(projectFile)
        val properties = DotNetBuildOptions.getInstance(project).state.globalProperties.orEmpty()
        val key = listOf(projectFile.path, configuration, framework, properties, projectFile.modificationStamp).joinToString("|")
        targetPaths[key]?.let { return it.ifEmpty { null } }
        val globals = MsBuildEvaluationResult.globalProperties(properties)
        val found = MsBuildEvaluation.getInstance(project).targetPath(projectFile, configuration, framework, globals)
            ?: if (ApplicationManager.getApplication().isUnitTestMode) null else targetPathOfCli(projectFile, configuration, framework, properties)
        targetPaths[key] = found.orEmpty()
        return found
    }

    private fun targetPathOfCli(projectFile: VirtualFile, configuration: String, framework: String?, properties: String): String? = try {
        val arguments = MsBuildTargetPath.arguments(projectFile.path, configuration, framework, DotNetBuildOptions.propertyArguments(properties))
        val output = DotNetCli.execute(DotNetCli.commandLine(projectFile.parent.path, *arguments.toTypedArray()))
        if (output.exitCode == 0) MsBuildTargetPath.parse(output.stdout)
        else null.also { PluginLog.warn(LOG_CATEGORY, "`msbuild -getProperty:TargetPath` of ${projectFile.name} exit code ${output.exitCode}: ${DotNetCli.lastLines(output, 1)}") }
    } catch (e: ExecutionException) {
        PluginLog.warn(LOG_CATEGORY, "`msbuild -getProperty:TargetPath` of ${projectFile.name} could not run", e)
        null
    }

    companion object {
        /** The category of the journal of the plugin for the IL Viewer. */
        const val LOG_CATEGORY = "il"

        fun getInstance(project: Project): IlViewerService = project.service()
    }
}
