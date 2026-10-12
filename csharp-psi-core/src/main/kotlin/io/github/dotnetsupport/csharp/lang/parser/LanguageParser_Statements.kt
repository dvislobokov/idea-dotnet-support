// Ported from Roslyn: src/Compilers/CSharp/Portable/Parser/LanguageParser.cs, statements (ParseStatement 8245,
// ParseStatementAttributeDeclarations 8258, ParseStatementCore 8346, ParseStatementCoreRest 8441,
// TryParseStatementStartingWithIdentifier 8491, ParseStatementStartingWithUsing 8526, TryParseStatementStartingWithUnsafe
// 8530, IsPossibleLocalDeclarationStatement 8568, IsPossibleFirstTypedIdentifierInLocalDeclarationStatement 8615,
// IsPossibleTypedIdentifierStart 9073, ParsePossiblyAttributedBlock 9114, ParseBlock 9162, ParseStatements 9205,
// IsPossibleStatement 9297, ParseFixedStatement 9328 .. ParseLabeledStatement 10530 (every statement kind),
// ParseExpressionOrDeclaration 9889, IsPossibleDeclarationExpression 9896, IsVarType 9972, ParseLocalDeclarationStatement
// 10546, ParseDesignation 10705, ParseWhenClause 10768, ParseParenthesizedVariableDeclaration 10786, ParseLocalDeclaration
// 10808, ParseLocalDeclarationStatementModifiers 10864, ParseVariableDeclarators 5336, ParseVariableDeclarator 5527,
// ParseExpressionStatement 11101, ShouldContextualKeywordBeTreatedAsModifier 1546, IsTypeDeclarationStart 2483),
// roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c. Roslyn: Copyright (c) .NET Foundation and Contributors, MIT
// License (NOTICE.md). Local function bodies: LanguageParser_Members.kt. Departures: docs/csharp-psi/GRAMMAR.md, "Statements and
// queries".
package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpDiagnosticAnchor
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpErrorCode

// ---- entry ---------------------------------------------------------------------------------------------------------

/** `ParseStatement()` (LP 8245): the public entry (`SyntaxFactory.ParseStatement`). */
fun LanguageParser.parseStatement(): Node = parsePossiblyAttributedStatement() ?: parseExpressionStatement(open())

/** `ParsePossiblyAttributedStatement` (LP 8255) */
fun LanguageParser.parsePossiblyAttributedStatement(statementTerm: Int = 0): Node? {
    // The reset point is before the attributes: a reset (not a statement, or an `await` retry) also undoes the marker.
    val resetPointBeforeStatement = getResetPoint()
    val outerTerm = termState
    try {
        val m = open()
        val attributes = parseStatementAttributeDeclarations()
        // Roslyn parses the attributes of a top-level statement once, before `_termState |= IsPossibleStatementStartOrStop`
        // (`ParseMemberDeclarationOrStatementCore`, LP 2619 and 2781): [statementTerm] applies to the statement only,
        // also after the `await` retry of `ParseStatementCoreRest`, which re-parses the attributes here.
        termState = outerTerm or statementTerm
        return parseStatementCore(m, resetPointBeforeStatement, hadAttributes = attributes.count > 0, statementTerm)
    } finally {
        resetPointBeforeStatement.release()
        if (statementTerm != 0) termState = outerTerm
    }
}

/** `ParseStatementAttributeDeclarations` (LP 8258): `[...]` is a collection expression unless it looks like attributes. */
internal fun LanguageParser.parseStatementAttributeDeclarations(): AttributeLists {
    if (currentKind !== SyntaxKind.OpenBracketToken) return AttributeLists(0, false)
    val isCollectionExpression = speculate {
        parseCollectionExpression()
        var hadBracketArgumentList = false
        while (currentKind === SyntaxKind.OpenBracketToken) {
            parseBracketedArgumentList()
            hadBracketArgumentList = true
        }
        var isCollection = when (currentKind) {
            SyntaxKind.DotToken, SyntaxKind.QuestionToken, SyntaxKind.ExclamationToken, SyntaxKind.PlusPlusToken,
            SyntaxKind.MinusMinusToken, SyntaxKind.MinusGreaterThanToken,
            -> true
            else -> false
        }
        isCollection = isCollection || LanguageParser.isExpectedBinaryOperator(currentKind) || LanguageParser.isExpectedAssignmentOperator(currentKind) ||
            ((currentContextualKind === SyntaxKind.SwitchKeyword || currentContextualKind === SyntaxKind.WithKeyword) && peekToken(1).kind === SyntaxKind.OpenBraceToken)
        if (!isCollection && hadBracketArgumentList && currentKind === SyntaxKind.OpenParenToken) {
            val returnType = parseReturnType()
            isCollection = returnType.containsErrorDiagnostic || !isTrueIdentifier()
        }
        isCollection
    }
    return if (isCollectionExpression) AttributeLists(0, false) else parseAttributeDeclarations(inExpressionContext = true)
}

/** `ParseStatementCore(attributes, isGlobal: false)` (LP 8346); [m] is open before the attributes. */
private fun LanguageParser.parseStatementCore(m: Open, resetPointBeforeStatement: SyntaxParser.ResetPoint, hadAttributes: Boolean, statementTerm: Int): Node? {
    recursionDepth++
    try {
        if (recursionDepth > SyntaxParser.MAX_DEPTH) {
            // Give up on the statement: skip to its `;`, but not past the `}` of an enclosing block.
            val skipped = skipBalancedTokens(
                setOf(SyntaxKind.SemicolonToken), includeStop = true, builder.mark(),
                CSharpErrorCode.ERR_InsufficientStack.describe(), stopAtUnbalancedClose = true,
            )
            if (skipped == 0) createMissingToken(SyntaxKind.SemicolonToken, message = CSharpErrorCode.ERR_InsufficientStack.describe())
            return m.done(SyntaxKind.EmptyStatement)
        }
        when (currentKind) {
            SyntaxKind.FixedKeyword -> return parseFixedStatement(m)
            SyntaxKind.BreakKeyword -> return parseBreakOrContinueStatement(m, SyntaxKind.BreakStatement)
            SyntaxKind.ContinueKeyword -> return parseBreakOrContinueStatement(m, SyntaxKind.ContinueStatement)
            SyntaxKind.TryKeyword, SyntaxKind.CatchKeyword, SyntaxKind.FinallyKeyword -> return parseTryStatement(m)
            SyntaxKind.CheckedKeyword, SyntaxKind.UncheckedKeyword -> return parseCheckedStatement(m)
            SyntaxKind.DoKeyword -> return parseDoStatement(m)
            SyntaxKind.ForKeyword -> return parseForOrForEachStatement(m)
            SyntaxKind.ForEachKeyword -> return parseForEachStatement(m)
            SyntaxKind.GotoKeyword -> return parseGotoStatement(m)
            SyntaxKind.IfKeyword -> return parseIfStatement(m)
            SyntaxKind.ElseKeyword -> return parseMisplacedElse(m) // `else` without `if`
            SyntaxKind.LockKeyword -> return parseLockStatement(m)
            SyntaxKind.ReturnKeyword -> return parseReturnStatement(m)
            SyntaxKind.SwitchKeyword, SyntaxKind.CaseKeyword -> return parseSwitchStatement(m) // `case`: error recovery
            SyntaxKind.ThrowKeyword -> return parseThrowStatement(m)
            SyntaxKind.UnsafeKeyword -> when (peekToken(1).kind) { // TryParseStatementStartingWithUnsafe (LP 8530)
                SyntaxKind.OpenParenToken -> return parseExpressionStatementOrLocalFunctionStartingWithUnsafe(m, hadAttributes)
                SyntaxKind.OpenBraceToken -> return parseUnsafeStatement(m)
                else -> {}
            }
            SyntaxKind.UsingKeyword -> { // ParseStatementStartingWithUsing (LP 8526)
                if (peekToken(1).kind === SyntaxKind.OpenParenToken) return parseUsingStatement(m)
                // Never null: a using declaration is not a local function (`canParseAsLocalFunction` false, LP 10549).
                return parseLocalDeclarationStatement(m, hadAttributes)!!
            }
            SyntaxKind.WhileKeyword -> return parseWhileStatement(m)
            SyntaxKind.OpenBraceToken -> return parseBlock(m)
            SyntaxKind.SemicolonToken -> {
                eatToken()
                return m.done(SyntaxKind.EmptyStatement)
            }
            SyntaxKind.IdentifierToken -> {
                tryParseStatementStartingWithIdentifier(m)?.let { return it }
            }
        }
        return parseStatementCoreRest(m, resetPointBeforeStatement, hadAttributes, statementTerm)
    } finally {
        recursionDepth--
    }
}

/** `ParseStatementCoreRest` (LP 8441) */
private fun LanguageParser.parseStatementCoreRest(m: Open, resetPointBeforeStatement: SyntaxParser.ResetPoint, hadAttributes: Boolean, statementTerm: Int): Node? {
    if (!isPossibleLocalDeclarationStatement()) return parseExpressionStatement(m)
    val beginsWithAwait = currentContextualKind === SyntaxKind.AwaitKeyword
    val result = parseLocalDeclarationStatement(m, hadAttributes)
    if (result == null) {
        // An unattributed declaration starting with an accessibility modifier: not a statement (a missing `}`).
        resetPointBeforeStatement.reset()
        return null
    }
    if (result.containsErrors && beginsWithAwait && !isInAsync) {
        resetPointBeforeStatement.reset()
        val m2 = open()
        parseStatementAttributeDeclarations()
        termState = termState or statementTerm
        val savedAsync = isInAsync
        isInAsync = true
        try {
            return parseExpressionStatement(m2)
        } finally {
            isInAsync = savedAsync
        }
    }
    return result
}

