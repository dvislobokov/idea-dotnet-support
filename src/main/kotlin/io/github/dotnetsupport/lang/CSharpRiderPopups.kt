package io.github.dotnetsupport.lang

import com.intellij.codeInsight.generation.actions.GenerateAction
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler
import com.intellij.idea.ActionsBundle
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.psi.PsiFile
import com.intellij.refactoring.actions.RefactoringQuickListPopupAction

/*
 * The three popups Rider builds for the caret of a C# file: Refactor This (Ctrl+Alt+Shift+T in the default keymap), Navigate To
 * (Ctrl+Shift+G) and Generate (Alt+Insert). Rider's backend fills them; here they are action groups of what exists at the caret:
 * the actions of the platform that the plugin serves (Rename, Go to Declaration...), the plugin's own intentions that are refactorings
 * or generators, and what the language server offers ([CSharpPopupContributor]). What is not available is not listed.
 */

/** Which of Rider's lists a contribution goes to. */
enum class CSharpPopupKind { REFACTOR, GENERATE }

/**
 * Rows of Refactor This and Generate from elsewhere: the module of the language server adds the code actions of the server. Called on a
 * pooled thread under a progress indicator, outside a read action (a contributor may wait for a server); [file] is the C# file of [editor].
 */
interface CSharpPopupContributor {
    fun actions(kind: CSharpPopupKind, project: Project, editor: Editor, file: PsiFile): List<AnAction>

    companion object {
        val EP_NAME: ExtensionPointName<CSharpPopupContributor> = ExtensionPointName.create("io.github.dotnetsupport.csharpPopupContributor")
    }
}

object CSharpRiderPopups {
    /** Refactor This: the platform's refactorings by their ids, in Rider's order; the ones the plugin does not serve for C# hide themselves. */
    val REFACTOR_FIRST = listOf("RenameElement", "ChangeSignature", "Inline", "SafeDelete")
    val REFACTOR_EXTRACT = listOf("IntroduceVariable", "IntroduceField", "IntroduceConstant", "IntroduceParameter", "ExtractMethod", "ExtractInterface", "ExtractSuperclass", "MembersPullUp", "MemberPushDown")
    val REFACTOR_MOVE = listOf("Move", "CopyElement")

    /** Navigate To: action id to Rider's name of the row, in Rider's order and groups; null keeps the platform's name. */
    val NAVIGATE: List<List<Pair<String, String?>>> = listOf(
        listOf("GotoDeclaration" to "Declaration", "GotoImplementation" to "Implementation"),
        listOf("GotoSuperMethod" to "Base Symbols", "FindUsages" to "Find Usages", "GotoRelated" to "Related Files", "GotoTypeDeclaration" to "Type of Symbol",
            "GotoTest" to "Related Tests", "ShowUsages" to "Show Usages"),
        listOf("TypeHierarchy" to "Type Hierarchy", "CallHierarchy" to "Call Hierarchy", "DotNet.IlViewer" to "IL Code"),
        listOf("RevealIn" to null),
    )

