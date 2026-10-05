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
 * switch each. [hasNative]: the native implementation exists and the switch is offered (the tree of step 7 is the first one).
 * [needsIndexes]: the native one is blind while the IDE indexes. [defaultSource]: what answers until the user chooses; NATIVE once the
 * native implementation has passed the corpus gates and the robot (principle 1). [title] is the English name, the Russian one is in the bundle.
 */
enum class CSharpFeature(
    val title: String, val needsIndexes: Boolean, val hasNative: Boolean = false, val defaultSource: CSharpFeatureSource = CSharpFeatureSource.ROSLYN,
) {
    /** Structure view, folding, breadcrumbs: today the declaration scanner of the plugin (`CSharpDeclarations`), behind `CSharpSyntaxModel`. */
    /** NATIVE by default since 0.1.45: the snapshots, the corpus gates and the robot on `debug-playground` matched the heuristics or bettered them. */
    SYNTAX_TREE("Structure, folding and breadcrumbs", needsIndexes = false, hasNative = true, defaultSource = CSharpFeatureSource.NATIVE),
    /**
     * Reformat Code, Reformat Selection, Auto-Indent Lines. Native since 0.1.49 ([NativeCSharpFormatting]): the whitespace rules of
     * `dotnet format whitespace` on the file's own tree, in place of the server and of the `dotnet format` process; CSharpier stays
     * CSharpier. ROSLYN by default until the robot has checked it.
     */
    /** NATIVE by default since 0.1.49: Reformat of a method, a `switch`, initializers and the whole playground file matched the server (robot). */
    FORMATTING("Formatting", needsIndexes = false, hasNative = true, defaultSource = CSharpFeatureSource.NATIVE),
    /**
     * Complete Statement, gray text, Extend Selection. Native since 0.1.48 ([NativeCSharpEditing]): the statement at the caret, the nodes
     * around it and the `;` of the gray text come from the file's own tree; no index. ROSLYN by default until the robot has checked it.
     */
    /** NATIVE by default since 0.1.48: Extend Selection, Complete Statement and the gray `;` matched the scenarios on the playground (robot). */
    EDITING("Typing assistance", needsIndexes = false, hasNative = true, defaultSource = CSharpFeatureSource.NATIVE),
    /**
     * The kind of a usage in Find Usages (write, call, nameof, attribute): the server does not send it. Native since 0.1.46 on the file's
     * own tree ([NativeCSharpUsageKinds]); it reads that file's PSI alone, no index, so it needs none and works while the IDE indexes.
     */
    /** NATIVE by default since 0.1.46: the kinds on the playground matched the heuristics in Find Usages (robot), the tree tells more where they differ. */
    USAGE_KINDS("Kinds of usages", needsIndexes = false, hasNative = true, defaultSource = CSharpFeatureSource.NATIVE),
    /**
     * Go to Declaration, Ctrl + hover, the usages under the caret. Native since 0.1.50, the syntactic part ([NativeCSharpNavigation]): locals,
     * parameters, labels, range variables, type parameters, members of the enclosing types, types of the solution by name (stub index, so it
     * needs the indexes). What the tree cannot resolve (`a.B`, base members, assemblies) still goes to the server, as do Go to Super and the
     * other server actions. NATIVE by default since 0.1.60: Go to Declaration on Navigation.cs and LibraryNames.cs (90 places) went where
     * the server goes (robot); `new T()` goes to the constructor, as the server's.
     */
    NAVIGATION("Navigation and usages", needsIndexes = true, hasNative = true, defaultSource = CSharpFeatureSource.NATIVE),
    /**
     * The completion list. Native since 0.1.55, the syntactic part ([NativeCSharpCompletionContributor]): keywords by place, names in scope,
     * members of the own type and its bases of the solution, types of the solution (stubs, hence the indexes), `override` / `partial`,
     * names after a type, `Task.FromResult` / `Task.CompletedTask`. The server still completes when NATIVE (members after a dot, library
     * types); the native list drops its duplicates. NATIVE by default since 0.1.60: on 26 places of the playground nothing of the server's
     * list was lost but `yield` outside an iterator and `await` in a getter, where they are wrong (robot).
     */
    COMPLETION("Completion", needsIndexes = true, hasNative = true, defaultSource = CSharpFeatureSource.NATIVE),
    /**
     * Quick documentation and parameter info. Native since 0.1.66 ([NativeCSharpDocumentationTargetProvider], [NativeCSharpParameterInfoHandler]):
     * the symbol the semantics of the plugin resolves (task C3), its Quick Info line and the XML documentation of the source or of the
     * assembly; needs the indexes (stubs, the index of assemblies). ROSLYN by default until the robot has compared it with the server.
     */
    DOCUMENTATION("Documentation and parameter info", needsIndexes = true, hasNative = true),
    /**
     * Errors and warnings. Native since 0.1.54, the syntactic part ([NativeCSharpDiagnostics]): Roslyn's syntax errors from the file's tree,
     * its lexer and its directives, so no indexes; the semantic errors still come from the server, which gives way only on the syntax errors
     * the tree reports itself. NATIVE by default since 0.1.56: the robot saw the same errors in the same places as the server's, once each,
     * across switches both ways.
     */
    DIAGNOSTICS("Errors and warnings", needsIndexes = false, hasNative = true, defaultSource = CSharpFeatureSource.NATIVE),
    /**
     * The colors of identifiers in the palette of Rider ([CSharpColors]). Native since 0.1.51 ([NativeCSharpSemanticColors]): declarations,
     * locals, parameters and the members and types of the solution by simple name, from the file's tree and the stubs (hence the indexes).
     * NATIVE by default since 0.1.60: on four files of the playground every name the server colors got a color, the same one or a finer
     * one (a declaration, a mutable local, a local function, a primary constructor parameter) (robot); the heuristics of the plugin color
     * while the IDE indexes.
     */
    SEMANTIC_COLORS("Colors of identifiers", needsIndexes = true, hasNative = true, defaultSource = CSharpFeatureSource.NATIVE),
    /**
     * Shift+F6. Native since 0.1.53, the syntactic part ([NativeCSharpRename]): inplace rename of locals, parameters, local functions,
     * labels, range variables and type parameters by the one resolver of the native tree, with conflicts and `@` before keywords; members
     * and types still go to the server (needs the indexes: the stub index for named arguments and members). NATIVE by default since 0.1.56: the robot renamed
     * the scenarios of the playground as the server does (and the `<param name>` of the doc comment too).
     */
    RENAME("Rename", needsIndexes = true, hasNative = true, defaultSource = CSharpFeatureSource.NATIVE),
    /**
     * Alt+Enter context actions of the code. Native since 0.1.64 ([NativeCSharpContextAction]): `if` ↔ `?:`, block ↔ expression body,
     * introduce / inline variable, `var` ↔ explicit type (the types of C2, hence the indexes). With the server ready and this switch
     * ROSLYN the server's own actions answer and the native ones stand back; with NATIVE the server's rows of the same actions are
     * dropped (`NativeCSharpServerActions`). ROSLYN by default until the robot has checked it.
     */
    CONTEXT_ACTIONS("Context actions", needsIndexes = true, hasNative = true);

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
 * alone, and a stored NATIVE of a feature without one is ignored (an older or newer version of the plugin may have written it). The
 * default is the feature's [CSharpFeature.defaultSource]: ROSLYN until its native implementation has passed the gates, then NATIVE (SYNTAX_TREE
 * since 0.1.45); a choice the user stored wins over it, and without the server the native one answers anyway ([native]).
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
    fun native(feature: CSharpFeature, project: Project): Boolean = native(feature, dumb = feature.needsIndexes && DumbService.isDumb(project))

    /**
     * Application settings only, for who has no project (the parser definition, `CSharpSyntaxTrees.nativeTree`): the answer for [dumb]
     * false is the final one for a feature that does not need the indexes. Cheap: a service and a map lookup.
     */
    fun native(feature: CSharpFeature, dumb: Boolean = false): Boolean {
        if (!hasNative(feature)) return false
        val settings = RoslynLanguageServerSettings.getInstance()
        return native(true, settings.source(feature), settings.state.enabled, feature.needsIndexes, dumb)
    }

    /** Pretends [features] have a native implementation until [disposable] is disposed: the plumbing is tested before the native code exists. */
    @TestOnly
    fun implementForTests(features: Set<CSharpFeature>, disposable: Disposable) {
        implementedForTests = features
        Disposer.register(disposable) { implementedForTests = null }
    }
}
