package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.newproject.DotNetTemplate
import io.github.dotnetsupport.newproject.DotNetTemplateSettings
import io.github.dotnetsupport.newproject.DotNetTemplates
import io.github.dotnetsupport.newproject.KnownTemplateOptions
import io.github.dotnetsupport.newproject.NewSolution
import io.github.dotnetsupport.newproject.NewSolutionDialog
import io.github.dotnetsupport.newproject.SdkVersions
import io.github.dotnetsupport.newproject.TemplateCategory
import io.github.dotnetsupport.newproject.TemplateHelp
import io.github.dotnetsupport.newproject.TemplateIdentities
import io.github.dotnetsupport.newproject.WelcomeNewSolutionPlacement
import java.io.File

/** File | New | New Solution...: the pure part (kinds, paths, checks, commands) and the composition of the window. */
class NewSolutionTest : BasePlatformTestCase() {
    private fun template(shortName: String, tags: String, name: String = shortName) =
        DotNetTemplate(name, shortName, listOf("C#"), "C#", tags.split('/'))

    fun testTemplatesAreSortedIntoTheKindsOfRider() {
        val expected = mapOf(
            template("console", "Common/Console") to TemplateCategory.CONSOLE,
            template("classlib", "Common/Library") to TemplateCategory.LIBRARY,
            template("wpf", "Common/WPF") to TemplateCategory.DESKTOP,
            template("winforms", "Common/WinForms") to TemplateCategory.DESKTOP,
            template("avalonia.app", "Desktop/Xaml/Avalonia/Windows/Linux/macOS") to TemplateCategory.DESKTOP,
            template("webapi", "Web/WebAPI/API/Service") to TemplateCategory.WEB,
            template("blazor", "Web/Blazor/WebAssembly") to TemplateCategory.WEB,
            template("razorclasslib", "Web/Razor/Library/Razor Class Library") to TemplateCategory.WEB,
            template("worker", "Common/Worker/Web") to TemplateCategory.SERVICES,
            template("grpc", "Web/gRPC/API/Service") to TemplateCategory.SERVICES,
            template("xunit", "Test/xUnit/Desktop/Web") to TemplateCategory.TEST,
            template("mstest", "Test/MSTest/Desktop/Web") to TemplateCategory.TEST,
            template("maui", "MAUI/Android/iOS/macOS/Mac Catalyst/Windows/Tizen") to TemplateCategory.MAUI,
            template("aspire-starter", "Common/.NET Aspire/Cloud/Web/Web API/API/Service") to TemplateCategory.ASPIRE,
            template("analyzer", "Roslyn/Analyzer") to TemplateCategory.ROSLYN,
            template("mcpserver", "AI/MCP", "MCP Server App") to TemplateCategory.CUSTOM,
        )
        for ((template, category) in expected) assertEquals(template.shortName, category, NewSolution.categoryOf(template))
        assertNull("a solution file is not a project", NewSolution.categoryOf(DotNetTemplate("Solution File", "sln", emptyList(), null, listOf("Solution"))))
        // the built-in list has tags of its own, so it is sorted without the CLI too
        assertEquals(
            listOf(TemplateCategory.CONSOLE, TemplateCategory.LIBRARY, TemplateCategory.WEB, TemplateCategory.SERVICES, TemplateCategory.TEST),
            NewSolution.categories(DotNetTemplates.BUILT_IN),
        )
        assertEquals("in the order of Rider, kinds without templates left out", TemplateCategory.entries.filter { it != TemplateCategory.CUSTOM },
            NewSolution.categories(expected.keys.filter { NewSolution.categoryOf(it) != TemplateCategory.CUSTOM }.toList()))
        assertTrue(NewSolution.matches(template("webapi", "Web/WebAPI"), "api"))
        assertTrue(NewSolution.matches(template("x", "Web", "ASP.NET Core Web API"), "core web"))
        assertFalse(NewSolution.matches(template("console", "Common/Console"), "blazor"))
    }

