package io.github.dotnetsupport.lang

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.codeInsight.codeVision.CodeVisionProvider
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.CodeVisionState
import com.intellij.codeInsight.codeVision.settings.CodeVisionGroupSettingProvider
import com.intellij.codeInsight.codeVision.settings.PlatformCodeVisionIds
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.InlayHintsUtils
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeAnyChangeAbstractAdapter
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.awt.RelativePoint
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpSearchTarget
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynOptions
import io.github.dotnetsupport.testing.testTargets
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Code Vision of a C# file without the language server (feature `CODE_LENS`): "N usages" above every type and member, "N implementations" /
 * "N overrides" / "N inheritors" where there are any, "Run | Debug" above tests and test classes. The two Code Lens options of
 * Settings | .NET | Language Server govern it exactly as they govern the server's `textDocument/codeLens`; the server's lenses stand down
 * with NATIVE (`RoslynLspIntegration`), these with ROSLYN. The platform's Code Vision settings (Settings | Inlay Hints | Code Vision) apply
 * on top: the usages are the platform's "Usages" group, the inheritors its "Inheritors" group, the tests a group of their own.
 *
 * The counts are the plugin's own Find Usages ([NativeCSharpFindUsages], `CSharpSolutionSearch`), so a click shows the same usages the
 * count promised. They run on a pooled thread of the Code Vision host in a non-blocking read action ([NativeCSharpCodeVisionProvider]),
 * cancellable, silent while the IDE indexes. The usages outside the file and the inheritors are cached per file until another file changes
 * ([CSharpUsageCounts]); the usages inside the file are recounted on every computation, which reads this file alone — typing in a big
 * file does not search the solution again for each of its members.
 */
object NativeCSharpCodeLens {
    const val REFERENCES_ID = "csharp.references"
    const val INHERITORS_ID = "csharp.inheritors"
    const val RUN_TEST_ID = "csharp.tests.run"
    const val DEBUG_TEST_ID = "csharp.tests.debug"
    const val TESTS_GROUP_ID = "csharp.tests"
    val PROVIDER_IDS: List<String> = listOf(REFERENCES_ID, INHERITORS_ID, RUN_TEST_ID, DEBUG_TEST_ID)

    /** Past this many usages the count is "500+": a member used everywhere is not worth a full search on every pass. */
    const val LIMIT = 500

    class Lens(val range: TextRange, val text: String, val declaration: PsiElement)

    fun serves(file: PsiFile?): Boolean = file is CSharpFile && file.compilationUnit != null && isSolutionSource(file) && CSharpFeatures.native(CSharpFeature.CODE_LENS, file.project)

    /**
     * Lenses stand over the sources of the solution alone, as in Rider: not over a library source from Source Link, a decompiled type or
     * the metadata view — read-only files in memory ([LightVirtualFile]), whose members the solution cannot be searched for anyway.
     */
    fun isSolutionSource(file: PsiFile): Boolean {
        val virtualFile = file.viewProvider.virtualFile
        return virtualFile.isWritable && virtualFile !is LightVirtualFile
    }

    fun referencesEnabled(): Boolean = RoslynOptions.isOn("code_lens.dotnet_enable_references_code_lens")
    fun testsEnabled(): Boolean = RoslynOptions.isOn("code_lens.dotnet_enable_tests_code_lens")

    /** The types and members of [file] a lens stands above, in the order of the text: what Find Usages can search for. */
    fun declarations(file: CSharpFile): List<PsiElement> = PsiTreeUtil.collectElementsOfType(file, CSharpMemberDeclaration::class.java).flatMap { member ->
        when (member) {
            is CSharpBaseNamespaceDeclaration, is CSharpIndexerDeclaration -> emptyList() // no usages of a namespace; the search has none of an indexer
            // `int a, b;`: each declarator is a declaration of its own; `int a;` is named by the field
            is CSharpBaseFieldDeclaration -> if (member.declaration?.variables?.size == 1) listOfNotNull(named(member)) else member.declaration?.variables.orEmpty().mapNotNull(::named)
            else -> listOfNotNull(named(member))
        }
    }

    private fun named(declaration: PsiElement): PsiElement? = CSharpDeclarationNames.nameElement(declaration)?.let(CSharpSolutionSearch::declarationNamedBy)

