package io.github.dotnetsupport.newproject

import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.PluginLog

/**
 * A parameter of a `dotnet new` template (`--test-runner`, `--use-program-main`, `--auth`...), as `dotnet new <template> --help` describes it.
 * [name] is the long option; [kind] decides the control; [default] is what the template does when the option is not given.
 */
class TemplateOption(
    val name: String,
    val aliases: List<String>,
    val description: String,
    val kind: Kind,
    val choices: List<Choice>,
    val default: String?,
    val multiple: Boolean,
    /** `Enabled if: UseMSTestSdk && (TestRunner == Microsoft.Testing.Platform)`: shown as a hint, the engine ignores the option when it does not apply. */
    val enabledIf: String?,
) {
    enum class Kind { CHOICE, BOOL, TEXT }

    class Choice(val value: String, val description: String)

    /** The label of the row: `Test runner` from `--test-runner`. */
    val label: String get() = name.removePrefix("--").replace('-', ' ').replaceFirstChar { it.uppercase() }

    val isBoolDefaultTrue: Boolean get() = default.equals("true", ignoreCase = true)
}

/**
 * The template options section of `dotnet new <template> --help`, which is what the New Project dialog shows below the framework:
 * ```
 * Template options:
 *   --test-runner <Microsoft.Testing.Platform|MSTest|VSTest>  Select the runner/platform.
 *                                                             Type: choice
 *                                                               Microsoft.Testing.Platform  Use Microsoft.Testing.Platform...
 *                                                             Default: VSTest
 * ```
 * Checked against SDK 10.0.401 (`src/test/resources/dotnetNew`). `--framework` has a row of its own in the dialog; `--no-restore` is not a
 * property of the project and is left out.
 */
object TemplateOptions {
    private val OPTION_LINE = Regex("""^ {2}(-\S+(?:,\s*-\S+)*)(?:\s+<([^>]*)>)?(?:\s{2,}(.*))?$""")
    private val KEY_LINE = Regex("""^(Type|Default|Enabled if|Multiple values are allowed):\s*(.*)$""")
    private val CHOICE_LINE = Regex("""^(\S+)(?:\s{2,}(.*))?$""")
    private val HIDDEN = setOf("--framework", "--no-restore")

