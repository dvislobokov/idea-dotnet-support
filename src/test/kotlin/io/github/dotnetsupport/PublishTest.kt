package io.github.dotnetsupport

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.publish.DotNetPublishConfigurationType
import io.github.dotnetsupport.publish.DotNetPublishRunConfiguration
import io.github.dotnetsupport.publish.PublishAction
import io.github.dotnetsupport.publish.PublishCommand
import io.github.dotnetsupport.publish.PublishOptions
import io.github.dotnetsupport.publish.PublishProfiles
import io.github.dotnetsupport.publish.PublishSettings
import io.github.dotnetsupport.sdk.SdkVersion
import java.io.File
import java.nio.file.Files

class PublishTest : BasePlatformTestCase() {
    private val root = File(if (File.separatorChar == '/') "/work/Shop" else "C:/work/Shop").absoluteFile
    private val projectPath = File(root, "Shop.csproj").path
    private fun out(vararg parts: String) = File(root, parts.joinToString(File.separator)).path

    private fun resource(name: String): String = javaClass.getResourceAsStream("/publish/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    fun testFrameworkDependentPortablePublish() {
        val options = PublishOptions(projectPath, framework = "net9.0")
        assertEquals(
            listOf("publish", projectPath, "-c", "Release", "-f", "net9.0", "-o", out("bin", "Release", "net9.0", "publish"), "-nologo", "-clp:NoSummary"),
            PublishCommand.arguments(options),
        )
        assertNull(PublishCommand.validate(options))
    }

    fun testSelfContainedSingleFileForARuntime() {
        val options = PublishOptions(projectPath, framework = "net9.0", runtime = "win-x64", selfContained = true, singleFile = true, trimmed = true, readyToRun = true)
        assertEquals(
            listOf(
                "publish", projectPath, "-c", "Release", "-f", "net9.0", "-r", "win-x64", "--self-contained", "true",
                "-p:PublishSingleFile=true", "-p:PublishTrimmed=true", "-p:PublishReadyToRun=true",
                "-o", out("bin", "Release", "net9.0", "win-x64", "publish"), "-nologo", "-clp:NoSummary",
            ),
            PublishCommand.arguments(options),
        )
    }

    fun testFrameworkDependentWithARuntimeSaysSoAndNeverTrims() {
        val arguments = PublishCommand.arguments(PublishOptions(projectPath, configuration = "Debug", framework = "net8.0", runtime = "linux-x64", trimmed = true))
        assertEquals(listOf("-r", "linux-x64", "--self-contained", "false"), arguments.subList(6, 10))
        assertFalse(arguments.toString(), arguments.any { it.startsWith("-p:PublishTrimmed") })
        assertTrue(arguments.contains(out("bin", "Debug", "net8.0", "linux-x64", "publish")))
    }

    fun testValidation() {
        assertEquals("Project is not specified", PublishCommand.validate(PublishOptions("")))
        assertEquals("Self-contained, single file, trimming and ReadyToRun need a target runtime", PublishCommand.validate(PublishOptions(projectPath, singleFile = true)))
        assertEquals("Trimming needs the Self-Contained deployment mode", PublishCommand.validate(PublishOptions(projectPath, runtime = "win-x64", trimmed = true)))
        assertNull(PublishCommand.validate(PublishOptions(projectPath, runtime = "win-x64", singleFile = true)))
    }

    fun testOutputFolderRelativeOrAbsolute() {
        assertEquals(out("artifacts", "app"), PublishCommand.outputDirectory(PublishOptions(projectPath, outputDir = "artifacts\\app\\")).path)
        assertEquals(out("artifacts", "app"), PublishCommand.outputDirectory(PublishOptions(projectPath, outputDir = "artifacts/app")).path)
        val absolute = File(root.parentFile, "drop").path
        assertEquals(absolute, PublishCommand.outputDirectory(PublishOptions(projectPath, outputDir = absolute)).path)
    }

    fun testProfileAndExplicitSwitches() {
        val arguments = PublishCommand.arguments(PublishOptions(projectPath, framework = "net9.0", runtime = "win-x64", singleFile = true, profile = "FolderProfile"))
        // with a profile what the dialog shows wins over it: false is passed too
        assertTrue(arguments.containsAll(listOf("-p:PublishSingleFile=true", "-p:PublishTrimmed=false", "-p:PublishReadyToRun=false", "-p:PublishProfile=FolderProfile")))
    }