    /** Where the lens goes: above the first line of the declaration, attributes included, the doc comment not. */
    fun range(declaration: PsiElement): TextRange {
        // a declarator of `int a, b;` shares the line of its field: one lens line, two entries
        val anchor = (declaration as? CSharpVariableDeclarator)?.let { PsiTreeUtil.getParentOfType(it, CSharpBaseFieldDeclaration::class.java) } ?: declaration
        return InlayHintsUtils.getTextRangeWithoutLeadingCommentsAndWhitespaces(anchor)
    }

    /** "N usages" of every searchable declaration of [file]; the count within the file fresh, the count outside it from the cache. */
    fun referenceLenses(file: CSharpFile, session: CSharpSemanticSession = CSharpSemanticSession(file.project)): List<Lens> {
        val project = file.project
        val counts = CSharpUsageCounts.getInstance(project)
        val outside = GlobalSearchScope.projectScope(project).intersectWith(GlobalSearchScope.notScope(GlobalSearchScope.fileScope(file)))
        return declarations(file).mapNotNull { declaration ->
            ProgressManager.checkCanceled()
            val target = CSharpSolutionSearch.targetOf(declaration) ?: return@mapNotNull null
            val key = key(declaration, target)
            // the hierarchy (what Find Usages cascades over) comes with the external count: a solution search of its own for virtual members
            val cached = counts.usages(file, key) {
                val full = CSharpSolutionSearch.withHierarchy(project, target, session)
                CSharpUsageCounts.Usages(full, count(project, full, outside, session))
            }
            val inside = count(project, cached.target, LocalSearchScope(file), session)
            Lens(range(declaration), usagesText(inside + cached.outside), declaration)
        }
    }

    /** "N implementations" / "N overrides" / "N inheritors" for the types and overridable members of [file] that have any. */
    fun inheritorLenses(file: CSharpFile, session: CSharpSemanticSession = CSharpSemanticSession(file.project)): List<Lens> {
        val project = file.project
        val counts = CSharpUsageCounts.getInstance(project)
        return declarations(file).mapNotNull { declaration ->
            ProgressManager.checkCanceled()
            val isType = declaration is CSharpBaseTypeDeclaration
            if (!isType && (declaration is CSharpConstructorDeclaration || !CSharpSolutionSearch.isOverridable(declaration))) return@mapNotNull null
            val target = CSharpSolutionSearch.targetOf(declaration) ?: return@mapNotNull null
            val count = counts.inheritors(file, key(declaration, target)) {
                if (isType) CSharpSolutionSearch.allSubtypes(project, target, session, LIMIT).size else CSharpSolutionSearch.overridingMembers(project, declaration, session).size
            }
            if (count == 0) return@mapNotNull null
            val inInterface = isInterface(if (isType) declaration else CSharpSolutionSearch.ownerType(declaration))
            val abstract = !isType && (declaration as? CSharpMemberDeclaration)?.modifiers?.any { it.text == "abstract" } == true
            val noun = when {
                inInterface || abstract -> "implementation"
                isType -> "inheritor"
                else -> "override"
            }
            Lens(range(declaration), "$count $noun" + if (count == 1) "" else "s", declaration)
        }
    }

    /** "Run" / "Debug" above every test method and test class of [file]: the targets of the ▶ gutter, the same configurations. */
    fun testLenses(file: CSharpFile): List<Lens> = testTargets(file).mapNotNull { target ->
        val leaf = file.findElementAt(target.nameRange.startOffset)?.takeIf(CSharpLeaves::isIdentifier) ?: return@mapNotNull null
        val declaration = CSharpSolutionSearch.declarationNamedBy(leaf) ?: return@mapNotNull null
        Lens(range(declaration), target.displayName, leaf)
    }

    fun usagesText(count: Int): String = when {
        count == 0 -> "no usages"
        count == 1 -> "1 usage"
        count >= LIMIT -> "$LIMIT+ usages"
        else -> "$count usages"
    }

    private fun count(project: Project, target: CSharpSearchTarget, scope: SearchScope, session: CSharpSemanticSession): Int {
        var count = 0
        CSharpSolutionSearch.processUsages(project, target, scope, session) { ++count < LIMIT }
        return count
    }

    private fun isInterface(type: PsiElement?): Boolean = (type as? CSharpTypeDeclaration)?.keyword?.text == "interface"

