// Ported from Roslyn: src/Compilers/CSharp/Portable/Parser/LanguageParser_Patterns.cs (whole file: patterns, switch
// expressions, list patterns), roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c.
// Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
//
// Roslyn converts built nodes between types, expressions and patterns (ConvertExpressionToType, ConvertTypeToExpression,
// ConvertPatternToExpressionIfPossible). A PsiBuilder node cannot change kind, so every conversion here is a reset to
// the start of the construct and a reparse in the other shape (docs/csharp-psi/GRAMMAR.md, "Patterns").
package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpErrorCode

/**
 * `ParseTypeOrPatternForIsOperator` (LPP 14): parses the right side of `is`; returns true when it is a type
 * (`IsExpression`), false when it is a pattern (`IsPatternExpression`). A constant pattern whose expression is a name,
 * a type pattern and a discard are reparsed as a type.
 */
fun LanguageParser.parseTypeOrPatternForIsOperator(): Boolean {
    val rp = getResetPoint()
    try {
        val pattern = parsePattern(LanguageParser.getPrecedence(SyntaxKind.IsPatternExpression), afterIs = true)
        val asType = when (pattern.kind) {
            SyntaxKind.ConstantPattern -> pattern.inner?.isNameShaped == true
            SyntaxKind.TypePattern, SyntaxKind.DiscardPattern -> true
            else -> false
        }
        if (!asType) return false
        rp.reset()
        when {
            pattern.kind === SyntaxKind.DiscardPattern -> parseIdentifierName()
            pattern.kind === SyntaxKind.ConstantPattern && !CSharpSyntaxFacts.isPredefinedType(currentKind) && !isTrueIdentifier() &&
                currentKind !== SyntaxKind.ColonColonToken -> {
                // The constant was a member access on the missing name of an invalid term (`x is .A`): `ConvertExpressionToType`
                // (LPP 33) makes it `QualifiedName(missing, ., A)`, which `ParseType` would not parse (it stops at the `.`).
                var name = addError(createMissingIdentifierName(CSharpErrorCode.ERR_InvalidExprTerm.describe(CSharpSyntaxFacts.getText(currentKind))))!!
                while (currentKind === SyntaxKind.DotToken) name = parseQualifiedNameRight(NameOptions.InExpression, name, asExpression = false)
            }
            else -> parseType(ParseTypeMode.AfterIs)
        }
        return true
    } finally {
        rp.release()
    }
}

/** `ParsePattern(precedence, afterIs, inSwitchArmPattern)` (LPP 53) */
fun LanguageParser.parsePattern(precedence: Int, afterIs: Boolean = false, inSwitchArmPattern: Boolean = false): Node {
    // Departure: Roslyn has no guard here (patterns recurse through ParseSubExpression only for constants); nested
    // `(`-patterns recurse without it, so the depth guard is applied here too, weighted by the frames a pattern level
    // takes (SyntaxParser.MAX_DEPTH).
    recursionDepth += PATTERN_DEPTH_WEIGHT
    try {
        if (recursionDepth > SyntaxParser.MAX_DEPTH) {
            val m = open()
            createMissingIdentifierName(CSharpErrorCode.ERR_InsufficientStack.describe())
            return m.done(SyntaxKind.ConstantPattern)
        }
        return parseDisjunctivePattern(precedence, afterIs, inSwitchArmPattern)
    } finally {
        recursionDepth -= PATTERN_DEPTH_WEIGHT
    }
}

/** `ParseDisjunctivePattern` (LPP 58) */
private fun LanguageParser.parseDisjunctivePattern(precedence: Int, afterIs: Boolean, inSwitchArmPattern: Boolean): Node {
    var result = parseConjunctivePattern(precedence, afterIs, inSwitchArmPattern)
    while (currentContextualKind === SyntaxKind.OrKeyword) {
        convertToKeyword()
        parseConjunctivePattern(precedence, afterIs, inSwitchArmPattern)
        result = result.wrap(SyntaxKind.OrPattern)
    }
    return result
}

