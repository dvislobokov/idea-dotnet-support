package io.github.dotnetsupport.index

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.solution.SolutionService
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * A diagnostic for a name the compiler sees and the plugin does not (CS0246 on `List`, CS1061 on `Type.GetMethod` in a live solution,
 * 0.1.181): what the plugin has decided about the project of the C# file in the editor — its project, the target of the assets file, the
 * libraries and assemblies, which of them the set of indexes keeps, and where the types every project has come from. Copied as text, to
 * be sent; nothing of the sources is in it, only paths and names of packages.
 */
class CopyCompilationReferencesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isVisible = e.project != null
        e.presentation.isEnabled = file != null && file.extension.equals("cs", ignoreCase = true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = runCatching { report(project, file) }.getOrElse { "the report failed: $it\n" + it.stackTraceToString() }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                CopyPasteManager.getInstance().setContents(StringSelection(text))
                DotNetCli.notifyInfo(project, "Compilation references copied", "What the plugin compiles ${file.name} against is in the clipboard (${text.lines().size} lines)")
            }, ModalityState.any())
        }
    }

    private fun report(project: Project, file: VirtualFile): String = buildString {
        val version = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport"))?.version
        appendLine("C# Project Support $version — compilation references of ${file.path}")
        val model = CompilationModel.getInstance(project)
        val projectFile = ReadAction.compute<VirtualFile?, RuntimeException> { model.projectOf(file) }
        appendLine("project: ${projectFile?.path ?: "none (a loose file)"}")
        projectFile ?: return@buildString
        append(projectReport(project, projectFile))
    }

    companion object {
        /** What the plugin compiles [projectFile] against: frameworks, the assets file, libraries, the set of indexes, where the basic types come from. */
        fun projectReport(project: Project, projectFile: VirtualFile): String = buildString {
            val msbuild = SolutionService.getInstance(project).msBuildProject(projectFile)
            appendLine("target frameworks in the project file: ${msbuild.targetFrameworks}; old format: ${msbuild.isLegacy}; toolbar: ${DotNetBuildSettings.getInstance(project).framework}")
            val dotnet = DotNetCli.findExecutable()?.let { runCatching { File(it).canonicalFile }.getOrDefault(File(it)) }
            appendLine("dotnet: ${dotnet?.path}; packs next to it: ${dotnet?.parentFile?.let { File(it, "packs").list()?.sorted() } ?: "NONE"}")
            val assets = File(projectFile.parent.path, "obj/project.assets.json")
            appendLine("assets: ${assets.path} ${if (assets.isFile) "(${assets.length()} bytes)" else "MISSING"}")
            if (assets.isFile) appendLine(assetsSummary(assets.readText()))

            val service = AssemblyIndexService.getInstance(project)
            val references = service.references(projectFile)
            appendLine()
            appendLine("references: ${if (references == null) "none computed" else "target ${references.framework}, ${references.assemblies.size} assemblies, ${references.projects.size} projects, ${references.projectOutputs.size} project outputs"}")
            for (library in references?.libraries.orEmpty()) {
                appendLine("  ${library.kind} ${library.presentableName}: ${library.assemblies.size} dll, ${library.assemblies.firstOrNull()?.parent}")
            }
            for (p in references?.projects.orEmpty()) appendLine("  PROJECT $p")
            for (o in references?.projectOutputs.orEmpty()) appendLine("  OUTPUT $o")

            val indexes = service.indexes(projectFile)
            val kept = indexes.mapNotNullTo(HashSet()) { service.assemblyFile(it.mvid)?.path }
            appendLine()
            appendLine("indexes in the set: ${indexes.size}; complete: ${service.isComplete(projectFile)}")
            val dropped = references?.forIndex.orEmpty().filter { it.path !in kept }
            if (dropped.isNotEmpty()) {
                appendLine("not in the set (a conflict lost, or not indexed): ${dropped.size}")
                for (d in dropped) appendLine("  $d")
            }
            for (index in indexes) appendLine("  ${index.assemblyName} ${index.assemblyVersion} ${service.assemblyFile(index.mvid)}")

            val set = service.symbols(projectFile)
            appendLine()
            appendLine("types:")
            for ((name, member) in PROBES) {
                val type = set.findType(name)
                if (type == null) { appendLine("  $name: NOT FOUND"); continue }
                val members = set.ownMembers(type)
                appendLine("  $name: ${type.index.assemblyName} ${type.index.assemblyVersion} ${service.assemblyFile(type.index.mvid)}, ${members.size} members" +
                    (member?.let { m -> ", $m: ${if (members.any { it.name == m }) "yes" else "NO"}" } ?: ""))
            }
        }

        private val PROBES = listOf(
            "System.Object" to "ToString", "System.Enum" to "ToString", "System.Type" to "GetMethod", "System.Threading.Tasks.Task" to "Dispose",
            "System.Collections.Generic.List`1" to null, "System.Collections.Generic.Dictionary`2" to null, "System.Linq.Enumerable" to "ToArray",
            "System.IServiceProvider" to null, "System.Collections.Concurrent.ConcurrentDictionary`2" to null,
        )

        /** The targets, the frameworks of the project and the entries of the framework packages of the assets file, as restore wrote them. */
        fun assetsSummary(text: String): String = buildString {
            val root = runCatching { JsonParser.parseString(text.removePrefix("﻿")) as? JsonObject }.getOrNull() ?: return "  not JSON"
            val gson = GsonBuilder().setPrettyPrinting().create()
            val targets = root.getAsJsonObject("targets")
            appendLine("  targets: ${targets?.keySet()}")
            appendLine("  packageFolders: ${root.getAsJsonObject("packageFolders")?.keySet()}")
            root.getAsJsonObject("project")?.getAsJsonObject("frameworks")?.entrySet()?.forEach { (tfm, value) ->
                val framework = value as? JsonObject
                appendLine("  framework $tfm: frameworkReferences ${framework?.getAsJsonObject("frameworkReferences")?.keySet()}, " +
                    "downloadDependencies ${framework?.get("downloadDependencies")}, restored by ${framework?.get("runtimeIdentifierGraphPath")}")
            }
            for ((target, value) in targets?.entrySet().orEmpty()) {
                val entries = (value as? JsonObject)?.entrySet().orEmpty()
                appendLine("  target $target: ${entries.size} entries")
                for ((id, entry) in entries) {
                    val name = id.substringBefore('/')
                    if (FRAMEWORK_PACKAGES.any { name.equals(it, ignoreCase = true) }) appendLine("    $id: ${gson.toJson(entry).replace("\n", "\n    ")}")
                }
            }
        }

        private val FRAMEWORK_PACKAGES = listOf("NETStandard.Library", "Microsoft.NETCore.App", "System.Runtime", "System.Collections", "System.Linq", "Microsoft.NETCore.Platforms")
    }
}
