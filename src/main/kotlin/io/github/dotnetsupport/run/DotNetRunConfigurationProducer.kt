package io.github.dotnetsupport.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.lang.CSharpLeaves
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.solution.SolutionService

/** Run configuration for the project that owns the context file: `dotnet test` for test projects, `dotnet run` for runnable ones. */
class DotNetRunConfigurationProducer : LazyRunConfigurationProducer<DotNetRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = DotNetConfigurationType.instance.factory

    override fun setupConfigurationFromContext(
        configuration: DotNetRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val (projectFile, command) = target(context) ?: return false
        configuration.options.projectPath = projectFile.path
        configuration.options.command = command
        configuration.name = projectFile.nameWithoutExtension
        return true
    }

    override fun isConfigurationFromContext(configuration: DotNetRunConfiguration, context: ConfigurationContext): Boolean {
        val (projectFile, command) = target(context) ?: return false
        // a configuration with a filter runs a part of the project: it belongs to the test producer
        return configuration.options.projectPath == projectFile.path && configuration.options.command == command &&
            configuration.options.testFilter.isNullOrBlank()
    }

    private fun target(context: ConfigurationContext): Pair<VirtualFile, DotNetCommand>? {
        val file = context.location?.virtualFile ?: return null
        val projectFile = DotNetProjects.findOwningProject(file) ?: return null
        val msBuildProject = SolutionService.getInstance(context.project).msBuildProject(projectFile)
        return when {
            msBuildProject.isTestProject -> projectFile to DotNetCommand.TEST
            msBuildProject.isRunnable -> projectFile to DotNetCommand.RUN
            else -> null
        }
    }
}

/** Gutter ▶ at `static ... Main(`, recognized by the leaves of either tree ([CSharpLeaves]): the heuristic one has no members to ask. */
class MainMethodRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (!CSharpLeaves.isIdentifier(element) || !element.textMatches("Main")) return null
        if (CSharpLeaves.codeLeaf(element, forward = true)?.let { CSharpLeaves.isPunctuation(it, "(") } != true) return null
        if (!isStaticMember(element)) return null
        return withExecutorActions(AllIcons.RunConfigurations.TestState.Run)
    }

    /** Looks for `static` among the modifiers and the return type before the name. */
    private fun isStaticMember(name: PsiElement): Boolean {
        var leaf = CSharpLeaves.codeLeaf(name, forward = false)
        repeat(MAX_TOKENS_BEFORE_NAME) {
            val current = leaf ?: return false
            if (STOPS.any { CSharpLeaves.isPunctuation(current, it) }) return false
            if (CSharpLeaves.isKeyword(current, "static")) return true
            leaf = CSharpLeaves.codeLeaf(current, forward = false)
        }
        return false
    }

    private companion object {
        // "public static async Task<int>" and an attribute or two
        const val MAX_TOKENS_BEFORE_NAME = 16
        val STOPS = listOf(";", "{", "}")
    }
}
