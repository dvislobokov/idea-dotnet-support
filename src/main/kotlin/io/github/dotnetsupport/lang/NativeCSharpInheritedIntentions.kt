package io.github.dotnetsupport.lang

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*

/**
 * Where Alt+Enter offers the members a type inherits, as Rider does: on the header of a class, struct or record (its name — where CS0534 /
 * CS0535 stand —, its modifiers, the types of its base list) and on whitespace of its body between members (a blank line, the end of a
 * line after a member). Not inside a member: there the actions of the member are what Alt+Enter is for.
 */
object NativeCSharpInheritedPlaces {
    enum class Where { HEADER, BODY }

    fun at(file: CSharpFile, offset: Int): Where? {
        if (file.compilationUnit == null) return null
        val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
        val type = PsiTreeUtil.getParentOfType(leaf, CSharpTypeDeclaration::class.java, false) ?: return null
        if (type is CSharpInterfaceDeclaration || type is CSharpUnionDeclaration) return null
        val open = type.openBraceToken?.takeIf { it.textLength > 0 } ?: return null
        val close = type.closeBraceToken?.takeIf { it.textLength > 0 } ?: return null
        val headerStart = (type.identifier ?: return null).textRange.startOffset.let { name ->
            // the modifiers and the keyword too, not the attributes above them
            type.modifiers.firstOrNull()?.textRange?.startOffset?.coerceAtMost(name) ?: type.keyword?.textRange?.startOffset?.coerceAtMost(name) ?: name
        }
        if (offset in headerStart..open.textRange.startOffset) return Where.HEADER
        if (offset < open.textRange.endOffset || offset > close.textRange.startOffset) return null
        if (type.members.any { it.textRange.startOffset <= offset && offset <= it.textRange.endOffset && !(it.textRange.endOffset == offset && endsLine(file, offset)) }) return null
        return Where.BODY
    }

    /** The caret right after a member at the end of its line (`int _x;|`): Rider's whitespace after the member, still the type's. */
    private fun endsLine(file: CSharpFile, offset: Int): Boolean {
        val text = file.viewProvider.document?.charsSequence ?: file.text
        var i = offset
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i >= text.length || text[i] == '\n' || text[i] == '\r'
    }

    /** A base other than `object` / `ValueType` gives a member of [choices]: the header offers Override members only then (else every class would). */
    fun fromRealBase(choices: List<CSharpGenerateChoice>): Boolean = choices.any { it.group?.text !in OBJECT_GROUPS }

    private val OBJECT_GROUPS = setOf("object", "Object", "ValueType", "Enum")
}

/** Alt+Enter rows that open the dialogs of Generate (Rider's Implement missing members / Override members); not offered while the IDE indexes. */
abstract class NativeCSharpInheritedIntention(private val generator: CSharpGenerator, private val title: String) : IntentionAction, PriorityAction,
    com.intellij.openapi.project.DumbAware {
    override fun getText(): String = title
    override fun getFamilyName(): String = title
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is CSharpFile || DumbService.isDumb(project)) return false
        val offset = editor.caretModel.offset
        val where = NativeCSharpInheritedPlaces.at(file, offset) ?: return false
        val site = CSharpGenerateSite.at(file, offset) ?: return false
        return offers(where, NativeCSharpGenerate.choices(generator, site))
    }

    protected abstract fun offers(where: NativeCSharpInheritedPlaces.Where, choices: List<CSharpGenerateChoice>): Boolean

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        NativeCSharpGenerateRunner.run(generator, project, editor, file)
    }
}

/** Alt+Enter on a type that misses abstract members of its base or members of its interfaces (CS0534 / CS0535): first in the list, as in Rider. */
class NativeCSharpImplementMissingMembersIntention : NativeCSharpInheritedIntention(CSharpGenerator.MISSING_MEMBERS, "Implement missing members") {
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.TOP
    override fun offers(where: NativeCSharpInheritedPlaces.Where, choices: List<CSharpGenerateChoice>): Boolean = choices.isNotEmpty()
}

/** Alt+Enter on whitespace of a type's body, or on the header of a type with a base class: the dialog of Override members. */
class NativeCSharpOverrideMembersIntention : NativeCSharpInheritedIntention(CSharpGenerator.OVERRIDING_MEMBERS, "Override members...") {
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.NORMAL
    override fun offers(where: NativeCSharpInheritedPlaces.Where, choices: List<CSharpGenerateChoice>): Boolean =
        choices.isNotEmpty() && (where == NativeCSharpInheritedPlaces.Where.BODY || NativeCSharpInheritedPlaces.fromRealBase(choices))
}
