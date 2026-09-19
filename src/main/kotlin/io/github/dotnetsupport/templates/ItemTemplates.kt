package io.github.dotnetsupport.templates

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.IncorrectOperationException
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.msbuild.MsBuildProject
import io.github.dotnetsupport.solution.SolutionService

enum class ItemCategory(val title: String) {
    CSHARP("C#"),
    ASPNET("ASP.NET"),
    RAZOR("Razor and Blazor"),
    TESTS("Tests"),
    EFCORE("EF Core"),
    CONFIG("Configuration"),
    RESOURCES("Resources");

    /** Whether the category is worth showing at the first level for [project]; the rest goes under "Other". */
    fun isRelevantFor(project: MsBuildProject?): Boolean {
        if (this == CONFIG) return true
        if (project == null) return false
        val sdk = project.sdk.orEmpty()
        fun hasPackage(part: String) = project.packages.any { it.name.contains(part, ignoreCase = true) }
        return when (this) {
            CSHARP, RESOURCES, CONFIG -> true
            ASPNET -> sdk.startsWith("Microsoft.NET.Sdk.Web", ignoreCase = true) || hasPackage("Microsoft.AspNetCore")
            RAZOR -> RAZOR_SDKS.any { sdk.startsWith(it, ignoreCase = true) } || hasPackage("AspNetCore.Components")
            TESTS -> project.isTestProject
            EFCORE -> hasPackage("EntityFrameworkCore")
        }
    }

    private companion object {
        val RAZOR_SDKS = listOf("Microsoft.NET.Sdk.Web", "Microsoft.NET.Sdk.Razor", "Microsoft.NET.Sdk.BlazorWebAssembly")
    }
}

/** [fileName] may contain variables and sub-directories: `${NAME}.razor.cs`, `Properties/launchSettings.json`. */
class ItemFile(val fileName: String, val template: String, val wrapInNamespace: Boolean = template.endsWith(".cs"))

class ItemTemplate(
    val id: String,
    val title: String,
    val category: ItemCategory,
    val files: List<ItemFile> = emptyList(),
    /** Conventional ending of the type name, added when the user leaves it out: `Orders` -> `OrdersController`. */
    val suffix: String? = null,
    /** Set for files with a well-known name: nothing is asked. */
    val fixedName: String? = null,
    val namePrompt: String = "Name",
    val isIdentifier: Boolean = true,
    /** Short name of a `dotnet new` item template that produces the file instead of [files]. */
    val cliTemplate: String? = null,
    /** File the CLI template creates, relative to the target directory. */
    val cliOutput: String? = null,
) {
    /** File whose icon represents the template and which is opened after creation. */
    val mainFileName: String get() = cliOutput ?: files.first().fileName
}

object ItemTemplates {
    private fun csharp(id: String, title: String, category: ItemCategory, template: String, suffix: String? = null, prompt: String = "Name") =
        ItemTemplate(id, title, category, listOf(ItemFile("\${NAME}.cs", template)), suffix = suffix, namePrompt = prompt)

    private fun cli(id: String, title: String, shortName: String, output: String) =
        ItemTemplate(id, title, ItemCategory.CONFIG, fixedName = output, cliTemplate = shortName, cliOutput = output)