    fun testWhereTheSolutionAndTheProjectAreCreated() {
        val parent = File("C:/Projects").path
        val s = File.separator
        assertEquals("The project will be created in ...${s}WpfApp1${s}WpfApp1", NewSolution.createdIn(parent, "WpfApp1", "WpfApp1", false))
        assertEquals("The project will be created in ...${s}App", NewSolution.createdIn(parent, "App", "Other", true))
        assertEquals("The solution will be created in ...${s}Solution1", NewSolution.createdIn(parent, "Solution1", null, false))
        assertEquals("The solution will be created in $parent", NewSolution.createdIn(parent, "Solution1", null, false, createDirectory = false))
        assertEquals(File(parent, "S/P"), NewSolution.projectDirectory(parent, "S", "P", false))
        assertEquals(File(parent, "S"), NewSolution.projectDirectory(parent, "S", "P", true))

        assertEquals("WpfApp", NewSolution.defaultBaseName(template("wpf", "Common/WPF")))
        assertEquals("ConsoleApp", NewSolution.defaultBaseName(template("console", "Common/Console")))
        assertEquals("WebApplication", NewSolution.defaultBaseName(template("other-web", "Web")))
        assertEquals("Solution", NewSolution.defaultBaseName(null))
        assertEquals("ConsoleApp3", NewSolution.defaultName("ConsoleApp") { it in setOf("ConsoleApp1", "ConsoleApp2") })
    }

    fun testValidation() {
        val parent = File("C:/Projects").path
        val nonEmpty = setOf(File(parent, "Busy"), File(parent, "Sln/Busy"))
        fun check(solution: String, project: String?, directory: String = parent, same: Boolean = false, createDirectory: Boolean = true) =
            NewSolution.validate(solution, project, directory, same, hasTemplate = true, isNonEmptyDirectory = { it in nonEmpty }, createDirectory = createDirectory)

        assertNull(check("App", "App"))
        assertEquals(NewSolution.Field.SOLUTION_NAME, check("", "App")?.field)
        assertEquals(NewSolution.Field.SOLUTION_NAME, check("A:b", "App")?.field)
        assertEquals(NewSolution.Field.PROJECT_NAME, check("App", " ")?.field)
        assertEquals(NewSolution.Field.PROJECT_NAME, check("App", "a|b")?.field)
        assertEquals(NewSolution.Field.PROJECT_NAME, check("App", "App.")?.field)
        assertEquals(NewSolution.Field.DIRECTORY, check("App", "App", directory = "")?.field)
        assertEquals(NewSolution.Field.DIRECTORY, check("Busy", "App")?.field)
        assertEquals(NewSolution.Field.DIRECTORY, check("Sln", "Busy")?.field)
        assertNull("the project shares the solution directory", check("Sln", "Busy", same = true))
        assertNull("an empty solution", check("App", null))
        assertNull("a solution put into an existing directory on purpose", check("Busy", null, createDirectory = false))
        assertEquals(NewSolution.Field.TEMPLATE,
            NewSolution.validate("App", "App", parent, false, hasTemplate = false, isNonEmptyDirectory = { false })?.field)
    }