/** `LooksLikeTypeOfPattern` (LPP 77) */
private fun LanguageParser.looksLikeTypeOfPattern(): Boolean {
    val tk = currentKind
    if (CSharpSyntaxFacts.isPredefinedType(tk)) return true
    if (tk === SyntaxKind.IdentifierToken && currentContextualKind !== SyntaxKind.UnderscoreToken &&
        (currentContextualKind !== SyntaxKind.NameOfKeyword || peekToken(1).kind !== SyntaxKind.OpenParenToken)
    ) return true
    if (looksLikeTupleArrayType()) return true
    return isFunctionPointerStart()
}

/** `ParseConjunctivePattern` (LPP 104) */
private fun LanguageParser.parseConjunctivePattern(precedence: Int, afterIs: Boolean, inSwitchArmPattern: Boolean): Node {
    var result = parseNegatedPattern(precedence, afterIs, inSwitchArmPattern)
    while (currentContextualKind === SyntaxKind.AndKeyword) {
        convertToKeyword()
        parseNegatedPattern(precedence, afterIs, inSwitchArmPattern)
        result = result.wrap(SyntaxKind.AndPattern)
    }
    return result
}

/** `ScanDesignation(permitTuple)` (LPP 119) */
fun LanguageParser.scanDesignation(permitTuple: Boolean): Boolean {
    // Tuple designations recurse; depth guard as in scanType.
    recursionDepth++
    try {
        if (recursionDepth > SyntaxParser.MAX_DEPTH) return false
        return scanDesignationCore(permitTuple)
    } finally {
        recursionDepth--
    }
}

private fun LanguageParser.scanDesignationCore(permitTuple: Boolean): Boolean {
    when (currentKind) {
        SyntaxKind.IdentifierToken -> {
            val result = isTrueIdentifier()
            eatToken()
            return result
        }
        SyntaxKind.OpenParenToken -> {
            if (!permitTuple) return false
            var sawComma = false
            while (true) {
                eatToken()
                if (!scanDesignation(permitTuple = true)) return false
                when (currentKind) {
                    SyntaxKind.CloseParenToken -> {
                        eatToken()
                        return sawComma
                    }
                    SyntaxKind.CommaToken -> sawComma = true
                    else -> return false
                }
            }
        }
        else -> return false
    }
}

/** `ParseNegatedPattern` (LPP 158) */
private fun LanguageParser.parseNegatedPattern(precedence: Int, afterIs: Boolean, inSwitchArmPattern: Boolean): Node {
    when {
        currentContextualKind === SyntaxKind.NotKeyword -> {
            val m = open()
            convertToKeyword()
            parseNegatedPattern(precedence, afterIs, inSwitchArmPattern)
            return m.done(SyntaxKind.NotPattern)
        }
        currentKind === SyntaxKind.EqualsEqualsToken -> {
            // ERR_EqualityOperatorInPatternNotSupported: the `==` is skipped before the pattern.
            skipTokens(1, CSharpErrorCode.ERR_EqualityOperatorInPatternNotSupported.describe())
            return parseNegatedPattern(precedence, afterIs, inSwitchArmPattern)
        }
        currentKind === SyntaxKind.ExclamationEqualsToken -> {
            // Roslyn: a missing `not` with the `!=` as its trailing skipped syntax (the error is on the `!=`).
            val m = open()
            createMissingToken(SyntaxKind.NotKeyword, message = SKIPPED)
            skipTokens(1, CSharpErrorCode.ERR_InequalityOperatorInPatternNotSupported.describe(), countError = false)
            parseNegatedPattern(precedence, afterIs, inSwitchArmPattern)
            return m.done(SyntaxKind.NotPattern)
        }
        else -> return parsePrimaryPattern(precedence, afterIs, inSwitchArmPattern, exprOnly = false)
    }
}

/**
 * `ParsePrimaryPattern` (LPP 186). With [exprOnly] (`ConvertPatternToExpressionIfPossible`, LPP 468) the constant,
 * type or discard pattern this position is known to hold is parsed as a bare expression; [permitTypeArguments] as in
 * `ConvertTypeToExpression`.
 */
