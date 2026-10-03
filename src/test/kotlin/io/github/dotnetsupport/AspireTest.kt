package io.github.dotnetsupport

import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.aspire.AspireDashboard
import io.github.dotnetsupport.aspire.AspireHosts
import io.github.dotnetsupport.aspire.AspireProcesses
import io.github.dotnetsupport.msbuild.MsBuildProject
import io.github.dotnetsupport.run.DotNetRunConfigurationGenerator
import io.github.dotnetsupport.run.ListeningAddressRecorder

/** Aspire, step 1: the AppHost, its dashboard link, and the services DCP starts. The fixtures are the output of a real Aspire 13.6 AppHost. */
class AspireTest : BasePlatformTestCase() {
    private fun resource(name: String): String = javaClass.getResourceAsStream("/aspire/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    fun testAppHostInEveryShapeOfAspire() {
        // Aspire 13 (`dotnet new aspire-starter` of 13.6.0): the SDK with its version in the attribute, OutputType Exe
        val aspire13 = MsBuildProject.parse("""
            <Project Sdk="Aspire.AppHost.Sdk/13.6.0">
              <PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework><AspireUseCliBundle>true</AspireUseCliBundle></PropertyGroup>
              <ItemGroup><ProjectReference Include="..\Probe.ApiService\Probe.ApiService.csproj" /></ItemGroup>
            </Project>""".trimIndent())
        assertTrue(aspire13.isAspireHost)
        assertTrue(aspire13.isRunnable)
        assertFalse(aspire13.isTestProject)
        // Aspire 9 (templates 9.4 / 9.5): the SDK as an element under Microsoft.NET.Sdk
        val aspire9 = MsBuildProject.parse("""
            <Project Sdk="Microsoft.NET.Sdk">
              <Sdk Name="Aspire.AppHost.Sdk" Version="9.5.2" />
              <PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net8.0</TargetFramework></PropertyGroup>
              <ItemGroup><PackageReference Include="Aspire.Hosting.AppHost" Version="9.5.2" /></ItemGroup>
            </Project>""".trimIndent())
        assertTrue(aspire9.isAspireHost)
        // Aspire 8: a property and the hosting package
        assertTrue(MsBuildProject.parse("<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><IsAspireHost>true</IsAspireHost></PropertyGroup></Project>").isAspireHost)
        // no OutputType: still something to run
        assertTrue(MsBuildProject.parse("<Project Sdk=\"Aspire.AppHost.Sdk\"/>").isRunnable)
        assertTrue(MsBuildProject.parse("<Project><Import Sdk=\"Aspire.AppHost.Sdk\" Project=\"Sdk.props\"/></Project>").isAspireHost)

        // the service defaults and the services are not hosts
        assertFalse(MsBuildProject.parse("<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><IsAspireSharedProject>true</IsAspireSharedProject></PropertyGroup></Project>").isAspireHost)
        assertFalse(MsBuildProject.parse("<Project Sdk=\"Microsoft.NET.Sdk.Web\"><PropertyGroup><IsAspireHost>false</IsAspireHost></PropertyGroup></Project>").isAspireHost)
        assertFalse(MsBuildProject.parse("<Project Sdk=\"Microsoft.NET.Sdk.Web\"/>").isAspireHost)
    }

