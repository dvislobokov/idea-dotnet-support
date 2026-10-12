package io.github.dotnetsupport.newproject

import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.PluginLog

data class DotNetTemplate(
    val name: String,
    val shortName: String,
    val languages: List<String>,
    val defaultLanguage: String?,
    /** The Tags column of `dotnet new list`: `Common/Console`, `Web/WebAPI/API/Service`; the New Solution dialog sorts templates by them. */
    val tags: List<String> = emptyList(),
) {
    override fun toString(): String = name
}

object DotNetTemplates {
    private val CSHARP_ONLY = listOf("C#")
    private val ALL_LANGUAGES = listOf("C#", "F#", "VB")

    /** Shown until (and unless) `dotnet new list` answers. */
    val BUILT_IN: List<DotNetTemplate> = listOf(
        DotNetTemplate("Console App", "console", ALL_LANGUAGES, "C#", listOf("Common", "Console")),
        DotNetTemplate("Class Library", "classlib", ALL_LANGUAGES, "C#", listOf("Common", "Library")),
        DotNetTemplate("ASP.NET Core Web API", "webapi", listOf("C#", "F#"), "C#", listOf("Web", "WebAPI", "API", "Service")),
        DotNetTemplate("ASP.NET Core Web App (Razor Pages)", "webapp", CSHARP_ONLY, "C#", listOf("Web", "MVC", "Razor Pages")),
        DotNetTemplate("ASP.NET Core Web App (MVC)", "mvc", listOf("C#", "F#"), "C#", listOf("Web", "MVC")),
        DotNetTemplate("ASP.NET Core Empty", "web", listOf("C#", "F#"), "C#", listOf("Web", "Empty")),
        DotNetTemplate("Blazor Web App", "blazor", CSHARP_ONLY, "C#", listOf("Web", "Blazor", "WebAssembly")),
        DotNetTemplate("Worker Service", "worker", listOf("C#", "F#"), "C#", listOf("Common", "Worker", "Web")),
        DotNetTemplate("xUnit Test Project", "xunit", ALL_LANGUAGES, "C#", listOf("Test", "xUnit", "Desktop", "Web")),
        DotNetTemplate("NUnit Test Project", "nunit", ALL_LANGUAGES, "C#", listOf("Test", "NUnit", "Desktop", "Web")),
        DotNetTemplate("MSTest Test Project", "mstest", ALL_LANGUAGES, "C#", listOf("Test", "MSTest", "Desktop", "Web")),
    )

    /** Installed project templates. Blocking; returns [BUILT_IN] when the CLI is unavailable or prints something unexpected. */
    fun loadProjectTemplates(): List<DotNetTemplate> = try {
        // English names, as Rider shows them ("Console App", not the name in the language of the OS); the parsing does not depend on it
        val output = DotNetCli.execute(DotNetCli.commandLine(null, "new", "list", "--type", "project").withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en"), 60_000)
        parseList(output.stdout).ifEmpty { PluginLog.warn(LOG_CATEGORY, "`dotnet new list --type project` printed no templates the plugin reads (exit code ${output.exitCode}), the built-in list is shown"); BUILT_IN }
    } catch (e: Exception) {
        PluginLog.warn(LOG_CATEGORY, "`dotnet new list --type project` could not run, the built-in list is shown", e)
        BUILT_IN
    }

    /** Installed item templates (`dotnet new list --type item`); empty when the CLI is unavailable. Blocking. */
    fun loadItemTemplates(): List<DotNetTemplate> = try {
        parseList(DotNetCli.execute(DotNetCli.commandLine(null, "new", "list", "--type", "item"), 60_000).stdout)
    } catch (e: Exception) {
        PluginLog.warn(LOG_CATEGORY, "`dotnet new list --type item` could not run", e)
        emptyList()
    }

    /** `net8.0`-style monikers of the installed SDKs, newest first. */
    fun loadFrameworks(): List<String> = try {
        parseSdkList(DotNetCli.execute(DotNetCli.commandLine(null, "--list-sdks"), 30_000).stdout)
    } catch (e: Exception) {
        PluginLog.warn(LOG_CATEGORY, "`dotnet --list-sdks` could not run, no frameworks to choose from", e)
        emptyList()
    }

    /** Versions of the installed SDKs (`10.0.401`), newest first; empty when the CLI is unavailable. Blocking. */
    fun loadSdkVersions(): List<String> = try {
        parseSdkVersions(DotNetCli.execute(DotNetCli.commandLine(null, "--list-sdks"), 30_000).stdout)
    } catch (e: Exception) {
        PluginLog.warn(LOG_CATEGORY, "`dotnet --list-sdks` could not run, no SDKs to choose from", e)
        emptyList()
    }

    /** The category of the journal of the plugin for the templates: `dotnet new` and the template packages. */
    const val LOG_CATEGORY = "templates"

    /**
     * The output is a table whose header is underlined with dashes; column boundaries are taken from
     * the dashes so that the parsing does not depend on the (localized) column titles:
     * ```
     * Template Name  Short Name    Language    Tags
     * -------------  ------------  ----------  --------------
     * Console App    console       [C#],F#,VB  Common/Console
     * ```
     */
    fun parseList(output: String): List<DotNetTemplate> {
        val lines = output.lines()
        val ruler = lines.indexOfFirst { it.isNotBlank() && it.all { c -> c == '-' || c == ' ' } }
        if (ruler < 0) return emptyList()
        val columns = Regex("-+").findAll(lines[ruler]).map { it.range }.toList()
        if (columns.size < 3) return emptyList()

        fun cell(line: String, column: Int): String {
            val start = columns[column].first
            // the last column is not limited by its underline
            val end = if (column == columns.lastIndex) line.length else minOf(columns[column + 1].first, line.length)
            return if (start >= line.length) "" else line.substring(start, end).trim()
        }

        return lines.drop(ruler + 1).takeWhile { it.isNotBlank() }.mapNotNull { line ->
            val name = cell(line, 0)
            // A template may have aliases: "webapp,razor"
            val shortName = cell(line, 1).substringBefore(',').trim()
            if (name.isEmpty() || shortName.isEmpty()) return@mapNotNull null
            val languages = cell(line, 2).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            DotNetTemplate(
                name, shortName,
                languages = languages.map { it.removeSurrounding("[", "]") },
                defaultLanguage = languages.firstOrNull { it.startsWith("[") }?.removeSurrounding("[", "]"),
                tags = if (columns.size > 3) cell(line, 3).split('/').map { it.trim() }.filter { it.isNotEmpty() } else emptyList(),
            )
        }
    }

    /** Lines look like `8.0.404 [C:\Program Files\dotnet\sdk]`. */
    fun parseSdkList(output: String): List<String> =
        output.lines()
            .mapNotNull { it.trim().substringBefore('.').toIntOrNull() }
            .distinct()
            .sortedDescending()
            .map { "net$it.0" }

    /** Full versions of `dotnet --list-sdks`, newest first: `10.0.401`, `9.0.305`, `9.0.100`. */
    fun parseSdkVersions(output: String): List<String> =
        output.lines()
            .map { it.trim().substringBefore(' ') }
            .filter { it.firstOrNull()?.isDigit() == true && '.' in it }
            .distinct()
            .sortedWith(compareByDescending<String> { SdkVersions.key(it) })
}
