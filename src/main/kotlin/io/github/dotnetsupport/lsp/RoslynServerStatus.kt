package io.github.dotnetsupport.lsp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.containers.CollectionFactory
import io.github.dotnetsupport.format.FormatterChoice
import io.github.dotnetsupport.lang.CSharpColors
import io.github.dotnetsupport.lang.CSharpSyntaxHighlighter
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.solution.Solution
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether the C# language server of the project answers (started, and its solution is loaded). Roslyn is the authority: where the
 * plugin has a heuristic of its own for what the server does (colors of identifiers, folding, problems of the last build in the
 * editor, whitespace formatting), the heuristic works only while this is false. Written by the content module with the client of the
 * server, which the rest of the plugin must not refer to; without that module it just stays false.
 */
@Service(Service.Level.PROJECT)
class RoslynServerStatus {
    @Volatile
    var isReady: Boolean = false

    /**
     * Files whose identifiers the semantic tokens of the server color on screen: tokens from the cache of the plugin while the solution
     * loads, or an answer of the loaded server. Until a file is here the heuristics color it, also after the server is ready: stepping
     * aside before the tokens are shown left the file without colors for a moment.
     */
    val coloredByServer: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()

    /** The absolute path of the solution the server has loaded (`solution/open`); null for loose projects or nothing. See [loaded]. */
    @Volatile
    var loadedSolution: String? = null
        private set

    /** The project files the server has loaded without a solution (`project/open`, or found by itself); empty with a solution. */
    @Volatile
    var loadedProjects: List<String> = emptyList()
        private set

    /** [covers] per file for one list of loaded projects: dropped when the loaded solution changes or its file is read anew. */
    private class Coverage(val source: Any?, val projects: Set<String>?, val files: ConcurrentHashMap<VirtualFile, Boolean> = ConcurrentHashMap())

    @Volatile
    private var coverage: Coverage? = null

    @Volatile
    private var solutionFile: VirtualFile? = null

    /**
     * Written by the content module when it tells the server what to open, and with nulls when the server stops. [file] is the solution
     * when the caller has it (tests: the light project is not on the local file system); otherwise it is found by [solution].
     */
    fun loaded(solution: String?, projects: List<String> = emptyList(), file: VirtualFile? = null) {
        loadedSolution = solution
        loadedProjects = projects
        solutionFile = file
        coverage = null
    }

    /** A project or a solution file was read anew or came and went (Reload, a new `.csproj`): the owners of files are found again. */
    fun forgetCoverage() {
        coverage = null
    }

    /** Whether the server has loaded the project of [file], by the solution (or the projects) it was told to open. Cached per file. */
    fun hasLoaded(project: Project, file: VirtualFile): Boolean {
        val solution = loadedSolution
        // the parsed solution is cached by SolutionService until its file changes or Reload Solution: a new object means a new list
        val solutionFile = if (solution == null) null else this.solutionFile?.takeIf { it.isValid } ?: LocalFileSystem.getInstance().findFileByPath(solution)?.also { this.solutionFile = it }
        val source: Any? = if (solution == null) loadedProjects else solutionFile?.let { SolutionService.getInstance(project).solution(it) }
        val current = coverage?.takeIf { it.source === source } ?: Coverage(source, projectsOf(solutionFile, source)).also { coverage = it }
        return current.files.getOrPut(file) { RoslynCoverage.covers(DotNetProjects.findOwningProject(file)?.path, current.projects) }
    }

    private fun projectsOf(solutionFile: VirtualFile?, source: Any?): Set<String>? = when (source) {
        is Solution -> CollectionFactory.createFilePathSet(source.allProjects.mapNotNull { p -> solutionFile?.let { p.resolveFile(it)?.path } })
        is List<*> -> source.takeIf { it.isNotEmpty() }?.let { CollectionFactory.createFilePathSet(it.filterIsInstance<String>()) }
        else -> null
    }

