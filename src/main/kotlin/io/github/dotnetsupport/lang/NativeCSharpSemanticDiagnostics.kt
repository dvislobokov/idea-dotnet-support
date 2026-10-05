package io.github.dotnetsupport.lang

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.hint.QuestionAction
import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.HintAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.lang.semantic.CSharpSemanticChecks
import io.github.dotnetsupport.lang.semantic.CSharpSemanticProblem
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession

/**
 * `DIAGNOSTICS`, the semantic part (task C4c of CSHARP_PSI_MIGRATION.md): the errors of [CSharpSemanticChecks] in the editor with Roslyn's
 * codes and texts, «Import type» on a name that is not imported, the gray of the `using` directives nothing needs and «Remove unused
 * directives in file». Shown by [NativeCSharpDiagnosticsAnnotator] with the syntax errors, only when the indexes are there.
 */
object NativeCSharpSemanticDiagnostics {
    /** The semantic problems of [file], cached until the next change of PSI, of the indexes of assemblies or of dumb mode; none while the IDE indexes. */
    fun of(file: CSharpFile): List<CSharpSemanticProblem> {
        if (file.compilationUnit == null || DumbService.isDumb(file.project)) return emptyList()
        return CachedValuesManager.getCachedValue(file) {
            val project = file.project
            val problems = if (DumbService.isDumb(project)) emptyList() else CSharpSemanticChecks(CSharpSemanticSession(project).resolver(file)).run()
            CachedValueProvider.Result.create(problems, PsiModificationTracker.MODIFICATION_COUNT, AssemblyIndexService.getInstance(project).modificationTracker, DumbService.getInstance(project).modificationTracker,
                io.github.dotnetsupport.codeanalysis.CodeAnalysisService.getInstance(project).modificationTracker)
        }
    }

    /** The codes the native pass reports on each 0-based line of [file] (syntax and semantic): what the last build and the server repeat. */
    fun codesByLine(file: CSharpFile): Map<Int, Set<String>> {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return emptyMap()
        val found = HashMap<Int, MutableSet<String>>()
        fun add(code: String, offset: Int) {
            if (offset <= document.textLength) found.getOrPut(document.getLineNumber(offset)) { HashSet() } += code
        }
        for (d in NativeCSharpDiagnostics.of(file)) d.text.substringBefore(':').takeIf { it.startsWith("CS") }?.let { add(it, d.start) }
        for (p in NativeCSharpUsingChecks.of(file)) add(p.error.code, p.range.startOffset)
        for (p in of(file)) add(p.code, p.range.startOffset)
        return found
    }

    /** IDE0005 of the server is the gray the native pass shows as CS8019 / CS8933. */
    fun sameCode(native: Set<String>, code: String): Boolean = code in native || (code == "IDE0005" && ("CS8019" in native || "CS8933" in native))
}

/**
 * «Import type» (Alt+Enter on CS0246 / CS0103 / CS0234, and on CS1061 for an extension method): adds `using N;` for the one namespace,
 * or asks which when there are several. As a [HintAction] it is also the blue «Import 'N.Type'? Alt+Enter» hint the platform shows over
 * an unresolved name, like Rider's and the Java plugin's.
 */
class CSharpImportTypeFix(private val name: String, private val namespaces: List<String>, private val extension: Boolean, private val range: TextRange) : IntentionAction, HintAction, HighPriorityAction {
    override fun getText(): String = when {
        namespaces.size == 1 && extension -> "Import '${namespaces.single()}' for extension method '$name'"
        namespaces.size == 1 -> "Import '${namespaces.single()}.$name'"
        extension -> "Import extension method '$name'…"
        else -> "Import type '$name'…"
    }

    override fun getFamilyName(): String = "Import type"
    override fun startInWriteAction(): Boolean = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file is CSharpFile && namespaces.isNotEmpty()

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file !is CSharpFile) return
        if (namespaces.size == 1 || editor == null || ApplicationManager.getApplication().isUnitTestMode) return addUsing(project, file, namespaces.first())
        JBPopupFactory.getInstance().createPopupChooserBuilder(namespaces)
            .setTitle(if (extension) "Import Extension Method '$name' From" else "Import '$name' From")
            .setItemChosenCallback { addUsing(project, file, it) }
            .createPopup().showInBestPositionFor(editor)
    }

    override fun showHint(editor: Editor): Boolean {
        val project = editor.project ?: return false
        if (namespaces.size != 1 || HintManager.getInstance().hasShownHintsThatWillHideByOtherHint(true)) return false
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? CSharpFile ?: return false
        val shortcut = KeymapUtil.getFirstKeyboardShortcutText("ShowIntentionActions")
        if (range.endOffset > editor.document.textLength) return false
        val question = object : QuestionAction {
            override fun execute(): Boolean {
                addUsing(project, file, namespaces.single())
                return true
            }
        }
        val message = "${namespaces.single()}.$name?" + if (shortcut.isEmpty()) "" else " $shortcut"
        HintManager.getInstance().showQuestionHint(editor, message, range.startOffset, range.endOffset, question)
        return true
    }

    private fun addUsing(project: Project, file: CSharpFile, namespace: String) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Import Type", null, {
            CSharpUsings.insertion(document.charsSequence, namespace)?.let { document.insertString(it.offset, it.text) }
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }, file)
    }
}

/** «Remove unused directives in file»: every `using` the native pass shows gray (CS8019, CS8933), as Rider's fix of the same name. */
class CSharpRemoveUnusedUsingsFix : IntentionAction {
    override fun getText(): String = "Remove unused directives in file"
    override fun getFamilyName(): String = text
    override fun startInWriteAction(): Boolean = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file is CSharpFile && unused(file).isNotEmpty()

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file !is CSharpFile) return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val ranges = unused(file).map { lineOf(document.charsSequence, it) }.sortedByDescending { it.startOffset }
        WriteCommandAction.runWriteCommandAction(project, text, null, {
            for (range in ranges) document.deleteString(range.startOffset, range.endOffset)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }, file)
    }

    private fun unused(file: CSharpFile): List<TextRange> = NativeCSharpSemanticDiagnostics.of(file).filter { it.code == "CS8019" }.map { it.range }

    /** The directive with its line when nothing else is on it. */
    private fun lineOf(text: CharSequence, range: TextRange): TextRange {
        var start = range.startOffset
        while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
        var end = range.endOffset
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        if ((start == 0 || text[start - 1] == '\n') && end < text.length && (text[end] == '\n' || text[end] == '\r')) {
            if (text[end] == '\r') end++
            if (end < text.length && text[end] == '\n') end++
            return TextRange(start, end)
        }
        return range
    }
}
