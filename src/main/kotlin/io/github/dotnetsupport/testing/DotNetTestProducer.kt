package io.github.dotnetsupport.testing

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpTokenTypes
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetConfigurationType
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.solution.SOLUTION_EXTENSIONS
import io.github.dotnetsupport.solution.SolutionService

/** Tests of a file, tokenized once per change of the file rather than once per identifier the gutter asks about. */
internal fun testTargets(file: PsiFile): List<TestTarget> = CachedValuesManager.getCachedValue(file) {
    CachedValueProvider.Result.create(TestDiscovery.targets(file.viewProvider.contents), file)
}

internal fun testTargetAt(element: PsiElement): TestTarget? {
    val file = element.containingFile as? CSharpFile ?: return null
    if (element.elementType != CSharpTokenTypes.IDENTIFIER) return null
    return testTargets(file).find { it.nameRange.startOffset == element.textRange.startOffset }
}

/** ▶ next to test methods and to the classes that contain them. */
class DotNetTestRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        val target = testTargetAt(element) ?: return null
        return withExecutorActions(if (target.methodName == null) AllIcons.RunConfigurations.TestState.Run_run else AllIcons.RunConfigurations.TestState.Run)
    }
}

/** `dotnet test --filter` for the test method or class under the caret. */
class DotNetTestConfigurationProducer : LazyRunConfigurationProducer<DotNetRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = DotNetConfigurationType.instance.factory

    override fun setupConfigurationFromContext(configuration: DotNetRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val (projectFile, target) = target(context) ?: return false
        configuration.options.projectPath = projectFile.path
        configuration.options.command = DotNetCommand.TEST
        configuration.options.testFilter = target.filter
        configuration.name = target.displayName
        return true
    }

    override fun isConfigurationFromContext(configuration: DotNetRunConfiguration, context: ConfigurationContext): Boolean {
        val (projectFile, target) = target(context) ?: return false
        val options = configuration.options
        return options.command == DotNetCommand.TEST && options.projectPath == projectFile.path && options.testFilter == target.filter
    }

    // more specific than "all tests of the project", which the same file also produces
    override fun isPreferredConfiguration(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean = true
    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean = other.configuration is DotNetRunConfiguration

    private fun target(context: ConfigurationContext): Pair<VirtualFile, TestTarget>? {
        val element = context.psiLocation ?: return null
        val target = testTargetAt(element) ?: return null
        val projectFile = element.containingFile?.virtualFile?.let(DotNetProjects::findOwningProject) ?: return null
        return projectFile to target
    }
}

/** Runs the tests of the selected test project (or of the whole solution) collecting coverage with coverlet. */
class RunTestsWithCoverageAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = target(e)
        e.presentation.isEnabledAndVisible = target != null
        if (target != null) e.presentation.text = "Run Tests of '${target.nameWithoutExtension}' with Coverage"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = target(e) ?: return
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration("${target.nameWithoutExtension} with Coverage", DotNetConfigurationType.instance.factory)
        (settings.configuration as DotNetRunConfiguration).options.apply {
            projectPath = target.path
            command = DotNetCommand.TEST
            collectCoverage = true
        }
        runManager.setTemporaryConfiguration(settings)
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    /** A test project, or a solution: `dotnet test` accepts both. */
    private fun target(e: AnActionEvent): VirtualFile? {
        val project = e.project ?: return null
        val file = SolutionContext.buildTarget(e) ?: return null
        return when {
            file.extension?.lowercase() in SOLUTION_EXTENSIONS -> file
            SolutionService.getInstance(project).msBuildProject(file).isTestProject -> file
            else -> null
        }
    }
}
