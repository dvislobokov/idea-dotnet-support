package io.github.dotnetsupport.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.Topic
import io.github.dotnetsupport.DotNetBundle

/**
 * The options of the C# editor of the plugin (Settings | .NET | C# Editor): what the analysis, completion, navigation, Code Vision, inlay
 * hints and code generation of the plugin's own C# read ([CSharpOptions]). Their sections are the names `roslyn-language-server` gave them
 * (the plugin talked to it until 0.1.262), and they are stored where the page of the server kept them (the state `DotNetRoslynLanguageServer`
 * of dotnet-support.xml), so what a user set before stays. Machine-wide: a preference of the user, not of a repository.
 */
@Service(Service.Level.APP)
@State(name = "DotNetRoslynLanguageServer", storages = [Storage("dotnet-support.xml")])
class CSharpEditorSettings : SimplePersistentStateComponent<CSharpEditorSettings.Settings>(Settings()) {
    class Settings : BaseState() {
        /** Section -> value, for the options that differ from their defaults in [CSharpOptions]. */
        var options by map<String, String>()
    }

    /** The value of [option] as the page shows it and the features read it. */
    fun value(option: CSharpOption): String = state.options[option.section] ?: option.default

    fun setValue(option: CSharpOption, value: String) {
        if (value == value(option)) return
        // a new map: that is how BaseState notices the change
        state.options = state.options.toMutableMap().apply { if (value == option.default) remove(option.section) else put(option.section, value) }
    }

    /** The page was applied: the features that read the options redraw (inlay hints, Code Vision, colors, errors). */
    fun interface Listener {
        fun settingsChanged()
    }

    companion object {
        @JvmField
        val CHANGED: Topic<Listener> = Topic.create("DotNetCSharpEditorSettings", Listener::class.java)

        fun getInstance(): CSharpEditorSettings = service()
    }
}

/** One option of the page: its [group], its [section] (the key it is stored and read by), its English [label] and [default]. */
class CSharpOption(val group: String, val section: String, val label: String, val default: String, val values: List<String>? = null, val comment: String? = null) {
    val isToggle: Boolean get() = values == null && (default == "true" || default == "false")

    /** [label] and [comment] in the language of the settings page; the English ones are here, the Russian ones in the bundle. */
    val text: String get() = DotNetBundle.messageOr("csharp.option.$section", label)
    val note: String? get() = comment?.let { DotNetBundle.messageOr("csharp.option.$section.comment", it) }
}

/**
 * Every option of the C# editor, in the groups of the page; each one is read by a feature of the plugin by its section ([isOn] / [value]),
 * `CSharpOptionsTest` keeps that honest. The defaults are the ones of the C# extension of VS Code, the ones Rider has where they differ.
 */
object CSharpOptions {
    private val SCOPES = listOf("openFiles", "fullSolution", "none")

    private fun toggle(group: String, section: String, label: String, default: Boolean, comment: String? = null) =
        CSharpOption(group, section, label, default.toString(), comment = comment)

