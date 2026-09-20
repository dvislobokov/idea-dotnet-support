package io.github.dotnetsupport

import com.intellij.execution.RunManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.run.DotNetRunConfigurationGenerator
import io.github.dotnetsupport.sdk.DotNetSdks
import io.github.dotnetsupport.sdk.GlobalJson
import io.github.dotnetsupport.sdk.SdkVersion
import io.github.dotnetsupport.settings.DotNetSettings
import io.github.dotnetsupport.settings.DotNetSettingsConfigurable

class SettingsAndSdkTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            DotNetSettings.getInstance().apply { dotnetPath = ""; createRunConfigurations = true; openBuildWindowOnEveryBuild = true; switchToSolutionView = true }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testSdkList() {
        val sdks = DotNetSdks.parse("9.0.301 [C:\\Program Files\\dotnet\\sdk]\r\n10.0.100-rc.1.25451.107 [C:\\Program Files\\dotnet\\sdk]\r\n10.0.401 [C:\\Program Files\\dotnet\\sdk]\r\n\r\nnot a version\r\n")
        assertEquals(listOf("10.0.401", "10.0.100-rc.1.25451.107", "9.0.301"), sdks.map { it.version.text })
        assertEquals("C:\\Program Files\\dotnet\\sdk", sdks[0].location)
        assertEquals(4, sdks[0].version.featureBand)
        assertTrue(sdks[1].version.isPrerelease)
        assertTrue(SdkVersion.parse("10.0.100")!! > SdkVersion.parse("10.0.100-rc.1")!!)
    }

    /** The outcomes were taken from the real `dotnet --version` with SDKs 9.0.301 and 10.0.401 installed. */
    fun testGlobalJsonRollForwardMatchesTheCli() {
        val installed = listOf("9.0.301", "10.0.401").map { SdkVersion.parse(it)!! }
        fun resolve(version: String, rollForward: String? = null): String? {
            val policy = rollForward?.let { ", \"rollForward\": \"$it\"" }.orEmpty()
            return GlobalJson.parse("{ \"sdk\": { \"version\": \"$version\"$policy } }")!!.resolve(installed)?.text
        }
        assertNull(resolve("9.0.100"))                          // default latestPatch: another feature band
        assertEquals("9.0.301", resolve("9.0.100", "latestFeature"))
        assertEquals("9.0.301", resolve("9.0.300"))
        assertEquals("9.0.301", resolve("9.0.301", "disable"))
        assertNull(resolve("9.0.302", "disable"))
        assertNull(resolve("8.0.100", "latestMinor"))
        assertEquals("10.0.401", resolve("8.0.100", "latestMajor"))
        assertNull(resolve("10.0.100", "latestPatch"))
        assertEquals("10.0.401", resolve("10.0.400", "patch"))
        assertNull(resolve("11.0.100", "latestMajor"))
    }

    fun testGlobalJsonFile() {
        // comments are allowed; without a version the newest SDK is fine
        val noVersion = GlobalJson.parse("{ /* pinned by CI */ \"sdk\": { \"allowPrerelease\": false } }")!!
        assertNull(noVersion.version)
        assertEquals("10.0.401", noVersion.resolve(listOf("10.0.401", "11.0.100-preview.1").map { SdkVersion.parse(it)!! })?.text)
        // a file that only pins MSBuild SDKs says nothing about the .NET SDK
        assertNull(GlobalJson.parse("{ \"msbuild-sdks\": { \"Foo.Sdk\": \"1.0.0\" } }"))
        assertNull(GlobalJson.parse("{ broken"))

        myFixture.addFileToProject("global.json", "{ \"sdk\": { \"version\": \"9.0.100\", \"rollForward\": \"latestFeature\" } }")
        val nested = myFixture.addFileToProject("src/App/App.csproj", "<Project/>").virtualFile.parent
        val (file, found) = GlobalJson.find(nested)!!
        assertEquals("global.json", file.name)
        assertEquals("9.0.100" to "latestFeature", found.version!!.text to found.rollForward)
    }

    fun testConfiguredExecutableWinsOverPath() {
        val settings = DotNetSettings.getInstance()
        val custom = FileUtil.createTempFile("dotnet", ".exe", true)
        settings.dotnetPath = "  ${custom.path}  "
        assertEquals(custom.path, settings.dotnetPath)
        assertEquals(custom.path, DotNetCli.findExecutable())

        // a path that no longer exists must not break everything: fall back to auto-detection
        settings.dotnetPath = custom.path + ".missing"
        assertEquals(DotNetCli.detectExecutable(), DotNetCli.findExecutable())
    }

    fun testRunConfigurationsCanBeSwitchedOff() {
        // the light project is shared between tests, and creating a solution file already triggers the generator
        val runManager = RunManager.getInstance(project)
        fun generated() = runManager.allSettings.filter { it.configuration is DotNetRunConfiguration }
        DotNetSettings.getInstance().createRunConfigurations = false
        generated().forEach(runManager::removeConfiguration)

        myFixture.addFileToProject("Web/Web.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"/>")
        myFixture.addFileToProject("All.slnx", "<Solution><Project Path=\"Web/Web.csproj\"/></Solution>")
        val generator = DotNetRunConfigurationGenerator.getInstance(project)
        // there is something to generate...
        assertEquals(listOf("Web"), generator.collectTargets().map { it.name })
        // ...but nothing is, neither by the file listener nor on request
        generator.schedule()
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(emptyList<String>(), generated().map { it.name })
    }

    fun testSettingsPageIsRegisteredAndBuilds() {
        // Settings | Tools | .NET
        val registration = Configurable.PROJECT_CONFIGURABLE.getExtensions(project).single { it.id == "io.github.dotnetsupport.settings" }
        assertEquals("tools", registration.parentId)
        val page = DotNetSettingsConfigurable(project)
        try {
            assertNotNull(page.createComponent())
            page.reset()
            assertFalse(page.isModified)
        } finally {
            page.disposeUIResources()
        }
    }
}
