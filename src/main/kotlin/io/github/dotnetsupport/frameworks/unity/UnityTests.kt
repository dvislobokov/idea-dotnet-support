package io.github.dotnetsupport.frameworks.unity

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.ui.components.JBTextArea
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpLeaves
import io.github.dotnetsupport.testing.DotNetTestLocator
import io.github.dotnetsupport.testing.TestDiscovery
import io.github.dotnetsupport.testing.TestTarget
import io.github.dotnetsupport.testing.TrxEventsConverter
import io.github.dotnetsupport.testing.UnitTestsRunProfile
import org.jdom.Element
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JComponent

/**
 * Tests of the Unity Test Framework run by the open editor (UNITY_PLAN.md, step 4; `Tests/TestRuns.cs` of the package): the command
 * `run-tests` with the mode and the filters, the events in `Temp/DotNetSupport/tests/<run>/events.jsonl` in the format of the test logger of
 * `dotnet test`, so the run is the same tree in the Unit Tests window. Pure parts here: the tests give them the text.
 */
object UnityTests {
    enum class Mode { EditMode, PlayMode }

    fun runsFolder(root: File): File = File(UnityEditorLink.folder(root), "tests")

    /** The command for the package: the header, then one filter per line. */
    fun command(run: String, mode: Mode, groups: List<String>): String =
        (listOf("run-tests $run ${mode.name}") + groups.map { "group:$it" }).joinToString("\n")

    /**
     * The filter of a class or a method as a regular expression over the full names of the tests (Unity's `groupNames`): a class takes its
     * methods (and not `OrderTestsBase` of `OrderTests`), a method its cases (`Damage(10,5)`).
     */
    fun group(target: TestTarget): String {
        val className = escape(target.className.replace('+', '.'))
        return if (target.methodName == null) "^$className\\." else "^$className\\.${escape(target.methodName!!)}(\\(|$)"
    }

    // by hand: Regex.escape of the JVM gives \Q…\E, which .NET does not know
    private fun escape(text: String): String = text.replace(Regex("""[\\.$^{\[(|)*+?]""")) { "\\" + it.value }

    /** Whether the tests of [file] are of edit mode: their assembly definition is for the editor only, or they are under an `Editor` folder. */
    fun modeOf(file: VirtualFile, root: VirtualFile): Mode {
        var directory = file.parent
        while (directory != null && directory != root) {
            directory.children.firstOrNull { it.extension.equals("asmdef", ignoreCase = true) }?.let { asmdef ->
                val text = runCatching { String(asmdef.contentsToByteArray()) }.getOrDefault("")
                val platforms = Regex(""""includePlatforms"\s*:\s*\[([^\]]*)]""").find(text)?.groupValues?.get(1).orEmpty()
                return if (Regex(""""Editor"""").containsMatchIn(platforms)) Mode.EditMode else Mode.PlayMode
            }
            directory = directory.parent
        }
        return if ("/Editor/" in file.path) Mode.EditMode else Mode.PlayMode
    }

    /** Whether [file] (`events.jsonl`) has the end of its run. */
    fun isComplete(file: File): Boolean = file.isFile && runCatching { file.readText().contains("\"event\":\"runComplete\"") }.getOrDefault(false)
}

class UnityTestConfigurationType : ConfigurationType {
    private val factory = object : ConfigurationFactory(this) {
        override fun getId(): String = "UnityTests"
        override fun createTemplateConfiguration(project: Project): RunConfiguration = UnityTestRunConfiguration(project, this, "Unity Tests")
    }

    override fun getDisplayName(): String = "Unity Tests"
    override fun getConfigurationTypeDescription(): String = "Tests of the Unity Test Framework, run by the open Unity editor"
    override fun getIcon() = UnityIcons.Unity
    override fun getId(): String = "DotNetUnityTests"
    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(factory)

    val configurationFactory: ConfigurationFactory get() = factory

    companion object {
        val instance: UnityTestConfigurationType get() = ConfigurationTypeUtil.findConfigurationType(UnityTestConfigurationType::class.java)
    }
}

class UnityTestRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<Any>(project, factory, name), UnitTestsRunProfile {
    var mode: UnityTests.Mode = UnityTests.Mode.EditMode
    /** Regular expressions over the full names of the tests; none: every test of the mode. */
    var groups: List<String> = emptyList()

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = Editor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = UnityTestRunState(this, environment)

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("mode", mode.name)
        groups.forEach { element.addContent(Element("group").setText(it)) }
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        mode = element.getAttributeValue("mode")?.let { value -> UnityTests.Mode.entries.firstOrNull { it.name == value } } ?: UnityTests.Mode.EditMode
        groups = element.getChildren("group").map { it.text }
    }

    private class Editor : SettingsEditor<UnityTestRunConfiguration>() {
        private val mode = ComboBox(UnityTests.Mode.entries.toTypedArray())
        private val groups = JBTextArea(4, 60)

        override fun resetEditorFrom(configuration: UnityTestRunConfiguration) {
            mode.selectedItem = configuration.mode
            groups.text = configuration.groups.joinToString("\n")
        }

        override fun applyEditorTo(configuration: UnityTestRunConfiguration) {
            configuration.mode = mode.selectedItem as UnityTests.Mode
            configuration.groups = groups.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }

        override fun createEditor(): JComponent = FormBuilder.createFormBuilder()
            .addLabeledComponent("Test mode:", mode)
            .addLabeledComponent("Tests (regular expressions over full names, one per line; empty: all):", groups, true)
            .panel
    }
}

/** A run: the command to the editor, a process that ends with the run (or when the editor goes), the test tree of `dotnet test`. */
class UnityTestRunState(private val configuration: UnityTestRunConfiguration, private val environment: ExecutionEnvironment) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = configuration.project
        val connection = UnityEditorConnection.getInstance(project)
        connection.update()
        val link = connection.link
        if (link?.status != UnityEditorLink.Status.CONNECTED) throw ExecutionException(UnityEditorStatusWidget.describe(link))
        val run = "run-" + System.currentTimeMillis()
        val folder = File(UnityTests.runsFolder(link.root), run).apply { mkdirs() }
        val handler = UnityTestProcessHandler(connection, File(folder, "events.jsonl"))
        val properties = UnityTestConsoleProperties(configuration, executor, File(link.root, "Assets").path, folder)
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(UnityTestConsoleProperties.FRAMEWORK, handler, properties)
        handler.startNotify()
        if (!connection.send(UnityTests.command(run, configuration.mode, configuration.groups))) {
            handler.finish(1)
            throw ExecutionException("The command did not reach the Unity editor")
        }
        return DefaultExecutionResult(console, handler)
    }
}

