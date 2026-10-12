package io.github.dotnetsupport.lang

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionActionWithOptions
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*

/**
 * The modifiers of a member or a type changed from its header (0.1.171), as Rider offers them on Alt+Enter: the accessibility ("To private"
 * on a public member, "To internal" on a public top-level type, the other legal ones in the submenu), "To virtual", "To abstract" (in an
 * abstract class: the body goes), "Make partial" on a type. Pure text edits on the tree, tested without an editor.
 */
object NativeCSharpModifierEdits {
    private val ACCESS = listOf("public", "private", "protected", "internal", "file")

    /** The canonical order of the modifiers (`dotnet_style_` / Rider): an inserted one goes to its place among the written ones. */
    private val ORDER = listOf(
        "public", "private", "protected", "internal", "file", "static", "extern", "new", "virtual", "abstract", "sealed", "override", "readonly",
        "unsafe", "required", "volatile", "async", "partial",
    )

    private fun present(token: PsiElement?): Boolean = (token?.textLength ?: 0) > 0

    // ---- the header under the caret

    /** The member or type whose header (modifiers, type, name, parameters — not attributes, not the body) holds [offset]. */
    fun headerAt(file: PsiFile, offset: Int): CSharpMemberDeclaration? {
        for (leaf in listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)) {
            var member = PsiTreeUtil.getParentOfType(leaf, CSharpMemberDeclaration::class.java, false)
            while (member != null) {
                if (member is CSharpBaseNamespaceDeclaration || member is CSharpIncompleteMember || member is CSharpEnumMemberDeclaration) break
                val start = headerStart(member)
                val end = headerEnd(member)
                if (offset in start..end) return member
                member = PsiTreeUtil.getParentOfType(member, CSharpMemberDeclaration::class.java, true)
            }
        }
        return null
    }

    /** After the attributes: the first modifier, or the first token of the declaration itself. */
    private fun headerStart(member: CSharpMemberDeclaration): Int =
        member.modifiers.firstOrNull()?.textRange?.startOffset ?: member.attributeLists.lastOrNull()?.let { list ->
            var next = list.nextSibling
            while (next != null && (next.textLength == 0 || next is com.intellij.psi.PsiWhiteSpace || next is com.intellij.psi.PsiComment)) next = next.nextSibling
            next?.textRange?.startOffset
        } ?: member.textRange.startOffset

    private fun headerEnd(member: CSharpMemberDeclaration): Int {
        val stop: PsiElement? = when (member) {
            is CSharpBaseTypeDeclaration -> member.openBraceToken?.takeIf(::present) ?: member.semicolonToken?.takeIf(::present)
            is CSharpBaseMethodDeclaration -> member.body ?: member.expressionBody ?: member.semicolonToken?.takeIf(::present)
            is CSharpPropertyDeclaration -> member.accessorList ?: member.expressionBody
            is CSharpIndexerDeclaration -> member.accessorList ?: member.expressionBody
            is CSharpEventDeclaration -> member.accessorList
            is CSharpBaseFieldDeclaration -> member.declaration?.variables?.firstOrNull()?.initializer ?: member.semicolonToken?.takeIf(::present)
            is CSharpDelegateDeclaration -> member.semicolonToken?.takeIf(::present)
            else -> null
        }
        return stop?.textRange?.startOffset ?: member.textRange.endOffset
    }

    // ---- accessibility

    private fun accessOf(member: CSharpMemberDeclaration): List<String> = member.modifiers.map { it.text }.filter { it in ACCESS }

    private fun isTopLevel(member: CSharpMemberDeclaration): Boolean = member.parent !is CSharpTypeDeclaration

    /** What is written, or the default where nothing is: `private` in a type, `internal` at the top level. */
    fun currentAccess(member: CSharpMemberDeclaration): String = accessOf(member).let { written ->
        when {
            written.isNotEmpty() -> written.sortedBy { ACCESS.indexOf(it) }.let { if (it == listOf("private", "protected")) "private protected" else it.joinToString(" ") }
            isTopLevel(member) -> "internal"
            else -> "private"
        }
    }

    /** The accessibilities the member may have, `null` where its accessibility is not the user's to change. */
    fun legalAccess(member: CSharpMemberDeclaration): List<String>? {
        val modifiers = member.modifiers.map { it.text }
        if ("override" in modifiers || "file" in modifiers || "partial" in modifiers && member is CSharpMethodDeclaration) return null
        if (member is CSharpBaseNamespaceDeclaration || member is CSharpEnumMemberDeclaration || member is CSharpIncompleteMember) return null
        if (member is CSharpConstructorDeclaration && "static" in modifiers) return null
        if (member is CSharpDestructorDeclaration || member is CSharpOperatorDeclaration || member is CSharpConversionOperatorDeclaration) return null
        if (member is CSharpMethodDeclaration && member.explicitInterfaceSpecifier != null) return null
        if (member is CSharpBasePropertyDeclaration && member.explicitInterfaceSpecifier != null) return null
        if (isTopLevel(member)) return if (member is CSharpBaseTypeDeclaration || member is CSharpDelegateDeclaration) listOf("public", "internal") else null
        val container = member.parent as CSharpTypeDeclaration
        // the members of an interface: public by default, the rest needs bodies — not offered
        if (container is CSharpInterfaceDeclaration) return null
        val containerModifiers = container.modifiers.map { it.text }
        val struct = container is CSharpStructDeclaration || container is CSharpRecordDeclaration && container.classOrStructKeyword?.text == "struct"
        val noProtected = struct || "static" in containerModifiers || "sealed" in containerModifiers
        return if (noProtected) listOf("public", "internal", "private") else listOf("public", "protected", "internal", "protected internal", "private protected", "private")
    }

    /** "To private" on a public member, "To internal" on a public top-level type, "To public" on the others: Rider's first row. */
    fun primaryAccess(member: CSharpMemberDeclaration): String? {
        val legal = legalAccess(member) ?: return null
        val current = currentAccess(member)
        val wanted = if (current == "public") (if (isTopLevel(member)) "internal" else "private") else "public"
        return wanted.takeIf { it in legal && it != current }
    }

    fun otherAccess(member: CSharpMemberDeclaration): List<String> {
        val legal = legalAccess(member) ?: return emptyList()
        val current = currentAccess(member)
        val primary = primaryAccess(member)
        return legal.filter { it != current && it != primary }
    }

    /** The modifiers of [member] with [access] in place of the written accessibility, in the canonical order. */
    fun toAccess(member: CSharpMemberDeclaration, access: String): CSharpTextEdit? {
        if (access == currentAccess(member) && accessOf(member).isNotEmpty()) return null
        val others = member.modifiers.map { it.text }.filter { it !in ACCESS }
        return replaceModifiers(member, access.split(' ') + others)
    }

    // ---- virtual / abstract / partial

    private fun classOf(member: CSharpMemberDeclaration): CSharpTypeDeclaration? =
        (member.parent as? CSharpTypeDeclaration)?.takeIf { it is CSharpClassDeclaration || it is CSharpRecordDeclaration && it.classOrStructKeyword?.text != "struct" }

    private fun overridable(member: CSharpMemberDeclaration): Boolean {
        if (member !is CSharpMethodDeclaration && member !is CSharpPropertyDeclaration && member !is CSharpIndexerDeclaration && member !is CSharpEventDeclaration &&
            member !is CSharpEventFieldDeclaration) return false
        if (member is CSharpMethodDeclaration && member.explicitInterfaceSpecifier != null) return false
        if (member is CSharpBasePropertyDeclaration && member.explicitInterfaceSpecifier != null) return false
        val modifiers = member.modifiers.map { it.text }
        if (modifiers.any { it in setOf("static", "virtual", "abstract", "override", "sealed", "private", "extern", "partial", "const") }) return false
        if (modifiers.none { it in ACCESS }) return false
        val container = classOf(member) ?: return false
        val containerModifiers = container.modifiers.map { it.text }
        return "sealed" !in containerModifiers && "static" !in containerModifiers
    }

    /** "To virtual": an overridable method / property / event of a class that can be derived from. */
    fun toVirtual(member: CSharpMemberDeclaration): CSharpTextEdit? {
        if (!overridable(member)) return null
        return replaceModifiers(member, member.modifiers.map { it.text } + "virtual")
    }

    /**
     * "To abstract" in an abstract class: the modifier, and the body goes — `{ … }` / `=> …;` of a method becomes `;`, a property keeps
     * `get;` / `set;` without bodies and loses its initializer.
     */
    fun toAbstract(member: CSharpMemberDeclaration, text: CharSequence): CSharpTextEdit? {
        if (member !is CSharpMethodDeclaration && member !is CSharpPropertyDeclaration) return null
        val container = classOf(member) ?: return null
        if ("abstract" !in container.modifiers.map { it.text }) return null
        val modifiers = member.modifiers.map { it.text }
        if (modifiers.any { it in setOf("static", "abstract", "override", "sealed", "private", "extern", "partial", "async") }) return null
        if (member is CSharpMethodDeclaration && member.explicitInterfaceSpecifier != null) return null
        if (member is CSharpPropertyDeclaration && member.explicitInterfaceSpecifier != null) return null
        val start = member.textRange.startOffset
        val pieces = ArrayList<Pair<TextRange, String>>()
        val newModifiers = (modifiers.filter { it != "virtual" } + "abstract")
        pieces += modifierRange(member) to sorted(newModifiers).joinToString(" ").let { if (member.modifiers.isEmpty()) "$it " else it }
        when (member) {
            is CSharpMethodDeclaration -> {
                val body: PsiElement = member.body ?: member.expressionBody ?: return null
                val from = trimBack(text, body.textRange.startOffset)
                pieces += TextRange(from, member.textRange.endOffset) to ";"
            }
            is CSharpPropertyDeclaration -> {
                member.expressionBody?.let { body ->
                    pieces += TextRange(trimBack(text, body.textRange.startOffset), member.textRange.endOffset) to " { get; }"
                }
                member.accessorList?.let { list ->
                    val accessors = list.accessors.joinToString(" ") { a -> a.modifiers.joinToString("") { "${it.text} " } + (a.keyword?.text ?: "") + ";" }
                    pieces += TextRange(list.textRange.startOffset, member.textRange.endOffset) to "{ $accessors }"
                }
            }
        }
        return apply(member, text, pieces)
    }

    /** "Make partial" on the header of a class, struct, interface or record that is not partial. */
    fun makePartial(member: CSharpMemberDeclaration): CSharpTextEdit? {
        val type = member as? CSharpTypeDeclaration ?: return null
        if ("partial" in type.modifiers.map { it.text }) return null
        val keyword = type.keyword?.takeIf(::present) ?: return null
        return CSharpTextEdit(TextRange(keyword.textRange.startOffset, keyword.textRange.startOffset), "partial ", 0)
    }

    // ---- the text

    private fun sorted(modifiers: List<String>): List<String> = modifiers.distinct().sortedBy { ORDER.indexOf(it).let { i -> if (i < 0) ORDER.size else i } }

    /** The range of the written modifiers, or an empty range where they would go. */
    private fun modifierRange(member: CSharpMemberDeclaration): TextRange {
        val modifiers = member.modifiers
        if (modifiers.isEmpty()) return TextRange(headerStart(member), headerStart(member))
        return TextRange(modifiers.first().textRange.startOffset, modifiers.last().textRange.endOffset)
    }

    private fun replaceModifiers(member: CSharpMemberDeclaration, modifiers: List<String>): CSharpTextEdit {
        val range = modifierRange(member)
        val text = sorted(modifiers).joinToString(" ") + if (member.modifiers.isEmpty()) " " else ""
        return CSharpTextEdit(range, text, 0)
    }

    private fun trimBack(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && (text[i - 1] == ' ' || text[i - 1] == '\t' || text[i - 1] == '\n' || text[i - 1] == '\r')) i--
        return i
    }

    /** [pieces] (ranges of the document inside [member]) applied to the member's text: one edit of the whole member. */
    private fun apply(member: CSharpMemberDeclaration, text: CharSequence, pieces: List<Pair<TextRange, String>>): CSharpTextEdit {
        val start = member.textRange.startOffset
        val result = StringBuilder(text.subSequence(start, member.textRange.endOffset))
        for ((range, replacement) in pieces.sortedByDescending { it.first.startOffset }) result.replace(range.startOffset - start, range.endOffset - start, replacement)
        return CSharpTextEdit(member.textRange, result.toString(), 0)
    }
}