    companion object {
        fun isReady(project: Project): Boolean = !project.isDisposed && project.service<RoslynServerStatus>().isReady

        /**
         * The server answers about [file]: it is ready and the project of the file is in the solution (or the projects) it has loaded. Not
         * the folder of the solution: a solution in the root of the folder has the projects of the other solutions under it, and the server
         * knows nothing of them (`ShopApi/` next to `DebugPlayground.sln`). A file of no project is not the server's either.
         */
        fun covers(project: Project, file: VirtualFile?): Boolean =
            file != null && isReady(project) && project.service<RoslynServerStatus>().hasLoaded(project, file)

        /**
         * The server is ready and does not know [file] (a project of another solution, a loose file on disk): what the plugin has of its own
         * answers there as with the server off, and the server is not asked. A file in memory (a copy of completion, a fragment) is never
         * outside: its callers decide by the original file or by the project, as before. Nor is anything while the server has loaded neither
         * a solution nor projects (loose files): its miscellaneous files are all it has, as before.
         */
        fun outside(project: Project, file: VirtualFile?): Boolean {
            if (file == null || file is LightVirtualFile || !isReady(project)) return false
            val status = project.service<RoslynServerStatus>()
            return (status.loadedSolution != null || status.loadedProjects.isNotEmpty()) && !status.hasLoaded(project, file)
        }

        /** [isReady] for a feature of one file: false also while the server is ready, when it does not know the file ([outside]). */
        fun isReady(project: Project, file: VirtualFile?): Boolean = isReady(project) && !outside(project, file)

        /** The semantic tokens of the server color the identifiers of [file] (see [coloredByServer]); readiness alone is not enough. */
        fun colorsIdentifiers(project: Project, file: VirtualFile?): Boolean =
            file != null && !project.isDisposed && file in project.service<RoslynServerStatus>().coloredByServer
    }
}

/** Which files the loaded server knows: a pure rule, so it is tested without a server. */
object RoslynCoverage {
    /** [projectFile] owns the file (null: no project); [loaded] are the project files of what the server has loaded (null: nothing). */
    fun covers(projectFile: String?, loaded: Set<String>?): Boolean = projectFile != null && loaded != null && projectFile in loaded

    /** [covers] over plain paths, compared as the file system does (case on Windows). */
    fun covers(projectFile: String?, loaded: Collection<String>?): Boolean =
        covers(projectFile, loaded?.let { CollectionFactory.createFilePathSet(it) })
}

/** What the server of a project is busy with, from its start to the moment it answers. */
enum class RoslynPhase { STARTING, CHOOSING_SOLUTION, LOADING, READY }

object RoslynPolicy {
    /** After the name of the server in its widget of the status bar: "Roslyn: loading Shop.sln...". [target] is a file name or null. */
    fun statusText(phase: RoslynPhase, target: String?): String = when (phase) {
        RoslynPhase.STARTING -> ": starting..."
        RoslynPhase.CHOOSING_SOLUTION -> ": select a solution to load"
        RoslynPhase.LOADING -> if (target != null) ": loading $target..." else ": loading projects..."
        RoslynPhase.READY -> if (target != null) ": $target" else ""
    }

    /** `dotnet --list-runtimes` has a `Microsoft.NETCore.App <major>.x`: the server is a .NET [major] program and does not start without it. */
    fun hasRuntime(listRuntimes: String, major: Int): Boolean =
        listRuntimes.lineSequence().any { Regex("""^Microsoft\.NETCore\.App\s+$major\.""").containsMatchIn(it.trim()) }

    /** What the host of .NET printed before exit code 150: the framework the server wants, where the host looked, what it found there. */
    class MissingFramework(val required: String, val location: String?, val found: List<String>) {
        fun describe(): String = "The server needs $required; the dotnet host" + (location?.let { " at $it" } ?: "") +
            (if (found.isEmpty()) " has no Microsoft.NETCore.App at all." else " has only: ${found.joinToString(", ")}.")
    }

    /**
     * The standard error of a server process that did not start because its runtime is missing (`You must install or update .NET`),
     * read for the message of the plugin: which runtime, and where the host looked (`DOTNET_ROOT`, the registered location, the default).
     */
    fun missingFramework(stderr: String): MissingFramework? {
        if (!stderr.contains("You must install or update .NET")) return null
        val required = Regex("""Framework: '([^']+)', version '([^']+)'""").find(stderr)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" } ?: return null
        val location = Regex("""\.NET location:\s*(.+)""").find(stderr)?.groupValues?.get(1)?.trim()
        val found = Regex("""^\s+(\S+ at \[[^\]]+])\s*$""", RegexOption.MULTILINE).findAll(stderr).map { it.groupValues[1] }.toList()
        return MissingFramework(required, location, found)
    }

