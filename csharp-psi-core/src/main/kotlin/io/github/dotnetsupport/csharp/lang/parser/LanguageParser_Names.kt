// Ported from Roslyn: src/Compilers/CSharp/Portable/Parser/LanguageParser.cs, names and types (IsTrueIdentifier 6065,
// ParseIdentifierName/Token 6091-6137, ParseSimpleName 6241, ScanTypeArgumentList 6286, ScanPossibleTypeArgumentList
// 6415, ParseTypeArgumentList 6604, ParseTypeArgument 6740, IsOpenName 6810, ParseAliasQualifiedName 7087,
// ParseQualifiedName 7095, ParseQualifiedNameRight 7114, ScanType 7224-7430, ScanTupleType 7432, ScanFunctionPointerType
// 7480, ParseTypeOrVoid 7605, ParseType 7630, ParseTypeCore 7643, TryEatNullableQualifierIfApplicable 7746,
// ParseArrayRankSpecifier 7924, ParseTupleType 7984, ParseUnderlyingType 8033, ParseFunctionPointerTypeSyntax 8068,
// ParsePointerTypeMods 8237), attributes (1047-1300), parameters (ParseParameterList 4870-5100), IsPartialType 1664,
// IsPartialMember 1700, IsTokenQueryContextualKeyword 14104, roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c.
// Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.CSharpFeature
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpDiagnosticAnchor
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpErrorCode

/** Roslyn `ScanTypeFlags` (LP 7166). */
enum class ScanTypeFlags { NotType, MustBeType, GenericTypeOrMethod, GenericTypeOrExpression, NonGenericTypeOrExpression, AliasQualifiedName, NullableType, PointerOrMultiplication, TupleType }

/** Roslyn `ScanTypeArgumentListKind` (LP 6279). */
enum class ScanTypeArgumentListKind { NotTypeArgumentList, PossibleTypeArgumentList, DefiniteTypeArgumentList }

/** What `ParseAttributeDeclarations` returns that callers inspect. */
class AttributeLists(val count: Int, val lastCloseBracketMissing: Boolean)

// ---- identifiers --------------------------------------------------------------------------------------------------

/** `IsTrueIdentifier()` (LP 6065) */
fun LanguageParser.isTrueIdentifier(): Boolean =
    currentKind === SyntaxKind.IdentifierToken &&
        !isCurrentTokenPartialKeywordOfPartialMemberOrType() &&
        !isCurrentTokenQueryKeywordInQuery() &&
        !isCurrentTokenWhereOfConstraintClause()

/** `IsTrueIdentifier(token)` (LP 6084) */
fun LanguageParser.isTrueIdentifier(token: Tok): Boolean =
    token.kind === SyntaxKind.IdentifierToken && !(isInQuery && isTokenQueryContextualKeyword(token))

/** `IsCurrentTokenPartialKeywordOfPartialMemberOrType` (LP 6144) */
fun LanguageParser.isCurrentTokenPartialKeywordOfPartialMemberOrType(): Boolean =
    currentContextualKind === SyntaxKind.PartialKeyword && (isPartialType() || isPartialMember())

/** `IsPartialType` (LP 1664): `partial record` / `partial union` only when the feature is enabled. */
fun LanguageParser.isPartialType(): Boolean {
    val next = peekToken(1)
    return when (next.kind) {
        SyntaxKind.StructKeyword, SyntaxKind.ClassKeyword, SyntaxKind.InterfaceKeyword -> true
        else -> when (next.contextualKind) {
            SyntaxKind.RecordKeyword -> isFeatureEnabled(CSharpFeature.Records)
            SyntaxKind.UnionKeyword -> isFeatureEnabled(CSharpFeature.Unions)
            else -> false
        }
    }
}

/** `IsPartialMember` (LP 1700): `partial C(` is a partial constructor from C# 14 on, and never a partial method. */
fun LanguageParser.isPartialMember(): Boolean {
    if (peekToken(1).kind === SyntaxKind.EventKeyword) return true
    if (peekToken(1).kind === SyntaxKind.IdentifierToken && peekToken(2).kind === SyntaxKind.OpenParenToken) {
        return isFeatureEnabled(CSharpFeature.PartialEventsAndConstructors)
    }
    return speculate {
        eatToken()
        scanType() != ScanTypeFlags.NotType && isPossibleMemberName()
    }
}

/** `IsPossibleMemberName` (LP 1733) */
fun LanguageParser.isPossibleMemberName(): Boolean = when (currentKind) {
    SyntaxKind.IdentifierToken -> !(currentContextualKind === SyntaxKind.GlobalKeyword && peekToken(1).kind === SyntaxKind.UsingKeyword)
    SyntaxKind.ThisKeyword -> true
    else -> false
}

/** `IsCurrentTokenWhereOfConstraintClause` (LP 2234) */
fun LanguageParser.isCurrentTokenWhereOfConstraintClause(): Boolean =
    currentContextualKind === SyntaxKind.WhereKeyword && peekToken(1).kind === SyntaxKind.IdentifierToken && peekToken(2).kind === SyntaxKind.ColonToken

/** `IsCurrentTokenQueryKeywordInQuery` (LP 6138) */
fun LanguageParser.isCurrentTokenQueryKeywordInQuery(): Boolean = isInQuery && isTokenQueryContextualKeyword(currentToken)

/** `IsCurrentTokenFieldInKeywordContext` (LP 6156): `field` in an accessor of a property, from C# 14 on. */
fun LanguageParser.isCurrentTokenFieldInKeywordContext(): Boolean =
    currentContextualKind === SyntaxKind.FieldKeyword && isInFieldKeywordContext && isFeatureEnabled(CSharpFeature.FieldKeyword)

/** `IsTokenQueryContextualKeyword` (LP 14104) + `IsTokenStartOfNewQueryClause` */
fun LanguageParser.isTokenQueryContextualKeyword(token: Tok): Boolean = when (token.contextualKind) {
    SyntaxKind.FromKeyword, SyntaxKind.JoinKeyword, SyntaxKind.IntoKeyword, SyntaxKind.WhereKeyword, SyntaxKind.OrderByKeyword,
    SyntaxKind.GroupKeyword, SyntaxKind.SelectKeyword, SyntaxKind.LetKeyword,
    SyntaxKind.OnKeyword, SyntaxKind.EqualsKeyword, SyntaxKind.AscendingKeyword, SyntaxKind.DescendingKeyword, SyntaxKind.ByKeyword,
    -> true
    else -> false
}

/** `IsSomeWord` (LP 49) */
fun isSomeWord(kind: IElementType): Boolean = kind === SyntaxKind.IdentifierToken || CSharpSyntaxFacts.isKeywordKind(kind)

/** `ParseIdentifierName(code)` (LP 6091) */
fun LanguageParser.parseIdentifierName(message: String = CSharpErrorCode.ERR_IdentifierExpected.describe()): Node {
    val m = open()
    val tok = currentToken
    val real = parseIdentifierToken(message)
    val node = m.done(SyntaxKind.IdentifierName)
    if (real) node.identifierContextualKind = tok.contextualKind
    node.isNameShaped = true
    return node
}

/** `ParseIdentifierToken(code)` (LP 6105): true when a real identifier was eaten. */
fun LanguageParser.parseIdentifierToken(message: String = CSharpErrorCode.ERR_IdentifierExpected.describe()): Boolean {
    if (currentKind === SyntaxKind.IdentifierToken) {
        if (isCurrentTokenPartialKeywordOfPartialMemberOrType() || isCurrentTokenQueryKeywordInQuery()) {
            createMissingToken(SyntaxKind.IdentifierToken, message = CSharpErrorCode.ERR_InvalidExprTerm.describe(currentText))
            return false
        }
        if (isInAsync && currentContextualKind === SyntaxKind.AwaitKeyword) errorCount++ // ERR_BadAwaitAsIdentifier
        eatToken()
        return true
    }
    createMissingToken(SyntaxKind.IdentifierToken, message = message)
    return false
}

// ---- simple names and type arguments --------------------------------------------------------------------------

