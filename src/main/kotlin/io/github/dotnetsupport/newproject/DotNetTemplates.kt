package io.github.dotnetsupport.newproject

import io.github.dotnetsupport.cli.DotNetCli

data class DotNetTemplate(val name: String, val shortName: String, val languages: List<String>, val defaultLanguage: String?) {
    override fun toString(): String = name
}

object DotNetTemplates {
    private val CSHARP_ONLY = listOf("C#")
    private val ALL_LANGUAGES = listOf("C#", "F#", "VB")

    /** Shown until (and unless) `dotnet new list` answers. */
    val BUILT_IN: List<DotNetTemplate> = listOf(
        DotNetTemplate("Console App", "console", ALL_LANGUAGES, "C#"),
        DotNetTemplate("Class Library", "classlib", ALL_LANGUAGES, "C#"),
        DotNetTemplate("ASP.NET Core Web API", "webapi", listOf("C#", "F#"), "C#"),
        DotNetTemplate("ASP.NET Core Web App (Razor Pages)", "webapp", CSHARP_ONLY, "C#"),
        DotNetTemplate("ASP.NET Core Web App (MVC)", "mvc", listOf("C#", "F#"), "C#"),
        DotNetTemplate("ASP.NET Core Empty", "web", listOf("C#", "F#"), "C#"),
        DotNetTemplate("Blazor Web App", "blazor", CSHARP_ONLY, "C#"),
        DotNetTemplate("Worker Service", "worker", listOf("C#", "F#"), "C#"),
        DotNetTemplate("xUnit Test Project", "xunit", ALL_LANGUAGES, "C#"),
        DotNetTemplate("NUnit Test Project", "nunit", ALL_LANGUAGES, "C#"),
        DotNetTemplate("MSTest Test Project", "mstest", ALL_LANGUAGES, "C#"),
    )

    /** Installed project templates. Blocking; returns [BUILT_IN] when the CLI is unavailable or prints something unexpected. */
    fun loadProjectTemplates(): List<DotNetTemplate> = try {
        val output = DotNetCli.execute(DotNetCli.commandLine(null, "new", "list", "--type", "project"), 60_000)
        parseList(output.stdout).ifEmpty { BUILT_IN }
    } catch (_: Exception) {
        BUILT_IN
    }

    /** Installed item templates (`dotnet new list --type item`); empty when the CLI is unavailable. Blocking. */
    fun loadItemTemplates(): List<DotNetTemplate> = try {
        parseList(DotNetCli.execute(DotNetCli.commandLine(null, "new", "list", "--type", "item"), 60_000).stdout)
    } catch (_: Exception) {
        emptyList()
    }

    /** `net8.0`-style monikers of the installed SDKs, newest first. */
    fun loadFrameworks(): List<String> = try {
        parseSdkList(DotNetCli.execute(DotNetCli.commandLine(null, "--list-sdks"), 30_000).stdout)
    } catch (_: Exception) {
        emptyList()
    }

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
}