    fun isCSharpEditor(e: AnActionEvent): Boolean = e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.PSI_FILE) is CSharpFile

    /** The group of Navigate To; the rows the context does not allow hide themselves in the popup. */
    fun navigateGroup(): DefaultActionGroup = DefaultActionGroup().apply {
        NAVIGATE.forEachIndexed { index, rows ->
            if (index > 0) addSeparator()
            rows.forEach { (id, text) -> named(id, text)?.let(::add) }
        }
    }

    /** The static part of Refactor This; [dynamic] (the plugin's and the server's) goes after Rider's first four. */
    fun refactorGroup(dynamic: List<AnAction>): DefaultActionGroup = DefaultActionGroup().apply {
        REFACTOR_FIRST.forEach { id -> named(id, null)?.let(::add) }
        if (dynamic.isNotEmpty()) { addSeparator(); addAll(dynamic) }
        addSeparator()
        REFACTOR_EXTRACT.forEach { id -> named(id, null)?.let(::add) }
        addSeparator()
        REFACTOR_MOVE.forEach { id -> named(id, null)?.let(::add) }
    }

    /** Generate: the generators first, in Rider's order, then the rest of the platform's Generate group (Insert New GUID...). */
    fun generateGroup(dynamic: List<AnAction>): DefaultActionGroup = DefaultActionGroup().apply {
        addAll(dynamic.sortedBy { generateRank(it.templatePresentation.text.orEmpty()) })
        (ActionManager.getInstance().getAction("GenerateGroup") as? ActionGroup)?.let { addSeparator(); add(it) }
    }

    /** The place of a generator in Rider's list: Constructor, properties, missing / overriding / delegating members, ..., Unit Test. */
    fun generateRank(title: String): Int {
        val text = title.lowercase()
        return RIDER_GENERATE_ORDER.indexOfFirst { text.contains(it) }.let { if (it < 0) RIDER_GENERATE_ORDER.size else it }
    }

    private val RIDER_GENERATE_ORDER = listOf(
        "constructor", "propert", "implement", "missing", "override", "delegat", "partial", "deconstruct", "equals", "equality", "comparer",
        "relational", "compareto", "tostring", "format", "dispose", "test",
    )

    /** A registered action under Rider's name, its shortcut kept; null when this IDE has no such action. */
    fun named(id: String, text: String?): AnAction? = ActionManager.getInstance().getAction(id)?.let { RiderNamedAction(it, text) }

    /** The plugin's own intentions that Rider lists among the refactorings or the generators, as rows of the popup when available. */
    fun nativeActions(kind: CSharpPopupKind, project: Project, editor: Editor, file: PsiFile): List<AnAction> {
        val candidates: List<Pair<IntentionAction, String?>> = when (kind) {
            CSharpPopupKind.REFACTOR -> listOf(
                NativeCSharpIntroduceVariableIntention() to "Introduce Variable", NativeCSharpInlineVariableIntention() to "Inline Variable",
                MoveTypeToFileIntention() to null, RenameFileToTypeIntention() to null, AdjustNamespaceIntention() to null,
            )
            CSharpPopupKind.GENERATE -> listOf(CreateTestIntention() to "Unit Test", AddPartialPartIntention() to "Partial Part")
        }
        return candidates.mapNotNull { (intention, text) ->
            val available = ReadAction.compute<Boolean, RuntimeException> { !project.isDisposed && runCatching { intention.isAvailable(project, editor, file) }.getOrDefault(false) }
            if (available) IntentionRowAction(text ?: intention.text, intention, file, editor) else null
        }
    }

    /** The rows that need computing: the plugin's intentions and the contributors (the language server), under a progress. */
    fun dynamicActions(kind: CSharpPopupKind, project: Project, editor: Editor, file: PsiFile): List<AnAction> =
        ProgressManager.getInstance().runProcessWithProgressSynchronously<List<AnAction>, RuntimeException>({
            val own = nativeActions(kind, project, editor, file)
            val contributed = CSharpPopupContributor.EP_NAME.extensionList.flatMap { contributor -> runCatching { contributor.actions(kind, project, editor, file) }.getOrDefault(emptyList()) }
            val ownTitles = own.mapNotNull { it.templatePresentation.text?.lowercase() }.toSet()
            own + contributed.filter { it.templatePresentation.text?.lowercase() !in ownTitles }
        }, if (kind == CSharpPopupKind.REFACTOR) "Looking for Refactorings" else "Looking for What Can Be Generated", true, project)

    /** Shows [group] as Rider's list under the caret, or a hint when nothing in it is available. */
    fun show(e: AnActionEvent, title: String, group: ActionGroup, nothing: String) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(title, group, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
        if ((popup as? ListPopup)?.listStep?.values?.isEmpty() == true) {
            HintManager.getInstance().showInformationHint(editor, nothing)
            return
        }
        popup.showInBestPositionFor(editor)
    }
}