/**
 * `ParseSimpleName(options)` (LP 6241). Hard spot 7.3: `IdentifierName` vs `GenericName` is decided after scanning the
 * type argument list, so the marker is completed late with either kind.
 */
fun LanguageParser.parseSimpleName(options: Int = NameOptions.None): Node {
    val m = open()
    val tok = currentToken
    if (!parseIdentifierToken()) {
        val missing = m.done(SyntaxKind.IdentifierName)
        missing.isNameShaped = true
        return missing
    }
    if (currentKind === SyntaxKind.LessThanToken) {
        val kind = speculate { scanTypeArgumentList(options) }
        if (kind == ScanTypeArgumentListKind.DefiniteTypeArgumentList ||
            (kind == ScanTypeArgumentListKind.PossibleTypeArgumentList && options and NameOptions.InTypeList != 0)
        ) {
            parseTypeArgumentList()
            val generic = m.done(SyntaxKind.GenericName)
            generic.isNameShaped = true
            return generic
        }
    }
    val node = m.done(SyntaxKind.IdentifierName)
    node.identifierContextualKind = tok.contextualKind
    node.isNameShaped = true
    return node
}

/** `ScanTypeArgumentList(options)` (LP 6286); the token window is restored by the caller. */
fun LanguageParser.scanTypeArgumentList(options: Int): ScanTypeArgumentListKind {
    if (currentKind !== SyntaxKind.LessThanToken) return ScanTypeArgumentListKind.NotTypeArgumentList
    if (options and NameOptions.InExpression == 0) return ScanTypeArgumentListKind.DefiniteTypeArgumentList

    val (flags, isDefinitelyTypeArgumentList) = scanPossibleTypeArgumentList()
    if (flags == ScanTypeFlags.NotType) return ScanTypeArgumentListKind.NotTypeArgumentList
    if (isDefinitelyTypeArgumentList) return ScanTypeArgumentListKind.DefiniteTypeArgumentList

    return when (currentKind) {
        SyntaxKind.OpenParenToken, SyntaxKind.CloseParenToken, SyntaxKind.CloseBracketToken, SyntaxKind.CloseBraceToken,
        SyntaxKind.ColonToken, SyntaxKind.SemicolonToken, SyntaxKind.CommaToken, SyntaxKind.DotToken, SyntaxKind.QuestionToken,
        SyntaxKind.EqualsEqualsToken, SyntaxKind.ExclamationEqualsToken, SyntaxKind.BarToken, SyntaxKind.CaretToken,
        SyntaxKind.AmpersandAmpersandToken, SyntaxKind.BarBarToken, SyntaxKind.AmpersandToken, SyntaxKind.OpenBracketToken,
        SyntaxKind.LessThanToken, SyntaxKind.LessThanEqualsToken, SyntaxKind.GreaterThanEqualsToken, SyntaxKind.IsKeyword,
        SyntaxKind.AsKeyword, SyntaxKind.OpenBraceToken, SyntaxKind.EndOfFileToken, SyntaxKind.EqualsGreaterThanToken,
        -> ScanTypeArgumentListKind.DefiniteTypeArgumentList
        SyntaxKind.GreaterThanToken ->
            if (options and NameOptions.AfterIs != 0 && peekToken(1).kind !== SyntaxKind.GreaterThanToken) ScanTypeArgumentListKind.DefiniteTypeArgumentList
            else ScanTypeArgumentListKind.PossibleTypeArgumentList
        SyntaxKind.IdentifierToken -> {
            val next = peekToken(1).kind
            if (options and (NameOptions.AfterIs or NameOptions.DefinitePattern or NameOptions.AfterOut) != 0 ||
                (options and NameOptions.AfterTupleComma != 0 && (next === SyntaxKind.CommaToken || next === SyntaxKind.CloseParenToken)) ||
                (options and NameOptions.FirstElementOfPossibleTupleLiteral != 0 && next === SyntaxKind.CommaToken)
            ) ScanTypeArgumentListKind.DefiniteTypeArgumentList
            else ScanTypeArgumentListKind.PossibleTypeArgumentList
        }
        else -> ScanTypeArgumentListKind.PossibleTypeArgumentList
    }
}

/** `ScanPossibleTypeArgumentList(out greaterThanToken, out isDefinitelyTypeArgumentList)` (LP 6415). */
fun LanguageParser.scanPossibleTypeArgumentList(): Pair<ScanTypeFlags, Boolean> {
    var isDefinitelyTypeArgumentList = false
    if (isOpenName()) {
        eatToken()
        while (currentKind === SyntaxKind.CommaToken) eatToken()
        eatToken()
        scanTypeLastTokenKind = SyntaxKind.GreaterThanToken
        return ScanTypeFlags.GenericTypeOrMethod to true
    }

    var result = ScanTypeFlags.GenericTypeOrExpression
    var lastScannedType: ScanTypeFlags? = null
    do {
        eatToken()
        if (currentKind === SyntaxKind.OpenBracketToken) return ScanTypeFlags.NotType to false
        if (currentKind === SyntaxKind.GreaterThanToken) {
            eatToken()
            scanTypeLastTokenKind = SyntaxKind.GreaterThanToken
            return result to isDefinitelyTypeArgumentList
        }
        if (currentKind === SyntaxKind.CommaToken) {
            lastScannedType = null
            continue
        }
        lastScannedType = scanType()
        when (lastScannedType) {
            ScanTypeFlags.NotType -> return ScanTypeFlags.NotType to false
            ScanTypeFlags.MustBeType -> {
                isDefinitelyTypeArgumentList = isDefinitelyTypeArgumentList || currentKind === SyntaxKind.CommaToken || currentKind === SyntaxKind.GreaterThanToken
                result = ScanTypeFlags.GenericTypeOrMethod
            }
            ScanTypeFlags.NullableType -> {
                isDefinitelyTypeArgumentList = isDefinitelyTypeArgumentList || currentKind === SyntaxKind.CommaToken || currentKind === SyntaxKind.GreaterThanToken
                if (isDefinitelyTypeArgumentList) result = ScanTypeFlags.GenericTypeOrMethod
            }
            ScanTypeFlags.GenericTypeOrExpression -> {
                if (!isDefinitelyTypeArgumentList) {
                    isDefinitelyTypeArgumentList = currentKind === SyntaxKind.CommaToken
                    result = ScanTypeFlags.GenericTypeOrMethod
                }
            }
            ScanTypeFlags.GenericTypeOrMethod -> result = ScanTypeFlags.GenericTypeOrMethod
            else -> {}
        }
    } while (currentKind === SyntaxKind.CommaToken)

    if (currentKind !== SyntaxKind.GreaterThanToken) {
        if (currentKind === SyntaxKind.IdentifierToken) {
            createMissingToken(SyntaxKind.GreaterThanToken, report = false)
            return result to isDefinitelyTypeArgumentList
        }
        if (lastScannedType == ScanTypeFlags.TupleType && currentKind === SyntaxKind.OpenParenToken) {
            createMissingToken(SyntaxKind.GreaterThanToken, report = false)
            return result to isDefinitelyTypeArgumentList
        }
        return ScanTypeFlags.NotType to false
    }

    eatToken()
    scanTypeLastTokenKind = SyntaxKind.GreaterThanToken
    isDefinitelyTypeArgumentList = isDefinitelyTypeArgumentList || currentKind === SyntaxKind.CloseParenToken
    if (isDefinitelyTypeArgumentList) result = ScanTypeFlags.GenericTypeOrMethod
    return result to isDefinitelyTypeArgumentList
}