/** `TryParseStatementStartingWithIdentifier` (LP 8491) */
private fun LanguageParser.tryParseStatementStartingWithIdentifier(m: Open): Node? {
    if (currentContextualKind === SyntaxKind.AwaitKeyword && peekToken(1).kind === SyntaxKind.ForEachKeyword) {
        eatContextualToken(SyntaxKind.AwaitKeyword)
        return parseForEachStatement(m)
    }
    if (isPossibleAwaitUsing()) {
        if (peekToken(2).kind === SyntaxKind.OpenParenToken) {
            // `await using Type ...` is a local declaration (ParseLocalDeclarationStatement)
            eatContextualToken(SyntaxKind.AwaitKeyword)
            return parseUsingStatement(m)
        }
        return null
    }
    if (isPossibleLabeledStatement()) return parseLabeledStatement(m)
    if (isPossibleYieldStatement()) return parseYieldStatement(m)
    if (isPossibleAwaitExpressionStatement()) return parseExpressionStatement(m)
    if (isQueryExpression(mayBeVariableDeclaration = true, mayBeMemberDeclaration = false)) {
        // `ParseExpressionStatement(attributes, ParseQueryExpression(0))`: the query is not continued as an expression.
        val query = parseQueryExpression(Precedence.Expression)
        eatToken(SyntaxKind.SemicolonToken)
        return m.done(SyntaxKind.ExpressionStatement).also { it.inner = query }
    }
    return null
}

/** `ParseExpressionStatementOrLocalFunctionStartingWithUnsafe` (LP 8541): `unsafe (` is an expression unless a local function follows. */
private fun LanguageParser.parseExpressionStatementOrLocalFunctionStartingWithUnsafe(m: Open, hadAttributes: Boolean): Node {
    // A PsiBuilder rollback cannot keep the result: decide speculatively, then parse for real. The decision is memoised
    // by position ([LanguageParser.unsafeLocalFunctionDecisions]): the real parse of an outer local function takes the
    // inner `unsafe (` statements' decisions from the memo instead of speculating again (2^depth, docs/csharp-psi/GRAMMAR.md).
    val key = decisionKey(hadAttributes)
    val isLocalFunction = unsafeLocalFunctionDecisions[key]
        ?: speculate { parseLocalDeclarationStatement(open(), hadAttributes)?.kind === SyntaxKind.LocalFunctionStatement }
            .also { unsafeLocalFunctionDecisions[key] = it }
    return if (isLocalFunction) parseLocalDeclarationStatement(m, hadAttributes)!! else parseExpressionStatement(m)
}

/** `IsPossibleAwaitUsing` (LP 8552) */
private fun LanguageParser.isPossibleAwaitUsing(): Boolean = currentContextualKind === SyntaxKind.AwaitKeyword && peekToken(1).kind === SyntaxKind.UsingKeyword

/** `IsPossibleLabeledStatement` (LP 8557) */
private fun LanguageParser.isPossibleLabeledStatement(): Boolean = peekToken(1).kind === SyntaxKind.ColonToken && isTrueIdentifier()

/** `IsPossibleYieldStatement` (LP 8562) */
private fun LanguageParser.isPossibleYieldStatement(): Boolean =
    currentContextualKind === SyntaxKind.YieldKeyword && (peekToken(1).kind === SyntaxKind.ReturnKeyword || peekToken(1).kind === SyntaxKind.BreakKeyword)

/** `IsPossibleLocalDeclarationStatement(isGlobalScriptLevel: false)` (LP 8568) */
private fun LanguageParser.isPossibleLocalDeclarationStatement(): Boolean {
    var tk = currentKind
    if (tk === SyntaxKind.RefKeyword || isDeclarationModifier(tk) ||
        (CSharpSyntaxFacts.isPredefinedType(tk) && peekToken(1).kind !== SyntaxKind.DotToken && peekToken(1).kind !== SyntaxKind.OpenParenToken)
    ) return true
    if (tk === SyntaxKind.UsingKeyword) return true
    if (isPossibleAwaitUsing()) return true
    if (isDefiniteScopedModifier(isFunctionPointerParameter = false, isLambdaParameter = false)) return true
    tk = currentContextualKind
    val isPossibleModifier = isAdditionalLocalFunctionModifier(tk) &&
        ((tk !== SyntaxKind.AsyncKeyword && tk !== SyntaxKind.SafeKeyword && tk !== SyntaxKind.ScopedKeyword) || shouldContextualKeywordBeTreatedAsModifier())
    if (isPossibleModifier) return true
    return isPossibleFirstTypedIdentifierInLocalDeclarationStatement()
}

/** `IsPossibleFirstTypedIdentifierInLocalDeclarationStatement(isGlobalScriptLevel: false)` (LP 8615) */
internal fun LanguageParser.isPossibleFirstTypedIdentifierInLocalDeclarationStatement(): Boolean {
    isPossibleTypedIdentifierStart(currentToken, peekToken(1), allowThisKeyword = false)?.let { return it }
    if (currentContextualKind === SyntaxKind.IdentifierToken) {
        val token1 = peekToken(1)
        if (token1.kind === SyntaxKind.DotToken && hasTrailingNewline(token1)) {
            if (peekToken(2).kind === SyntaxKind.IdentifierToken && peekToken(3).kind === SyntaxKind.IdentifierToken) {
                val token4Kind = peekToken(4).kind
                if (token4Kind !== SyntaxKind.SemicolonToken && token4Kind !== SyntaxKind.EqualsToken && token4Kind !== SyntaxKind.CommaToken &&
                    token4Kind !== SyntaxKind.OpenParenToken && token4Kind !== SyntaxKind.LessThanToken
                ) return false
            }
        }
    }
    return speculate {
        val st = scanType()
        when {
            st == ScanTypeFlags.MustBeType && currentKind !== SyntaxKind.DotToken && currentKind !== SyntaxKind.OpenParenToken -> true
            st == ScanTypeFlags.NotType -> false
            currentKind !== SyntaxKind.IdentifierToken ->
                st == ScanTypeFlags.GenericTypeOrExpression && (isDefiniteStatement() || isTypeDeclarationStart() || isAccessibilityModifier(currentKind))
            else -> true
        }
    }
}

/** `IsPossibleTypedIdentifierStart` (LP 9073) */
internal fun LanguageParser.isPossibleTypedIdentifierStart(current: Tok, next: Tok, allowThisKeyword: Boolean): Boolean? {
    if (!isTrueIdentifier(current)) return null
    return when (next.kind) {
        SyntaxKind.DotToken, SyntaxKind.AsteriskToken, SyntaxKind.QuestionToken, SyntaxKind.OpenBracketToken, SyntaxKind.LessThanToken, SyntaxKind.ColonColonToken -> null
        SyntaxKind.OpenParenToken -> if (current.contextualKind === SyntaxKind.VarKeyword) null else false
        SyntaxKind.IdentifierToken -> isTrueIdentifier(next)
        SyntaxKind.ThisKeyword -> allowThisKeyword
        else -> false
    }
}

// ---- blocks ---------------------------------------------------------------------------------------------------------

/** `ParseBlock(attributes)` (LP 9162); the attributes, if any, are inside [m]. */
fun LanguageParser.parseBlock(m: Open = open()): Node {
    eatToken(SyntaxKind.OpenBraceToken)
    parseStatements()
    eatToken(SyntaxKind.CloseBraceToken)
    return m.done(SyntaxKind.Block)
}

/**
 * A body block of a member, accessor, local function (`ParseMethodOrAccessorBodyBlock` LP 9122), lambda (`ParseLambdaBody`
 * LP 13963) or anonymous method (`ParseAnonymousMethodExpression` LP 13795): [parseBlock] whose node gets a
 * [CSharpBodyBlockType] with the parse context it started in, when it can be reparsed alone (docs/csharp-psi/GRAMMAR.md,
 * "Reparseable bodies"). Not reparseable (a plain `Block`): a body inside an interpolation hole (its tokens come from
 * the lexer's queued run), one with a missing `}`, one parsed at the same position in two different contexts during
 * this parse (a decision taken on the other parse could change with the body's text), an empty body with directives
 * (the platform may expand a body with no child markers lazily, and its directives would be lexed without the file's
 * directive context).
 */
fun LanguageParser.parseBodyBlock(errorSensitive: Boolean): Node {
    if (currentKind !== SyntaxKind.OpenBraceToken || interpolationHoleDepth > 0) return parseBlock(open())
    val context = BodyContext.capture(this, errorSensitive)
    val openRaw = position
    val previous = bodyContexts.put(openRaw, context.key)
    if (previous != null && previous != context.key) conflictedBodies += openRaw
    val m = open()
    val ternaryBefore = ternaryCollectionCount
    eatToken(SyntaxKind.OpenBraceToken)
    val emptyWithTrivia = currentKind === SyntaxKind.CloseBraceToken && hasDirectiveTriviaSince(openRaw)
    parseStatements()
    val closeReal = eatToken(SyntaxKind.CloseBraceToken)
    val type = if (!closeReal || emptyWithTrivia || openRaw in conflictedBodies) null
    else CSharpBodyBlockType.forContext(context.withResults(
        hadErrors = errorCount != m.errorsAtStart,
        hadErrorDiagnostic = errorCount - skippedErrorCount != m.errorsAtStart - m.skippedErrorsAtStart,
        hadTernaryCollection = ternaryCollectionCount != ternaryBefore,
    ))
    return m.done(SyntaxKind.Block, type ?: SyntaxKind.Block)
}

