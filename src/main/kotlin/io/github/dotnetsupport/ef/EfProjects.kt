package io.github.dotnetsupport.ef

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.msbuild.MsBuildProject
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import java.io.StringReader

/** Which projects of the solution `dotnet ef` makes sense for, and what to offer for its options. */
object EfProjects {
    const val DESIGN_PACKAGE = "Microsoft.EntityFrameworkCore.Design"
    private const val CORE_PACKAGE = "Microsoft.EntityFrameworkCore"
    private val APP_SETTINGS = Regex("""^appsettings\.([^.]+)\.json$""", RegexOption.IGNORE_CASE)
    private val DEFAULT_ENVIRONMENTS = listOf("Development", "Staging", "Production")

    fun solutionProjects(project: Project): List<VirtualFile> {
        val solutions = SolutionService.getInstance(project)
        return solutions.solutionFiles()
            .flatMap { file -> solutions.solution(file).allProjects.mapNotNull { it.resolveFile(file) } }
            .distinct().sortedBy { it.nameWithoutExtension.lowercase() }
    }

    fun usesEf(msBuildProject: MsBuildProject): Boolean = msBuildProject.packages.any { it.name.contains("EntityFrameworkCore", ignoreCase = true) }

    /** A package reference of the project, or (a package coming through a project reference) what the restore has resolved. */
    fun usesEf(project: Project, projectFile: VirtualFile): Boolean {
        val solutions = SolutionService.getInstance(project)
        return usesEf(solutions.msBuildProject(projectFile)) || solutions.assets(projectFile).targets.any { it.findPackage(CORE_PACKAGE) != null }
    }

    /** Projects that can hold a `DbContext` and migrations. */
    fun migrationsProjects(project: Project): List<VirtualFile> = solutionProjects(project).filter { usesEf(project, it) }

    /** Applications EF can start to get a configured context. */
    fun startupProjects(project: Project): List<VirtualFile> {
        val solutions = SolutionService.getInstance(project)
        return solutionProjects(project).filter { solutions.msBuildProject(it).let { p -> p.isRunnable && !p.isTestProject } }
    }

    /**
     * The startup project to suggest for [migrationsProject]: itself when it is an application, otherwise an application
     * that references it, the one with the design package first.
     */
    fun defaultStartupProject(project: Project, migrationsProject: VirtualFile): VirtualFile? {
        val solutions = SolutionService.getInstance(project)
        val candidates = startupProjects(project)
        if (migrationsProject in candidates) return migrationsProject
        val referencing = candidates.filter { references(solutions, it, migrationsProject) }
        return referencing.firstOrNull { hasDesignPackage(project, it) == true } ?: referencing.firstOrNull() ?: candidates.singleOrNull()
    }

    private fun references(solutions: SolutionService, from: VirtualFile, to: VirtualFile): Boolean {
        // the assets list the transitive references too, the project file only the direct ones
        val resolved = solutions.assets(from).targets.flatMap { it.projects }.mapNotNull { it.path }
        val declared = solutions.msBuildProject(from).projectReferences
        return (resolved + declared).any { from.parent?.findFileByRelativePath(it) == to }
    }

    /** Null when the project is not restored yet and the package is not referenced directly: unknown. */
    fun hasDesignPackage(project: Project, startupProject: VirtualFile): Boolean? {
        val solutions = SolutionService.getInstance(project)
        if (solutions.msBuildProject(startupProject).packages.any { it.name.equals(DESIGN_PACKAGE, ignoreCase = true) }) return true
        val targets = solutions.assets(startupProject).targets
        return if (targets.isEmpty()) null else targets.any { it.findPackage(DESIGN_PACKAGE) != null }
    }

    /** The version of EF the project is restored with: the design package has to match it. */
    fun efVersion(project: Project, projectFile: VirtualFile): String? =
        SolutionService.getInstance(project).assets(projectFile).targets.firstNotNullOfOrNull { it.findPackage(CORE_PACKAGE)?.version }

