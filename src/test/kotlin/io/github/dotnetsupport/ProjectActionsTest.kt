package io.github.dotnetsupport

import com.intellij.execution.RunManager
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.actions.RenameProjectAction
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.msbuild.MsBuildItemEditor
import io.github.dotnetsupport.run.DotNetConfigurationType
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.solution.SolutionEditor
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.DependenciesKey
import io.github.dotnetsupport.view.DependenciesNode

/** Rename Project, Add Assembly Reference, transitive project references. */
class ProjectActionsTest : BasePlatformTestCase() {
    fun testSolutionEditorRenamesTheProjectEntry() {
        val sln = """
            Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "App", "src\App\App.csproj", "{BBBBBBBB-0000-0000-0000-000000000001}"
            EndProject
            Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "Tests", "tests\Tests\Tests.csproj", "{BBBBBBBB-0000-0000-0000-000000000002}"
            EndProject
        """.trimIndent()
        val renamed = SolutionEditor.renameProject(sln, "sln", "src/App/App.csproj", "src/Shop/Shop.csproj", "Shop")
        assertTrue(renamed.contains("""= "Shop", "src\Shop\Shop.csproj", "{BBBBBBBB-0000-0000-0000-000000000001}""""))
        assertTrue(renamed.contains("""= "Tests", "tests\Tests\Tests.csproj""""))

        val slnx = """<Solution><Project Path="src/App/App.csproj" DisplayName="Old" /><Project Path="tests/Tests/Tests.csproj" /></Solution>"""
        assertEquals(
            """<Solution><Project Path="src/Shop/Shop.csproj" /><Project Path="tests/Tests/Tests.csproj" /></Solution>""",
            SolutionEditor.renameProject(slnx, "slnx", "src\\App\\App.csproj", "src/Shop/Shop.csproj", "Shop"),
        )
    }

    fun testItemEditor() {
        val csproj = "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <PropertyGroup>\n    <TargetFramework>net9.0</TargetFramework>\n  </PropertyGroup>\n</Project>\n"
        assertEquals(
            "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <PropertyGroup>\n    <TargetFramework>net9.0</TargetFramework>\n  </PropertyGroup>\n\n" +
                "  <ItemGroup>\n    <Reference Include=\"Vendor.Lib\">\n      <HintPath>..\\libs\\Vendor.Lib.dll</HintPath>\n    </Reference>\n  </ItemGroup>\n\n</Project>\n",
            MsBuildItemEditor.addAssemblyReferences(csproj, listOf("../libs/Vendor.Lib.dll")),
        )
        val references = """<ItemGroup><ProjectReference Include="..\App\App.csproj" /><ProjectReference Include="../Core/Core.csproj" /></ItemGroup>"""
        assertEquals(
            """<ItemGroup><ProjectReference Include="..\Shop\Shop.csproj" /><ProjectReference Include="../Core/Core.csproj" /></ItemGroup>""",
            MsBuildItemEditor.renameProjectReference(references, "../App/App.csproj", "../Shop/Shop.csproj"),
        )
    }

    fun testRenameProjectMovesEverythingAlong() {
        val app = myFixture.addFileToProject("ren/src/App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><OutputType>Exe</OutputType></PropertyGroup></Project>").virtualFile
        val tests = myFixture.addFileToProject("ren/tests/Tests/Tests.csproj", """<Project Sdk="Microsoft.NET.Sdk"><ItemGroup><ProjectReference Include="..\..\src\App\App.csproj" /></ItemGroup></Project>""").virtualFile
        val sln = myFixture.addFileToProject("ren/Ren.slnx", """<Solution><Project Path="src/App/App.csproj" /><Project Path="tests/Tests/Tests.csproj" /></Solution>""").virtualFile
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration("App", DotNetConfigurationType::class.java)
        (settings.configuration as DotNetRunConfiguration).options.projectPath = app.path
        runManager.addConfiguration(settings)
        try {
            val slnProject = SolutionService.getInstance(project).solution(sln).allProjects.first { it.name == "App" }
            RenameProjectAction.rename(project, SolutionContext(sln, project = slnProject), "Shop", renameDirectory = true)

            assertEquals("Shop.csproj", app.name)
            assertEquals("src/Shop/Shop.csproj", VfsUtilCore.getRelativePath(app, sln.parent, '/'))
            assertEquals(listOf("Shop", "Tests"), SolutionService.getInstance(project).solution(sln).allProjects.map { it.name })
            assertTrue(VfsUtilCore.loadText(tests).contains("""Include="..\..\src\Shop\Shop.csproj""""))
            assertEquals(app.path, (settings.configuration as DotNetRunConfiguration).options.projectPath)
            assertEquals("Shop", settings.name)
        } finally {
            runManager.removeConfiguration(settings)
        }
    }

    fun testTransitiveProjectReferences() {
        myFixture.addFileToProject("tr/App/App.csproj", """<Project Sdk="Microsoft.NET.Sdk"><ItemGroup><ProjectReference Include="..\Core\Core.csproj" /></ItemGroup></Project>""")
        myFixture.addFileToProject("tr/Core/Core.csproj", """<Project Sdk="Microsoft.NET.Sdk"><ItemGroup><ProjectReference Include="..\Shared\Shared.csproj" /><ProjectReference Include="..\App\App.csproj" /></ItemGroup></Project>""")
        myFixture.addFileToProject("tr/Shared/Shared.csproj", """<Project Sdk="Microsoft.NET.Sdk"/>""")
        val app = myFixture.findFileInTempDir("tr/App/App.csproj")

        val dependencies = DependenciesNode(project, DependenciesKey(app), ViewSettings.DEFAULT)
        val projects = dependencies.children.first { it.describe() == "Projects" }
        val core = projects.children.single()
        assertEquals("Core", core.describe())
        // Shared comes through Core; App itself, referenced back by Core, is not shown again
        assertEquals(listOf("Shared"), core.children.map { it.describe() })
        assertTrue(core.children.single().canNavigate())
    }

    private fun AbstractTreeNode<*>.describe(): String {
        update()
        return presentation.presentableText.orEmpty()
    }
}
