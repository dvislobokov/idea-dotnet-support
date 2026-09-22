package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.nuget.NuGetClient
import io.github.dotnetsupport.nuget.NuGetResponses
import io.github.dotnetsupport.nuget.NuGetService
import io.github.dotnetsupport.nuget.NuGetVersion

class NuGetTest : BasePlatformTestCase() {
    private val NL = 10.toChar().toString()

    private fun v(text: String) = NuGetVersion.parse(text)!!

    fun testVersionOrder() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.2", "1.10.0", "2.0.0.1")
        assertEquals(ordered, ordered.shuffled(java.util.Random(7)).sortedBy { v(it) })

        assertEquals(v("1.0"), v("1.0.0.0"))
        assertEquals(v("1.0.0+build.5"), v("1.0.0"))
        assertTrue(v("4.4.1-dev-02447").isPrerelease)
        assertNull(NuGetVersion.parse("6.*"))
        assertNull(NuGetVersion.parse(""))

        val published = listOf("4.3.1", "4.4.0", "4.4.1-dependabot-02442", "4.4.1-dev-02447")
        assertEquals("4.4.0", NuGetVersion.latest(published, includePrerelease = false))
        assertEquals("4.4.1-dev-02447", NuGetVersion.latest(published, includePrerelease = true))
        // a package that has only prereleases still has a "latest"
        assertEquals("0.2.0-beta", NuGetVersion.latest(listOf("0.1.0-beta", "0.2.0-beta"), includePrerelease = false))
    }

    fun testResponses() {
        val index = NuGetResponses.parseServiceIndex(
            """{"version":"3.0.0","resources":[
                {"@id":"https://search.example/query","@type":"SearchQueryService"},
                {"@id":"https://search.example/query-35","@type":"SearchQueryService/3.5.0"},
                {"@id":"https://api.example/flat","@type":"PackageBaseAddress/3.0.0"}]}"""
        )
        assertEquals("https://search.example/query-35", index.searchUrl)
        assertEquals("https://api.example/flat/", index.packageBaseUrl)

        val found = NuGetResponses.parseSearch(
            """{"totalHits":2,"data":[
                {"id":"Serilog","version":"4.4.0","description":"Simple .NET logging","authors":["Serilog Contributors","Someone"],"totalDownloads":3278491170,
                 "projectUrl":"https://serilog.net/","verified":true,"versions":[{"version":"4.3.1","downloads":1},{"version":"4.4.0","downloads":2}]},
                {"id":"Private.Package","version":"1.0.0","summary":"from a private feed","authors":"Me","versions":[]}]}"""
        )
        assertEquals(listOf("Serilog", "Private.Package"), found.map { it.id })
        assertEquals("Serilog Contributors, Someone", found[0].authors)
        assertEquals(3278491170L, found[0].totalDownloads)
        assertEquals(listOf("4.3.1", "4.4.0"), found[0].versions)
        assertTrue(found[0].isVerified)
        assertEquals("from a private feed" to "Me", found[1].description to found[1].authors)
        assertNull(found[1].projectUrl)

        assertEquals(listOf("1.0.0", "1.1.0"), NuGetResponses.parseVersions("""{"versions":["1.0.0","1.1.0"]}"""))
        assertEquals(emptyList<String>(), NuGetResponses.parseVersions("<html>502</html>"))
        assertEquals(
            listOf("https://api.nuget.org/v3/index.json", "C:\\packages"),
            NuGetResponses.parseSources("E https://api.nuget.org/v3/index.json\nD https://disabled.example/index.json\nE C:\\packages\n"),
        )
    }

    fun testNuspecAndSearchMetadata() {
        val details = NuGetResponses.parseNuspec(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
              <metadata>
                <id>Serilog.Sinks.Console</id>
                <authors>Serilog Contributors</authors>
                <license type="expression">Apache-2.0</license>
                <licenseUrl>https://licenses.nuget.org/Apache-2.0</licenseUrl>
                <projectUrl>https://github.com/serilog/serilog-sinks-console</projectUrl>
                <description>A Serilog sink that writes log events to the console.</description>
                <dependencies>
                  <group targetFramework="net8.0"><dependency id="Serilog" version="4.0.0" exclude="Build,Analyzers" /></group>
                  <group targetFramework=".NETStandard2.0"><dependency id="Serilog" version="4.0.0" /><dependency id="System.Memory" version="4.5.5" /></group>
                  <group targetFramework="net9.0" />
                </dependencies>
              </metadata>
            </package>
            """.trimIndent()
        )!!
        assertEquals("Apache-2.0", details.license)
        assertEquals("Serilog Contributors", details.authors)
        assertEquals(
            listOf("net8.0" to listOf("Serilog 4.0.0"), ".NETStandard2.0" to listOf("Serilog 4.0.0", "System.Memory 4.5.5"), "net9.0" to emptyList()),
            details.dependencyGroups,
        )
        // packages from before dependency groups
        val old = NuGetResponses.parseNuspec("<package><metadata><description>d</description><dependencies><dependency id=\"A\" version=\"1.0\"/></dependencies></metadata></package>")!!
        assertEquals(listOf("" to listOf("A 1.0")), old.dependencyGroups)
        assertNull(NuGetResponses.parseNuspec("<html>404</html>"))

        val found = NuGetResponses.parseSearch("""{"data":[{"id":"Serilog","version":"4.4.0","iconUrl":"https://x/icon","licenseUrl":"https://x/license","tags":["logging","structured"]},
            {"id":"Old","version":"1.0.0","tags":"a b, c","iconUrl":""}]}""")
        assertEquals("https://x/icon" to listOf("logging", "structured"), found[0].iconUrl to found[0].tags)
        assertEquals(null to listOf("a", "b", "c"), found[1].iconUrl to found[1].tags)
    }

    fun testSourceListIsParsedWithoutRelyingOnTheLanguage() {
        // the CLI is localized: "[Включено]" here, "[Enabled]" elsewhere
        val detailed = "Зарегистрированные источники:\r\n  1.  nuget.org [Включено]\r\n      https://api.nuget.org/v3/index.json\r\n" +
            "  2.  My Company Feed [Отключено]\r\n      https://pkgs.example/v3/index.json\r\n  3.  local [Включено]\r\n      C:\\packages\r\n"
        val short = "E https://api.nuget.org/v3/index.json\nD https://pkgs.example/v3/index.json\nE C:\\packages\n"
        val sources = NuGetResponses.parseSourceList(detailed, short)
        assertEquals(listOf("nuget.org", "My Company Feed", "local"), sources.map { it.name })
        assertEquals(listOf("https://api.nuget.org/v3/index.json", "https://pkgs.example/v3/index.json", "C:\\packages"), sources.map { it.url })
        assertEquals(listOf(true, false, true), sources.map { it.isEnabled })
        assertEquals(emptyList<Any>(), NuGetResponses.parseSourceList("error: no config", ""))
    }

    fun testLogKeepsOutputUntilTheTabIsOpened() {
        val log = io.github.dotnetsupport.nuget.NuGetLog()
        val command = com.intellij.execution.configurations.GeneralCommandLine("dotnet", "add", "App.csproj", "package", "Serilog")
        // what DotNetCli reports while a command runs
        log.commandStarted(command)
        log.text("info : PackageReference added" + NL, false)
        log.commandFinished(0)
        log.commandStarted(command)
        log.text("error: NU1101: Unable to find package" + NL, true)
        log.commandFinished(1)

        val printed = ArrayList<Pair<String, Boolean>>()
        log.subscribe { text, isError -> printed += text to isError }
        assertEquals(
            listOf(
                "> dotnet add App.csproj package Serilog$NL" to false, "info : PackageReference added$NL" to false, "Done.$NL$NL" to false,
                "> dotnet add App.csproj package Serilog$NL" to false, "error: NU1101: Unable to find package$NL" to true, "Failed with exit code 1.$NL$NL" to true,
            ),
            printed,
        )
        log.print("later")
        assertEquals("later" to false, printed.last())
    }

    fun testToolWindowIsBuilt() {
        val toolWindow = com.intellij.toolWindow.ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        try {
            io.github.dotnetsupport.nuget.NuGetToolWindowFactory().createToolWindowContent(project, toolWindow)
            assertEquals(listOf("Packages", "Sources", "Folders", "Log"), toolWindow.contentManager.contents.map { it.displayName })
        } finally {
            // the IDE disposes a tool window with its project; the mock has to be disposed by hand (the Log tab owns a console editor)
            com.intellij.openapi.util.Disposer.dispose(toolWindow.disposable)
        }
    }

    fun testMenuIsInTheMainMenuBar() {
        val actions = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val mainMenu = actions.getAction("MainMenu") as com.intellij.openapi.actionSystem.DefaultActionGroup
        val dotNet = actions.getAction("DotNet.MainMenu")
        assertTrue(mainMenu.childActionsOrStubs.any { it === dotNet || actions.getId(it) == "DotNet.MainMenu" })

        val nuGet = actions.getAction("DotNet.NuGet") as com.intellij.openapi.actionSystem.DefaultActionGroup
        val ids = nuGet.childActionsOrStubs.mapNotNull { actions.getId(it) }
        assertEquals("DotNet.NuGet.QuickList", ids.first())
        assertTrue(ids.containsAll(listOf("DotNet.RestoreSolution", "DotNet.NuGet.ForceRestore", "DotNet.NuGet.UpgradeSolution", "DotNet.NuGet.ShowFolders", "DotNet.NuGet.Settings")))
    }

    fun testClientAcrossFeeds() {
        val requested = ArrayList<String>()
        val client = NuGetClient { url, _ ->
            requested += url
            when {
                url == "https://a/index.json" -> """{"resources":[{"@id":"https://a/query","@type":"SearchQueryService"},{"@id":"https://a/flat/","@type":"PackageBaseAddress/3.0.0"}]}"""
                url == "https://b/index.json" -> """{"resources":[{"@id":"https://b/query","@type":"SearchQueryService"},{"@id":"https://b/flat/","@type":"PackageBaseAddress/3.0.0"}]}"""
                url.startsWith("https://a/query") -> """{"data":[{"id":"Common","version":"1.0.0"},{"id":"OnlyA","version":"1.0.0"}]}"""
                url.startsWith("https://b/query") -> """{"data":[{"id":"common","version":"9.9.9"},{"id":"OnlyB","version":"2.0.0"}]}"""
                url == "https://b/flat/onlyb/index.json" -> """{"versions":["1.0.0","2.0.0"]}"""
                else -> throw java.io.IOException("404 $url")
            }
        }
        val sources = listOf("https://a/index.json", "https://broken/index.json", "https://b/index.json")

        // a broken feed does not break the search; a package that is in two feeds comes from the first one
        assertEquals(listOf("Common 1.0.0", "OnlyA 1.0.0", "OnlyB 2.0.0"), client.search("c# json", false, sources).map { "${it.id} ${it.version}" })
        assertTrue(requested.any { it.startsWith("https://a/query?q=c%23+json&take=40&prerelease=false") })
        // the package id is lower-cased in the flat container; the feed that has the package answers
        assertEquals(listOf("1.0.0", "2.0.0"), client.versions("OnlyB", sources))
        assertEquals(emptyList<String>(), client.versions("Missing", sources))
        // service indexes are requested once
        assertEquals(1, requested.count { it == "https://a/index.json" })
    }

    fun testInstalledPackages() {
        val projectFile = myFixture.addFileToProject(
            "App/App.csproj",
            """
            <Project Sdk="Microsoft.NET.Sdk">
              <ItemGroup>
                <PackageReference Include="Serilog" Version="4.*" />
                <PackageReference Include="Central.Package" />
                <PackageReference Include="Dapper" Version="2.1.35" />
              </ItemGroup>
            </Project>
            """.trimIndent(),
        ).virtualFile
        myFixture.addFileToProject("Directory.Packages.props", """<Project><ItemGroup><PackageVersion Include="Central.Package" Version="1.2.3"/></ItemGroup></Project>""")
        myFixture.addFileToProject(
            "App/obj/project.assets.json",
            """{"targets":{"net9.0":{"Serilog/4.4.0":{"type":"package"},"Dapper/2.1.35":{"type":"package"}}},"libraries":{},"project":{"frameworks":{"net9.0":{}}}}""",
        )

        val installed = NuGetService.getInstance(project).installed(projectFile)
        assertEquals(listOf("Central.Package", "Dapper", "Serilog"), installed.map { it.id })
        // resolved by restore > declared in the project > central package management
        assertEquals(listOf("1.2.3", "2.1.35", "4.4.0"), installed.map { it.version })
        assertEquals("4.*", installed[2].declaredVersion)

        assertNotNull(ActionManager.getInstance().getAction("DotNet.ManageNuGet"))
    }
}
