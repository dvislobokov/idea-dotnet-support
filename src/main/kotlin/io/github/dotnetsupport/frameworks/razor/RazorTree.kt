package io.github.dotnetsupport.frameworks.razor

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTFactory
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lexer.LexerBase
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.TreeElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.CharTable
import io.github.dotnetsupport.razor.lang.parser.RazorParser
import io.github.dotnetsupport.razor.lang.parser.RazorSyntaxTree
import io.github.dotnetsupport.razor.lang.syntax.BaseMarkupEndTagSyntax
import io.github.dotnetsupport.razor.lang.syntax.BaseMarkupStartTagSyntax
import io.github.dotnetsupport.razor.lang.syntax.BaseRazorDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpEphemeralTextLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpExpressionLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpStatementLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpTransitionSyntax
import io.github.dotnetsupport.razor.lang.syntax.ChunkGenerator
import io.github.dotnetsupport.razor.lang.syntax.MarkupEphemeralTextLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupTextLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupTransitionSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorMetaCodeSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorParserOptions
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxNode
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxWalker
import io.github.dotnetsupport.razor.lang.syntax.RazorToken
import io.github.dotnetsupport.razor.lang.syntax.UnclassifiedTextLiteralSyntax
import java.util.EnumMap

/**
 * What a token of the compiler's tree is to the editor (RAZOR_PLAN.md, R3a): the markup (the text of the HTML file of the view), the C#
 * (the hosts of the injected C# document, [RazorCodeLanguage] for Ctrl+/) or Razor's own — transitions, directive keywords, braces,
 * comments. Told by the chunk generator of the literal the token is in, as the compiler's `.cspans` are.
 */
enum class RazorSpan { MARKUP, CODE, META }

/** A token of the tree in the PSI: its compiler kind and its span. */
class RazorTreeTokenType(val kind: RazorSyntaxKind, val span: RazorSpan, language: Language) :
    IElementType("Razor:" + (if (span == RazorSpan.CODE) "CS:" else "") + kind.name, language)

/** A node of the tree in the PSI: its compiler kind; a literal of C# is of [RazorCodeLanguage] (the language at a line start, for Ctrl+/). */
class RazorTreeNodeType(val kind: RazorSyntaxKind, language: Language) : IElementType(kind.name, language) {
    override fun toString(): String = "Razor:" + (if (language == RazorCodeLanguage) "CS:" else "") + kind
}

/** The element types of the PSI built from the compiler's tree: one per node kind (two for the literals that can be C#), one per token kind and span. */
object RazorTreeTypes {
    private val nodes = EnumMap<RazorSyntaxKind, RazorTreeNodeType>(RazorSyntaxKind::class.java)
    private val codeNodes = EnumMap<RazorSyntaxKind, RazorTreeNodeType>(RazorSyntaxKind::class.java)
    private val tokens: Map<RazorSpan, EnumMap<RazorSyntaxKind, RazorTreeTokenType>> = RazorSpan.entries.associateWith { EnumMap(RazorSyntaxKind::class.java) }

    init {
        for (kind in RazorSyntaxKind.entries) {
            if (!kind.isTokenKind && kind != RazorSyntaxKind.None && kind != RazorSyntaxKind.List) {
                nodes[kind] = RazorTreeNodeType(kind, RazorLanguage)
                codeNodes[kind] = RazorTreeNodeType(kind, RazorCodeLanguage)
            } else if (kind.isTokenKind) for (span in RazorSpan.entries) {
                tokens.getValue(span)[kind] = RazorTreeTokenType(kind, span, if (span == RazorSpan.CODE) RazorCodeLanguage else RazorLanguage)
            }
        }
    }

    /** The root node, the context of the C# injection ([RazorDocumentPsi]). */
    @JvmField val DOCUMENT: RazorTreeNodeType = node(RazorSyntaxKind.RazorDocument, RazorSpan.META)

    fun node(kind: RazorSyntaxKind, span: RazorSpan?): RazorTreeNodeType = (if (span == RazorSpan.CODE) codeNodes else nodes)[kind] ?: error("no element type for $kind")
    fun token(kind: RazorSyntaxKind, span: RazorSpan): RazorTreeTokenType = tokens.getValue(span)[kind] ?: error("no token type for $kind")

    /** Everything but the markup: the HTML parser's errors next to them are not errors of the page ([RazorErrorFilter]). */
    @JvmField val TEMPLATE: TokenSet = TokenSet.create(*(tokens.getValue(RazorSpan.META).values + tokens.getValue(RazorSpan.CODE).values).toTypedArray())

    @JvmField val COMMENTS: TokenSet = TokenSet.create(
        token(RazorSyntaxKind.RazorCommentLiteral, RazorSpan.META), token(RazorSyntaxKind.RazorCommentStar, RazorSpan.META), token(RazorSyntaxKind.RazorCommentTransition, RazorSpan.META),
    )

