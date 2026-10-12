package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.msbuild.EvaluatedFiles
import io.github.dotnetsupport.msbuild.MsBuildEvaluation
import io.github.dotnetsupport.msbuild.MsBuildEvaluationResult
import io.github.dotnetsupport.msbuild.MsBuildItem
import io.github.dotnetsupport.msbuild.MsBuildProject
import io.github.dotnetsupport.view.LinkedFileNode
import io.github.dotnetsupport.view.SolutionKey
import io.github.dotnetsupport.view.SolutionNode
import io.github.dotnetsupport.view.SolutionTreeStructure
import io.github.dotnetsupport.view.SolutionViewSettings

/**
 * MsBuildHost on the side of the plugin: its answers (saved from the real helper, `src/test/resources/msbuild`, for a project of the old
 * format in `C:\src\Legacy`) and the Solution view of such a project. The helper itself is never started here.
 */
class MsBuildEvaluationTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            SolutionViewSettings.setShowAllFiles(project, false)
        } finally {
            super.tearDown()
        }
    }

    private fun answer(name: String): MsBuildEvaluationResult =
        MsBuildEvaluationResult.parse(JsonParser.parseString(javaClass.getResourceAsStream("/msbuild/$name")!!.readBytes().toString(Charsets.UTF_8)))

    fun testTheAnswerOfALegacyProject() {
        val targetPath = answer("legacy-targetpath.json")
        assertEquals("C:\\src\\Legacy\\bin\\CustomDebug\\Legacy.exe", targetPath.property("TargetPath"))
        assertEquals(listOf("net472"), targetPath.targetFrameworks)
        // the targets of Visual Studio for web applications are not in the SDK: evaluated without them, and told so
        assertTrue(targetPath.warning!!.contains("Microsoft.WebApplication.targets"))
        assertTrue(targetPath.imports.any { it.endsWith("Microsoft.CSharp.targets") })

        val items = answer("legacy-evaluate.json").items
        assertEquals(6, items.getValue("Compile").size)
        assertEquals(MsBuildItem("C:\\src\\Legacy\\Form1.Designer.cs", mapOf("DependentUpon" to "Form1.cs")), items.getValue("Compile")[2])
        assertEquals(listOf("C:\\src\\Legacy\\Web.config", "C:\\src\\Legacy\\Views\\Index.cshtml"), items.getValue("Content").map { it.include })
        // a condition that is false for Debug: packages.config is not there
        assertEquals(emptyList<MsBuildItem>(), items.getValue("None"))
        assertNull(MsBuildEvaluationResult.parse(JsonParser.parseString("""{"properties":{"TargetPath":""}}""")).property("TargetPath"))
        assertEquals(MsBuildEvaluationResult(), MsBuildEvaluationResult.parse(null))
    }

    fun testTheFilesOfALegacyProject() {
        val files = EvaluatedFiles.of("C:/src/Legacy", answer("legacy-evaluate.json"))
        assertTrue(files.contains("Program.cs"))
        assertTrue(files.contains("properties/assemblyinfo.cs"))
        assertTrue(files.contains("Views\\Index.cshtml"))
        // listed though not on disk: the include stays relative and is still a file of the project
        assertTrue(files.contains("Missing.cs"))
        assertFalse(files.contains("Stray.cs"))
        assertFalse(files.contains("Old/NotInProject.cs"))
        assertTrue(files.containsDirectory("Properties"))
        assertTrue(files.containsDirectory("Views/"))
        assertFalse(files.containsDirectory("Old"))
        assertEquals("Form1.cs", files.dependentParent("Form1.Designer.cs"))
        assertEquals("Form1.cs", files.dependentParent("form1.resx"))
        assertEquals(listOf(EvaluatedFiles.Linked("Shared/Util.cs", "C:/src/Shared/Util.cs")), files.linked)
    }

    fun testRequestsAndPaths() {
        val request = MsBuildEvaluationResult.request("C:/src/App/App.csproj", mapOf("Configuration" to "Debug", "Platform" to ""), listOf("TargetPath"), listOf("Compile"))
        assertEquals(
            """{"projectPath":"C:/src/App/App.csproj","globalProperties":{"Configuration":"Debug"},"properties":["TargetPath"],"itemTypes":["Compile"]}""",
            request.toString(),
        )
        assertEquals(mapOf("Configuration" to "Release", "Platform" to "x64"), MsBuildEvaluationResult.globalProperties("Configuration=Release; Platform = x64;=x;junk"))
        assertEquals("C:/a/c", EvaluatedFiles.normalize("C:\\a\\b\\..\\c"))
        assertEquals("C:/c", EvaluatedFiles.normalize("C:/../c"))
        assertEquals("/src/x/", EvaluatedFiles.normalize("/src/./x/"))
        assertEquals("../Shared/One.cs", EvaluatedFiles.normalize("..\\Shared\\One.cs"))
    }

    fun testWhatChangesAnEvaluation() {
        val imports = setOf("c:/program files/dotnet/sdk/10.0.401/microsoft.common.props")
        fun depends(changed: String) = MsBuildEvaluation.dependsOn("C:/src/Legacy/Legacy.csproj", imports, changed.lowercase())
        assertTrue(depends("C:/src/Legacy/Legacy.csproj"))
        assertTrue(depends("C:/Program Files/dotnet/sdk/10.0.401/Microsoft.Common.props"))
        assertTrue(depends("C:/src/Legacy/Sub/New.cs")) // a wildcard may take it
        assertTrue(depends("C:/src/Directory.Build.props")) // appeared above the project
        assertFalse(depends("C:/src/Directory.Build.txt"))
        assertFalse(depends("C:/src/Legacy/bin/Debug/Legacy.exe")) // a build does not change the project
        assertFalse(depends("C:/src/Legacy/obj/x.cache"))
        assertFalse(depends("C:/src/Other/Other.csproj"))
    }

    fun testWhichProjectsAreLegacy() {
        assertTrue(MsBuildProject.parse("""<Project ToolsVersion="15.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003"><ItemGroup><Compile Include="A.cs" /></ItemGroup></Project>""").isLegacy)
        assertFalse(MsBuildProject.parse("""<Project Sdk="Microsoft.NET.Sdk" />""").isLegacy)
        assertFalse(MsBuildProject.parse("""<Project><Import Project="Sdk.props" Sdk="Microsoft.NET.Sdk" /></Project>""").isLegacy)
        assertFalse(MsBuildProject.parse("""<Project><Sdk Name="Microsoft.NET.Sdk" /></Project>""").isLegacy)
    }

    /** Without the helper (as in every test, and when it fails) the debug launch falls back to `dotnet msbuild -getProperty`. */
    fun testNoTargetPathWithoutTheHelper() {
        val projectFile = myFixture.addFileToProject("NoHelper/NoHelper.csproj", """<Project Sdk="Microsoft.NET.Sdk" />""").virtualFile
        assertNull(MsBuildEvaluation.getInstance(project).targetPath(projectFile, "Debug", null))
    }

    fun testALegacyProjectShowsTheFilesItLists() {
        for (path in listOf("Leg/Program.cs", "Leg/Form1.cs", "Leg/Form1.Designer.cs", "Leg/Stray.cs", "Leg/Old/NotInProject.cs", "Leg/Views/Index.cshtml", "Leg/Empty/.keep", "Common/Util.cs")) {
            myFixture.addFileToProject(path, "")
        }
        val projectFile = myFixture.addFileToProject(
            "Leg/Leg.csproj",
            """
            <Project ToolsVersion="15.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
              <ItemGroup><Compile Include="Program.cs" /></ItemGroup>
            </Project>
            """.trimIndent(),
        ).virtualFile
        val sln = myFixture.addFileToProject("Leg.slnx", """<Solution><Project Path="Leg/Leg.csproj" /></Solution>""").virtualFile
        val structure = SolutionTreeStructure(project)
        val projectNode = SolutionNode(project, SolutionKey(sln), structure).children.single()
        // no answer of the helper yet: the files on disk, as before
        assertEquals(listOf("Dependencies", "Empty", "Form1.cs", "Old", "Program.cs", "Stray.cs", "Views"), names(structure, projectNode))

        val directory = projectFile.parent.path
        val root = projectFile.parent.parent.path
        val result = MsBuildEvaluationResult.parse(JsonParser.parseString(
            """
            {"items": {
              "Compile": [{"include": "$directory/Program.cs"}, {"include": "$directory/Form1.cs"},
                          {"include": "$directory/Form1.Designer.cs", "metadata": {"DependentUpon": "Form1.cs"}},
                          {"include": "$root/Common/Util.cs", "metadata": {"Link": "Shared\\Util.cs"}}],
              "Content": [{"include": "$directory/Views/Index.cshtml"}],
              "Folder": [{"include": "Empty\\"}]
            }}
            """.trimIndent(),
        ))
        val evaluation = MsBuildEvaluation.getInstance(project)
        try {
            evaluation.putForTests(projectFile, result)
            val children = SolutionNode(project, SolutionKey(sln), structure).children.single()
            assertEquals(listOf("Dependencies", "Empty", "Form1.cs", "Program.cs", "Shared", "Views"), names(structure, children))
            assertEquals(listOf("Form1.Designer.cs"), names(structure, child(structure, children, "Form1.cs")))
            assertTrue(child(structure, child(structure, children, "Shared"), "Util.cs") is LinkedFileNode)

            // Show All Files brings back what the project does not list
            SolutionViewSettings.setShowAllFiles(project, true)
            assertEquals(listOf("Dependencies", "Empty", "Form1.cs", "Leg.csproj", "Old", "Program.cs", "Shared", "Stray.cs", "Views"), names(structure, children))
        } finally {
            evaluation.putForTests(projectFile, null)
        }
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