    fun testCommandsThatCreateTheSolution() {
        val console = DotNetTemplate("Console App", "console", listOf("C#", "F#", "VB"), "C#")
        val settings = DotNetTemplateSettings(console, "F#", "net9.0", listOf("--aot"))
        val full = NewSolution.Request("C:/p", "Shop", "Shop.Api", sameDirectory = false, git = true, template = settings, pinnedSdk = "9.0.301")
        assertEquals(
            listOf(
                NewSolution.Command("dotnet", listOf("new", "globaljson", "--sdk-version", "9.0.301")),
                NewSolution.Command("dotnet", listOf("new", "sln", "-n", "Shop")),
                NewSolution.Command("dotnet", listOf("new", "console", "-n", "Shop.Api", "-o", "Shop.Api", "-lang", "F#", "-f", "net9.0", "--aot")),
                NewSolution.Command("dotnet", listOf("sln", "add", "Shop.Api/Shop.Api.fsproj")),
                NewSolution.Command("dotnet", listOf("new", "gitignore")),
                NewSolution.Command("git", listOf("init")),
            ),
            NewSolution.commands(full),
        )
        assertEquals(File("C:/p", "Shop"), full.solutionDirectory)

        val same = NewSolution.Request("C:/p", "App", "App", sameDirectory = true, git = false, template = DotNetTemplateSettings(console, null, null), pinnedSdk = null)
        assertEquals(
            listOf(listOf("new", "sln", "-n", "App"), listOf("new", "console", "-n", "App", "-o", "."), listOf("sln", "add", "App.csproj")),
            NewSolution.commands(same).map { it.arguments },
        )

        val empty = NewSolution.Request("C:/p", "Solution1", null, sameDirectory = false, git = false, template = null, pinnedSdk = null, createDirectory = false)
        assertEquals(listOf(NewSolution.Command("dotnet", listOf("new", "sln", "-n", "Solution1"))), NewSolution.commands(empty))
        assertEquals(File("C:/p"), empty.solutionDirectory)
    }

    fun testSdksAndFrameworks() {
        val sdks = DotNetTemplates.parseSdkVersions("9.0.100 [C:\\dotnet\\sdk]\n10.0.401 [C:\\dotnet\\sdk]\n9.0.301 [C:\\dotnet\\sdk]\n10.0.100-rc.1.25451.107 [C:\\dotnet\\sdk]\n")
        assertEquals(listOf("10.0.401", "10.0.100-rc.1.25451.107", "9.0.301", "9.0.100"), sdks)
        assertEquals(listOf("10.0.401", "10.0.100-rc.1.25451.107"), SdkVersions.sdksFor("net10.0", sdks))
        assertEquals(sdks, SdkVersions.sdksFor("net9.0", sdks))
        assertEquals(sdks, SdkVersions.sdksFor("netstandard2.0", sdks))
        assertEquals("SDK 9.0.301", SdkVersions.label("9.0.301", sdks))
        assertEquals("SDK 9.0", SdkVersions.label("9.0.301", listOf("10.0.401", "9.0.301")))

        assertTrue(NewSolution.supports(listOf("net10.0", "net9.0"), "net9.0"))
        assertFalse(NewSolution.supports(listOf("net10.0", "net9.0"), "net8.0"))
        assertTrue("frameworks not known yet", NewSolution.supports(null, "net8.0"))
        assertTrue("a template without --framework", NewSolution.supports(emptyList(), "net8.0"))
    }

    fun testHelpGivesTheDescriptionAndTheKnownOptions() {
        val help = TemplateHelp.parse(File("src/test/resources/dotnetNew/console.txt").readText())
        assertEquals("A project for creating a command-line application that can run on .NET on Windows, Linux and macOS", help.description)
        assertEquals("Microsoft", help.author)
        assertEquals(listOf("net10.0", "net9.0"), help.frameworks)
        val (main, advanced) = KnownTemplateOptions.split(help.options)
        assertEquals(listOf("--use-program-main"), main.map { it.name })
        assertEquals(listOf("--aot", "--langVersion"), advanced.map { it.name })
        assertEquals("Do not use top-level statements", KnownTemplateOptions.labelOf(main.single()))
        assertEquals(KnownTemplateOptions.DEFAULT_LANGUAGE_VERSION, KnownTemplateOptions.suggestionsOf(advanced[1]).first())
    }