/** Directive tokens, disabled text or conflict markers between the raw position [fromRaw] and the current token. */
private fun LanguageParser.hasDirectiveTriviaSince(fromRaw: Int): Boolean {
    var step = fromRaw - position + 1
    while (step < 0) {
        if (CSharpBodyBlockType.DIRECTIVE_LIKE.contains(builder.rawLookup(step))) return true
        step++
    }
    return false
}

/**
 * The contents of a body parsed alone ([CSharpBodyBlockType.doParseContents]): `{`, the statements and `}` of
 * [parseBlock], without the `Block` marker (the body node is the chameleon). Returns the end offset of the real closing
 * `}` (in the builder's text), or -1 when either brace is missing.
 */
internal fun LanguageParser.parseBodyBlockContents(): Int {
    val open = eatToken(SyntaxKind.OpenBraceToken)
    parseStatements()
    val closeEnd = if (currentKind === SyntaxKind.CloseBraceToken) currentToken.end else -1
    val close = eatToken(SyntaxKind.CloseBraceToken)
    return if (open && close) closeEnd else -1
}

/** `ParsePossiblyAttributedBlock` (LP 9114) */
private fun LanguageParser.parsePossiblyAttributedBlock(): Node {
    val m = open()
    parseAttributeDeclarations(inExpressionContext = false)
    return parseBlock(m)
}

/** `ParseStatements(stopOnSwitchSections)` (LP 9205); skipped tokens become error elements between the statements. */
private fun LanguageParser.parseStatements(stopOnSwitchSections: Boolean = false) {
    val saveTerm = termState
    termState = termState or TerminatorState.IsPossibleStatementStartOrStop
    if (stopOnSwitchSections) termState = termState or TerminatorState.IsSwitchSectionStart
    val last = intArrayOf(-1)
    while (currentKind !== SyntaxKind.CloseBraceToken && !currentToken.isEof &&
        !(stopOnSwitchSections && isPossibleSwitchSection()) && isMakingProgress(last)
    ) {
        if (isPossibleStatement()) {
            val statement = parsePossiblyAttributedStatement()
            if (statement != null) continue
        }
        if (skipBadStatementListTokens() == PostSkipAction.Abort) break
    }
    termState = saveTerm
}

/** `IsPossibleStatementStartOrStop` (LP 9245) */
fun LanguageParser.isPossibleStatementStartOrStop(): Boolean = currentKind === SyntaxKind.SemicolonToken || isPossibleStatement()

/** `SkipBadStatementListTokens` (LP 9251) */
private fun LanguageParser.skipBadStatementListTokens(): PostSkipAction =
    skipBadSeparatedListTokensWithExpectedKind(
        isNotExpected = { !isPossibleStatement() },
        abort = { currentKind === SyntaxKind.CloseBraceToken },
        SyntaxKind.CloseBraceToken, SyntaxKind.CloseBraceToken,
    )

/** `IsDefiniteStatement` (LP 9265) */
fun LanguageParser.isDefiniteStatement(): Boolean = when (currentKind) {
    SyntaxKind.FixedKeyword, SyntaxKind.BreakKeyword, SyntaxKind.ContinueKeyword, SyntaxKind.TryKeyword, SyntaxKind.ConstKeyword,
    SyntaxKind.DoKeyword, SyntaxKind.ForKeyword, SyntaxKind.ForEachKeyword, SyntaxKind.GotoKeyword, SyntaxKind.IfKeyword,
    SyntaxKind.ElseKeyword, SyntaxKind.LockKeyword, SyntaxKind.ReturnKeyword, SyntaxKind.UnsafeKeyword, SyntaxKind.UsingKeyword,
    SyntaxKind.WhileKeyword, SyntaxKind.VolatileKeyword, SyntaxKind.ExternKeyword, SyntaxKind.CaseKeyword,
    -> true
    else -> false
}

/** `IsPossibleStatement` (LP 9297) */
fun LanguageParser.isPossibleStatement(): Boolean {
    if (isDefiniteStatement()) return true
    return when (val tk = currentKind) {
        SyntaxKind.CheckedKeyword, SyntaxKind.UncheckedKeyword, SyntaxKind.ThrowKeyword, SyntaxKind.SwitchKeyword, SyntaxKind.OpenBraceToken,
        SyntaxKind.SemicolonToken, SyntaxKind.StaticKeyword, SyntaxKind.ReadOnlyKeyword, SyntaxKind.RefKeyword, SyntaxKind.OpenBracketToken,
        -> true
        SyntaxKind.IdentifierToken -> isTrueIdentifier()
        else -> CSharpSyntaxFacts.isPredefinedType(tk) || isPossibleExpression()
    }
}

// ---- declaration expressions -------------------------------------------------------------------------------------

/** `ParseExpressionOrDeclaration(mode, permitTupleDesignation)` (LP 9889) */
fun LanguageParser.parseExpressionOrDeclaration(mode: ParseTypeMode, permitTupleDesignation: Boolean): Node {
    val isScoped = BooleanArray(1)
    return if (isPossibleDeclarationExpression(mode, permitTupleDesignation, isScoped)) parseDeclarationExpression(mode, isScoped[0])
    else parseSubExpression(Precedence.Expression)
}

/** `IsPossibleDeclarationExpression(mode, permitTupleDesignation, out isScoped)` (LP 9896) */
private fun LanguageParser.isPossibleDeclarationExpression(mode: ParseTypeMode, permitTupleDesignation: Boolean, isScoped: BooleanArray): Boolean {
    isScoped[0] = false
    if (isInAsync && currentContextualKind === SyntaxKind.AwaitKeyword) return false
    return speculate {
        var decided: Boolean? = null
        if (currentContextualKind === SyntaxKind.ScopedKeyword) {
            withResetPoint { rp ->
                eatToken()
                if (scanType() != ScanTypeFlags.NotType && currentKind === SyntaxKind.IdentifierToken) {
                    val ok = when (mode) {
                        ParseTypeMode.FirstElementOfPossibleTupleLiteral -> peekToken(1).kind === SyntaxKind.CommaToken
                        ParseTypeMode.AfterTupleComma -> peekToken(1).kind === SyntaxKind.CommaToken || peekToken(1).kind === SyntaxKind.CloseParenToken
                        else -> true
                    }
                    if (ok) {
                        isScoped[0] = true
                        decided = true
                    }
                }
                if (decided == null) rp.reset()
            }
        }
        if (decided != null) {
            decided!!
        } else {
            val typeIsVar = isVarType()
            if (scanType(mode) == ScanTypeFlags.NotType) {
                false
            } else {
                val lastKind = scanTypeLastTokenKind
                if (!scanDesignation(permitTupleDesignation && (typeIsVar || CSharpSyntaxFacts.isPredefinedType(lastKind)))) {
                    false
                } else {
                    when (mode) {
                        ParseTypeMode.FirstElementOfPossibleTupleLiteral -> currentKind === SyntaxKind.CommaToken
                        ParseTypeMode.AfterTupleComma -> currentKind === SyntaxKind.CommaToken || currentKind === SyntaxKind.CloseParenToken
                        else -> true
                    }
                }
            }
        }
    }
}

/** `IsVarType` (LP 9972) */
fun LanguageParser.isVarType(): Boolean {
    if (!isIdentifierVar()) return false
    return when (peekToken(1).kind) {
        SyntaxKind.DotToken, SyntaxKind.ColonColonToken, SyntaxKind.OpenBracketToken, SyntaxKind.AsteriskToken, SyntaxKind.QuestionToken, SyntaxKind.LessThanToken -> false
        else -> true
    }
}

// ---- simple statements -------------------------------------------------------------------------------------------

/** `ParseReturnStatement` (LP 10177) */
private fun LanguageParser.parseReturnStatement(m: Open): Node {
    eatToken(SyntaxKind.ReturnKeyword)
    if (currentKind !== SyntaxKind.SemicolonToken) parsePossibleRefExpression()
    eatToken(SyntaxKind.SemicolonToken)
    return m.done(SyntaxKind.ReturnStatement)
}

/** `ParseThrowStatement` (LP 10372) */
private fun LanguageParser.parseThrowStatement(m: Open): Node {
    eatToken(SyntaxKind.ThrowKeyword)
    if (currentKind !== SyntaxKind.SemicolonToken) parseExpressionCore()
    eatToken(SyntaxKind.SemicolonToken)
    return m.done(SyntaxKind.ThrowStatement)
}

/** `ParseEmbeddedStatement` (LP 9352): a statement, or an empty statement with a missing `;` when there is none. */
private fun LanguageParser.parseEmbeddedStatement(): Node {
    parsePossiblyAttributedStatement()?.let { return it }
    val m = open()
    eatToken(SyntaxKind.SemicolonToken)
    return m.done(SyntaxKind.EmptyStatement)
}

/** `ParseFixedStatement` (LP 9328) */
private fun LanguageParser.parseFixedStatement(m: Open): Node {
    eatToken(SyntaxKind.FixedKeyword)
    eatToken(SyntaxKind.OpenParenToken)
    val saveTerm = termState
    termState = termState or TerminatorState.IsEndOfFixedStatement
    parseParenthesizedVariableDeclaration(allowScoped = false, forStatement = false)
    termState = saveTerm
    eatToken(SyntaxKind.CloseParenToken)
    parseEmbeddedStatement()
    return m.done(SyntaxKind.FixedStatement)
}