    /**
     * `DOTNET_ROOT` for the process of the server: the one of the environment when it is set, otherwise the folder of the `dotnet` the
     * plugin runs its commands with. The host of a global tool looks for the runtime in `DOTNET_ROOT` first, then in the installation
     * registered on the machine, which on a machine with a corporate SDK in a folder of its own is the wrong one (seen: the tool built for
     * .NET 10 started with `/usr/share/dotnet-sdk-8.8.403` while `dotnet` 10 was on PATH).
     */
    fun dotnetRoot(environment: String?, dotnetExecutable: String?): String? =
        environment?.takeIf { it.isNotBlank() } ?: dotnetExecutable?.let { java.io.File(it).absoluteFile.parent }

    /**
     * `dotnet format whitespace` is the formatter of Roslyn started as a process, about a second per file; the server runs the same
     * formatter in milliseconds. "Built-in" is the plugin's own formatter, the server stands in only for a file without the native tree.
     * CSharpier is another formatter and a decision of the team, "None" is nobody at all.
     */
    fun formatsByServer(resolved: FormatterChoice, serverReady: Boolean, nativeTree: Boolean = true): Boolean = serverReady &&
        (resolved == FormatterChoice.DOTNET_FORMAT || resolved == FormatterChoice.BUILT_IN && !nativeTree)

    /**
     * The text of a diagnostic of the server with its code in front (`CS0230: Type and identifier...`), as the native ones and Rider show
     * them, so the errors of the two sources read alike side by side. A message that carries the code already is left as it is.
     */
    fun diagnosticText(code: String?, message: String): String =
        if (code.isNullOrBlank() || message.startsWith("$code:")) message else "$code: $message"

    /**
     * A semantic token of the server in the palette of the plugin ([CSharpColors], the one of Rider), so a file looks the same before and
     * after the server is ready and with the native colors. The server tells `static` and a reassigned local (`ReassignedVariable`) by
     * modifiers, not a declaration from a use: a method is colored as a call. Null: what the lexer has colored already (comments, strings,
     * punctuation).
     */
    fun textAttributesKey(tokenType: String, modifiers: List<String> = emptyList()): TextAttributesKey? {
        val static = "static" in modifiers
        return when (tokenType) {
            "class" -> if (static) CSharpColors.STATIC_CLASS else CSharpColors.CLASS
            "recordClass" -> CSharpColors.RECORD
            "struct" -> CSharpColors.STRUCT
            "recordStruct" -> CSharpColors.RECORD_STRUCT
            "interface" -> CSharpColors.INTERFACE
            "enum" -> CSharpColors.ENUM
            "delegate" -> CSharpColors.DELEGATE
            "typeParameter" -> CSharpColors.TYPE_PARAMETER
            "type", "module" -> CSharpColors.TYPE
            "namespace" -> CSharpColors.NAMESPACE
            "method", "function" -> if (static) CSharpColors.STATIC_METHOD_CALL else CSharpColors.METHOD_CALL
            "extensionMethod" -> CSharpColors.EXTENSION_METHOD_CALL
            "property" -> if (static) CSharpColors.STATIC_PROPERTY else CSharpColors.PROPERTY
            "field" -> if (static) CSharpColors.STATIC_FIELD else CSharpColors.FIELD
            "constant", "enumMember" -> CSharpColors.CONSTANT
            "event" -> CSharpColors.EVENT
            "variable", "local" -> if ("ReassignedVariable" in modifiers) CSharpColors.MUTABLE_LOCAL_VARIABLE else CSharpColors.LOCAL_VARIABLE
            "parameter" -> CSharpColors.PARAMETER
            "label" -> CSharpColors.LABEL
            // contextual keywords (`var`, `record`, `await`...) are words for the lexer
            "keyword", "controlKeyword" -> CSharpSyntaxHighlighter.KEYWORD
            else -> null
        }
    }
}
