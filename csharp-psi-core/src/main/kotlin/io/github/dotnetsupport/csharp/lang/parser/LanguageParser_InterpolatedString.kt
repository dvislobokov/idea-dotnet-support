// Ported from Roslyn: src/Compilers/CSharp/Portable/Parser/LanguageParser_InterpolatedString.cs (ParseInterpolatedStringToken,
// ParseInterpolation, the alignment and format clauses), roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c.
// Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
// Roslyn re-lexes the whole literal here; our lexer already emits the parts (docs/csharp-psi/GRAMMAR.md, "Lexer vs Roslyn's tokens"),
// so this file only builds the tree over them.
package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpDiagnosticAnchor
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpErrorCode

/** `ParseInterpolatedStringToken` (LPI 90): `InterpolatedStringExpression(start, parts..., end)` over the lexer's parts. */
fun LanguageParser.parseInterpolatedStringToken(): Node {
    val m = open()
    if (currentKind === SyntaxKind.InterpolatedStringToken) {
        // An unsplit literal (the lexer gave up on it): one token, no contents.
        eatToken()
        return m.done(SyntaxKind.InterpolatedStringExpression)
    }
    // Split this literal into its parts (it is one token of the window until here, SyntaxParser.splitInterpolatedStringAt).
    val saveSplit = splitInterpolatedStringAt
    // The literal's last lexeme (Roslyn's extent of the token, Tok.lastStep): an unterminated `$"abc` ends at the line
    // break, and a `{` on a later line is not a hole of it.
    val lastRaw = builder.rawTokenIndex() + currentToken.lastStep
    splitInterpolatedStringAt = builder.rawTokenIndex() + currentToken.rawStep
    try {
        parseInterpolatedStringParts(lastRaw)
    } finally {
        splitInterpolatedStringAt = saveSplit
    }
    return m.done(SyntaxKind.InterpolatedStringExpression)
}

/** The parts of the literal being split: the start token, texts and interpolations, the end token. */
private fun LanguageParser.parseInterpolatedStringParts(lastRaw: Int) {
    eatToken() // the start token
    loop@ while (true) {
        val kind = if (builder.rawTokenIndex() + currentToken.rawStep > lastRaw) SyntaxKind.EndOfFileToken else currentKind
        when (kind) {
            SyntaxKind.InterpolatedStringTextToken -> {
                val t = open()
                eatToken()
                t.done(SyntaxKind.InterpolatedStringText)
            }
            SyntaxKind.OpenBraceToken -> parseInterpolation()
            SyntaxKind.InterpolatedStringEndToken, SyntaxKind.InterpolatedRawStringEndToken -> {
                eatToken()
                break@loop
            }
            else -> {
                createMissingToken(SyntaxKind.InterpolatedStringEndToken)
                break@loop
            }
        }
    }
}

/** `ParseInterpolation` (LPI 518): `{ expression (, alignment)? (: format)? }`. */
private fun LanguageParser.parseInterpolation() {
    val m = open()
    eatToken(SyntaxKind.OpenBraceToken)
    // Roslyn parses the hole's text with a nested parser that ends before the format `:`; see SyntaxParser.interpolationHoleDepth.
    val saveHoleEnd = interpolationHoleEnd
    val end = builder.rawTokenIndex() + interpolationHoleEndStep()
    interpolationHoleEnd = end
    interpolationHoleDepth++
    // Roslyn's nested parser starts with no terminator state and outside a query (only `async` and `field` carry over,
    // `ParserSyntaxContextResetter`, LPI 508): an enclosing statement's `}` terminator does not stop the hole's lists.
    val saveTerm = termState
    val saveInQuery = isInQuery
    termState = TerminatorState.EndOfFile
    isInQuery = false
    try {
        parseExpressionCore()
        if (currentKind === SyntaxKind.CommaToken) {
            val a = open()
            eatToken()
            parseExpressionCore()
            a.done(SyntaxKind.InterpolationAlignmentClause)
        }
    } finally {
        termState = saveTerm
        isInQuery = saveInQuery
        interpolationHoleDepth--
        interpolationHoleEnd = saveHoleEnd
    }
    // Tokens left before the end of the hole's expression (Roslyn: the rest of the nested parser's text) are skipped.
    if (builder.rawTokenIndex() + currentToken.rawStep < end) {
        // `ConsumeUnexpectedTokens(expression)`: ERR_UnexpectedToken with the first token's text, on the expression (LP 14721)
        val message = CSharpErrorCode.ERR_UnexpectedToken.describe(currentText, anchor = CSharpDiagnosticAnchor.PREVIOUS_SIBLING)
        val s = builder.mark()
        while (builder.rawTokenIndex() + currentToken.rawStep < end && advance()) {}
        s.error(message)
        countSkippedError()
    }
    if (currentKind === SyntaxKind.ColonToken) {
        val f = open()
        eatToken()
        if (currentKind === SyntaxKind.InterpolatedStringTextToken) eatToken() else createMissingToken(SyntaxKind.InterpolatedStringTextToken, report = false)
        f.done(SyntaxKind.InterpolationFormatClause)
    }
    if (currentKind === SyntaxKind.CloseBraceToken) eatToken() else createMissingToken(SyntaxKind.CloseBraceToken)
    m.done(SyntaxKind.Interpolation)
}