    fun testAppHostConfigurationComesFirst() {
        myFixture.addFileToProject("AspireOrder/Api/Api.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"/>")
        val host = myFixture.addFileToProject("AspireOrder/Host/Host.csproj", "<Project Sdk=\"Aspire.AppHost.Sdk/13.6.0\"><PropertyGroup><OutputType>Exe</OutputType></PropertyGroup></Project>")
        myFixture.addFileToProject("AspireOrder/AspireOrder.slnx", "<Solution><Project Path=\"Api/Api.csproj\"/><Project Path=\"Host/Host.csproj\"/></Solution>")

        val names = DotNetRunConfigurationGenerator.getInstance(project).collectTargets().map { it.name }.filter { it == "Api" || it == "Host" }
        assertEquals(listOf("Host", "Api"), names)
        assertTrue(io.github.dotnetsupport.solution.SolutionService.getInstance(project).msBuildProject(host.virtualFile).isAspireHost)
        assertFalse(AspireHosts.isAppHost(project, null))
    }

    fun testDashboardLinkOfTheAppHost() {
        // what the AppHost logs itself: under the debugger, which starts the program and not `dotnet run`
        val output = resource("apphost-13.6-output.log")
        assertEquals("https://localhost:17149/login?t=00000000000000000000000000000001", AspireDashboard.loginUrl(output))
        assertEquals("https://localhost:17149/login?t=00000000000000000000000000000001", AspireDashboard.loginUrl(output.lines().single { "Login to the dashboard at" in it }))
        // the plain address the dashboard listens on is no login link
        assertNull(AspireDashboard.loginUrl("      Now listening on: https://localhost:17149"))
        assertNull(AspireDashboard.loginUrl("      - Dashboard:  https://localhost:17149"))
    }

    fun testDashboardLinkOfTheAspireCli() {
        // `dotnet run` of an Aspire 13 AppHost goes through the Aspire CLI: a localized label and the link as an OSC 8 hyperlink
        val output = resource("apphost-13.6-dotnet-run-output.log")
        assertEquals("https://localhost:17149/login?t=00000000000000000000000000000002", AspireDashboard.loginUrl(output))
        val line = output.lines().single { "/login?t=" in it }
        // the address stops at the escape sequence that follows it
        assertEquals("https://localhost:17149/login?t=00000000000000000000000000000002", AspireDashboard.loginUrl(line))
        // the link of the log file of the CLI is no dashboard
        assertEquals(1, output.lines().count { AspireDashboard.loginUrl(it) != null })
    }

    fun testTheRunKeepsTheDashboardLink() {
        val handler = NopProcessHandler()
        ListeningAddressRecorder.attach(handler)
        handler.startNotify()
        for (line in resource("apphost-13.6-output.log").lines()) handler.notifyTextAvailable("$line\n", ProcessOutputTypes.STDOUT)
        assertEquals("https://localhost:17149/login?t=00000000000000000000000000000001", handler.getUserData(AspireDashboard.KEY))
        assertEquals("https://localhost:17149", handler.getUserData(ListeningAddressRecorder.KEY))
    }

    private fun rows(): List<AspireProcesses.Row> = resource("apphost-13.6-processes.tsv").lines().filter { it.isNotBlank() }.map { line ->
        val (pid, parent, _, executable, commandLine) = line.split('\t')
        AspireProcesses.Row(pid.toLong(), parent.toLong(), executable, commandLine)
    }

    fun testServicesStartedByDcp() {
        val starter = "C:\\Users\\dev\\AppData\\Local\\Temp\\aspire-probe\\starter"
        val projects = listOf("AppHost", "ApiService", "Web", "ServiceDefaults").map { "$starter\\Probe.$it\\Probe.$it.csproj" }
        val rows = rows()
        // the AppHost (70732) is not a child of the AppHost's DCP; `dotnet run`, DCP and the dashboard are not services
        val services = AspireProcesses.services(rows, appHostPid = 70732, projects = projects)
        assertEquals(listOf(68992L to "Probe.ApiService", 54700L to "Probe.Web"), services.map { it.pid to it.name })
        assertEquals("$starter\\Probe.Web\\Probe.Web.csproj", services.last().projectPath)

        // the DCP of another AppHost; a project outside of the solution
        assertEquals(emptyList<AspireProcesses.Service>(), AspireProcesses.services(rows, appHostPid = 1, projects = projects))
        assertEquals(listOf("Probe.Web"), AspireProcesses.services(rows, 70732, projects.filter { "ApiService" !in it }).map { it.name })

        // without an apphost of .NET (UseAppHost=false, Linux): `dotnet <dll>` of the output; a restarted resource is a new process
        val linux = listOf(
            AspireProcesses.Row(10, 1, "/home/dev/.aspire/bundle/dcp/dcp", "/home/dev/.aspire/bundle/dcp/dcp start-apiserver --monitor 5 --kubeconfig /tmp/k"),
            AspireProcesses.Row(11, 10, "/home/dev/.aspire/bundle/dcp/dcp", "dcp run-controllers --monitor 10"),
            AspireProcesses.Row(12, 11, "/usr/share/dotnet/dotnet", "dotnet run --project /src/Api/Api.csproj --no-build"),
            AspireProcesses.Row(13, 12, "/usr/share/dotnet/dotnet", "/usr/share/dotnet/dotnet exec /src/Api/bin/Debug/net10.0/Api.dll"),
            AspireProcesses.Row(14, 11, "/usr/share/dotnet/dotnet", "dotnet /src/Api/bin/Debug/net10.0/Api.dll"),
        )
        assertEquals(setOf(13L, 14L), AspireProcesses.services(linux, 5, listOf("/src/Api/Api.csproj")).map { it.pid }.toSet())
        // which processes the watcher reads the command line of (on Windows it costs a list of all processes)
        assertTrue(AspireProcesses.needsCommandLine(linux[0], parentIsDcp = false))
        assertFalse(AspireProcesses.needsCommandLine(linux[1], parentIsDcp = true))
        assertTrue(AspireProcesses.needsCommandLine(linux[2], parentIsDcp = true))
        assertFalse(AspireProcesses.needsCommandLine(rows().single { it.pid == 68992L }, parentIsDcp = false))
    }
}
