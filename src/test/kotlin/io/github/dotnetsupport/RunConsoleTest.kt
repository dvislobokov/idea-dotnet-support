package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetConfigurationType
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.run.DotNetStackTraceFolding
import io.github.dotnetsupport.run.LogLevel
import io.github.dotnetsupport.run.LogLevelFilter
import io.github.dotnetsupport.sdk.SdkFeatures
import io.github.dotnetsupport.sdk.SdkVersion
import java.io.File

class RunConsoleTest : BasePlatformTestCase() {
    fun testLogLevels() {
        fun level(line: String) = LogLevelFilter.find(line)?.let { (range, level) -> line.substring(range) to level }

        // the simple console formatter of Microsoft.Extensions.Logging
        assertEquals("info" to LogLevel.INFO, level("info: Microsoft.Hosting.Lifetime[14]"))
        assertEquals("warn" to LogLevel.WARNING, level("warn: Microsoft.AspNetCore.HttpsPolicy.HttpsRedirectionMiddleware[3]"))
        assertEquals("fail" to LogLevel.ERROR, level("fail: Shop.Orders[0]"))
        assertEquals("crit" to LogLevel.ERROR, level("crit: Shop.Orders[0]"))
        assertEquals("dbug" to LogLevel.DEBUG, level("dbug: Microsoft.Extensions.Hosting.Internal.Host[1]"))
        assertEquals("trce" to LogLevel.TRACE, level("      trce: X[0]"))
        // Serilog, NLog, a hand-made format
        assertEquals("INF" to LogLevel.INFO, level("[21:40:01 INF] Now listening on: http://localhost:5000"))
        assertEquals("ERR" to LogLevel.ERROR, level("[2024-05-01 21:40:01.123 +03:00 ERR] Connection refused"))
        assertEquals("WARN" to LogLevel.WARNING, level("2024-05-01 10:00:00.1234|WARN|Shop.Orders|slow query"))
        assertEquals("FATAL" to LogLevel.ERROR, level("[FATAL] out of memory"))

        // words of ordinary output are not levels
        assertNull(level("information: nothing"))
        assertNull(level("      Now listening on: http://localhost:5000"))
        assertNull(level("Build succeeded with 2 warn: ings"))
        assertNull(level("[INFORMATION]"))
        assertNull(level("see [1] and info below"))
    }

    fun testLogLevelIsHighlightedWithConsoleColors() {
        val line = "warn: Shop.Orders[0]\n"
        val item = LogLevelFilter().applyFilter(line, 500 + line.length)!!.resultItems.single()
        assertEquals(500 to 504, item.highlightStartOffset to item.highlightEndOffset)
        assertNotNull(item.highlightAttributes)
        assertNull(item.hyperlinkInfo)
        assertNull(LogLevelFilter().applyFilter("      Application started.\n", 40))
    }

    fun testFrameworkFramesAreFolded() {
        fun folds(line: String) = DotNetStackTraceFolding.isFrameworkFrame(line)
        assertTrue(folds("   at System.Threading.Tasks.Task.ThrowIfExceptional(Boolean includeTaskCanceledExceptions)"))
        assertTrue(folds("   at Microsoft.AspNetCore.Routing.EndpointMiddleware.<Invoke>g__AwaitRequestTask|7_0(Endpoint endpoint, Task requestTask, ILogger logger)"))
        assertTrue(folds("   at System.Collections.Generic.List`1.get_Item(Int32 index)"))
        assertTrue(folds("   в System.Linq.Enumerable.First[TSource](IEnumerable`1 source)"))
        assertTrue(folds("--- End of stack trace from previous location ---"))
        assertTrue(folds("    at System.Private.CoreLib.il!System.Threading.Monitor.Wait(class System.Object,int32)"))

        // the code of the application, the message of the exception, ordinary output
        assertFalse(folds("   at Shop.Orders.Load(Int32 id) in C:\\src\\Shop\\Orders.cs:line 42"))
        assertFalse(folds("   at Program.<Main>$(String[] args) in C:\\src\\Shop\\Program.cs:line 7"))
        assertFalse(folds("    at app!Program.<Main>$(class System.String[])"))
        assertFalse(folds("System.InvalidOperationException: Sequence contains no elements"))
        assertFalse(folds("info: Microsoft.Hosting.Lifetime[14]"))
        assertFalse(folds("      Microsoft.Hosting is starting (really)"))

        val folding = DotNetStackTraceFolding()
        assertEquals("   <2 framework frames>", folding.getPlaceholderText(project, mutableListOf(
            "   at System.A.B()", "--- End of stack trace from previous location ---", "   at Microsoft.C.D()")))
        assertEquals("   <1 framework frame>", folding.getPlaceholderText(project, mutableListOf("   at System.A.B()")))
    }

