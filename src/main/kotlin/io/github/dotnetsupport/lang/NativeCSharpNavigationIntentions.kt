package io.github.dotnetsupport.lang

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.ide.hierarchy.CallHierarchyBrowserBase
import com.intellij.ide.hierarchy.actions.BrowseHierarchyActionBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import org.jetbrains.annotations.TestOnly

/**
 * The navigation Rider lists in Alt+Enter on the header of a member (Jellyfin p01, p16, p19, p21, p24, 0.1.170): "Incoming Calls",
 * "Outgoing Calls" (our Call Hierarchy in that view) and "Implements method from 'IUserManager' interface" / "Overrides method from
 * 'Base' class" (the base member, as Go to Super). They change nothing: no write action, no preview, low in the list as Rider has them.
 * Only on the header (attributes, modifiers, type, name, parameters — before the body), not in bodies; on the native tree and its
 * navigation (`NativeCSharpFindUsages.serves`: not while the IDE indexes, not with the server answering for navigation).
 */
abstract class NativeCSharpNavigationIntention(private val family: String) : IntentionAction, PriorityAction, com.intellij.openapi.project.DumbAware {
    override fun getFamilyName(): String = family
    override fun startInWriteAction(): Boolean = false
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.LOW
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    protected fun header(editor: Editor?, file: PsiFile?): PsiElement? {
        if (editor == null || file !is CSharpFile || file.compilationUnit == null || !NativeCSharpFindUsages.serves(file)) return null
        return NativeCSharpNavigationIntentions.headerAt(file, editor.caretModel.offset)
    }
}

object NativeCSharpNavigationIntentions {
    /** The member whose header [offset] is on: a method, constructor, operator, local function, property, indexer or event; null in its body. */
    fun headerAt(file: PsiFile, offset: Int): PsiElement? {
        val leaf = file.findElementAt(offset) ?: (if (offset > 0) file.findElementAt(offset - 1) else null) ?: return null
        var current: PsiElement? = leaf
        while (current != null && current !is PsiFile) {
            when (current) {
                is CSharpBaseMethodDeclaration -> return current.takeIf { before(offset, current.body ?: current.expressionBody) }
                is CSharpLocalFunctionStatement -> return current.takeIf { before(offset, current.body ?: current.expressionBody) }
                is CSharpBasePropertyDeclaration ->
                    return current.takeIf { before(offset, current.accessorList ?: (current as? CSharpPropertyDeclaration)?.expressionBody ?: (current as? CSharpIndexerDeclaration)?.expressionBody) }
                is CSharpEventFieldDeclaration -> return current
                is CSharpStatement, is CSharpBaseTypeDeclaration, is CSharpMemberDeclaration -> return null
            }
            current = current.parent
        }
        return null
    }

    private fun before(offset: Int, body: PsiElement?): Boolean = body == null || offset <= body.textRange.startOffset

    /** "Implements method from 'IUserManager' interface" / "Overrides property from 'Base' class" for the first of [bases]. */
    fun baseTitle(member: PsiElement, bases: List<Any>): String? {
        val base = bases.firstOrNull() ?: return null
        val kind = when (member) {
            is CSharpBasePropertyDeclaration -> if (member is CSharpEventDeclaration) "event" else if (member is CSharpIndexerDeclaration) "indexer" else "property"
            is CSharpEventFieldDeclaration -> "event"
            else -> "method"
        }
        val (owner, isInterface) = when (base) {
            is PsiElement -> CSharpSolutionSearch.ownerType(base).let { CSharpDeclarationNames.name(it ?: return null) to ((it as? CSharpTypeDeclaration)?.keyword?.text == "interface") }
            is CSharpSymbol.LibraryMember -> base.member.type.name.substringBefore('`') to (base.member.type.kind == IndexedTypeKind.INTERFACE)
            else -> return null
        }
        owner ?: return null
        return if (isInterface) "Implements $kind from '$owner' interface" else "Overrides $kind from '$owner' class"
    }

    /** Opens the call hierarchy of [target] in the view of its callers or of its callees; [openForTests] takes the place of the tool window. */
    fun openCalls(project: Project, target: PsiElement, callers: Boolean) {
        openForTests?.let { return it(target, callers) }
        val browser = BrowseHierarchyActionBase.createAndAddToPanel(project, NativeCSharpCallHierarchyProvider(), target) as? NativeCSharpCallHierarchyBrowser ?: return
        browser.changeView(if (callers) CallHierarchyBrowserBase.getCallerType() else CallHierarchyBrowserBase.getCalleeType())
    }

    @TestOnly
    @Volatile
    var openForTests: ((PsiElement, Boolean) -> Unit)? = null
}

/** Alt+Enter on the header of a method, constructor or property: its callers in the Call Hierarchy (Rider: "Incoming Calls"). */
class NativeCSharpIncomingCallsIntention : NativeCSharpNavigationIntention("Incoming Calls") {
    override fun getText(): String = "Incoming Calls"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = header(editor, file)?.let(NativeCSharpCallGraph::isCallable) == true
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val target = header(editor, file)?.takeIf(NativeCSharpCallGraph::isCallable) ?: return
        NativeCSharpNavigationIntentions.openCalls(project, target, callers = true)
    }
}

/** Alt+Enter on the header of a method, constructor or property: what it calls in the Call Hierarchy (Rider: "Outgoing Calls"). */
class NativeCSharpOutgoingCallsIntention : NativeCSharpNavigationIntention("Outgoing Calls") {
    override fun getText(): String = "Outgoing Calls"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = header(editor, file)?.let(NativeCSharpCallGraph::isCallable) == true
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val target = header(editor, file)?.takeIf(NativeCSharpCallGraph::isCallable) ?: return
        NativeCSharpNavigationIntentions.openCalls(project, target, callers = false)
    }
}

/** Alt+Enter on the header of a member that implements or overrides one: go to it (Rider: "Implements method from 'IUserManager' interface"). */
class NativeCSharpGoToBaseMemberIntention : NativeCSharpNavigationIntention("Go to base member") {
    @Volatile private var title = "Go to base member"

    override fun getText(): String = title

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val member = header(editor, file) ?: return false
        if (member is CSharpConstructorDeclaration || member is CSharpLocalFunctionStatement) return false
        val bases = CSharpSolutionSearch.baseMembers(member, CSharpSemanticSession(project))
        title = NativeCSharpNavigationIntentions.baseTitle(member, bases) ?: return false
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        editor ?: return
        val member = header(editor, file) ?: return
        val name = CSharpDeclarationNames.name(member).orEmpty()
        val elements = NativeCSharpHierarchies.underProgress(project, "Searching for Base Symbols") {
            CSharpSolutionSearch.baseMembers(member, CSharpSemanticSession(project)).mapNotNull { NativeCSharpHierarchies.elementOf(project, it) }
        }
        if (elements.isNotEmpty()) NativeCSharpHierarchies.choose(editor, elements, "Choose Base Symbol of $name")
    }
}