/** `IsEndOfFixedStatement` (LP 9347) */
fun LanguageParser.isEndOfFixedStatement(): Boolean =
    currentKind === SyntaxKind.CloseParenToken || currentKind === SyntaxKind.OpenBraceToken || currentKind === SyntaxKind.SemicolonToken

/** `ParseBreakStatement` (LP 9394), `ParseContinueStatement` (LP 9403) */
private fun LanguageParser.parseBreakOrContinueStatement(m: Open, kind: IElementType): Node {
    eatToken()
    if (isTrueIdentifier()) parseIdentifierName()
    eatToken(SyntaxKind.SemicolonToken)
    return m.done(kind)
}

/**
 * `ParseTryStatement` (LP 9412); also entered on a misplaced `catch`/`finally`, where the `try` is missing and the
 * block is a fully missing one. Without `catch` and `finally` a missing `finally { }` is synthesized.
 */
private fun LanguageParser.parseTryStatement(m: Open): Node {
    val tryBlockContainsErrors: Boolean
    if (!eatToken(SyntaxKind.TryKeyword)) {
        missingBlock()
        tryBlockContainsErrors = true
    } else {
        val saveTerm = termState
        termState = termState or TerminatorState.IsEndOfTryBlock
        tryBlockContainsErrors = parsePossiblyAttributedBlock().containsErrorDiagnostic
        termState = saveTerm
    }
    var hasCatch = false
    while (currentKind === SyntaxKind.CatchKeyword) {
        parseCatchClause()
        hasCatch = true
    }
    var hasFinally = false
    if (currentKind === SyntaxKind.FinallyKeyword) {
        val f = open()
        eatToken()
        parsePossiblyAttributedBlock()
        f.done(SyntaxKind.FinallyClause)
        hasFinally = true
    }
    if (!hasCatch && !hasFinally) {
        if (!tryBlockContainsErrors) errorCount++ // ERR_ExpectedEndTry on the last token of the block
        val f = open()
        createMissingToken(SyntaxKind.FinallyKeyword, report = false)
        missingBlock()
        f.done(SyntaxKind.FinallyClause)
    }
    return m.done(SyntaxKind.TryStatement)
}

/** local `missingBlock` of `ParseTryStatement` (LP 9475): `{` and `}` missing, no diagnostic. */
private fun LanguageParser.missingBlock(): Node {
    val b = open()
    createMissingToken(SyntaxKind.OpenBraceToken, report = false)
    createMissingToken(SyntaxKind.CloseBraceToken, report = false)
    return b.done(SyntaxKind.Block)
}

/** `IsEndOfTryBlock` (LP 9483) */
fun LanguageParser.isEndOfTryBlock(): Boolean =
    currentKind === SyntaxKind.CloseBraceToken || currentKind === SyntaxKind.CatchKeyword || currentKind === SyntaxKind.FinallyKeyword

/** `ParseCatchClause` (LP 9488) */
private fun LanguageParser.parseCatchClause(): Node {
    val m = open()
    eatToken() // catch
    val saveTerm = termState
    if (currentKind === SyntaxKind.OpenParenToken) {
        val decl = open()
        eatToken()
        termState = termState or TerminatorState.IsEndOfCatchClause
        parseType()
        if (isTrueIdentifier()) parseIdentifierToken()
        termState = saveTerm
        eatToken(SyntaxKind.CloseParenToken)
        decl.done(SyntaxKind.CatchDeclaration)
    }
    val keywordKind = currentContextualKind
    if (keywordKind === SyntaxKind.WhenKeyword || keywordKind === SyntaxKind.IfKeyword) {
        val filter = open()
        if (keywordKind === SyntaxKind.IfKeyword) {
            // The early `if` filter syntax: a missing `when` with the `if` as its skipped trailing syntax.
            createMissingToken(SyntaxKind.WhenKeyword)
            skipTokens(1, countError = false)
        } else {
            eatContextualToken(SyntaxKind.WhenKeyword)
        }
        termState = termState or TerminatorState.IsEndOfFilterClause
        eatToken(SyntaxKind.OpenParenToken)
        parseExpressionForParenthesizedConstruct()
        termState = saveTerm
        eatToken(SyntaxKind.CloseParenToken)
        filter.done(SyntaxKind.CatchFilterClause)
    }
    termState = termState or TerminatorState.IsEndOfCatchBlock
    parsePossiblyAttributedBlock()
    termState = saveTerm
    return m.done(SyntaxKind.CatchClause)
}

/** `IsEndOfCatchClause` (LP 9547) */
fun LanguageParser.isEndOfCatchClause(): Boolean = when (currentKind) {
    SyntaxKind.CloseParenToken, SyntaxKind.OpenBraceToken, SyntaxKind.CloseBraceToken, SyntaxKind.CatchKeyword, SyntaxKind.FinallyKeyword -> true
    else -> false
}

/** `IsEndOfFilterClause` (LP 9556) */
fun LanguageParser.isEndOfFilterClause(): Boolean = isEndOfCatchClause()

/** `IsEndOfCatchBlock` (LP 9564) */
fun LanguageParser.isEndOfCatchBlock(): Boolean =
    currentKind === SyntaxKind.CloseBraceToken || currentKind === SyntaxKind.CatchKeyword || currentKind === SyntaxKind.FinallyKeyword

/** `ParseCheckedStatement` (LP 9571): `checked(...)` is an expression statement. */
private fun LanguageParser.parseCheckedStatement(m: Open): Node {
    if (peekToken(1).kind === SyntaxKind.OpenParenToken) return parseExpressionStatement(m)
    val kind = CSharpSyntaxFacts.getCheckStatement(currentKind)!!
    eatToken()
    parsePossiblyAttributedBlock()
    return m.done(kind)
}

/** `ParseDoStatement` (LP 9588) */
private fun LanguageParser.parseDoStatement(m: Open): Node {
    eatToken(SyntaxKind.DoKeyword)
    parseEmbeddedStatement()
    eatToken(SyntaxKind.WhileKeyword)
    eatToken(SyntaxKind.OpenParenToken)
    val saveTerm = termState
    termState = termState or TerminatorState.IsEndOfDoWhileExpression
    parseExpressionForParenthesizedConstruct()
    termState = saveTerm
    eatToken(SyntaxKind.CloseParenToken)
    eatToken(SyntaxKind.SemicolonToken)
    return m.done(SyntaxKind.DoStatement)
}

/** `IsEndOfDoWhileExpression` (LP 9612) */
fun LanguageParser.isEndOfDoWhileExpression(): Boolean = currentKind === SyntaxKind.CloseParenToken || currentKind === SyntaxKind.SemicolonToken

/** The kind of the current token, then eats it (`this.EatToken().Kind`). */
private fun LanguageParser.eatTokenKind(): IElementType {
    val kind = currentKind
    eatToken()
    return kind
}

/** `ParseForOrForEachStatement` (LP 9617): `for (T x in` is parsed as a `foreach` with a missing keyword. */
private fun LanguageParser.parseForOrForEachStatement(m: Open): Node {
    val isForEach = speculate {
        eatToken() // for
        eatTokenKind() === SyntaxKind.OpenParenToken &&
            scanType() != ScanTypeFlags.NotType &&
            eatTokenKind() === SyntaxKind.IdentifierToken &&
            eatTokenKind() === SyntaxKind.InKeyword
    }
    return if (isForEach) parseForEachStatement(m) else parseForStatement(m)
}

/** `ParseForStatement` (LP 9650); the embedded statement is parsed with `IsEndOfForStatementArgument` still set, as in Roslyn. */
private fun LanguageParser.parseForStatement(m: Open): Node {
    val saveTerm = termState
    termState = termState or TerminatorState.IsEndOfForStatementArgument
    eatToken(SyntaxKind.ForKeyword)
    eatToken(SyntaxKind.OpenParenToken)
    eatVariableDeclarationOrInitializers()
    eatCommaOrSemicolon()
    if (currentKind !== SyntaxKind.SemicolonToken && currentKind !== SyntaxKind.CommaToken) parseExpressionCore()
    eatCommaOrSemicolon()
    if (currentKind !== SyntaxKind.CloseParenToken) parseForStatementExpressionList(allowSemicolonAsSeparator = true)
    eatUnexpectedTokensAndCloseParenToken()
    parseEmbeddedStatement()
    val result = m.done(SyntaxKind.ForStatement)
    termState = saveTerm
    return result
}

/** local `eatVariableDeclarationOrInitializers` of `ParseForStatement` (LP 9692) */
private fun LanguageParser.eatVariableDeclarationOrInitializers() {
    var isDeclaration = false
    withResetPoint { rp ->
        if (currentContextualKind === SyntaxKind.ScopedKeyword) {
            if (peekToken(1).kind === SyntaxKind.RefKeyword) {
                isDeclaration = true
            } else {
                eatToken()
                isDeclaration = scanType() != ScanTypeFlags.NotType && currentKind === SyntaxKind.IdentifierToken
                rp.reset()
            }
        } else if (currentKind === SyntaxKind.RefKeyword) {
            isDeclaration = true
        }
        if (!isDeclaration) {
            isDeclaration = !isQueryExpression(mayBeVariableDeclaration = true, mayBeMemberDeclaration = false) &&
                scanType() != ScanTypeFlags.NotType && isTrueIdentifier()
            rp.reset()
        }
    }
    if (isDeclaration) {
        parseParenthesizedVariableDeclaration(allowScoped = true, forStatement = true)
    } else if (currentKind !== SyntaxKind.SemicolonToken) {
        parseForStatementExpressionList(allowSemicolonAsSeparator = false)
    }
}