/** `ParseTypeArgumentList` (LP 6604) */
fun LanguageParser.parseTypeArgumentList(): Node {
    val m = open()
    val isOpenName = isOpenName()
    eatToken(SyntaxKind.LessThanToken)
    // `CheckFeatureAvailability(open, IDS_FeatureGenerics)` (LP 6609): a diagnostic on `<` before C# 2.
    if (!isFeatureEnabled(CSharpFeature.Generics)) errorCount++
    if (isOpenName) {
        open().done(SyntaxKind.OmittedTypeArgument)
        while (currentKind === SyntaxKind.CommaToken) {
            eatToken(SyntaxKind.CommaToken)
            open().done(SyntaxKind.OmittedTypeArgument)
        }
        eatToken(SyntaxKind.GreaterThanToken)
        return m.done(SyntaxKind.TypeArgumentList)
    }

    parseTypeArgument()
    while (true) {
        if (currentKind === SyntaxKind.GreaterThanToken) break
        if (tokenBreaksTypeArgumentList(currentToken)) break
        if (currentKind === SyntaxKind.IdentifierToken && tokenBreaksTypeArgumentList(peekToken(1))) break
        if (currentKind === SyntaxKind.IdentifierToken && peekToken(1).kind === SyntaxKind.CloseBracketToken) break
        if (currentKind === SyntaxKind.CommaToken || isPossibleType()) {
            eatToken(SyntaxKind.CommaToken)
            parseTypeArgument()
        } else if (skipBadTypeArgumentListTokens() == PostSkipAction.Abort) {
            break
        }
    }
    eatToken(SyntaxKind.GreaterThanToken)
    return m.done(SyntaxKind.TypeArgumentList)
}

/** local `tokenBreaksTypeArgumentList` (LP 6677) */
private fun LanguageParser.tokenBreaksTypeArgumentList(token: Tok): Boolean {
    // Roslyn: `GetContextualKeywordKind(token.ValueText)`: `@or` breaks the list too.
    val contextualKind = contextualKindOfValueText(token)
    if (contextualKind === SyntaxKind.OrKeyword || contextualKind === SyntaxKind.AndKeyword) return true
    return when (token.kind) {
        SyntaxKind.OpenParenToken, SyntaxKind.LessThanToken, SyntaxKind.CloseParenToken, SyntaxKind.SemicolonToken,
        SyntaxKind.OpenBraceToken, SyntaxKind.CloseBraceToken, SyntaxKind.EqualsToken, SyntaxKind.EqualsGreaterThanToken,
        SyntaxKind.ThisKeyword, SyntaxKind.OperatorKeyword,
        -> true
        else -> false
    }
}

private fun LanguageParser.skipBadTypeArgumentListTokens(): PostSkipAction =
    skipBadSeparatedListTokensWithExpectedKind(
        isNotExpected = { currentKind !== SyntaxKind.CommaToken && !isPossibleType() },
        abort = { currentKind === SyntaxKind.GreaterThanToken },
        SyntaxKind.CommaToken, SyntaxKind.GreaterThanToken,
    )

/** `ParseTypeArgument` (LP 6740): attributes and variance tokens become skipped tokens. */
fun LanguageParser.parseTypeArgument(): Node {
    if (currentKind === SyntaxKind.OpenBracketToken && peekToken(1).kind !== SyntaxKind.CloseBracketToken) {
        // `AddError(result, ERR_TypeExpected)` on the type after the skipped attributes (LP 6801)
        skipAsError(CSharpErrorCode.ERR_TypeExpected.describe(anchor = CSharpDiagnosticAnchor.NEXT)) {
            val saveTerm = termState
            termState = TerminatorState.IsEndOfTypeArgumentList
            parseAttributeDeclarations(inExpressionContext = false)
            termState = saveTerm
        }
    }
    if (currentKind === SyntaxKind.InKeyword || currentKind === SyntaxKind.OutKeyword) skipTokens(1, CSharpErrorCode.ERR_IllegalVarianceSyntax.describe())
    val result = parseType()
    if (result.isMissing && currentKind !== SyntaxKind.CommaToken && currentKind !== SyntaxKind.GreaterThanToken &&
        (peekToken(1).kind === SyntaxKind.CommaToken || peekToken(1).kind === SyntaxKind.GreaterThanToken)
    ) {
        skipTokens(1)
    }
    return result
}

/** `IsOpenName` (LP 6810) */
fun LanguageParser.isOpenName(): Boolean {
    var n = 1
    while (peekToken(n).kind === SyntaxKind.CommaToken) n++
    return peekToken(n).kind === SyntaxKind.GreaterThanToken
}

/** `IsPossibleType` (LP 7216) */
fun LanguageParser.isPossibleType(): Boolean = CSharpSyntaxFacts.isPredefinedType(currentKind) || isTrueIdentifier()

/** `IsDotOrColonColon` (LP 6026) */
fun LanguageParser.isDotOrColonColon(): Boolean = currentKind === SyntaxKind.DotToken || currentKind === SyntaxKind.ColonColonToken

// ---- qualified names ------------------------------------------------------------------------------------------

/** `ParseAliasQualifiedName(allowedParts)` (LP 7087); `global` before `::` becomes the keyword. */
fun LanguageParser.parseAliasQualifiedName(allowedParts: Int = NameOptions.None, asExpression: Boolean = false): Node {
    if (currentContextualKind === SyntaxKind.GlobalKeyword && peekToken(1).kind === SyntaxKind.ColonColonToken) remapCurrentToken(SyntaxKind.GlobalKeyword)
    val name = parseSimpleName(allowedParts)
    if (currentKind !== SyntaxKind.ColonColonToken) return name
    return parseQualifiedNameRight(allowedParts, name, asExpression)
}

/**
 * `ParseQualifiedName(options)` (LP 7095). With [asExpression] the dotted parts are `SimpleMemberAccessExpression`s:
 * `ConvertTypeToExpression` (LPP 479) of the pattern parser, done by reparsing (docs/csharp-psi/GRAMMAR.md).
 */
fun LanguageParser.parseQualifiedName(options: Int = NameOptions.None, asExpression: Boolean = false): Node {
    var name = parseAliasQualifiedName(options, asExpression)
    while (isDotOrColonColon()) {
        if (peekToken(1).kind === SyntaxKind.ThisKeyword) break
        name = parseQualifiedNameRight(options, name, asExpression)
    }
    return name
}

/** `ParseQualifiedNameRight(options, left, separator)` (LP 7114); the separator is the current token. */
internal fun LanguageParser.parseQualifiedNameRight(options: Int, left: Node, asExpression: Boolean): Node {
    val separator = currentKind
    val wrapped: Node
    if (separator === SyntaxKind.DotToken) {
        eatToken()
        val right = parseSimpleName(options)
        wrapped = left.wrap(if (asExpression) SyntaxKind.SimpleMemberAccessExpression else SyntaxKind.QualifiedName)
        wrapped.inner = right
    } else {
        if (left.kind === SyntaxKind.IdentifierName) {
            eatToken()
            val right = parseSimpleName(options)
            wrapped = left.wrap(SyntaxKind.AliasQualifiedName)
            wrapped.inner = right
        } else {
            // ERR_UnexpectedAliasedName: the `::` is skipped in favour of a missing `.`.
            skipTokens(1, CSharpErrorCode.ERR_UnexpectedAliasedName.describe())
            val right = parseSimpleName(options)
            wrapped = left.wrap(if (asExpression) SyntaxKind.SimpleMemberAccessExpression else SyntaxKind.QualifiedName)
            wrapped.inner = right
        }
    }
    wrapped.left = left
    wrapped.isNameShaped = true
    return wrapped
}

/** `ConvertTypeToExpression` (LPP 479) would succeed on this type node. */
fun LanguageParser.isTypeConvertibleToExpression(type: Node, permitTypeArguments: Boolean = false): Boolean = when (type.kind) {
    SyntaxKind.GenericName -> permitTypeArguments
    SyntaxKind.IdentifierName -> true
    SyntaxKind.QualifiedName -> permitTypeArguments || type.inner?.kind !== SyntaxKind.GenericName
    else -> false
}

// ---- scanning types ---------------------------------------------------------------------------------------------

/** `ScanType(forPattern)` (LP 7224) */
fun LanguageParser.scanType(forPattern: Boolean = false): ScanTypeFlags =
    scanType(if (forPattern) ParseTypeMode.DefinitePattern else ParseTypeMode.Normal)

/** `ScanNamedTypePart(out lastTokenOfType)` (LP 7239) */
fun LanguageParser.scanNamedTypePart(): ScanTypeFlags {
    if (currentKind !== SyntaxKind.IdentifierToken || !isTrueIdentifier()) return ScanTypeFlags.NotType
    scanTypeLastTokenKind = SyntaxKind.IdentifierToken
    eatToken()
    return if (currentKind === SyntaxKind.LessThanToken) scanPossibleTypeArgumentList().first else ScanTypeFlags.NonGenericTypeOrExpression
}