    val ALL: List<CSharpOption> = listOf(
        // the whole solution by default (decision of the user, 2026-10-16): the plugin's own pass lists the errors of every file in Problems
        // → Project Errors, the helper analyzes whole projects (AnalysisScopes)
        CSharpOption("Analysis", "background_analysis.dotnet_compiler_diagnostics_scope", "Compiler diagnostics for:", "fullSolution", SCOPES),
        CSharpOption("Analysis", "background_analysis.dotnet_analyzer_diagnostics_scope", "Analyzer diagnostics for:", "fullSolution", SCOPES,
            "\"fullSolution\" analyzes every file of the solution in the background: accurate, and heavy on a large solution"),

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

        // on by default as in Rider (decision of the user 2026-09-22): names of literal / indexer / new arguments, types of var and lambda
        // parameters; "everything else", new() and collection expressions stay off: they make a line noisy
        toggle("Inlay Hints", "inlay_hints.dotnet_enable_inlay_hints_for_parameters", "Parameter names", true),
        toggle("Inlay Hints", "inlay_hints.dotnet_enable_inlay_hints_for_literal_parameters", "Parameter names: for literals", true),
        toggle("Inlay Hints", "inlay_hints.dotnet_enable_inlay_hints_for_indexer_parameters", "Parameter names: for indexers", true),
        toggle("Inlay Hints", "inlay_hints.dotnet_enable_inlay_hints_for_object_creation_parameters", "Parameter names: for 'new' expressions", true),
        toggle("Inlay Hints", "inlay_hints.dotnet_enable_inlay_hints_for_other_parameters", "Parameter names: for everything else", false),
        toggle("Inlay Hints", "inlay_hints.dotnet_suppress_inlay_hints_for_parameters_that_differ_only_by_suffix", "Parameter names: not when the names differ only by suffix", true),
        toggle("Inlay Hints", "inlay_hints.dotnet_suppress_inlay_hints_for_parameters_that_match_method_intent", "Parameter names: not when the name matches the intent of the method", true),
        toggle("Inlay Hints", "inlay_hints.dotnet_suppress_inlay_hints_for_parameters_that_match_argument_name", "Parameter names: not when the argument has the same name", true),
        toggle("Inlay Hints", "inlay_hints.csharp_enable_inlay_hints_for_types", "Types", true),
        toggle("Inlay Hints", "inlay_hints.csharp_enable_inlay_hints_for_implicit_variable_types", "Types: of 'var' variables", true),
        toggle("Inlay Hints", "inlay_hints.csharp_enable_inlay_hints_for_lambda_parameter_types", "Types: of lambda parameters", true),
        toggle("Inlay Hints", "inlay_hints.csharp_enable_inlay_hints_for_implicit_object_creation", "Types: of 'new()' expressions", false),
        toggle("Inlay Hints", "inlay_hints.csharp_enable_inlay_hints_for_collection_expressions", "Types: of collection expressions", false),

        toggle("Editing", "auto_insert.dotnet_enable_auto_insert", "Insert documentation comments and closing braces automatically", true),
        toggle("Editing", "formatting.dotnet_organize_imports_on_format", "Organize 'using' directives when formatting", false),
        toggle("Editing", "highlighting.dotnet_highlight_related_regex_components", "Highlight related parts of regular expressions", true),
        toggle("Editing", "highlighting.dotnet_highlight_related_json_components", "Highlight related parts of JSON strings", true),

        CSharpOption("Code Generation", "type_members.dotnet_member_insertion_location", "Insert generated members:", "with_other_members_of_the_same_kind",
            listOf("with_other_members_of_the_same_kind", "at_the_end")),
        CSharpOption("Code Generation", "type_members.dotnet_property_generation_behavior", "Generated properties:", "prefer_throwing_properties",
            listOf("prefer_throwing_properties", "prefer_auto_properties")),
    )

    val GROUPS: List<String> = ALL.map { it.group }.distinct()

    fun option(section: String): CSharpOption = ALL.firstOrNull { it.section == section } ?: error("not an option of the page: $section")

    /** The value of the option [section] as the page shows it. */
    fun value(section: String): String = CSharpEditorSettings.getInstance().value(option(section))

    fun isOn(section: String): Boolean = value(section) == "true"

    fun key(group: String): String = "csharp.group." + group.lowercase().replace(' ', '_')

    /** The name of a group in the language of the settings page. */
    fun title(group: String): String = DotNetBundle.messageOr(key(group), group)
}

/** Settings | .NET | C# Editor: the options of [CSharpOptions], a group each, generated from the list. */
class CSharpEditorConfigurable : BoundConfigurable(DotNetBundle.message("page.csharpEditor")) {
    private val settings get() = CSharpEditorSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        for (group in CSharpOptions.GROUPS) group(CSharpOptions.title(group)) { CSharpOptions.ALL.filter { it.group == group }.forEach { option(it) } }
    }

    override fun apply() {
        super.apply()
        ApplicationManager.getApplication().messageBus.syncPublisher(CSharpEditorSettings.CHANGED).settingsChanged()
    }

    private fun Panel.option(option: CSharpOption) {
        when {
            option.isToggle -> row {
                checkBox(option.text).bindSelected({ settings.value(option) == "true" }, { settings.setValue(option, it.toString()) }).apply { option.note?.let { comment(it) } }
            }
            option.values != null -> row(option.text) {
                comboBox(option.values).bindItem({ settings.value(option) }, { settings.setValue(option, it ?: option.default) }).apply { option.note?.let { comment(it) } }
            }
            else -> row(option.text) {
                textField().align(AlignX.FILL).bindText({ settings.value(option) }, { settings.setValue(option, it.trim()) }).apply { option.note?.let { comment(it) } }
            }
        }
    }
}