    val ALL: List<ItemTemplate> = listOf(
        csharp("exception", "Exception", ItemCategory.CSHARP, "exception.cs", suffix = "Exception"),
        csharp("attribute", "Attribute", ItemCategory.CSHARP, "attribute.cs", suffix = "Attribute"),
        csharp("extensions", "Extensions Class", ItemCategory.CSHARP, "extensions.cs", suffix = "Extensions"),
        csharp("delegate", "Delegate", ItemCategory.CSHARP, "delegate.cs"),
        ItemTemplate(
            "program", "Top-level Program", ItemCategory.CSHARP,
            listOf(ItemFile("Program.cs", "program.cs", wrapInNamespace = false)), fixedName = "Program.cs",
        ),

        csharp("apiController", "API Controller", ItemCategory.ASPNET, "apiController.cs", suffix = "Controller"),
        csharp("mvcController", "MVC Controller", ItemCategory.ASPNET, "mvcController.cs", suffix = "Controller"),
        csharp("minimalApi", "Minimal API Endpoints", ItemCategory.ASPNET, "minimalApi.cs", suffix = "Endpoints"),
        csharp("middleware", "Middleware", ItemCategory.ASPNET, "middleware.cs", suffix = "Middleware"),
        csharp("filter", "Action Filter", ItemCategory.ASPNET, "filter.cs", suffix = "Filter"),
        csharp("backgroundService", "Background Service", ItemCategory.ASPNET, "backgroundService.cs"),
        csharp("healthCheck", "Health Check", ItemCategory.ASPNET, "healthCheck.cs", suffix = "HealthCheck"),
        csharp("options", "Options Class with Registration", ItemCategory.ASPNET, "options.cs", suffix = "Options"),

        ItemTemplate("component", "Razor Component", ItemCategory.RAZOR, listOf(ItemFile("\${NAME}.razor", "component.razor"))),
        ItemTemplate(
            "componentCodeBehind", "Razor Component with Code-Behind", ItemCategory.RAZOR,
            listOf(ItemFile("\${NAME}.razor", "componentMarkup.razor"), ItemFile("\${NAME}.razor.cs", "componentCodeBehind.cs")),
        ),
        ItemTemplate(
            "razorPage", "Razor Page", ItemCategory.RAZOR,
            listOf(ItemFile("\${NAME}.cshtml", "razorPage.cshtml"), ItemFile("\${NAME}.cshtml.cs", "pageModel.cs")),
        ),
        ItemTemplate("view", "View", ItemCategory.RAZOR, listOf(ItemFile("\${NAME}.cshtml", "view.cshtml"))),
        ItemTemplate("layout", "Layout", ItemCategory.RAZOR, listOf(ItemFile("_Layout.cshtml", "layout.cshtml")), fixedName = "_Layout.cshtml"),
        ItemTemplate("imports", "_Imports.razor", ItemCategory.RAZOR, listOf(ItemFile("_Imports.razor", "imports.razor")), fixedName = "_Imports.razor"),
        ItemTemplate(
            "viewImports", "_ViewImports.cshtml", ItemCategory.RAZOR,
            listOf(ItemFile("_ViewImports.cshtml", "viewImports.cshtml")), fixedName = "_ViewImports.cshtml",
        ),

        csharp("xunitTest", "xUnit Test Class", ItemCategory.TESTS, "xunitTest.cs", suffix = "Tests"),
        csharp("nunitTest", "NUnit Test Class", ItemCategory.TESTS, "nunitTest.cs", suffix = "Tests"),
        csharp("mstestTest", "MSTest Test Class", ItemCategory.TESTS, "mstestTest.cs", suffix = "Tests"),
        csharp("fixture", "Fixture Class", ItemCategory.TESTS, "fixture.cs", suffix = "Fixture"),

        csharp("dbContext", "DbContext", ItemCategory.EFCORE, "dbContext.cs", suffix = "Context"),
        csharp("entityConfiguration", "Entity Type Configuration", ItemCategory.EFCORE, "entityConfiguration.cs", suffix = "Configuration", prompt = "Entity name"),

        ItemTemplate(
            "appsettings", "appsettings.{Environment}.json", ItemCategory.CONFIG,
            listOf(ItemFile("appsettings.\${NAME}.json", "appsettings.json")), namePrompt = "Environment (Development, Staging, ...)", isIdentifier = false,
        ),
        ItemTemplate(
            "launchSettings", "launchSettings.json", ItemCategory.CONFIG,
            listOf(ItemFile("Properties/launchSettings.json", "launchSettings.json")), fixedName = "Properties/launchSettings.json",
        ),
        cli("globalJson", "global.json", "globaljson", "global.json"),
        cli("nugetConfig", "nuget.config", "nugetconfig", "nuget.config"),
        cli("buildProps", "Directory.Build.props", "buildprops", "Directory.Build.props"),
        cli("buildTargets", "Directory.Build.targets", "buildtargets", "Directory.Build.targets"),
        cli("packagesProps", "Directory.Packages.props", "packagesprops", "Directory.Packages.props"),
        cli("editorConfig", ".editorconfig", "editorconfig", ".editorconfig"),
        cli("gitignore", ".gitignore", "gitignore", ".gitignore"),
        cli("toolManifest", "dotnet-tools.json", "tool-manifest", ".config/dotnet-tools.json"),
        ItemTemplate("dockerfile", "Dockerfile", ItemCategory.CONFIG, listOf(ItemFile("Dockerfile", "Dockerfile")), fixedName = "Dockerfile"),
        ItemTemplate(
            "githubWorkflow", "GitHub Actions Workflow", ItemCategory.CONFIG,
            listOf(ItemFile(".github/workflows/dotnet.yml", "githubWorkflow.yml")), fixedName = ".github/workflows/dotnet.yml",
        ),

        ItemTemplate("resx", "Resources File (.resx)", ItemCategory.RESOURCES, listOf(ItemFile("\${NAME}.resx", "resources.resx"))),
        ItemTemplate(
            "userControl", "XAML User Control (WPF)", ItemCategory.RESOURCES,
            listOf(ItemFile("\${NAME}.xaml", "userControl.xaml"), ItemFile("\${NAME}.xaml.cs", "userControlCodeBehind.cs")),
        ),
        ItemTemplate(
            "window", "XAML Window (WPF)", ItemCategory.RESOURCES,
            listOf(ItemFile("\${NAME}.xaml", "window.xaml"), ItemFile("\${NAME}.xaml.cs", "windowCodeBehind.cs")),
        ),
    )