/** local `eatCommaOrSemicolon` of `ParseForStatement` (LP 9744) */
private fun LanguageParser.eatCommaOrSemicolon() {
    if (currentKind === SyntaxKind.CommaToken) eatTokenAsKind(SyntaxKind.SemicolonToken) else eatToken(SyntaxKind.SemicolonToken)
}

/** local `eatUnexpectedTokensAndCloseParenToken` of `ParseForStatement` (LP 9749): stray `;`/`,` skipped before `)`. */
private fun LanguageParser.eatUnexpectedTokensAndCloseParenToken() {
    if (currentKind === SyntaxKind.SemicolonToken || currentKind === SyntaxKind.CommaToken) {
        val skipped = builder.mark()
        while (currentKind === SyntaxKind.SemicolonToken || currentKind === SyntaxKind.CommaToken) {
            advance()
            errorCount++ // `EatTokenEvenWithIncorrectKind(CloseParenToken)` on each
        }
        skipped.error(SyntaxParser.expectedMessage(SyntaxKind.CloseParenToken, null, CSharpDiagnosticAnchor.EACH_EXPECTED))
    }
    eatToken(SyntaxKind.CloseParenToken)
}

/** local `parseForStatementExpressionList` of `ParseForStatement` (LP 9763) */
private fun LanguageParser.parseForStatementExpressionList(allowSemicolonAsSeparator: Boolean) {
    parseCommaSeparatedSyntaxList(
        SyntaxKind.CloseParenToken,
        isPossibleElement = { isPossibleExpression() },
        parseElement = { parseExpressionCore() },
        skipBadTokens = { expected, close -> skipBadForStatementExpressionListTokens(expected, close) },
        allowTrailingSeparator = false,
        requireOneElement = false,
        allowSemicolonAsSeparator = allowSemicolonAsSeparator,
    )
}

/** local `skipBadForStatementExpressionListTokens` of `ParseForStatement` (LP 9774) */
private fun LanguageParser.skipBadForStatementExpressionListTokens(expected: IElementType, closeKind: IElementType): PostSkipAction {
    if (currentKind === SyntaxKind.CloseParenToken || currentKind === SyntaxKind.SemicolonToken) return PostSkipAction.Abort
    return skipBadSeparatedListTokensWithExpectedKind(
        isNotExpected = { currentKind !== SyntaxKind.CommaToken && !isPossibleExpression() },
        abort = { close -> currentKind === close || currentKind === SyntaxKind.SemicolonToken },
        expected, closeKind,
    )
}

/** `IsEndOfForStatementArgument` (LP 9787) */
fun LanguageParser.isEndOfForStatementArgument(): Boolean =
    currentKind === SyntaxKind.SemicolonToken || currentKind === SyntaxKind.CloseParenToken || currentKind === SyntaxKind.OpenBraceToken

/**
 * `ParseForEachStatement(attributes, awaitTokenOpt)` (LP 9792); the `await`, if any, is already eaten into [m]. A
 * declaration whose designation is not parenthesized makes a `ForEachStatement` holding the type and the identifier
 * directly (Roslyn takes the `DeclarationExpression` apart, LP 9831); here the declaration marker is dropped instead.
 * Everything else is a `ForEachVariableStatement`.
 */
private fun LanguageParser.parseForEachStatement(m: Open): Node {
    if (currentKind === SyntaxKind.ForKeyword) {
        // `for (T x in`: a missing `foreach` with the `for` as skipped syntax (ConvertToMissingWithTrailingTrivia).
        eatTokenAsKind(SyntaxKind.ForEachKeyword)
    } else {
        eatToken(SyntaxKind.ForEachKeyword)
    }
    eatToken(SyntaxKind.OpenParenToken)

    // ParseExpressionOrDeclaration(ParseTypeMode.Normal, permitTupleDesignation: true)
    val isScoped = BooleanArray(1)
    var kind = SyntaxKind.ForEachVariableStatement
    if (isPossibleDeclarationExpression(ParseTypeMode.Normal, permitTupleDesignation = true, isScoped)) {
        val declaration = open()
        if (isScoped[0]) {
            val scoped = open()
            eatContextualToken(SyntaxKind.ScopedKeyword)
            parseType(ParseTypeMode.Normal)
            scoped.done(SyntaxKind.ScopedType)
        } else {
            parseType(ParseTypeMode.Normal)
        }
        if (currentKind === SyntaxKind.OpenParenToken) {
            parseDesignation(forPattern = false)
            declaration.done(SyntaxKind.DeclarationExpression)
        } else {
            // SingleVariableDesignation / DiscardDesignation: the identifier (a discard reverted to IdentifierToken).
            declaration.drop()
            if (currentContextualKind === SyntaxKind.UnderscoreToken) eatToken() else eatToken(SyntaxKind.IdentifierToken)
            kind = SyntaxKind.ForEachStatement
        }
    } else {
        val variable = parseSubExpression(Precedence.Expression)
        if (!isValidForeachVariable(variable)) errorCount++ // ERR_BadForeachDecl on the `in`
    }
    eatToken(SyntaxKind.InKeyword)
    parseExpressionCore()
    eatToken(SyntaxKind.CloseParenToken)
    parseEmbeddedStatement()
    return m.done(kind)
}

/** `IsValidForeachVariable` (LP 9993) for a variable that is not a declaration expression. */
private fun isValidForeachVariable(variable: Node): Boolean = when (variable.kind) {
    SyntaxKind.DeclarationExpression, SyntaxKind.TupleExpression -> true
    SyntaxKind.IdentifierName -> variable.identifierContextualKind === SyntaxKind.UnderscoreToken
    else -> false
}

/** `ParseGotoStatement` (LP 10011) */
private fun LanguageParser.parseGotoStatement(m: Open): Node {
    eatToken(SyntaxKind.GotoKeyword)
    val kind = when (currentKind) {
        SyntaxKind.CaseKeyword -> {
            eatToken()
            parseExpressionCore()
            SyntaxKind.GotoCaseStatement
        }
        SyntaxKind.DefaultKeyword -> {
            eatToken()
            SyntaxKind.GotoDefaultStatement
        }
        else -> {
            parseIdentifierName()
            SyntaxKind.GotoStatement
        }
    }
    eatToken(SyntaxKind.SemicolonToken)
    return m.done(kind)
}

/**
 * `ParseIfStatement` (LP 10077). Roslyn parses an `else if` chain iteratively (a stack instead of recursion); here the
 * markers of the chain stay open and are completed innermost first: `If [ ... Else [ If [ ... Else [ ... ] ] ] ]`.
 */
private fun LanguageParser.parseIfStatement(m: Open): Node {
    val ifs = ArrayList<Open>()
    val elses = ArrayList<Open?>()
    var current = m
    while (true) {
        eatToken(SyntaxKind.IfKeyword)
        eatToken(SyntaxKind.OpenParenToken)
        parseExpressionForParenthesizedConstruct()
        eatToken(SyntaxKind.CloseParenToken)
        parseEmbeddedStatement()
        ifs += current
        if (currentKind !== SyntaxKind.ElseKeyword) {
            elses += null
            break
        }
        val elseClause = open()
        eatToken(SyntaxKind.ElseKeyword)
        elses += elseClause
        if (currentKind !== SyntaxKind.IfKeyword) {
            parseEmbeddedStatement()
            break
        }
        current = open()
    }
    var result: Node? = null
    for (i in ifs.indices.reversed()) {
        elses[i]?.done(SyntaxKind.ElseClause)
        result = ifs[i].done(SyntaxKind.IfStatement)
    }
    return result!!
}

/** `ParseMisplacedElse` (LP 10142): `else` without `if` becomes an `if` with missing parts. */
private fun LanguageParser.parseMisplacedElse(m: Open): Node {
    createMissingToken(SyntaxKind.IfKeyword, message = CSharpErrorCode.ERR_ElseCannotStartStatement.describe()) // EatToken(IfKeyword, ERR_ElseCannotStartStatement)
    eatToken(SyntaxKind.OpenParenToken)
    parseExpressionCore()
    eatToken(SyntaxKind.CloseParenToken)
    parseExpressionStatement(open())
    parseElseClauseOpt()
    return m.done(SyntaxKind.IfStatement)
}

/** `ParseElseClauseOpt` (LP 10156) */
private fun LanguageParser.parseElseClauseOpt() {
    if (currentKind !== SyntaxKind.ElseKeyword) return
    val m = open()
    eatToken(SyntaxKind.ElseKeyword)
    parseEmbeddedStatement()
    m.done(SyntaxKind.ElseClause)
}

/** `ParseLockStatement` (LP 10165) */
private fun LanguageParser.parseLockStatement(m: Open): Node {
    eatToken(SyntaxKind.LockKeyword)
    eatToken(SyntaxKind.OpenParenToken)
    parseExpressionForParenthesizedConstruct()
    eatToken(SyntaxKind.CloseParenToken)
    parseEmbeddedStatement()
    return m.done(SyntaxKind.LockStatement)
}

/** `ParseYieldStatement` (LP 10187) */
private fun LanguageParser.parseYieldStatement(m: Open): Node {
    convertToKeyword() // yield
    val kind: IElementType
    if (currentKind === SyntaxKind.BreakKeyword) {
        kind = SyntaxKind.YieldBreakStatement
        eatToken()
    } else {
        kind = SyntaxKind.YieldReturnStatement
        eatToken(SyntaxKind.ReturnKeyword)
        if (currentKind === SyntaxKind.SemicolonToken) errorCount++ // ERR_EmptyYield
        else parseExpressionCore()
    }
    eatToken(SyntaxKind.SemicolonToken)
    return m.done(kind)
}

