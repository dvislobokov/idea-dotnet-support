package io.github.dotnetsupport.csharp.lang.lexer

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.dotnetsupport.csharp.lang.CSharpLanguage
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.SyntaxKind

/**
 * A token inside a preprocessor directive (`#if`, `IDENT`, `&&`, `#pragma`, ...). [roslynKind] is the kind Roslyn
 * gives the token in the parsed directive (`HashToken`, `IfKeyword`, `IdentifierToken`, ...). It is a separate type
 * from the code token of the same kind so that the parser never sees it: directive tokens are trivia, as Roslyn's
 * structured directive trivia. The debug name ends in `Trivia` (`IfKeywordInDirectiveTrivia`) for the tree mappings
 * that drop trivia by name.
 */
class CSharpDirectiveTokenType(val roslynKind: IElementType) : IElementType("${roslynKind}InDirectiveTrivia", CSharpLanguage) {
    override fun toString(): String = debugName
}

/** Token types of the lexer that are not Roslyn kinds, and token sets of the lexer's output. */
object CSharpTokenTypes {
    private val directiveTypes: Map<IElementType, CSharpDirectiveTokenType> = (
        listOf(
            SyntaxKind.HashToken, SyntaxKind.OpenParenToken, SyntaxKind.CloseParenToken, SyntaxKind.CommaToken,
            SyntaxKind.MinusToken, SyntaxKind.ColonToken, SyntaxKind.ExclamationToken, SyntaxKind.ExclamationEqualsToken,
            SyntaxKind.EqualsToken, SyntaxKind.EqualsEqualsToken, SyntaxKind.AmpersandAmpersandToken,
            SyntaxKind.BarBarToken, SyntaxKind.NumericLiteralToken, SyntaxKind.StringLiteralToken,
            SyntaxKind.IdentifierToken, SyntaxKind.BadToken,
        ) + CSharpSyntaxFacts.IsPreprocessorKeyword.types
        ).associateWith { CSharpDirectiveTokenType(it) }

    /** The directive token type of a Roslyn [kind] (`ScanDirectiveToken` kinds and preprocessor keywords). */
    @JvmStatic
    fun directive(kind: IElementType): IElementType =
        directiveTypes[kind] ?: throw IllegalArgumentException("not a directive token kind: $kind")

    /** All tokens of directive lines (not their whitespace, comments, messages). */
    @JvmField val DIRECTIVE_TOKENS: TokenSet = TokenSet.create(*directiveTypes.values.toTypedArray())

    @JvmField val WHITESPACES: TokenSet = TokenSet.create(SyntaxKind.WhitespaceTrivia, SyntaxKind.EndOfLineTrivia)

    /**
     * Trivia other than whitespace, which the parser skips: comments, doc comments, directive tokens and messages
     * (`PreprocessingMessageTrivia`), disabled text of `#if` and of conflict markers, conflict markers.
     */
    @JvmField val COMMENTS: TokenSet = TokenSet.orSet(
        TokenSet.create(
            SyntaxKind.SingleLineCommentTrivia,
            SyntaxKind.MultiLineCommentTrivia,
            SyntaxKind.SingleLineDocumentationCommentTrivia,
            SyntaxKind.MultiLineDocumentationCommentTrivia,
            SyntaxKind.PreprocessingMessageTrivia,
            SyntaxKind.ConflictMarkerTrivia,
            SyntaxKind.DisabledTextTrivia,
        ),
        DIRECTIVE_TOKENS,
    )
}
