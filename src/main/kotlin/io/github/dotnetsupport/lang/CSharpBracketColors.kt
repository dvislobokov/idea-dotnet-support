package io.github.dotnetsupport.lang

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.tree.TokenSet
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.settings.DotNetSettings
import java.util.BitSet

/**
 * Matching brackets by their depth, as VS Code's bracket pair colorization and Rider's rainbow brackets: `()`, `[]`, `{}` and the `<>` of
 * type argument and parameter lists (never a comparison). The platform has no mechanism for it (its `RainbowVisitor` colors identifiers),
 * so this is an annotator at INFORMATION with the keys [LEVELS], and the layer of [CSharpOpeningColors] paints the same as a file opens.
 *
 * The brackets come from the tokens of [CSharpHighlightingLexer], which keeps strings, characters and comments whole and lexes the holes of
 * interpolated strings as code, so the same on both trees; the native tree says which `<` and `>` are brackets and which `#if` branches
 * are inactive (left alone there), the heuristic one knows neither. Mismatches as in VS Code: a closing bracket takes the nearest opening
 * one of its kind, the ones opened in between stay uncolored, a closing bracket without an opening one too — the rest of the file keeps
 * its levels.
 */
object CSharpBracketColors {
    /** `CSHARP_BRACES_LEVEL_n`: cycled by depth; the defaults are VS Code's (`colorSchemes/CSharpBrackets*.xml`), a scheme without them shows plain braces. */
    val LEVELS: List<TextAttributesKey> = (1..3).map { createTextAttributesKey("CSHARP_BRACES_LEVEL_$it", CSharpSyntaxHighlighter.BRACES) }

    private val TYPE_LISTS = TokenSet.create(SyntaxKind.TypeArgumentList, SyntaxKind.TypeParameterList, SyntaxKind.FunctionPointerParameterList)

    fun enabled(): Boolean = DotNetSettings.getInstance().colorizeBrackets

    /** Both brackets of every matched pair of [file] with the key of its depth, in the order of the text. */
    fun colors(file: CSharpFile): List<Pair<TextRange, TextAttributesKey>> {
        val text = file.viewProvider.contents
        val native = file.compilationUnit != null
        val angles = if (native) angleBrackets(file, text.length) else null
        val inactive = if (native) NativeCSharpInactiveCode.ranges(file) else emptyList()
        return pairs(text, angles, inactive).map { (range, depth) -> range to LEVELS[depth % LEVELS.size] }
    }

    /** The `<` and `>` of the type lists of the native tree, by offset: `[0]` opening, `[1]` closing. */
    private fun angleBrackets(file: CSharpFile, length: Int): Array<BitSet> {
        val out = arrayOf(BitSet(length), BitSet(length))
        var leaf = TreeUtil.findFirstLeaf(file.node)
        while (leaf != null) {
            val type = leaf.elementType
            if ((type === SyntaxKind.LessThanToken || type === SyntaxKind.GreaterThanToken) && leaf.textLength == 1 && TYPE_LISTS.contains(leaf.treeParent?.elementType)) {
                out[if (type === SyntaxKind.LessThanToken) 0 else 1].set(leaf.startOffset)
            }
            leaf = TreeUtil.nextLeaf(leaf)
        }
        return out
    }

    /**
     * The matched pairs of [text] as `(bracket, depth)` for each of the two brackets, in the order of the text. [angles]: the offsets of the
     * `<` (`[0]`) and `>` (`[1]`) that are brackets, none when unknown; [inactive]: ranges whose brackets do not count, in order.
     */
    fun pairs(text: CharSequence, angles: Array<BitSet>?, inactive: List<TextRange>): List<Pair<TextRange, Int>> {
        val out = ArrayList<Pair<TextRange, Int>>()
        // the stack of open brackets: kind and offset
        var kinds = IntArray(32)
        var offsets = IntArray(32)
        var size = 0
        var skipped = 0
        val lexer = CSharpHighlightingLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            while (skipped < inactive.size && inactive[skipped].endOffset <= start) skipped++
            if (skipped < inactive.size && inactive[skipped].startOffset <= start) { lexer.advance(); continue }
            val kind = when (type) {
                CSharpTokenTypes.LBRACE -> 0
                CSharpTokenTypes.RBRACE -> -1
                CSharpTokenTypes.LPAREN -> 1
                CSharpTokenTypes.RPAREN -> -2
                CSharpTokenTypes.LBRACKET -> 2
                CSharpTokenTypes.RBRACKET -> -3
                CSharpTokenTypes.OPERATOR -> when {
                    angles == null -> Int.MIN_VALUE
                    angles[0].get(start) -> 3
                    angles[1].get(start) -> -4
                    else -> Int.MIN_VALUE
                }
                else -> Int.MIN_VALUE
            }
            lexer.advance()
            when {
                kind == Int.MIN_VALUE -> {}
                kind >= 0 -> {
                    if (size == kinds.size) { kinds = kinds.copyOf(size * 2); offsets = offsets.copyOf(size * 2) }
                    kinds[size] = kind
                    offsets[size] = start
                    size++
                }
                else -> {
                    // the nearest open bracket of the kind; the ones above it are left unclosed and uncolored (as VS Code does)
                    var j = size - 1
                    while (j >= 0 && kinds[j] != -kind - 1) j--
                    if (j < 0) continue
                    out += TextRange(offsets[j], offsets[j] + 1) to j
                    out += TextRange(start, start + 1) to j
                    size = j
                }
            }
        }
        out.sortBy { it.first.startOffset }
        return out
    }

    /** The checkbox of Settings | .NET changed: the open editors follow, the colors remembered for the next opening are stale. */
    fun rehighlight() {
        CSharpOpeningColors.forgetAll()
        for (project in ProjectManager.getInstance().openProjects) if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart()
    }
}

class CSharpBracketColorsAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is CSharpFile || !CSharpBracketColors.enabled()) return
        for ((range, key) in CSharpBracketColors.colors(element)) holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
    }
}