    fun testContainer() {
        val arguments = PublishCommand.arguments(PublishOptions(projectPath, framework = "net9.0", container = true, containerRepository = "shop", containerTag = "1.2"))
        assertEquals(listOf("-t:PublishContainer", "-p:ContainerRepository=shop", "-p:ContainerImageTag=1.2"), arguments.subList(arguments.indexOf("-t:PublishContainer"), arguments.size - 2))
        assertTrue(PublishCommand.displayString(PublishOptions(projectPath)).startsWith("dotnet publish "))

        assertNull(PublishCommand.containerUnsupportedReason(SdkVersion.parse("8.0.300"), webOrWorker = false, enableSdkContainerSupport = false))
        assertNull(PublishCommand.containerUnsupportedReason(SdkVersion.parse("8.0.100"), webOrWorker = true, enableSdkContainerSupport = false))
        assertNull(PublishCommand.containerUnsupportedReason(SdkVersion.parse("8.0.100"), webOrWorker = false, enableSdkContainerSupport = true))
        assertNotNull(PublishCommand.containerUnsupportedReason(SdkVersion.parse("8.0.100"), webOrWorker = false, enableSdkContainerSupport = false))
        assertNotNull(PublishCommand.containerUnsupportedReason(SdkVersion.parse("7.0.410"), webOrWorker = true, enableSdkContainerSupport = true))
        assertNotNull(PublishCommand.containerUnsupportedReason(null, webOrWorker = true, enableSdkContainerSupport = true))
    }

    fun testOptionsSurviveTheirProperties() {
        val options = PublishOptions(
            projectPath, "Staging", "net8.0", "osx-arm64", selfContained = true, singleFile = true, trimmed = true, readyToRun = false,
            outputDir = "out", profile = "Mac", container = true, containerRepository = "shop", containerTag = "dev",
        )
        assertEquals(options, PublishOptions.fromProperties(projectPath, options.toProperties()))
        assertEquals(PublishOptions(projectPath), PublishOptions.fromProperties(projectPath, emptyMap()))
    }

    fun testVisualStudio2022ConsoleProfile() {
        val options = PublishProfiles.apply(PublishOptions(projectPath, framework = "net9.0"), "FolderProfile", PublishProfiles.parse(resource("vs2022-console.pubxml")))
        assertEquals(
            PublishOptions(projectPath, "Release", "net8.0", "win-x64", selfContained = true, singleFile = true, outputDir = "bin\\Release\\net8.0\\publish\\win-x64\\", profile = "FolderProfile"),
            options,
        )
        assertEquals(out("bin", "Release", "net8.0", "publish", "win-x64"), PublishCommand.outputDirectory(options).path)
    }

    fun testVisualStudioWebProfilesUsePublishUrlAndTheLastConfiguration() {
        val base = PublishOptions(projectPath, configuration = "Debug", framework = "net9.0", runtime = "win-x64", singleFile = true)
        val vs2022 = PublishProfiles.apply(base, "FolderProfile", PublishProfiles.parse(resource("vs2022-web.pubxml")))
        assertEquals(PublishOptions(projectPath, "Release", "net8.0", outputDir = "bin\\Release\\net8.0\\publish\\", profile = "FolderProfile"), vs2022)

        // the MSBuild namespace and the lower-case publishUrl of Visual Studio 2019
        val vs2019 = PublishProfiles.apply(base, "IIS", PublishProfiles.parse(resource("vs2019-web.pubxml")))
        assertEquals(PublishOptions(projectPath, "Staging", "netcoreapp3.1", "linux-x64", selfContained = true, outputDir = "C:\\inetpub\\wwwroot\\MyApp", profile = "IIS"), vs2019)
        assertEquals(emptyMap<String, String>(), PublishProfiles.parse("<Project><PropertyGroup>"))
    }

    fun testWrittenProfileIsReadBackAndOpensInVisualStudio() {
        val options = PublishOptions(projectPath, "Release", "net9.0", "win-x64", selfContained = true, singleFile = true, readyToRun = true, profile = "WinX64")
        val text = PublishProfiles.write(options)
        assertTrue(text, text.contains("<PublishDir>bin\\Release\\net9.0\\win-x64\\publish\\</PublishDir>"))
        assertTrue(text, text.contains("<_TargetId>Folder</_TargetId>") && text.contains("<PublishProtocol>FileSystem</PublishProtocol>"))
        val read = PublishProfiles.apply(PublishOptions(projectPath), "WinX64", PublishProfiles.parse(text))
        assertEquals(options.copy(outputDir = "bin\\Release\\net9.0\\win-x64\\publish\\"), read)
        assertEquals(PublishCommand.outputDirectory(options), PublishCommand.outputDirectory(read))

        // portable: no switches that need a runtime; a folder outside of the project stays absolute; values are escaped
        val elsewhere = File(root.parentFile, "drop & co").path
        val portable = PublishProfiles.write(PublishOptions(projectPath, framework = "net9.0", outputDir = elsewhere))
        assertFalse(portable, portable.contains("PublishSingleFile") || portable.contains("RuntimeIdentifier"))
        assertTrue(portable, portable.contains("<SelfContained>false</SelfContained>") && portable.contains("drop &amp; co"))
        assertEquals(elsewhere, PublishProfiles.parse(portable)["PublishDir"])
    }