    /** `appsettings.<Environment>.json` of the startup project, then the standard names. */
    fun environments(startupProject: VirtualFile?): List<String> {
        val configured = startupProject?.parent?.children.orEmpty().mapNotNull { APP_SETTINGS.find(it.name)?.groupValues?.get(1) }
        return (configured.sorted() + DEFAULT_ENVIRONMENTS).distinctBy { it.lowercase() }
    }

    /** Database providers to offer for scaffolding, the most common first. */
    val PROVIDERS = listOf(
        "Microsoft.EntityFrameworkCore.SqlServer", "Npgsql.EntityFrameworkCore.PostgreSQL", "Microsoft.EntityFrameworkCore.Sqlite",
        "Pomelo.EntityFrameworkCore.MySql", "MySql.EntityFrameworkCore", "Oracle.EntityFrameworkCore",
    )

    /** The provider the project already references. */
    fun referencedProvider(msBuildProject: MsBuildProject): String? =
        PROVIDERS.firstOrNull { provider -> msBuildProject.packages.any { it.name.equals(provider, ignoreCase = true) } }

    /** Referenced directly, or coming through another project: then only the restore knows. */
    fun provider(project: Project, projectFile: VirtualFile): String? {
        val solutions = SolutionService.getInstance(project)
        return referencedProvider(solutions.msBuildProject(projectFile))
            ?: PROVIDERS.firstOrNull { provider -> solutions.assets(projectFile).targets.any { it.findPackage(provider) != null } }
    }

    /** `ConnectionStrings` of an `appsettings*.json`, as the `Name=ConnectionStrings:Default` references the tool resolves itself. */
    fun connectionStringReferences(json: String): List<String> {
        val root = try {
            // JSONC, as launchSettings.json: comments for the lenient reader, trailing commas removed
            JsonParser.parseReader(JsonReader(StringReader(json.replace(TRAILING_COMMA, "$1"))).apply { strictness = Strictness.LENIENT }) as? JsonObject
        } catch (_: Exception) {
            null
        }
        return (root?.get("ConnectionStrings") as? JsonObject)?.keySet().orEmpty().map { "Name=ConnectionStrings:$it" }
    }

    fun connectionStringReferences(startupProject: VirtualFile?): List<String> =
        startupProject?.parent?.children.orEmpty()
            .filter { it.name.equals("appsettings.json", ignoreCase = true) || APP_SETTINGS.matches(it.name) }.sortedBy { it.name.length }
            .flatMap { file -> runCatching { connectionStringReferences(VfsUtilCore.loadText(file)) }.getOrDefault(emptyList()) }.distinct()

    private val TRAILING_COMMA = Regex(""",(\s*[}\]])""")

    fun relativePath(project: Project, file: VirtualFile): String =
        SolutionService.getInstance(project).solutionFiles().firstOrNull()?.parent?.let { VfsUtilCore.getRelativePath(file, it, '/') } ?: file.path
}

/** The last choices of the EF dialogs. Kept in the workspace file: a startup project and an environment are personal. */
@Service(Service.Level.PROJECT)
@State(name = "DotNetEfSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class EfSettings : SimplePersistentStateComponent<EfSettings.Settings>(Settings()) {
    class Settings : BaseState() {
        var migrationsProject by string()

        /** Migrations project path -> startup project path / `DbContext`. */
        var startupProjects by map<String, String>()
        var dbContexts by map<String, String>()
        var environment by string()
        var noBuild by property(false)
        var arguments by string()
    }

    /** [keepDbContext]: the command had no context to choose (scaffolding creates one). */
    fun remember(context: EfContext, keepDbContext: Boolean = false) {
        state.migrationsProject = context.project
        state.startupProjects = state.startupProjects.toMutableMap().apply { put(context.project, context.startupProject ?: context.project) }
        if (!keepDbContext) state.dbContexts =state.dbContexts.toMutableMap().apply { if (context.dbContext.isNullOrBlank()) remove(context.project) else put(context.project, context.dbContext) }
        state.environment = context.environment
        state.noBuild = context.noBuild
        state.arguments = context.applicationArguments.joinToString(" ") { if (' ' in it) "\"$it\"" else it }
    }

    companion object {
        fun getInstance(project: Project): EfSettings = project.service()
    }
}
