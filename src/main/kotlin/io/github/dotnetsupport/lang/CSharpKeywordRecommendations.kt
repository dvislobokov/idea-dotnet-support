package io.github.dotnetsupport.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*

/**
 * Keywords of Roslyn's `KeywordRecommenders` (dotnet/roslyn, `src/Features/CSharp/Portable/Completion/KeywordRecommenders`) that the
 * places of [NativeCSharpCompletionPlace] miss (COMPLETION_GAPS 2.14). Kept apart from [NativeCSharpKeywords] so that the native list
 * stays as it is and these come on top of it ([CSharpCompletionBehaviourContributor]):
 *  - after a complete pattern (`x is > 0 |`, an arm `1 |`, `case 1 |`): `and`, `or`, and `when` in a `switch`;
 *  - after a complete expression on the same line (`var b = r |`, `return x |`): `with`, `switch`, `is`, `as`;
 *  - in an accessor list: `get`, `set`, `init` (events: `add`, `remove`) and the accessibility of an accessor;
 *  - in the body of a property accessor: `field` (C# 14);
 *  - after `where T :` / `,` of a constraint list: `allows` (C# 13, `allows ref struct`);
 *  - at a member's start in a static class: `extension` (C# 14);
 *  - `[` of a top-level attribute list: `assembly`, `module`;
 *  - `delegate*`: `managed`, `unmanaged`.
 * After a pattern or an expression the parser of a half-typed line makes a new statement or member of the caret, and the native list of
 * that place is wrong there: those places are [Recommendation.exclusive], the rest of the native list goes.
 */
object CSharpKeywordRecommendations {
    class Recommendation(val keywords: List<String>, val exclusive: Boolean)

    private val ACCESSORS = listOf("get", "set", "init")
    private val ACCESSOR_ACCESS = listOf("private", "protected", "internal")
    private val EXPRESSION_ENDS = setOf("true", "false", "null", "this", "base", ")", "]")

    fun at(leaf: PsiElement): Recommendation? {
        if (!CSharpLeaves.isIdentifier(leaf)) return null
        val prev = NativeCSharpCompletionPlace.previousToken(leaf)
        functionPointer(prev)?.let { return it }
        accessorList(leaf, prev)?.let { return it }
        if (prev != null) {
            afterPattern(leaf, prev)?.let { return it }
            afterExpression(leaf, prev)?.let { return it }
        }
        val extra = ArrayList<String>()
        if (inPropertyAccessorBody(leaf)) extra += "field"
        if (prev != null && inConstraintList(leaf, prev)) extra += "allows"
        val place = NativeCSharpCompletionPlace.of(leaf)
        if (place?.kind == NativeCompletionKind.MEMBER_START && place.modifiers.isEmpty() && isStaticClass(place.typeDeclaration)) extra += "extension"
        if (place?.kind == NativeCompletionKind.ATTRIBUTE && prev?.text == "[" && topLevelAttribute(prev)) extra += listOf("assembly", "module")
        return if (extra.isEmpty()) null else Recommendation(extra, exclusive = false)
    }

    /** `nameof(|`: a name is wanted, not a keyword (`nameof(int)`, `nameof(out)` do not compile). */
    fun inNameof(leaf: PsiElement): Boolean {
        val prev = NativeCSharpCompletionPlace.previousToken(leaf) ?: return false
        if (prev.text != "(") return false
        val before = NativeCSharpCompletionPlace.previousToken(prev) ?: return false
        return before.text == "nameof"
    }

    /** `typeof(|`: `dynamic` is no type there (CS1962). */
    fun inTypeof(leaf: PsiElement): Boolean {
        val prev = NativeCSharpCompletionPlace.previousToken(leaf) ?: return false
        return prev.text == "(" && NativeCSharpCompletionPlace.previousToken(prev)?.text == "typeof"
    }

    private fun functionPointer(prev: PsiElement?): Recommendation? {
        if (prev?.text != "*") return null
        val before = NativeCSharpCompletionPlace.previousToken(prev) ?: return null
        return if (before.text == "delegate") Recommendation(listOf("managed", "unmanaged"), exclusive = true) else null
    }

    /** In `{ get; | }`: the accessors not written yet, and the accessibility of one. */
    private fun accessorList(leaf: PsiElement, prev: PsiElement?): Recommendation? {
        val list = PsiTreeUtil.getParentOfType(leaf, CSharpAccessorList::class.java, true, CSharpBlock::class.java, CSharpArrowExpressionClause::class.java) ?: return null
        if (prev != null && prev.text != "{" && prev.text != ";" && prev.text != "}" && prev.text !in ACCESSOR_ACCESS) return null
        val written = list.accessors.mapNotNull { accessor -> accessor.keyword?.takeIf { it != leaf && !PsiTreeUtil.isAncestor(it, leaf, false) }?.text }.toSet()
        val keywords = ArrayList<String>()
        if (list.parent is CSharpEventDeclaration) {
            keywords += listOf("add", "remove").filter { it !in written }
        } else {
            if ("get" !in written) keywords += "get"
            if ("set" !in written && "init" !in written) keywords += listOf("set", "init")
        }
        if (prev?.text !in ACCESSOR_ACCESS) keywords += ACCESSOR_ACCESS
        return Recommendation(keywords, exclusive = true)
    }