    fun testProfilesOfTheProjectAreListedAndLoaded() {
        val directory = Files.createTempDirectory("publish").toFile()
        try {
            val project = File(directory, "App.csproj").apply { writeText("<Project Sdk=\"Microsoft.NET.Sdk\" />") }
            assertEquals(emptyList<String>(), PublishProfiles.list(directory))
            val profile = PublishProfiles.file(directory, "Linux")
            profile.parentFile.mkdirs()
            profile.writeText(PublishProfiles.write(PublishOptions(project.path, framework = "net9.0", runtime = "linux-x64")))
            File(profile.parentFile, "notes.txt").writeText("")
            File(profile.parentFile, "FolderProfile.pubxml").writeText(resource("vs2022-console.pubxml"))
            assertEquals(listOf("FolderProfile", "Linux"), PublishProfiles.list(directory))
            val loaded = PublishProfiles.load(PublishOptions(project.path, framework = "net8.0"), "Linux")
            assertEquals("linux-x64", loaded.runtime)
            assertEquals("net9.0", loaded.framework)
            assertEquals("Missing", PublishProfiles.load(PublishOptions(project.path), "Missing").profile)
        } finally {
            directory.deleteRecursively()
        }
    }

    fun testFormShowsWhatItIsGivenAndDropsWhatThePortableRuntimeCannotDo() {
        val directory = Files.createTempDirectory("publish-form").toFile()
        try {
            val file = File(directory, "App.csproj").apply {
                writeText("<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><OutputType>Exe</OutputType><TargetFrameworks>net8.0;net9.0</TargetFrameworks></PropertyGroup></Project>")
            }
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
            val form = io.github.dotnetsupport.publish.PublishForm(project)
            val options = PublishOptions(file.path, "Debug", "net8.0", "linux-arm64", selfContained = true, singleFile = true, trimmed = true, readyToRun = true,
                outputDir = "out", container = true, containerRepository = "app", containerTag = "1")
            form.reset(options)
            assertEquals(options.copy(projectPath = form.options.projectPath), form.options)
            assertEquals(File(file.path).canonicalPath, File(form.options.projectPath).canonicalPath)
            form.reset(options.copy(runtime = null))
            val portable = form.options
            assertFalse(portable.selfContained || portable.singleFile || portable.trimmed || portable.readyToRun)
            assertNull(PublishCommand.validate(portable))
        } finally {
            directory.deleteRecursively()
        }
    }

    fun testLastChoicesAreRememberedPerProject() {
        val settings = PublishSettings.getInstance(project)
        val path = File(root, "Remembered.csproj").path
        assertNull(settings.last(path))
        val options = PublishOptions(path, framework = "net9.0", runtime = "linux-musl-x64", selfContained = true)
        settings.remember(options)
        assertEquals(options, settings.last(path))
        assertNull(settings.last(projectPath))
        settings.state.projects = settings.state.projects.toMutableMap().apply { remove(path) }
    }

    fun testActionIsInTheSolutionViewAndTheDotNetMenu() {
        val actions = ActionManager.getInstance()
        assertTrue(actions.getAction("DotNet.Publish") is PublishAction)
        // in the popup of the Solution view it is one of the rarer commands, under Tools
        for (parent in listOf("DotNet.MainMenu", "DotNet.ProjectTools")) {
            val children = (actions.getAction(parent) as DefaultActionGroup).childActionsOrStubs.map { actions.getId(it) }
            assertTrue(parent, "DotNet.Publish" in children)
        }
        val menu = (actions.getAction("DotNet.MainMenu") as DefaultActionGroup).childActionsOrStubs.map { actions.getId(it) }
        assertEquals("after the build commands", menu.indexOf("DotNet.BuildPerformance") + 1, menu.indexOf("DotNet.Publish"))
    }

    fun testRunConfigurationIsSavedAndUpdated() {
        assertTrue(ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.any { it is DotNetPublishConfigurationType })
        val runManager = RunManager.getInstance(project)
        val path = File(root, "Saved.csproj").path
        val first = DotNetPublishRunConfiguration.save(project, PublishOptions(path, framework = "net9.0"))
        try {
            assertEquals("Publish Saved", first.name)
            assertSame(first, runManager.selectedConfiguration)
            val again = DotNetPublishRunConfiguration.save(project, PublishOptions(path, framework = "net9.0", runtime = "win-x64", selfContained = true))
            assertSame(first, again)
            assertEquals(1, runManager.getConfigurationSettingsList(DotNetPublishConfigurationType.instance).size)
            val configuration = again.configuration as DotNetPublishRunConfiguration
            assertEquals("win-x64", configuration.publishOptions.runtime)
            assertEquals("win-x64", configuration.options.properties["RuntimeIdentifier"])
            assertEquals("Publish Saved", configuration.suggestedName())
            // the project file is not there
            assertTrue(checkError(configuration).startsWith("Project file not found"))
            configuration.publishOptions = PublishOptions(path, runtime = null, trimmed = true)
            assertTrue(checkError(configuration).contains("target runtime"))
        } finally {
            runManager.removeConfiguration(first)
        }
    }

    private fun checkError(configuration: DotNetPublishRunConfiguration): String = try {
        configuration.checkConfiguration()
        fail("no error")
        ""
    } catch (e: RuntimeConfigurationError) {
        e.localizedMessage
    }
}
