package io.github.dotnetsupport.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.lang.CSharpTokenTypes
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
        return configuration.options.projectPath == projectFile.path && configuration.options.command == command
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

/** Gutter ▶ at `static ... Main(`. There is no parser, so the entry point is recognized by tokens. */
class MainMethodRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.elementType != CSharpTokenTypes.IDENTIFIER || element.text != "Main") return null
        if (nextCodeLeaf(element, forward = true)?.elementType != CSharpTokenTypes.LPAREN) return null
        if (!isStaticMember(element)) return null
        return withExecutorActions(AllIcons.RunConfigurations.TestState.Run)
    }

    /** Looks for `static` among the modifiers and the return type before the name. */
    private fun isStaticMember(name: PsiElement): Boolean {
        var leaf = nextCodeLeaf(name, forward = false)
        repeat(MAX_TOKENS_BEFORE_NAME) {
            val type = leaf?.elementType ?: return false
            if (type == CSharpTokenTypes.SEMICOLON || type == CSharpTokenTypes.LBRACE || type == CSharpTokenTypes.RBRACE) return false
            if (type == CSharpTokenTypes.KEYWORD && leaf?.text == "static") return true
            leaf = leaf?.let { nextCodeLeaf(it, forward = false) }
        }
        return false
    }

    private fun nextCodeLeaf(element: PsiElement, forward: Boolean): PsiElement? {
        var leaf = if (forward) PsiTreeUtil.nextLeaf(element) else PsiTreeUtil.prevLeaf(element)
        while (leaf != null && (leaf.elementType == TokenType.WHITE_SPACE || leaf.elementType in CSharpTokenTypes.COMMENTS)) {
            leaf = if (forward) PsiTreeUtil.nextLeaf(leaf) else PsiTreeUtil.prevLeaf(leaf)
        }
        return leaf
    }

    private companion object {
        // "public static async Task<int>" and an attribute or two
        const val MAX_TOKENS_BEFORE_NAME = 16
    }
}
