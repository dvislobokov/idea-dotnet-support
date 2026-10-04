package io.github.dotnetsupport.lang

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.dotnetsupport.DotNetBundle
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import org.jetbrains.annotations.TestOnly

/**
 * A feature of the C# editor that the plugin's own PSI (csharp-psi, CSHARP_PSI_MIGRATION.md) is to serve instead of today's path, one
 * switch each. [hasNative]: the native implementation exists and the switch is offered; false for all until step 9 brings the first one.
 * [needsIndexes]: the native one is blind while the IDE indexes. [title] is the English name, the Russian one is in the bundle.
 */
enum class CSharpFeature(val title: String, val needsIndexes: Boolean, val hasNative: Boolean = false) {
    /** Structure view, folding, breadcrumbs: today the declaration scanner of the plugin (`CSharpDeclarations`). */
    SYNTAX_TREE("Structure, folding and breadcrumbs", needsIndexes = false),
    FORMATTING("Formatting", needsIndexes = false),
    /** Complete Statement, gray text, Extend Selection. */
    EDITING("Typing assistance", needsIndexes = false),
    /** The kind of a usage in Find Usages (write, call, nameof, attribute): the server does not send it. */
    USAGE_KINDS("Kinds of usages", needsIndexes = true),
    NAVIGATION("Navigation and usages", needsIndexes = true),
    COMPLETION("Completion", needsIndexes = true),
    DOCUMENTATION("Documentation and parameter info", needsIndexes = true),
    DIAGNOSTICS("Errors and warnings", needsIndexes = true),
    SEMANTIC_COLORS("Colors of identifiers", needsIndexes = true),
    RENAME("Rename", needsIndexes = true);

    // in the language of the settings page; not `toString()`: see [io.github.dotnetsupport.PluginLanguage.label]
    val label: String get() = DotNetBundle.messageOr("feature.$name", title)
}

/**
 * Who answers for a [CSharpFeature]. [ROSLYN] is the path of today, whatever it is for the feature: the language server where it answers,
 * the heuristics of the plugin where it does not (and while it is not ready, see `RoslynServerStatus`). [NATIVE] is csharp-psi.
 */
enum class CSharpFeatureSource(val title: String) {
    ROSLYN("Language server"),
    NATIVE("Built-in");

    val label: String get() = DotNetBundle.messageOr("feature.source.$name", title)
}

/**
 * One source per feature at a time (CSHARP_PSI_MIGRATION.md, principles 1–2): a handler of the module `io.github.dotnetsupport.roslyn`
 * stands down when [native] is true, a native one when it is false. Exclusive switches rather than the order of extensions, so two
 * answers never meet.
 *
 * NATIVE exists only where there is a native implementation ([CSharpFeature.hasNative]): the page offers the switch for those features
 * alone, and a stored NATIVE of a feature without one is ignored (an older or newer version of the plugin may have written it). So today,
 * with no native code yet, every feature answers ROSLYN, the page shows no switch, and nothing changes for the user.
 */
object CSharpFeatures {
    @Volatile
    private var implementedForTests: Set<CSharpFeature>? = null

    fun hasNative(feature: CSharpFeature): Boolean = implementedForTests?.contains(feature) ?: feature.hasNative

    /** The features whose switch the settings page offers. */
    fun offered(): List<CSharpFeature> = CSharpFeature.entries.filter(::hasNative)

    /**
     * The pure rule. No native implementation: today's path, always. Without the language server whatever is built in works, there is
     * nothing else to ask; with it the switch decides. While the IDE indexes, a native feature that needs the indexes yields to today's path.
     */
    fun native(hasNative: Boolean, source: CSharpFeatureSource, serverEnabled: Boolean, needsIndexes: Boolean, dumb: Boolean): Boolean = when {
        !hasNative -> false
        needsIndexes && dumb -> false
        !serverEnabled -> true
        else -> source == CSharpFeatureSource.NATIVE
    }

    /** Settings and dumb mode only, no PSI: safe on the EDT and in the background without a read action. */
    fun native(feature: CSharpFeature, project: Project): Boolean {
        if (!hasNative(feature)) return false
        val settings = RoslynLanguageServerSettings.getInstance()
        return native(true, settings.source(feature), settings.state.enabled, feature.needsIndexes, feature.needsIndexes && DumbService.isDumb(project))
    }

    /** Pretends [features] have a native implementation until [disposable] is disposed: the plumbing is tested before the native code exists. */
    @TestOnly
    fun implementForTests(features: Set<CSharpFeature>, disposable: Disposable) {
        implementedForTests = features
        Disposer.register(disposable) { implementedForTests = null }
    }
}