/** No process of its own: it ends when the editor writes the end of the run, or when the editor is gone; Stop cancels the run. */
class UnityTestProcessHandler(private val connection: UnityEditorConnection, private val events: File) : ProcessHandler() {
    private val watch: ScheduledFuture<*> = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(::check, 300, 300, TimeUnit.MILLISECONDS)

    private fun check() {
        when {
            UnityTests.isComplete(events) -> finish(0)
            connection.link?.status == UnityEditorLink.Status.NO_EDITOR -> finish(1)
        }
    }

    fun finish(exitCode: Int) {
        watch.cancel(false)
        if (!isProcessTerminated) notifyProcessTerminated(exitCode)
    }

    override fun destroyProcessImpl() {
        connection.send("cancel-tests")
        // the editor writes the end of a cancelled run; the run ends anyway if it does not
        AppExecutorUtil.getAppScheduledExecutorService().schedule({ finish(1) }, 2, TimeUnit.SECONDS)
    }

    override fun detachProcessImpl() = finish(0)
    override fun detachIsDefault(): Boolean = false
    override fun getProcessInput(): OutputStream? = null
}

/** The tree of `dotnet test` over the events of the editor; no TRX report comes, so the results folder stays empty. */
class UnityTestConsoleProperties(configuration: UnityTestRunConfiguration, executor: Executor, private val assets: String, private val events: File) :
    SMTRunnerConsoleProperties(configuration, FRAMEWORK, executor), SMCustomMessagesParsing {
    init {
        isIdBasedTestTree = true
        setIfUndefined(TestConsoleProperties.HIDE_PASSED_TESTS, false)
        setIfUndefined(TestConsoleProperties.HIDE_IGNORED_TEST, false)
    }

    override fun createTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties): OutputToGeneralTestEventsConverter =
        TrxEventsConverter(testFrameworkName, consoleProperties, FileUtil.createTempDirectory("unity-tests", null, true), assets, events)

    override fun getTestLocator(): SMTestLocator = DotNetTestLocator

    companion object {
        const val FRAMEWORK = "UnityTest"
    }
}

/** ▶ of a test (or of its class) in a Unity project runs it in the editor: this replaces the `dotnet test` the same place produces. */
class UnityTestConfigurationProducer : LazyRunConfigurationProducer<UnityTestRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = UnityTestConfigurationType.instance.configurationFactory

    override fun setupConfigurationFromContext(configuration: UnityTestRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val (target, mode) = target(context) ?: return false
        configuration.mode = mode
        configuration.groups = listOf(UnityTests.group(target))
        configuration.name = target.displayName
        return true
    }

    override fun isConfigurationFromContext(configuration: UnityTestRunConfiguration, context: ConfigurationContext): Boolean {
        val (target, mode) = target(context) ?: return false
        return configuration.mode == mode && configuration.groups == listOf(UnityTests.group(target))
    }

    override fun isPreferredConfiguration(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean = true
    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean = other.configuration !is UnityTestRunConfiguration

    private fun target(context: ConfigurationContext): Pair<TestTarget, UnityTests.Mode>? {
        val element = context.psiLocation ?: return null
        val file = element.containingFile as? CSharpFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val root = UnityProjects.rootOf(virtualFile) ?: return null
        if (!CSharpLeaves.isIdentifier(element)) return null
        val target = targets(file).find { it.nameRange.startOffset == element.textRange.startOffset } ?: return null
        return target to UnityTests.modeOf(virtualFile, root)
    }

    private fun targets(file: PsiFile): List<TestTarget> = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(TestDiscovery.targets(file.viewProvider.contents), file)
    }
}
