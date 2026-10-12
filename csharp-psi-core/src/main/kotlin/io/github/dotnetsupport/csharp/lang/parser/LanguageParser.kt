// Ported from Roslyn: src/Compilers/CSharp/Portable/Parser/LanguageParser.cs, expressions (lines 11123-12980:
// ParseExpression .. ScanExplicitlyTypedLambda, ParseCastOrParenExpressionOrTuple 12892, ScanCast 12980, CanFollowCast
// 13245, IsPossibleLambdaExpression 13118, new/initializers/collections 13306-13793, anonymous methods and lambdas
// 13795-14100, ParsePossibleRefExpression 4444), roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c.
// Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
// Kotlin names are Roslyn's with a lower-case first letter; departures are listed in docs/csharp-psi/GRAMMAR.md.
package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.CSharpLanguageLevel
import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpDiagnosticAnchor
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpErrorCode

/** Roslyn `Precedence` (LP 11261); ints because `Expression`, `Assignment` and `Lambda` share a value. */
object Precedence {
    const val Expression = 0
    const val Assignment = 0
    const val Lambda = 0
    const val Conditional = 1
    const val Coalescing = 2
    const val ConditionalOr = 3
    const val ConditionalAnd = 4
    const val LogicalOr = 5
    const val LogicalXor = 6
    const val LogicalAnd = 7
    const val Equality = 8
    const val Relational = 9
    const val Shift = 10
    const val Additive = 11
    const val Multiplicative = 12
    const val Switch = 13
    const val Range = 14
    const val Unary = 15
    const val Cast = 16
    const val PointerIndirection = 17
    const val AddressOf = 18
    const val Primary = 19
}

/** Roslyn `ParseTypeMode` (LP 7616). */
enum class ParseTypeMode { Normal, Parameter, AfterIs, DefinitePattern, AfterOut, AfterRef, AfterTupleComma, AsExpression, NewExpression, FirstElementOfPossibleTupleLiteral }

/** Roslyn `NameOptions` (LP 6048), as bit flags. */
object NameOptions {
    const val None = 0
    const val InExpression = 1 shl 0
    const val InTypeList = 1 shl 1
    const val PossiblePattern = 1 shl 2
    const val AfterIs = 1 shl 3
    const val DefinitePattern = 1 shl 4
    const val AfterOut = 1 shl 5
    const val AfterTupleComma = 1 shl 6
    const val FirstElementOfPossibleTupleLiteral = 1 shl 7
}

/**
 * The expression part of Roslyn's `LanguageParser` on a [PsiBuilder]. Names, types, patterns, statements and
 * interpolated strings are extension functions in the sibling files (`LanguageParser_*.kt`).
 */