    /** The span of the tokens of a literal node; null for a block, whose tokens (if any) take the span of the literal above. */
    fun spanOf(node: RazorSyntaxNode): RazorSpan? {
        val generator = when (node) {
            is RazorMetaCodeSyntax -> node.chunkGenerator
            is UnclassifiedTextLiteralSyntax -> node.chunkGenerator
            is MarkupTransitionSyntax -> node.chunkGenerator
            is MarkupTextLiteralSyntax -> node.chunkGenerator
            is MarkupEphemeralTextLiteralSyntax -> node.chunkGenerator
            is BaseMarkupStartTagSyntax -> node.chunkGenerator
            is BaseMarkupEndTagSyntax -> node.chunkGenerator
            is CSharpTransitionSyntax -> node.chunkGenerator
            is CSharpStatementLiteralSyntax -> node.chunkGenerator
            is CSharpExpressionLiteralSyntax -> node.chunkGenerator
            is CSharpEphemeralTextLiteralSyntax -> node.chunkGenerator
            else -> return null
        }
        return when (generator) {
            ChunkGenerator.Markup, is ChunkGenerator.LiteralAttribute -> RazorSpan.MARKUP
            ChunkGenerator.Statement, ChunkGenerator.Expression, is ChunkGenerator.DirectiveToken, is ChunkGenerator.AddImport -> RazorSpan.CODE
            else -> RazorSpan.META
        }
    }
}

/** A node of the compiler's tree in the PSI. */
open class RazorElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    val kind: RazorSyntaxKind get() = (node.elementType as RazorTreeNodeType).kind
    override fun toString(): String = node.elementType.toString()
}

/** The root of the tree: what the C# injector is asked about ([RazorCSharpInjector]). */
class RazorDocumentPsi(node: ASTNode) : RazorElement(node)

/**
 * `@page "/x"`, `@using Foo`, `@inject T Name`, `@code { }`, `@section Name { }`: the transition, the body with the keyword (a
 * [RazorSyntaxKind.RazorMetaCode]), the tokens of the directive and, for a block directive, the braces (meta code) around its content.
 */
class RazorDirectivePsi(node: ASTNode) : RazorElement(node) {
    private val body: RazorElement? get() = children.filterIsInstance<RazorElement>().firstOrNull { it.kind == RazorSyntaxKind.RazorDirectiveBody }

    /** `page`, `code`, `section`, `using`... */
    val directiveName: String
        get() = when (kind) {
            RazorSyntaxKind.RazorUsingDirective -> "using"
            else -> body?.children?.filterIsInstance<RazorElement>()?.firstOrNull { it.kind == RazorSyntaxKind.RazorMetaCode }?.text?.trim().orEmpty()
        }

    /** The C# literals of the directive's tokens: `ILogger<Counter>` and `Logger` of `@inject`, `"/counter"` of `@page`. */
    val tokens: List<RazorCodeLiteral> get() = PsiTreeUtil.findChildrenOfType(this, RazorCodeLiteral::class.java).filter { it.isValidHost && !it.isInsideBraces() }

    /** The argument as written: `Scripts` of `@section Scripts`, `"/counter"` of `@page`, `ILogger<Counter> Logger` of `@inject`. */
    val argument: String?
        get() {
            val tokens = tokens
            if (tokens.isEmpty()) return null
            return text.substring(tokens.first().startOffsetInParent(this), tokens.last().let { it.startOffsetInParent(this) + it.textLength }).trim().takeIf { it.isNotEmpty() }
        }

    val openBrace: PsiElement? get() = braces().firstOrNull { it.text == "{" }
    val closeBrace: PsiElement? get() = braces().lastOrNull { it.text == "}" }?.takeIf { openBrace != null }

    private fun braces(): List<PsiElement> {
        val code = body?.children?.filterIsInstance<RazorElement>()?.firstOrNull { it.kind == RazorSyntaxKind.CSharpCodeBlock } ?: return emptyList()
        return code.children.filterIsInstance<RazorElement>().filter { it.kind == RazorSyntaxKind.RazorMetaCode }
    }

    private fun RazorCodeLiteral.isInsideBraces(): Boolean {
        val open = openBrace ?: return false
        return textOffset > open.textOffset
    }

    private fun PsiElement.startOffsetInParent(ancestor: PsiElement): Int = textRange.startOffset - ancestor.textRange.startOffset
}

/**
 * A literal of C# tokens: a statement, an expression, a directive token, `using Foo`. The hosts of the injected C# document
 * ([RazorCSharpInjector]): the text goes into it as written ([isValidHost] for the ones with code, not for the whitespace of a directive).
 */
