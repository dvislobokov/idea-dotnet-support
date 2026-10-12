package io.github.dotnetsupport

import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.build.BuildPerformance
import io.github.dotnetsupport.build.MsBuildTargets
import io.github.dotnetsupport.testing.TestExplorerModel
import io.github.dotnetsupport.view.SolutionKey
import io.github.dotnetsupport.view.SolutionNode
import io.github.dotnetsupport.view.SolutionViewSettings

class RiderPanelsMoreTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            SolutionViewSettings.setShowAllFiles(project, false)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testBuildPerformanceSummary() {
        // as printed by `dotnet build -clp:PerformanceSummary` with DOTNET_CLI_UI_LANGUAGE=en
        val performance = BuildPerformance.parse(
            listOf(
                "  App -> C:\\src\\App\\bin\\Debug\\net9.0\\App.dll",
                "",
                "Project Evaluation Performance Summary:",
                "       93 ms  C:\\src\\App\\App.csproj   2 calls",
                "",
                "Project Performance Summary:",
                "      754 ms  C:\\src\\App\\App.csproj   9 calls",
                "                  2 ms  _GenerateRestoreProjectPathWalk            1 calls",
                "                 63 ms  _GenerateRestoreGraphProjectEntry          1 calls",
                "     1200 ms  C:\\src\\Core\\Core.csproj   3 calls",
                "",
                "Target Performance Summary:",
                "        0 ms  BeforeRebuild                              1 calls",
                "      318 ms  CoreCompile                                2 calls",
                "       41 ms  ResolvePackageAssets                       2 calls",
                "",
                "Task Performance Summary:",
                "      310 ms  Csc                                        2 calls",
                "        1 ms  Message                                    5 calls",
                "",
                "Build succeeded.",
            ).joinToString("\r\n")
        )
        assertEquals(listOf("CoreCompile" to 318L, "ResolvePackageAssets" to 41L, "BeforeRebuild" to 0L), performance.targets.map { it.name to it.milliseconds })
        assertEquals(listOf("Csc", "Message"), performance.tasks.map { it.name })
        assertEquals(2, performance.targets.first().calls)
        // the targets listed under each project are not projects; evaluation is a separate section
        assertEquals(listOf("C:\\src\\Core\\Core.csproj", "C:\\src\\App\\App.csproj"), performance.projects.map { it.name })
        assertTrue(BuildPerformance.parse("Build succeeded.").isEmpty)
    }

    fun testMsBuildTargets() {
        val own = MsBuildTargets.declaredIn(
            """
            <Project Sdk="Microsoft.NET.Sdk">
              <Target Name="GenerateClient" BeforeTargets="CoreCompile"><Exec Command="npm run gen" /></Target>
              <Target AfterTargets="Build" Name="CopyDocs" />
            </Project>
            """.trimIndent()
        )
        assertEquals(listOf("GenerateClient", "CopyDocs"), own)

        val targets = MsBuildTargets.arrange("_CheckForInvalidConfiguration\r\nBuild\r\nCopyDocs\r\nClean\r\n_CopyFiles\r\nGenerateClient\r\nBuild\r\nwarning MSB0001: not a target\r\n", own)
        // the ones of the repository first, SDK internals last
        assertEquals(listOf("CopyDocs", "GenerateClient", "Build", "Clean", "_CheckForInvalidConfiguration", "_CopyFiles"), targets.map { it.name })
        assertEquals(listOf(true, true, false, false, false, false), targets.map { it.isOwn })

        val projectFile = myFixture.addFileToProject("src/App/App.csproj", "<Project><Target Name=\"Own\"/></Project>").virtualFile
        myFixture.addFileToProject("Directory.Build.targets", "<Project><Target Name=\"Shared\"/></Project>")
        assertEquals(listOf("Own", "Shared"), MsBuildTargets.ownTargets(projectFile))
    }

    fun testTestExplorerModel() {
        val testSdk = "<ItemGroup><PackageReference Include=\"Microsoft.NET.Test.Sdk\" Version=\"17.0.0\"/></ItemGroup>"
        myFixture.addFileToProject("App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        myFixture.addFileToProject("App/NotATest.cs", "class A { [Fact] public void LooksLikeATest() {} }")
        myFixture.addFileToProject("Tests/Tests.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\">$testSdk</Project>")
        myFixture.addFileToProject("Tests/OrderTests.cs", "namespace Shop.Tests;\npublic class OrderTests {\n  [Fact] public void Adds() {}\n  [Fact] public void Removes() {}\n}")
        myFixture.addFileToProject("Tests/Nested/CartTests.cs", "namespace Shop.Tests.Nested;\npublic class CartTests { [Test] public void Empty() {} }")
        myFixture.addFileToProject("Tests/obj/Generated.cs", "class G { [Fact] public void Generated() {} }")
        myFixture.addFileToProject("All.slnx", "<Solution><Project Path=\"App/App.csproj\"/><Project Path=\"Tests/Tests.csproj\"/></Solution>")

        // only test projects, without build output
        val tests = TestExplorerModel.discover(project).single()
        assertEquals("Tests", tests.name)
        assertEquals(
            listOf("Shop.Tests.Nested.CartTests" to listOf("Empty"), "Shop.Tests.OrderTests" to listOf("Adds", "Removes")),
            tests.classes.map { (type, methods) -> type.target.className to methods.map { it.target.methodName } },
        )
        val adds = tests.tests.first { it.target.methodName == "Adds" }
        assertEquals("OrderTests.cs", adds.file.name)

        val selection = tests.tests.filter { it.target.methodName in setOf("Adds", "Empty") }.map { it.target }
        // one `--filter` expression with the alternatives joined by "|"; their order follows the selection
        assertEquals(
            setOf("FullyQualifiedName~Shop.Tests.OrderTests.Adds", "FullyQualifiedName~Shop.Tests.Nested.CartTests.Empty"),
            TestExplorerModel.filter(selection)!!.split('|').toSet(),
        )
        assertNull(TestExplorerModel.filter(emptyList()))
    }

    fun testShowAllFiles() {
        myFixture.addFileToProject("App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        myFixture.addFileToProject("App/Program.cs", "")
        myFixture.addFileToProject("App/bin/Debug/App.txt", "")
        myFixture.addFileToProject("App/obj/project.assets.json", "{}")
        val sln = myFixture.addFileToProject("App.slnx", "<Solution><Project Path=\"App/App.csproj\"/></Solution>").virtualFile

        fun children(): List<String> = SolutionNode(project, SolutionKey(sln), ViewSettings.DEFAULT).children.single().children
            .map { node: AbstractTreeNode<*> -> node.update(); node.presentation.presentableText ?: node.name.orEmpty() }.sorted()

        assertEquals(listOf("Dependencies", "Program.cs"), children())
        SolutionViewSettings.setShowAllFiles(project, true)
        assertEquals(listOf("App.csproj", "Dependencies", "Program.cs", "bin", "obj"), children())

        // what Show All Files adds is painted as ignored: build output with its content, and the project file
        val app = sln.parent.findChild("App")!!
        fun outside(path: String) = io.github.dotnetsupport.view.BuildOutputDecorator.isOutsideOfProject(app.findFileByRelativePath(path)!!)
        assertTrue(outside("bin") && outside("bin/Debug/App.txt") && outside("obj/project.assets.json") && outside("App.csproj"))
        assertFalse(outside("Program.cs"))
        // a folder that is merely called bin, without a project next to it
        val tools = myFixture.addFileToProject("App/Tools/bin/run.sh", "").virtualFile
        assertFalse(io.github.dotnetsupport.view.BuildOutputDecorator.isOutsideOfProject(tools))
    }

    fun testRegistration() {
        val actionManager = ActionManager.getInstance()
        for (id in listOf("DotNet.RunMsBuildTarget", "DotNet.BuildPerformance", "DotNet.ShowAllFiles")) assertNotNull(id, actionManager.getAction(id))
        val viewOptions = actionManager.getAction("ProjectView.ToolWindow.SecondaryActions") as DefaultActionGroup
        assertTrue(viewOptions.getChildActionsOrStubs().any { actionManager.getId(it) == "DotNet.ShowAllFiles" })
    }
}
