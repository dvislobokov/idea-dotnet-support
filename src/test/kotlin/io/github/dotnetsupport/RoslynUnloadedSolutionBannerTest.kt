package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.roslyn.RoslynUnloadedSolutionBanner
import io.github.dotnetsupport.solution.SolutionService

class RoslynUnloadedSolutionBannerTest : BasePlatformTestCase() {
    private val solutions = mapOf(
        "/w/Main.sln" to listOf("/w/Console/Console.csproj", "/w/Lib/Lib.csproj"),
        "/w/Legacy/Legacy.sln" to listOf("/w/Legacy/Old.csproj"),
        "/w/ShopApi/ShopApi.sln" to listOf("/w/ShopApi/ShopApi.csproj", "/w/Lib/Lib.csproj"),
    )

    fun testAProjectOfTheLoadedSolutionHasNoBanner() {
        assertNull(RoslynUnloadedSolutionBanner.solutionToLoad("/w/Console/Console.csproj", "/w/Main.sln", solutions))
        // in the loaded solution and in another one too: the loaded one serves it
        assertNull(RoslynUnloadedSolutionBanner.solutionToLoad("/w/Lib/Lib.csproj", "/w/Main.sln", solutions))
    }

    fun testAProjectOfAnotherSolutionNamesIt() {
        assertEquals("/w/ShopApi/ShopApi.sln", RoslynUnloadedSolutionBanner.solutionToLoad("/w/ShopApi/ShopApi.csproj", "/w/Main.sln", solutions))
        assertEquals("/w/Main.sln", RoslynUnloadedSolutionBanner.solutionToLoad("/w/Console/Console.csproj", "/w/ShopApi/ShopApi.sln", solutions))
    }

    fun testAProjectOfNoSolutionHasNoBanner() {
        assertNull(RoslynUnloadedSolutionBanner.solutionToLoad("/w/Tools/Tool.csproj", "/w/Main.sln", solutions))
    }

    fun testPathsAreComparedAsPathsOfTheSystem() {
        val expected = if (com.intellij.openapi.util.SystemInfo.isFileSystemCaseSensitive) null else "/w/ShopApi/ShopApi.sln"
        assertEquals(expected, RoslynUnloadedSolutionBanner.solutionToLoad("/w/shopapi/ShopApi.csproj", "/w/Main.sln", solutions))
    }

    fun testTheSolutionsOfTheFolderAsTheBannerSeesThem() {
        val main = myFixture.addFileToProject("unloadedSln/Main.slnx", "<Solution><Project Path=\"Console/Console.csproj\" /></Solution>").virtualFile
        myFixture.addFileToProject("unloadedSln/Console/Console.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />")
        val consoleFile = myFixture.addFileToProject("unloadedSln/Console/Program.cs", "class P {}").virtualFile
        val shop = myFixture.addFileToProject("unloadedSln/ShopApi/ShopApi.slnx", "<Solution><Project Path=\"ShopApi.csproj\" /></Solution>").virtualFile
        myFixture.addFileToProject("unloadedSln/ShopApi/ShopApi.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\" />")
        val shopFile = myFixture.addFileToProject("unloadedSln/ShopApi/Playground/Lambdas.cs", "class L {}").virtualFile
        SolutionService.getInstance(project).solutionFilesChanged()

        val found = RoslynUnloadedSolutionBanner.solutionProjects(project).filterKeys { it.contains("/unloadedSln/") }
        assertEquals(setOf(main.path, shop.path), found.keys)
        val shopProject = DotNetProjects.findOwningProject(shopFile)!!
        assertEquals(shop.path, RoslynUnloadedSolutionBanner.solutionToLoad(shopProject.path, main.path, found))
        assertNull(RoslynUnloadedSolutionBanner.solutionToLoad(DotNetProjects.findOwningProject(consoleFile)!!.path, main.path, found))
    }

    fun testNoServerNoBanner() {
        val file = myFixture.addFileToProject("unloadedSln2/ShopApi/Program.cs", "class P {}").virtualFile
        myFixture.addFileToProject("unloadedSln2/ShopApi/ShopApi.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />")
        assertNull(RoslynUnloadedSolutionBanner().collectNotificationData(project, file))
    }
}
