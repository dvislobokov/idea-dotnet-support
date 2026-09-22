package io.github.dotnetsupport.lsp

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.messages.Topic

/** `--logLevel` of the server. */
enum class RoslynLogLevel { None, Critical, Error, Warning, Information, Debug, Trace }

/** `--sourceGeneratorExecutionPreference`: on every change, or on save / build only. */
enum class SourceGeneratorExecution { Automatic, Balanced }

/**
 * How `roslyn-language-server` is started and what it is told when it asks for its settings (`workspace/configuration`).
 * The facts behind it (options of the command line, the sections the server requests) come from probing the real server:
 * `tools/roslyn-lsp`. Machine-wide: a preference of the user, not of a repository.
 */
@Service(Service.Level.APP)
@State(name = "DotNetRoslynLanguageServer", storages = [Storage("dotnet-support.xml")])
class RoslynLanguageServerSettings : SimplePersistentStateComponent<RoslynLanguageServerSettings.Settings>(Settings()) {
    class Settings : BaseState() {
        /** Off: the server is never started, C# stays on the heuristics of the plugin. */
        var enabled by property(true)
        var logLevel by enum(RoslynLogLevel.Information)

        /** Empty: a folder next to the logs of the IDE. */
        var logDirectory by string("")
        var autoLoadProjects by property(true)

        /** 0: the limit the server recommends. */
        var autoLoadProjectsLimit by property(0)
        var sourceGeneratorExecution by enum(SourceGeneratorExecution.Automatic)
        var additionalArguments by string("")

        /** Section (without the language prefix) -> value, for the options that differ from their defaults in [RoslynOptions]. */
        var options by map<String, String>()

        /** `section = value` lines for what [RoslynOptions] does not list; they win over [options]. */
        var additionalOptions by string("")
    }

    /** The value of [option] as the page shows it and as the server gets it. */
    fun value(option: RoslynOption): String = state.options[option.section] ?: option.default

    fun setValue(option: RoslynOption, value: String) {
        if (value == value(option)) return
        // a new map: that is how BaseState notices the change
        state.options = state.options.toMutableMap().apply { if (value == option.default) remove(option.section) else put(option.section, value) }
    }

    /** The page was applied: [restart] when the command line of the server changed, otherwise its options only. */
    fun interface Listener {
        fun settingsChanged(restart: Boolean)
    }

    companion object {
        /** The bridge to the client of the server: it lives in a content module the rest of the plugin must not refer to. */
        @JvmField
        val CHANGED: Topic<Listener> = Topic.create("DotNetRoslynLanguageServerSettings", Listener::class.java)

        fun getInstance(): RoslynLanguageServerSettings = service()
    }
}

/** One setting the server asks for: `csharp|<section>` or, for the ones that do not depend on the language, `<section>`. */
class RoslynOption(val group: String, val section: String, val label: String, val default: String, val values: List<String>? = null, val comment: String? = null) {
    val isToggle: Boolean get() = values == null && (default == "true" || default == "false")
    val isText: Boolean get() = values == null && !isToggle
}

/**
 * The settings of the server worth a control, in the groups of the page. Sections and names are the ones the server requests
 * (server 5.12, see `tools/roslyn-lsp/README.md`); the defaults are the ones of the C# extension of VS Code. Every listed option is
 * always answered with an explicit value, so what the page shows is what the server works with.
 */
object RoslynOptions {
    private val SCOPES = listOf("openFiles", "fullSolution", "none")

    private fun toggle(group: String, section: String, label: String, default: Boolean, comment: String? = null) = RoslynOption(group, section, label, default.toString(), comment = comment)

