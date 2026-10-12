// Ported from Roslyn: src/Compilers/CSharp/Portable/Parser/LanguageParser.cs, query expressions (IsQueryExpression 14142,
// IsQueryExpressionAfterFrom 14149, ParseQueryExpression 14200, ParseQueryBody 14212, ParseFromClause 14259,
// ParseJoinClause 14292, ParseLetClause 14312, ParseWhereClause 14325, ParseOrderByClause 14333, ParseOrdering 14378,
// ParseSelectClause 14396, ParseGroupClause 14404, ParseQueryContinuation 14414), roslynCommit
// 35d9211b841e7613c1d2f8f5af6d628ace696c4c. Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License
// (NOTICE.md). Departures are listed in docs/csharp-psi/GRAMMAR.md ("Statements and queries").
package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpErrorCode

/** `IsQueryExpression(mayBeVariableDeclaration, mayBeMemberDeclaration)` (LP 14142) */
fun LanguageParser.isQueryExpression(mayBeVariableDeclaration: Boolean, mayBeMemberDeclaration: Boolean): Boolean =
    currentContextualKind === SyntaxKind.FromKeyword && isQueryExpressionAfterFrom(mayBeVariableDeclaration, mayBeMemberDeclaration)

/** `IsQueryExpressionAfterFrom` (LP 14149): `from x in`, `from T x`, ... */
private fun LanguageParser.isQueryExpressionAfterFrom(mayBeVariableDeclaration: Boolean, mayBeMemberDeclaration: Boolean): Boolean {
    val pk1 = peekToken(1).kind
    if (CSharpSyntaxFacts.isPredefinedType(pk1)) return true
    if (pk1 === SyntaxKind.IdentifierToken) {
        val pk2 = peekToken(2).kind
        if (pk2 === SyntaxKind.InKeyword) return true
        if (mayBeVariableDeclaration && (pk2 === SyntaxKind.SemicolonToken || pk2 === SyntaxKind.CommaToken || pk2 === SyntaxKind.EqualsToken)) return false
        if (mayBeMemberDeclaration) {
            if (pk2 === SyntaxKind.OpenParenToken || pk2 === SyntaxKind.OpenBraceToken) return false
        } else {
            return true
        }
    }
    return speculate {
        eatToken()
        scanType() != ScanTypeFlags.NotType && (currentKind === SyntaxKind.IdentifierToken || currentKind === SyntaxKind.InKeyword)
    }
}

/**
 * `ParseQueryExpression(precedence)` (LP 14200), in the query context (`ParserSyntaxContextResetter(isInQueryContext:
 * true)`, restored after the body). `WRN_PrecedenceInversion` for a query below assignment precedence is a warning:
 * not a parse error here.
 */
fun LanguageParser.parseQueryExpression(@Suppress("UNUSED_PARAMETER") precedence: Int): Node {
    val savedInQuery = isInQuery
    isInQuery = true
    try {
        val m = open()
        parseFromClause()
        parseQueryBody()
        return m.done(SyntaxKind.QueryExpression)
    } finally {
        isInQuery = savedInQuery
    }
}

/** `ParseQueryBody` (LP 14212) */
private fun LanguageParser.parseQueryBody(): Node {
    val m = open()
    while (true) {
        when (currentContextualKind) {
            SyntaxKind.FromKeyword -> parseFromClause()
            SyntaxKind.JoinKeyword -> parseJoinClause()
            SyntaxKind.LetKeyword -> parseLetClause()
            SyntaxKind.WhereKeyword -> parseWhereClause()
            SyntaxKind.OrderByKeyword -> parseOrderByClause()
            else -> break
        }
    }
    when (currentContextualKind) {
        SyntaxKind.SelectKeyword -> parseSelectClause()
        SyntaxKind.GroupKeyword -> parseGroupClause()
        else -> {
            // `SelectClause(EatToken(SelectKeyword, ERR_ExpectedSelectOrGroup), CreateMissingIdentifierName())`
            val select = open()
            createMissingToken(SyntaxKind.SelectKeyword, message = CSharpErrorCode.ERR_ExpectedSelectOrGroup.describe())
            createMissingIdentifierNameWithoutError()
            select.done(SyntaxKind.SelectClause)
        }
    }
    if (currentContextualKind === SyntaxKind.IntoKeyword) parseQueryContinuation()
    return m.done(SyntaxKind.QueryBody)
}

