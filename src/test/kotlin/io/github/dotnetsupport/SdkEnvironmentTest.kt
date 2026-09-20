package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.newproject.TemplatePackages
import io.github.dotnetsupport.newproject.TemplatePackagesDialog
import io.github.dotnetsupport.nuget.NuGetClient
import io.github.dotnetsupport.sdk.DotNetEnvironment
import io.github.dotnetsupport.sdk.DotNetEnvironmentDialog
import io.github.dotnetsupport.sdk.DotNetInfo
import io.github.dotnetsupport.sdk.GlobalJson
import io.github.dotnetsupport.sdk.SdkCheck
import io.github.dotnetsupport.sdk.SdkVersion
import io.github.dotnetsupport.sdk.SupportState
import io.github.dotnetsupport.upgrade.UpgradeAssistant
import io.github.dotnetsupport.upgrade.UpgradeReport
import io.github.dotnetsupport.upgrade.UpgradeReportDialog
import io.github.dotnetsupport.upgrade.UpgradeSeverity
import java.io.File

/** The CLI outputs are the real ones: .NET SDK 10.0.401, upgrade-assistant 1.0.518. */
class SdkEnvironmentTest : BasePlatformTestCase() {
    private val sdkCheck = """
        .NET SDKs:
        Version       Status
        ----------------------------------------------------
        8.0.100       .NET 8.0 is out of support.
        9.0.301       .NET 9.0 is going out of support soon.
        10.0.300      Patch 10.0.401 is available.
        10.0.401      Up to date.

        .NET Runtimes:
        Name                              Version      Status
        -------------------------------------------------------------------------------------
        Microsoft.AspNetCore.App          9.0.6        .NET 9.0 is going out of support soon.
        Microsoft.NETCore.App             10.0.12      Up to date.


        The latest versions of .NET can be installed from https://aka.ms/dotnet-core-download. For more information about .NET lifecycles, see https://aka.ms/dotnet-core-support.
    """.trimIndent()

    fun testSdkCheck() {
        val components = SdkCheck.parse(sdkCheck)
        assertEquals(
            listOf("8.0.100=OUT_OF_SUPPORT", "9.0.301=ATTENTION", "10.0.300=ATTENTION", "10.0.401=UP_TO_DATE"),
            components.filter { it.isSdk }.map { "${it.version}=${it.state}" },
        )
        assertEquals("Patch 10.0.401 is available.", components[2].status)
        assertEquals(
            listOf("Microsoft.AspNetCore.App 9.0.6 ATTENTION", "Microsoft.NETCore.App 10.0.12 UP_TO_DATE"),
            components.filter { !it.isSdk }.map { "${it.name} ${it.version} ${it.state}" },
        )
        // the closing sentence mentions versions too, but it is not a row
        assertEquals(6, components.size)
        assertEquals(emptyList<Any>(), SdkCheck.parse("Unable to load release information: the network is down"))
    }

    fun testDotNetInfo() {
        val info = DotNetInfo.parse(
            """
            .NET SDK:
             Version:           10.0.401
             Commit:            e34a38d2ae
             MSBuild version:   18.9.11+e34a38d2a

            Runtime Environment:
             OS Name:     Windows
             RID:         win-x64
             Base Path:   C:\Program Files\dotnet\sdk\10.0.401\

            Host:
              Version:      10.0.12
              Architecture: x64

            .NET SDKs installed:
              9.0.301 [C:\Program Files\dotnet\sdk]

            global.json file:
              Not found
            """.trimIndent()
        )
        assertEquals("10.0.401", info.value(".NET SDK", "Version"))
        // "Version" of another section, and a value with a colon inside
        assertEquals("10.0.12", info.value("Host", "Version"))
        assertEquals("C:\\Program Files\\dotnet\\sdk\\10.0.401\\", info.value("Runtime Environment", "Base Path"))
        assertNull(info.value("Host", "Commit"))
        assertEquals(
            listOf("SDK in use=10.0.401", "MSBuild=18.9.11+e34a38d2a", "Host=10.0.12, x64", "Runtime identifier=win-x64", "Base path=C:\\Program Files\\dotnet\\sdk\\10.0.401\\"),
            info.summary().map { "${it.first}=${it.second}" },
        )
    }

