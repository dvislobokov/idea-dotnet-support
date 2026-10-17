package io.github.dotnetsupport.lang

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * A feature of the C# editor answered by the plugin's own C# (csharp-psi, CSHARP_PSI_MIGRATION.md). [needsIndexes]: the feature reads the
 * stub index or the index of assemblies, so while the IDE indexes it stands back and the plugin's heuristics answer (the colors of the
 * declaration scanner, the syntax errors alone). Until 0.1.262 each feature also had a switch between the plugin and `roslyn-language-server`;
 * the server is gone, the plugin answers for all of them.
 */
enum class CSharpFeature(val needsIndexes: Boolean) {
    /** Structure view, folding, breadcrumbs: the tree of csharp-psi. */
    SYNTAX_TREE(needsIndexes = false),

    /** Reformat Code, Reformat Selection, Auto-Indent Lines ([NativeCSharpFormatting]). */
    FORMATTING(needsIndexes = false),

    /** Complete Statement, gray text, Extend Selection ([NativeCSharpEditing]). */
    EDITING(needsIndexes = false),

    /** The kind of a usage in Find Usages: write, call, nameof, attribute ([NativeCSharpUsageKinds]). */
    USAGE_KINDS(needsIndexes = false),

    /** Go to Declaration, Find Usages, Go to Implementation / Super, the hierarchies ([NativeCSharpNavigation], [NativeCSharpFindUsages]). */
    NAVIGATION(needsIndexes = true),

    /** The completion list ([NativeCSharpCompletionContributor]). */
    COMPLETION(needsIndexes = true),

    /** Quick documentation and parameter info ([NativeCSharpDocumentationTargetProvider], [NativeCSharpParameterInfoHandler]). */
    DOCUMENTATION(needsIndexes = true),

    /** Errors and warnings: the syntax ones need no index ([NativeCSharpDiagnostics]), the semantic ones stay silent while it is built. */
    DIAGNOSTICS(needsIndexes = false),

    /** The colors of identifiers in the palette of Rider ([NativeCSharpSemanticColors]). */
    SEMANTIC_COLORS(needsIndexes = true),

    /** Shift+F6 ([NativeCSharpRename], [NativeCSharpSolutionRename]). */
    RENAME(needsIndexes = true),

    /** Alt+Enter context actions of the code ([NativeCSharpContextAction]). */
    CONTEXT_ACTIONS(needsIndexes = true),

    /** Inlay hints: parameter names and types ([NativeCSharpInlayHints]). */
    INLAY_HINTS(needsIndexes = true),

    /** Code Vision above types and members ([NativeCSharpCodeLens]). */
    CODE_LENS(needsIndexes = true),
}

/**
 * Whether the plugin's own implementation of a [CSharpFeature] answers now: always, except a feature that needs the indexes while the IDE
 * builds them — then the heuristics of the plugin answer, or nothing.
 */
object CSharpFeatures {
    fun native(feature: CSharpFeature, project: Project): Boolean = native(feature, dumb = feature.needsIndexes && DumbService.isDumb(project))

    /** [native] for a feature of [file]; false without a file. */
    fun native(feature: CSharpFeature, file: PsiFile?): Boolean = file != null && native(feature, file.project)

    /** For who has no project (the parser definition, `CSharpSyntaxTrees.nativeTree`): [dumb] is whether the IDE indexes. */
    fun native(feature: CSharpFeature, dumb: Boolean = false): Boolean = !(feature.needsIndexes && dumb)
}