private fun LanguageParser.parsePrimaryPattern(precedence: Int, afterIs: Boolean, inSwitchArmPattern: Boolean, exprOnly: Boolean, permitTypeArguments: Boolean = false): Node {
    when (currentKind) {
        SyntaxKind.CommaToken, SyntaxKind.SemicolonToken, SyntaxKind.CloseBraceToken, SyntaxKind.CloseParenToken,
        SyntaxKind.CloseBracketToken, SyntaxKind.EqualsGreaterThanToken,
        -> {
            val name = parseIdentifierName(CSharpErrorCode.ERR_MissingPattern.describe())
            return if (exprOnly) name else constantPattern(name)
        }
    }
    if (currentContextualKind === SyntaxKind.UnderscoreToken) {
        if (exprOnly) return parseIdentifierName()
        val m = open()
        eatContextualToken(SyntaxKind.UnderscoreToken)
        return m.done(SyntaxKind.DiscardPattern)
    }
    when (currentKind) {
        SyntaxKind.OpenBracketToken -> return parseListPattern(inSwitchArmPattern)
        SyntaxKind.DotToken -> if (isAtDotDotToken()) {
            val m = open()
            eatDotDotToken()
            if (isPossibleSubpatternElement()) parsePattern(precedence, afterIs = false, inSwitchArmPattern)
            return m.done(SyntaxKind.SlicePattern)
        }
        SyntaxKind.LessThanToken, SyntaxKind.LessThanEqualsToken, SyntaxKind.GreaterThanToken, SyntaxKind.GreaterThanEqualsToken,
        SyntaxKind.EqualsEqualsToken, SyntaxKind.ExclamationEqualsToken,
        -> {
            val m = open()
            eatToken()
            parseSubExpression(Precedence.Relational)
            return m.done(SyntaxKind.RelationalPattern)
        }
    }

    val rp = getResetPoint()
    try {
        var type: Node? = null
        if (looksLikeTypeOfPattern()) {
            type = parseType(if (afterIs) ParseTypeMode.AfterIs else ParseTypeMode.DefinitePattern)
            if (type.isMissing || !canTokenFollowTypeInPattern(precedence)) {
                rp.reset()
                type = null
            }
        }
        val pattern = parsePatternContinued(type, rp, precedence, afterIs, inSwitchArmPattern, exprOnly, permitTypeArguments)
        if (pattern != null) return pattern
        rp.reset()
        val value = parseSubExpression(precedence)
        return if (exprOnly) value else constantPattern(value)
    } finally {
        rp.release()
    }
}

private fun LanguageParser.constantPattern(expression: Node): Node {
    val node = expression.wrap(SyntaxKind.ConstantPattern)
    node.inner = expression
    return node
}

/** `CanTokenFollowTypeInPattern(precedence)` (LPP 257) */
private fun LanguageParser.canTokenFollowTypeInPattern(precedence: Int): Boolean = when (val kind = currentKind) {
    SyntaxKind.OpenParenToken, SyntaxKind.OpenBraceToken, SyntaxKind.IdentifierToken, SyntaxKind.CloseBraceToken,
    SyntaxKind.CloseBracketToken, SyntaxKind.CloseParenToken, SyntaxKind.CommaToken, SyntaxKind.SemicolonToken,
    -> true
    SyntaxKind.DotToken, SyntaxKind.MinusGreaterThanToken, SyntaxKind.ExclamationToken -> false
    else -> !CSharpSyntaxFacts.isBinaryExpressionOperatorToken(kind) ||
        LanguageParser.getPrecedence(CSharpSyntaxFacts.getBinaryExpression(kind)!!) <= precedence
}

/** The `NameOptions` `ParseTypeCore` uses for the type of a pattern (for the reparse as an expression). */
private fun patternNameOptions(afterIs: Boolean): Int =
    if (afterIs) NameOptions.InExpression or NameOptions.AfterIs or NameOptions.PossiblePattern
    else NameOptions.InExpression or NameOptions.DefinitePattern or NameOptions.PossiblePattern

/**
 * `ParsePatternContinued(type, precedence, inSwitchArmPattern)` (LPP 283). [typeStart] is the reset point before
 * [type]; the var pattern and the conversions of the type to an expression reparse from it.
 */