/** `ParseSwitchStatement` (LP 10224); `case` without `switch` gets a fully missing header. */
private fun LanguageParser.parseSwitchStatement(m: Open): Node {
    parseSwitchHeader()
    while (isPossibleSwitchSection()) parseSwitchSection()
    eatToken(SyntaxKind.CloseBraceToken)
    return m.done(SyntaxKind.SwitchStatement)
}

/**
 * local `parseSwitchHeader` of `ParseSwitchStatement` (LP 10244). A parenthesized governing expression gives its
 * parentheses to the statement (Roslyn takes the `ParenthesizedExpression` apart): the expression is parsed, and when
 * it is a `ParenthesizedExpression` it is reparsed as the inside of the parentheses the way
 * `ParseCastOrParenExpressionOrTuple` parses it (LP 12918-12943).
 */
private fun LanguageParser.parseSwitchHeader() {
    if (currentKind === SyntaxKind.CaseKeyword) {
        eatToken(SyntaxKind.SwitchKeyword)
        createMissingToken(SyntaxKind.OpenParenToken, report = false)
        createMissingIdentifierNameWithoutError()
        createMissingToken(SyntaxKind.CloseParenToken, report = false)
        createMissingToken(SyntaxKind.OpenBraceToken, report = false)
        return
    }
    eatToken(SyntaxKind.SwitchKeyword)
    // Roslyn (LP 10263) strips the parentheses off the parsed node; the port reparses the inside instead. The decision
    // is memoised by position so that nested switch headers are not reparsed again on each outer reparse (O(n^2)).
    val key = decisionKey()
    if (switchHeaderDecisions[key] == SWITCH_HEADER_PARENTHESIZED) {
        parseSwitchHeaderParenthesized()
    } else {
        val rp = getResetPoint()
        try {
            val expression = parseExpressionCore()
            when (expression.kind) {
                SyntaxKind.ParenthesizedExpression -> {
                    switchHeaderDecisions[key] = SWITCH_HEADER_PARENTHESIZED
                    rp.reset()
                    parseSwitchHeaderParenthesized()
                }
                SyntaxKind.TupleExpression -> {} // the statement's own parentheses may be omitted
                else -> errorCount++ // ERR_SwitchGoverningExpressionRequiresParens; `(` and `)` missing without diagnostics
            }
        } finally {
            rp.release()
        }
    }
    eatToken(SyntaxKind.OpenBraceToken)
}

private const val SWITCH_HEADER_PARENTHESIZED = 1

/** The parentheses of `switch (e)` as the statement's own tokens around `e` (LP 10263-10270). */
private fun LanguageParser.parseSwitchHeaderParenthesized() {
    eatToken(SyntaxKind.OpenParenToken)
    val inner = parseExpressionOrDeclaration(ParseTypeMode.FirstElementOfPossibleTupleLiteral, permitTupleDesignation = true)
    parseErrantExpressionWhenNoCloseParenToken(inner)
    eatToken(SyntaxKind.CloseParenToken)
}

/** `IsPossibleSwitchSection` (LP 10294) */
fun LanguageParser.isPossibleSwitchSection(): Boolean =
    currentKind === SyntaxKind.CaseKeyword || (currentKind === SyntaxKind.DefaultKeyword && peekToken(1).kind !== SyntaxKind.OpenParenToken)

/** `ParseSwitchSection` (LP 10300) */
private fun LanguageParser.parseSwitchSection(): Node {
    val section = open()
    do {
        val label = open()
        if (currentKind === SyntaxKind.CaseKeyword) {
            eatToken()
            if (currentKind === SyntaxKind.ColonToken) {
                parseIdentifierName(CSharpErrorCode.ERR_ConstantExpected.describe())
                eatToken(SyntaxKind.ColonToken)
                label.done(SyntaxKind.CaseSwitchLabel)
            } else if (parseExpressionOrPatternForSwitchStatement()) {
                parseWhenClause(Precedence.Expression)
                eatToken(SyntaxKind.ColonToken)
                label.done(SyntaxKind.CasePatternSwitchLabel)
            } else {
                eatToken(SyntaxKind.ColonToken)
                label.done(SyntaxKind.CaseSwitchLabel)
            }
        } else {
            eatToken(SyntaxKind.DefaultKeyword)
            eatToken(SyntaxKind.ColonToken)
            label.done(SyntaxKind.DefaultSwitchLabel)
        }
    } while (isPossibleSwitchSection())
    parseStatements(stopOnSwitchSections = true)
    return section.done(SyntaxKind.SwitchSection)
}

/** `ParseUnsafeStatement` (LP 10382) */
private fun LanguageParser.parseUnsafeStatement(m: Open): Node {
    eatToken(SyntaxKind.UnsafeKeyword)
    parsePossiblyAttributedBlock()
    return m.done(SyntaxKind.UnsafeStatement)
}

/** `ParseUsingStatement(attributes, awaitTokenOpt)` (LP 10391); the `await`, if any, is already eaten into [m]. */
private fun LanguageParser.parseUsingStatement(m: Open): Node {
    eatToken(SyntaxKind.UsingKeyword)
    eatToken(SyntaxKind.OpenParenToken)
    val rp = getResetPoint()
    try {
        parseUsingExpression(rp)
    } finally {
        rp.release()
    }
    eatToken(SyntaxKind.CloseParenToken)
    parseEmbeddedStatement()
    return m.done(SyntaxKind.UsingStatement)
}

/** `ParseUsingExpression` (LP 10414): a declaration or an expression inside `using (`...`)`. */
private fun LanguageParser.parseUsingExpression(resetPoint: SyntaxParser.ResetPoint) {
    if (isAwaitExpression()) {
        parseExpressionCore()
        return
    }
    val st: ScanTypeFlags
    if (isQueryExpression(mayBeVariableDeclaration = true, mayBeMemberDeclaration = false)) {
        st = ScanTypeFlags.NotType
    } else {
        if (isDefiniteScopedModifier(isFunctionPointerParameter = false, isLambdaParameter = false)) {
            parseParenthesizedVariableDeclaration(allowScoped = true, forStatement = false)
            return
        }
        st = scanType()
    }
    if (st == ScanTypeFlags.NullableType) {
        if (currentKind !== SyntaxKind.IdentifierToken) {
            resetPoint.reset()
            parseExpressionCore()
            return
        }
        when (peekToken(1).kind) {
            SyntaxKind.CommaToken, SyntaxKind.CloseParenToken -> {
                resetPoint.reset()
                parseParenthesizedVariableDeclaration(allowScoped = false, forStatement = false)
            }
            SyntaxKind.EqualsToken -> {
                // A declaration; `name? id = expr :` with one declarator is a conditional expression after all.
                resetPoint.reset()
                val count = IntArray(1)
                val type = parseParenthesizedVariableDeclaration(allowScoped = false, forStatement = false, count)
                if (currentKind === SyntaxKind.ColonToken && type.kind === SyntaxKind.NullableType &&
                    CSharpSyntaxFacts.isName(type.inner?.kind) && count[0] == 1
                ) {
                    resetPoint.reset()
                    parseExpressionCore()
                }
            }
            else -> {
                resetPoint.reset()
                parseExpressionCore()
            }
        }
    } else if (isUsingStatementVariableDeclaration(st)) {
        resetPoint.reset()
        parseParenthesizedVariableDeclaration(allowScoped = false, forStatement = false)
    } else {
        resetPoint.reset()
        parseExpressionCore()
    }
}

/** `IsUsingStatementVariableDeclaration` (LP 10507) */
private fun LanguageParser.isUsingStatementVariableDeclaration(st: ScanTypeFlags): Boolean {
    val condition1 = st == ScanTypeFlags.MustBeType && currentKind !== SyntaxKind.DotToken
    val condition2 = st != ScanTypeFlags.NotType && currentKind === SyntaxKind.IdentifierToken
    val condition3 = st == ScanTypeFlags.NonGenericTypeOrExpression || peekToken(1).kind === SyntaxKind.EqualsToken
    return condition1 || (condition2 && condition3)
}

/** `ParseWhileStatement` (LP 10518) */
private fun LanguageParser.parseWhileStatement(m: Open): Node {
    eatToken(SyntaxKind.WhileKeyword)
    eatToken(SyntaxKind.OpenParenToken)
    parseExpressionForParenthesizedConstruct()
    eatToken(SyntaxKind.CloseParenToken)
    parseEmbeddedStatement()
    return m.done(SyntaxKind.WhileStatement)
}

/** `ParseLabeledStatement` (LP 10530) */
private fun LanguageParser.parseLabeledStatement(m: Open): Node {
    parseIdentifierToken()
    eatToken(SyntaxKind.ColonToken)
    if (parsePossiblyAttributedStatement() == null) {
        val empty = open()
        eatToken(SyntaxKind.SemicolonToken)
        empty.done(SyntaxKind.EmptyStatement)
    }
    return m.done(SyntaxKind.LabeledStatement)
}

/**
 * `ParseParenthesizedVariableDeclaration(VariableFlags, scopedKeyword)` (LP 10786) with `ParseLocalDeclaration`
 * (LP 10808) for `fixed (...)`, `for (...)` and `using (...)`: a `VariableDeclaration` that stops at `)`. With
 * [allowScoped] the `scoped` keyword (`ParsePossibleScopedKeyword`, eaten by Roslyn's caller) makes a `ScopedType`.
 * Returns the type node; [declaratorCount] receives the number of declarators.
 */