    /** A declaration across edits of its file: owners, kind, name and parameters, not offsets, so typing elsewhere keeps the cached counts. */
    private fun key(declaration: PsiElement, target: CSharpSearchTarget): String {
        val owners = generateSequence(declaration.parent) { it.parent }.filterIsInstance<CSharpBaseTypeDeclaration>().map { CSharpDeclarationNames.name(it) ?: "?" }.toList().asReversed()
        val parameters = (declaration as? CSharpBaseMethodDeclaration)?.parameterList?.text?.filterNot(Char::isWhitespace).orEmpty()
        return (owners + "${target.kind}:${target.name}$parameters").joinToString(".")
    }

    /** Show Usages at the lens, as a click on the platform's "N usages" does. */
    fun showUsages(editor: Editor, declaration: PsiElement, event: MouseEvent?) =
        GotoDeclarationAction.startFindUsages(editor, declaration.project, declaration, event?.let(::RelativePoint))

    /** The subtypes of a type, the overrides and implementations of a member, as the gutter of overrides lists them. */
    fun showInheritors(editor: Editor, declaration: PsiElement) {
        val project = declaration.project
        val title = if (declaration is CSharpBaseTypeDeclaration) "Inheritors" else "Implementations"
        val found = NativeCSharpHierarchies.underProgress(project, title) {
            val session = CSharpSemanticSession(project)
            if (declaration is CSharpBaseTypeDeclaration) CSharpSolutionSearch.targetOf(declaration)?.let { CSharpSolutionSearch.allSubtypes(project, it, session) }.orEmpty()
            else CSharpSolutionSearch.overridingMembers(project, declaration, session)
        }
        if (found.isNotEmpty()) NativeCSharpHierarchies.choose(editor, found, title)
    }

    /** Runs or debugs the configuration the ▶ gutter would: the one of `DotNetTestConfigurationProducer` for the test under [leaf]. */
    fun runTest(leaf: PsiElement, debug: Boolean) {
        val context = ConfigurationContext(leaf)
        val runManager = RunManager.getInstance(leaf.project)
        val settings = context.findExisting() ?: context.configuration?.also(runManager::setTemporaryConfiguration) ?: return
        ProgramRunnerUtil.executeConfiguration(settings, if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance())
    }
}

/**
 * The counts of [NativeCSharpCodeLens] that read other files, per file: the usages of its members outside it and the inheritors of its
 * types and members. A change in a file drops the caches of every other file (their members may have gained a usage there), not its own:
 * while the user types in one file, its lenses recount only that file. The inheritors of the changed file are dropped too, a subtype
 * may have been added right there. The same change asks the Code Vision host to compute the plugin's lenses again in every editor (the host
 * does that by itself only for the editor of the changed document), as does the end of indexing, when the counts become possible.
 */
@Service(Service.Level.PROJECT)
class CSharpUsageCounts(private val project: Project) : Disposable {
    class Usages(val target: CSharpSearchTarget, val outside: Int)

    private class FileCache {
        val usages = ConcurrentHashMap<String, Usages>()
        val inheritors = ConcurrentHashMap<String, Int>()
    }

    private val byFile = ConcurrentHashMap<VirtualFile, FileCache>()

    init {
        PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeAnyChangeAbstractAdapter() {
            override fun onChange(file: PsiFile?) {
                val changed = file?.virtualFile
                byFile.keys.removeIf { it != changed }
                changed?.let(byFile::get)?.inheritors?.clear()
                invalidateLenses()
            }
        }, this)
        project.messageBus.connect(this).subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun exitDumbMode() = invalidateLenses()
        })
    }

    /** The host computes the plugin's lenses anew in every editor (debounced by the host; a computation under way is cancelled). */
    private fun invalidateLenses() {
        if (project.isDisposed || ApplicationManager.getApplication().isUnitTestMode) return
        project.service<CodeVisionHost>().invalidateProvider(CodeVisionHost.LensInvalidateSignal(null, NativeCSharpCodeLens.PROVIDER_IDS))
    }

    fun usages(file: PsiFile, key: String, compute: () -> Usages): Usages {
        val cache = cache(file) ?: return compute()
        // not computeIfAbsent: the search takes read actions and checks for cancellation, which must not happen under the map's lock
        cache.usages[key]?.takeIf { it.target.declarations.all(PsiElement::isValid) }?.let { return it }
        return compute().also { cache.usages[key] = it }
    }

    fun inheritors(file: PsiFile, key: String, compute: () -> Int): Int {
        val cache = cache(file) ?: return compute()
        cache.inheritors[key]?.let { return it }
        return compute().also { cache.inheritors[key] = it }
    }

    private fun cache(file: PsiFile): FileCache? = file.virtualFile?.let { byFile.computeIfAbsent(it) { FileCache() } }

    fun clear() = byFile.clear()

    override fun dispose() = byFile.clear()

    companion object {
        fun getInstance(project: Project): CSharpUsageCounts = project.service()
    }
}