/** Alt+Enter on a header: the accessibility Rider puts first, the other legal ones in the submenu ("To private", "To internal"…). */
class NativeCSharpChangeAccessibilityIntention : NativeCSharpContextAction("Change accessibility", serverHasIt = true), IntentionActionWithOptions {
    @Volatile private var shown: String = "Change accessibility"
    @Volatile private var others: List<String> = emptyList()

    override fun getText(): String = shown
    override fun getFamilyName(): String = "Change accessibility"

    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val member = NativeCSharpModifierEdits.headerAt(file, editor.caretModel.offset) ?: return null
        val access = NativeCSharpModifierEdits.primaryAccess(member) ?: return null
        shown = "To $access"
        others = NativeCSharpModifierEdits.otherAccess(member)
        return NativeCSharpModifierEdits.toAccess(member, access)
    }

    override fun getOptions(): List<IntentionAction> = others.map(::NativeCSharpToAccessOption)
}

/** One accessibility of the submenu of [NativeCSharpChangeAccessibilityIntention]. */
class NativeCSharpToAccessOption(private val access: String) : NativeCSharpContextAction("To $access", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpModifierEdits.headerAt(file, editor.caretModel.offset)?.let { NativeCSharpModifierEdits.toAccess(it, access) }
}

/** Alt+Enter on the header of an overridable member of a class: `virtual` (Rider: "To virtual"). */
class NativeCSharpToVirtualIntention : NativeCSharpContextAction("To virtual", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpModifierEdits.headerAt(file, editor.caretModel.offset)?.let(NativeCSharpModifierEdits::toVirtual)
}

/** Alt+Enter on the header of a method or property of an abstract class: `abstract`, the body removed (Rider: "To abstract"). */
class NativeCSharpToAbstractIntention : NativeCSharpContextAction("To abstract", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpModifierEdits.headerAt(file, editor.caretModel.offset)?.let { NativeCSharpModifierEdits.toAbstract(it, editor.document.charsSequence) }
}

/** Alt+Enter on the header of a type that is not partial: `partial` before its keyword (Rider: "Make partial"). */
class NativeCSharpMakePartialIntention : NativeCSharpContextAction("Make partial", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpModifierEdits.headerAt(file, editor.caretModel.offset)?.let(NativeCSharpModifierEdits::makePartial)
}
