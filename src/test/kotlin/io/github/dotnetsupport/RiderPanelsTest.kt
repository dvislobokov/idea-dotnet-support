package io.github.dotnetsupport

import com.intellij.execution.RunManager
import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.build.DotNetBuildCommand
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetConfigurationType
import io.github.dotnetsupport.run.DotNetConsoleFilterProvider
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.solution.SolutionParser
import io.github.dotnetsupport.view.SolutionKey
import io.github.dotnetsupport.view.SolutionNode
import io.github.dotnetsupport.view.SolutionTreeStructure

class RiderPanelsTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            DotNetBuildSettings.getInstance(project).apply { configuration = DotNetBuildSettings.DEFAULT_CONFIGURATION; framework = null }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testFileNesting() {
        myFixture.addFileToProject("Web/Web.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"/>")
        for (file in listOf(
            "Web/appsettings.json", "Web/appsettings.Development.json", "Web/appsettings.Production.json", "Web/Program.cs",
            "Web/Pages/Index.razor", "Web/Pages/Index.razor.cs", "Web/Pages/Index.razor.css",
            "Web/Resources/Strings.resx", "Web/Resources/Strings.Designer.cs", "Web/Resources/Strings.ru.resx",
        )) myFixture.addFileToProject(file, "")
        val sln = myFixture.addFileToProject("Web.slnx", "<Solution><Project Path=\"Web/Web.csproj\"/></Solution>").virtualFile

        // through the tree structure, as the Project tool window asks: that is where the nesting provider of the platform kicks in
        val structure = SolutionTreeStructure(project)
        // sorted: the order is the business of the comparator of the tool window, not of the structure
        fun names(node: Any): List<String> = structure.getChildElements(node).map { child ->
            (child as AbstractTreeNode<*>).update()
            child.presentation.presentableText ?: child.name.orEmpty()
        }.sorted()
        fun child(node: Any, name: String): Any = structure.getChildElements(node).first { (it as AbstractTreeNode<*>).apply { update() }.let { n -> (n.presentation.presentableText ?: n.name) == name } }

        val projectNode = SolutionNode(project, SolutionKey(sln), structure).children.single()
        // in the root of the project, where the directory is represented by our own node
        assertEquals(listOf("Dependencies", "Pages", "Program.cs", "Resources", "appsettings.json"), names(projectNode))
        assertEquals(listOf("appsettings.Development.json", "appsettings.Production.json"), names(child(projectNode, "appsettings.json")))
        // and in ordinary folders
        val pages = child(projectNode, "Pages")
        assertEquals(listOf("Index.razor"), names(pages))
        assertEquals(listOf("Index.razor.cs", "Index.razor.css"), names(child(pages, "Index.razor")))
        // a culture-specific resource file is a sibling, the designer file is nested
        val resources = child(projectNode, "Resources")
        assertEquals(listOf("Strings.resx", "Strings.ru.resx"), names(resources))
        assertEquals(listOf("Strings.Designer.cs"), names(child(resources, "Strings.resx")))
    }

    fun testSolutionConfigurations() {
        val sln = SolutionParser.parseSln(
            listOf(
                "Global",
                "\tGlobalSection(SolutionConfigurationPlatforms) = preSolution",
                "\t\tDebug|Any CPU = Debug|Any CPU",
                "\t\tDebug|x64 = Debug|x64",
                "\t\tRelease|Any CPU = Release|Any CPU",
                "\t\tStaging|Any CPU = Staging|Any CPU",
                "\tEndGlobalSection",
                "\tGlobalSection(ProjectConfigurationPlatforms) = postSolution",
                "\t\t{A}.Debug|Any CPU.ActiveCfg = Debug|Any CPU",
                "\tEndGlobalSection",
                "EndGlobal",
            ).joinToString("\r\n")
        )
        assertEquals(listOf("Debug", "Release", "Staging"), sln.configurations)
        assertEquals(listOf("Debug", "Perf"), SolutionParser.parseSlnx("<Solution><Configurations><BuildType Name=\"Debug\"/><BuildType Name=\"Perf\"/></Configurations></Solution>").configurations)
        assertEquals(emptyList<String>(), SolutionParser.parseSlnx("<Solution/>").configurations)
    }

    fun testConfigurationAndFrameworkGoIntoCommands() {
        val multi = myFixture.addFileToProject("Lib/Lib.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFrameworks>net8.0;net9.0</TargetFrameworks><OutputType>Exe</OutputType></PropertyGroup></Project>").virtualFile
        val single = myFixture.addFileToProject("App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net9.0</TargetFramework></PropertyGroup></Project>").virtualFile
        val sln = myFixture.addFileToProject("All.slnx", "<Solution><Project Path=\"Lib/Lib.csproj\"/><Project Path=\"App/App.csproj\"/></Solution>").virtualFile

        val settings = DotNetBuildSettings.getInstance(project)
        // .slnx omits the default configurations; only multi-targeted projects offer a framework choice
        assertEquals(listOf("Debug", "Release"), settings.availableConfigurations())
        assertEquals(listOf("net8.0", "net9.0"), settings.availableFrameworks())
        assertEquals(listOf("-c", "Debug"), settings.buildArguments(DotNetBuildCommand.BUILD, sln).toList())

        settings.configuration = "Release"
        settings.framework = "net8.0"
        assertEquals(listOf("-c", "Release", "--framework", "net8.0"), settings.buildArguments(DotNetBuildCommand.REBUILD, multi).toList())
        // the CLI rejects --framework for a solution; a project that does not target the framework keeps its own
        assertEquals(listOf("-c", "Release"), settings.buildArguments(DotNetBuildCommand.BUILD, sln).toList())
        assertEquals(listOf("-c", "Release"), settings.buildArguments(DotNetBuildCommand.CLEAN, single).toList())
        assertEquals(emptyList<String>(), settings.buildArguments(DotNetBuildCommand.RESTORE, sln).toList())
    }

    fun testRunCommandLineUsesTheSelection() {
        if (DotNetCli.findExecutable() == null) return
        val configuration = RunManager.getInstance(project).createConfiguration("t", DotNetConfigurationType.instance.factory).configuration as DotNetRunConfiguration
        configuration.options.projectPath = "C:/src/App/App.csproj"
        DotNetBuildSettings.getInstance(project).configuration = "Release"

        assertEquals(listOf("run", "--project", "C:/src/App/App.csproj", "-c", "Release"), configuration.buildCommandLine().parametersList.list)
        configuration.options.command = DotNetCommand.WATCH
        assertEquals(listOf("watch", "--project", "C:/src/App/App.csproj", "run", "-c", "Release"), configuration.buildCommandLine().parametersList.list)
        configuration.options.command = DotNetCommand.TEST
        assertEquals(listOf("test", "C:/src/App/App.csproj", "-c", "Release"), configuration.buildCommandLine().parametersList.list)
    }

    fun testRegistration() {
        val actionManager = ActionManager.getInstance()
        assertNotNull(actionManager.getAction("DotNet.BuildConfiguration"))
        assertNotNull(actionManager.getAction("DotNet.AnalyzeStackTrace"))
        // the selector sits in the toolbar of the new UI
        val toolbar = actionManager.getAction("MainToolbarRight") as DefaultActionGroup
        assertTrue(toolbar.getChildActionsOrStubs().any { actionManager.getId(it) == "DotNet.BuildConfiguration" })
        // .NET frames are links in every console, the stack trace analyzer included
        assertTrue(ConsoleFilterProvider.FILTER_PROVIDERS.extensionList.any { it is DotNetConsoleFilterProvider })
    }
}
