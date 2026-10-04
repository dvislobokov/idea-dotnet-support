package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.hint.HintManager
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiFile
import com.intellij.ui.SimpleListCellRenderer
import io.github.dotnetsupport.lang.CSharpDeclarationInfo
import io.github.dotnetsupport.lang.CSharpDeclarations
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.DeclarationKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind

/**
 * Go to Super (Ctrl+U) in C#. On a type: its base class and interfaces from the server's type hierarchy. On a member (method, property,
 * event, indexer — its declaration, or its body where the caret is not on a type): the members of the same name in the supertypes, all the
 * way up (see [RoslynBaseMembers]). One — a jump, several — a list. Derived symbols are Go to Implementation.
 */
class RoslynGotoSuperHandler : LanguageCodeInsightActionHandler {
    override fun startInWriteAction(): Boolean = false
    override fun isValidFor(editor: Editor?, file: PsiFile?): Boolean = file is CSharpFile && RoslynFeatures.serves(CSharpFeature.NAVIGATION, file.project)

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val target = RoslynHierarchies.target(project, editor, file) ?: return HintManager.getInstance().showErrorHint(editor, "The C# language server is not loaded yet")
        val caret = RoslynBaseMembers.memberAt(CSharpDeclarations.scan(editor.document.immutableCharSequence), editor.caretModel.offset)
        if (caret != null && !caret.inBody) return gotoBaseMember(target, editor, caret)
        val type = RoslynHierarchies.prepareType(target).firstOrNull()
            ?: return if (caret != null) gotoBaseMember(target, editor, caret) else HintManager.getInstance().showErrorHint(editor, "Put the caret on a type or on a member")
        val bases = RoslynHierarchies.supertypes(target.client, type).map { HierarchyElement(project, target.client, it) }
        if (bases.isEmpty()) return HintManager.getInstance().showErrorHint(editor, "${type.name} has no base types in the sources")
        choose(editor, bases, "Choose Base Type of ${type.name}")
    }

    private fun gotoBaseMember(target: RoslynHierarchies.Target, editor: Editor, caret: RoslynBaseMembers.Caret) {
        val name = caret.member.name.let { if (caret.member.kind == DeclarationKind.INDEXER) "the indexer" else it }
        val typeAt = RoslynHierarchies.Target(target.project, target.client, target.file, RoslynNavigation.position(editor.document, caret.type.nameRange.startOffset), caret.type.name)
        val found = RoslynHierarchies.withProgress(target.project, "Searching for Base Symbols of $name") { BaseMemberSearch(typeAt, caret.member).run() }
            ?: return HintManager.getInstance().showErrorHint(editor, "The language server does not know ${caret.type.name} yet")
        val elements = found.members.map { HierarchyElement(target.project, target.client, it) }
            .ifEmpty { found.types.map { HierarchyElement(target.project, target.client, it) }.filter { it.canNavigate() } }
        if (elements.isEmpty()) {
            val metadata = found.types.joinToString { it.name }.takeIf { it.isNotEmpty() }?.let { ": the base types $it are not in the sources" }.orEmpty()
            return HintManager.getInstance().showErrorHint(editor, "No base symbols of $name found$metadata")
        }
        choose(editor, elements, "Choose Base Symbol of $name")
    }

    private fun choose(editor: Editor, elements: List<HierarchyElement>, title: String) {
        if (elements.size == 1) return elements.single().navigate(true)
        JBPopupFactory.getInstance().createPopupChooserBuilder(elements)
            .setTitle(title)
            .setRenderer(SimpleListCellRenderer.create { label, element, _ ->
                label.text = element.rowText + element.locationText?.let { "  ($it)" }.orEmpty()
                label.icon = element.item.icon
            })
            .setNamerForFiltering { it.rowText }
            .setItemChosenCallback { it.navigate(true) }
            .createPopup().showInBestPositionFor(editor)
    }
}

/**
 * The walk up the supertypes of the type at [typeAt] (under a progress, off the EDT): every supertype file is scanned for the members
 * of [member]'s name. A supertype whose file is not there (metadata) or not understood stays a type to go to.
 */
private class BaseMemberSearch(private val typeAt: RoslynHierarchies.Target, private val member: CSharpDeclarationInfo) {
    private val client = typeAt.client

    /** The members as items to navigate to, or, with none, the supertypes that were not looked into; `null` — the server has no such type. */
    class Found(val members: List<HierarchyItem>, val types: List<HierarchyItem>)

    fun run(): Found? {
        val type = RoslynHierarchies.prepareTypeNow(typeAt).firstOrNull() ?: return null
        val seen = arrayListOf(type)
        val bases = ArrayList<RoslynBaseMembers.Base<HierarchyItem>>()
        var level = listOf(type)
        var distance = 0
        while (level.isNotEmpty() && distance < MAX_DEPTH && bases.size < MAX_TYPES) {
            distance++
            val next = ArrayList<HierarchyItem>()
            for (item in level) {
                ProgressManager.checkCanceled()
                for (base in runCatching { RoslynHierarchies.supertypes(client, item) }.getOrDefault(emptyList())) {
                    if (seen.any { it.sameAs(base) }) continue
                    seen += base
                    next += base
                    bases += RoslynBaseMembers.Base(base, distance, base.isInterface, ReadAction.compute<List<CSharpDeclarationInfo>?, RuntimeException> { membersIn(base) })
                }
            }
            level = next
        }
        val choice = RoslynBaseMembers.choose(member, bases)
        return Found(ReadAction.compute<List<HierarchyItem>, RuntimeException> { choice.members.mapNotNull { (base, found) -> itemOf(base, found) } }, choice.types)
    }

    private fun documentOf(item: HierarchyItem): Document? = client.descriptor.findFileByUri(item.uri)?.let(RoslynHierarchies::documentOf)

    private fun membersIn(base: HierarchyItem): List<CSharpDeclarationInfo>? {
        val document = documentOf(base) ?: return null
        val type = RoslynBaseMembers.typeIn(CSharpDeclarations.scan(document.immutableCharSequence), base.name, offsetOf(document, base.selectionRange.start)) ?: return null
        return RoslynBaseMembers.matches(member, type.children)
    }

    /** The member found in [base] as a row of the list: `Area(): double` in `ICaptureShape`. */
    private fun itemOf(base: HierarchyItem, found: CSharpDeclarationInfo): HierarchyItem? {
        val document = documentOf(base) ?: return null
        fun range(start: Int, end: Int) = Range(RoslynNavigation.position(document, start), RoslynNavigation.position(document, end))
        val kind = when (found.kind) {
            DeclarationKind.PROPERTY, DeclarationKind.INDEXER -> SymbolKind.Property
            DeclarationKind.EVENT -> SymbolKind.Event
            else -> SymbolKind.Method
        }
        return HierarchyItem(found.presentation, kind, base.name, base.uri, range(found.range.startOffset, found.range.endOffset), range(found.nameRange.startOffset, found.nameRange.endOffset), null)
    }

    private fun offsetOf(document: Document, position: Position): Int? =
        position.line.takeIf { it in 0 until document.lineCount }?.let { (document.getLineStartOffset(it) + position.character).coerceAtMost(document.getLineEndOffset(it)) }

    private companion object {
        const val MAX_DEPTH = 16
        const val MAX_TYPES = 64
    }
}