/** `ScanType(mode, out lastTokenOfType)` (LP 7258) */
fun LanguageParser.scanType(mode: ParseTypeMode): ScanTypeFlags {
    // Tuple types recurse through `scanTupleType`; the depth guard applies to scans as to parses (SyntaxParser.MAX_DEPTH).
    recursionDepth++
    try {
        if (recursionDepth > SyntaxParser.MAX_DEPTH) return ScanTypeFlags.NotType
        return scanTypeCore(mode)
    } finally {
        recursionDepth--
    }
}

private fun LanguageParser.scanTypeCore(mode: ParseTypeMode): ScanTypeFlags {
    var result: ScanTypeFlags
    if (currentKind === SyntaxKind.RefKeyword) {
        eatToken()
        if (currentKind === SyntaxKind.ReadOnlyKeyword) eatToken()
    }

    if (currentKind === SyntaxKind.IdentifierToken || currentKind === SyntaxKind.ColonColonToken) {
        var isAlias: Boolean
        if (currentKind === SyntaxKind.ColonColonToken) {
            result = ScanTypeFlags.NonGenericTypeOrExpression
            isAlias = true
            scanTypeLastTokenKind = null
        } else {
            isAlias = peekToken(1).kind === SyntaxKind.ColonColonToken
            result = scanNamedTypePart()
            if (result == ScanTypeFlags.NotType) return ScanTypeFlags.NotType
        }
        var firstLoop = true
        while (isDotOrColonColon()) {
            if (!firstLoop) isAlias = false
            firstLoop = false
            eatToken()
            result = scanNamedTypePart()
            if (result == ScanTypeFlags.NotType) return ScanTypeFlags.NotType
        }
        if (isAlias) result = ScanTypeFlags.AliasQualifiedName
    } else if (CSharpSyntaxFacts.isPredefinedType(currentKind)) {
        scanTypeLastTokenKind = currentKind
        eatToken()
        result = ScanTypeFlags.MustBeType
    } else if (currentKind === SyntaxKind.OpenParenToken) {
        eatToken()
        result = scanTupleType()
        if (result == ScanTypeFlags.NotType || (mode == ParseTypeMode.DefinitePattern && currentKind !== SyntaxKind.OpenBracketToken)) return ScanTypeFlags.NotType
    } else if (isFunctionPointerStart()) {
        result = scanFunctionPointerType()
    } else {
        scanTypeLastTokenKind = null
        return ScanTypeFlags.NotType
    }

    val last = intArrayOf(-1)
    loop@ while (isMakingProgress(last)) {
        when (currentKind) {
            SyntaxKind.QuestionToken -> {
                if (scanTypeLastTokenKind === SyntaxKind.QuestionToken || scanTypeLastTokenKind === SyntaxKind.AsteriskToken) break@loop
                scanTypeLastTokenKind = SyntaxKind.QuestionToken
                eatToken()
                result = ScanTypeFlags.NullableType
            }
            SyntaxKind.AsteriskToken -> {
                when (mode) {
                    ParseTypeMode.FirstElementOfPossibleTupleLiteral, ParseTypeMode.AfterTupleComma -> {
                        if (!pointerTypeModsFollowedByRankAndDimensionSpecifier()) break@loop
                    }
                    ParseTypeMode.DefinitePattern -> break@loop
                    else -> {}
                }
                scanTypeLastTokenKind = SyntaxKind.AsteriskToken
                eatToken()
                if (result == ScanTypeFlags.GenericTypeOrExpression || result == ScanTypeFlags.NonGenericTypeOrExpression) result = ScanTypeFlags.PointerOrMultiplication
                else if (result == ScanTypeFlags.GenericTypeOrMethod) result = ScanTypeFlags.MustBeType
            }
            SyntaxKind.OpenBracketToken -> {
                eatToken()
                while (currentKind === SyntaxKind.CommaToken) eatToken()
                if (currentKind !== SyntaxKind.CloseBracketToken) {
                    scanTypeLastTokenKind = null
                    return ScanTypeFlags.NotType
                }
                scanTypeLastTokenKind = SyntaxKind.CloseBracketToken
                eatToken()
                result = ScanTypeFlags.MustBeType
            }
            else -> break@loop
        }
    }
    return result
}

/** `ScanTupleType(out lastTokenOfType)` (LP 7432); the `(` is already eaten. */
fun LanguageParser.scanTupleType(): ScanTypeFlags {
    var tupleElementType = scanType()
    if (tupleElementType != ScanTypeFlags.NotType) {
        if (isTrueIdentifier()) {
            scanTypeLastTokenKind = SyntaxKind.IdentifierToken
            eatToken()
        }
        if (currentKind === SyntaxKind.CommaToken) {
            do {
                eatToken()
                tupleElementType = scanType()
                if (tupleElementType == ScanTypeFlags.NotType) {
                    eatToken()
                    return ScanTypeFlags.NotType
                }
                if (isTrueIdentifier()) {
                    scanTypeLastTokenKind = SyntaxKind.IdentifierToken
                    eatToken()
                }
            } while (currentKind === SyntaxKind.CommaToken)
            if (currentKind === SyntaxKind.CloseParenToken) {
                scanTypeLastTokenKind = SyntaxKind.CloseParenToken
                eatToken()
                return ScanTypeFlags.TupleType
            }
        }
    }
    scanTypeLastTokenKind = null
    return ScanTypeFlags.NotType
}

/** `ScanFunctionPointerType(out lastTokenOfType)` (LP 7476) */
private fun LanguageParser.scanFunctionPointerType(): ScanTypeFlags {
    eatToken(SyntaxKind.DelegateKeyword)
    eatToken(SyntaxKind.AsteriskToken)
    scanTypeLastTokenKind = SyntaxKind.AsteriskToken
    if (currentKind === SyntaxKind.IdentifierToken) {
        val peek1 = peekToken(1)
        val tok = currentToken
        if (tok.contextualKind === SyntaxKind.ManagedKeyword || tok.contextualKind === SyntaxKind.UnmanagedKeyword ||
            isPossibleFunctionPointerParameterListStart(peek1) || peek1.kind === SyntaxKind.OpenBracketToken
        ) {
            eatToken()
            scanTypeLastTokenKind = SyntaxKind.IdentifierToken
        } else {
            return ScanTypeFlags.MustBeType
        }
        if (currentKind === SyntaxKind.OpenBracketToken) {
            eatToken()
            val saveTerm = termState
            termState = termState or TerminatorState.IsEndOfFunctionPointerCallingConvention
            try {
                while (true) {
                    tryEatToken(SyntaxKind.IdentifierToken)
                    if (skipBadFunctionPointerTokens() == PostSkipAction.Abort) break
                    eatToken()
                }
                tryEatToken(SyntaxKind.CloseBracketToken)
                scanTypeLastTokenKind = SyntaxKind.CloseBracketToken
            } finally {
                termState = saveTerm
            }
        }
    }
    if (!isPossibleFunctionPointerParameterListStart(currentToken)) return ScanTypeFlags.MustBeType

    val validStartingToken = currentKind === SyntaxKind.LessThanToken
    eatToken()
    val saveTerm = termState
    termState = termState or (if (validStartingToken) TerminatorState.IsEndOfFunctionPointerParameterList else TerminatorState.IsEndOfFunctionPointerParameterListErrored)
    try {
        while (true) {
            parseParameterModifiers(isFunctionPointerParameter = true, isLambdaParameter = false)
            scanType()
            if (skipBadFunctionPointerTokens() == PostSkipAction.Abort) break
            eatToken(SyntaxKind.CommaToken)
        }
    } finally {
        termState = saveTerm
    }
    if (!validStartingToken && currentKind === SyntaxKind.CloseParenToken) eatTokenAsKind(SyntaxKind.GreaterThanToken)
    else eatToken(SyntaxKind.GreaterThanToken)
    scanTypeLastTokenKind = SyntaxKind.GreaterThanToken
    return ScanTypeFlags.MustBeType
}