private fun LanguageParser.parseParenthesizedVariableDeclaration(allowScoped: Boolean, forStatement: Boolean, declaratorCount: IntArray? = null): Node {
    val declaration = open()
    val scoped = open()
    val type: Node
    if (allowScoped && parsePossibleScopedKeyword(isFunctionPointerParameter = false, isLambdaParameter = false)) {
        parseType()
        type = scoped.done(SyntaxKind.ScopedType)
    } else {
        scoped.drop()
        type = parseType()
    }
    val saveTerm = termState
    termState = termState or TerminatorState.IsEndOfDeclarationClause
    parseVariableDeclarators(type, allowLocalFunctions = false, isConst = false, mods = emptyList(), stopOnCloseParen = true, forStatement = forStatement, declaratorCount = declaratorCount)
    termState = saveTerm
    declaration.done(SyntaxKind.VariableDeclaration)
    return type
}

/** `CreateMissingIdentifierName()` (LP 6037): an `IdentifierName` with a missing identifier and no diagnostic. */
fun LanguageParser.createMissingIdentifierNameWithoutError(): Node {
    val m = open()
    createMissingToken(SyntaxKind.IdentifierToken, report = false)
    return m.done(SyntaxKind.IdentifierName)
}

/** `ParseExpressionStatement(attributes)` (LP 11101) */
fun LanguageParser.parseExpressionStatement(m: Open): Node {
    val expression = parseExpressionCore()
    eatToken(SyntaxKind.SemicolonToken)
    return m.done(SyntaxKind.ExpressionStatement).also { it.inner = expression }
}

// ---- local declarations ----------------------------------------------------------------------------------------------

/**
 * `ParseLocalDeclarationStatement(attributes)` (LP 10546); null when it is not a statement: no attributes
 * ([hadAttributes] is Roslyn's `attributes.Count != 0`), a leading accessibility modifier and no local function.
 */
internal fun LanguageParser.parseLocalDeclarationStatement(m: Open, hadAttributes: Boolean): Node? {
    var canParseAsLocalFunction = false
    var usingKeyword = false
    if (isPossibleAwaitUsing()) {
        eatContextualToken(SyntaxKind.AwaitKeyword)
        eatToken()
        usingKeyword = true
    } else if (currentKind === SyntaxKind.UsingKeyword) {
        eatToken()
        usingKeyword = true
    } else {
        canParseAsLocalFunction = true
    }
    val mods = parseLocalDeclarationStatementModifiers(isUsingDeclaration = usingKeyword)
    if (isDefiniteScopedModifier(isFunctionPointerParameter = false, isLambdaParameter = false)) {
        // `scoped T x`: the type is a ScopedType wrapping the type that follows.
        val scoped = open()
        eatContextualToken(SyntaxKind.ScopedKeyword)
        val type = if (canParseAsLocalFunction) parseReturnType() else parseType()
        val scopedType = scoped.done(SyntaxKind.ScopedType)
        if (parseLocalDeclarationRest(scopedType, type, canParseAsLocalFunction, mods)) return m.done(SyntaxKind.LocalFunctionStatement)
    } else {
        val type = if (canParseAsLocalFunction) parseReturnType() else parseType()
        if (parseLocalDeclarationRest(type, type, canParseAsLocalFunction, mods)) return m.done(SyntaxKind.LocalFunctionStatement)
    }
    if (canParseAsLocalFunction && !hadAttributes && mods.isNotEmpty() && isAccessibilityModifier(mods[0])) return null
    // LP 10619: ERR_BadMemberFlag on the local function modifiers of a local (using declarations reported them all).
    if (!usingKeyword) errorCount += mods.count { isAdditionalLocalFunctionModifier(it) }
    eatToken(SyntaxKind.SemicolonToken)
    return m.done(SyntaxKind.LocalDeclarationStatement)
}

/**
 * `ParseLocalDeclaration` (LP 10808) after the type: the `VariableDeclaration` around type and declarators. Returns
 * true when a local function was parsed instead (`LocalFunctionStatement` on the statement marker).
 */
private fun LanguageParser.parseLocalDeclarationRest(typeNode: Node, innerType: Node, allowLocalFunctions: Boolean, mods: List<IElementType>): Boolean {
    val saveTerm = termState
    termState = termState or TerminatorState.IsEndOfDeclarationClause
    val localFunction = parseVariableDeclarators(innerType, allowLocalFunctions, isConst = mods.contains(SyntaxKind.ConstKeyword), mods)
    termState = saveTerm
    if (localFunction) return true
    typeNode.wrap(SyntaxKind.VariableDeclaration)
    return false
}

/** `ParseLocalDeclarationStatementModifiers` (LP 10864): the contextual kinds of the modifiers eaten. */
private fun LanguageParser.parseLocalDeclarationStatementModifiers(isUsingDeclaration: Boolean): List<IElementType> {
    val list = ArrayList<IElementType>()
    while (true) {
        val k = currentContextualKind
        if (!isDeclarationModifier(k) && !isAdditionalLocalFunctionModifier(k)) break
        if (k === SyntaxKind.AsyncKeyword || k === SyntaxKind.SafeKeyword) {
            if (!shouldTreatAsModifier()) break
            eatContextualToken(k)
        } else {
            eatToken()
        }
        // LP 10885: AddError on the token; no element, only the count (`ContainsDiagnostics` drives the `await` retry).
        if (isUsingDeclaration) {
            errorCount++ // ERR_NoModifiersOnUsing
        } else if (k === SyntaxKind.ReadOnlyKeyword || k === SyntaxKind.VolatileKeyword) {
            errorCount++ // ERR_BadMemberFlag
        }
        list += k
    }
    return list
}

/** local `shouldTreatAsModifier` of `ParseLocalDeclarationStatementModifiers` (LP 10900) */
private fun LanguageParser.shouldTreatAsModifier(): Boolean = speculate {
    var result = false
    do {
        eatToken()
        if (isDeclarationModifier(currentKind) || isAdditionalLocalFunctionModifier(currentKind)) {
            result = true
            break
        }
        val isTypeThenIdentifier = speculate { scanType() != ScanTypeFlags.NotType && currentKind === SyntaxKind.IdentifierToken }
        if (isTypeThenIdentifier) {
            result = true
            break
        }
    } while (isAdditionalLocalFunctionModifier(currentContextualKind))
    result
}

/** `IsDeclarationModifier` (LP 10932) */
fun isDeclarationModifier(kind: IElementType): Boolean =
    kind === SyntaxKind.ConstKeyword || kind === SyntaxKind.StaticKeyword || kind === SyntaxKind.ReadOnlyKeyword || kind === SyntaxKind.VolatileKeyword

/** `IsAdditionalLocalFunctionModifier` (LP 10946) */
fun isAdditionalLocalFunctionModifier(kind: IElementType): Boolean = when (kind) {
    SyntaxKind.StaticKeyword, SyntaxKind.AsyncKeyword, SyntaxKind.UnsafeKeyword, SyntaxKind.SafeKeyword, SyntaxKind.ExternKeyword,
    SyntaxKind.PublicKeyword, SyntaxKind.InternalKeyword, SyntaxKind.ProtectedKeyword, SyntaxKind.PrivateKeyword,
    -> true
    else -> false
}

/** `IsAccessibilityModifier` (LP 10968) */
fun isAccessibilityModifier(kind: IElementType): Boolean =
    kind === SyntaxKind.PublicKeyword || kind === SyntaxKind.InternalKeyword || kind === SyntaxKind.ProtectedKeyword || kind === SyntaxKind.PrivateKeyword

/** `ShouldContextualKeywordBeTreatedAsModifier(parsingStatementNotDeclaration: true)` (LP 1546) */
private fun LanguageParser.shouldContextualKeywordBeTreatedAsModifier(): Boolean {
    if (isNonContextualModifier(peekToken(1))) return true
    return speculate {
        eatToken()
        scanType() != ScanTypeFlags.NotType && isPossibleMemberName()
    }
}

/** `IsNonContextualModifier` (LP 1659) */
private fun isNonContextualModifier(token: Tok): Boolean = when (token.kind) {
    SyntaxKind.PublicKeyword, SyntaxKind.InternalKeyword, SyntaxKind.ProtectedKeyword, SyntaxKind.PrivateKeyword, SyntaxKind.SealedKeyword,
    SyntaxKind.AbstractKeyword, SyntaxKind.StaticKeyword, SyntaxKind.VirtualKeyword, SyntaxKind.ExternKeyword, SyntaxKind.NewKeyword,
    SyntaxKind.OverrideKeyword, SyntaxKind.ReadOnlyKeyword, SyntaxKind.VolatileKeyword, SyntaxKind.UnsafeKeyword, SyntaxKind.ConstKeyword,
    SyntaxKind.FixedKeyword, SyntaxKind.RefKeyword,
    -> true
    else -> false
}

/** `IsTypeDeclarationStart` (LP 2483): [isTypeDeclarationStartExact], feature checks included. */
fun LanguageParser.isTypeDeclarationStart(): Boolean = isTypeDeclarationStartExact()

// ---- declarators ------------------------------------------------------------------------------------------------------

/**
 * `ParseVariableDeclarators` (LP 5336) for a local declaration (`variableDeclarationsExpected: true`). Returns true when
 * the first declarator turned out to be a local function. [stopOnCloseParen] and [forStatement]
 * (`VariableFlags.ForStatement`) serve the declarations of `for`, `fixed` and `using (...)`; [declaratorCount]
 * receives the number of declarators.
 */