/**
 * The lens of a [NativeCSharpCodeLens] list as a Code Vision provider: one entry per lens, clickable. A provider of the Code Vision host
 * itself, not a `DaemonBoundCodeVisionProvider`: the host keeps one entry per line of a provider ([singleEntryPerLine], the last one), and
 * the adapter of the daemon-bound ones cannot say otherwise — `int a, b;` and `enum Kind { A, B }` would show the last declaration's count
 * alone. The host computes on a pooled thread when an editor opens, when its document changes, and on [CodeVisionHost.invalidateProvider];
 * the counts read the PSI in a non-blocking read action, so typing cancels and restarts them as the daemon would. Edits of other files and
 * the end of indexing come as invalidations from [CSharpUsageCounts].
 */
abstract class NativeCSharpCodeVisionProvider : CodeVisionProvider<Unit> {
    override val defaultAnchor: CodeVisionAnchorKind get() = CodeVisionAnchorKind.Default

    /** Every declaration of a line keeps its entry: `int a, b;` reads "1 usage | no usages", a one-line enum has one for each member. */
    override val singleEntryPerLine: Boolean get() = false

    protected abstract fun lenses(file: CSharpFile): List<NativeCSharpCodeLens.Lens>
    protected abstract fun entry(lens: NativeCSharpCodeLens.Lens): CodeVisionEntry

    override fun precomputeOnUiThread(editor: Editor) = Unit

    override fun computeCodeVision(editor: Editor, uiData: Unit): CodeVisionState {
        val project = editor.project?.takeIf { !it.isDefault && !it.isDisposed } ?: return CodeVisionState.Ready(emptyList())
        CSharpUsageCounts.getInstance(project) // its listeners ask the host again when another file changes or indexing ends
        val compute = { PsiDocumentManager.getInstance(project).getPsiFile(editor.document)?.let { computeLenses(editor, it) }.orEmpty() }
        // the host's test mode computes on the EDT under a modal progress; otherwise a pooled thread, where a write action must not wait for a count
        val lenses = if (ApplicationManager.getApplication().isDispatchThread) ReadAction.compute<List<Pair<TextRange, CodeVisionEntry>>, RuntimeException>(compute)
        else ReadAction.nonBlocking(compute).withDocumentsCommitted(project).expireWith(CSharpUsageCounts.getInstance(project)).executeSynchronously()
        return CodeVisionState.Ready(lenses)
    }

    /** The entries of [file] in [editor]: none unless the plugin's lenses serve the file (the server's turn, or the IDE indexes). */
    fun computeLenses(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        if (file.project.isDefault || !NativeCSharpCodeLens.serves(file)) return emptyList()
        return lenses(file as CSharpFile).map { it.range to entry(it) }
    }

    /** A click comes later than the pass: the declaration by a pointer, so an edit in between does not point into the void. */
    protected fun clickable(lens: NativeCSharpCodeLens.Lens, tooltip: String, icon: javax.swing.Icon? = null, onClick: (MouseEvent?, Editor, PsiElement) -> Unit): CodeVisionEntry {
        val pointer: SmartPsiElementPointer<PsiElement> = SmartPointerManager.createPointer(lens.declaration)
        return ClickableTextCodeVisionEntry(lens.text, id, { event, editor -> pointer.element?.let { onClick(event, editor, it) } }, icon, lens.text, tooltip)
    }
}

/** "N usages" above types and members, the platform's "Usages" group; off with «References» of the Code Lens options. */
class NativeCSharpReferencesCodeVisionProvider : NativeCSharpCodeVisionProvider() {
    override val id: String get() = NativeCSharpCodeLens.REFERENCES_ID
    override val name: String get() = "C# usages"
    override val groupId: String get() = PlatformCodeVisionIds.USAGES.key
    override val relativeOrderings: List<CodeVisionRelativeOrdering> get() = emptyList()

    override fun lenses(file: CSharpFile): List<NativeCSharpCodeLens.Lens> = if (NativeCSharpCodeLens.referencesEnabled()) NativeCSharpCodeLens.referenceLenses(file) else emptyList()

    override fun entry(lens: NativeCSharpCodeLens.Lens): CodeVisionEntry = clickable(lens, "Show usages") { event, editor, declaration -> NativeCSharpCodeLens.showUsages(editor, declaration, event) }
}