private fun LanguageParser.parsePatternContinued(
    type: Node?,
    typeStart: SyntaxParser.ResetPoint,
    precedence: Int,
    afterIs: Boolean,
    inSwitchArmPattern: Boolean,
    exprOnly: Boolean,
    permitTypeArguments: Boolean,
): Node? {
    if (type != null && type.kind === SyntaxKind.IdentifierName && type.identifierContextualKind === SyntaxKind.VarKeyword &&
        (currentKind === SyntaxKind.OpenParenToken || isValidPatternDesignation(inSwitchArmPattern))
    ) {
        // `var` becomes the keyword: reparse the identifier as `VarKeyword`.
        typeStart.reset()
        val m = open()
        eatCurrentAs(SyntaxKind.VarKeyword)
        parseDesignation(forPattern = true)
        return m.done(SyntaxKind.VarPattern)
    }

    if (currentKind === SyntaxKind.OpenParenToken && (type != null || !looksLikeCast(inSwitchArmPattern))) {
        return parsePositionalOrParenthesized(type, precedence, inSwitchArmPattern, exprOnly)
    }

    if (currentKind === SyntaxKind.OpenBraceToken || isMisplacedIdentifierBeforePropertyPattern(inSwitchArmPattern)) {
        val whole = type?.precede() ?: open()
        parsePropertyPatternClause(inSwitchArmPattern)
        tryParseSimpleDesignation(inSwitchArmPattern)
        return whole.done(SyntaxKind.RecursivePattern)
    }

    if (type != null) {
        if (isTrueIdentifier() && isValidPatternDesignation(inSwitchArmPattern)) {
            parseSimpleDesignation()
            return type.wrap(SyntaxKind.DeclarationPattern)
        }
        if (isTypeConvertibleToExpression(type, permitTypeArguments)) {
            // ConvertTypeToExpression: the same tokens as member accesses, then the binary-operator continuation.
            typeStart.reset()
            val expression = parseQualifiedName(patternNameOptions(afterIs), asExpression = true)
            val continued = parseExpressionContinued(expression, precedence)
            return if (exprOnly) continued else constantPattern(continued)
        }
        if (exprOnly) {
            // A type that is not an expression was asked for as one: cannot happen (the caller checked); keep the type.
            return type
        }
        val tp = type.wrap(SyntaxKind.TypePattern)
        tp.inner = type
        return tp
    }
    return null
}

/** `(`-branch of `ParsePatternContinued` (LPP 284): positional pattern, parenthesized pattern or parenthesized expression. */
private fun LanguageParser.parsePositionalOrParenthesized(type: Node?, precedence: Int, inSwitchArmPattern: Boolean, exprOnly: Boolean): Node {
    // The decision below (positional pattern, or a reparse as `(pattern)` / `(expression)`) is memoised by position so
    // that the reparse of an outer level does not repeat the first pass of the inner levels (docs/csharp-psi/GRAMMAR.md, 7.3).
    val decisionKey = if (type == null) decisionKey(inSwitchArmPattern) else -1L
    when (if (type == null) parenPatternDecisions[decisionKey] else null) {
        PAREN_DECISION_CONSTANT -> return parseParenthesizedConstantPattern(precedence, exprOnly)
        PAREN_DECISION_PATTERN -> return parseParenthesizedPattern()
    }
    val atParen = getResetPoint()
    try {
        val whole = type?.precede() ?: open()
        val clause = open()
        eatToken(SyntaxKind.OpenParenToken)
        var subpatternCount = 0
        var singleIsPlain = false
        var singlePlainIsConstant = false
        parseCommaSeparatedSyntaxList(
            SyntaxKind.CloseParenToken,
            isPossibleElement = { isPossibleSubpatternElement() },
            parseElement = {
                val sub = parseSubpatternElement()
                subpatternCount++
                singleIsPlain = sub.inner != null
                singlePlainIsConstant = sub.inner?.kind === SyntaxKind.ConstantPattern
            },
            skipBadTokens = { expected, close -> skipBadPatternListTokens(expected, close) },
            allowTrailingSeparator = false,
            requireOneElement = false,
            allowSemicolonAsSeparator = false,
        )
        val separators = subpatternCount - 1
        eatToken(SyntaxKind.CloseParenToken)
        clause.done(SyntaxKind.PositionalPatternClause)
        val hasPropertyClause = parsePropertyPatternClauseIfPresent(inSwitchArmPattern)
        val hasDesignation = tryParseSimpleDesignation(inSwitchArmPattern)

        if (type == null && !hasPropertyClause && !hasDesignation && subpatternCount == 1 && separators <= 0 && singleIsPlain) {
            // Not a positional pattern after all: `(pattern)` or `(expression)`; reparse without the wrappers.
            whole.drop()
            atParen.reset()
            if (singlePlainIsConstant) {
                parenPatternDecisions[decisionKey] = PAREN_DECISION_CONSTANT
                return parseParenthesizedConstantPattern(precedence, exprOnly)
            }
            parenPatternDecisions[decisionKey] = PAREN_DECISION_PATTERN
            return parseParenthesizedPattern()
        }
        if (type == null) parenPatternDecisions[decisionKey] = PAREN_DECISION_POSITIONAL
        return whole.done(SyntaxKind.RecursivePattern)
    } finally {
        atParen.release()
    }
}