    /** The caret right after a whole pattern: `x is > 0 |`, `x is not null |`, an arm `1 |`, `case Order o |`. */
    private fun afterPattern(leaf: PsiElement, prev: PsiElement): Recommendation? {
        var pattern: PsiElement? = null
        var current: PsiElement? = prev.parent
        while (current != null && current !is CSharpFile && current.textRange.endOffset == prev.textRange.endOffset) {
            if (current is CSharpPattern) pattern = current
            if (current is CSharpStatement || current is CSharpMemberDeclaration) break
            current = current.parent
        }
        pattern ?: return null
        if (PsiTreeUtil.isAncestor(pattern, leaf, false)) return null
        if (lineBreakBetween(prev, leaf)) return null
        var top: PsiElement = pattern
        while (top.parent is CSharpPattern || top.parent is CSharpSubpattern || top.parent is CSharpPositionalPatternClause || top.parent is CSharpPropertyPatternClause) top = top.parent
        val owner = top.parent
        val inSwitch = owner is CSharpSwitchExpressionArm || owner is CSharpCasePatternSwitchLabel
        if (owner !is CSharpIsPatternExpression && !inSwitch && owner !is CSharpPattern) return null
        return Recommendation(listOf("and", "or") + if (inSwitch) listOf("when") else emptyList(), exclusive = true)
    }

    /** The caret right after a whole expression on its line: `var b = r |`, `return x |`, `if (a |`. */
    private fun afterExpression(leaf: PsiElement, prev: PsiElement): Recommendation? {
        if (!(CSharpLeaves.isIdentifier(prev) || prev.text in EXPRESSION_ENDS || prev.parent is CSharpLiteralExpression)) return null
        if (lineBreakBetween(prev, leaf)) return null
        if (PsiTreeUtil.getParentOfType(prev, CSharpQueryExpression::class.java) != null) return null
        var expression: CSharpExpression? = null
        var current: PsiElement? = prev.parent
        while (current is CSharpExpression && current.textRange.endOffset == prev.textRange.endOffset) {
            if (current is CSharpType && current !is CSharpSimpleName) break
            expression = current
            current = current.parent
        }
        expression ?: return null
        if (PsiTreeUtil.isAncestor(expression, leaf, false)) return null
        val owner = expression.parent
        // a type, a declaration, a name being declared: not a value
        val valueOwner = owner is CSharpEqualsValueClause || owner is CSharpReturnStatement || owner is CSharpArgument || owner is CSharpArrowExpressionClause ||
            owner is CSharpAssignmentExpression && owner.right == expression || owner is CSharpIfStatement || owner is CSharpWhileStatement ||
            owner is CSharpExpressionStatement || owner is CSharpCaseSwitchLabel || owner is CSharpSwitchStatement || owner is CSharpParenthesizedExpression ||
            owner is CSharpBinaryExpression && owner.right == expression || owner is CSharpSwitchExpressionArm && owner.expression == expression
        if (!valueOwner) return null
        if (owner is CSharpEqualsValueClause && owner.parent is CSharpParameter) return null
        val keywords = arrayListOf("as", "is", "switch", "with")
        if (owner is CSharpCaseSwitchLabel) keywords += "when"
        return Recommendation(keywords, exclusive = true)
    }

    private fun lineBreakBetween(prev: PsiElement, leaf: PsiElement): Boolean {
        val file = leaf.containingFile ?: return true
        val text = file.viewProvider.contents
        val start = prev.textRange.endOffset
        val end = leaf.textRange.startOffset
        if (start > end || end > text.length) return true
        return text.subSequence(start, end).contains('\n')
    }

    private fun inPropertyAccessorBody(leaf: PsiElement): Boolean {
        val accessor = PsiTreeUtil.getParentOfType(leaf, CSharpAccessorDeclaration::class.java, true, CSharpMemberDeclaration::class.java, CSharpAnonymousFunctionExpression::class.java, CSharpLocalFunctionStatement::class.java)
        if (accessor != null) {
            val body = accessor.body ?: accessor.expressionBody
            return body != null && PsiTreeUtil.isAncestor(body, leaf, false) && accessor.parent?.parent is CSharpPropertyDeclaration
        }
        // `int X => |`: the expression body of a property is its getter
        val clause = PsiTreeUtil.getParentOfType(leaf, CSharpArrowExpressionClause::class.java, true, CSharpMemberDeclaration::class.java, CSharpAnonymousFunctionExpression::class.java)
        return clause != null && clause.parent is CSharpPropertyDeclaration
    }

    private fun inConstraintList(leaf: PsiElement, prev: PsiElement): Boolean {
        val clause = PsiTreeUtil.getParentOfType(leaf, CSharpTypeParameterConstraintClause::class.java) ?: return false
        return prev.parent == clause && (prev == clause.colonToken || prev.text == ",")
    }

    private fun isStaticClass(type: CSharpTypeDeclaration?): Boolean =
        type is CSharpClassDeclaration && type.modifiers.any { it.text == "static" } && type.typeParameterList == null && type.parent !is CSharpTypeDeclaration

    /** `[assembly:` / `[module:` go before the namespaces and types of a file: a list of the file, or of one of its top-level declarations. */
    private fun topLevelAttribute(open: PsiElement): Boolean {
        val list = open.parent as? CSharpAttributeList ?: return false
        if (list.target != null) return false
        return when (val owner = list.parent) {
            is CSharpCompilationUnit -> true
            is CSharpMemberDeclaration -> owner.parent is CSharpCompilationUnit && owner !is CSharpGlobalStatement
            else -> false
        }
    }
}
