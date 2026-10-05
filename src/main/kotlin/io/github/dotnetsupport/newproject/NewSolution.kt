package io.github.dotnetsupport.newproject

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import java.io.File
import javax.swing.Icon

/**
 * A kind of project in the left column of the New Solution dialog, as in Rider: "Project Type" kinds, then "Other", then whatever the
 * installed templates bring that fits nowhere ("Custom Templates"). [defaultName] starts the solution name: `ConsoleApp1`.
 */
enum class TemplateCategory(val title: String, val group: Group, val defaultName: String) {
    CONSOLE("Console", Group.PROJECT_TYPE, "ConsoleApp"),
    LIBRARY("Class Library", Group.PROJECT_TYPE, "ClassLibrary"),
    DESKTOP("Desktop", Group.PROJECT_TYPE, "DesktopApp"),
    WEB("Web", Group.PROJECT_TYPE, "WebApplication"),
    SERVICES("Services", Group.PROJECT_TYPE, "WorkerService"),
    TEST("Unit Test", Group.PROJECT_TYPE, "TestProject"),
    MAUI("MAUI", Group.OTHER, "MauiApp"),
    ASPIRE("Aspire", Group.OTHER, "AspireApp"),
    ROSLYN("Roslyn", Group.OTHER, "Analyzer"),
    CUSTOM("Custom Templates", Group.CUSTOM, "Project");

    enum class Group(val title: String) { PROJECT_TYPE("Project Type"), OTHER("Other"), CUSTOM("Custom Templates") }

    val icon: Icon
        get() = when (this) {
            CONSOLE -> AllIcons.Nodes.Console
            LIBRARY -> AllIcons.Nodes.PpLib
            DESKTOP -> AllIcons.Nodes.Desktop
            WEB -> AllIcons.General.Web
            SERVICES -> AllIcons.General.Settings
            TEST -> AllIcons.Nodes.TestGroup
            MAUI -> AllIcons.Nodes.Deploy
            ASPIRE -> AllIcons.Webreferences.Server
            ROSLYN -> AllIcons.Nodes.Lambda
            CUSTOM -> AllIcons.Nodes.Template
        }
}

/** The pure part of the New Solution dialog: sorting templates into kinds, paths, checks and the commands that create the solution. */
object NewSolution {
    /** Templates of these tags are not projects of a solution. */
    private val NOT_PROJECTS = setOf("solution", "config", "item")
    private const val INVALID_NAME_CHARS = "\\/:*?\"<>|"

    /**
     * The kind of [template] by its tags (`dotnet new list`). The order matters: test templates are tagged Desktop and Web too, Aspire ones
     * Web and Service, a worker Web, MAUI ones Windows; a web API is tagged Service but is Web in Rider. Null for what is not a project.
     */
    fun categoryOf(template: DotNetTemplate): TemplateCategory? {
        val tags = template.tags.map { it.lowercase() }.toSet()
        val name = template.name.lowercase()
        fun tag(vararg any: String) = any.any { it in tags }
        return when {
            template.shortName == "sln" || tags.any { it in NOT_PROJECTS } -> null
            tag("test", "unit test") -> TemplateCategory.TEST
            tag(".net aspire", "aspire") || "aspire" in name -> TemplateCategory.ASPIRE
            tag("maui") || ".net maui" in name -> TemplateCategory.MAUI
            tag("roslyn", "analyzer", "analyzers", "source generator", "code fix", "codefix") -> TemplateCategory.ROSLYN
            tag("worker", "grpc") -> TemplateCategory.SERVICES
            tag("desktop", "wpf", "winforms", "windows forms", "winui", "avalonia") -> TemplateCategory.DESKTOP
            tag("web", "asp.net", "blazor", "razor", "webapi", "mvc") -> TemplateCategory.WEB
            tag("console") -> TemplateCategory.CONSOLE
            tag("library") -> TemplateCategory.LIBRARY
            else -> TemplateCategory.CUSTOM
        }
    }

    /** The kinds that have templates, in the order of the list. */
    fun categories(templates: List<DotNetTemplate>): List<TemplateCategory> {
        val present = templates.mapNotNull(::categoryOf).toSet()
        return TemplateCategory.entries.filter { it in present }
    }

    fun templatesOf(category: TemplateCategory, templates: List<DotNetTemplate>): List<DotNetTemplate> = templates.filter { categoryOf(it) == category }

    /** The search of the left column: the name, the short name or a tag. */
    fun matches(template: DotNetTemplate, query: String): Boolean {
        val text = query.trim()
        return text.isEmpty() || template.name.contains(text, ignoreCase = true) || template.shortName.contains(text, ignoreCase = true) ||
            template.tags.any { it.contains(text, ignoreCase = true) }
    }