private fun LanguageParser.skipBadFunctionPointerTokens(): PostSkipAction =
    // `SkipBadTokensWithExpectedKind(expected: CommaToken)` (LP 7585)
    skipBadTokensWithErrorCode(
        isNotExpected = { currentKind !== SyntaxKind.CommaToken }, abort = { isTerminator() },
        SyntaxParser.expectedMessage(SyntaxKind.CommaToken, null, CSharpDiagnosticAnchor.FIRST_EXPECTED),
    )

// ---- parsing types ----------------------------------------------------------------------------------------------

/** `ParseTypeOrVoid` (LP 7605) */
fun LanguageParser.parseTypeOrVoid(): Node {
    if (currentKind === SyntaxKind.VoidKeyword && peekToken(1).kind !== SyntaxKind.AsteriskToken) return singleTokenNode(SyntaxKind.PredefinedType)
    return parseType()
}

/** `ParseType(mode)` (LP 7630) */
fun LanguageParser.parseType(mode: ParseTypeMode = ParseTypeMode.Normal): Node {
    if (currentKind === SyntaxKind.RefKeyword) {
        val m = open()
        eatToken()
        if (currentKind === SyntaxKind.ReadOnlyKeyword) eatToken()
        val inner = parseTypeCore(ParseTypeMode.AfterRef)
        val refType = m.done(SyntaxKind.RefType)
        refType.inner = inner
        return refType
    }
    return parseTypeCore(mode)
}

/** `ParseTypeCore(mode)` (LP 7643) */
private fun LanguageParser.parseTypeCore(mode: ParseTypeMode): Node {
    val nameOptions = when (mode) {
        ParseTypeMode.AfterIs -> NameOptions.InExpression or NameOptions.AfterIs or NameOptions.PossiblePattern
        ParseTypeMode.DefinitePattern -> NameOptions.InExpression or NameOptions.DefinitePattern or NameOptions.PossiblePattern
        ParseTypeMode.AfterOut -> NameOptions.InExpression or NameOptions.AfterOut
        ParseTypeMode.AfterTupleComma -> NameOptions.InExpression or NameOptions.AfterTupleComma
        ParseTypeMode.FirstElementOfPossibleTupleLiteral -> NameOptions.InExpression or NameOptions.FirstElementOfPossibleTupleLiteral
        else -> NameOptions.None
    }
    var type = parseUnderlyingType(mode, nameOptions)
    val last = intArrayOf(-1)
    loop@ while (isMakingProgress(last)) {
        when (currentKind) {
            SyntaxKind.QuestionToken -> {
                if (tryEatNullableQualifierIfApplicable(type, mode)) {
                    val elementType = type
                    type = type.wrap(SyntaxKind.NullableType)
                    type.inner = elementType // `NullableTypeSyntax.ElementType` (ParseUsingExpression, LP 10481)
                    continue@loop
                }
                break@loop
            }
            SyntaxKind.AsteriskToken -> {
                when (mode) {
                    ParseTypeMode.AfterIs, ParseTypeMode.DefinitePattern, ParseTypeMode.AfterTupleComma, ParseTypeMode.FirstElementOfPossibleTupleLiteral -> {
                        if (pointerTypeModsFollowedByRankAndDimensionSpecifier()) {
                            type = parsePointerTypeMods(type)
                            continue@loop
                        }
                        break@loop
                    }
                    else -> {
                        type = parsePointerTypeMods(type)
                        continue@loop
                    }
                }
            }
            SyntaxKind.OpenBracketToken -> {
                do {
                    parseArrayRankSpecifier()
                } while (currentKind === SyntaxKind.OpenBracketToken)
                type = type.wrap(SyntaxKind.ArrayType)
                continue@loop
            }
            else -> break@loop
        }
    }
    return type
}

/** `TryEatNullableQualifierIfApplicable` (LP 7746): eats the `?` when it is a nullable qualifier. */
private fun LanguageParser.tryEatNullableQualifierIfApplicable(typeParsedSoFar: Node, mode: ParseTypeMode): Boolean {
    if (typeParsedSoFar.kind === SyntaxKind.NullableType || typeParsedSoFar.kind === SyntaxKind.PointerType) return false
    return withResetPoint { rp ->
        eatToken()
        if (!canFollowNullableType(mode)) {
            rp.reset()
            false
        } else {
            true
        }
    }
}

/** local `canFollowNullableType` of `TryEatNullableQualifierIfApplicable` (LP 7770) */
private fun LanguageParser.canFollowNullableType(mode: ParseTypeMode): Boolean {
    if (mode == ParseTypeMode.AfterIs && currentKind === SyntaxKind.OpenBracketToken) {
        return when (peekToken(1).kind) {
            SyntaxKind.CommaToken -> true
            SyntaxKind.CloseBracketToken -> speculate {
                parsePossibleRefExpression()
                currentKind !== SyntaxKind.ColonToken
            }
            else -> false
        }
    }
    return when (mode) {
        ParseTypeMode.AfterIs, ParseTypeMode.DefinitePattern, ParseTypeMode.AsExpression -> {
            if (isTrueIdentifier(currentToken)) {
                val ck = currentContextualKind
                if (ck === SyntaxKind.AsyncKeyword || ck === SyntaxKind.AwaitKeyword || ck === SyntaxKind.FromKeyword) return false
                val nextToken = peekToken(1)
                if (nextToken.contextualKind === SyntaxKind.WithKeyword) return false
                val nextTokenKind = nextToken.kind
                return isLiteral(nextTokenKind) || CSharpSyntaxFacts.isPredefinedType(nextTokenKind) ||
                    nextTokenKind === SyntaxKind.CloseParenToken || nextTokenKind === SyntaxKind.CloseBracketToken ||
                    nextTokenKind === SyntaxKind.CloseBraceToken || nextTokenKind === SyntaxKind.OpenBraceToken ||
                    nextTokenKind === SyntaxKind.CommaToken || nextTokenKind === SyntaxKind.SemicolonToken ||
                    nextTokenKind === SyntaxKind.EndOfFileToken
            }
            if (currentKind === SyntaxKind.OpenBracketToken) return true
            !canStartExpression()
        }
        ParseTypeMode.NewExpression -> currentKind === SyntaxKind.OpenParenToken || currentKind === SyntaxKind.OpenBracketToken || currentKind === SyntaxKind.OpenBraceToken
        else -> true
    }
}

/** `SyntaxFacts.IsLiteral(kind)` */
fun isLiteral(kind: IElementType): Boolean = when (kind) {
    SyntaxKind.IdentifierToken, SyntaxKind.StringLiteralToken, SyntaxKind.Utf8StringLiteralToken, SyntaxKind.CharacterLiteralToken,
    SyntaxKind.NumericLiteralToken, SyntaxKind.XmlTextLiteralToken, SyntaxKind.XmlTextLiteralNewLineToken, SyntaxKind.XmlEntityLiteralToken,
    SyntaxKind.SingleLineRawStringLiteralToken, SyntaxKind.Utf8SingleLineRawStringLiteralToken, SyntaxKind.MultiLineRawStringLiteralToken,
    SyntaxKind.Utf8MultiLineRawStringLiteralToken,
    -> true
    else -> false
}

/** `PointerTypeModsFollowedByRankAndDimensionSpecifier` (LP 7906) */
fun LanguageParser.pointerTypeModsFollowedByRankAndDimensionSpecifier(): Boolean {
    var i = 0
    while (true) {
        when (peekToken(i).kind) {
            SyntaxKind.AsteriskToken -> i++
            SyntaxKind.OpenBracketToken -> return true
            else -> return false
        }
    }
}

/**
 * `ParseArrayRankSpecifier(out sawNonOmittedSize)` (LP 7924). With [asArgumentList] the result is the
 * `BracketedArgumentList` of a C-style array declarator (LP 5686-5724), each size wrapped in an `Argument`.
 * Omitted sizes are empty `OmittedArraySizeExpression` nodes; when sizes are mixed they are missing names instead,
 * decided by a reparse (the nodes are already built when the mix is known).
 */