private const val PATTERN_DEPTH_WEIGHT = 4

private const val PAREN_DECISION_POSITIONAL = 0
private const val PAREN_DECISION_PATTERN = 1
private const val PAREN_DECISION_CONSTANT = 2

/** `(` constant-expression `)` continued as an expression: `ConstantPattern(ParseExpressionContinued(ParenthesizedExpression))` (LPP 336). */
private fun LanguageParser.parseParenthesizedConstantPattern(precedence: Int, exprOnly: Boolean): Node {
    val m = open()
    eatToken(SyntaxKind.OpenParenToken)
    parsePrimaryPatternAsExpression(Precedence.Conditional)
    eatToken(SyntaxKind.CloseParenToken)
    val paren = m.done(SyntaxKind.ParenthesizedExpression)
    val continued = parseExpressionContinued(paren, precedence)
    return if (exprOnly) continued else constantPattern(continued)
}

/** `ParenthesizedPattern(openParen, subpattern, closeParen)` (LPP 340). */
private fun LanguageParser.parseParenthesizedPattern(): Node {
    val m = open()
    eatToken(SyntaxKind.OpenParenToken)
    parsePattern(Precedence.Conditional)
    eatToken(SyntaxKind.CloseParenToken)
    return m.done(SyntaxKind.ParenthesizedPattern)
}

/** The expression of a pattern known to be a constant pattern (reparse of `ParsePattern(Precedence.Conditional)`). */
private fun LanguageParser.parsePrimaryPatternAsExpression(precedence: Int, permitTypeArguments: Boolean = false): Node =
    parsePrimaryPattern(precedence, afterIs = false, inSwitchArmPattern = false, exprOnly = true, permitTypeArguments = permitTypeArguments)

/** local `looksLikeCast` of `ParsePatternContinued` (LPP 391) */
private fun LanguageParser.looksLikeCast(inSwitchArmPattern: Boolean): Boolean = speculate { scanCast(forPattern = true, inSwitchArmPattern) }

/** The misplaced designator of local `parsePropertyPatternClause` (LPP 374): `x is T i { ... }`. */
private fun LanguageParser.isMisplacedIdentifierBeforePropertyPattern(inSwitchArmPattern: Boolean): Boolean =
    isTrueIdentifier() && isValidPatternDesignation(inSwitchArmPattern) && peekToken(1).kind === SyntaxKind.OpenBraceToken

/** local `parsePropertyPatternClause` (LPP 374): true when a clause was parsed. */
private fun LanguageParser.parsePropertyPatternClauseIfPresent(inSwitchArmPattern: Boolean): Boolean {
    if (currentKind === SyntaxKind.OpenBraceToken || isMisplacedIdentifierBeforePropertyPattern(inSwitchArmPattern)) {
        parsePropertyPatternClause(inSwitchArmPattern)
        return true
    }
    return false
}