/** `ParseFromClause` (LP 14259) */
private fun LanguageParser.parseFromClause(): Node {
    val m = open()
    eatContextualToken(SyntaxKind.FromKeyword)
    if (peekToken(1).kind !== SyntaxKind.InKeyword) parseType()
    if (peekToken(1).contextualKind === SyntaxKind.InKeyword &&
        (currentKind !== SyntaxKind.IdentifierToken || CSharpSyntaxFacts.isQueryContextualKeyword(currentContextualKind))
    ) {
        // A keyword or a literal where the name belongs, with the `in` in its place: `ConvertToMissingWithTrailingTrivia`
        // makes a missing identifier with the token as its skipped trailing trivia (the missing token comes first).
        // `GetExpectedTokenError(IdentifierToken, name.ContextualKind)` on the token, then the missing identifier (LP 14276)
        val message = SyntaxParser.expectedMessage(SyntaxKind.IdentifierToken, currentContextualKind)
        createMissingToken(SyntaxKind.IdentifierToken, message = SKIPPED)
        skipTokens(1, message, countError = false)
    } else {
        parseIdentifierToken()
    }
    eatToken(SyntaxKind.InKeyword)
    parseExpressionCore()
    return m.done(SyntaxKind.FromClause)
}

/** `ParseJoinClause` (LP 14292) */
private fun LanguageParser.parseJoinClause(): Node {
    val m = open()
    eatContextualToken(SyntaxKind.JoinKeyword)
    if (peekToken(1).kind !== SyntaxKind.InKeyword) parseType()
    parseIdentifierToken()
    eatToken(SyntaxKind.InKeyword)
    parseExpressionCore()
    eatContextualToken(SyntaxKind.OnKeyword)
    parseExpressionCore()
    eatContextualToken(SyntaxKind.EqualsKeyword)
    parseExpressionCore()
    if (currentContextualKind === SyntaxKind.IntoKeyword) {
        val into = open()
        convertToKeyword()
        parseIdentifierToken()
        into.done(SyntaxKind.JoinIntoClause)
    }
    return m.done(SyntaxKind.JoinClause)
}

/** `ParseLetClause` (LP 14312) */
private fun LanguageParser.parseLetClause(): Node {
    val m = open()
    eatContextualToken(SyntaxKind.LetKeyword)
    if (CSharpSyntaxFacts.isReservedKeyword(currentKind) && peekToken(1).kind === SyntaxKind.EqualsToken) eatTokenAsKind(SyntaxKind.IdentifierToken)
    else parseIdentifierToken()
    eatToken(SyntaxKind.EqualsToken)
    parseExpressionCore()
    return m.done(SyntaxKind.LetClause)
}

/** `ParseWhereClause` (LP 14325) */
private fun LanguageParser.parseWhereClause(): Node {
    val m = open()
    eatContextualToken(SyntaxKind.WhereKeyword)
    parseExpressionCore()
    return m.done(SyntaxKind.WhereClause)
}

/**
 * `ParseOrderByClause` (LP 14333). Roslyn's loop runs only while the current token is a comma, so its skip branch
 * (`skipBadOrderingListTokens`) is unreachable and not ported.
 */
private fun LanguageParser.parseOrderByClause(): Node {
    val m = open()
    eatContextualToken(SyntaxKind.OrderByKeyword)
    parseOrdering()
    while (currentKind === SyntaxKind.CommaToken) {
        eatToken(SyntaxKind.CommaToken)
        parseOrdering()
    }
    return m.done(SyntaxKind.OrderByClause)
}

/** `ParseOrdering` (LP 14378) */
private fun LanguageParser.parseOrdering(): Node {
    val m = open()
    parseExpressionCore()
    var kind = SyntaxKind.AscendingOrdering
    val ck = currentContextualKind
    if (ck === SyntaxKind.AscendingKeyword || ck === SyntaxKind.DescendingKeyword) {
        convertToKeyword()
        if (ck === SyntaxKind.DescendingKeyword) kind = SyntaxKind.DescendingOrdering
    }
    return m.done(kind)
}

/** `ParseSelectClause` (LP 14396) */
private fun LanguageParser.parseSelectClause(): Node {
    val m = open()
    eatContextualToken(SyntaxKind.SelectKeyword)
    parseExpressionCore()
    return m.done(SyntaxKind.SelectClause)
}

/** `ParseGroupClause` (LP 14404) */
private fun LanguageParser.parseGroupClause(): Node {
    val m = open()
    eatContextualToken(SyntaxKind.GroupKeyword)
    parseExpressionCore()
    eatContextualToken(SyntaxKind.ByKeyword)
    parseExpressionCore()
    return m.done(SyntaxKind.GroupClause)
}

/** `ParseQueryContinuation` (LP 14414) */
private fun LanguageParser.parseQueryContinuation(): Node {
    val m = open()
    eatContextualToken(SyntaxKind.IntoKeyword)
    parseIdentifierToken()
    // `into` chains recurse through ParseQueryBody (Roslyn: StackGuard); past the depth guard the body is given up:
    // an empty QueryBody with an error, the rest of the chain is left to the caller's recovery.
    recursionDepth++
    try {
        if (recursionDepth > SyntaxParser.MAX_DEPTH) {
            val body = open()
            createMissingIdentifierName(CSharpErrorCode.ERR_InsufficientStack.describe())
            body.done(SyntaxKind.QueryBody)
        } else {
            parseQueryBody()
        }
    } finally {
        recursionDepth--
    }
    return m.done(SyntaxKind.QueryContinuation)
}