    fun byId(id: String): ItemTemplate = ALL.first { it.id == id }
}

/** Everything a template needs to know about the place where the item is created. */
class ItemContext(val project: Project, val directory: VirtualFile) {
    val projectFile: VirtualFile? = DotNetProjects.findOwningProject(directory)
    val msBuildProject: MsBuildProject? = projectFile?.let { SolutionService.getInstance(project).msBuildProject(it) }
    val namespace: String? = projectFile?.let { DotNetProjects.namespaceFor(directory, it, msBuildProject?.rootNamespace) }

    /** [namespaceOverride]: for code that must live in the namespace of an existing type rather than the one of the folder. */
    fun csharpContext(extraUsings: List<String> = emptyList(), namespaceOverride: String? = null) = TemplateRenderer.CSharpContext(
        namespace = namespaceOverride ?: namespace,
        fileScopedNamespace = (namespaceOverride ?: namespace) != null && CSharpNamespaces.isFileScopedPreferred(project, directory),
        implicitUsings = msBuildProject?.implicitUsings == true,
        webSdk = msBuildProject?.sdk.orEmpty().startsWith("Microsoft.NET.Sdk.Web", ignoreCase = true),
        extraUsings = extraUsings.filter { it != (namespaceOverride ?: namespace) },
    )

    fun variables(name: String, baseName: String): Map<String, String> {
        val projectName = projectFile?.nameWithoutExtension ?: directory.name
        val framework = msBuildProject?.targetFrameworks?.firstOrNull().orEmpty()
        val isWeb = msBuildProject?.sdk.orEmpty().startsWith("Microsoft.NET.Sdk.Web", ignoreCase = true)
        return mapOf(
            "NAME" to name,
            "BASE" to baseName,
            "ROUTE" to baseName.lowercase(),
            "NAMESPACE" to namespace.orEmpty(),
            "ROOT_NAMESPACE" to (projectFile?.let { DotNetProjects.namespaceFor(it.parent, it, msBuildProject?.rootNamespace) } ?: projectName),
            "PROJECT" to projectName,
            "PROJECT_FILE" to (projectFile?.name ?: "$projectName.csproj"),
            "DOTNET_VERSION" to (Regex("""^net(\d+\.\d+)""").find(framework)?.groupValues?.get(1) ?: DEFAULT_DOTNET_VERSION),
            "RUNTIME_IMAGE" to if (isWeb) "aspnet" else "runtime",
        )
    }

    private companion object {
        const val DEFAULT_DOTNET_VERSION = "8.0"
    }
}

object ItemCreator {
    /** `Orders` + suffix `Controller` -> `OrdersController` / `Orders`; a name that already ends with the suffix is kept. */
    fun names(template: ItemTemplate, input: String): Pair<String, String> {
        val suffix = template.suffix ?: return input to input
        return if (input.endsWith(suffix) && input.length > suffix.length) input to input.removeSuffix(suffix) else (input + suffix) to input
    }

    /**
     * Creates the files of [template] in [directory]. [input] may have leading folders (`Models/Order`),
     * which are created and become a part of the namespace. Returns the created files, the main one first.
     */
    fun create(
        project: Project,
        directory: VirtualFile,
        template: ItemTemplate,
        input: String,
        extraUsings: List<String> = emptyList(),
        extraVariables: Map<String, String> = emptyMap(),
        namespaceOverride: String? = null,
    ): List<VirtualFile> = WriteCommandAction.writeCommandAction(project).withName("Create ${template.title}").compute<List<VirtualFile>, Exception> {
        val path = input.trim().replace('\\', '/').split('/').filter { it.isNotBlank() }
        val targetDirectory = if (path.size > 1) VfsUtil.createDirectoryIfMissing(directory, path.dropLast(1).joinToString("/")) else directory
        val (name, baseName) = names(template, path.lastOrNull().orEmpty())

        val context = ItemContext(project, targetDirectory)
        val variables = context.variables(name, baseName) + extraVariables
        val planned = template.files.map { TemplateRenderer.substitute(it.fileName, variables) to it }
        planned.firstOrNull { (fileName, _) -> targetDirectory.findFileByRelativePath(fileName) != null }
            ?.let { throw IncorrectOperationException("File '${it.first}' already exists") }

        planned.map { (fileName, file) ->
            val text = TemplateRenderer.load(file.template).let {
                if (file.wrapInNamespace) TemplateRenderer.renderCSharp(it, variables, context.csharpContext(extraUsings, namespaceOverride))
                else TemplateRenderer.renderPlain(it, variables)
            }
            val parent = fileName.substringBeforeLast('/', "").let { if (it.isEmpty()) targetDirectory else VfsUtil.createDirectoryIfMissing(targetDirectory, it) }
            parent.createChildData(this, fileName.substringAfterLast('/')).also { VfsUtil.saveText(it, text) }
        }
    }
}