    /** Blocking: `dotnet new <shortName> --help`, in the language when the template has several. Empty on any failure. */
    fun load(shortName: String, language: String?): List<TemplateOption> = try {
        val arguments = listOfNotNull("new", shortName, "--help", language?.let { "--language" }, language)
        val output = DotNetCli.execute(DotNetCli.commandLine(null, *arguments.toTypedArray()).withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en"), 60_000)
        parse(output.stdout)
    } catch (e: Exception) {
        PluginLog.warn(DotNetTemplates.LOG_CATEGORY, "`dotnet new $shortName --help` could not run, no options are shown", e)
        emptyList()
    }

    fun parse(help: String): List<TemplateOption> {
        val lines = help.lines()
        val start = lines.indexOfFirst { it.trim().equals("Template options:", ignoreCase = true) }
        if (start < 0) return emptyList()
        val result = ArrayList<TemplateOption>()
        var current: Builder? = null
        var inChoices = false
        for (line in lines.drop(start + 1)) {
            if (line.isBlank()) { if (current != null) break else continue } // the section ends with a blank line
            if (!line.startsWith(" ")) break
            val option = OPTION_LINE.matchEntire(line.trimEnd())
            if (option != null && line.startsWith("  -")) {
                current?.build()?.let(result::add)
                val names = option.groupValues[1].split(',').map { it.trim() }
                current = Builder(names.last { it.startsWith("--") }.ifEmpty { names.last() }, names, option.groupValues[3].trim())
                inChoices = false
                continue
            }
            val builder = current ?: continue
            val text = line.trim()
            val key = KEY_LINE.matchEntire(text)
            when {
                key != null -> {
                    inChoices = false
                    when (key.groupValues[1]) {
                        "Type" -> { builder.type = key.groupValues[2].trim().lowercase(); inChoices = builder.type == "choice" }
                        "Default" -> builder.default = key.groupValues[2].trim()
                        "Enabled if" -> builder.enabledIf = key.groupValues[2].trim()
                        "Multiple values are allowed" -> builder.multiple = key.groupValues[2].trim().equals("true", ignoreCase = true)
                    }
                }
                inChoices -> CHOICE_LINE.matchEntire(text)?.let { builder.choices += TemplateOption.Choice(it.groupValues[1], it.groupValues[2].trim()) }
                builder.type == null -> builder.description = (builder.description + " " + text).trim() // a wrapped description
            }
        }
        current?.build()?.let(result::add)
        return result.filter { it.name !in HIDDEN }
    }

    /** The `--framework` choices of the help, for the framework row: what the template really supports. */
    fun frameworks(help: String): List<String> {
        val lines = help.lines()
        val line = lines.firstOrNull { it.trimStart().startsWith("-f, --framework <") || it.trimStart().startsWith("--framework <") } ?: return emptyList()
        return line.substringAfter('<').substringBefore('>').split('|').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The `Description:` line at the top of the help: what the New Solution dialog writes under the list of templates. */
    fun description(help: String): String = header(help, "Description")

    /** The `Author:` line at the top of the help. */
    fun author(help: String): String = header(help, "Author")

    private fun header(help: String, key: String): String =
        help.lineSequence().takeWhile { !it.trim().startsWith("Usage:") }
            .firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim().orEmpty()

    /** `--test-runner MSTest --sdk` for what differs from the defaults; a bool set to false when its default is true is passed as `--x false`. */
    fun arguments(options: List<TemplateOption>, values: Map<String, String>): List<String> = buildList {
        for (option in options) {
            val value = values[option.name]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (value.equals(option.default, ignoreCase = true)) continue
            when (option.kind) {
                TemplateOption.Kind.BOOL -> if (value.equals("true", ignoreCase = true)) add(option.name) else if (option.isBoolDefaultTrue) { add(option.name); add("false") }
                else -> { add(option.name); add(value) }
            }
        }
    }

    private class Builder(val name: String, val aliases: List<String>, var description: String) {
        var type: String? = null
        var default: String? = null
        var enabledIf: String? = null
        var multiple = false
        val choices = ArrayList<TemplateOption.Choice>()

        fun build(): TemplateOption {
            val kind = when (type) {
                "choice" -> TemplateOption.Kind.CHOICE
                "bool" -> TemplateOption.Kind.BOOL
                else -> TemplateOption.Kind.TEXT
            }
            return TemplateOption(name, aliases.filter { it != name }, description, kind, choices, default, multiple, enabledIf)
        }
    }
}

/** What `dotnet new <template> --help` says about a template: its options, the frameworks it supports, its description. */
class TemplateHelp(val options: List<TemplateOption>, val frameworks: List<String>, val description: String, val author: String = "") {
    companion object {
        val EMPTY = TemplateHelp(emptyList(), emptyList(), "")

        fun parse(help: String) =
            TemplateHelp(TemplateOptions.parse(help), TemplateOptions.frameworks(help), TemplateOptions.description(help), TemplateOptions.author(help))
    }
}

/**
 * `dotnet new <template> --help` once per template and language for the session of the IDE: the New Project wizard, the Add New Project
 * dialog and the New Solution dialog ask the same questions, a second each.
 */
object TemplateHelpCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, TemplateHelp>()

    private fun key(shortName: String, language: String?) = "$shortName|${language.orEmpty()}"

    /** Null until [load] has run for the pair. */
    fun cached(shortName: String, language: String?): TemplateHelp? = cache[key(shortName, language)]

    /** Blocking; [TemplateHelp.EMPTY] (not cached) when the CLI fails. */
    fun load(shortName: String, language: String?): TemplateHelp {
        cached(shortName, language)?.let { return it }
        val help = try {
            val arguments = listOfNotNull("new", shortName, "--help", language?.let { "--language" }, language)
            val output = DotNetCli.execute(DotNetCli.commandLine(null, *arguments.toTypedArray()).withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en"), 60_000)
            if (output.exitCode != 0 && output.stdout.isBlank()) return TemplateHelp.EMPTY
            TemplateHelp.parse(output.stdout)
        } catch (e: Exception) {
            PluginLog.warn(DotNetTemplates.LOG_CATEGORY, "`dotnet new $shortName --help` could not run, no options are shown", e)
            return TemplateHelp.EMPTY
        }
        cache[key(shortName, language)] = help
        return help
    }

    /** After templates are installed or removed: a template of the same short name may now be another one. */
    fun clear() = cache.clear()
}
