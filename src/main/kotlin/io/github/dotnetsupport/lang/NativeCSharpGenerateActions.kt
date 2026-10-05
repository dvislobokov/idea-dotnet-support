package io.github.dotnetsupport.lang

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.generation.ClassMember
import com.intellij.codeInsight.generation.MemberChooserObject
import com.intellij.codeInsight.generation.MemberChooserObjectBase
import com.intellij.codeInsight.hint.HintManager
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.ide.util.MemberChooser
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.ui.components.JBCheckBox
import org.jetbrains.annotations.TestOnly
import javax.swing.JComponent

/**
 * Running a generator of [NativeCSharpGenerate] in the editor: the member chooser of the platform (Rider's dialog: the rows grouped by
 * where they come from, the options below), then one command that inserts the members, the base types and the `using` directives and
 * formats the members with the native formatter.
 */
object NativeCSharpGenerateRunner {
    /** In tests: picks the rows instead of the dialog (all the checked ones when not set) and the options (all when not set). */
    @Volatile
    private var testChooser: ((List<CSharpGenerateChoice>) -> List<CSharpGenerateChoice>?)? = null

    @TestOnly
    fun setChooserForTests(chooser: ((List<CSharpGenerateChoice>) -> List<CSharpGenerateChoice>?)?) {
        testChooser = chooser
    }

    fun available(generator: CSharpGenerator, project: Project, editor: Editor, file: PsiFile): Boolean {
        if (file !is CSharpFile || DumbService.isDumb(project)) return false
        val site = CSharpGenerateSite.at(file, editor.caretModel.offset) ?: return false
        return NativeCSharpGenerate.choices(generator, site).isNotEmpty()
    }

    fun run(generator: CSharpGenerator, project: Project, editor: Editor, file: PsiFile) {
        if (file !is CSharpFile || project.isDisposed || editor.isDisposed) return
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val prepared = ReadAction.compute<Pair<CSharpGenerateSite, List<CSharpGenerateChoice>>?, RuntimeException> {
            val site = CSharpGenerateSite.at(file, editor.caretModel.offset) ?: return@compute null
            site to NativeCSharpGenerate.choices(generator, site)
        }
        if (prepared == null || prepared.second.isEmpty()) {
            HintManager.getInstance().showInformationHint(editor, "Nothing to generate: ${generator.title.lowercase()} are not available here")
            return
        }
        val (site, choices) = prepared
        val optionTitles = ReadAction.compute<List<String>, RuntimeException> { NativeCSharpGenerate.options(generator, site) }
        val chosen: List<CSharpGenerateChoice>
        val options: Set<String>
        if (ApplicationManager.getApplication().isUnitTestMode) {
            chosen = testChooser?.invoke(choices) ?: choices.filter { it.selected }
            options = optionTitles.toSet()
        } else if (choices.size == 1 && choices[0].payload == Unit) {
            // nothing to choose: a constructor without parameters, Dispose of no field
            chosen = emptyList()
            options = emptySet()
        } else {
            val result = choose(generator, project, site, choices, optionTitles) ?: return
            chosen = result.first
            options = result.second
        }
        apply(generator, project, editor, file, site, chosen.filter { it.payload != Unit }, options)
    }

    private fun apply(generator: CSharpGenerator, project: Project, editor: Editor, file: CSharpFile, site: CSharpGenerateSite, chosen: List<CSharpGenerateChoice>, options: Set<String>) {
        WriteCommandAction.writeCommandAction(project, file).withName(generator.chooserTitle).run<RuntimeException> {
            val documents = PsiDocumentManager.getInstance(project)
            documents.commitDocument(editor.document)
            if (!site.type.isValid) return@run
            val code = NativeCSharpGenerate.generate(generator, site, chosen, options) ?: return@run
            val text = editor.document.text
            val result = NativeCSharpGenerateEdits.apply(text, site, code, NativeCSharpContextEdits.unit(file))
            for ((offset, inserted) in result.steps) editor.document.insertString(offset, inserted)
            documents.commitDocument(editor.document)
            val marker = editor.document.createRangeMarker(result.membersRange)
            if (NativeCSharpFormatting.engaged(file)) {
                runCatching { CodeStyleManager.getInstance(project).reformatText(file, result.membersRange.startOffset, result.membersRange.endOffset) }
                documents.doPostponedOperationsAndUnblockDocument(editor.document)
                documents.commitDocument(editor.document)
            }
            val start = marker.startOffset
            val firstLine = editor.document.charsSequence.let { chars -> var i = start; while (i < chars.length && chars[i].isWhitespace()) i++; i }
            editor.caretModel.moveToOffset(firstLine.coerceAtMost(editor.document.textLength))
            editor.selectionModel.removeSelection()
            marker.dispose()
        }
    }