    fun testEnvironmentOfARunConfiguration() {
        val projectFile = File(myFixture.addFileToProject("Web/Web.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"/>").virtualFile.path)
        val configuration = DotNetRunConfiguration(project, DotNetConfigurationType.instance.factory, "Web")
        configuration.options.projectPath = projectFile.path

        fun environment() = configuration.buildCommandLine().environment.filterKeys { it in DotNetRunConfiguration.HOSTING_VARIABLES }
        assertEquals(emptyMap<String, String>(), environment())
        assertFalse("-e" in configuration.buildCommandLine().parametersList.list)

        configuration.options.environmentName = "Staging"
        assertEquals(mapOf("ASPNETCORE_ENVIRONMENT" to "Staging", "DOTNET_ENVIRONMENT" to "Staging"), environment())

        // a variable set by hand in the table is the user's business
        configuration.options.environment = mutableMapOf("ASPNETCORE_ENVIRONMENT" to "Custom")
        assertEquals(mapOf("ASPNETCORE_ENVIRONMENT" to "Custom", "DOTNET_ENVIRONMENT" to "Staging"), environment())
        configuration.options.environment = mutableMapOf()

        // `-e` beats the launch profile, but only a new enough SDK knows it; the options come before the program arguments
        configuration.options.programArguments = "--seed"
        val arguments = configuration.buildCommandLine().parametersList.list
        if (SdkFeatures.supportsRunEnvironmentOption(SdkFeatures.sdkFor(null))) {
            val option = arguments.indexOf("-e")
            assertEquals(listOf("-e", "ASPNETCORE_ENVIRONMENT=Staging", "-e", "DOTNET_ENVIRONMENT=Staging", "--", "--seed"), arguments.subList(option, arguments.size))
        } else {
            assertFalse("-e" in arguments)
        }
        // tests get the variables, `dotnet test -e` means something else
        configuration.options.command = DotNetCommand.TEST
        assertFalse("-e" in configuration.buildCommandLine().parametersList.list)
        assertEquals("Staging", configuration.buildCommandLine().environment["DOTNET_ENVIRONMENT"])
    }

    fun testSdkGateOfTheEnvironmentOption() {
        fun supports(version: String?) = SdkFeatures.supportsRunEnvironmentOption(version?.let { SdkVersion.parse(it) })
        assertEquals(listOf(false, false, true, true, true, false), listOf("8.0.404", "9.0.103", "9.0.200", "9.0.301", "10.0.100-rc.1", null).map(::supports))

        // global.json pins an older SDK for this directory
        val installed = listOf("9.0.103", "10.0.401").map { SdkVersion.parse(it)!! }
        assertEquals("10.0.401", SdkFeatures.sdkFor(null, installed)?.text)
        myFixture.addFileToProject("global.json", "{ \"sdk\": { \"version\": \"9.0.100\", \"rollForward\": \"latestFeature\" } }")
        val directory = myFixture.addFileToProject("Api/Api.csproj", "<Project/>").virtualFile.parent
        assertEquals("9.0.103", SdkFeatures.sdkFor(directory, installed)?.text)
    }

    fun testRunProjectReusesTheConfigurationOfTheProject() {
        val projectFile = myFixture.addFileToProject("Tool/Tool.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>").virtualFile
        val target = io.github.dotnetsupport.run.RunProjectTarget(projectFile, DotNetCommand.RUN)
        val runManager = com.intellij.execution.RunManager.getInstance(project)

        // nothing runs the project yet: a new configuration, not registered until the action executes it
        val created = target.settings(project)
        assertEquals("Tool", created.name)
        assertEquals(projectFile.path, (created.configuration as DotNetRunConfiguration).options.projectPath)
        assertFalse(runManager.allSettings.contains(created))

        runManager.addConfiguration(created)
        try {
            assertSame(created, target.settings(project))
            // `dotnet test` of the same project is another thing to run
            assertNotSame(created, io.github.dotnetsupport.run.RunProjectTarget(projectFile, DotNetCommand.TEST).settings(project))
        } finally {
            runManager.removeConfiguration(created)
        }
    }

    fun testEnvironmentNamesComeFromAppSettings() {
        val directory = com.intellij.openapi.util.io.FileUtil.createTempDirectory("web", null, true)
        listOf("appsettings.json", "appsettings.Development.json", "appsettings.QA.json", "appsettings.Local-Docker.json", "appsettings.json.bak", "other.Staging.json")
            .forEach { File(directory, it).writeText("{}") }
        assertEquals(listOf("Development", "Staging", "Production", "Local-Docker", "QA"), DotNetRunConfiguration.environmentNames(File(directory, "Web.csproj")))
        assertEquals(listOf("Development", "Staging", "Production"), DotNetRunConfiguration.environmentNames(File("missing/Web.csproj")))
    }
}