    /** True when the template supports [framework]; a template whose frameworks are not known (yet), or that has no `--framework`, does. */
    fun supports(templateFrameworks: List<String>?, framework: String?): Boolean =
        framework == null || templateFrameworks.isNullOrEmpty() || framework in templateFrameworks

    /** [createDirectory] is the "Create directory for the solution" of an Empty Solution; a solution with a project always gets one. */
    fun solutionDirectory(parent: String, solutionName: String, createDirectory: Boolean = true): File =
        if (createDirectory) File(parent, solutionName) else File(parent)

    fun projectDirectory(parent: String, solutionName: String, projectName: String, sameDirectory: Boolean): File =
        solutionDirectory(parent, solutionName).let { if (sameDirectory) it else File(it, projectName) }

    /**
     * The gray line under the directory, as in Rider: what is added to the chosen directory, `...\WpfApp1\WpfApp1`; the directory itself
     * when nothing is added to it.
     */
    fun createdIn(parent: String, solutionName: String, projectName: String?, sameDirectory: Boolean, createDirectory: Boolean = true): String {
        val separator = File.separator
        val added = when {
            projectName == null -> if (createDirectory) listOf(solutionName) else emptyList()
            sameDirectory -> listOf(solutionName)
            else -> listOf(solutionName, projectName)
        }
        val where = if (added.isEmpty()) File(parent).path else "..." + added.joinToString("") { "$separator$it" }
        return (if (projectName == null) "The solution will be created in " else "The project will be created in ") + where
    }

    /** The gray line under the directory of Add | New Project: the directory of the project itself. */
    fun projectCreatedIn(parent: String, projectName: String): String =
        "The project will be created in " + (if (projectName.isBlank()) File(parent).path else File(parent, projectName.trim()).path)

    /** The first problem of the form of Add | New Project, or null: the project goes to `<parent>/<name>`, which must be free. */
    fun validateProject(projectName: String, parent: String, hasTemplate: Boolean, isNonEmptyDirectory: (File) -> Boolean): Problem? {
        when {
            projectName.isBlank() -> return Problem(Field.PROJECT_NAME, "Specify the project name")
            projectName.any { it in INVALID_NAME_CHARS || it < ' ' } -> return Problem(Field.PROJECT_NAME, "The project name contains characters that are not allowed in file names")
            projectName.trim() != projectName || projectName.endsWith('.') -> return Problem(Field.PROJECT_NAME, "The project name cannot start or end with a space or end with a dot")
        }
        if (parent.isBlank()) return Problem(Field.DIRECTORY, "Specify the project directory")
        val directory = File(parent, projectName)
        if (isNonEmptyDirectory(directory)) return Problem(Field.DIRECTORY, "The directory ${directory.path} already exists and is not empty")
        if (!hasTemplate) return Problem(Field.TEMPLATE, "Select a template")
        return null
    }

    /** The name Rider starts with for a template: `WpfApp` for `wpf`, `ConsoleApp` for `console`, else the one of its kind. */
    fun defaultBaseName(template: DotNetTemplate?): String {
        if (template == null) return "Solution"
        DEFAULT_NAMES[template.shortName]?.let { return it }
        return categoryOf(template)?.defaultName ?: "Project"
    }

    private val DEFAULT_NAMES = mapOf(
        "console" to "ConsoleApp", "classlib" to "ClassLibrary", "wpf" to "WpfApp", "wpflib" to "WpfLibrary", "wpfcustomcontrollib" to "WpfCustomControlLibrary",
        "wpfusercontrollib" to "WpfUserControlLibrary", "winforms" to "WinFormsApp", "winformslib" to "WinFormsLibrary", "winformscontrollib" to "WinFormsControlLibrary",
        "web" to "WebApplication", "webapi" to "WebApplication", "webapp" to "WebApplication", "mvc" to "WebApplication", "blazor" to "BlazorApp",
        "blazorwasm" to "BlazorApp", "grpc" to "GrpcService", "worker" to "WorkerService", "xunit" to "TestProject", "nunit" to "TestProject",
        "mstest" to "TestProject", "maui" to "MauiApp", "mauilib" to "MauiLib", "aspire" to "AspireApp", "aspire-starter" to "AspireApp",
    )

    /** `ConsoleApp1`, or the first number whose directory is free. */
    fun defaultName(base: String, isTaken: (String) -> Boolean): String = generateSequence(1) { it + 1 }.map { "$base$it" }.first { !isTaken(it) }

    enum class Field { SOLUTION_NAME, PROJECT_NAME, DIRECTORY, TEMPLATE }