class LanguageParser(
    builder: PsiBuilder,
    /** The file's language version; by default from the builder ([CSharpLanguageLevel.forBuilder], docs/csharp-psi/GRAMMAR.md "Language version"). */
    languageVersion: CSharpLanguageVersion = CSharpLanguageLevel.forBuilder(builder),
) : SyntaxParser(builder, languageVersion) {

    /** See `lastAttributeArgumentAborts` (LanguageParser_Names.kt). */
    @JvmField var attributeArgumentAborts: Boolean = false

    /** Set by `parseParameterList`: a parameter of the last list parsed had no error (`TryParseLocalFunctionStatementBody`, LP 11060). */
    @JvmField var parameterListHadCleanParameter: Boolean = false

    /**
     * Decisions of `parsePositionalOrParenthesized` by raw position of the `(` (and context bits): a parenthesized
     * single subpattern is first parsed as a positional pattern and then reparsed as `(pattern)` / `(expression)`;
     * without the memo the reparse of each level repeats the reparses of the inner levels (2^depth).
     */
    @JvmField val parenPatternDecisions: HashMap<Long, Int> = HashMap()

    // ---- declarations (LanguageParser_Declarations.kt) -------------------------------------------------------------

    /** Element count of the last `TupleType` parsed (`TryParseConversionOperatorDeclaration`, LP 3925). */
    @JvmField var lastTupleTypeElementCount: Int = 0

    /*
     * Invariant: these two are not in the reset point snapshot ([SyntaxParser.ResetPoint]). The flag is set by the
     * second pass of `ParseNamespaceBody` right before a member and consumed by `parseMainTypeDeclaration`; a type
     * declaration is never parsed speculatively (`parseMemberDeclarationOrStatementCore` returns it directly), so no
     * reset point taken before it is reset once it has consumed the flag or left [absorbedTypeDeclaration]. The only
     * rollback of a namespace body, between its two passes, happens before either is set. A new rollback over a parsed
     * type declaration must snapshot them (docs/csharp-psi/GRAMMAR.md, "Moving members into the preceding type").
     */
    /** Second pass of `ParseNamespaceBody`: the next type declaration stays open to absorb the members after it. */
    @JvmField var absorbNextTypeDeclaration: Boolean = false

    /** The type declaration left open by the flag above. */
    @JvmField var absorbedTypeDeclaration: AbsorbedType? = null

    /** The last type declaration that ended with a real `}` and no `;` (a candidate of the namespace post-pass). */
    @JvmField var lastCleanTypeDeclaration: Node? = null
    /**
     * The same memo for the other parse-then-reparse decisions of statements, keyed by [decisionKey]: whether the
     * governing expression of `parseSwitchHeader` was a `ParenthesizedExpression` (reparsed without the wrapper), and
     * how `parseExpressionOrPatternForSwitchStatement` ended (pattern, or reparsed as an expression). A lambda body in
     * the reparsed tokens holds the inner switch statements; without the memo each level doubles the work (2^depth).
     */
    @JvmField val switchHeaderDecisions: HashMap<Long, Int> = HashMap()
    @JvmField val caseLabelDecisions: HashMap<Long, Int> = HashMap()

    /**
     * Reparseable bodies (`parseBodyBlock`): the context key of the body parsed at a raw position, and the positions
     * where bodies were parsed in more than one context (speculative parses included; never reset).
     */
    @JvmField val bodyContexts: HashMap<Int, Long> = HashMap()
    @JvmField val conflictedBodies: HashSet<Int> = HashSet()

    /** Whether `unsafe (` at a position starts a local function (`ParseExpressionStatementOrLocalFunctionStartingWithUnsafe`). */
    @JvmField val unsafeLocalFunctionDecisions: HashMap<Long, Boolean> = HashMap()

    /**
     * Key of the decision memos: the raw position and the whole parse context a decision may depend on (the terminator
     * state and the flags of a reset point), so that a memoised decision is the one a fresh parse at that position would
     * make. A body reparsed alone (docs/csharp-psi/GRAMMAR.md, "Reparseable bodies") starts with empty memos; a decision taken from
     * a memo filled in another context would make the full parse differ from it.
     */
    fun decisionKey(extraBit: Boolean = false): Long =
        (position.toLong() shl 34) or ((termState.toLong() and 0x1FFFFFFFL) shl 5) or
            (if (extraBit) 1L else 0L) or (if (isInAsync) 2L else 0L) or (if (isInQuery) 4L else 0L) or
            (if (isInFieldKeywordContext) 8L else 0L) or (if (forceConditionalAccessExpression) 16L else 0L)

    // ---- terminators (LP 94-135, predicates of the slice) ----------------------------------------------------------

    override fun isTerminatorState(flag: Int): Boolean = when (flag) {
        TerminatorState.IsAttributeDeclarationTerminator -> isAttributeDeclarationTerminator()
        TerminatorState.IsEndOfReturnType -> isEndOfReturnType()
        TerminatorState.IsEndOfParameterList -> isEndOfParameterList()
        TerminatorState.IsPossibleEndOfVariableDeclaration -> currentKind === SyntaxKind.CommaToken || currentKind === SyntaxKind.SemicolonToken
        TerminatorState.IsEndOfTypeArgumentList -> currentKind === SyntaxKind.GreaterThanToken
        TerminatorState.IsPossibleStatementStartOrStop -> isPossibleStatementStartOrStop()
        TerminatorState.IsEndOfDeclarationClause -> currentKind === SyntaxKind.SemicolonToken || currentKind === SyntaxKind.ColonToken
        TerminatorState.IsEndOfArgumentList -> currentKind === SyntaxKind.CloseParenToken || currentKind === SyntaxKind.CloseBracketToken
        TerminatorState.IsEndOfTypeParameterList -> currentKind === SyntaxKind.OpenParenToken || currentKind === SyntaxKind.ColonToken || currentKind === SyntaxKind.OpenBraceToken ||
            isCurrentTokenWhereOfConstraintClause()
        TerminatorState.IsEndOfFunctionPointerParameterList -> currentKind === SyntaxKind.GreaterThanToken
        TerminatorState.IsEndOfFunctionPointerParameterListErrored -> currentKind === SyntaxKind.CloseParenToken
        TerminatorState.IsEndOfFunctionPointerCallingConvention -> currentKind === SyntaxKind.CloseBracketToken
        TerminatorState.IsEndOfFixedStatement -> isEndOfFixedStatement()
        TerminatorState.IsEndOfTryBlock -> isEndOfTryBlock()
        TerminatorState.IsEndOfCatchClause -> isEndOfCatchClause()
        TerminatorState.IsEndOfFilterClause -> isEndOfFilterClause()
        TerminatorState.IsEndOfCatchBlock -> isEndOfCatchBlock()
        TerminatorState.IsEndOfDoWhileExpression -> isEndOfDoWhileExpression()
        TerminatorState.IsEndOfForStatementArgument -> isEndOfForStatementArgument()
        TerminatorState.IsSwitchSectionStart -> isPossibleSwitchSection()
        else -> isDeclarationTerminatorState(flag)
    }

    /** `IsEndOfReturnType` (LP 3756) */
    fun isEndOfReturnType(): Boolean =
        currentKind === SyntaxKind.OpenParenToken || currentKind === SyntaxKind.OpenBraceToken || currentKind === SyntaxKind.SemicolonToken

    /** `IsEndOfParameterList` (LP 13972 area) */
    fun isEndOfParameterList(): Boolean =
        currentKind === SyntaxKind.CloseParenToken || currentKind === SyntaxKind.CloseBracketToken || currentKind === SyntaxKind.SemicolonToken

    // ---- entry points ----------------------------------------------------------------------------------------------

    /** `ParseExpression` (LP 11123) */
    fun parseExpression(): Node = parseExpressionCore()

    /** `ParseExpressionCore` (LP 11130) */
    fun parseExpressionCore(): Node = parseSubExpression(Precedence.Expression)

    /** `CanStartExpression` (LP 11138) */
    fun canStartExpression(): Boolean = isPossibleExpression(allowBinaryExpressions = false, allowAssignmentExpressions = false)

    /** `IsPossibleExpression` (LP 11146) */
    fun isPossibleExpression(): Boolean = isPossibleExpression(allowBinaryExpressions = true, allowAssignmentExpressions = true)

    /** `IsPossibleExpression(bool, bool)` (LP 11151) */
    fun isPossibleExpression(allowBinaryExpressions: Boolean, allowAssignmentExpressions: Boolean): Boolean {
        val tk = currentKind
        return when (tk) {
            SyntaxKind.TypeOfKeyword, SyntaxKind.DefaultKeyword, SyntaxKind.SizeOfKeyword, SyntaxKind.MakeRefKeyword,
            SyntaxKind.RefTypeKeyword, SyntaxKind.CheckedKeyword, SyntaxKind.UncheckedKeyword, SyntaxKind.UnsafeKeyword,
            SyntaxKind.RefValueKeyword, SyntaxKind.ArgListKeyword, SyntaxKind.BaseKeyword, SyntaxKind.FalseKeyword,
            SyntaxKind.ThisKeyword, SyntaxKind.TrueKeyword, SyntaxKind.NullKeyword, SyntaxKind.OpenParenToken,
            SyntaxKind.NumericLiteralToken, SyntaxKind.StringLiteralToken, SyntaxKind.Utf8StringLiteralToken,
            SyntaxKind.SingleLineRawStringLiteralToken, SyntaxKind.Utf8SingleLineRawStringLiteralToken,
            SyntaxKind.MultiLineRawStringLiteralToken, SyntaxKind.Utf8MultiLineRawStringLiteralToken,
            SyntaxKind.InterpolatedStringToken, SyntaxKind.InterpolatedStringStartToken,
            SyntaxKind.InterpolatedVerbatimStringStartToken, SyntaxKind.InterpolatedSingleLineRawStringStartToken,
            SyntaxKind.InterpolatedMultiLineRawStringStartToken, SyntaxKind.CharacterLiteralToken, SyntaxKind.NewKeyword,
            SyntaxKind.DelegateKeyword, SyntaxKind.ColonColonToken, SyntaxKind.ThrowKeyword, SyntaxKind.StackAllocKeyword,
            SyntaxKind.RefKeyword, SyntaxKind.OpenBracketToken,
            -> true
            SyntaxKind.DotToken -> isAtDotDotToken()
            SyntaxKind.StaticKeyword -> isPossibleAnonymousMethodExpression() || isPossibleLambdaExpression(Precedence.Expression)
            SyntaxKind.IdentifierToken -> isTrueIdentifier() || currentContextualKind === SyntaxKind.FromKeyword
            else -> CSharpSyntaxFacts.isPredefinedType(tk) ||
                CSharpSyntaxFacts.isAnyUnaryExpression(tk) ||
                (allowBinaryExpressions && CSharpSyntaxFacts.isBinaryExpression(tk)) ||
                (allowAssignmentExpressions && CSharpSyntaxFacts.isAssignmentExpressionOperatorToken(tk))
        }
    }

    /** `IsInvalidSubExpression` (LP 11209) */
    private fun isInvalidSubExpression(kind: IElementType): Boolean = kind in invalidSubExpressionStarts

    /** `IsAwaitExpression` (LP 11440) */
    fun isAwaitExpression(): Boolean {
        if (currentContextualKind !== SyntaxKind.AwaitKeyword) return false
        if (isInAsync) return true
        val next = peekToken(1)
        return when (next.kind) {
            SyntaxKind.IdentifierToken -> next.contextualKind !== SyntaxKind.WithKeyword
            SyntaxKind.NewKeyword, SyntaxKind.ThisKeyword, SyntaxKind.BaseKeyword, SyntaxKind.DelegateKeyword,
            SyntaxKind.TypeOfKeyword, SyntaxKind.CheckedKeyword, SyntaxKind.UncheckedKeyword, SyntaxKind.DefaultKeyword,
            SyntaxKind.TrueKeyword, SyntaxKind.FalseKeyword, SyntaxKind.StringLiteralToken,
            SyntaxKind.SingleLineRawStringLiteralToken, SyntaxKind.Utf8SingleLineRawStringLiteralToken,
            SyntaxKind.MultiLineRawStringLiteralToken, SyntaxKind.Utf8MultiLineRawStringLiteralToken,
            SyntaxKind.InterpolatedStringToken, SyntaxKind.Utf8StringLiteralToken, SyntaxKind.InterpolatedStringStartToken,
            SyntaxKind.InterpolatedVerbatimStringStartToken, SyntaxKind.InterpolatedSingleLineRawStringStartToken,
            SyntaxKind.InterpolatedMultiLineRawStringStartToken, SyntaxKind.NumericLiteralToken, SyntaxKind.NullKeyword,
            SyntaxKind.CharacterLiteralToken,
            -> true
            else -> false
        }
    }

    /** `IsPossibleAwaitExpressionStatement` (LP 11435) */
    fun isPossibleAwaitExpressionStatement(): Boolean = (isScript || isInAsync) && currentContextualKind === SyntaxKind.AwaitKeyword

    /** Reparses of a conditional's when-true part (hard spot 7.1); for tests. */
    @JvmField var conditionalReparses: Int = 0

    /** `ParseSubExpression` (LP 11496) */
    fun parseSubExpression(precedence: Int): Node {
        recursionDepth++
        try {
            if (recursionDepth > MAX_DEPTH) {
                // StackGuard.EnsureSufficientExecutionStack: give up on this operand instead of overflowing.
                return addError(createMissingIdentifierName(CSharpErrorCode.ERR_InsufficientStack.describe()))!!
            }
            return parseSubExpressionCore(precedence)
        } finally {
            recursionDepth--
        }
    }

    /** `ParseSubExpressionCore` (LP 11511) */
    private fun parseSubExpressionCore(precedence: Int): Node {
        val tk = currentKind
        if (isInvalidSubExpression(tk)) {
            return addError(createMissingIdentifierName(CSharpErrorCode.ERR_InvalidExprTerm.describe(CSharpSyntaxFacts.getText(tk))))!!
        }
        return parseExpressionContinued(parseUnaryOrPrimaryExpression(precedence, tk), precedence)
    }

    /** local `parseUnaryOrPrimaryExpression` (LP 11527) */
    private fun parseUnaryOrPrimaryExpression(precedence: Int, tk: IElementType): Node {
        if (isExpectedPrefixUnaryOperator(tk)) {
            val opKind = CSharpSyntaxFacts.getPrefixUnaryExpression(tk)!!
            val m = open()
            eatToken()
            parseSubExpression(getPrecedence(opKind))
            return m.done(opKind)
        }

        if (isAtDotDotToken()) {
            val m = open()
            eatDotDotToken()
            if (canStartExpression()) parseSubExpression(Precedence.Range)
            return m.done(SyntaxKind.RangeExpression)
        }

        if (isAwaitExpression()) {
            val m = open()
            eatContextualToken(SyntaxKind.AwaitKeyword)
            parseSubExpression(getPrecedence(SyntaxKind.AwaitExpression))
            return m.done(SyntaxKind.AwaitExpression)
        }

        if (isQueryExpression(mayBeVariableDeclaration = false, mayBeMemberDeclaration = false)) {
            return parseQueryExpression(precedence)
        }

        if (currentContextualKind === SyntaxKind.FromKeyword && isInQuery) {
            // `AddError(EatToken(), ERR_InvalidExprTerm, CurrentToken.Text)`: Roslyn reads the text after eating, so the
            // message names the token after `from` (LP 11569)
            val name = createMissingIdentifierName(SKIPPED)
            val next = peekToken(1)
            skipTokens(1, CSharpErrorCode.ERR_InvalidExprTerm.describe(builder.originalText.subSequence(next.start, next.end)))
            return name
        }

        if (tk === SyntaxKind.ThrowKeyword) {
            val result = parseThrowExpression()
            return if (precedence <= Precedence.Coalescing) result else addError(result)!!
        }

        if (isPossibleDeconstructionLeft(precedence)) {
            return parseDeclarationExpression(ParseTypeMode.Normal, isScoped = false)
        }

        return parsePrimaryExpression(precedence)
    }

    /** `ParseExpressionContinued` (LP 11596): the binary/assignment loop plus the conditional operator. */
    fun parseExpressionContinued(unaryOrPrimaryExpression: Node, precedence: Int): Node {
        var current = unaryOrPrimaryExpression
        while (true) {
            current = tryExpandExpression(current, precedence) ?: break
        }
        if (currentKind === SyntaxKind.QuestionToken && precedence <= Precedence.Conditional) {
            return consumeConditionalExpression(current)
        }
        return current
    }

    /** True when [parseExpressionContinued] would consume at least one more token at [precedence] (used by the pattern conversions, docs/csharp-psi/GRAMMAR.md). */
    fun expressionContinuationFollows(leftKind: IElementType, precedence: Int): Boolean {
        val (operatorTokenKind, operatorExpressionKind) = getExpressionOperatorTokenKindAndExpressionKind()
        if (operatorTokenKind != null) {
            val newPrecedence = getPrecedence(operatorExpressionKind!!)
            if (newPrecedence > precedence || (newPrecedence == precedence && isRightAssociative(operatorExpressionKind))) return true
        }
        return currentKind === SyntaxKind.QuestionToken && precedence <= Precedence.Conditional
    }

    /** local `tryExpandExpression` (LP 11621) */
    private fun tryExpandExpression(leftOperand: Node, precedence: Int): Node? {
        val (operatorTokenKind, operatorExpressionKind) = getExpressionOperatorTokenKindAndExpressionKind()
        if (operatorTokenKind == null || operatorExpressionKind == null) return null

        val newPrecedence = getPrecedence(operatorExpressionKind)
        if (newPrecedence < precedence) return null
        if (newPrecedence == precedence && !isRightAssociative(operatorExpressionKind)) return null

        eatExpressionOperatorToken(operatorTokenKind)

        if (newPrecedence > getPrecedence(leftOperand.kind)) {
            // ERR_UnexpectedToken for an is-pattern on the left, WRN_PrecedenceInversion otherwise: a diagnostic on the
            // operator, no tree change.
            if (leftOperand.kind === SyntaxKind.IsPatternExpression) errorCount++
        }

        when (operatorExpressionKind) {
            SyntaxKind.AsExpression -> {
                parseType(ParseTypeMode.AsExpression)
                return leftOperand.wrap(SyntaxKind.AsExpression)
            }
            SyntaxKind.IsExpression -> return parseIsExpression(leftOperand)
            SyntaxKind.SwitchExpression -> return parseSwitchExpression(leftOperand)
            SyntaxKind.WithExpression -> return parseWithExpression(leftOperand)
            SyntaxKind.RangeExpression -> {
                if (canStartExpression()) parseSubExpression(Precedence.Range)
                return leftOperand.wrap(SyntaxKind.RangeExpression)
            }
        }

        if (isExpectedAssignmentOperator(operatorTokenKind)) {
            return parseAssignmentExpression(operatorExpressionKind, leftOperand)
        }
        if (isExpectedBinaryOperator(operatorTokenKind)) {
            parseSubExpression(newPrecedence)
            return leftOperand.wrap(operatorExpressionKind)
        }
        throw IllegalStateException("unreachable: $operatorTokenKind")
    }

    /**
     * local `consumeConditionalExpression` (LP 11700). Hard spot 7.1: Roslyn restores an earlier parse after trying
     * another; here the first interpretation is parsed a third time when the forced one does not end at `:`.
     */
    private fun consumeConditionalExpression(leftOperand: Node): Node {
        eatToken() // `?`
        val afterQuestionToken = getResetPoint()
        val whenTrueStartsWithBracket = currentKind === SyntaxKind.OpenBracketToken
        try {
            // `containsTernaryCollectionToReinterpret(whenTrue)` (LP 11769) walks the when-true subtree for a
            // conditional whose when-true starts with `[`: here every such conditional increments
            // `ternaryCollectionCount` when it is built, so the ones nested in when-true show as a change of the counter.
            // This conditional itself is counted at the end, for its parents.
            val countBefore = ternaryCollectionCount
            parsePossibleRefExpression()

            if (currentKind !== SyntaxKind.ColonToken && !forceConditionalAccessExpression && ternaryCollectionCount > countBefore) {
                // Go back to right after the `?` and reparse with `?[` as a conditional access.
                conditionalReparses++
                afterQuestionToken.reset()
                val savedForce = forceConditionalAccessExpression
                forceConditionalAccessExpression = true
                try {
                    parsePossibleRefExpression()
                } finally {
                    forceConditionalAccessExpression = savedForce
                }
                if (currentKind !== SyntaxKind.ColonToken) {
                    // Retrying did not help: the original interpretation (parsed again, see GRAMMAR.md).
                    conditionalReparses++
                    afterQuestionToken.reset()
                    parsePossibleRefExpression()
                }
            }
        } finally {
            afterQuestionToken.release()
        }

        eatToken(SyntaxKind.ColonToken)
        parsePossibleRefExpression()
        if (whenTrueStartsWithBracket) ternaryCollectionCount++
        return leftOperand.wrap(SyntaxKind.ConditionalExpression)
    }

    /** `GetExpressionOperatorTokenKindAndExpressionKind` (LP 11797): merges `..`, `>>`, `>>=`, `>>>`, `>>>=`. */
    fun getExpressionOperatorTokenKindAndExpressionKind(): Pair<IElementType?, IElementType?> {
        val token1 = currentToken
        var token1Kind = token1.contextualKind

        if (isAtDotDotToken()) return SyntaxKind.DotDotToken to SyntaxKind.RangeExpression

        if (token1Kind === SyntaxKind.GreaterThanToken) {
            val token2 = peekToken(1)
            if ((token2.kind === SyntaxKind.GreaterThanToken || token2.kind === SyntaxKind.GreaterThanEqualsToken) && noTriviaBetween(token1, token2)) {
                if (token2.kind === SyntaxKind.GreaterThanToken) {
                    val token3 = peekToken(2)
                    token1Kind = if ((token3.kind === SyntaxKind.GreaterThanToken || token3.kind === SyntaxKind.GreaterThanEqualsToken) && noTriviaBetween(token2, token3)) {
                        if (token3.kind === SyntaxKind.GreaterThanToken) SyntaxKind.GreaterThanGreaterThanGreaterThanToken
                        else SyntaxKind.GreaterThanGreaterThanGreaterThanEqualsToken
                    } else {
                        SyntaxKind.GreaterThanGreaterThanToken
                    }
                } else {
                    token1Kind = SyntaxKind.GreaterThanGreaterThanEqualsToken
                }
            }
        }

        if (isExpectedBinaryOperator(token1Kind)) return token1Kind to CSharpSyntaxFacts.getBinaryExpression(token1Kind)
        if (isExpectedAssignmentOperator(token1Kind)) return token1Kind to CSharpSyntaxFacts.getAssignmentExpression(token1Kind)
        if (token1Kind === SyntaxKind.SwitchKeyword && peekToken(1).kind === SyntaxKind.OpenBraceToken) return token1Kind to SyntaxKind.SwitchExpression
        if (token1Kind === SyntaxKind.WithKeyword && peekToken(1).kind === SyntaxKind.OpenBraceToken) return token1Kind to SyntaxKind.WithExpression
        return null to null
    }

    /** `EatExpressionOperatorToken` (LP 11857): merged tokens are collapsed into one leaf. */
    fun eatExpressionOperatorToken(operatorTokenKind: IElementType) {
        when (operatorTokenKind) {
            SyntaxKind.DotDotToken -> eatDotDotToken()
            SyntaxKind.GreaterThanGreaterThanToken, SyntaxKind.GreaterThanGreaterThanEqualsToken -> collapseTokens(2, operatorTokenKind)
            SyntaxKind.GreaterThanGreaterThanGreaterThanToken, SyntaxKind.GreaterThanGreaterThanGreaterThanEqualsToken -> collapseTokens(3, operatorTokenKind)
            else -> eatContextualToken(operatorTokenKind)
        }
    }

    private fun collapseTokens(count: Int, kind: IElementType) {
        val m = builder.mark()
        repeat(count) { advance() }
        m.collapse(kind)
    }

    /** `ParseAssignmentExpression` (LP 11898) */
    private fun parseAssignmentExpression(operatorExpressionKind: IElementType, leftOperand: Node): Node {
        if (operatorExpressionKind === SyntaxKind.SimpleAssignmentExpression && currentKind === SyntaxKind.RefKeyword &&
            !isPossibleLambdaExpression(Precedence.Assignment)
        ) {
            val m = open()
            eatToken()
            parseExpressionCore()
            m.done(SyntaxKind.RefExpression)
        } else {
            parseSubExpression(Precedence.Assignment)
        }
        val node = leftOperand.wrap(operatorExpressionKind)
        node.left = leftOperand
        return node
    }

    /** `IsAtDotDotToken` (LP 11923): two adjacent dots (or a `..` token from the lexer). */
    fun isAtDotDotToken(): Boolean {
        val t = currentToken
        if (t.kind === SyntaxKind.DotDotToken) return true
        if (t.kind !== SyntaxKind.DotToken) return false
        val next = peekToken(1)
        return next.kind === SyntaxKind.DotToken && noTriviaBetween(t, next)
    }

    /** `IsAtDotDotToken(token1, token2)` */
    fun isAtDotDotToken(token1: Tok, token2: Tok): Boolean =
        token1.kind === SyntaxKind.DotDotToken || (token1.kind === SyntaxKind.DotToken && token2.kind === SyntaxKind.DotToken && noTriviaBetween(token1, token2))

    /** `EatDotDotToken` (LP 11940) */
    fun eatDotDotToken() {
        if (currentKind === SyntaxKind.DotDotToken) {
            eatToken()
        } else {
            collapseTokens(2, SyntaxKind.DotDotToken)
        }
        val token3 = currentToken
        if (token3.kind === SyntaxKind.DotToken && token3.start == peekTokenEndBefore()) {
            // Three dots in a row: ERR_TripleDotNotAllowed; exactly three → the third is skipped.
            errorCount++
            val token4 = peekToken(1)
            if (!(token4.kind === SyntaxKind.DotToken && noTriviaBetween(token3, token4))) skipTokens(1, CSharpErrorCode.ERR_TripleDotNotAllowed.describe(anchor = CSharpDiagnosticAnchor.PREVIOUS_START))
        }
    }

    /** End offset of the token just eaten (for adjacency checks after `advance`). */
    private fun peekTokenEndBefore(): Int {
        var step = -1
        while (true) {
            val type = builder.rawLookup(step) ?: return -1
            if (!isTrivia(type)) return builder.rawTokenTypeStart(step + 1)
            step--
        }
    }

    /** `ParseDeclarationExpression` (LP 11976) */
    fun parseDeclarationExpression(mode: ParseTypeMode, isScoped: Boolean): Node {
        val m = open()
        if (isScoped) {
            val scoped = open()
            eatContextualToken(SyntaxKind.ScopedKeyword)
            parseType(mode)
            scoped.done(SyntaxKind.ScopedType)
        } else {
            parseType(mode)
        }
        parseDesignation(forPattern = false)
        return m.done(SyntaxKind.DeclarationExpression)
    }

    /** `ParseThrowExpression` (LP 11988) */
    private fun parseThrowExpression(): Node {
        val m = open()
        eatToken(SyntaxKind.ThrowKeyword)
        parseSubExpression(Precedence.Coalescing)
        return m.done(SyntaxKind.ThrowExpression)
    }

    /** `ParseIsExpression` (LP 11995): `IsPatternExpression` or `IsExpression` by what `ParseTypeOrPatternForIsOperator` yields. */
    private fun parseIsExpression(leftOperand: Node): Node {
        val isType = parseTypeOrPatternForIsOperator()
        return leftOperand.wrap(if (isType) SyntaxKind.IsExpression else SyntaxKind.IsPatternExpression)
    }

    // ---- primary expressions (LP 12006-12278) ---------------------------------------------------------------------

    /** `ParsePrimaryExpression` (LP 12006) */
    fun parsePrimaryExpression(precedence: Int): Node = parsePostFixExpression(parsePrimaryExpressionWithoutPostfix(precedence))

    /** local `parsePrimaryExpressionWithoutPostfix` (LP 12015) */
    private fun parsePrimaryExpressionWithoutPostfix(precedence: Int): Node {
        val tk = currentKind
        when (tk) {
            SyntaxKind.TypeOfKeyword -> return parseTypeOfExpression()
            SyntaxKind.DefaultKeyword -> return parseDefaultExpression()
            SyntaxKind.SizeOfKeyword -> return parseSizeOfExpression()
            SyntaxKind.MakeRefKeyword -> return parseKeywordParenthesizedExpression(SyntaxKind.MakeRefExpression)
            SyntaxKind.RefTypeKeyword -> return parseKeywordParenthesizedExpression(SyntaxKind.RefTypeExpression)
            SyntaxKind.CheckedKeyword -> return parseKeywordParenthesizedExpression(SyntaxKind.CheckedExpression)
            SyntaxKind.UncheckedKeyword -> return parseKeywordParenthesizedExpression(SyntaxKind.UncheckedExpression)
            SyntaxKind.UnsafeKeyword -> return parseKeywordParenthesizedExpression(SyntaxKind.UnsafeExpression)
            SyntaxKind.RefValueKeyword -> return parseRefValueExpression()
            SyntaxKind.ColonColonToken -> return parseAliasQualifiedName(NameOptions.InExpression)
            SyntaxKind.EqualsGreaterThanToken -> return parseLambdaExpression()
            SyntaxKind.StaticKeyword -> return when {
                isPossibleAnonymousMethodExpression() -> parseAnonymousMethodExpression()
                isPossibleLambdaExpression(precedence) -> parseLambdaExpression()
                else -> addError(createMissingIdentifierName(CSharpErrorCode.ERR_InvalidExprTerm.describe(currentText)))!!
            }
            SyntaxKind.IdentifierToken -> {
                if (!isTrueIdentifier()) return addError(createMissingIdentifierName(CSharpErrorCode.ERR_InvalidExprTerm.describe(currentText)))!!
                if (isPossibleAnonymousMethodExpression()) return parseAnonymousMethodExpression()
                if (isPossibleLambdaExpression(precedence)) {
                    tryParseLambdaExpression()?.let { return it }
                }
                if (isPossibleDeconstructionLeft(precedence)) return parseDeclarationExpression(ParseTypeMode.Normal, isScoped = false)
                if (isCurrentTokenFieldInKeywordContext() && peekToken(1).kind !== SyntaxKind.ColonColonToken) {
                    val m = open()
                    eatContextualToken(SyntaxKind.FieldKeyword)
                    return m.done(SyntaxKind.FieldExpression)
                }
                return parseAliasQualifiedName(NameOptions.InExpression)
            }
            SyntaxKind.OpenBracketToken -> return if (isPossibleLambdaExpression(precedence)) parseLambdaExpression() else parseCollectionExpression()
            SyntaxKind.ThisKeyword -> return singleTokenNode(SyntaxKind.ThisExpression)
            SyntaxKind.BaseKeyword -> return singleTokenNode(SyntaxKind.BaseExpression)
            SyntaxKind.ArgListKeyword, SyntaxKind.FalseKeyword, SyntaxKind.TrueKeyword, SyntaxKind.NullKeyword,
            SyntaxKind.NumericLiteralToken, SyntaxKind.StringLiteralToken, SyntaxKind.Utf8StringLiteralToken,
            SyntaxKind.CharacterLiteralToken,
            -> return singleTokenNode(CSharpSyntaxFacts.getLiteralExpression(tk)!!)
            SyntaxKind.InterpolatedStringStartToken, SyntaxKind.InterpolatedVerbatimStringStartToken,
            SyntaxKind.InterpolatedSingleLineRawStringStartToken, SyntaxKind.InterpolatedMultiLineRawStringStartToken,
            SyntaxKind.InterpolatedStringToken,
            -> return parseInterpolatedStringToken()
            SyntaxKind.SingleLineRawStringLiteralToken, SyntaxKind.Utf8SingleLineRawStringLiteralToken,
            SyntaxKind.MultiLineRawStringLiteralToken, SyntaxKind.Utf8MultiLineRawStringLiteralToken,
            -> return singleTokenNode(CSharpSyntaxFacts.getLiteralExpression(tk)!!)
            SyntaxKind.OpenParenToken -> {
                if (isPossibleLambdaExpression(precedence)) {
                    tryParseLambdaExpression()?.let { return it }
                }
                return parseCastOrParenExpressionOrTuple()
            }
            SyntaxKind.NewKeyword -> return parseNewExpression()
            SyntaxKind.StackAllocKeyword -> return parseStackAllocExpression()
            SyntaxKind.DelegateKeyword -> return if (isPossibleLambdaExpression(precedence)) parseLambdaExpression() else parseAnonymousMethodExpression()
            SyntaxKind.RefKeyword -> {
                if (isPossibleLambdaExpression(precedence)) return parseLambdaExpression()
                val m = open()
                eatToken()
                parseExpressionCore()
                return addError(m.done(SyntaxKind.RefExpression))!!
            }
            else -> {
                if (CSharpSyntaxFacts.isPredefinedType(tk)) {
                    if (isPossibleLambdaExpression(precedence)) return parseLambdaExpression()
                    val expr = singleTokenNode(SyntaxKind.PredefinedType)
                    if (currentKind !== SyntaxKind.DotToken || tk === SyntaxKind.VoidKeyword) addError(expr)
                    return expr
                }
                val message = when {
                    tk === SyntaxKind.EndOfFileToken -> CSharpErrorCode.ERR_ExpressionExpected.describe()
                    // on the operator, wherever the token before is (LP 12176)
                    CSharpSyntaxFacts.isBinaryExpression(tk) || CSharpSyntaxFacts.isAssignmentExpressionOperatorToken(tk) ->
                        CSharpErrorCode.ERR_InvalidExprTerm.describe(CSharpSyntaxFacts.getText(tk), anchor = CSharpDiagnosticAnchor.NEXT)
                    else -> CSharpErrorCode.ERR_InvalidExprTerm.describe(CSharpSyntaxFacts.getText(tk))
                }
                return addError(createMissingIdentifierName(message))!!
            }
        }
    }

    /** A node made of the current token alone (`ThisExpression`, literals, `PredefinedType`). */
    fun singleTokenNode(kind: IElementType): Node {
        val m = open()
        eatToken()
        return m.done(kind)
    }

    /** local `parsePostFixExpression` (LP 12192) */
    private fun parsePostFixExpression(start: Node): Node {
        var expr = start
        while (true) {
            when (currentKind) {
                SyntaxKind.OpenParenToken -> {
                    parseParenthesizedArgumentList()
                    expr = expr.wrap(SyntaxKind.InvocationExpression)
                }
                SyntaxKind.OpenBracketToken -> {
                    parseBracketedArgumentList()
                    expr = expr.wrap(SyntaxKind.ElementAccessExpression)
                }
                SyntaxKind.PlusPlusToken, SyntaxKind.MinusMinusToken -> {
                    val kind = CSharpSyntaxFacts.getPostfixUnaryExpression(currentKind)!!
                    eatToken()
                    expr = expr.wrap(kind)
                }
                SyntaxKind.ColonColonToken -> {
                    if (peekToken(1).kind === SyntaxKind.IdentifierToken) {
                        // `::` becomes a missing dot with the `::` as skipped syntax (ERR_UnexpectedAliasedName).
                        skipTokens(1, CSharpErrorCode.ERR_UnexpectedAliasedName.describe())
                        parseSimpleName(NameOptions.InExpression)
                        expr = expr.wrap(SyntaxKind.SimpleMemberAccessExpression)
                    } else {
                        // `EatTokenEvenWithIncorrectKind(DotToken)` as skipped syntax (LP 12230)
                        skipTokens(1, SyntaxParser.expectedMessage(SyntaxKind.DotToken, currentKind, CSharpDiagnosticAnchor.FIRST_EXPECTED))
                    }
                }
                SyntaxKind.MinusGreaterThanToken -> {
                    eatToken()
                    parseSimpleName(NameOptions.InExpression)
                    expr = expr.wrap(SyntaxKind.PointerMemberAccessExpression)
                }
                SyntaxKind.DotToken -> {
                    if (isAtDotDotToken()) return expr
                    val dot = currentToken
                    if (hasTrailingNewline(dot) && peekToken(1).kind === SyntaxKind.IdentifierToken && peekToken(2).contextualKind === SyntaxKind.IdentifierToken) {
                        // `expr.` followed by a declaration on the next line: an incomplete member access.
                        eatToken()
                        addError(createMissingIdentifierName())
                        return expr.wrap(SyntaxKind.SimpleMemberAccessExpression)
                    }
                    eatToken()
                    val name = parseSimpleName(NameOptions.InExpression)
                    val wrapped = expr.wrap(SyntaxKind.SimpleMemberAccessExpression)
                    wrapped.isNameShaped = expr.isNameShaped
                    wrapped.left = expr
                    wrapped.inner = name
                    expr = wrapped
                }
                SyntaxKind.QuestionToken -> {
                    expr = tryParseConditionalAccessExpression(expr) ?: return expr
                }
                SyntaxKind.ExclamationToken -> {
                    eatToken()
                    expr = expr.wrap(SyntaxKind.SuppressNullableWarningExpression)
                }
                else -> return expr
            }
        }
    }

    /** `IsPossibleDeconstructionLeft` (LP 12295) */
    fun isPossibleDeconstructionLeft(precedence: Int): Boolean {
        if (precedence > Precedence.Assignment || !(isIdentifierVar() || CSharpSyntaxFacts.isPredefinedType(currentKind))) return false
        return speculate {
            eatToken()
            currentKind === SyntaxKind.OpenParenToken && scanDesignator() && currentKind === SyntaxKind.EqualsToken
        }
    }

    /** `SyntaxToken.IsIdentifierVar()` for the current token. */
    fun isIdentifierVar(): Boolean = currentToken.let { it.kind === SyntaxKind.IdentifierToken && it.contextualKind === SyntaxKind.VarKeyword }

    /** `ScanDesignator` (LP 12310) */
    private fun scanDesignator(): Boolean {
        when (currentKind) {
            SyntaxKind.IdentifierToken -> {
                if (!isTrueIdentifier()) return false
                eatToken()
                return true
            }
            SyntaxKind.OpenParenToken -> {
                while (true) {
                    eatToken()
                    if (!scanDesignator()) return false
                    when (currentKind) {
                        SyntaxKind.CommaToken -> continue
                        SyntaxKind.CloseParenToken -> {
                            eatToken()
                            return true
                        }
                        else -> return false
                    }
                }
            }
            else -> return false
        }
    }

    /** `IsPossibleAnonymousMethodExpression` (LP 12347) */
    fun isPossibleAnonymousMethodExpression(): Boolean {
        var tokenIndex = 0
        while (peekToken(tokenIndex).kind === SyntaxKind.StaticKeyword || peekToken(tokenIndex).contextualKind === SyntaxKind.AsyncKeyword) tokenIndex++
        return peekToken(tokenIndex).kind === SyntaxKind.DelegateKeyword && peekToken(tokenIndex + 1).kind !== SyntaxKind.AsteriskToken
    }

    /** `TryParseConditionalAccessExpression` (LP 12368) */
    private fun tryParseConditionalAccessExpression(primaryExpression: Node): Node? {
        if (currentKind !== SyntaxKind.QuestionToken) return null
        val nextToken = peekToken(1)
        val binding: Node
        if (nextToken.kind === SyntaxKind.DotToken && !isAtDotDotToken(nextToken, peekToken(2))) {
            eatToken() // ?
            val m = open()
            eatToken() // .
            parseSimpleName(NameOptions.InExpression)
            binding = m.done(SyntaxKind.MemberBindingExpression)
        } else if (isStartOfElementBindingExpression(nextToken.kind)) {
            eatToken() // ?
            val m = open()
            parseBracketedArgumentList()
            binding = m.done(SyntaxKind.ElementBindingExpression)
        } else {
            return null
        }
        parseWhenNotNull(binding)
        return primaryExpression.wrap(SyntaxKind.ConditionalAccessExpression)
    }

    /** local `isStartOfElementBindingExpression` (LP 12425) */
    private fun isStartOfElementBindingExpression(nextTokenKind: IElementType): Boolean {
        if (nextTokenKind !== SyntaxKind.OpenBracketToken) return false
        if (forceConditionalAccessExpression) return true
        return speculate {
            eatToken()
            parsePossibleRefExpression()
            currentKind !== SyntaxKind.ColonToken
        }
    }

    /**
     * local `parseWhenNotNull` (LP 12447). Roslyn eats the `!`s and rolls them back when nothing follows; a rollback
     * cannot undo a `precede()` on the receiver here, so the `!`s are only eaten once the lookahead shows that an
     * access, a conditional access or an assignment follows them (docs/csharp-psi/GRAMMAR.md).
     */
    private fun parseWhenNotNull(start: Node): Node {
        var expr = start
        while (true) {
            var bangs = 0
            while (peekToken(bangs).kind === SyntaxKind.ExclamationToken) bangs++
            val afterBangs = peekToken(bangs).kind
            val dependentAccess = afterBangs === SyntaxKind.OpenParenToken || afterBangs === SyntaxKind.OpenBracketToken || afterBangs === SyntaxKind.DotToken
            val conditionalAccess = !dependentAccess && afterBangs === SyntaxKind.QuestionToken && speculate {
                repeat(bangs) { eatToken() }
                conditionalAccessFollows()
            }
            val assignment = !dependentAccess && !conditionalAccess && speculate {
                repeat(bangs) { eatToken() }
                val (operatorTokenKind, _) = getExpressionOperatorTokenKindAndExpressionKind()
                operatorTokenKind != null && isExpectedAssignmentOperator(operatorTokenKind)
            }
            if (!dependentAccess && !conditionalAccess && !assignment) return expr // trailing `!`s belong to the parent

            repeat(bangs) {
                eatToken()
                expr = expr.wrap(SyntaxKind.SuppressNullableWarningExpression)
            }
            if (dependentAccess) {
                expr = tryParseDependentAccess(expr)!!
                continue
            }
            if (conditionalAccess) return tryParseConditionalAccessExpression(expr)!!
            val (operatorTokenKind, operatorExpressionKind) = getExpressionOperatorTokenKindAndExpressionKind()
            eatExpressionOperatorToken(operatorTokenKind!!)
            return parseAssignmentExpression(operatorExpressionKind!!, expr)
        }
    }

    /** The test of `TryParseConditionalAccessExpression` without building anything: `?` followed by `.name` or `[...]` binding. */
    private fun conditionalAccessFollows(): Boolean {
        if (currentKind !== SyntaxKind.QuestionToken) return false
        val nextToken = peekToken(1)
        if (nextToken.kind === SyntaxKind.DotToken && !isAtDotDotToken(nextToken, peekToken(2))) return true
        if (nextToken.kind !== SyntaxKind.OpenBracketToken) return false
        if (forceConditionalAccessExpression) return true
        return isStartOfElementBindingExpression(SyntaxKind.OpenBracketToken) // eats the `?` itself
    }

    /** local `tryParseDependentAccess` (LP 12495) */
    private fun tryParseDependentAccess(expr: Node): Node? = when (currentKind) {
        SyntaxKind.OpenParenToken -> {
            parseParenthesizedArgumentList()
            expr.wrap(SyntaxKind.InvocationExpression)
        }
        SyntaxKind.OpenBracketToken -> {
            parseBracketedArgumentList()
            expr.wrap(SyntaxKind.ElementAccessExpression)
        }
        SyntaxKind.DotToken -> {
            eatToken()
            parseSimpleName(NameOptions.InExpression)
            expr.wrap(SyntaxKind.SimpleMemberAccessExpression)
        }
        else -> null
    }

    // ---- argument lists (LP 12507-12670) --------------------------------------------------------------------------

    /** `ParseParenthesizedArgumentList` (LP 12507) */
    fun parseParenthesizedArgumentList(): Node {
        val m = open()
        parseArgumentList(SyntaxKind.OpenParenToken, SyntaxKind.CloseParenToken)
        return m.done(SyntaxKind.ArgumentList)
    }

    /** `ParseBracketedArgumentList` (LP 12523) */
    fun parseBracketedArgumentList(): Node {
        val m = open()
        parseArgumentList(SyntaxKind.OpenBracketToken, SyntaxKind.CloseBracketToken)
        return m.done(SyntaxKind.BracketedArgumentList)
    }

    /** `ParseArgumentList` (LP 12539) */
    private fun parseArgumentList(openKind: IElementType, closeKind: IElementType) {
        val isIndexer = openKind === SyntaxKind.OpenBracketToken
        if (currentKind === SyntaxKind.OpenParenToken || currentKind === SyntaxKind.OpenBracketToken) eatTokenAsKind(openKind) else eatToken(openKind)

        val saveTerm = termState
        termState = termState or TerminatorState.IsEndOfArgumentList

        if (currentKind !== closeKind && currentKind !== SyntaxKind.SemicolonToken) {
            parseCommaSeparatedSyntaxList(
                closeKind,
                isPossibleElement = { isPossibleArgumentExpression() },
                parseElement = { parseArgumentExpression(isIndexer) },
                skipBadTokens = { expected, close -> skipBadArgumentListTokens(expected, close) },
                allowTrailingSeparator = false,
                requireOneElement = false,
                allowSemicolonAsSeparator = false,
            )
        } else if (isIndexer && currentKind === closeKind) {
            parseArgumentExpression(isIndexer)
        }

        termState = saveTerm

        if (currentKind === SyntaxKind.CloseParenToken || currentKind === SyntaxKind.CloseBracketToken) eatTokenAsKind(closeKind) else eatToken(closeKind)
    }

    private fun skipBadArgumentListTokens(expectedKind: IElementType, closeKind: IElementType): PostSkipAction {
        if (currentKind === SyntaxKind.CloseParenToken || currentKind === SyntaxKind.CloseBracketToken || currentKind === SyntaxKind.SemicolonToken) return PostSkipAction.Abort
        return skipBadSeparatedListTokensWithExpectedKind(
            isNotExpected = { currentKind !== SyntaxKind.CommaToken && !isPossibleArgumentExpression() },
            abort = { close -> currentKind === close || currentKind === SyntaxKind.SemicolonToken },
            expectedKind, closeKind,
        )
    }

    /** `IsPossibleArgumentExpression` (LP 12627) */
    fun isPossibleArgumentExpression(): Boolean = isValidArgumentRefKindKeyword(currentKind) || isPossibleExpression()

    private fun isValidArgumentRefKindKeyword(kind: IElementType): Boolean =
        kind === SyntaxKind.RefKeyword || kind === SyntaxKind.OutKeyword || kind === SyntaxKind.InKeyword

    /** `ParseArgumentExpression` (LP 12645) */
    fun parseArgumentExpression(isIndexer: Boolean): Node {
        val m = open()
        if (currentKind === SyntaxKind.IdentifierToken && peekToken(1).kind === SyntaxKind.ColonToken) {
            val nc = open()
            parseIdentifierName()
            eatToken(SyntaxKind.ColonToken)
            nc.done(SyntaxKind.NameColon)
        }

        var refKind: IElementType? = null
        if (isValidArgumentRefKindKeyword(currentKind) && !(currentKind === SyntaxKind.RefKeyword && isPossibleLambdaExpression(Precedence.Expression))) {
            refKind = currentKind
            eatToken()
        }

        if (isIndexer && (currentKind === SyntaxKind.CommaToken || currentKind === SyntaxKind.CloseBracketToken)) {
            parseIdentifierName(CSharpErrorCode.ERR_ValueExpected.describe())
        } else if (currentKind === SyntaxKind.CommaToken) {
            parseIdentifierName(CSharpErrorCode.ERR_MissingArgument.describe())
        } else if (refKind === SyntaxKind.OutKeyword) {
            parseExpressionOrDeclaration(ParseTypeMode.Normal, permitTupleDesignation = false)
        } else {
            parseSubExpression(Precedence.Expression)
        }
        return m.done(SyntaxKind.Argument)
    }

    // ---- typeof, default, sizeof, checked, ... (LP 12688-12775) ----------------------------------------------------

    /** `ParseTypeOfExpression` (LP 12688) */
    private fun parseTypeOfExpression(): Node {
        val m = open()
        eatToken()
        eatToken(SyntaxKind.OpenParenToken)
        parseTypeOrVoid()
        eatToken(SyntaxKind.CloseParenToken)
        return m.done(SyntaxKind.TypeOfExpression)
    }

    /** `ParseDefaultExpression` (LP 12697) */
    private fun parseDefaultExpression(): Node {
        val m = open()
        eatToken()
        if (currentKind === SyntaxKind.OpenParenToken) {
            eatToken(SyntaxKind.OpenParenToken)
            parseType()
            eatToken(SyntaxKind.CloseParenToken)
            return m.done(SyntaxKind.DefaultExpression)
        }
        return m.done(SyntaxKind.DefaultLiteralExpression)
    }

    /** `ParseSizeOfExpression` (LP 12714) */
    private fun parseSizeOfExpression(): Node {
        val m = open()
        eatToken()
        eatToken(SyntaxKind.OpenParenToken)
        parseType()
        eatToken(SyntaxKind.CloseParenToken)
        return m.done(SyntaxKind.SizeOfExpression)
    }

    /** `ParseMakeRefExpression`, `ParseRefTypeExpression`, `ParseCheckedOrUncheckedExpression`, `ParseUnsafeExpression` (LP 12723-12763). */
    private fun parseKeywordParenthesizedExpression(kind: IElementType): Node {
        val m = open()
        eatToken()
        eatToken(SyntaxKind.OpenParenToken)
        parseExpressionForParenthesizedConstruct()
        eatToken(SyntaxKind.CloseParenToken)
        return m.done(kind)
    }

    /** `ParseRefValueExpression` (LP 12764) */
    private fun parseRefValueExpression(): Node {
        val m = open()
        eatToken(SyntaxKind.RefValueKeyword)
        eatToken(SyntaxKind.OpenParenToken)
        parseSubExpression(Precedence.Expression)
        eatToken(SyntaxKind.CommaToken)
        parseType()
        eatToken(SyntaxKind.CloseParenToken)
        return m.done(SyntaxKind.RefValueExpression)
    }

    /** `ParseExpressionForParenthesizedConstruct` (LP 10044) */
    fun parseExpressionForParenthesizedConstruct(): Node = parseErrantExpressionWhenNoCloseParenToken(parseExpressionCore())

    /** `ParseErrantExpressionWhenNoCloseParenToken` (LP 10047): `(a b)` → `b` skipped. */
    fun parseErrantExpressionWhenNoCloseParenToken(expression: Node): Node {
        if (currentKind !== SyntaxKind.CloseParenToken) {
            // Roslyn demotes the second expression to skipped syntax: parse to measure, roll back, skip the tokens.
            withResetPoint { rp ->
                val startRaw = position
                val next = parseExpressionCore()
                val accept = currentKind === SyntaxKind.CloseParenToken && peekToken(1).kind !== SyntaxKind.EqualsGreaterThanToken && !next.lastTokenMissing && position > startRaw
                val endRaw = position
                rp.reset()
                if (accept) {
                    // `AddErrorToFirstToken(nextExpression, ERR_UnexpectedToken, first token's text)` (LP 10066)
                    val message = CSharpErrorCode.ERR_UnexpectedToken.describe(currentText, anchor = CSharpDiagnosticAnchor.FIRST)
                    val skipped = builder.mark()
                    while (position < endRaw && advance()) { /* eat */ }
                    skipped.error(message)
                    countSkippedError()
                }
            }
        }
        return expression
    }

    // ---- lambda scanning (LP 12775-12890, 13118-13243) -------------------------------------------------------------

    /** `ScanParenthesizedLambda` (LP 12775) */
    private fun scanParenthesizedLambda(precedence: Int): Boolean =
        scanImplicitlyTypedLambdaOrSimpleExplicitlyTypedParenthesizedLambda(precedence) || scanExplicitlyTypedLambda(precedence)

    /** `ScanImplicitlyTypedLambdaOrSimpleExplicitlyTypedParenthesizedLambda` (LP 12785) */
    private fun scanImplicitlyTypedLambdaOrSimpleExplicitlyTypedParenthesizedLambda(precedence: Int): Boolean {
        if (precedence > Precedence.Lambda) return false
        var index = 1
        while (true) {
            val token = peekToken(index++)
            if (isTrueIdentifier(token) || token.kind === SyntaxKind.CommaToken || isParameterModifierIncludingScoped(token)) continue
            return token.kind === SyntaxKind.CloseParenToken && peekToken(index).kind === SyntaxKind.EqualsGreaterThanToken
        }
    }

    /** `ScanExplicitlyTypedLambda` (LP 12816) */
    private fun scanExplicitlyTypedLambda(precedence: Int): Boolean {
        if (precedence > Precedence.Lambda) return false
        return speculate {
            var result: Boolean? = null
            while (result == null) {
                eatToken() // ( or ,
                parseAttributeDeclarations(inExpressionContext = true)
                if (isParameterModifierIncludingScoped(currentToken)) parseParameterModifiers(isFunctionPointerParameter = false, isLambdaParameter = true)
                if (shouldParseLambdaParameterType() && scanType() == ScanTypeFlags.NotType) {
                    result = false
                    break
                }
                if (isTrueIdentifier()) eatToken()
                if (tryEatToken(SyntaxKind.EqualsToken)) {
                    if (currentKind === SyntaxKind.OpenBracketToken) {
                        result = false
                        break
                    }
                    parseExpressionCore()
                }
                result = when (currentKind) {
                    SyntaxKind.CommaToken -> null
                    SyntaxKind.CloseParenToken -> peekToken(1).kind === SyntaxKind.EqualsGreaterThanToken
                    else -> false
                }
            }
            result == true
        }
    }

    /** `IsPossibleLambdaExpression` (LP 13118) */
    fun isPossibleLambdaExpression(precedence: Int): Boolean {
        if (precedence > Precedence.Lambda) return false
        if (peekToken(1).kind === SyntaxKind.EqualsGreaterThanToken) return true

        return speculate {
            var result: Boolean? = null
            if (currentKind === SyntaxKind.OpenBracketToken) {
                val attrs = parseAttributeDeclarations(inExpressionContext = true)
                if (attrs.count > 0 && attrs.lastCloseBracketMissing) result = false
            }
            if (result == null) {
                var seenStatic = false
                if (currentKind === SyntaxKind.StaticKeyword) {
                    eatToken()
                    seenStatic = true
                } else if (currentContextualKind === SyntaxKind.AsyncKeyword && peekToken(1).kind === SyntaxKind.StaticKeyword) {
                    eatToken()
                    eatToken()
                    seenStatic = true
                }
                if (seenStatic && (currentKind === SyntaxKind.EqualsGreaterThanToken || currentKind === SyntaxKind.OpenParenToken)) {
                    result = true
                } else if (currentKind === SyntaxKind.IdentifierToken && peekToken(1).kind === SyntaxKind.EqualsGreaterThanToken) {
                    result = true
                } else {
                    if (currentContextualKind === SyntaxKind.AsyncKeyword && isAnonymousFunctionAsyncModifier()) eatToken()
                    withResetPoint { nested ->
                        val st = scanType()
                        if (st == ScanTypeFlags.NotType || currentKind !== SyntaxKind.OpenParenToken) nested.reset()
                    }
                    result = if (currentKind === SyntaxKind.IdentifierToken && peekToken(1).kind === SyntaxKind.EqualsGreaterThanToken) true
                    else if (currentKind !== SyntaxKind.OpenParenToken) false
                    else scanParenthesizedLambda(precedence)
                }
            }
            result!!
        }
    }

    // ---- parentheses, casts, tuples (LP 12892-13111, 13245) --------------------------------------------------------

    /** `ParseCastOrParenExpressionOrTuple` (LP 12892) */
    private fun parseCastOrParenExpressionOrTuple(): Node {
        // A parenthesis level costs about twice the frames of an operand level (SyntaxParser.MAX_DEPTH).
        recursionDepth++
        try {
            return parseCastOrParenExpressionOrTupleCore()
        } finally {
            recursionDepth--
        }
    }

    private fun parseCastOrParenExpressionOrTupleCore(): Node {
        val isCast = speculate { scanCast(forPattern = false, inSwitchArmPattern = false) && !isCurrentTokenQueryKeywordInQuery() }
        if (isCast) {
            val m = open()
            eatToken(SyntaxKind.OpenParenToken)
            parseType()
            eatToken(SyntaxKind.CloseParenToken)
            parseSubExpression(Precedence.Cast)
            return m.done(SyntaxKind.CastExpression)
        }

        val m = open()
        eatToken(SyntaxKind.OpenParenToken)
        val expression = parseExpressionOrDeclaration(ParseTypeMode.FirstElementOfPossibleTupleLiteral, permitTupleDesignation = true)

        if (currentKind === SyntaxKind.CommaToken) {
            expression.wrap(SyntaxKind.Argument)
            return parseTupleExpressionTail(m)
        }

        if (expression.kind === SyntaxKind.IdentifierName && currentKind === SyntaxKind.ColonToken) {
            // `( name:` — the identifier becomes the NameColon of the first argument (hard spot 7.4: precede()).
            val arg = expression.precede()
            val nc = expression.precede()
            eatToken()
            nc.done(SyntaxKind.NameColon)
            parseExpressionOrDeclaration(ParseTypeMode.FirstElementOfPossibleTupleLiteral, permitTupleDesignation = true)
            arg.done(SyntaxKind.Argument)
            return parseTupleExpressionTail(m)
        }

        parseErrantExpressionWhenNoCloseParenToken(expression)
        eatToken(SyntaxKind.CloseParenToken)
        return m.done(SyntaxKind.ParenthesizedExpression)
    }

    /** `ParseTupleExpressionTail` (LP 12946); [open] is the marker before `(`, the first argument is already built. */
    private fun parseTupleExpressionTail(open: Open): Node {
        var count = 1
        while (currentKind === SyntaxKind.CommaToken) {
            eatToken(SyntaxKind.CommaToken)
            val argStart = open()
            val expression = parseExpressionOrDeclaration(ParseTypeMode.AfterTupleComma, permitTupleDesignation = true)
            if (expression.kind === SyntaxKind.IdentifierName && currentKind === SyntaxKind.ColonToken) {
                val nc = expression.precede()
                eatToken()
                nc.done(SyntaxKind.NameColon)
                parseExpressionOrDeclaration(ParseTypeMode.AfterTupleComma, permitTupleDesignation = true)
            }
            argStart.done(SyntaxKind.Argument)
            count++
        }
        if (count < 2) {
            createMissingToken(SyntaxKind.CommaToken, report = false)
            val arg = open()
            createMissingIdentifierName(CSharpErrorCode.ERR_TupleTooFewElements.describe())
            arg.done(SyntaxKind.Argument)
            errorCount++
        }
        eatToken(SyntaxKind.CloseParenToken)
        return open.done(SyntaxKind.TupleExpression)
    }

    /** `ScanCast` (LP 12980) */
    fun scanCast(forPattern: Boolean, inSwitchArmPattern: Boolean): Boolean {
        if (currentKind !== SyntaxKind.OpenParenToken) return false
        eatToken()
        val type = scanType(forPattern)
        if (type == ScanTypeFlags.NotType) return false
        if (currentKind !== SyntaxKind.CloseParenToken) return false
        eatToken()

        if (forPattern && currentKind === SyntaxKind.IdentifierToken) {
            if (isBinaryPattern()) return false
            if (inSwitchArmPattern && currentContextualKind === SyntaxKind.WhenKeyword) return false
            return true
        }

        return when (type) {
            ScanTypeFlags.PointerOrMultiplication, ScanTypeFlags.NullableType, ScanTypeFlags.MustBeType, ScanTypeFlags.AliasQualifiedName ->
                !forPattern || when (currentKind) {
                    SyntaxKind.PlusToken, SyntaxKind.MinusToken, SyntaxKind.AmpersandToken, SyntaxKind.AsteriskToken -> true
                    SyntaxKind.DotToken -> if (isAtDotDotToken()) true else canFollowCast(currentKind)
                    else -> canFollowCast(currentKind)
                }
            ScanTypeFlags.GenericTypeOrMethod, ScanTypeFlags.TupleType -> currentKind === SyntaxKind.OpenBracketToken || canFollowCast(currentKind)
            ScanTypeFlags.GenericTypeOrExpression, ScanTypeFlags.NonGenericTypeOrExpression -> {
                if (currentKind === SyntaxKind.OpenBracketToken && peekToken(1).kind === SyntaxKind.CloseBracketToken) true
                else canFollowCast(currentKind)
            }
        }
    }

    /** local `isBinaryPattern` of `ScanCast` (LP 13084) */
    private fun isBinaryPattern(): Boolean {
        fun isBinaryPatternKeyword() = currentContextualKind === SyntaxKind.OrKeyword || currentContextualKind === SyntaxKind.AndKeyword
        if (!isBinaryPatternKeyword()) return false
        var lastTokenIsBinaryOperator = true
        eatToken()
        while (isBinaryPatternKeyword()) {
            lastTokenIsBinaryOperator = !lastTokenIsBinaryOperator
            eatToken()
        }
        return lastTokenIsBinaryOperator == isPossibleSubpatternElement()
    }

    /** `CanFollowCast` (LP 13245) */
    private fun canFollowCast(kind: IElementType): Boolean = kind !in cannotFollowCast

    // ---- new, initializers, collection expressions, stackalloc (LP 13306-13793) ------------------------------------

    /** `ParseNewExpression` (LP 13306) */
    private fun parseNewExpression(): Node = when {
        isAnonymousType() -> parseAnonymousTypeExpression()
        isImplicitlyTypedArray() -> parseImplicitlyTypedArrayCreation()
        else -> parseArrayOrObjectCreationExpression()
    }

    /** `ParseCollectionExpression` (LP 13325) */
    fun parseCollectionExpression(): Node {
        val m = open()
        eatToken(SyntaxKind.OpenBracketToken)
        parseCommaSeparatedSyntaxList(
            SyntaxKind.CloseBracketToken,
            isPossibleElement = { isPossibleCollectionElement() },
            parseElement = { parseCollectionElement() },
            skipBadTokens = { expected, close ->
                skipBadSeparatedListTokensWithExpectedKind(
                    isNotExpected = { currentKind !== SyntaxKind.CommaToken && !isPossibleCollectionElement() },
                    abort = { c -> currentKind === c },
                    expected, close,
                )
            },
            allowTrailingSeparator = true,
            requireOneElement = false,
            allowSemicolonAsSeparator = false,
        )
        eatToken(SyntaxKind.CloseBracketToken)
        return m.done(SyntaxKind.CollectionExpression)
    }

    private fun isPossibleCollectionElement(): Boolean = isPossibleExpression()

    /** `ParseCollectionElement` (LP 13359) */
    private fun parseCollectionElement(): Node {
        val m = open()
        if (currentContextualKind === SyntaxKind.WithKeyword && peekToken(1).kind === SyntaxKind.OpenParenToken) {
            eatContextualToken(SyntaxKind.WithKeyword)
            parseParenthesizedArgumentList()
            return m.done(SyntaxKind.WithElement)
        }
        if (isAtDotDotToken()) {
            eatDotDotToken()
            parseExpressionCore()
            return m.done(SyntaxKind.SpreadElement)
        }
        parseExpressionCore()
        return m.done(SyntaxKind.ExpressionElement)
    }

    private fun isAnonymousType(): Boolean = currentKind === SyntaxKind.NewKeyword && peekToken(1).kind === SyntaxKind.OpenBraceToken

    /** `ParseAnonymousTypeExpression` (LP 13384) */
    private fun parseAnonymousTypeExpression(): Node {
        val m = open()
        eatToken(SyntaxKind.NewKeyword)
        eatToken(SyntaxKind.OpenBraceToken)
        parseCommaSeparatedSyntaxList(
            SyntaxKind.CloseBraceToken,
            isPossibleElement = { isPossibleExpression() },
            parseElement = { parseAnonymousTypeMemberInitializer() },
            skipBadTokens = { expected, close -> skipBadInitializerListTokens(expected, close) },
            allowTrailingSeparator = true,
            requireOneElement = false,
            allowSemicolonAsSeparator = false,
        )
        eatToken(SyntaxKind.CloseBraceToken)
        return m.done(SyntaxKind.AnonymousObjectCreationExpression)
    }

    /** `ParseAnonymousTypeMemberInitializer` (LP 13412) */
    private fun parseAnonymousTypeMemberInitializer(): Node {
        val m = open()
        if (isNamedAssignment()) parseNameEquals()
        parseExpressionCore()
        return m.done(SyntaxKind.AnonymousObjectMemberDeclarator)
    }

    /** `ParseNameEquals` (LP 950) */
    fun parseNameEquals(): Node {
        val m = open()
        val id = open()
        parseIdentifierToken()
        id.done(SyntaxKind.IdentifierName)
        eatToken(SyntaxKind.EqualsToken)
        return m.done(SyntaxKind.NameEquals)
    }

    private fun isInitializerMember(): Boolean = isComplexElementInitializer() || isNamedAssignment() || isDictionaryInitializer() || isPossibleExpression()
    private fun isComplexElementInitializer(): Boolean = currentKind === SyntaxKind.OpenBraceToken
    private fun isNamedAssignment(): Boolean = isTrueIdentifier() && peekToken(1).kind === SyntaxKind.EqualsToken
    private fun isNamedMemberInitializer(): Boolean = isTrueIdentifier() && peekToken(1).kind.let { it === SyntaxKind.EqualsToken || it === SyntaxKind.ColonToken }
    private fun isDictionaryInitializer(): Boolean = currentKind === SyntaxKind.OpenBracketToken

    /** `ParseArrayOrObjectCreationExpression` (LP 13444) */
    private fun parseArrayOrObjectCreationExpression(): Node {
        val m = open()
        eatToken(SyntaxKind.NewKeyword)
        var type: Node? = null
        if (!isImplicitObjectCreation()) {
            type = parseType(ParseTypeMode.NewExpression)
            if (type.kind === SyntaxKind.ArrayType) {
                if (currentKind === SyntaxKind.OpenBraceToken) parseArrayInitializer()
                return m.done(SyntaxKind.ArrayCreationExpression)
            }
        }
        var hasArgumentList = false
        var hasInitializer = false
        if (currentKind === SyntaxKind.OpenParenToken) {
            parseParenthesizedArgumentList()
            hasArgumentList = true
        }
        if (currentKind === SyntaxKind.OpenBraceToken) {
            parseObjectOrCollectionInitializer()
            hasInitializer = true
        }
        if (!hasArgumentList && !hasInitializer) {
            val al = open()
            eatToken(SyntaxKind.OpenParenToken, reportError = type != null && !type.containsErrors)
            createMissingToken(SyntaxKind.CloseParenToken, report = false)
            al.done(SyntaxKind.ArgumentList)
        }
        return m.done(if (type == null) SyntaxKind.ImplicitObjectCreationExpression else SyntaxKind.ObjectCreationExpression)
    }

    /** `IsImplicitObjectCreation` (LP 13491) */
    private fun isImplicitObjectCreation(): Boolean {
        if (currentKind !== SyntaxKind.OpenParenToken) return false
        return speculate {
            eatToken()
            val flags = scanTupleType()
            if (flags != ScanTypeFlags.NotType) {
                when (currentKind) {
                    SyntaxKind.QuestionToken, SyntaxKind.OpenBracketToken, SyntaxKind.OpenParenToken -> false
                    else -> true
                }
            } else {
                true
            }
        }
    }

    /** `ParseWithExpression` (LP 13519) */
    private fun parseWithExpression(receiver: Node): Node {
        val init = open()
        eatToken(SyntaxKind.OpenBraceToken)
        parseCommaSeparatedSyntaxList(
            SyntaxKind.CloseBraceToken,
            isPossibleElement = { isPossibleExpression() },
            parseElement = { parseExpressionCore() },
            skipBadTokens = { expected, close -> skipBadInitializerListTokens(expected, close) },
            allowTrailingSeparator = true,
            requireOneElement = false,
            allowSemicolonAsSeparator = false,
        )
        eatToken(SyntaxKind.CloseBraceToken)
        init.done(SyntaxKind.WithInitializerExpression)
        return receiver.wrap(SyntaxKind.WithExpression)
    }

    /** `ParseObjectOrCollectionInitializer` (LP 13544): the kind is decided after the members are parsed (delayed done). */
    fun parseObjectOrCollectionInitializer(): Node {
        val m = open()
        eatToken(SyntaxKind.OpenBraceToken)
        var sawNamedAssignment = false
        val count = parseCommaSeparatedSyntaxList(
            SyntaxKind.CloseBraceToken,
            isPossibleElement = { isInitializerMember() },
            parseElement = { if (parseObjectOrCollectionInitializerMember()) sawNamedAssignment = true },
            skipBadTokens = { expected, close -> skipBadInitializerListTokens(expected, close) },
            allowTrailingSeparator = true,
            requireOneElement = false,
            allowSemicolonAsSeparator = true,
        )
        eatToken(SyntaxKind.CloseBraceToken)
        val isObjectInitializer = count == 0 || sawNamedAssignment
        return m.done(if (isObjectInitializer) SyntaxKind.ObjectInitializerExpression else SyntaxKind.CollectionInitializerExpression)
    }

    /**
     * `ParseObjectOrCollectionInitializerMember` (LP 13590). Returns true for a member that makes the initializer an
     * object initializer: a simple assignment whose left side is an `IdentifierName` or `ImplicitElementAccess`.
     */
    private fun parseObjectOrCollectionInitializerMember(): Boolean {
        if (isComplexElementInitializer()) {
            parseComplexElementInitializer()
            return false
        }
        if (isDictionaryInitializer()) {
            parseDictionaryInitializer()
            return true
        }
        if (isNamedMemberInitializer()) {
            parseObjectInitializerNamedAssignment()
            return true
        }
        val expr = parsePossibleRefExpression()
        return expr.kind === SyntaxKind.SimpleAssignmentExpression && expr.left?.kind.let { it === SyntaxKind.IdentifierName || it === SyntaxKind.ImplicitElementAccess }
    }

    private fun skipBadInitializerListTokens(expectedKind: IElementType, closeKind: IElementType): PostSkipAction =
        skipBadSeparatedListTokensWithExpectedKind(
            isNotExpected = { currentKind !== SyntaxKind.CommaToken && !isPossibleExpression() },
            abort = { close -> currentKind === close },
            expectedKind, closeKind,
        )

    /** `ParseObjectInitializerNamedAssignment` (LP 13629) */
    private fun parseObjectInitializerNamedAssignment(): Node {
        val m = open()
        parseIdentifierName()
        if (currentKind === SyntaxKind.ColonToken) eatTokenAsKind(SyntaxKind.EqualsToken) else eatToken(SyntaxKind.EqualsToken)
        if (currentKind === SyntaxKind.OpenBraceToken) parseObjectOrCollectionInitializer() else parsePossibleRefExpression()
        return m.done(SyntaxKind.SimpleAssignmentExpression)
    }

    /** `ParseDictionaryInitializer` (LP 13642) */
    private fun parseDictionaryInitializer(): Node {
        val m = open()
        val access = open()
        parseBracketedArgumentList()
        access.done(SyntaxKind.ImplicitElementAccess)
        eatToken(SyntaxKind.EqualsToken)
        if (currentKind === SyntaxKind.OpenBraceToken) parseObjectOrCollectionInitializer() else parsePossibleRefExpression()
        return m.done(SyntaxKind.SimpleAssignmentExpression)
    }

    /** `ParseComplexElementInitializer` (LP 13653) */
    private fun parseComplexElementInitializer(): Node {
        val m = open()
        eatToken(SyntaxKind.OpenBraceToken)
        parseCommaSeparatedSyntaxList(
            SyntaxKind.CloseBraceToken,
            isPossibleElement = { isPossibleExpression() },
            parseElement = { parseExpressionCore() },
            skipBadTokens = { expected, close -> skipBadInitializerListTokens(expected, close) },
            allowTrailingSeparator = false,
            requireOneElement = false,
            allowSemicolonAsSeparator = false,
        )
        eatToken(SyntaxKind.CloseBraceToken)
        return m.done(SyntaxKind.ComplexElementInitializerExpression)
    }

    private fun isImplicitlyTypedArray(): Boolean = peekToken(1).kind === SyntaxKind.OpenBracketToken

    /** `ParseImplicitlyTypedArrayCreation` (LP 13680): sizes inside `new[...]` are errors, skipped. */
    private fun parseImplicitlyTypedArrayCreation(): Node {
        val m = open()
        eatToken(SyntaxKind.NewKeyword)
        eatToken(SyntaxKind.OpenBracketToken)
        val last = intArrayOf(-1)
        while (isMakingProgress(last)) {
            if (isPossibleExpression()) {
                // Roslyn parses the size and demotes it to skipped syntax of the `[` or of the last comma.
                skipAsError(CSharpErrorCode.ERR_InvalidArray.describe()) { parseExpressionCore() }
            }
            if (currentKind === SyntaxKind.CommaToken) {
                eatToken()
                continue
            }
            break
        }
        eatToken(SyntaxKind.CloseBracketToken)
        parseArrayInitializer()
        return m.done(SyntaxKind.ImplicitArrayCreationExpression)
    }

    /** `ParseArrayInitializer` (LP 13720) */
    fun parseArrayInitializer(): Node {
        val m = open()
        eatToken(SyntaxKind.OpenBraceToken)
        parseCommaSeparatedSyntaxList(
            SyntaxKind.CloseBraceToken,
            isPossibleElement = { isPossibleVariableInitializer() },
            parseElement = { parseVariableInitializer() },
            skipBadTokens = { expected, close ->
                skipBadSeparatedListTokensWithExpectedKind(
                    isNotExpected = { currentKind !== SyntaxKind.CommaToken && !isPossibleVariableInitializer() },
                    abort = { c -> currentKind === c },
                    expected, close,
                )
            },
            allowTrailingSeparator = true,
            requireOneElement = false,
            allowSemicolonAsSeparator = false,
        )
        eatToken(SyntaxKind.CloseBraceToken)
        return m.done(SyntaxKind.ArrayInitializerExpression)
    }

    /** `ParseVariableInitializer` (LP 5855) */
    fun parseVariableInitializer(): Node = if (currentKind === SyntaxKind.OpenBraceToken) parseArrayInitializer() else parseExpressionCore()

    /** `IsPossibleVariableInitializer` (LP 5862) */
    fun isPossibleVariableInitializer(): Boolean = currentKind === SyntaxKind.OpenBraceToken || isPossibleExpression()

    /** `ParseStackAllocExpression` (LP 13749) */
    private fun parseStackAllocExpression(): Node =
        if (isImplicitlyTypedArray()) parseImplicitlyTypedStackAllocExpression() else parseRegularStackAllocExpression()

    /** `ParseImplicitlyTypedStackAllocExpression` (LP 13755) */
    private fun parseImplicitlyTypedStackAllocExpression(): Node {
        val m = open()
        eatToken(SyntaxKind.StackAllocKeyword)
        eatToken(SyntaxKind.OpenBracketToken)
        val last = intArrayOf(-1)
        while (isMakingProgress(last)) {
            if (isPossibleExpression()) {
                // Roslyn parses the size and demotes it to skipped syntax of the `[`.
                skipAsError(CSharpErrorCode.ERR_InvalidStackAllocArray.describe()) { parseExpressionCore() }
            }
            if (currentKind === SyntaxKind.CommaToken) {
                skipTokens(1, CSharpErrorCode.ERR_InvalidStackAllocArray.describe())
                continue
            }
            break
        }
        eatToken(SyntaxKind.CloseBracketToken)
        parseArrayInitializer()
        return m.done(SyntaxKind.ImplicitStackAllocArrayCreationExpression)
    }

    /** `ParseRegularStackAllocExpression` (LP 13786) */
    private fun parseRegularStackAllocExpression(): Node {
        val m = open()
        eatToken(SyntaxKind.StackAllocKeyword)
        parseType()
        if (currentKind === SyntaxKind.OpenBraceToken) parseArrayInitializer()
        return m.done(SyntaxKind.StackAllocArrayCreationExpression)
    }

    // ---- anonymous methods and lambdas (LP 13795-14100) -----------------------------------------------------------

    /** `ParseAnonymousMethodExpression` (LP 13795) */
    private fun parseAnonymousMethodExpression(): Node {
        val m = open()
        val sawAsync = parseAnonymousFunctionModifiers()
        val savedAsync = isInAsync
        val savedForce = forceConditionalAccessExpression
        isInAsync = isInAsync || sawAsync
        forceConditionalAccessExpression = false
        try {
            eatToken(SyntaxKind.DelegateKeyword)
            if (currentKind === SyntaxKind.OpenParenToken) parseParenthesizedParameterList()
            if (currentKind !== SyntaxKind.OpenBraceToken) {
                val block = open()
                eatToken(SyntaxKind.OpenBraceToken)
                createMissingToken(SyntaxKind.CloseBraceToken, report = false)
                block.done(SyntaxKind.Block)
            } else {
                parseBodyBlock(errorSensitive = true)
            }
            return m.done(SyntaxKind.AnonymousMethodExpression)
        } finally {
            isInAsync = savedAsync
            forceConditionalAccessExpression = savedForce
        }
    }

    /** `ParseAnonymousFunctionModifiers` (LP 13847); returns whether `async` was among them. */
    private fun parseAnonymousFunctionModifiers(): Boolean {
        var sawAsync = false
        while (true) {
            if (currentKind === SyntaxKind.StaticKeyword) {
                eatToken(SyntaxKind.StaticKeyword)
                continue
            }
            if (currentContextualKind === SyntaxKind.AsyncKeyword && isAnonymousFunctionAsyncModifier()) {
                eatContextualToken(SyntaxKind.AsyncKeyword)
                sawAsync = true
                continue
            }
            break
        }
        return sawAsync
    }

    /** `IsAnonymousFunctionAsyncModifier` (LP 13872) */
    private fun isAnonymousFunctionAsyncModifier(): Boolean = when (val kind = peekToken(1).kind) {
        SyntaxKind.OpenParenToken, SyntaxKind.IdentifierToken, SyntaxKind.StaticKeyword, SyntaxKind.RefKeyword, SyntaxKind.DelegateKeyword -> true
        else -> CSharpSyntaxFacts.isPredefinedType(kind)
    }

    /** `TryParseLambdaExpression` (LP 13894): `x ? () => y :` is a conditional, not a lambda with a nullable return type. */
    private fun tryParseLambdaExpression(): Node? {
        return withResetPoint { rp ->
            val result = parseLambdaExpression()
            if (currentKind === SyntaxKind.ColonToken && result.kind === SyntaxKind.ParenthesizedLambdaExpression && result.returnType?.kind === SyntaxKind.NullableType) {
                rp.reset()
                null
            } else {
                result
            }
        }
    }

    /** `ParseLambdaExpression` (LP 13909) */
    fun parseLambdaExpression(): Node {
        val m = open()
        parseAttributeDeclarations(inExpressionContext = true)
        val sawAsync = parseAnonymousFunctionModifiers()
        val savedAsync = isInAsync
        val savedForce = forceConditionalAccessExpression
        isInAsync = isInAsync || sawAsync
        forceConditionalAccessExpression = false
        recursionDepth++ // a lambda level costs about twice the frames of an operand level (SyntaxParser.MAX_DEPTH)
        try {
            var returnType: Node? = null
            withResetPoint { rp ->
                returnType = parseReturnType()
                if (currentKind !== SyntaxKind.OpenParenToken) {
                    rp.reset()
                    returnType = null
                }
            }
            if (currentKind === SyntaxKind.OpenParenToken) {
                parseLambdaParameterList()
                eatToken(SyntaxKind.EqualsGreaterThanToken)
                parseLambdaBody()
                val node = m.done(SyntaxKind.ParenthesizedLambdaExpression)
                node.returnType = returnType
                return node
            }
            val p = open()
            if (currentKind !== SyntaxKind.IdentifierToken && peekToken(1).kind === SyntaxKind.EqualsGreaterThanToken) eatTokenAsKind(SyntaxKind.IdentifierToken)
            else parseIdentifierToken()
            p.done(SyntaxKind.Parameter)
            eatToken(SyntaxKind.EqualsGreaterThanToken)
            parseLambdaBody()
            return m.done(SyntaxKind.SimpleLambdaExpression)
        } finally {
            recursionDepth--
            isInAsync = savedAsync
            forceConditionalAccessExpression = savedForce
        }
    }

    /** `ParseLambdaBody` (LP 13963) */
    private fun parseLambdaBody() {
        if (currentKind === SyntaxKind.OpenBraceToken) parseBodyBlock(errorSensitive = true) else parsePossibleRefExpression()
    }

    /** `ParseLambdaParameterList` (LP 13968) */
    private fun parseLambdaParameterList(): Node {
        val m = open()
        eatToken(SyntaxKind.OpenParenToken)
        val saveTerm = termState
        termState = termState or TerminatorState.IsEndOfParameterList
        parseCommaSeparatedSyntaxList(
            SyntaxKind.CloseParenToken,
            isPossibleElement = { isPossibleLambdaParameter() },
            parseElement = { parseLambdaParameter() },
            skipBadTokens = { expected, close ->
                skipBadSeparatedListTokensWithExpectedKind(
                    isNotExpected = { currentKind !== SyntaxKind.CommaToken && !isPossibleLambdaParameter() },
                    abort = { c -> currentKind === c },
                    expected, close,
                )
            },
            allowTrailingSeparator = false,
            requireOneElement = false,
            allowSemicolonAsSeparator = false,
        )
        termState = saveTerm
        eatToken(SyntaxKind.CloseParenToken)
        return m.done(SyntaxKind.ParameterList)
    }

    /** `IsPossibleLambdaParameter` (LP 14001) */
    private fun isPossibleLambdaParameter(): Boolean = when (currentKind) {
        SyntaxKind.ParamsKeyword, SyntaxKind.ReadOnlyKeyword, SyntaxKind.RefKeyword, SyntaxKind.OutKeyword, SyntaxKind.InKeyword,
        SyntaxKind.OpenParenToken, SyntaxKind.OpenBracketToken,
        -> true
        SyntaxKind.IdentifierToken -> isTrueIdentifier()
        SyntaxKind.DelegateKeyword -> isFunctionPointerStart()
        else -> CSharpSyntaxFacts.isPredefinedType(currentKind)
    }

    /** `ParseLambdaParameter` (LP 14025) */
    private fun parseLambdaParameter(): Node {
        val m = open()
        parseAttributeDeclarations(inExpressionContext = false)
        if (isParameterModifierIncludingScoped(currentToken)) parseParameterModifiers(isFunctionPointerParameter = false, isLambdaParameter = true)
        if (shouldParseLambdaParameterType()) parseType(ParseTypeMode.Parameter)
        parseIdentifierToken()
        if (currentKind === SyntaxKind.EqualsToken) {
            val ev = open()
            eatToken()
            parseExpressionCore()
            ev.done(SyntaxKind.EqualsValueClause)
        }
        return m.done(SyntaxKind.Parameter)
    }

    /** `ShouldParseLambdaParameterType` (LP 14056) */
    private fun shouldParseLambdaParameterType(): Boolean {
        if (CSharpSyntaxFacts.isPredefinedType(currentKind)) return true
        if (currentKind === SyntaxKind.OpenParenToken) return true
        if (isFunctionPointerStart()) return true
        if (isTrueIdentifier(currentToken)) {
            val peek1 = peekToken(1).kind
            if (peek1 !== SyntaxKind.CommaToken && peek1 !== SyntaxKind.CloseParenToken && peek1 !== SyntaxKind.EqualsGreaterThanToken &&
                peek1 !== SyntaxKind.OpenBraceToken && peek1 !== SyntaxKind.EqualsToken
            ) return true
        }
        return false
    }

    /** `ParsePossibleRefExpression` (LP 4444) */
    fun parsePossibleRefExpression(): Node {
        if (currentKind === SyntaxKind.RefKeyword && !isPossibleLambdaExpression(Precedence.Expression)) {
            val m = open()
            eatToken()
            parseExpressionCore()
            return m.done(SyntaxKind.RefExpression)
        }
        return parseExpressionCore()
    }

    /**
     * Eats tokens up to a token of [stops] at bracket depth 0 (included with [includeStop]); with [stopAtUnbalancedClose]
     * before a closing bracket that has no opening one among the eaten tokens (the `}` of an enclosing block). The tokens become an error element on [m]; returns the
     * number eaten.
     */
    fun skipBalancedTokens(
        stops: Set<IElementType>, includeStop: Boolean, m: PsiBuilder.Marker?, message: String,
        stopAtUnbalancedClose: Boolean = false,
    ): Int {
        var depth = 0
        var count = 0
        while (!currentToken.isEof) {
            val k = currentKind
            if (depth == 0 && k in stops) {
                if (includeStop) {
                    advance()
                    count++
                }
                break
            }
            when (k) {
                SyntaxKind.OpenParenToken, SyntaxKind.OpenBracketToken, SyntaxKind.OpenBraceToken -> depth++
                SyntaxKind.CloseParenToken, SyntaxKind.CloseBracketToken, SyntaxKind.CloseBraceToken -> {
                    if (stopAtUnbalancedClose && depth == 0) break
                    depth--
                }
            }
            advance()
            count++
        }
        if (m != null) {
            if (count > 0) {
                m.error(message)
                countSkippedError()
            } else {
                m.drop()
            }
        }
        return count
    }

    companion object {
        private val invalidSubExpressionStarts = setOf(
            SyntaxKind.BreakKeyword, SyntaxKind.CaseKeyword, SyntaxKind.CatchKeyword, SyntaxKind.ConstKeyword, SyntaxKind.ContinueKeyword,
            SyntaxKind.DoKeyword, SyntaxKind.FinallyKeyword, SyntaxKind.ForKeyword, SyntaxKind.ForEachKeyword, SyntaxKind.GotoKeyword,
            SyntaxKind.IfKeyword, SyntaxKind.ElseKeyword, SyntaxKind.LockKeyword, SyntaxKind.ReturnKeyword, SyntaxKind.SwitchKeyword,
            SyntaxKind.TryKeyword, SyntaxKind.UsingKeyword, SyntaxKind.WhileKeyword,
        )

        private val cannotFollowCast = setOf(
            SyntaxKind.AsKeyword, SyntaxKind.IsKeyword, SyntaxKind.SemicolonToken, SyntaxKind.CloseParenToken, SyntaxKind.CloseBracketToken,
            SyntaxKind.OpenBraceToken, SyntaxKind.CloseBraceToken, SyntaxKind.CommaToken, SyntaxKind.EqualsToken, SyntaxKind.PlusEqualsToken,
            SyntaxKind.MinusEqualsToken, SyntaxKind.AsteriskEqualsToken, SyntaxKind.SlashEqualsToken, SyntaxKind.PercentEqualsToken,
            SyntaxKind.AmpersandEqualsToken, SyntaxKind.CaretEqualsToken, SyntaxKind.BarEqualsToken, SyntaxKind.LessThanLessThanEqualsToken,
            SyntaxKind.GreaterThanGreaterThanEqualsToken, SyntaxKind.GreaterThanGreaterThanGreaterThanEqualsToken, SyntaxKind.QuestionToken,
            SyntaxKind.ColonToken, SyntaxKind.BarBarToken, SyntaxKind.AmpersandAmpersandToken, SyntaxKind.BarToken, SyntaxKind.CaretToken,
            SyntaxKind.AmpersandToken, SyntaxKind.EqualsEqualsToken, SyntaxKind.ExclamationEqualsToken, SyntaxKind.LessThanToken,
            SyntaxKind.LessThanEqualsToken, SyntaxKind.GreaterThanToken, SyntaxKind.GreaterThanEqualsToken, SyntaxKind.QuestionQuestionEqualsToken,
            SyntaxKind.LessThanLessThanToken, SyntaxKind.GreaterThanGreaterThanToken, SyntaxKind.GreaterThanGreaterThanGreaterThanToken,
            SyntaxKind.PlusToken, SyntaxKind.MinusToken, SyntaxKind.AsteriskToken, SyntaxKind.SlashToken, SyntaxKind.PercentToken,
            SyntaxKind.PlusPlusToken, SyntaxKind.MinusMinusToken, SyntaxKind.OpenBracketToken, SyntaxKind.DotToken,
            SyntaxKind.MinusGreaterThanToken, SyntaxKind.QuestionQuestionToken, SyntaxKind.EndOfFileToken, SyntaxKind.SwitchKeyword,
            SyntaxKind.EqualsGreaterThanToken,
        )

        private val rightAssociative = setOf(
            SyntaxKind.SimpleAssignmentExpression, SyntaxKind.AddAssignmentExpression, SyntaxKind.SubtractAssignmentExpression,
            SyntaxKind.MultiplyAssignmentExpression, SyntaxKind.DivideAssignmentExpression, SyntaxKind.ModuloAssignmentExpression,
            SyntaxKind.AndAssignmentExpression, SyntaxKind.ExclusiveOrAssignmentExpression, SyntaxKind.OrAssignmentExpression,
            SyntaxKind.LeftShiftAssignmentExpression, SyntaxKind.RightShiftAssignmentExpression,
            SyntaxKind.UnsignedRightShiftAssignmentExpression, SyntaxKind.CoalesceAssignmentExpression, SyntaxKind.CoalesceExpression,
        )

        /** `IsRightAssociative` (LP 11237) */
        fun isRightAssociative(op: IElementType): Boolean = op in rightAssociative

        private val precedences: Map<IElementType, Int> = HashMap<IElementType, Int>().apply {
            fun put(p: Int, vararg kinds: IElementType) = kinds.forEach { this[it] = p }
            put(Precedence.Expression, SyntaxKind.QueryExpression, SyntaxKind.ConditionalExpression)
            put(Precedence.Lambda, SyntaxKind.ParenthesizedLambdaExpression, SyntaxKind.SimpleLambdaExpression, SyntaxKind.AnonymousMethodExpression)
            put(
                Precedence.Assignment, SyntaxKind.SimpleAssignmentExpression, SyntaxKind.AddAssignmentExpression,
                SyntaxKind.SubtractAssignmentExpression, SyntaxKind.MultiplyAssignmentExpression, SyntaxKind.DivideAssignmentExpression,
                SyntaxKind.ModuloAssignmentExpression, SyntaxKind.AndAssignmentExpression, SyntaxKind.ExclusiveOrAssignmentExpression,
                SyntaxKind.OrAssignmentExpression, SyntaxKind.LeftShiftAssignmentExpression, SyntaxKind.RightShiftAssignmentExpression,
                SyntaxKind.UnsignedRightShiftAssignmentExpression, SyntaxKind.CoalesceAssignmentExpression,
            )
            put(Precedence.Coalescing, SyntaxKind.CoalesceExpression, SyntaxKind.ThrowExpression)
            put(Precedence.ConditionalOr, SyntaxKind.LogicalOrExpression)
            put(Precedence.ConditionalAnd, SyntaxKind.LogicalAndExpression)
            put(Precedence.LogicalOr, SyntaxKind.BitwiseOrExpression)
            put(Precedence.LogicalXor, SyntaxKind.ExclusiveOrExpression)
            put(Precedence.LogicalAnd, SyntaxKind.BitwiseAndExpression)
            put(Precedence.Equality, SyntaxKind.EqualsExpression, SyntaxKind.NotEqualsExpression)
            put(
                Precedence.Relational, SyntaxKind.LessThanExpression, SyntaxKind.LessThanOrEqualExpression, SyntaxKind.GreaterThanExpression,
                SyntaxKind.GreaterThanOrEqualExpression, SyntaxKind.IsExpression, SyntaxKind.AsExpression, SyntaxKind.IsPatternExpression,
            )
            put(Precedence.Switch, SyntaxKind.SwitchExpression, SyntaxKind.WithExpression)
            put(Precedence.Shift, SyntaxKind.LeftShiftExpression, SyntaxKind.RightShiftExpression, SyntaxKind.UnsignedRightShiftExpression)
            put(Precedence.Additive, SyntaxKind.AddExpression, SyntaxKind.SubtractExpression)
            put(Precedence.Multiplicative, SyntaxKind.MultiplyExpression, SyntaxKind.DivideExpression, SyntaxKind.ModuloExpression)
            put(
                Precedence.Unary, SyntaxKind.UnaryPlusExpression, SyntaxKind.UnaryMinusExpression, SyntaxKind.BitwiseNotExpression,
                SyntaxKind.LogicalNotExpression, SyntaxKind.PreIncrementExpression, SyntaxKind.PreDecrementExpression,
                SyntaxKind.TypeOfExpression, SyntaxKind.SizeOfExpression, SyntaxKind.CheckedExpression, SyntaxKind.UncheckedExpression,
                SyntaxKind.UnsafeExpression, SyntaxKind.MakeRefExpression, SyntaxKind.RefValueExpression, SyntaxKind.RefTypeExpression,
                SyntaxKind.AwaitExpression, SyntaxKind.IndexExpression,
            )
            put(Precedence.Cast, SyntaxKind.CastExpression)
            put(Precedence.PointerIndirection, SyntaxKind.PointerIndirectionExpression)
            put(Precedence.AddressOf, SyntaxKind.AddressOfExpression)
            put(Precedence.Range, SyntaxKind.RangeExpression)
        }

        /** `GetPrecedence` (LP 11287); every other expression kind is `Primary`. */
        fun getPrecedence(op: IElementType): Int = precedences[op] ?: Precedence.Primary

        /** `IsExpectedPrefixUnaryOperator` (LP 11420) */
        fun isExpectedPrefixUnaryOperator(kind: IElementType): Boolean =
            CSharpSyntaxFacts.isPrefixUnaryExpression(kind) && kind !== SyntaxKind.RefKeyword && kind !== SyntaxKind.OutKeyword

        fun isExpectedBinaryOperator(kind: IElementType): Boolean = CSharpSyntaxFacts.isBinaryExpression(kind)
        fun isExpectedAssignmentOperator(kind: IElementType): Boolean = CSharpSyntaxFacts.isAssignmentExpressionOperatorToken(kind)
    }
}