/** A platform action under the name Rider gives its row; the update and the shortcut are the action's own. */
class RiderNamedAction(delegate: AnAction, private val text: String?) : AnActionWrapper(delegate) {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (text != null) e.presentation.text = text
    }
}

/** An intention of the plugin as a row of a popup: invoked the way Alt+Enter invokes it (command, write action, preview). */
class IntentionRowAction(text: String, private val intention: IntentionAction, private val file: PsiFile, private val editor: Editor) : AnAction(text), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        if (!file.isValid || editor.isDisposed) return
        ShowIntentionActionsHandler.chooseActionAndInvoke(file, editor, intention, templatePresentation.text)
    }
}

/**
 * Refactor This (`Refactorings.QuickListPopupAction`, Ctrl+Alt+Shift+T): in the editor of a C# file Rider's list of what can be done with
 * the symbol at the caret; anywhere else the platform's own popup, unchanged. Rider customizes the same action of the platform.
 */
class CSharpRefactorThisAction : AnAction(), DumbAware {
    private val platform = RefactoringQuickListPopupAction()

    init {
        // registered by a plugin, the action would look for its texts in the bundle of the plugin and show an empty menu item
        templatePresentation.setText(ActionsBundle.actionText(ID))
        templatePresentation.description = ActionsBundle.actionDescription(ID)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        if (CSharpRiderPopups.isCSharpEditor(e)) e.presentation.isEnabledAndVisible = e.project != null else platform.update(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        if (project == null || editor == null || file !is CSharpFile) return platform.actionPerformed(e)
        val dynamic = CSharpRiderPopups.dynamicActions(CSharpPopupKind.REFACTOR, project, editor, file)
        CSharpRiderPopups.show(e, "Refactor This", CSharpRiderPopups.refactorGroup(dynamic), "No refactorings available at the caret")
    }

    companion object {
        const val ID = "Refactorings.QuickListPopupAction"
    }
}

/**
 * Generate (Alt+Insert): in the editor of a C# file Rider's list of generators (the server's constructor, Equals, overrides, the members
 * of an interface; the plugin's unit test) with the rest of the Generate group after them; anywhere else the platform's own popup.
 */
class CSharpGenerateAction : AnAction(), DumbAware {
    private val platform = GenerateAction()

    init {
        templatePresentation.setText(ActionsBundle.actionText(ID))
        templatePresentation.description = ActionsBundle.actionDescription(ID)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        if (!CSharpRiderPopups.isCSharpEditor(e)) return platform.update(e)
        e.presentation.isEnabledAndVisible = e.project != null
        // the name of the row in Rider's menu of the editor
        if (e.isFromContextMenu) e.presentation.text = "Generate Code..."
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        if (project == null || editor == null || file !is CSharpFile) return platform.actionPerformed(e)
        val dynamic = CSharpRiderPopups.dynamicActions(CSharpPopupKind.GENERATE, project, editor, file)
        CSharpRiderPopups.show(e, "Generate", CSharpRiderPopups.generateGroup(dynamic), "Nothing to generate here: put the caret on a type or a member")
    }

    companion object {
        const val ID = "Generate"
    }
}

/** Navigate To (Rider: `ReSharperNavigateTo`, Ctrl+Shift+G in its default keymap): where the symbol at the caret leads, as one list. */
class CSharpNavigateToAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val available = e.project != null && CSharpRiderPopups.isCSharpEditor(e)
        e.presentation.isEnabled = available
        // in Navigate of the main menu it stays, disabled, outside a C# file; the menu of the editor hides it
        e.presentation.isVisible = available || !e.isFromContextMenu
    }

    override fun actionPerformed(e: AnActionEvent) {
        CSharpRiderPopups.show(e, "Navigate To", CSharpRiderPopups.navigateGroup(), "Nowhere to navigate from here")
    }
}

/** A group of the editor's menu that only C# files have: the rows Rider has there and other languages of the IDE do not need. */
class CSharpEditorMenuGroup : DefaultActionGroup(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = CSharpRiderPopups.isCSharpEditor(e)
    }
}