fun LanguageParser.parseArrayRankSpecifier(asArgumentList: Boolean = false): Boolean {
    return withResetPoint { rp ->
        val first = parseArrayRankSpecifierCore(asArgumentList, omittedAsMissingName = false)
        if (first.sawOmitted && first.sawNonOmitted) {
            rp.reset()
            parseArrayRankSpecifierCore(asArgumentList, omittedAsMissingName = true).sawNonOmitted
        } else {
            first.sawNonOmitted
        }
    }
}

private class RankResult(val sawOmitted: Boolean, val sawNonOmitted: Boolean)

private fun LanguageParser.parseArrayRankSpecifierCore(asArgumentList: Boolean, omittedAsMissingName: Boolean): RankResult {
    val m = open()
    var sawNonOmittedSize = false
    var sawOmittedSize = false
    var count = 0
    eatToken(SyntaxKind.OpenBracketToken)

    fun omitted() {
        sawOmittedSize = true
        val arg = if (asArgumentList) open() else null
        // `AddError(CreateMissingIdentifierName(), offset: 0, width of the omitted size, ERR_ValueExpected)`: zero-width where it stands (LP 7974)
        if (omittedAsMissingName) addError(createMissingIdentifierName(CSharpErrorCode.ERR_ValueExpected.describe(anchor = CSharpDiagnosticAnchor.HERE)))
        else open().done(SyntaxKind.OmittedArraySizeExpression)
        arg?.done(SyntaxKind.Argument)
        count++
    }

    val last = intArrayOf(-1)
    while (isMakingProgress(last) && currentKind !== SyntaxKind.CloseBracketToken) {
        if (currentKind === SyntaxKind.CommaToken) {
            omitted()
            eatToken()
            count++
        } else if (isPossibleExpression()) {
            val arg = if (asArgumentList) open() else null
            parseExpressionCore()
            arg?.done(SyntaxKind.Argument)
            sawNonOmittedSize = true
            count++
            if (currentKind !== SyntaxKind.CloseBracketToken) {
                eatToken(SyntaxKind.CommaToken)
                count++
            }
        } else if (skipBadArrayRankSpecifierTokens() == PostSkipAction.Abort) {
            break
        }
    }
    if (count and 1 == 0) omitted()
    eatToken(SyntaxKind.CloseBracketToken)
    m.done(if (asArgumentList) SyntaxKind.BracketedArgumentList else SyntaxKind.ArrayRankSpecifier)
    return RankResult(sawOmittedSize, sawNonOmittedSize)
}

private fun LanguageParser.skipBadArrayRankSpecifierTokens(): PostSkipAction =
    skipBadSeparatedListTokensWithExpectedKind(
        isNotExpected = { currentKind !== SyntaxKind.CommaToken && !isPossibleExpression() },
        abort = { currentKind === SyntaxKind.CloseBracketToken },
        SyntaxKind.CommaToken, SyntaxKind.CloseBracketToken,
    )

/** `ParseTupleType` (LP 7984) */
fun LanguageParser.parseTupleType(): Node {
    val m = open()
    eatToken(SyntaxKind.OpenParenToken)
    var count = 0
    if (currentKind !== SyntaxKind.CloseParenToken) {
        parseTupleElement()
        count++
        while (currentKind === SyntaxKind.CommaToken) {
            eatToken(SyntaxKind.CommaToken)
            parseTupleElement()
            count++
        }
    }
    if (count < 2) {
        if (count < 1) {
            val e = open()
            createMissingIdentifierName(SKIPPED)
            e.done(SyntaxKind.TupleElement)
        }
        createMissingToken(SyntaxKind.CommaToken, report = false)
        val e = open()
        addError(createMissingIdentifierName(CSharpErrorCode.ERR_TupleTooFewElements.describe()))
        e.done(SyntaxKind.TupleElement)
    }
    eatToken(SyntaxKind.CloseParenToken)
    lastTupleTypeElementCount = count
    return m.done(SyntaxKind.TupleType)
}

/** `ParseTupleElement` (LP 8018) */
private fun LanguageParser.parseTupleElement(): Node {
    val m = open()
    parseType()
    if (isTrueIdentifier()) parseIdentifierToken()
    return m.done(SyntaxKind.TupleElement)
}

/** `ParseUnderlyingType(mode, options)` (LP 8033) */
fun LanguageParser.parseUnderlyingType(mode: ParseTypeMode, options: Int = NameOptions.None, asExpression: Boolean = false): Node {
    if (CSharpSyntaxFacts.isPredefinedType(currentKind)) {
        val isVoid = currentKind === SyntaxKind.VoidKeyword
        val node = singleTokenNode(SyntaxKind.PredefinedType)
        if (isVoid && currentKind !== SyntaxKind.AsteriskToken) addError(node)
        return node
    }
    if (isTrueIdentifier() || currentKind === SyntaxKind.ColonColonToken) return parseQualifiedName(options, asExpression)
    if (currentKind === SyntaxKind.OpenParenToken) return parseTupleType()
    if (isFunctionPointerStart()) return parseFunctionPointerTypeSyntax()
    val code = if (mode == ParseTypeMode.NewExpression) CSharpErrorCode.ERR_BadNewExpr else CSharpErrorCode.ERR_TypeExpected
    return addError(createMissingIdentifierName(code.describe()))!!
}

/** `ParseFunctionPointerTypeSyntax` (LP 8068) */
fun LanguageParser.parseFunctionPointerTypeSyntax(): Node {
    val m = open()
    eatToken(SyntaxKind.DelegateKeyword)
    eatToken(SyntaxKind.AsteriskToken)
    parseFunctionPointerCallingConvention()
    if (!isPossibleFunctionPointerParameterListStart(currentToken)) {
        val list = open()
        createMissingToken(SyntaxKind.LessThanToken)
        val p = open()
        createMissingIdentifierName(SKIPPED)
        p.done(SyntaxKind.FunctionPointerParameter)
        if (!tryEatToken(SyntaxKind.GreaterThanToken)) createMissingToken(SyntaxKind.GreaterThanToken, report = false)
        list.done(SyntaxKind.FunctionPointerParameterList)
        return m.done(SyntaxKind.FunctionPointerType)
    }
    val list = open()
    val lessThanMissing = !eatTokenAsKind(SyntaxKind.LessThanToken)
    val saveTerm = termState
    termState = termState or (if (lessThanMissing) TerminatorState.IsEndOfFunctionPointerParameterListErrored else TerminatorState.IsEndOfFunctionPointerParameterList)
    try {
        while (true) {
            val p = open()
            parseParameterModifiers(isFunctionPointerParameter = true, isLambdaParameter = false)
            parseTypeOrVoid()
            p.done(SyntaxKind.FunctionPointerParameter)
            if (skipBadFunctionPointerListTokens() == PostSkipAction.Abort) break
            eatToken(SyntaxKind.CommaToken)
        }
        if (lessThanMissing && currentKind === SyntaxKind.CloseParenToken) eatTokenAsKind(SyntaxKind.GreaterThanToken) else eatToken(SyntaxKind.GreaterThanToken)
    } finally {
        termState = saveTerm
    }
    list.done(SyntaxKind.FunctionPointerParameterList)
    return m.done(SyntaxKind.FunctionPointerType)
}

private fun LanguageParser.skipBadFunctionPointerListTokens(): PostSkipAction =
    skipBadSeparatedListTokensWithExpectedKind(
        isNotExpected = { currentKind !== SyntaxKind.CommaToken },
        abort = { false },
        SyntaxKind.CommaToken, SyntaxKind.GreaterThanToken,
    )