/** `ParsePropertyPatternClause` (LPP 509), with the misplaced designator (local `parsePropertyPatternClause`, LPP 373) as skipped tokens in front. */
private fun LanguageParser.parsePropertyPatternClause(inSwitchArmPattern: Boolean): Node {
    val m = open()
    if (isMisplacedIdentifierBeforePropertyPattern(inSwitchArmPattern)) skipTokens(1, CSharpErrorCode.ERR_DesignatorBeforePropertyPattern.describe())
    eatToken(SyntaxKind.OpenBraceToken)
    parseCommaSeparatedSyntaxList(
        SyntaxKind.CloseBraceToken,
        isPossibleElement = { isPossibleSubpatternElement() },
        parseElement = { parseSubpatternElement() },
        skipBadTokens = { expected, close -> skipBadPatternListTokens(expected, close) },
        allowTrailingSeparator = true,
        requireOneElement = false,
        allowSemicolonAsSeparator = false,
    )
    eatToken(SyntaxKind.CloseBraceToken)
    return m.done(SyntaxKind.PropertyPatternClause)
}

/** `TryParseSimpleDesignation(whenIsKeyword)` (LPP 400): true when a designation was parsed. */
fun LanguageParser.tryParseSimpleDesignation(whenIsKeyword: Boolean): Boolean {
    if (isTrueIdentifier() && isValidPatternDesignation(whenIsKeyword)) {
        parseSimpleDesignation()
        return true
    }
    return false
}

/** `IsValidPatternDesignation(inSwitchArmPattern)` (LPP 407) */
private fun LanguageParser.isValidPatternDesignation(inSwitchArmPattern: Boolean): Boolean {
    if (currentKind !== SyntaxKind.IdentifierToken) return false
    return when (currentContextualKind) {
        SyntaxKind.WhenKeyword -> !inSwitchArmPattern
        SyntaxKind.AndKeyword, SyntaxKind.OrKeyword -> when (val tk = peekToken(1).kind) {
            SyntaxKind.CloseBraceToken, SyntaxKind.CloseBracketToken, SyntaxKind.CloseParenToken, SyntaxKind.CommaToken,
            SyntaxKind.SemicolonToken, SyntaxKind.QuestionToken, SyntaxKind.ColonToken,
            -> true
            SyntaxKind.LessThanEqualsToken, SyntaxKind.LessThanToken, SyntaxKind.GreaterThanEqualsToken, SyntaxKind.GreaterThanToken,
            SyntaxKind.IdentifierToken, SyntaxKind.OpenBraceToken, SyntaxKind.OpenParenToken, SyntaxKind.OpenBracketToken,
            -> false
            else -> {
                if (CSharpSyntaxFacts.isBinaryExpression(tk)) true
                else speculate {
                    eatToken()
                    !canStartExpression()
                }
            }
        }
        else -> true
    }
}

/**
 * `ParseSubpatternElement` (LPP 528). When `:` follows a pattern convertible to an expression
 * (`ConvertPatternToExpressionIfPossible`, permitTypeArguments), the pattern is reparsed as the expression of a
 * `NameColon`/`ExpressionColon`. The returned node's [Node.inner] is the pattern when there was no colon (for the
 * parenthesized-pattern decision of the caller).
 */
private fun LanguageParser.parseSubpatternElement(): Node {
    val m = open()
    val rp = getResetPoint()
    var plainPattern: Node? = null
    try {
        val pattern = parsePattern(Precedence.Conditional)
        val convertible = when (pattern.kind) {
            SyntaxKind.ConstantPattern, SyntaxKind.DiscardPattern -> true
            SyntaxKind.TypePattern -> pattern.inner?.let { isTypeConvertibleToExpression(it, permitTypeArguments = true) } == true
            else -> false
        }
        if (currentKind === SyntaxKind.ColonToken && convertible) {
            rp.reset()
            val expr = parsePrimaryPatternAsExpression(Precedence.Conditional, permitTypeArguments = true)
            eatToken()
            expr.wrap(if (expr.kind === SyntaxKind.IdentifierName) SyntaxKind.NameColon else SyntaxKind.ExpressionColon)
            parsePattern(Precedence.Conditional)
        } else {
            plainPattern = pattern
        }
    } finally {
        rp.release()
    }
    val node = m.done(SyntaxKind.Subpattern)
    node.inner = plainPattern
    return node
}