    val ALL: List<RoslynOption> = listOf(
        RoslynOption("Analysis", "background_analysis.dotnet_compiler_diagnostics_scope", "Compiler diagnostics for:", "openFiles", SCOPES),
        RoslynOption("Analysis", "background_analysis.dotnet_analyzer_diagnostics_scope", "Analyzer diagnostics for:", "openFiles", SCOPES,
            "\"fullSolution\" analyzes every file of the solution in the background: accurate, and heavy on a large solution"),

        toggle("Projects", "projects.dotnet_enable_automatic_restore", "Restore NuGet packages when a project needs it", true),
        // off by default (2026-09-22, decision of the user): with it on, a .cs file created and opened while the solution is loaded is taken as a
        // program of its own and stays one until its tab is reopened - no errors in it, no types from the other files (seen live)
        toggle("Projects", "projects.dotnet_enable_file_based_programs", "Support file-based programs (a .cs file run with 'dotnet run file.cs')", false,
            comment = "On: a new .cs file of a project is analysed on its own until its tab is reopened"),
        toggle("Projects", "projects.dotnet_enable_file_based_programs_when_ambiguous", "Treat a loose .cs file as a file-based program when it is ambiguous", false),
        RoslynOption("Projects", "projects.dotnet_binary_log_path", "Folder for MSBuild binary logs of project loading:", "", comment = "Empty: no binary logs"),

        toggle("Completion", "completion.dotnet_show_completion_items_from_unimported_namespaces", "Show items from namespaces that are not imported", true),
        toggle("Completion", "completion.dotnet_show_name_completion_suggestions", "Suggest names for new members and variables", true),
        toggle("Completion", "completion.dotnet_provide_regex_completions", "Completion inside regular expressions", true),
        toggle("Completion", "completion.dotnet_trigger_completion_in_argument_lists", "Show completion in argument lists automatically", true),

        toggle("Navigation and Documentation", "navigation.dotnet_navigate_to_decompiled_sources", "Navigate to decompiled sources", true),
        toggle("Navigation and Documentation", "navigation.dotnet_navigate_to_source_link_and_embedded_sources", "Navigate to Source Link and embedded sources", true),
        toggle("Navigation and Documentation", "quick_info.dotnet_show_remarks_in_quick_info", "Show remarks in quick documentation", true),
        toggle("Navigation and Documentation", "symbol_search.dotnet_search_reference_assemblies", "Search symbols in reference assemblies", true),

        toggle("Code Lens", "code_lens.dotnet_enable_references_code_lens", "References", true),
        toggle("Code Lens", "code_lens.dotnet_enable_tests_code_lens", "Run and debug tests", true),

        toggle("Inlay Hints", "inlay_hints.dotnet_enable_inlay_hints_for_parameters", "Parameter names", false),
        toggle("Inlay Hints", "inlay_hints.dotnet_enable_inlay_hints_for_literal_parameters", "Parameter names: for literals", false),
        toggle("Inlay Hints", "inlay_hints.dotnet_enable_inlay_hints_for_indexer_parameters", "Parameter names: for indexers", false),
        toggle("Inlay Hints", "inlay_hints.dotnet_enable_inlay_hints_for_object_creation_parameters", "Parameter names: for 'new' expressions", false),
        toggle("Inlay Hints", "inlay_hints.dotnet_enable_inlay_hints_for_other_parameters", "Parameter names: for everything else", false),
        toggle("Inlay Hints", "inlay_hints.dotnet_suppress_inlay_hints_for_parameters_that_differ_only_by_suffix", "Parameter names: not when the names differ only by suffix", true),
        toggle("Inlay Hints", "inlay_hints.dotnet_suppress_inlay_hints_for_parameters_that_match_method_intent", "Parameter names: not when the name matches the intent of the method", true),
        toggle("Inlay Hints", "inlay_hints.dotnet_suppress_inlay_hints_for_parameters_that_match_argument_name", "Parameter names: not when the argument has the same name", true),
        toggle("Inlay Hints", "inlay_hints.csharp_enable_inlay_hints_for_types", "Types", false),
        toggle("Inlay Hints", "inlay_hints.csharp_enable_inlay_hints_for_implicit_variable_types", "Types: of 'var' variables", false),
        toggle("Inlay Hints", "inlay_hints.csharp_enable_inlay_hints_for_lambda_parameter_types", "Types: of lambda parameters", false),
        toggle("Inlay Hints", "inlay_hints.csharp_enable_inlay_hints_for_implicit_object_creation", "Types: of 'new()' expressions", false),
        toggle("Inlay Hints", "inlay_hints.csharp_enable_inlay_hints_for_collection_expressions", "Types: of collection expressions", false),

        toggle("Editing", "auto_insert.dotnet_enable_auto_insert", "Insert documentation comments and closing braces automatically", true),
        toggle("Editing", "formatting.dotnet_organize_imports_on_format", "Organize 'using' directives when formatting", false),
        toggle("Editing", "highlighting.dotnet_highlight_related_regex_components", "Highlight related parts of regular expressions", true),
        toggle("Editing", "highlighting.dotnet_highlight_related_json_components", "Highlight related parts of JSON strings", true),

        RoslynOption("Code Generation", "type_members.dotnet_member_insertion_location", "Insert generated members:", "with_other_members_of_the_same_kind",
            listOf("with_other_members_of_the_same_kind", "at_the_end")),
        RoslynOption("Code Generation", "type_members.dotnet_property_generation_behavior", "Generated properties:", "prefer_throwing_properties",
            listOf("prefer_throwing_properties", "prefer_auto_properties")),
    )

    val GROUPS: List<String> = ALL.map { it.group }.distinct()
}

/** The indents the server formats with; they come from the code style of the IDE, not from the page of the server. */
class RoslynCodeStyle(val tabWidth: Int, val indentSize: Int, val useTabs: Boolean, val endOfLine: String?, val insertFinalNewline: Boolean?)

/** What the server is told to load: `solution/open` or, when there is no solution, `project/open`. */
sealed interface RoslynWorkspaceTarget {
    data class Solution(val path: String) : RoslynWorkspaceTarget
    data class Projects(val paths: List<String>) : RoslynWorkspaceTarget

    /** Several solutions and none of them chosen yet: the server holds one at a time, and which one is for the user to say. */
    data class Choice(val solutions: List<String>) : RoslynWorkspaceTarget
}