    class Problem(val field: Field, val message: String)

    /** The first problem of the form, or null. [isNonEmptyDirectory] tells whether a directory exists and has files. */
    fun validate(
        solutionName: String,
        projectName: String?,
        parent: String,
        sameDirectory: Boolean,
        hasTemplate: Boolean,
        isNonEmptyDirectory: (File) -> Boolean,
        createDirectory: Boolean = true,
    ): Problem? {
        fun nameProblem(name: String, what: String, field: Field): Problem? = when {
            name.isBlank() -> Problem(field, "Specify the $what name")
            name.any { it in INVALID_NAME_CHARS || it < ' ' } -> Problem(field, "The $what name contains characters that are not allowed in file names")
            name.trim() != name || name.endsWith('.') -> Problem(field, "The $what name cannot start or end with a space or end with a dot")
            else -> null
        }
        nameProblem(solutionName, "solution", Field.SOLUTION_NAME)?.let { return it }
        if (projectName != null) nameProblem(projectName, "project", Field.PROJECT_NAME)?.let { return it }
        if (parent.isBlank()) return Problem(Field.DIRECTORY, "Specify the solution directory")
        val directory = solutionDirectory(parent, solutionName, createDirectory)
        // a solution put into an existing directory on purpose ("Create directory for the solution" off) may join files there
        if (createDirectory && isNonEmptyDirectory(directory)) return Problem(Field.DIRECTORY, "The directory ${directory.path} already exists and is not empty")
        if (projectName != null && !sameDirectory && isNonEmptyDirectory(projectDirectory(parent, solutionName, projectName, false))) {
            return Problem(Field.DIRECTORY, "The directory of the project already exists and is not empty")
        }
        if (projectName != null && !hasTemplate) return Problem(Field.TEMPLATE, "Select a template")
        return null
    }

    /** What the dialog asks for. [template] is null for an Empty Solution; [pinnedSdk] is set when the user picked an SDK other than the default one. */
    class Request(
        val parent: String,
        val solutionName: String,
        val projectName: String?,
        val sameDirectory: Boolean,
        val git: Boolean,
        val template: DotNetTemplateSettings?,
        val pinnedSdk: String?,
        val createDirectory: Boolean = true,
    ) {
        val solutionDirectory: File get() = solutionDirectory(parent, solutionName, createDirectory || projectName != null)
        val projectDirectory: File? get() = projectName?.let { projectDirectory(parent, solutionName, it, sameDirectory) }
    }

    /** One command, run in the solution directory: [tool] is `dotnet` or `git`. */
    data class Command(val tool: String, val arguments: List<String>)

    /**
     * `global.json` first, so that every later `dotnet` command runs on the pinned SDK; then the solution (`.sln` or `.slnx`, whatever the SDK
     * makes by default), the project and its place in the solution; the repository last, with the `.gitignore` of `dotnet new` that keeps
     * `bin` and `obj` out of it.
     */
    fun commands(request: Request): List<Command> = buildList {
        request.pinnedSdk?.let { add(Command("dotnet", listOf("new", "globaljson", "--sdk-version", it))) }
        add(Command("dotnet", listOf("new", "sln", "-n", request.solutionName)))
        val template = request.template
        val project = request.projectName
        if (template != null && project != null) {
            val output = if (request.sameDirectory) "." else project
            add(Command("dotnet", template.newArguments(project, output)))
            val projectFile = (if (request.sameDirectory) "" else "$project/") + "$project.${template.projectExtension}"
            add(Command("dotnet", listOf("sln", "add", projectFile)))
        }
        if (request.git) {
            add(Command("dotnet", listOf("new", "gitignore")))
            add(Command("git", listOf("init")))
        }
    }
}

/** SDK versions for the "from SDK" link next to the target framework. */
object SdkVersions {
    /** Sortable: `10.0.401` above `9.0.305` above `9.0.100` above `9.0.100-rc.1`. */
    fun key(version: String): Long {
        val release = version.substringBefore('-')
        val parts = release.split('.').map { it.toLongOrNull() ?: 0L }
        val stable = if ('-' in version) 0L else 1L
        return parts.getOrElse(0) { 0 } * 1_000_000_000L + parts.getOrElse(1) { 0 } * 1_000_000L + parts.getOrElse(2) { 0 } * 10L + stable
    }

    fun major(version: String): Int? = version.substringBefore('.').toIntOrNull()

    /** `net9.0` -> 9; null for `netstandard2.0` and the like. */
    fun frameworkMajor(framework: String): Int? = Regex("""^net(\d+)\.\d+$""").matchEntire(framework)?.groupValues?.get(1)?.toIntOrNull()