class RazorCodeLiteral(node: ASTNode) : RazorElement(node), PsiLanguageInjectionHost {
    val span: RazorSpan get() = (node.firstChildNode?.elementType as? RazorTreeTokenType)?.span ?: RazorSpan.META
    override fun isValidHost(): Boolean = span == RazorSpan.CODE && textLength > 0

    override fun updateText(text: String): PsiLanguageInjectionHost {
        val type = node.firstChildNode?.elementType ?: return this
        val leaf = ASTFactory.leaf(type, text)
        node.replaceAllChildrenToChildrenOf(ASTFactory.composite(node.elementType).also { it.rawAddChildren(leaf) })
        return this
    }

    override fun createLiteralTextEscaper(): LiteralTextEscaper<out PsiLanguageInjectionHost> = LiteralTextEscaper.createSimple(this)
}

/** Builds the AST of the PSI from the compiler's tree: a composite per node (the lists are transparent), a leaf per token with text. */
class RazorAstBuilder private constructor(private val charTable: CharTable?) : RazorSyntaxWalker() {
    private val stack = ArrayDeque<CompositeElement>()
    private val spans = ArrayDeque<RazorSpan>().also { it.addLast(RazorSpan.META) }
    private var root: CompositeElement? = null

    override fun visitNode(node: RazorSyntaxNode, position: Int): Boolean {
        val span = RazorTreeTypes.spanOf(node)
        val composite = ASTFactory.composite(RazorTreeTypes.node(node.kind, span))
        stack.lastOrNull()?.rawAddChildren(composite) ?: run { root = composite }
        stack.addLast(composite)
        spans.addLast(span ?: spans.last())
        return true
    }

    override fun leaveNode(node: RazorSyntaxNode, position: Int) {
        stack.removeLast()
        spans.removeLast()
    }

    override fun visitToken(token: RazorToken, position: Int) {
        if (token.content.isEmpty()) return
        val text = charTable?.intern(token.content) ?: token.content
        stack.last().rawAddChildren(ASTFactory.leaf(RazorTreeTypes.token(token.kind, spans.last()), text))
    }

    companion object {
        /** The options of the file [name] as the SDK's engine has them: the directives of the kind, the Roslyn tokenizer. */
        fun options(name: String): RazorParserOptions = RazorParserOptions.forFile(name, useRoslynTokenizer = true)

        fun parse(text: CharSequence, name: String): RazorSyntaxTree = RazorParser.parse(text, options(name), name)

        fun build(tree: RazorSyntaxTree, charTable: CharTable?): TreeElement {
            val builder = RazorAstBuilder(charTable)
            builder.visit(tree.root)
            return builder.root!!
        }

        /** The tree of the last parse of the file ([RazorFileElementType]): its diagnostics (R6), its options. */
        val TREE: Key<RazorSyntaxTree> = Key.create("razor.syntaxTree")
    }
}

/**
 * The base lexer of the HTML file of the view ([RazorTemplateDataElementType]): the markup of the compiler's tree as [RazorTokenTypes.MARKUP],
 * everything else as [RazorTokenTypes.TEMPLATE_OUTER] — runs merged, so the HTML parser sees the markup of the file with the Razor cut out.
 */
class RazorTemplateLexer(private val name: String) : LexerBase() {
    private var buffer: CharSequence = ""
    private var endOffset = 0
    private var runs: IntArray = IntArray(0)
    private var types: Array<IElementType?> = arrayOfNulls(0)
    private var index = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        val tree = RazorAstBuilder.parse(buffer.subSequence(startOffset, endOffset), name)
        val starts = ArrayList<Int>()
        val kinds = ArrayList<IElementType>()
        object : RazorSyntaxWalker() {
            val spans = ArrayDeque<RazorSpan>().also { it.addLast(RazorSpan.META) }
            override fun visitNode(node: RazorSyntaxNode, position: Int): Boolean {
                spans.addLast(RazorTreeTypes.spanOf(node) ?: spans.last())
                return true
            }
            override fun leaveNode(node: RazorSyntaxNode, position: Int) {
                spans.removeLast()
            }
            override fun visitToken(token: RazorToken, position: Int) {
                if (token.content.isEmpty()) return
                val type = if (spans.last() == RazorSpan.MARKUP) RazorTokenTypes.MARKUP else RazorTokenTypes.TEMPLATE_OUTER
                if (kinds.lastOrNull() != type) {
                    starts += startOffset + position
                    kinds += type
                }
            }
        }.visit(tree.root)
        runs = IntArray(starts.size + 1).also { starts.forEachIndexed { i, s -> it[i] = s }; it[starts.size] = endOffset }
        types = arrayOfNulls<IElementType>(kinds.size).also { kinds.forEachIndexed { i, k -> it[i] = k } }
        index = 0
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = types.getOrNull(index)
    override fun getTokenStart(): Int = runs[index]
    override fun getTokenEnd(): Int = runs[index + 1]
    override fun advance() {
        if (index < types.size) index++
    }
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset
}