/** local `parseCallingConvention` of `ParseFunctionPointerTypeSyntax` (LP 8151) */
private fun LanguageParser.parseFunctionPointerCallingConvention(): Node? {
    if (currentKind !== SyntaxKind.IdentifierToken) return null
    val peek1 = peekToken(1)
    val m = open()
    val ck = currentContextualKind
    val managed: Boolean
    when {
        ck === SyntaxKind.ManagedKeyword || ck === SyntaxKind.UnmanagedKeyword -> {
            managed = ck === SyntaxKind.ManagedKeyword
            eatContextualToken(ck)
        }
        isPossibleFunctionPointerParameterListStart(peek1) -> {
            managed = true
            eatTokenAsKind(SyntaxKind.ManagedKeyword)
        }
        peek1.kind === SyntaxKind.OpenBracketToken -> {
            managed = false
            eatTokenAsKind(SyntaxKind.UnmanagedKeyword)
        }
        else -> {
            m.drop()
            return null
        }
    }
    if (currentKind === SyntaxKind.OpenBracketToken) {
        val list = open()
        eatToken(SyntaxKind.OpenBracketToken)
        val saveTerm = termState
        termState = termState or TerminatorState.IsEndOfFunctionPointerCallingConvention
        try {
            while (true) {
                val c = open()
                eatToken(SyntaxKind.IdentifierToken)
                c.done(SyntaxKind.FunctionPointerUnmanagedCallingConvention)
                if (skipBadSeparatedListTokensWithExpectedKind({ currentKind !== SyntaxKind.CommaToken }, { false }, SyntaxKind.CommaToken, SyntaxKind.CloseBracketToken) == PostSkipAction.Abort) break
                eatToken(SyntaxKind.CommaToken)
            }
            eatToken(SyntaxKind.CloseBracketToken)
        } finally {
            termState = saveTerm
        }
        val node = list.done(SyntaxKind.FunctionPointerUnmanagedCallingConventionList)
        if (managed) addError(node)
    }
    return m.done(SyntaxKind.FunctionPointerCallingConvention)
}

/** `IsFunctionPointerStart` (LP 8228) */
fun LanguageParser.isFunctionPointerStart(): Boolean = currentKind === SyntaxKind.DelegateKeyword && peekToken(1).kind === SyntaxKind.AsteriskToken

/** `IsPossibleFunctionPointerParameterListStart` (LP 8231) */
fun isPossibleFunctionPointerParameterListStart(token: Tok): Boolean = token.kind === SyntaxKind.LessThanToken || token.kind === SyntaxKind.OpenParenToken

/** `ParsePointerTypeMods` (LP 8237) */
fun LanguageParser.parsePointerTypeMods(type: Node): Node {
    var result = type
    while (currentKind === SyntaxKind.AsteriskToken) {
        eatToken()
        result = result.wrap(SyntaxKind.PointerType)
    }
    return result
}

/** `ParseReturnType` (LP 3754) */
fun LanguageParser.parseReturnType(): Node {
    val saveTerm = termState
    termState = termState or TerminatorState.IsEndOfReturnType
    val type = parseTypeOrVoid()
    termState = saveTerm
    return type
}

// ---- attributes (LP 1047-1300) ---------------------------------------------------------------------------------

/** `IsPossibleAttributeDeclaration` (LP 1047) */
fun LanguageParser.isPossibleAttributeDeclaration(): Boolean {
    if (currentKind !== SyntaxKind.OpenBracketToken) return false
    return speculate {
        eatToken()
        when {
            isTrueIdentifier() -> true
            isAttributeTarget() -> true
            CSharpSyntaxFacts.isLiteralExpression(currentKind) -> false
            else -> true
        }
    }
}

/** `ParseAttributeDeclarations(inExpressionContext)` (LP 1075) */
fun LanguageParser.parseAttributeDeclarations(inExpressionContext: Boolean): AttributeLists {
    val saveTerm = termState
    termState = termState or TerminatorState.IsAttributeDeclarationTerminator
    if (saveTerm == termState) return AttributeLists(0, false)
    var count = 0
    var lastCloseMissing = false
    while (isPossibleAttributeDeclaration()) {
        val declaration = tryParseAttributeDeclaration(inExpressionContext) ?: break
        count++
        lastCloseMissing = declaration.lastTokenMissing
    }
    termState = saveTerm
    return AttributeLists(count, lastCloseMissing)
}

/** `IsAttributeDeclarationTerminator` (LP 1110) */
fun LanguageParser.isAttributeDeclarationTerminator(): Boolean = currentKind === SyntaxKind.CloseBracketToken || isPossibleAttributeDeclaration()

/** `IsAttributeTarget` (LP 1116) */
private fun LanguageParser.isAttributeTarget(): Boolean = isSomeWord(currentKind) && peekToken(1).kind === SyntaxKind.ColonToken

/** `TryParseAttributeDeclaration(inExpressionContext)` (LP 1119) */
internal fun LanguageParser.tryParseAttributeDeclaration(inExpressionContext: Boolean): Node? {
    return withResetPoint { rp ->
        val m = open()
        eatToken(SyntaxKind.OpenBracketToken)
        if (isAttributeTarget()) {
            val t = open()
            convertToKeyword()
            eatToken(SyntaxKind.ColonToken)
            t.done(SyntaxKind.AttributeTargetSpecifier)
        }
        parseCommaSeparatedSyntaxList(
            SyntaxKind.CloseBracketToken,
            isPossibleElement = { isPossibleAttribute() },
            parseElement = { parseAttribute() },
            skipBadTokens = { expected, close ->
                skipBadSeparatedListTokensWithExpectedKind(
                    isNotExpected = { currentKind !== SyntaxKind.CommaToken && !isPossibleAttribute() },
                    abort = { c -> currentKind === c },
                    expected, close,
                )
            },
            allowTrailingSeparator = true,
            requireOneElement = true,
            allowSemicolonAsSeparator = false,
        )
        eatToken(SyntaxKind.CloseBracketToken)
        if (inExpressionContext && shouldParseAsCollectionExpression()) {
            m.drop()
            rp.reset()
            null
        } else {
            m.done(SyntaxKind.AttributeList)
        }
    }
}

/** local `shouldParseAsCollectionExpression` (LP 1152) */
private fun LanguageParser.shouldParseAsCollectionExpression(): Boolean =
    currentKind === SyntaxKind.DotToken || currentKind === SyntaxKind.MinusGreaterThanToken ||
        (currentKind === SyntaxKind.QuestionToken && peekToken(1).kind === SyntaxKind.DotToken)

/** `IsPossibleAttribute` (LP 1192) */
private fun LanguageParser.isPossibleAttribute(): Boolean = isTrueIdentifier()

/** `ParseAttribute` (LP 1197) */
private fun LanguageParser.parseAttribute(): Node {
    val m = open()
    parseQualifiedName()
    parseAttributeArgumentList()
    return m.done(SyntaxKind.Attribute)
}

/** `ParseAttributeArgumentList` (LP 1209); the `immediatelyAbort` on bad string literals is not ported. */
private fun LanguageParser.parseAttributeArgumentList(): Node? {
    if (currentKind !== SyntaxKind.OpenParenToken) return null
    val m = open()
    eatToken(SyntaxKind.OpenParenToken)
    parseCommaSeparatedSyntaxList(
        SyntaxKind.CloseParenToken,
        isPossibleElement = { isPossibleExpression() },
        parseElement = { parseAttributeArgument() },
        skipBadTokens = { expected, close ->
            skipBadSeparatedListTokensWithExpectedKind(
                isNotExpected = { currentKind !== SyntaxKind.CommaToken && !isPossibleExpression() },
                abort = { c -> currentKind === c },
                expected, close,
            )
        },
        allowTrailingSeparator = false,
        requireOneElement = false,
        allowSemicolonAsSeparator = false,
        immediatelyAbort = { lastAttributeArgumentAborts },
    )
    eatToken(SyntaxKind.CloseParenToken)
    return m.done(SyntaxKind.AttributeArgumentList)
}

/**
 * local `immediatelyAbort(argument)` of `ParseAttributeArgumentList` (LP 1246): the argument is an unterminated regular
 * string literal (Roslyn: `ERR_NewlineInConst` on the token) or an interpolated string without its end token; the
 * next line would otherwise be read as more arguments. Set by [parseAttributeArgument] for the argument just parsed.
 */
var LanguageParser.lastAttributeArgumentAborts: Boolean
    get() = attributeArgumentAborts
    set(value) { attributeArgumentAborts = value }

/** `"` + text without a closing quote on the same line (the lexer stops a regular string at the line end). */
private fun isUnterminatedRegularString(text: CharSequence): Boolean {
    if (text.isEmpty() || text[0] != '"') return false
    var i = 1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i++
            '"' -> return false
        }
        i++
    }
    return true
}

