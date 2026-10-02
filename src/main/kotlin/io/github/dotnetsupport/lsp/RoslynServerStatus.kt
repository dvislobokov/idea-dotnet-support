package io.github.dotnetsupport.lsp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.format.FormatterChoice
import io.github.dotnetsupport.lang.CSharpIdentifierAnnotator
import io.github.dotnetsupport.lang.CSharpSyntaxHighlighter
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

    /** Files colored by semantic tokens of the server taken from the cache of the plugin, while the solution is still loading. */
    val coloredFromCache: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()

    /** Directories of what the server has loaded (the solution, or the projects): the files it knows about are in them. */
    @Volatile
    var loadedRoots: List<String> = emptyList()

    companion object {
        fun isReady(project: Project): Boolean = !project.isDisposed && project.service<RoslynServerStatus>().isReady

        /**
         * The server answers about [file]: it is ready and the file is in what it has loaded. For the places where the plugin has its own
         * answer for the same question and would double the one of the server — Go to Class / Symbol. A file outside (a project that is in
         * no solution, a loose file) stays with the plugin.
         */
        fun covers(project: Project, file: VirtualFile): Boolean {
            if (!isReady(project)) return false
            val roots = project.service<RoslynServerStatus>().loadedRoots
            return roots.any { FileUtil.isAncestor(it, file.path, false) }
        }

        /** The server colors the identifiers of [file]: it is ready, or its tokens of this very text are shown from the cache. */
        fun colorsIdentifiers(project: Project, file: VirtualFile?): Boolean =
            isReady(project) || (file != null && !project.isDisposed && file in project.service<RoslynServerStatus>().coloredFromCache)
    }
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
     * formatter in milliseconds. CSharpier is another formatter and a decision of the team, "None" is nobody at all.
     */
    fun formatsByServer(resolved: FormatterChoice, serverReady: Boolean): Boolean = serverReady && resolved == FormatterChoice.DOTNET_FORMAT

    private val TYPES = setOf("class", "struct", "interface", "enum", "delegate", "recordClass", "recordStruct", "typeParameter", "type", "module")
    private val METHODS = setOf("method", "extensionMethod", "function")
    private val MEMBERS = setOf("property", "field", "event", "enumMember", "constant")

    /**
     * A semantic token of the server in the palette of the plugin (the one of Rider), so a file looks the same before and after the
     * server is ready. Null: what the lexer has colored already (comments, strings, punctuation) or what has no color (locals, parameters).
     */
    fun textAttributesKey(tokenType: String): TextAttributesKey? = when (tokenType) {
        in TYPES -> CSharpIdentifierAnnotator.TYPE
        in METHODS -> CSharpIdentifierAnnotator.METHOD
        in MEMBERS -> CSharpIdentifierAnnotator.MEMBER
        // contextual keywords (`var`, `record`, `await`...) are words for the lexer
        "keyword", "controlKeyword" -> CSharpSyntaxHighlighter.KEYWORD
        else -> null
    }
}