    fun testIdentitiesFromTheCacheOfTheTemplateEngine() {
        val json = """
            ${'\uFEFF'}{"Version":"1.0.0.7","TemplateInfo":[
              {"Identity":"Microsoft.Common.Console.CSharp.9.0","GroupIdentity":"Microsoft.Common.Console","ShortNameList":["console"],"TagsCollection":{"language":"C#","type":"project"},"Precedence":10000},
              {"Identity":"Microsoft.Common.Console.CSharp.10.0","GroupIdentity":"Microsoft.Common.Console","ShortNameList":["console"],"TagsCollection":{"language":"C#","type":"project"},"Precedence":11000},
              {"Identity":"Microsoft.Common.Console.FSharp.10.0","GroupIdentity":"Microsoft.Common.Console","ShortNameList":["console"],"TagsCollection":{"language":"F#"},"Precedence":11000},
              {"Identity":"Some.Template","ShortNameList":["webapp","razor"],"Precedence":"broken"}
            ]}
        """.trimIndent()
        val identities = TemplateIdentities.parse(json)
        assertEquals("Microsoft.Common.Console.CSharp.10.0", identities[TemplateIdentities.key("console", "C#")]?.identity)
        assertEquals("Microsoft.Common.Console", identities[TemplateIdentities.key("console", "C#")]?.groupIdentity)
        assertEquals("Microsoft.Common.Console.FSharp.10.0", identities[TemplateIdentities.key("console", "F#")]?.identity)
        assertEquals("Some.Template", identities[TemplateIdentities.key("razor", "")]?.identity)
        assertNull(identities[TemplateIdentities.key("razor", "")]?.groupIdentity)
        assertTrue(TemplateIdentities.parse("not json").isEmpty())
    }