    fun testEnvironmentSummary() {
        val components = SdkCheck.parse(sdkCheck)
        val globalJson = GlobalJson.parse("{ \"sdk\": { \"version\": \"7.0.100\" } }")
        val html = DotNetEnvironmentDialog.summaryHtml(DotNetEnvironment("C:\\dotnet\\dotnet.exe", null, components, null, globalJson, null))
        assertTrue(html, "requires 7.0.100 (rollForward: latestPatch). <b>No installed SDK satisfies it.</b>" in html)
        assertTrue(html, "<b>1 out of support</b>, 3 need attention" in html)

        val offline = DotNetEnvironmentDialog.summaryHtml(DotNetEnvironment("dotnet", null, emptyList(), "No such host is known", null, SdkVersion.parse("10.0.401")))
        assertTrue(offline, "dotnet sdk check failed: No such host is known" in offline)
        assertTrue(offline, "not used by this project" in offline)
        assertTrue("not found" in DotNetEnvironmentDialog.summaryHtml(DotNetEnvironment(null, null, emptyList(), null, null, null)))
    }

    fun testInstalledTemplatePackages() {
        val packages = TemplatePackages.parseInstalled(
            """
            Currently installed items:
               Avalonia.Templates
                  Version: 11.0.10
                  Details:
                     Author: Avalonia.Templates
                     Owners: avaloniaui
                     Reserved: ✔
                     NuGetSource: https://api.nuget.org/v3/index.json
                  Templates:
                     Avalonia .NET MVVM App (avalonia.mvvm) C#
                     Avalonia .NET App (avalonia.app) C#
                     Avalonia .NET MVVM App (avalonia.mvvm) F#
                     Avalonia .NET App (avalonia.app) F#
                     Avalonia Resource Dictionary (avalonia.resource)
                  Uninstall Command:
                     dotnet new uninstall Avalonia.Templates

               C:\work\my templates
                  Templates:
                     Company Service (company-service) C#
                  Uninstall Command:
                     dotnet new uninstall "C:\work\my templates"
            """.trimIndent()
        )
        assertEquals(listOf("Avalonia.Templates", "C:\\work\\my templates"), packages.map { it.id })
        assertEquals("11.0.10", packages[0].version)
        // one entry per template, not per language; "Author: ..." is not a template
        assertEquals(listOf("Avalonia .NET MVVM App", "Avalonia .NET App", "Avalonia Resource Dictionary"), packages[0].templates)
        assertNull(packages[1].version)
        assertEquals(listOf("Company Service"), packages[1].templates)

        assertEquals(emptyList<Any>(), TemplatePackages.parseInstalled("Currently installed items:\n(No Items)\n"))
    }

    fun testTemplateUpdates() {
        val updates = TemplatePackages.parseUpdates(
            """
            An update for template packages is available:
            Package             Current  Latest
            ------------------  -------  ------
            Avalonia.Templates  11.0.10  12.1.2


            To update the package use:
               dotnet new install <package>@<version>
               dotnet new install Avalonia.Templates@12.1.2
            """.trimIndent()
        )
        assertEquals(listOf("Avalonia.Templates 11.0.10 12.1.2"), updates.map { "${it.id} ${it.current} ${it.latest}" })
        assertEquals(emptyList<Any>(), TemplatePackages.parseUpdates("All template packages are up-to-date.\n"))
    }

    fun testTemplateSearchAsksForTemplatePackagesOnly() {
        val requested = ArrayList<String>()
        val client = NuGetClient { url, _ ->
            requested += url
            if (url.endsWith("index.json")) """{ "resources": [ { "@id": "https://search/query", "@type": "SearchQueryService" } ] }"""
            else """{ "data": [ { "id": "Avalonia.Templates", "version": "12.1.2", "description": "Templates", "totalDownloads": 533000, "verified": true } ] }"""
        }
        assertEquals(listOf("Avalonia.Templates"), TemplatePackages.search("avalonia ui", client).map { it.id })
        val query = requested.last()
        assertTrue(query, query.startsWith("https://search/query?q=avalonia+ui&") && query.endsWith("&packageType=Template"))
        assertEquals("533k downloads", TemplatePackagesDialog.downloads(533_000))
    }