private fun LanguageParser.parseVariableDeclarators(
    type: Node, allowLocalFunctions: Boolean, isConst: Boolean, mods: List<IElementType>,
    stopOnCloseParen: Boolean = false, forStatement: Boolean = false, declaratorCount: IntArray? = null,
): Boolean {
    if (parseVariableDeclarator(type, isFirst = true, allowLocalFunctions, isConst, mods)) return true
    var count = 1
    while (true) {
        if (currentKind === SyntaxKind.SemicolonToken) break
        if (stopOnCloseParen && currentKind === SyntaxKind.CloseParenToken) break
        if (currentKind === SyntaxKind.CommaToken) {
            // `for (int i = 0, i < n; ...`: a comma not followed by a declarator start ends the declaration (LP 5372).
            if (forStatement && peekToken(1).kind !== SyntaxKind.SemicolonToken) {
                val isLegalVariableDeclaratorStart = isTrueIdentifier(peekToken(1)) &&
                    peekToken(2).kind.let { it === SyntaxKind.CommaToken || it === SyntaxKind.EqualsToken || it === SyntaxKind.SemicolonToken }
                if (!isLegalVariableDeclaratorStart) break
            }
            eatToken(SyntaxKind.CommaToken)
            parseVariableDeclarator(type, isFirst = false, allowLocalFunctions = false, isConst, mods)
            count++
        } else if (skipBadVariableListTokens() == PostSkipAction.Abort) {
            break
        }
    }
    declaratorCount?.set(0, count)
    return false
}

/** `SkipBadVariableListTokens` (LP 5419) */
private fun LanguageParser.skipBadVariableListTokens(): PostSkipAction =
    skipBadSeparatedListTokensWithExpectedKind(
        isNotExpected = { currentKind !== SyntaxKind.CommaToken },
        abort = { currentKind === SyntaxKind.SemicolonToken },
        SyntaxKind.CommaToken, SyntaxKind.SemicolonToken,
    )

/**
 * `ParseVariableDeclarator` (LP 5527) with `VariableFlags.LocalOrField`. Returns true when a local function starts
 * here (`TryParseLocalFunctionStatementBody`, LP 10985): its parameters, clauses and body are parsed there.
 */
private fun LanguageParser.parseVariableDeclarator(parentType: Node, isFirst: Boolean, allowLocalFunctions: Boolean, isConst: Boolean, mods: List<IElementType>): Boolean {
    // `T\n x.y();` is a declaration of a missing name followed by a statement (LP 5540).
    if (currentKind === SyntaxKind.IdentifierToken && !parentType.isMissing) {
        // `parentType.GetLastToken().TrailingTrivia`: of the type, not of the `,` before a later declarator.
        val lastOfType = lastTokenOf(parentType)
        if (lastOfType != null && hasTrailingNewline(lastOfType)) {
            val next = peekToken(1).kind
            val isNonEqualsBinaryToken = next !== SyntaxKind.EqualsToken && CSharpSyntaxFacts.isBinaryExpressionOperatorToken(next)
            if (next === SyntaxKind.DotToken || next === SyntaxKind.OpenParenToken || next === SyntaxKind.MinusGreaterThanToken || isNonEqualsBinaryToken) {
                val isPossibleLocalFunctionToken = next === SyntaxKind.OpenParenToken || next === SyntaxKind.LessThanToken
                if (!isPossibleLocalFunctionToken || !isLocalFunctionAfterIdentifier()) {
                    val d = open()
                    createMissingToken(SyntaxKind.IdentifierToken)
                    d.done(SyntaxKind.VariableDeclarator)
                    return false
                }
            }
        }
    }

    val m = open()
    val errorsBeforeName = errorCount
    parseIdentifierToken()
    if (!isFirst && isTrueIdentifier()) errorCount++ // ERR_MultiTypeInDeclaration
    // `!ContainsErrorDiagnostic(name)` of `looksLikeVariableInitializer`: a missing name, `await` as a name in an async
    // context (ERR_BadAwaitAsIdentifier), a second type name (ERR_MultiTypeInDeclaration).
    val nameOk = errorCount == errorsBeforeName
    val saveTerm = termState
    when (currentKind) {
        SyntaxKind.EqualsToken -> parseEqualsValueClause(isConst)
        SyntaxKind.LessThanToken, SyntaxKind.OpenParenToken -> {
            if (allowLocalFunctions && isFirst && tryParseLocalFunctionStatementBody(parentType, mods)) {
                m.drop()
                return true
            }
            if (currentKind === SyntaxKind.OpenParenToken) {
                termState = termState or TerminatorState.IsPossibleEndOfVariableDeclaration
                addError(parseBracketedArgumentList())
                termState = saveTerm
            } else if (looksLikeVariableInitializer(nameOk)) {
                parseEqualsValueClauseWithMissingEquals()
            } else if (isConst) {
                errorCount++
            }
        }
        SyntaxKind.OpenBracketToken -> {
            termState = termState or TerminatorState.IsPossibleEndOfVariableDeclaration
            parseArrayRankSpecifier(asArgumentList = true)
            termState = saveTerm
            errorCount++ // ERR_CStyleArray
            if (currentKind === SyntaxKind.EqualsToken) parseEqualsValueClause(isConst)
        }
        else -> {
            if (looksLikeVariableInitializer(nameOk)) parseEqualsValueClauseWithMissingEquals()
            else if (isConst) errorCount++
        }
    }
    m.done(SyntaxKind.VariableDeclarator)
    return false
}

private fun LanguageParser.parseEqualsValueClause(isConst: Boolean) {
    val ev = open()
    eatToken()
    if (!isConst && currentKind === SyntaxKind.RefKeyword && !isPossibleLambdaExpression(Precedence.Expression)) {
        val r = open()
        eatToken()
        parseVariableInitializer()
        r.done(SyntaxKind.RefExpression)
    } else {
        parseVariableInitializer()
    }
    ev.done(SyntaxKind.EqualsValueClause)
}

private fun LanguageParser.parseEqualsValueClauseWithMissingEquals() {
    val ev = open()
    eatToken(SyntaxKind.EqualsToken)
    parseVariableInitializer()
    ev.done(SyntaxKind.EqualsValueClause)
}

/** local `looksLikeVariableInitializer` of `ParseVariableDeclarator` (LP 5781) */
private fun LanguageParser.looksLikeVariableInitializer(nameOk: Boolean): Boolean {
    if (currentKind === SyntaxKind.EqualsToken) return false
    val next = peekToken(1).kind
    val shouldParseAsNextDeclarator = currentKind === SyntaxKind.IdentifierToken &&
        (next === SyntaxKind.IdentifierToken || next === SyntaxKind.CommaToken || next === SyntaxKind.EqualsToken || next === SyntaxKind.SemicolonToken ||
            next === SyntaxKind.CloseParenToken || next === SyntaxKind.EndOfFileToken)
    if (shouldParseAsNextDeclarator) return false
    if (!nameOk) return false
    if (!canStartExpression()) return false
    return speculate {
        val initializer = parseExpressionCore()
        !CSharpSyntaxFacts.isTypeSyntax(initializer.kind) && !initializer.containsErrorDiagnostic
    }
}

/**
 * `IsLocalFunctionAfterIdentifier` (LP 5824) at the identifier: Roslyn eats it first (LP 5590) and then looks for
 * `<...>(...)` followed by a body or `where`.
 */
internal fun LanguageParser.isLocalFunctionAfterIdentifier(): Boolean = speculate {
    eatToken()
    parseTypeParameterList()
    val paramList = parseParenthesizedParameterList()
    !paramList.isMissing && (currentKind === SyntaxKind.OpenBraceToken || currentKind === SyntaxKind.EqualsGreaterThanToken || currentContextualKind === SyntaxKind.WhereKeyword)
}

// ---- designations ----------------------------------------------------------------------------------------------------

/** `ParseDesignation(forPattern)` (LP 10705) */
fun LanguageParser.parseDesignation(forPattern: Boolean): Node {
    if (currentKind === SyntaxKind.OpenParenToken) {
        val m = open()
        eatToken(SyntaxKind.OpenParenToken)
        var done = false
        if (forPattern) {
            done = currentKind === SyntaxKind.CloseParenToken
        } else {
            parseDesignation(forPattern)
            eatToken(SyntaxKind.CommaToken)
        }
        if (!done) {
            while (true) {
                parseDesignation(forPattern)
                if (currentKind === SyntaxKind.CommaToken) eatToken(SyntaxKind.CommaToken) else break
            }
        }
        eatToken(SyntaxKind.CloseParenToken)
        return m.done(SyntaxKind.ParenthesizedVariableDesignation)
    }
    return parseSimpleDesignation()
}

/** `ParseSimpleDesignation` (LP 10761) */
fun LanguageParser.parseSimpleDesignation(): Node {
    val m = open()
    if (currentContextualKind === SyntaxKind.UnderscoreToken) {
        eatContextualToken(SyntaxKind.UnderscoreToken)
        return m.done(SyntaxKind.DiscardDesignation)
    }
    eatToken(SyntaxKind.IdentifierToken)
    return m.done(SyntaxKind.SingleVariableDesignation)
}

/** `ParseWhenClause(precedence)` (LP 10768) */
fun LanguageParser.parseWhenClause(precedence: Int): Node? {
    if (currentContextualKind !== SyntaxKind.WhenKeyword) return null
    val m = open()
    eatContextualToken(SyntaxKind.WhenKeyword)
    parseSubExpression(precedence)
    return m.done(SyntaxKind.WhenClause)
}