    /** The SDKs that can build [framework] (their major is not older than its), newest first. */
    fun sdksFor(framework: String?, sdks: List<String>): List<String> {
        val needed = framework?.let(::frameworkMajor) ?: return sdks
        return sdks.filter { (major(it) ?: 0) >= needed }
    }

    /** `SDK 9.0` (the link after "from"); the full version when two installed SDKs share the major and minor. */
    fun label(version: String, sdks: List<String>): String {
        val short = version.split('.').take(2).joinToString(".")
        val ambiguous = sdks.count { it.split('.').take(2).joinToString(".") == short } > 1
        return "SDK " + if (ambiguous) version else short
    }
}

/**
 * Options of the SDK templates that Rider shows with words of its own and in places of their own: top-level statements in the form under
 * the template, nullable / native AOT / language version in Advanced Settings. Every other option goes to Advanced Settings as `--help`
 * describes it.
 */
object KnownTemplateOptions {
    enum class Place { MAIN, ADVANCED }

    class Known(val label: String, val place: Place, val suggestions: List<String> = emptyList())

    const val DEFAULT_LANGUAGE_VERSION = "Default for chosen framework"

    private val KNOWN = mapOf(
        "--use-program-main" to Known("Do not use top-level statements", Place.MAIN),
        "--nullable" to Known("Enable nullable", Place.ADVANCED),
        "--aot" to Known("Enable native AOT publish", Place.ADVANCED),
        "--langVersion" to Known(
            "Language version", Place.ADVANCED,
            listOf(DEFAULT_LANGUAGE_VERSION, "latest", "latestMajor", "preview", "14.0", "13.0", "12.0", "11.0", "10.0", "9.0", "8.0", "7.3"),
        ),
    )

    fun placeOf(option: TemplateOption): Place = KNOWN[option.name]?.place ?: Place.ADVANCED
    fun labelOf(option: TemplateOption): String = KNOWN[option.name]?.label ?: option.label
    fun suggestionsOf(option: TemplateOption): List<String> = KNOWN[option.name]?.suggestions.orEmpty()

    /** (main, advanced): known options first, in the order of the table, then the rest as the template declares them. */
    fun split(options: List<TemplateOption>): Pair<List<TemplateOption>, List<TemplateOption>> {
        val order = KNOWN.keys.toList()
        val sorted = options.sortedBy { order.indexOf(it.name).let { index -> if (index < 0) order.size else index } }
        return sorted.partition { placeOf(it) == Place.MAIN }
    }
}

/**
 * The identity and the group of a template for "Template description". `dotnet new` prints neither, but its template engine keeps them in
 * `~/.templateengine/dotnetcli/<SDK version>/templatecache.json`: read when it is there, the two rows stay hidden when it is not.
 */
object TemplateIdentities {
    class Identity(val identity: String, val groupIdentity: String?)

    /** The file of the SDK [sdkVersion], else of the newest SDK that has one. */
    fun cacheFile(home: File, sdkVersion: String?): File? {
        val root = File(home, ".templateengine/dotnetcli")
        sdkVersion?.let { File(root, "$it/templatecache.json") }?.takeIf { it.isFile }?.let { return it }
        return root.listFiles()?.filter { File(it, "templatecache.json").isFile }?.maxByOrNull { SdkVersions.key(it.name) }?.let { File(it, "templatecache.json") }
    }

    /** `shortName|language` -> the identity of the highest precedence (the newest framework of the template wins). */
    fun parse(json: String): Map<String, Identity> {
        val root = try {
            JsonParser.parseString(json.removePrefix("﻿")).asJsonObject
        } catch (e: Exception) {
            return emptyMap()
        }
        val best = HashMap<String, Pair<Long, Identity>>()
        for (element in root.getAsJsonArray("TemplateInfo") ?: return emptyMap()) {
            val info = element as? JsonObject ?: continue
            val identity = info.string("Identity") ?: continue
            val shortNames = info.getAsJsonArray("ShortNameList")?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
            val language = (info.get("TagsCollection") as? JsonObject)?.string("language").orEmpty()
            val precedence = runCatching { info.get("Precedence").asLong }.getOrDefault(0L)
            val value = Identity(identity, info.string("GroupIdentity"))
            for (shortName in shortNames) {
                val key = key(shortName, language)
                if ((best[key]?.first ?: Long.MIN_VALUE) < precedence) best[key] = precedence to value
            }
        }
        return best.mapValues { it.value.second }
    }

    fun key(shortName: String, language: String?): String = "$shortName|${language.orEmpty()}"

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
}