    /** The member chooser; null when cancelled. */
    private fun choose(
        generator: CSharpGenerator, project: Project, site: CSharpGenerateSite, choices: List<CSharpGenerateChoice>, optionTitles: List<String>,
    ): Pair<List<CSharpGenerateChoice>, Set<String>>? {
        val groups = HashMap<CSharpGenerateGroup, ChooserGroup>()
        // the chooser builds a parent node for every row: rows of no group go under the type itself
        val own = CSharpGenerateGroup(site.name, com.intellij.icons.AllIcons.Nodes.Class)
        val rows = choices.map { choice -> ChooserRow(choice, groups.getOrPut(choice.group ?: own) { ChooserGroup(choice.group ?: own) }) }
        val boxes = optionTitles.map { JBCheckBox(it, true) }
        val chooser = MemberChooser(rows.toTypedArray(), NativeCSharpGenerate.allowsEmpty(generator), true, project, null, boxes.toTypedArray<JComponent>())
        chooser.title = generator.chooserTitle
        chooser.setCopyJavadocVisible(false)
        chooser.selectElements(rows.filter { it.choice.selected }.toTypedArray())
        if (!chooser.showAndGet()) return null
        val selected = chooser.selectedElements.orEmpty().map { it.choice }
        return selected to boxes.filter { it.isSelected }.mapTo(HashSet()) { it.text }
    }

    private class ChooserGroup(group: CSharpGenerateGroup) : MemberChooserObjectBase(group.text, group.icon)

    private class ChooserRow(val choice: CSharpGenerateChoice, private val parent: ChooserGroup) : MemberChooserObjectBase(choice.text, choice.icon), ClassMember {
        override fun getParentNodeDelegate(): MemberChooserObject = parent
    }
}

/**
 * A row of Generate for a native generator; gray when the generator has nothing to offer at the caret, as Rider shows them. [shortcutOf]: the
 * action whose shortcut Rider shows on the row (Implement Methods for "Missing members").
 */
class NativeCSharpGenerateRow(private val generator: CSharpGenerator, private val available: Boolean, private val file: PsiFile, private val editor: Editor) :
    AnAction(generator.title, null, generator.icon), DumbAware {
    init {
        shortcutOf(generator)?.let { id -> ActionManager.getInstance().getAction(id)?.let { copyShortcutFrom(it) } }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = available
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: file.project
        if (available && file.isValid) NativeCSharpGenerateRunner.run(generator, project, editor, file)
    }

    private fun shortcutOf(generator: CSharpGenerator): String? = when (generator) {
        CSharpGenerator.MISSING_MEMBERS -> "ImplementMethods"
        CSharpGenerator.OVERRIDING_MEMBERS -> "OverrideMethods"
        else -> null
    }
}

/** Code | Implement Methods (Ctrl+I) and Override Methods (Ctrl+O) in a C# file: Rider's "Implement Missing Members" / "Override Members". */
abstract class NativeCSharpInheritedHandler(private val generator: CSharpGenerator) : LanguageCodeInsightActionHandler, CodeInsightActionHandler {
    override fun isValidFor(editor: Editor?, file: PsiFile?): Boolean = editor != null && file is CSharpFile && file.compilationUnit != null &&
        CSharpGenerateSite.at(file, editor.caretModel.offset) != null

    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, file: PsiFile) = NativeCSharpGenerateRunner.run(generator, project, editor, file)
}

class NativeCSharpImplementMembersHandler : NativeCSharpInheritedHandler(CSharpGenerator.MISSING_MEMBERS)

class NativeCSharpOverrideMembersHandler : NativeCSharpInheritedHandler(CSharpGenerator.OVERRIDING_MEMBERS)
