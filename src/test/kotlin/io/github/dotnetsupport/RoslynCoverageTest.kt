package io.github.dotnetsupport

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFeatures
import io.github.dotnetsupport.lsp.RoslynCoverage
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynServerStatus
import io.github.dotnetsupport.roslyn.RoslynFeatures
import io.github.dotnetsupport.solution.SolutionService

/**
 * Which files the loaded language server knows: those of the projects of its solution, not every file under the folder of the solution
 * (`ShopApi/` under the root of `debug-playground`, next to `DebugPlayground.sln`). The rest is the plugin's, as with the server off.
 */
class RoslynCoverageTest : BasePlatformTestCase() {
    private val status get() = project.getService(RoslynServerStatus::class.java)

    override fun tearDown() {
        try {
            // the light project is shared with the other tests of the class and between classes
            status.isReady = false
            status.loaded(null)
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            RoslynLanguageServerSettings.getInstance().state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
        } finally {
            super.tearDown()
        }
    }

    fun testTheRule() {
        val loaded = listOf("/w/Console/Console.csproj", "/w/Lib/Lib.csproj")
        assertTrue("a project of the solution", RoslynCoverage.covers("/w/Console/Console.csproj", loaded))
        assertFalse("a project of another solution under the same folder", RoslynCoverage.covers("/w/ShopApi/ShopApi.csproj", loaded))
        assertFalse("a file of no project", RoslynCoverage.covers(null, loaded))
        assertFalse("nothing loaded", RoslynCoverage.covers("/w/Console/Console.csproj", null as Collection<String>?))
    }

    fun testTheProjectsOfTheLoadedSolutionNotItsFolder() {
        val main = myFixture.addFileToProject("coverage/Main.slnx", "<Solution><Project Path=\"Console/Console.csproj\" /></Solution>").virtualFile
        myFixture.addFileToProject("coverage/Console/Console.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />")
        val consoleFile = myFixture.addFileToProject("coverage/Console/Program.cs", "class P {}").virtualFile
        myFixture.addFileToProject("coverage/ShopApi/ShopApi.slnx", "<Solution><Project Path=\"ShopApi.csproj\" /></Solution>")
        myFixture.addFileToProject("coverage/ShopApi/ShopApi.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\" />")
        val shopFile = myFixture.addFileToProject("coverage/ShopApi/Playground/Lambdas.cs", "class L {}").virtualFile
        val looseFile = myFixture.addFileToProject("coverage/Loose.cs", "class Loose {}").virtualFile

        status.loaded(main.path, file = main)
        assertFalse("not ready: nobody's", RoslynServerStatus.covers(project, consoleFile))
        assertFalse(RoslynServerStatus.outside(project, shopFile))
        status.isReady = true
        assertTrue(RoslynServerStatus.covers(project, consoleFile))
        assertFalse(RoslynServerStatus.outside(project, consoleFile))
        assertFalse("a project of another solution under the folder of the loaded one", RoslynServerStatus.covers(project, shopFile))
        assertTrue(RoslynServerStatus.outside(project, shopFile))
        assertFalse("a ready server, but not for this file", RoslynServerStatus.isReady(project, shopFile))
        assertTrue(RoslynServerStatus.isReady(project, consoleFile))
        assertFalse("a file of no project", RoslynServerStatus.covers(project, looseFile))
        assertTrue(RoslynServerStatus.outside(project, looseFile))

        // the solution gets the project: read anew, the answer cached per file follows
        runWriteAction { VfsUtil.saveText(main, "<Solution><Project Path=\"Console/Console.csproj\" /><Project Path=\"ShopApi/ShopApi.csproj\" /></Solution>") }
        FileDocumentManager.getInstance().saveAllDocuments()
        assertTrue(RoslynServerStatus.covers(project, shopFile))
        // Reload Solution forgets as well
        SolutionService.getInstance(project).reload()
        assertTrue(RoslynServerStatus.covers(project, consoleFile))

        // another solution loaded: the other way round
        val shop = myFixture.findFileInTempDir("coverage/ShopApi/ShopApi.slnx")
        status.loaded(shop.path, file = shop)
        assertTrue(RoslynServerStatus.covers(project, shopFile))
        assertFalse(RoslynServerStatus.covers(project, consoleFile))
    }

    fun testProjectsWithoutSolutionAndLooseFiles() {
        val project1 = myFixture.addFileToProject("coverage2/A/A.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />").virtualFile
        val a = myFixture.addFileToProject("coverage2/A/A.cs", "class A {}").virtualFile
        myFixture.addFileToProject("coverage2/B/B.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />")
        val b = myFixture.addFileToProject("coverage2/B/B.cs", "class B {}").virtualFile
        status.isReady = true
        // loose files only: the miscellaneous files of the server are all there is, as before
        assertFalse(RoslynServerStatus.covers(project, a))
        assertFalse(RoslynServerStatus.outside(project, a))
        status.loaded(null, listOf(project1.path))
        assertTrue(RoslynServerStatus.covers(project, a))
        assertTrue(RoslynServerStatus.outside(project, b))
    }

    /** A file the server does not know is served by the plugin even where the switch leaves the feature to the server. */
    fun testTheSwitchToTheServerDoesNotHoldForAFileItDoesNotKnow() {
        val settings = RoslynLanguageServerSettings.getInstance()
        settings.state.enabled = true
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.ROSLYN)
        val main = myFixture.addFileToProject("coverage3/Main.slnx", "<Solution><Project Path=\"Console/Console.csproj\" /></Solution>").virtualFile
        myFixture.addFileToProject("coverage3/Console/Console.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />")
        val console = myFixture.addFileToProject("coverage3/Console/Program.cs", "class P {}")
        myFixture.addFileToProject("coverage3/ShopApi/ShopApi.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\" />")
        val shop = myFixture.addFileToProject("coverage3/ShopApi/Lambdas.cs", "class L {}")
        status.loaded(main.path, file = main)

        assertFalse("loading: the switch decides", CSharpFeatures.native(CSharpFeature.COMPLETION, shop))
        status.isReady = true
        assertFalse(CSharpFeatures.native(CSharpFeature.COMPLETION, console))
        assertTrue(CSharpFeatures.native(CSharpFeature.COMPLETION, shop))
        // the side of the server: it answers for the one and is not asked about the other
        assertTrue(RoslynFeatures.serves(CSharpFeature.COMPLETION, console))
        assertFalse(RoslynFeatures.serves(CSharpFeature.COMPLETION, shop))
        assertFalse(RoslynFeatures.knows(project, shop.virtualFile))
    }
}