/** "N implementations" / "N overrides" / "N inheritors", the platform's "Inheritors" group; the platform's Code Vision settings alone govern it. */
class NativeCSharpInheritorsCodeVisionProvider : NativeCSharpCodeVisionProvider() {
    override val id: String get() = NativeCSharpCodeLens.INHERITORS_ID
    override val name: String get() = "C# inheritors"
    override val groupId: String get() = PlatformCodeVisionIds.INHERITORS.key
    override val relativeOrderings: List<CodeVisionRelativeOrdering> get() = listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingAfter(NativeCSharpCodeLens.REFERENCES_ID))

    override fun lenses(file: CSharpFile): List<NativeCSharpCodeLens.Lens> = NativeCSharpCodeLens.inheritorLenses(file)

    override fun entry(lens: NativeCSharpCodeLens.Lens): CodeVisionEntry = clickable(lens, "Go to implementations") { _, editor, declaration ->
        NativeCSharpCodeLens.showInheritors(editor, declaration)
    }
}

/** "Run" / "Debug" above tests and test classes; off with «Run and debug tests» of the Code Lens options. */
abstract class NativeCSharpTestCodeVisionProvider(private val debug: Boolean) : NativeCSharpCodeVisionProvider() {
    override val id: String get() = if (debug) NativeCSharpCodeLens.DEBUG_TEST_ID else NativeCSharpCodeLens.RUN_TEST_ID
    override val name: String get() = if (debug) "Debug test" else "Run test"
    override val groupId: String get() = NativeCSharpCodeLens.TESTS_GROUP_ID
    override val relativeOrderings: List<CodeVisionRelativeOrdering>
        get() = listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingAfter(if (debug) NativeCSharpCodeLens.RUN_TEST_ID else NativeCSharpCodeLens.INHERITORS_ID))

    override fun lenses(file: CSharpFile): List<NativeCSharpCodeLens.Lens> =
        if (NativeCSharpCodeLens.testsEnabled()) NativeCSharpCodeLens.testLenses(file).map { NativeCSharpCodeLens.Lens(it.range, if (debug) "Debug" else "Run", it.declaration) } else emptyList()

    override fun entry(lens: NativeCSharpCodeLens.Lens): CodeVisionEntry =
        clickable(lens, if (debug) "Debug the tests" else "Run the tests", if (debug) AllIcons.Actions.StartDebugger else AllIcons.Actions.Execute) { _, _, leaf -> NativeCSharpCodeLens.runTest(leaf, debug) }
}

class NativeCSharpRunTestCodeVisionProvider : NativeCSharpTestCodeVisionProvider(debug = false)
class NativeCSharpDebugTestCodeVisionProvider : NativeCSharpTestCodeVisionProvider(debug = true)

/**
 * The lenses on screen follow the page at once. The Code Vision host computes again only when a document changed, so an Apply that
 * changes a «Code Lens» option or the source of «Code Vision» changed nothing until the next edit: when the answer of either changes, the
 * providers of the host are asked anew in every editor — the plugin's lenses, and the server's, which reads the switch per request: a
 * switch of the source while the server runs shows its lenses, or hides them, at once.
 */
class NativeCSharpCodeLensSwitch : RoslynLanguageServerSettings.Listener {
    override fun settingsChanged(restart: Boolean) {
        val now = listOf(NativeCSharpCodeLens.referencesEnabled(), NativeCSharpCodeLens.testsEnabled(), CSharpFeatures.native(CSharpFeature.CODE_LENS))
        if (last.getAndSet(now) == now) return
        CSharpEditorRefresh.onEdt {
            for (project in CSharpEditorRefresh.editors().map { it.second }.distinct()) {
                project.service<CodeVisionHost>().invalidateProvider(CodeVisionHost.LensInvalidateSignal(null, emptyList()))
            }
        }
    }

    private companion object {
        val last = AtomicReference<List<Boolean>?>(null)
    }
}

/** The row of the test lenses in Settings | Inlay Hints | Code Vision: one switch for "Run" and "Debug". */
class NativeCSharpTestsCodeVisionGroupSettingProvider : CodeVisionGroupSettingProvider {
    override val groupId: String get() = NativeCSharpCodeLens.TESTS_GROUP_ID
    override val groupName: String get() = "C# tests"
    override val description: String get() = "Run or debug a test or a test class from the editor"
}