/** `IsPossibleSubpatternElement` (LPP 552) */
fun LanguageParser.isPossibleSubpatternElement(): Boolean =
    canStartExpression() || when (currentKind) {
        SyntaxKind.OpenBraceToken, SyntaxKind.OpenBracketToken, SyntaxKind.LessThanToken, SyntaxKind.LessThanEqualsToken,
        SyntaxKind.GreaterThanToken, SyntaxKind.GreaterThanEqualsToken,
        -> true
        else -> false
    }

/** `SkipBadPatternListTokens` (LPP 564) */
private fun LanguageParser.skipBadPatternListTokens(expectedKind: com.intellij.psi.tree.IElementType, closeKind: com.intellij.psi.tree.IElementType): PostSkipAction {
    val k = currentKind
    if (k === SyntaxKind.CloseParenToken || k === SyntaxKind.CloseBraceToken || k === SyntaxKind.CloseBracketToken || k === SyntaxKind.SemicolonToken) return PostSkipAction.Abort
    if (termState and TerminatorState.IsExpressionOrPatternInCaseLabelOfSwitchStatement != 0 && k === SyntaxKind.ColonToken) return PostSkipAction.Abort
    if (termState and TerminatorState.IsPatternInSwitchExpressionArm != 0 && (k === SyntaxKind.EqualsGreaterThanToken || k === SyntaxKind.ColonToken)) return PostSkipAction.Abort
    return skipBadSeparatedListTokensWithExpectedKind(
        isNotExpected = { currentKind !== SyntaxKind.CommaToken && !isPossibleSubpatternElement() },
        abort = { close -> currentKind === close || currentKind === SyntaxKind.SemicolonToken },
        expectedKind, closeKind,
    )
}

/**
 * `ParseExpressionOrPatternForSwitchStatement` (LPP 459) with `ConvertPatternToExpressionIfPossible` (LPP 468) and the
 * `when` rule of `ParseSwitchSection` (LP 10324-10330): the label of a `case` is parsed as a pattern; a constant
 * pattern or a discard is then the label's expression (`CaseSwitchLabel`) unless `when` follows, in which case it is a
 * `ConstantPattern` (a discard becomes `ConstantPattern(IdentifierName(_))`). Type patterns never convert here
 * (`permitTypeArguments: false`: the port builds a `TypePattern` only for types that do not convert). The conversion
 * is a reset and a reparse of the primary pattern as an expression, with the same terminator state. Returns true
 * when the label is a pattern (`CasePatternSwitchLabel`).
 */
fun LanguageParser.parseExpressionOrPatternForSwitchStatement(): Boolean {
    val savedState = termState
    termState = termState or TerminatorState.IsExpressionOrPatternInCaseLabelOfSwitchStatement
    // The outcome is memoised by position (as `parenPatternDecisions`): the reparse of an outer label then takes the
    // inner labels' final path directly instead of repeating their pattern pass (2^depth, docs/csharp-psi/GRAMMAR.md).
    val key = decisionKey()
    try {
        when (caseLabelDecisions[key]) {
            CASE_LABEL_EXPRESSION -> return parseCaseLabelAsExpression(whenFollows = false)
            CASE_LABEL_EXPRESSION_WHEN -> return parseCaseLabelAsExpression(whenFollows = true)
        }
        val rp = getResetPoint()
        try {
            val pattern = parsePattern(Precedence.Conditional, inSwitchArmPattern = true)
            val convertible = pattern.kind === SyntaxKind.ConstantPattern || pattern.kind === SyntaxKind.DiscardPattern
            if (!convertible) return true
            val whenFollows = currentContextualKind === SyntaxKind.WhenKeyword
            if (whenFollows && pattern.kind === SyntaxKind.ConstantPattern) return true // ConstantPattern(cp.Expression): the same node
            caseLabelDecisions[key] = if (whenFollows) CASE_LABEL_EXPRESSION_WHEN else CASE_LABEL_EXPRESSION
            rp.reset()
            termState = termState or TerminatorState.IsExpressionOrPatternInCaseLabelOfSwitchStatement
            return parseCaseLabelAsExpression(whenFollows)
        } finally {
            rp.release()
        }
    } finally {
        termState = savedState
    }
}