    fun testTheActionIsInFileNewAndInTheDotNetMenu() {
        val actions = ActionManager.getInstance()
        assertEquals("New Solution...", actions.getAction("DotNet.NewSolution").templatePresentation.text)
        val newGroup = (actions.getAction("NewGroup") as DefaultActionGroup).childActionsOrStubs.map { actions.getId(it) }
        assertTrue(newGroup.toString(), "DotNet.NewSolution" in newGroup)
        val menu = (actions.getAction("DotNet.MainMenu") as DefaultActionGroup).childActionsOrStubs.map { actions.getId(it) }
        assertEquals("next to New .NET Project...", menu.indexOf("DotNet.NewSolution") + 1, menu.indexOf("DotNet.NewProject"))

        // the Welcome screen: next to New Project, and available with no project open
        val welcome = (actions.getAction("WelcomeScreen.QuickStart") as DefaultActionGroup).childActionsOrStubs.map { actions.getId(it) }
        assertTrue(welcome.toString(), "DotNet.NewSolution.Welcome" in welcome)
        val newProject = welcome.indexOfFirst { it in WelcomeNewSolutionPlacement.NEW_PROJECT }
        if (newProject >= 0) assertEquals(welcome.toString(), newProject + 1, welcome.indexOf("DotNet.NewSolution.Welcome"))
        val action = actions.getAction("DotNet.NewSolution.Welcome")
        assertEquals("New Solution", action.templatePresentation.text)
        val event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
            action, com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT, null, "WelcomeScreen", com.intellij.openapi.actionSystem.ActionUiKind.NONE, null,
        )
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
    }

    /** The groups `WelcomeScreen.QuickStart` of the IDEs (2026.1 / 2026.2): New Solution goes right after New Project, wherever it is. */
    fun testWelcomeScreenPlacementInEveryIde() {
        fun target(vararg groups: Pair<String, List<String>>) = groups.toMap().let { tree -> WelcomeNewSolutionPlacement.target("WelcomeScreen.QuickStart") { tree[it] } }
        val idea = target("WelcomeScreen.QuickStart" to listOf("WelcomeScreen.QuickStart.Platform", "WelcomeScreen.DefaultNewProjectActionGroup", "WelcomeScreen.OpenProject", "Vcs.VcsClone"),
            "WelcomeScreen.QuickStart.Platform" to emptyList(), "WelcomeScreen.DefaultNewProjectActionGroup" to listOf("NewProject"))
        assertEquals("WelcomeScreen.QuickStart" to "WelcomeScreen.DefaultNewProjectActionGroup", idea)
        val goLand = target("WelcomeScreen.QuickStart" to listOf("WelcomeScreen.QuickStart.Platform", "WelcomeScreen.Platform.NewProject", "Vcs.VcsClone", "CombinedWelcomeRemdevAction"),
            "WelcomeScreen.QuickStart.Platform" to emptyList(), "WelcomeScreen.Platform.NewProject" to listOf("WelcomeScreen.CreateDirectoryProject", "WelcomeScreen.OpenDirectoryProject"),
            "WelcomeScreen.CreateDirectoryProject" to emptyList())
        assertEquals("WelcomeScreen.Platform.NewProject" to "WelcomeScreen.CreateDirectoryProject", goLand)
        assertEquals("WelcomeScreen.QuickStart" to null, target("WelcomeScreen.QuickStart" to listOf("Vcs.VcsClone")))
        assertNull(target())
    }

    /** A long description (Web templates) wraps instead of being cut at the right; `<T>` in it stays text. */
    fun testTheDescriptionWraps() {
        val dialog = NewSolutionDialog(null, loadFromCli = false)
        try {
            val text = "A project template for creating an ASP.NET Core application with an example Controller for a RESTful HTTP service. " +
                "This template can also be used for ASP.NET Core MVC Views and Controllers. ".repeat(4) + "Uses List<T>."
            dialog.describe(text)
            val pane = dialog.descriptionComponent()
            assertTrue(pane.text, pane.text.contains("List&lt;T&gt;"))
            val oneLine = pane.getFontMetrics(pane.font).stringWidth(text)
            assertTrue("preferred ${pane.preferredSize.width} of a line $oneLine", pane.preferredSize.width < oneLine / 2)
            pane.setSize(300, Short.MAX_VALUE.toInt())
            assertTrue("several lines at 300 px: ${pane.preferredSize.height}", pane.preferredSize.height > 3 * pane.getFontMetrics(pane.font).height)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun testTheWindowOpensWithoutAProject() {
        val dialog = NewSolutionDialog(null, loadFromCli = false)
        try {
            assertEquals(com.intellij.ide.impl.ProjectUtil.getBaseDir(), dialog.request()!!.parent)
            assertEquals("Console", dialog.leftTitles()[2])
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun testTheWindowShowsTheKindsAndTheFormOfTheChosenOne() {
        val directory = createTempDirectory()
        val dialog = NewSolutionDialog(project, loadFromCli = false)
        try {
            assertEquals(
                listOf("Empty Solution", "Project Type", "Console", "Class Library", "Web", "Services", "Unit Test"),
                dialog.leftTitles(),
            )
            dialog.setDirectory(directory.path)
            dialog.selectKind(TemplateCategory.WEB)
            assertEquals(listOf("webapi", "webapp", "mvc", "web", "blazor"), dialog.shownTemplates())
            assertTrue(dialog.isProjectNameShown())

            dialog.setNames("Shop", null)
            val s = File.separator
            assertEquals("the project name follows the solution name", "The project will be created in ...${s}Shop${s}Shop", dialog.createdInText())
            val request = dialog.request()!!
            assertEquals("Shop", request.projectName)
            assertEquals("webapi", request.template?.template?.shortName)
            assertNull("the default SDK is not pinned", request.pinnedSdk)

            dialog.selectKind(null)
            assertFalse("an empty solution has no project", dialog.isProjectNameShown())
            assertEquals("The solution will be created in ...${s}Shop", dialog.createdInText())
            assertNull(dialog.request()!!.template)

            dialog.setSearch("xunit")
            assertEquals("the search leaves the kinds with matching templates", listOf("Project Type", "Unit Test"), dialog.leftTitles())
            assertEquals(listOf("xunit"), dialog.shownTemplates())
        } finally {
            Disposer.dispose(dialog.disposable)
            directory.deleteRecursively()
        }
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("newSolution").toFile()
}