    fun testUpgradeReport() {
        val root = FileUtil.createTempDirectory("upgrade", null, true)
        val projectFile = File(root, "src/App/App.csproj").apply { parentFile.mkdirs(); writeText("<Project/>") }
        val program = File(root, "src/App/Program.cs").apply { writeText("class P {}") }

        // trimmed output of `upgrade-assistant analyze --serializer json`; in the Protected mode paths lose their beginning
        val json = "\uFEFF" + """
        {
          "settings": { "targetDisplayName": ".NETCoreApp,Version=v10.0" },
          "stats": { "summary": { "projects": 1, "issues": 3, "incidents": 3, "effort": 5 } },
          "projects": [ { "path": "work\\src\\App\\App.csproj", "ruleInstances": [
            { "ruleId": "NuGet.0002", "state": "Active", "location": { "kind": "File", "path": "work\\src\\App\\App.csproj",
              "snippet": "Newtonsoft.Json, 9.0.1\n\nRecommendation:\n\nNewtonsoft.Json 13.0.4", "label": "Newtonsoft.Json 9.0.1",
              "properties": { "PackageId": "Newtonsoft.Json", "PackageVersion": "9.0.1", "PackageNewVersion": "13.0.4", "PackageReplacements": null } } },
            { "ruleId": "Api.0001", "state": "Active", "location": { "kind": "File", "path": "work\\src\\App\\Program.cs",
              "snippet": "T:System.Net.WebRequest", "label": "T:System.Net.WebRequest", "line": 2, "column": 38,
              "links": [ { "title": "API documentation", "url": "https://learn.microsoft.com/dotnet/api/system.net.webrequest", "isCustom": false } ] } },
            { "ruleId": "Unknown.0001", "location": { "kind": "File", "path": "elsewhere\\Gone.cs" } }
          ] } ],
          "rules": {
            "NuGet.0002": { "id": "NuGet.0002", "description": "Upgrade the package.", "label": "NuGet package upgrade is recommended", "severity": "Potential", "effort": 1,
              "links": [ { "url": "https://go.microsoft.com/fwlink/?linkid=2262530", "isCustom": false } ] },
            "Api.0001": { "id": "Api.0001", "label": "API is not available", "severity": "Mandatory", "effort": 3 }
          }
        }
        """.trimIndent()

        val report = UpgradeReport.parse(json, projectFile)!!
        assertEquals(".NETCoreApp,Version=v10.0", report.target)
        assertEquals(5, report.effort)
        // the blocking ones first
        assertEquals(listOf("Api.0001", "NuGet.0002", "Unknown.0001"), report.incidents.map { it.ruleId })

        val api = report.incidents[0]
        assertEquals(UpgradeSeverity.MANDATORY, api.severity)
        assertEquals("API is not available", api.title)
        assertEquals(program, api.file)
        assertEquals(2 to 38, api.line to api.column)
        assertEquals("https://learn.microsoft.com/dotnet/api/system.net.webrequest", api.link)

        val nuget = report.incidents[1]
        assertEquals("Newtonsoft.Json 9.0.1 → 13.0.4", nuget.subject)
        assertEquals(projectFile, nuget.file)
        assertEquals("https://go.microsoft.com/fwlink/?linkid=2262530", nuget.link)

        // a rule missing from the dictionary and a file that is not there are not a reason to fail
        val unknown = report.incidents[2]
        assertEquals("Unknown.0001" to UpgradeSeverity.INFORMATION, unknown.title to unknown.severity)
        assertNull(unknown.file)

        assertEquals(
            "<html>Target: <b>.NETCoreApp,Version=v10.0</b> · 1 project · 3 incidents (1 mandatory, 1 potential, 1 information) · estimated effort: 5 story points</html>",
            UpgradeReportDialog.summary(report),
        )
        assertNull(UpgradeReport.parse("not a report", projectFile))
    }

    fun testUpgradeCommandLine() {
        val command = UpgradeAssistant.commandLine(File("C:/tools/upgrade-assistant.exe"), File("C:/work/App/App.csproj"), "net10.0", File("C:/tmp/report.json"))
        assertEquals(
            listOf("analyze", File("C:/work/App/App.csproj").path, "--non-interactive", "--targetFramework", "net10.0",
                "--report", File("C:/tmp/report.json").path, "--serializer", "json", "--privacyMode", "Unrestricted"),
            command.parametersList.list,
        )
        assertEquals("1", command.environment["DOTNET_SYSTEM_GLOBALIZATION_INVARIANT"])
        // the log names the tool that really runs
        assertTrue(io.github.dotnetsupport.cli.DotNetCli.displayString(command).startsWith("upgrade-assistant analyze "))
    }

    fun testUnitTestsWindowHasItsOwnStripeIcon() {
        // classic and new UI, light and dark, plus the 20x20 variant of the new UI stripe
        val names = listOf("unitTestsToolWindow.svg", "unitTestsToolWindow_dark.svg", "expui/unitTestsToolWindow.svg", "expui/unitTestsToolWindow_dark.svg",
            "expui/unitTestsToolWindow@20x20.svg", "expui/unitTestsToolWindow@20x20_dark.svg")
        names.forEach { assertNotNull(it, javaClass.getResource("/icons/$it")) }
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        assertTrue("<toolWindow id=\"Unit Tests\" anchor=\"left\" secondary=\"false\" icon=\"/icons/unitTestsToolWindow.svg\"" in pluginXml)
        assertTrue("\"unitTestsToolWindow.svg\": \"icons/unitTestsToolWindow.svg\"" in javaClass.getResource("/DotNetIconMappings.json")!!.readText())
    }

    fun testActionsAreRegistered() {
        val actions = ActionManager.getInstance()
        assertNotNull(actions.getAction("DotNet.Environment"))
        assertNotNull(actions.getAction("DotNet.AnalyzeUpgrade"))
    }
}
