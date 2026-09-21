package io.github.dotnetsupport

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.msbuild.MsBuildPackageCompletionContributor
import io.github.dotnetsupport.msbuild.PackageCompletionService
import io.github.dotnetsupport.nuget.NuGetClient
import io.github.dotnetsupport.nuget.NuGetSettings

class MsBuildPackageCompletionTest : BasePlatformTestCase() {
    private val requests = ArrayList<String>()

    override fun setUp() {
        super.setUp()
        // a feed answered from memory: no HTTP in tests
        val client = NuGetClient { url, _ ->
            requests += url
            when {
                url == FEED -> """{"resources":[{"@id":"https://feed.test/query","@type":"SearchQueryService"},{"@id":"https://feed.test/flat/","@type":"PackageBaseAddress/3.0.0"}]}"""
                url.startsWith("https://feed.test/query?q=seri") -> """{"data":[
                    {"id":"Serilog","version":"4.2.0","description":"Simple .NET logging","totalDownloads":1500000000,"verified":true,"versions":[]},
                    {"id":"Serilog.AspNetCore","version":"9.0.0","description":"Serilog for ASP.NET Core","totalDownloads":400000000,"verified":true,"versions":[]}]}"""
                url.startsWith("https://feed.test/query") -> """{"data":[]}"""
                url == "https://feed.test/flat/serilog/index.json" -> """{"versions":["3.1.1","4.0.0","4.2.0","4.3.0-dev-02345"]}"""
                else -> error("unexpected request: $url")
            }
        }
        PackageCompletionService.getInstance(project).useForTests(client, listOf(FEED))
    }

    override fun tearDown() {
        try {
            PackageCompletionService.getInstance(project).useForTests(null, null)
            NuGetSettings.getInstance().loadState(NuGetSettings.Settings())
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun complete(name: String, item: String): List<String> {
        myFixture.configureByText(name, "<Project Sdk=\"Microsoft.NET.Sdk\">\n<ItemGroup>\n$item\n</ItemGroup>\n</Project>")
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testPackageIdsComeFromTheFeed() {
        // in the order of the feed, i.e. by relevance
        assertEquals(listOf("Serilog", "Serilog.AspNetCore"), complete("Ids.csproj", "<PackageReference Include=\"seri<caret>\" />"))
        assertTrue(requests.any { it.startsWith("https://feed.test/query?q=seri") && "prerelease=false" in it })
        // one character is not a query
        requests.clear()
        assertTrue(complete("Short.csproj", "<PackageReference Include=\"s<caret>\" />").isEmpty())
        assertTrue(requests.none { "query" in it })
        // other items are not packages
        assertTrue(complete("Other.csproj", "<ProjectReference Include=\"seri<caret>\" />").isEmpty())
    }

    fun testChosenPackageGetsItsLatestVersion() {
        complete("Insert.csproj", "<PackageReference Include=\"seri<caret>\" />")
        myFixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
        assertTrue(myFixture.editor.document.text, "<PackageReference Include=\"Serilog\" Version=\"4.2.0\" />" in myFixture.editor.document.text)

        // the version is there already, or is managed centrally: only the id
        complete("HasVersion.csproj", "<PackageReference Include=\"seri<caret>\" Version=\"1.0.0\" />")
        myFixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
        assertTrue(myFixture.editor.document.text, "<PackageReference Include=\"Serilog\" Version=\"1.0.0\" />" in myFixture.editor.document.text)

        myFixture.addFileToProject("Cpm/Directory.Packages.props", "<Project/>")
        val central = myFixture.addFileToProject("Cpm/App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\">\n<ItemGroup>\n<PackageReference Include=\"seri<caret>\" />\n</ItemGroup>\n</Project>")
        myFixture.configureFromExistingVirtualFile(central.virtualFile)
        myFixture.completeBasic()
        myFixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
        assertTrue(myFixture.editor.document.text, "<PackageReference Include=\"Serilog\" />" in myFixture.editor.document.text)
    }

    fun testVersionsOfThePackage() {
        // newest first, stable only
        assertEquals(listOf("4.2.0", "4.0.0", "3.1.1"), complete("Versions.csproj", "<PackageReference Include=\"Serilog\" Version=\"<caret>\" />"))
        assertEquals(listOf("4.2.0", "4.0.0"), complete("Prefix.csproj", "<PackageReference Include=\"Serilog\" Version=\"4.<caret>\" />"))
        // central package management and the metadata written as a tag
        assertEquals(listOf("4.2.0", "4.0.0", "3.1.1"), complete("Central.props", "<PackageVersion Include=\"Serilog\" Version=\"<caret>\" />"))
        assertEquals(listOf("4.2.0", "4.0.0", "3.1.1"), complete("Tag.csproj", "<PackageReference Include=\"Serilog\"><Version><caret></Version></PackageReference>"))

        // prerelease: by the setting, or once a prerelease is being typed
        // (the only variant is inserted right away)
        complete("Dash.csproj", "<PackageReference Include=\"Serilog\" Version=\"4.3.0-<caret>\" />")
        assertTrue(myFixture.editor.document.text, "Version=\"4.3.0-dev-02345\"" in myFixture.editor.document.text)
        NuGetSettings.getInstance().includePrerelease = true
        assertEquals("4.3.0-dev-02345", complete("Pre.csproj", "<PackageReference Include=\"Serilog\" Version=\"<caret>\" />").first())

        // the list of a package is asked from the feed once
        assertEquals(1, requests.count { it.endsWith("/serilog/index.json") })
        // nothing to ask without an id
        assertTrue(complete("NoId.csproj", "<PackageReference Include=\"$(Package)\" Version=\"<caret>\" />").isEmpty())
    }

    fun testDownloadsAreShort() {
        assertEquals("1.5B", MsBuildPackageCompletionContributor.downloads(1_500_000_000))
        assertEquals("400.0M", MsBuildPackageCompletionContributor.downloads(400_000_000))
        assertEquals("12.3K", MsBuildPackageCompletionContributor.downloads(12_345))
        assertEquals("999", MsBuildPackageCompletionContributor.downloads(999))
    }

    private companion object {
        const val FEED = "https://feed.test/v3/index.json"
    }
}