private const val CASE_LABEL_EXPRESSION = 1
private const val CASE_LABEL_EXPRESSION_WHEN = 2

/** The reparse of `parseExpressionOrPatternForSwitchStatement`: `ConvertPatternToExpressionIfPossible` (LPP 459). */
private fun LanguageParser.parseCaseLabelAsExpression(whenFollows: Boolean): Boolean {
    // The `==` that `ParseNegatedPattern` skips before a primary pattern (LPP 168).
    while (currentKind === SyntaxKind.EqualsEqualsToken) skipTokens(1, CSharpErrorCode.ERR_EqualityOperatorInPatternNotSupported.describe())
    val expression = parsePrimaryPattern(Precedence.Conditional, afterIs = false, inSwitchArmPattern = true, exprOnly = true)
    if (whenFollows) constantPattern(expression)
    return whenFollows
}

/** `ParseSwitchExpression(governingExpression, switchKeyword)` (LPP 467); the `switch` keyword is already eaten. */
fun LanguageParser.parseSwitchExpression(governingExpression: Node): Node {
    eatToken(SyntaxKind.OpenBraceToken)
    while (currentKind !== SyntaxKind.CloseBraceToken && !currentToken.isEof) {
        val beforeArm = getResetPoint()
        try {
            val errantCase = currentKind === SyntaxKind.CaseKeyword
            val arm = open()
            if (errantCase) skipTokens(1, CSharpErrorCode.ERR_BadCaseInSwitchArm.describe())
            val armStart = position
            val savedState = termState
            termState = termState or TerminatorState.IsPatternInSwitchExpressionArm
            parsePattern(Precedence.Coalescing, inSwitchArmPattern = true)
            termState = savedState
            parseWhenClause(Precedence.Coalescing)
            if (currentKind === SyntaxKind.ColonToken) eatTokenAsKind(SyntaxKind.EqualsGreaterThanToken) else eatToken(SyntaxKind.EqualsGreaterThanToken)
            parseExpressionCore()
            if (!errantCase && position == armStart && currentKind !== SyntaxKind.CommaToken) {
                // An arm of width 0 is not added (FullWidth == 0): roll it back and stop.
                arm.drop()
                beforeArm.reset()
                break
            }
            arm.done(SyntaxKind.SwitchExpressionArm)
            if (currentKind !== SyntaxKind.CloseBraceToken) {
                if (currentKind === SyntaxKind.SemicolonToken) eatTokenAsKind(SyntaxKind.CommaToken) else eatToken(SyntaxKind.CommaToken)
            }
        } finally {
            beforeArm.release()
        }
    }
    eatToken(SyntaxKind.CloseBraceToken)
    return governingExpression.wrap(SyntaxKind.SwitchExpression)
}

/** `ParseListPattern(inSwitchArmPattern)` (LPP 660) */
private fun LanguageParser.parseListPattern(inSwitchArmPattern: Boolean): Node {
    val m = open()
    eatToken(SyntaxKind.OpenBracketToken)
    parseCommaSeparatedSyntaxList(
        SyntaxKind.CloseBracketToken,
        isPossibleElement = { isPossibleSubpatternElement() },
        parseElement = { parsePattern(Precedence.Conditional) },
        skipBadTokens = { expected, close -> skipBadPatternListTokens(expected, close) },
        allowTrailingSeparator = true,
        requireOneElement = false,
        allowSemicolonAsSeparator = false,
    )
    eatToken(SyntaxKind.CloseBracketToken)
    tryParseSimpleDesignation(inSwitchArmPattern)
    return m.done(SyntaxKind.ListPattern)
}

/** `LooksLikeTupleArrayType` (LPP 500) */
private fun LanguageParser.looksLikeTupleArrayType(): Boolean {
    if (currentKind !== SyntaxKind.OpenParenToken) return false
    return speculate { scanType(forPattern = true) != ScanTypeFlags.NotType }
}