/**
 * Raw step (relative to the current token, the first after the hole's `{`) where the hole ends, by the rule of Roslyn's
 * `Lexer.ScanInterpolatedStringLiteralHoleBalancedText`: brackets opened in the hole are balanced, a closing bracket
 * that does not match the innermost open one is part of the hole (an error), and the hole ends at a `}` with nothing
 * open, or at the string's next part (text or end: the `}` is missing); the expression also ends before a format `:`
 * with nothing open. Nested interpolated strings are skipped whole.
 * So in `$"{D(.E}"` the `}` is a token of the hole's expression, not its end (ParsingTests' MismatchedInterpolatedStringContents).
 */
private fun LanguageParser.interpolationHoleEndStep(): Int {
    val open = ArrayList<com.intellij.psi.tree.IElementType>()
    var nestedStrings = 0
    var step = 0
    while (true) {
        val t = builder.rawLookup(step) ?: return step
        when (t) {
            SyntaxKind.InterpolatedStringStartToken, SyntaxKind.InterpolatedVerbatimStringStartToken,
            SyntaxKind.InterpolatedSingleLineRawStringStartToken, SyntaxKind.InterpolatedMultiLineRawStringStartToken -> nestedStrings++
            SyntaxKind.InterpolatedStringEndToken, SyntaxKind.InterpolatedRawStringEndToken -> {
                if (nestedStrings == 0) return step
                nestedStrings--
            }
            SyntaxKind.InterpolatedStringTextToken -> if (nestedStrings == 0) return step
            else -> if (nestedStrings == 0) {
                when (t) {
                    // The format `:`: the expression ends before it (followed by the format text or the hole's `}`).
                    SyntaxKind.ColonToken -> if (open.isEmpty() && isFormatColonAt(step)) return step
                    SyntaxKind.OpenParenToken -> open += SyntaxKind.CloseParenToken
                    SyntaxKind.OpenBracketToken -> open += SyntaxKind.CloseBracketToken
                    SyntaxKind.OpenBraceToken -> open += SyntaxKind.CloseBraceToken
                    SyntaxKind.CloseParenToken, SyntaxKind.CloseBracketToken, SyntaxKind.CloseBraceToken -> {
                        if (open.isEmpty()) {
                            if (t === SyntaxKind.CloseBraceToken) return step
                        } else if (open.last() === t) {
                            open.removeAt(open.size - 1)
                        }
                    }
                }
            }
        }
        step++
    }
}

/** The next significant raw token after raw [step] is the format text or the hole's `}`. */
private fun LanguageParser.isFormatColonAt(step: Int): Boolean {
    var s = step + 1
    while (true) {
        val t = builder.rawLookup(s) ?: return true
        if (!isTrivia(t)) return t === SyntaxKind.InterpolatedStringTextToken || t === SyntaxKind.CloseBraceToken
        s++
    }
}