/** `ParseAttributeArgument` (LP 1269) */
private fun LanguageParser.parseAttributeArgument(): Node {
    val m = open()
    if (currentKind === SyntaxKind.IdentifierToken) {
        when (peekToken(1).kind) {
            SyntaxKind.EqualsToken -> parseNameEquals()
            SyntaxKind.ColonToken -> {
                val nc = open()
                parseIdentifierName()
                eatToken(SyntaxKind.ColonToken)
                nc.done(SyntaxKind.NameColon)
            }
        }
    }
    val first = currentToken
    val expression = parseExpressionCore()
    lastAttributeArgumentAborts = when (expression.kind) {
        SyntaxKind.StringLiteralExpression -> first.kind === SyntaxKind.StringLiteralToken &&
            isUnterminatedRegularString(builder.originalText.subSequence(first.start, first.end))
        SyntaxKind.InterpolatedStringExpression -> first.kind === SyntaxKind.InterpolatedStringStartToken && expression.lastTokenMissing
        else -> false
    }
    return m.done(SyntaxKind.AttributeArgument)
}

// ---- parameters (LP 4870-5100) -----------------------------------------------------------------------------------

/** `ParseParenthesizedParameterList(forExtensionOrUnion)` (LP 4792) */
fun LanguageParser.parseParenthesizedParameterList(forExtensionOrUnion: Boolean = false): Node {
    val m = open()
    parseParameterList(SyntaxKind.OpenParenToken, SyntaxKind.CloseParenToken, forExtensionOrUnion)
    return m.done(SyntaxKind.ParameterList)
}

/** `ParseBracketedParameterList` (LP 4803) */
fun LanguageParser.parseBracketedParameterList(): Node {
    val m = open()
    parseParameterList(SyntaxKind.OpenBracketToken, SyntaxKind.CloseBracketToken, forExtensionOrUnion = false)
    return m.done(SyntaxKind.BracketedParameterList)
}

/** `ParseParameterList` (LP 4870) */
private fun LanguageParser.parseParameterList(openKind: IElementType, closeKind: IElementType, forExtensionOrUnion: Boolean) {
    eatToken(openKind)
    val saveTerm = termState
    termState = termState or TerminatorState.IsEndOfParameterList
    parameterListHadCleanParameter = false
    parseCommaSeparatedSyntaxList(
        closeKind,
        isPossibleElement = { isPossibleParameter() },
        parseElement = {
            val errors = errorCount
            parseParameter(identifierIsOptional = forExtensionOrUnion)
            if (errorCount == errors) parameterListHadCleanParameter = true
        },
        skipBadTokens = { expected, close ->
            skipBadSeparatedListTokensWithExpectedKind(
                isNotExpected = { currentKind !== SyntaxKind.CommaToken && !isPossibleParameter() },
                abort = { c -> currentKind === c },
                expected, close,
            )
        },
        allowTrailingSeparator = false,
        requireOneElement = forExtensionOrUnion,
        allowSemicolonAsSeparator = false,
    )
    termState = saveTerm
    eatToken(closeKind)
}

/** `IsPossibleParameter` (LP 4916) */
fun LanguageParser.isPossibleParameter(): Boolean = when (currentKind) {
    SyntaxKind.OpenBracketToken, SyntaxKind.ArgListKeyword, SyntaxKind.OpenParenToken -> true
    SyntaxKind.DelegateKeyword -> isFunctionPointerStart()
    SyntaxKind.IdentifierToken -> isTrueIdentifier() || isDefiniteScopedModifier(isFunctionPointerParameter = false, isLambdaParameter = false)
    else -> isParameterModifierExcludingScoped(currentToken) || CSharpSyntaxFacts.isPredefinedType(currentKind)
}

/** `ParseParameter(identifierIsOptional)` (LP 4980); the receiver of an extension block may have no name. */
fun LanguageParser.parseParameter(identifierIsOptional: Boolean = false): Node {
    val m = open()
    parseAttributeDeclarations(inExpressionContext = false)
    parseParameterModifiers(isFunctionPointerParameter = false, isLambdaParameter = false)
    if (currentKind === SyntaxKind.ArgListKeyword) {
        eatToken()
        return m.done(SyntaxKind.Parameter)
    }
    parseType(ParseTypeMode.Parameter)
    var hasIdentifier = true
    if (currentKind === SyntaxKind.IdentifierToken && isCurrentTokenWhereOfConstraintClause()) {
        if (identifierIsOptional) hasIdentifier = false else createMissingToken(SyntaxKind.IdentifierToken)
    } else if (identifierIsOptional && currentKind !== SyntaxKind.IdentifierToken) {
        hasIdentifier = false
    } else {
        parseIdentifierToken()
    }
    if (hasIdentifier && currentKind === SyntaxKind.OpenBracketToken && peekToken(1).kind === SyntaxKind.CloseBracketToken) skipTokens(2, CSharpErrorCode.ERR_BadArraySyntax.describe(anchor = CSharpDiagnosticAnchor.FIRST))
    if (currentKind === SyntaxKind.EqualsToken) {
        val ev = open()
        eatToken()
        parseExpressionCore()
        ev.done(SyntaxKind.EqualsValueClause)
    }
    return m.done(SyntaxKind.Parameter)
}

/** `IsParameterModifierIncludingScoped` (LP 5038) */
fun LanguageParser.isParameterModifierIncludingScoped(token: Tok): Boolean = isParameterModifierExcludingScoped(token) || token.contextualKind === SyntaxKind.ScopedKeyword

/** `IsParameterModifierExcludingScoped` (LP 5041) */
fun isParameterModifierExcludingScoped(token: Tok): Boolean = when (token.kind) {
    SyntaxKind.ThisKeyword, SyntaxKind.RefKeyword, SyntaxKind.OutKeyword, SyntaxKind.InKeyword, SyntaxKind.ParamsKeyword, SyntaxKind.ReadOnlyKeyword -> true
    else -> false
}

/** `ParseParameterModifiers` (LP 5057) */
fun LanguageParser.parseParameterModifiers(isFunctionPointerParameter: Boolean, isLambdaParameter: Boolean) {
    var seenScoped = false
    while (true) {
        if (isParameterModifierExcludingScoped(currentToken)) {
            eatToken()
            continue
        }
        if (isDefiniteScopedModifier(isFunctionPointerParameter, isLambdaParameter)) {
            if (!seenScoped) {
                seenScoped = true
                eatContextualToken(SyntaxKind.ScopedKeyword)
                continue
            }
            val next = peekToken(1).kind
            if (next !== SyntaxKind.CloseParenToken && next !== SyntaxKind.CommaToken && next !== SyntaxKind.EqualsToken) {
                eatContextualToken(SyntaxKind.ScopedKeyword)
                continue
            }
        }
        return
    }
}

/** `IsDefiniteScopedModifier` (LP 10647) */
fun LanguageParser.isDefiniteScopedModifier(isFunctionPointerParameter: Boolean, isLambdaParameter: Boolean): Boolean {
    if (currentContextualKind !== SyntaxKind.ScopedKeyword) return false
    // C# 14: within a lambda `scoped` is always a modifier (`scoped scoped` is modifier + identifier).
    if (isLambdaParameter && isFeatureEnabled(CSharpFeature.SimpleLambdaParameterModifiers)) return true
    return speculate {
        eatToken()
        if (isParameterModifierExcludingScoped(currentToken)) true
        else scanType() != ScanTypeFlags.NotType && when {
            isFunctionPointerParameter -> currentKind === SyntaxKind.CommaToken || currentKind === SyntaxKind.GreaterThanToken
            else -> currentKind === SyntaxKind.IdentifierToken
        }
    }
}

/** `ParsePossibleScopedKeyword` (LP 10695): true when `scoped` was eaten. */
fun LanguageParser.parsePossibleScopedKeyword(isFunctionPointerParameter: Boolean, isLambdaParameter: Boolean): Boolean {
    if (!isDefiniteScopedModifier(isFunctionPointerParameter, isLambdaParameter)) return false
    eatContextualToken(SyntaxKind.ScopedKeyword)
    return true
}
