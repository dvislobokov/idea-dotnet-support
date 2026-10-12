package io.github.dotnetsupport

import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.msbuild.MsBuildGlob
import io.github.dotnetsupport.msbuild.MsBuildProject
import io.github.dotnetsupport.msbuild.ProjectContent
import io.github.dotnetsupport.view.DependentFilesNode
import io.github.dotnetsupport.view.LinkedFileNode
import io.github.dotnetsupport.view.LinkedFolderNode
import io.github.dotnetsupport.view.SolutionKey
import io.github.dotnetsupport.view.SolutionNode
import io.github.dotnetsupport.view.SolutionTreeStructure
import io.github.dotnetsupport.view.SolutionViewSettings

/** The content of a project as the SDK sees it: `Remove`, `DefaultItemExcludes`, `DependentUpon`, `Link`. */
class ProjectContentTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            SolutionViewSettings.setShowAllFiles(project, false)
        } finally {
            super.tearDown()
        }
    }

    fun testGlobs() {
        assertTrue(MsBuildGlob("Legacy\\**").matches("Legacy/Old/File.cs"))
        assertTrue(MsBuildGlob("Legacy/**").coversDirectory("Legacy"))
        assertFalse(MsBuildGlob("Legacy/**").coversDirectory("Legacy/Old"))
        assertTrue(MsBuildGlob("**/*.bak").matches("a/b/c.bak"))
        assertTrue(MsBuildGlob("**/*.bak").matches("c.bak"))
        assertFalse(MsBuildGlob("**/*.bak").matches("c.bak.txt"))
        assertTrue(MsBuildGlob("Data/*.json").matches("data/settings.JSON"))
        assertFalse(MsBuildGlob("Data/*.json").matches("Data/sub/settings.json"))
        assertTrue(MsBuildGlob("File?.cs").matches("File1.cs"))
        assertEquals("../Shared/Utils", MsBuildGlob("..\\Shared\\Utils\\**\\*.cs").fixedDirectory)
        assertEquals("../Shared", MsBuildGlob("../Shared/One.cs").fixedDirectory)
        assertFalse(MsBuildGlob("../Shared/One.cs").hasWildcards)
    }

    fun testProjectFileItems() {
        val project = MsBuildProject.parse(
            """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup>
                <DefaultItemExcludes>${'$'}(DefaultItemExcludes);scratch\**</DefaultItemExcludes>
              </PropertyGroup>
              <ItemGroup>
                <Compile Remove="Legacy\**" />
                <None Remove="Legacy\**;notes.txt" />
                <Compile Include="..\Shared\Utils\**\*.cs" LinkBase="Shared" />
                <Compile Include="..\Shared\One.cs" Link="Ext\One.cs" />
                <None Include="..\..\LICENSE" />
                <Compile Update="Form1.Designer.cs"><DependentUpon>Form1.cs</DependentUpon></Compile>
                <Compile Include="Data\Model.Generated.cs" DependentUpon="Model.tt" />
              </ItemGroup>
            </Project>
            """.trimIndent(),
        )
        val content = ProjectContent(project)
        assertTrue(content.isExcluded("Legacy/Old.cs"))
        assertTrue(content.isExcluded("Legacy/readme.md"))
        assertTrue(content.isExcludedDirectory("Legacy"))
        assertTrue(content.isExcluded("notes.txt"))
        assertTrue(content.isExcluded("scratch/x.cs"))
        assertTrue(content.isExcludedDirectory("scratch"))
        assertFalse(content.isExcluded("Program.cs"))
        // removed from Compile only: still a None item of the SDK, so it stays in the tree
        assertFalse(ProjectContent(MsBuildProject.parse("""<Project><ItemGroup><Compile Remove="Gen\**" /></ItemGroup></Project>""")).isExcluded("Gen/readme.md"))
        assertTrue(ProjectContent(MsBuildProject.parse("""<Project><ItemGroup><Compile Remove="Gen\**" /></ItemGroup></Project>""")).isExcluded("Gen/a.cs"))

        assertEquals("Form1.cs", content.dependentParent("form1.designer.cs"))
        assertEquals("Model.tt", content.dependentParent("Data/Model.Generated.cs"))
        assertNull(content.dependentParent("Form1.cs"))
        assertEquals(listOf("Compile", "Compile", "None"), project.linkedItems.map { it.itemType })
    }

    fun testHiddenAndNestedFilesInTheTree() {
        // the pairs the nesting rules of the plugin already know (Form1.cs + Form1.Designer.cs) are not the point: DependentUpon nests what no rule does
        for (path in listOf(
            "App/Program.cs", "App/Legacy/Old.cs", "App/Legacy/notes.md", "App/scratch/tmp.cs", "App/backup.bak",
            "App/Schema.xsd", "App/Schema.Designer.cs", "App/Forms/Login.cs", "App/Forms/Login.resx", "App/Forms/Login.ru.resx",
        )) myFixture.addFileToProject(path, "")
        myFixture.addFileToProject(
            "App/App.csproj",
            """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup><DefaultItemExcludes>${'$'}(DefaultItemExcludes);scratch\**;*.bak</DefaultItemExcludes></PropertyGroup>
              <ItemGroup>
                <Compile Remove="Legacy\**" /><None Remove="Legacy\**" />
                <Compile Update="Schema.Designer.cs"><DependentUpon>Schema.xsd</DependentUpon></Compile>
                <EmbeddedResource Update="Forms\Login.ru.resx"><DependentUpon>Login.resx</DependentUpon></EmbeddedResource>
              </ItemGroup>
            </Project>
            """.trimIndent(),
        )
        val sln = myFixture.addFileToProject("App.slnx", """<Solution><Project Path="App/App.csproj" /></Solution>""").virtualFile

        val structure = SolutionTreeStructure(project)
        val projectNode = SolutionNode(project, SolutionKey(sln), structure).children.single()
        assertEquals(listOf("Dependencies", "Forms", "Program.cs", "Schema.xsd"), names(structure, projectNode))
        val schema = child(structure, projectNode, "Schema.xsd")
        assertTrue(schema is DependentFilesNode)
        assertEquals(listOf("Schema.Designer.cs"), names(structure, schema))
        // in a folder too
        val forms = child(structure, projectNode, "Forms")
        assertEquals(listOf("Login.cs", "Login.resx"), names(structure, forms))
        assertEquals(listOf("Login.ru.resx"), names(structure, child(structure, forms, "Login.resx")))

        // Show All Files brings everything back, as files of the disk
        SolutionViewSettings.setShowAllFiles(project, true)
        assertEquals(listOf("App.csproj", "Dependencies", "Forms", "Legacy", "Program.cs", "Schema.xsd", "backup.bak", "scratch"), names(structure, projectNode))
    }

    fun testLinkedFilesAppearWhereTheirLinkSays() {
        myFixture.addFileToProject("Shared/Utils/Strings.cs", "")
        myFixture.addFileToProject("Shared/Utils/Deep/Math.cs", "")
        myFixture.addFileToProject("Shared/Utils/readme.md", "")
        myFixture.addFileToProject("Shared/One.cs", "")
        myFixture.addFileToProject("LICENSE", "")
        myFixture.addFileToProject("Lnk/Program.cs", "")
        myFixture.addFileToProject(
            "Lnk/Lnk.csproj",
            """
            <Project Sdk="Microsoft.NET.Sdk">
              <ItemGroup>
                <Compile Include="..\Shared\Utils\**\*.cs" LinkBase="Shared" />
                <Compile Include="..\Shared\One.cs" Link="Ext\One.cs" />
                <None Include="..\LICENSE" />
              </ItemGroup>
            </Project>
            """.trimIndent(),
        )
        val sln = myFixture.addFileToProject("Lnk.slnx", """<Solution><Project Path="Lnk/Lnk.csproj" /></Solution>""").virtualFile

        val structure = SolutionTreeStructure(project)
        val projectNode = SolutionNode(project, SolutionKey(sln), structure).children.single()
        assertEquals(listOf("Dependencies", "Ext", "LICENSE", "Program.cs", "Shared"), names(structure, projectNode))
        val license = child(structure, projectNode, "LICENSE")
        assertTrue(license is LinkedFileNode)
        assertEquals("../LICENSE", license.presentation.locationString)

        val shared = child(structure, projectNode, "Shared")
        assertTrue(shared is LinkedFolderNode)
        assertEquals(listOf("Deep", "Strings.cs"), names(structure, shared))
        assertEquals(listOf("Math.cs"), names(structure, child(structure, shared, "Deep")))
        assertEquals(listOf("One.cs"), names(structure, child(structure, projectNode, "Ext")))
    }

    private fun names(structure: SolutionTreeStructure, node: Any): List<String> = structure.getChildElements(node).map { describe(it) }.sorted()

    private fun child(structure: SolutionTreeStructure, node: Any, name: String): AbstractTreeNode<*> =
        structure.getChildElements(node).map { it as AbstractTreeNode<*> }.first { describe(it) == name }

    private fun describe(node: Any): String {
        val treeNode = node as AbstractTreeNode<*>
        treeNode.update()
        return treeNode.presentation.presentableText ?: treeNode.name.orEmpty()
    }
}
