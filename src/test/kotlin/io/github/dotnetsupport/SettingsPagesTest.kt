package io.github.dotnetsupport

import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.build.DotNetBuildCommand
import io.github.dotnetsupport.build.DotNetBuildConfigurable
import io.github.dotnetsupport.build.DotNetBuildOptions
import io.github.dotnetsupport.build.MsBuildVerbosity
import io.github.dotnetsupport.build.SmartRestore
import io.github.dotnetsupport.coverage.CoverageProjectViewDecorator
import io.github.dotnetsupport.coverage.CoverageReport
import io.github.dotnetsupport.coverage.CoverageSettings
import io.github.dotnetsupport.coverage.CoverageSettingsConfigurable
import io.github.dotnetsupport.coverage.DotNetCoverageService
import io.github.dotnetsupport.coverage.FileCoverage
import io.github.dotnetsupport.coverage.LineCoverage
import io.github.dotnetsupport.coverage.NewCoverageAction
import io.github.dotnetsupport.lang.CSharpLanguage
import io.github.dotnetsupport.nuget.NuGetAutoRestore
import io.github.dotnetsupport.nuget.NuGetSettings
import io.github.dotnetsupport.nuget.NuGetSettingsConfigurable
import io.github.dotnetsupport.settings.DotNetDebuggerConfigurable
import java.io.File
import java.time.LocalDateTime
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel

class SettingsPagesTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            NuGetSettings.getInstance().loadState(NuGetSettings.Settings())
            CoverageSettings.getInstance().loadState(CoverageSettings.Settings())
            DotNetBuildOptions.getInstance(project).loadState(DotNetBuildOptions.Settings())
            DotNetCoverageService.getInstance(project).clear()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testBuildArguments() {
        assertEquals(listOf("-p:Platform=x64", "-p:DefineConstants=A B"), DotNetBuildOptions.propertyArguments(" Platform = x64 ;; =broken; nothing ;DefineConstants=A B"))

        val now = LocalDateTime.of(2026, 9, 21, 4, 3, 37)
        val logs = File("logs")
        fun arguments(command: DotNetBuildCommand, skipRestore: Boolean = false, configure: DotNetBuildOptions.Settings.() -> Unit = {}) =
            DotNetBuildOptions.arguments(DotNetBuildOptions.Settings().apply(configure), command, skipRestore, logs, now)

        assertTrue("the defaults add nothing", arguments(DotNetBuildCommand.BUILD).isEmpty())
        assertEquals(listOf("--no-restore"), arguments(DotNetBuildCommand.BUILD, skipRestore = true))
        assertEquals(
            listOf("-p:Platform=x64", "-m:4", "-v:detailed", "-fl", "-flp:logfile=${File(logs, "Build_2026_09_21_04_03_37.log").path};verbosity=diagnostic;encoding=UTF-8"),
            arguments(DotNetBuildCommand.BUILD) {
                globalProperties = "Platform=x64"; parallelProcesses = 4; outputVerbosity = MsBuildVerbosity.DETAILED; logToFile = true; fileVerbosity = MsBuildVerbosity.DIAGNOSTIC
            },
        )
        // a restore takes the properties and the options of the NuGet page, nothing of the build
        NuGetSettings.getInstance().noCache = true
        assertEquals(listOf("-p:Platform=x64", "--no-cache"), arguments(DotNetBuildCommand.RESTORE, skipRestore = true) { globalProperties = "Platform=x64"; parallelProcesses = 4 })
    }

    fun testSmartRestore() {
        assertTrue(SmartRestore.isUpToDate(100, listOf(90, 100)))
        assertFalse(SmartRestore.isUpToDate(100, listOf(90, 101)))

        val directory = FileUtil.createTempDirectory("smart-restore", null, true)
        val projectFile = File(directory, "App/App.csproj").apply { parentFile.mkdirs(); writeText("<Project/>") }
        assertFalse("never restored", SmartRestore.isUpToDate(projectFile))
        val assets = File(directory, "App/obj/project.assets.json").apply { parentFile.mkdirs(); writeText("{}") }
        projectFile.setLastModified(1_000_000); assets.setLastModified(2_000_000)
        assertTrue(SmartRestore.isUpToDate(projectFile))
        // central package versions up the tree decide the packages too
        File(directory, "Directory.Packages.props").apply { writeText("<Project/>"); setLastModified(3_000_000) }
        assertFalse(SmartRestore.isUpToDate(projectFile))
    }

    fun testNuGetSettings() {
        val settings = NuGetSettings.getInstance()
        assertTrue(settings.restoreArguments().isEmpty())
        settings.noCache = true; settings.interactive = true
        assertEquals(listOf("--no-cache", "--interactive"), settings.restoreArguments())

        assertTrue(NuGetAutoRestore.affectsPackages("/repo/App/App.csproj"))
        assertTrue(NuGetAutoRestore.affectsPackages("/repo/Directory.Packages.props"))
        assertTrue(NuGetAutoRestore.affectsPackages("/repo/NuGet.Config"))
        assertFalse("the restore writes there itself", NuGetAutoRestore.affectsPackages("/repo/App/obj/App.csproj.nuget.g.props"))
        assertFalse(NuGetAutoRestore.affectsPackages("/repo/App/Program.cs"))
    }

    fun testNewCoverageIsAppliedAsConfigured() {
        fun report(path: String, vararg hits: Int) = CoverageReport(listOf(FileCoverage(path, hits.withIndex().associate { (i, hit) -> i + 1 to LineCoverage(hit, 0, 0) })))
        val service = DotNetCoverageService.getInstance(project)
        val settings = CoverageSettings.getInstance()
        settings.activateView = false

        settings.onNewCoverage = NewCoverageAction.REPLACE
        service.gathered(report("/repo/A.cs", 1, 0), "first")
        service.gathered(report("/repo/B.cs", 1), "second")
        assertEquals(listOf("/repo/B.cs"), service.report.files.map { it.path })

        settings.onNewCoverage = NewCoverageAction.ADD
        service.gathered(report("/repo/A.cs", 0, 1), "third")
        service.gathered(report("/repo/A.cs", 1, 0), "fourth")
        assertEquals(setOf("/repo/A.cs", "/repo/B.cs"), service.report.files.map { it.path }.toSet())
        assertEquals("both lines of A are covered by one run or the other", 3, service.report.coveredLines)
        assertEquals("second + third + fourth", service.title)

        settings.onNewCoverage = NewCoverageAction.DO_NOT_APPLY
        service.gathered(report("/repo/C.cs", 1), "fifth")
        assertEquals(3, service.report.coveredLines)

        // nothing is shown: nothing to ask about
        service.clear()
        settings.onNewCoverage = NewCoverageAction.ASK
        service.gathered(report("/repo/C.cs", 1), "sixth")
        assertEquals("sixth", service.title)
    }

    fun testCoverageOfFilesAndFolders() {
        val report = CoverageReport(listOf(
            FileCoverage("C:\\repo\\App\\A.cs", mapOf(1 to LineCoverage(1, 0, 0), 2 to LineCoverage(0, 0, 0))),
            FileCoverage("C:/repo/App/Sub/B.cs", mapOf(1 to LineCoverage(1, 0, 0), 2 to LineCoverage(3, 0, 0))),
            FileCoverage("C:/repo/AppTests/T.cs", mapOf(1 to LineCoverage(0, 0, 0))),
        ))
        assertEquals(1 to 2, CoverageProjectViewDecorator.linesUnder(report, "C:/repo/App/A.cs", isDirectory = false))
        assertEquals(3 to 4, CoverageProjectViewDecorator.linesUnder(report, "C:/repo/App", isDirectory = true))
        assertEquals("AppTests is not under App", 3 to 5, CoverageProjectViewDecorator.linesUnder(report, "C:/repo", isDirectory = true))
        assertNull(CoverageProjectViewDecorator.linesUnder(report, "C:/repo/Other", isDirectory = true))
    }

    /** Every page of Rider is there, with the options the plugin has something behind and nothing else: no disabled placeholders. */
    fun testPagesHaveOnlyWorkingOptions() {
        fun build(page: UnnamedConfigurable): JComponent = page.createComponent()!!.also { page.reset() }
        fun JComponent.checkBoxes(): List<JCheckBox> = UIUtil.findComponentsOfType(this, JCheckBox::class.java)

        val pages = mapOf(
            "nuget" to build(NuGetSettingsConfigurable()), "build" to build(DotNetBuildConfigurable(project)),
            "debugger" to build(DotNetDebuggerConfigurable(project)), "coverage" to build(CoverageSettingsConfigurable()),
        )
        assertEquals(
            listOf("Include prerelease", "Automatically restore missing packages when necessary", "Smart Restore on Build", "Do not use the HTTP cache", "Allow interactive authentication"),
            pages.getValue("nuget").checkBoxes().map { it.text },
        )
        assertEquals(listOf("Run build after solution is loaded", "Restore NuGet packages before build", "Write MSBuild log to file"), pages.getValue("build").checkBoxes().map { it.text })
        assertEquals(listOf("Enable external source debug", "Allow property evaluations and other implicit function calls"), pages.getValue("debugger").checkBoxes().map { it.text })
        // off, unlike in Rider: there is no decompiler, see DotNetSettings
        assertFalse(pages.getValue("debugger").checkBoxes().first { it.text == "Enable external source debug" }.isSelected)
        assertEquals(listOf("Activate Coverage View", "Show coverage in the project view"), pages.getValue("coverage").checkBoxes().map { it.text })

        for ((name, page) in pages) {
            val disabled = UIUtil.findComponentsOfType(page, JComponent::class.java)
                .filter { !it.isEnabled && (it is JCheckBox || it is javax.swing.JComboBox<*> || it is javax.swing.JTextField || it is javax.swing.JSpinner || it is javax.swing.JButton) }
            assertTrue("$name has disabled controls: " + disabled.map { it.javaClass.simpleName }, disabled.isEmpty())
        }

        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        for (page in listOf("build", "nuget", "coverage", "debugger")) {
            assertTrue(page, "parentId=\"io.github.dotnetsupport.settings\" id=\"io.github.dotnetsupport.settings.$page\"" in pluginXml)
        }
    }

    fun testCSharpCodeStyle() {
        val provider = LanguageCodeStyleSettingsProvider.forLanguage(CSharpLanguage)!!
        assertEquals("C#", provider.language.displayName)
        val indent = CodeStyleSettingsManager.getInstance(project).createTemporarySettings().getCommonSettings(CSharpLanguage).indentOptions!!
        assertEquals(4, indent.INDENT_SIZE)
        assertFalse(indent.USE_TAB_CHARACTER)

        // the indent options of the platform and nothing that would need a formatter inside the IDE
        assertEquals(com.intellij.application.options.SmartIndentOptionsEditor::class.java, provider.indentOptionsEditor!!.javaClass)
    }
}