object RoslynLanguageServer {
    /**
     * [solutions] (anywhere under the opened folder) win over loose [projects]: a solution is always opened by the plugin. The only one
     * is opened as is; of several the one [chosen] before, while it is still there.
     */
    fun workspaceTarget(solutions: List<String>, chosen: String?, projects: List<String>): RoslynWorkspaceTarget? = when {
        solutions.size == 1 -> RoslynWorkspaceTarget.Solution(solutions.single())
        chosen != null && chosen in solutions -> RoslynWorkspaceTarget.Solution(chosen)
        solutions.isNotEmpty() -> RoslynWorkspaceTarget.Choice(solutions.sortedBy { it.lowercase() })
        projects.isNotEmpty() -> RoslynWorkspaceTarget.Projects(projects.sortedBy { it.lowercase() })
        else -> null
    }

    /** `file:///c%3A/w` -> `file:///c:/w`: the drive colon as the server expects it in the URI of a folder. */
    fun plainDriveUri(uri: String): String = Regex("^(file:///[A-Za-z])%3[Aa]").replace(uri) { it.groupValues[1] + ":" }

    /** Everything of the settings that ends up on the command line: a change here needs a restart of the server. */
    fun commandLineKey(settings: RoslynLanguageServerSettings.Settings): List<String> = listOf(settings.enabled.toString()) + arguments(settings, "", null)

    /**
     * The arguments after the executable. `--stdio` is how the client talks to the server; with [clientProcessId] the server exits
     * when the IDE is gone, whatever happens to the pipes. With [solutionFound] the plugin names the solution (`solution/open`), and
     * `--autoLoadProjects` on top of that would load everything twice, or another solution than the chosen one.
     */
    fun arguments(settings: RoslynLanguageServerSettings.Settings, defaultLogDirectory: String, clientProcessId: Long?, solutionFound: Boolean = false): List<String> = buildList {
        add("--stdio")
        add("--logLevel"); add(settings.logLevel.name)
        add("--extensionLogDirectory"); add(settings.logDirectory?.takeIf { it.isNotBlank() } ?: defaultLogDirectory)
        if (settings.autoLoadProjects && !solutionFound) {
            add("--autoLoadProjects")
            if (settings.autoLoadProjectsLimit > 0) add(settings.autoLoadProjectsLimit.toString())
        }
        if (settings.sourceGeneratorExecution != SourceGeneratorExecution.Automatic) {
            add("--sourceGeneratorExecutionPreference"); add(settings.sourceGeneratorExecution.name)
        }
        clientProcessId?.let { add("--clientProcessId"); add(it.toString()) }
        addAll(ParametersListUtil.parse(settings.additionalArguments.orEmpty()))
    }

    /** `csharp|completion.x`, `visual_basic|completion.x` and `completion.x` are one setting here. */
    fun sectionKey(section: String): String = section.substringAfter('|')

    /** `section = value` lines; `#` starts a comment, a language prefix is dropped. */
    fun parseAdditionalOptions(text: String?): Map<String, String> = text.orEmpty().lineSequence()
        .map { it.substringBefore('#').trim() }.filter { '=' in it }
        .associate { sectionKey(it.substringBefore('=').trim()) to it.substringAfter('=').trim() }
        .filterKeys { it.isNotEmpty() }

    /**
     * The answer to `workspace/configuration`: a value per requested section, in order; null is "use your default", which is the
     * answer for everything that is neither listed in [RoslynOptions], nor set by hand, nor a part of the code style.
     */
    fun configuration(sections: List<String?>, settings: RoslynLanguageServerSettings, codeStyle: RoslynCodeStyle?): List<Any?> {
        val additional = parseAdditionalOptions(settings.state.additionalOptions)
        val listed = RoslynOptions.ALL.associateBy { it.section }
        return sections.map { section ->
            val key = sectionKey(section.orEmpty())
            additional[key]?.let { return@map typed(it) }
            listed[key]?.let { option -> return@map if (option.isText) settings.value(option).ifBlank { null } else typed(settings.value(option)) }
            codeStyle?.let { codeStyleValue(key, it) }
        }
    }

    private fun codeStyleValue(key: String, style: RoslynCodeStyle): Any? = when (key) {
        "code_style.formatting.indentation_and_spacing.tab_width" -> style.tabWidth
        "code_style.formatting.indentation_and_spacing.indent_size" -> style.indentSize
        "code_style.formatting.indentation_and_spacing.indent_style" -> if (style.useTabs) "tab" else "space"
        "code_style.formatting.new_line.end_of_line" -> style.endOfLine
        "code_style.formatting.new_line.insert_final_newline" -> style.insertFinalNewline
        else -> null
    }

    /** JSON has booleans and numbers, a text field has not. */
    private fun typed(value: String): Any? = when {
        value == "true" -> true
        value == "false" -> false
        value == "null" || value.isEmpty() -> null
        else -> value.toIntOrNull() ?: value
    }
}
