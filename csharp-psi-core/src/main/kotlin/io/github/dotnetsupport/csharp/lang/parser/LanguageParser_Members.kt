// Ported from Roslyn: src/Compilers/CSharp/Portable/Parser/LanguageParser.cs, member declarations
// (ParseMemberDeclarationOrStatementCore 2605, IsMisplacedModifier 3039, IsNoneOrIncompleteMember 3064,
// ReconsideredTypeAsAsyncModifier 3168, TryParseIndexerOrPropertyDeclaration 3191, IsStartOfPropertyBody 3219,
// ParseMemberDeclarationCore 3254, ReconsiderTypeAsAsyncModifier 3422, IsFieldDeclaration 3445, IsComplete 3493,
// ParseConstructorDeclaration 3525, TryParseConstructorInitializer 3546, ParseConstructorInitializer 3560,
// ParseDestructorDeclaration 3588, ParseBlockAndExpressionBodiesWithSemicolon 3609, ParseMethodDeclaration 3695,
// TryParseConversionOperatorDeclaration 3776, TryEatCheckedOrHandleUnchecked 4004, ParseOperatorDeclaration 4017,
// ParseIndexerDeclaration 4217, ParsePropertyDeclaration 4277, ParseAccessorList 4400, ParseArrowExpressionClause 4437,
// IsPossibleAccessor 4463, IsPossibleAccessorModifier 4473, ParseAccessorDeclaration 4674,
// ParseFixedSizeBufferDeclaration 5106, ParseEventDeclaration 5124, ParseEventDeclarationWithAccessors 5139,
// EatUnexpectedTrailingSemicolon 5229, ParseNormalFieldDeclaration 5242, ParseEventFieldDeclaration 5264,
// ParseFieldDeclarationVariableDeclarators 5304, ParseVariableDeclarators 5336, ParseVariableDeclarator 5527,
// ParseConstantFieldDeclaration 5867, ParseMemberName 6820, AccumulateExplicitInterfaceName 6958, IsOperatorStart 7005,
// IsPossibleTopLevelUsingLocalDeclarationStatement 8719, IsAnonymousDelegateExpression 8939, IsPossibleNewExpression
// 8987, ParseMethodOrAccessorBodyBlock 9122, TryParseLocalFunctionStatementBody 10985),
// roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c. Roslyn: Copyright (c) .NET Foundation and Contributors, MIT
// License (NOTICE.md). Departures: docs/csharp-psi/GRAMMAR.md, "Declarations".
package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpDiagnosticAnchor
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpErrorCode

// ---- the start of a member: attributes and modifiers, replayable ------------------------------------------------------

/**
 * The member marker (open before the attributes) and the modifiers of one attempt. Roslyn resets to points after the
 * attributes, the modifiers, the type or the member name and then *forward* again; a PsiBuilder rollback invalidates
 * every marker made after the point, so each attempt restarts at [MemberContext.start] (before the attributes) and
 * replays the deterministic prefix (docs/csharp-psi/GRAMMAR.md, "Declarations: replay").
 */
class MemberStart(val m: Open, val mods: Modifiers, val haveAttributes: Boolean, val isPossibleTypeDeclaration: Boolean)

/** The reset point before the attributes of a member and how its prefix is parsed. */
class MemberContext(val start: SyntaxParser.ResetPoint, val global: Boolean, val parentKind: IElementType)

/** Parses (again) attributes and modifiers of the member at [MemberContext.start]; [reset] rolls back first. */
private fun LanguageParser.startMember(ctx: MemberContext, reset: Boolean): MemberStart {
    if (reset) ctx.start.reset()
    val m = open()
    val attributes = if (ctx.global) parseStatementAttributeDeclarations() else parseAttributeDeclarations(inExpressionContext = false)
    val mods = Modifiers()
    val isPossibleTypeDeclaration = parseModifiers(mods, forAccessors = false, forTopLevelStatements = ctx.global)
    return MemberStart(m, mods, attributes.count > 0, isPossibleTypeDeclaration)
}

/** The type of a member, after [asyncReconsidered] turned the identifier `async` into a modifier (LP 3169). */
private fun LanguageParser.parseMemberType(st: MemberStart, asyncReconsidered: Boolean): Node {
    if (asyncReconsidered) {
        convertToKeyword()
        st.mods.kinds += SyntaxKind.AsyncKeyword
    }
    return parseReturnType()
}

// ---- ParseMemberDeclaration ---------------------------------------------------------------------------------------------

/** `ParseMemberDeclaration(parentKind)` (LP 3245); null when nothing here starts a member (nothing is consumed). */
fun LanguageParser.parseMemberDeclaration(parentKind: IElementType): Node? {
    recursionDepth++
    val saveTerm = termState
    val rp = getResetPoint()
    try {
        if (recursionDepth > SyntaxParser.MAX_DEPTH) return null
        return parseMemberDeclarationCore(MemberContext(rp, global = false, parentKind))
    } finally {
        rp.release()
        termState = saveTerm
        recursionDepth--
    }
}

/** `ParseMemberDeclarationCore(parentKind)` (LP 3254) */
private fun LanguageParser.parseMemberDeclarationCore(ctx: MemberContext): Node? {
    var st = startMember(ctx, reset = false)
    if (isExtensionContainerStart()) return parseMainTypeDeclaration(st.m, st.mods)
    if (currentKind === SyntaxKind.IdentifierToken && peekToken(1).kind === SyntaxKind.OpenParenToken) return parseConstructorDeclaration(st)
    if (currentKind === SyntaxKind.TildeToken) return parseDestructorDeclaration(st)
    if (currentKind === SyntaxKind.ConstKeyword) return parseConstantFieldDeclaration(st, ctx.parentKind)
    if (currentKind === SyntaxKind.EventKeyword) return parseEventDeclaration(st, ctx.parentKind)
    if (currentKind === SyntaxKind.FixedKeyword) return parseFixedSizeBufferDeclaration(st, ctx.parentKind)
    tryParseConversionOperatorDeclaration(st)?.let { return it }
    if (st.isPossibleTypeDeclaration && isTypeDeclarationStartExact()) return parseTypeDeclaration(st.m, st.mods)

    var asyncReconsidered = false
    var type = parseMemberType(st, false)
    if (isMisplacedModifier(type)) return finishMember(st.m, SyntaxKind.IncompleteMember, st.mods)
    while (true) { // parse_member_name
        if (type.kind !== SyntaxKind.RefType) {
            val op = isOperatorStart(advanceParser = true)
            if (op != null) return parseOperatorDeclaration(ctx, st, type)
        }
        if (isFieldDeclaration(isEvent = false, isGlobalScriptLevel = false)) return parseNormalFieldDeclaration(st, type, ctx.parentKind)
        val name = parseMemberName(isEvent = false, typeIsMissing = type.isMissing)
        when (isNoneOrIncompleteMember(st, type, name)) {
            IncompleteResult.None -> {
                ctx.start.reset()
                return null
            }
            IncompleteResult.Incomplete -> return finishMember(st.m, SyntaxKind.IncompleteMember, st.mods)
            IncompleteResult.IncompleteWithoutType -> {
                st = startMember(ctx, reset = true)
                return finishMember(st.m, SyntaxKind.IncompleteMember, st.mods)
            }
            IncompleteResult.NotIncomplete -> Unit

        }
        if (!asyncReconsidered && reconsiderTypeAsAsyncModifier(st, type, name)) {
            st = startMember(ctx, reset = true)
            asyncReconsidered = true
            type = parseMemberType(st, true)
            continue
        }
        tryParseIndexerOrPropertyDeclaration(st, name)?.let { return it }
        return parseMethodDeclaration(st)
    }
}

// ---- ParseMemberDeclarationOrStatement ------------------------------------------------------------------------------

/** What the token after the attributes of a top-level member or statement allows. */
private class AfterAttributes(
    val haveAttributes: Boolean,
    val first: Tok,
    val second: Tok,
    val isPossibleStatement: Boolean,
    val statementSwitch: Boolean,
)

/** `ParseMemberDeclarationOrStatement(parentKind)` (LP 2590); null when nothing here starts a member or statement. */
fun LanguageParser.parseMemberDeclarationOrStatement(parentKind: IElementType): Node? {
    recursionDepth++
    val saveTerm = termState
    val rp = getResetPoint()
    try {
        if (recursionDepth > SyntaxParser.MAX_DEPTH) return null
        return parseMemberDeclarationOrStatementCore(MemberContext(rp, global = true, parentKind))
    } finally {
        rp.release()
        termState = saveTerm
        recursionDepth--
    }
}

/** `ParseMemberDeclarationOrStatementCore(parentKind)` (LP 2605), not a script. */
private fun LanguageParser.parseMemberDeclarationOrStatementCore(ctx: MemberContext): Node? {
    val info = speculate {
        val attributes = parseStatementAttributeDeclarations()
        val first = currentToken
        val second = peekToken(1)
        val switch = when (currentKind) {
            SyntaxKind.UnsafeKeyword -> second.kind === SyntaxKind.OpenBraceToken || second.kind === SyntaxKind.OpenParenToken
            SyntaxKind.FixedKeyword -> second.kind === SyntaxKind.OpenParenToken
            SyntaxKind.DelegateKeyword -> isAnonymousDelegateExpression()
            SyntaxKind.NewKeyword -> isPossibleNewExpression()
            else -> false
        }
        AfterAttributes(
            attributes.count > 0, first, second,
            isPossibleStatement = currentKind !== SyntaxKind.CloseBraceToken && !currentToken.isEof && isPossibleStatement(),
            statementSwitch = switch,
        )
    }
    if (info.statementSwitch) {
        // unsafe {, unsafe (, fixed (: ParseStatementCore dispatches them; delegate/new: an expression statement.
        return globalStatement {
            if (info.first.kind === SyntaxKind.DelegateKeyword || info.first.kind === SyntaxKind.NewKeyword) {
                val s = open()
                parseStatementAttributeDeclarations()
                parseExpressionStatement(s)
            } else {
                parsePossiblyAttributedStatement()
            }
        }?.node
    }

    var st = startMember(ctx, reset = false)
    val haveAttributes = st.haveAttributes
    val haveModifiers = st.mods.count > 0

    if (currentKind === SyntaxKind.IdentifierToken && peekToken(1).kind === SyntaxKind.OpenParenToken && (haveAttributes || haveModifiers)) {
        tryParseLocalDeclarationStatementFromStartPoint(ctx, SyntaxKind.LocalFunctionStatement)?.let { return it }
        st = startMember(ctx, reset = true)
    }
    if (currentKind === SyntaxKind.ConstKeyword) {
        tryParseLocalDeclarationStatementFromStartPoint(ctx, SyntaxKind.LocalDeclarationStatement)?.let { return it }
        st = startMember(ctx, reset = true)
        return parseConstantFieldDeclaration(st, ctx.parentKind)
    }
    if (currentKind === SyntaxKind.EventKeyword) return parseEventDeclaration(st, ctx.parentKind)
    if (currentKind === SyntaxKind.FixedKeyword) return parseFixedSizeBufferDeclaration(st, ctx.parentKind)
    tryParseConversionOperatorDeclaration(st)?.let { return it }
    if (currentKind === SyntaxKind.NamespaceKeyword) return parseNamespaceDeclaration(st.m, st.mods)
    if (st.isPossibleTypeDeclaration && isTypeDeclarationStartExact()) return parseTypeDeclaration(st.m, st.mods)

    var asyncReconsidered = false
    var type = parseMemberType(st, false)

    if (!haveModifiers && (type.kind === SyntaxKind.RefType || !isOperatorStartAhead())) {
        if (info.isPossibleStatement) {
            ctx.start.reset()
            val statement = globalStatement {
                parsePossiblyAttributedStatement(statementTerm = TerminatorState.IsPossibleStatementStartOrStop)
            }
            if (statement != null && isAcceptableNonDeclarationStatement(statement.statement, info)) return statement.node
            st = startMember(ctx, reset = true)
            type = parseMemberType(st, false)
        }
    }

    if (isMisplacedModifier(type)) return finishMember(st.m, SyntaxKind.IncompleteMember, st.mods)

    while (true) { // parse_member_name
        val typeIsRef = type.kind === SyntaxKind.RefType
        if (!typeIsRef && isOperatorStart(advanceParser = true) != null) return parseOperatorDeclaration(ctx, st, type)

        if (isFieldDeclaration(isEvent = false, isGlobalScriptLevel = true)) {
            // The `_termState |= IsPossibleStatementStartOrStop` of Roslyn is undone by its Reset (the reset point holds
            // the terminator state).
            tryParseLocalDeclarationStatement(ctx, SyntaxKind.LocalDeclarationStatement)?.let { return it }
            st = startMember(ctx, reset = true)
            type = parseMemberType(st, asyncReconsidered)
            if (!typeIsRef) return parseNormalFieldDeclaration(st, type, ctx.parentKind)
        }

        var name = parseMemberName(isEvent = false, typeIsMissing = type.isMissing)

        if (!haveModifiers && !haveAttributes && name.isEmpty && !type.isMissing && type.kind !== SyntaxKind.RefType &&
            !isFollowedByPossibleUsingDirective()
        ) {
            tryParseLocalDeclarationStatementFromStartPoint(ctx, SyntaxKind.LocalDeclarationStatement)?.let { return it }
            st = startMember(ctx, reset = true)
            type = parseMemberType(st, asyncReconsidered)
            name = replayMemberName(type)
        }

        when (isNoneOrIncompleteMember(st, type, name)) {
            IncompleteResult.None -> {
                ctx.start.reset()
                return null
            }
            IncompleteResult.Incomplete -> return finishMember(st.m, SyntaxKind.IncompleteMember, st.mods)
            IncompleteResult.IncompleteWithoutType -> {
                st = startMember(ctx, reset = true)
                return finishMember(st.m, SyntaxKind.IncompleteMember, st.mods)
            }
            IncompleteResult.NotIncomplete -> Unit

        }

        if (!asyncReconsidered && reconsiderTypeAsAsyncModifier(st, type, name)) {
            st = startMember(ctx, reset = true)
            asyncReconsidered = true
            type = parseMemberType(st, true)
            continue
        }

        tryParseIndexerOrPropertyDeclaration(st, name)?.let { return it }

        if (name.explicitInterface == null) {
            tryParseLocalDeclarationStatementFromStartPoint(ctx, SyntaxKind.LocalFunctionStatement)?.let { return it }
            st = startMember(ctx, reset = true)
            type = parseMemberType(st, asyncReconsidered)
            name = replayMemberName(type)
        }
        if (!haveModifiers) {
            // tryParseStatement
            ctx.start.reset()
            val possible = speculate {
                parseStatementAttributeDeclarations()
                isPossibleStatement()
            }
            if (possible) {
                val statement = globalStatement {
                    parsePossiblyAttributedStatement(statementTerm = TerminatorState.IsPossibleStatementStartOrStop)
                }
                if (statement != null) return statement.node
            }
            st = startMember(ctx, reset = true)
            type = parseMemberType(st, asyncReconsidered)
            name = replayMemberName(type)
        }
        return parseMethodDeclaration(st)
    }
}

/**
 * Replays what lies between the type and the member name after a forward reset: `IsOperatorStart` and
 * `IsFieldDeclaration` were false and consumed nothing, so only `ParseMemberName` runs again.
 */
private fun LanguageParser.replayMemberName(type: Node): MemberName = parseMemberName(isEvent = false, typeIsMissing = type.isMissing)

/** A `GlobalStatement` around the statement [body] parses (in an async context, as top-level code is); null if none. */
private fun LanguageParser.globalStatement(body: () -> Node?): GlobalStatementResult? {
    val g = open()
    val saveAsync = isInAsync
    isInAsync = true
    val statement = try {
        body()
    } finally {
        isInAsync = saveAsync
    }
    if (statement == null) {
        g.drop()
        return null
    }
    return GlobalStatementResult(g.done(SyntaxKind.GlobalStatement), statement)
}

/** A `GlobalStatement` and the statement inside it. */
private class GlobalStatementResult(val node: Node, val statement: Node)

/** local `tryParseLocalDeclarationStatementFromStartPoint<T>` (LP 2982): from the start of the member. */
private fun LanguageParser.tryParseLocalDeclarationStatementFromStartPoint(ctx: MemberContext, kind: IElementType): Node? {
    ctx.start.reset()
    return tryParseLocalDeclarationStatementHere(kind)
}

/** local `tryParseLocalDeclarationStatement<T>` (LP 2934) after `Reset(ref afterAttributesPoint)`. */
private fun LanguageParser.tryParseLocalDeclarationStatement(ctx: MemberContext, kind: IElementType): Node? {
    ctx.start.reset()
    return tryParseLocalDeclarationStatementHere(kind)
}

private fun LanguageParser.tryParseLocalDeclarationStatementHere(kind: IElementType): Node? {
    val g = open()
    val s = open()
    val attributes = parseStatementAttributeDeclarations()
    val saveAsync = isInAsync
    isInAsync = true
    val start = position
    val statement = try {
        parseLocalDeclarationStatement(s, hadAttributes = attributes.count > 0)
    } finally {
        isInAsync = saveAsync
    }
    if (statement != null && statement.kind === kind && position > start) return g.done(SyntaxKind.GlobalStatement)
    // The caller restarts the member, which rolls these markers back.
    return null
}

/** local `isAcceptableNonDeclarationStatement` (LP 2997), not a script; the parser is right after [statement]. */
private fun LanguageParser.isAcceptableNonDeclarationStatement(statement: Node, info: AfterAttributes): Boolean {
    return when (statement.kind) {
        SyntaxKind.LocalFunctionStatement -> false
        // `Expression.Kind == IdentifierName && SemicolonToken.IsMissing` (not `await` + a missing operand).
        SyntaxKind.ExpressionStatement -> !(statement.lastTokenMissing && statement.inner?.kind === SyntaxKind.IdentifierName)
        SyntaxKind.LocalDeclarationStatement ->
            info.first.kind === SyntaxKind.UsingKeyword || (info.first.contextualKind === SyntaxKind.AwaitKeyword && info.second.kind === SyntaxKind.UsingKeyword)
        else -> true
    }
}

/** local `isFollowedByPossibleUsingDirective` (LP 3019) */
private fun LanguageParser.isFollowedByPossibleUsingDirective(): Boolean {
    if (currentKind === SyntaxKind.UsingKeyword) return !isPossibleTopLevelUsingLocalDeclarationStatement()
    if (currentContextualKind === SyntaxKind.GlobalKeyword && peekToken(1).kind === SyntaxKind.UsingKeyword) {
        return speculate {
            eatToken()
            !isPossibleTopLevelUsingLocalDeclarationStatement()
        }
    }
    return false
}

/** `IsPossibleTopLevelUsingLocalDeclarationStatement` (LP 8719) */
fun LanguageParser.isPossibleTopLevelUsingLocalDeclarationStatement(): Boolean {
    if (currentKind !== SyntaxKind.UsingKeyword) return false
    val tk = peekToken(1).kind
    if (tk === SyntaxKind.RefKeyword) return true
    if (isDeclarationModifier(tk)) {
        if (tk !== SyntaxKind.StaticKeyword) return true
    } else if (CSharpSyntaxFacts.isPredefinedType(tk)) {
        return true
    }
    return speculate {
        eatToken()
        if (isDefiniteScopedModifier(isFunctionPointerParameter = false, isLambdaParameter = false)) {
            true
        } else {
            if (tk === SyntaxKind.StaticKeyword) eatToken()
            isPossibleFirstTypedIdentifierInLocalDeclarationStatement()
        }
    }
}

/** `IsAnonymousDelegateExpression` (LP 8939) */
private fun LanguageParser.isAnonymousDelegateExpression(): Boolean {
    val next = peekToken(1)
    if (next.kind === SyntaxKind.OpenBraceToken) return true
    if (next.kind !== SyntaxKind.OpenParenToken) return false
    return speculate {
        eatToken()
        eatToken()
        !(scanTupleType() == ScanTypeFlags.TupleType && currentKind === SyntaxKind.IdentifierToken)
    }
}

/** `IsPossibleNewExpression` (LP 8987) */
private fun LanguageParser.isPossibleNewExpression(): Boolean {
    val next = peekToken(1)
    if (next.kind === SyntaxKind.OpenBraceToken || next.kind === SyntaxKind.OpenBracketToken) return true
    if (CSharpSyntaxFacts.getBaseTypeDeclarationKind(next.kind) != null) return false
    val modifier = getModifierExcludingScoped(next)
    if (modifier === SyntaxKind.PartialKeyword) {
        if (CSharpSyntaxFacts.isPredefinedType(peekToken(2).kind)) return false
        if (isTypeModifierOrTypeKeyword(peekToken(2).kind)) return false
    } else if (modifier != null) {
        return false
    }
    isPossibleTypedIdentifierStart(next, peekToken(2), allowThisKeyword = true)?.let { return !it }
    return speculate {
        eatToken()
        val st = scanType()
        !isPossibleMemberName() || st == ScanTypeFlags.NotType
    }
}

// ---- shared member helpers ---------------------------------------------------------------------------------------------

/** `IsMisplacedModifier` (LP 3039): a modifier after a complete type. */
private fun LanguageParser.isMisplacedModifier(type: Node): Boolean {
    if (getModifierExcludingScoped(currentToken) == null) return false
    when (currentContextualKind) {
        SyntaxKind.PartialKeyword, SyntaxKind.AsyncKeyword, SyntaxKind.RequiredKeyword, SyntaxKind.FileKeyword, SyntaxKind.ClosedKeyword,
        SyntaxKind.SafeKeyword,
        -> return false
    }
    if (!isComplete(type)) return false
    errorCount++ // ERR_BadModifierLocation
    return true
}

/** `IsComplete(node)` (LP 3493): the last token is not missing. */
private fun isComplete(node: Node): Boolean = !node.lastTokenMissing

private enum class IncompleteResult { NotIncomplete, None, Incomplete, IncompleteWithoutType }

/** `IsNoneOrIncompleteMember` (LP 3064) */
private fun LanguageParser.isNoneOrIncompleteMember(st: MemberStart, type: Node, name: MemberName): IncompleteResult {
    if (!name.isEmpty) return IncompleteResult.NotIncomplete
    if (looksLikeStartOfPropertyBody(st, type)) return IncompleteResult.NotIncomplete
    if (!st.haveAttributes && st.mods.count == 0 && type.isMissing && type.kind !== SyntaxKind.RefType) return IncompleteResult.None
    if (!type.containsErrorDiagnostic) {
        // ERR_NamespaceUnexpected (namespace or compilation unit) or ERR_InvalidMemberDecl: a diagnostic only.
        errorCount++
    }
    if (type.isMissing) return IncompleteResult.IncompleteWithoutType // `type.IsMissing ? null : type`
    return IncompleteResult.Incomplete
}

/** local `looksLikeStartOfPropertyBody` of `IsNoneOrIncompleteMember` (LP 3115) */
private fun LanguageParser.looksLikeStartOfPropertyBody(st: MemberStart, type: Node): Boolean {
    if (st.mods.count == 0 && !looksLikePropertyType(type)) return false
    return isStartOfPropertyBody(0)
}

/** local `looksLikePropertyType` (LP 3127) */
private fun looksLikePropertyType(type: Node): Boolean {
    if (type.isMissing || type.containsErrorDiagnostic) return false
    val t = if (type.kind === SyntaxKind.RefType) type.inner ?: return true else type
    if (t.kind === SyntaxKind.IdentifierName && t.identifierContextualKind != null && CSharpSyntaxFacts.isKeywordKind(t.identifierContextualKind)) return false
    return true
}

/** `ReconsideredTypeAsAsyncModifier` (LP 3168) + `ReconsiderTypeAsAsyncModifier` (LP 3422): the condition only. */
private fun LanguageParser.reconsiderTypeAsAsyncModifier(st: MemberStart, type: Node, name: MemberName): Boolean {
    if (type.kind === SyntaxKind.RefType || name.identifierKind == null) return false
    if (!(name.typeParameterListHadErrors ||
            (currentKind !== SyntaxKind.OpenParenToken && currentKind !== SyntaxKind.OpenBraceToken && currentKind !== SyntaxKind.EqualsGreaterThanToken))
    ) return false
    if (type.kind !== SyntaxKind.IdentifierName) return false
    if (name.identifierKind !== SyntaxKind.IdentifierToken) return false
    if (type.identifierContextualKind !== SyntaxKind.AsyncKeyword || st.mods.has(SyntaxKind.AsyncKeyword)) return false
    return true
}

/** `IsStartOfPropertyBody(index)` (LP 3219) */
fun LanguageParser.isStartOfPropertyBody(index: Int): Boolean {
    var kind = peekToken(index).kind
    if (kind === SyntaxKind.OpenBraceToken || kind === SyntaxKind.EqualsGreaterThanToken) return true
    if (kind === SyntaxKind.SemicolonToken) {
        kind = peekToken(index + 1).kind
        if (kind === SyntaxKind.OpenBraceToken || kind === SyntaxKind.EqualsGreaterThanToken) return true
    }
    return false
}

/** `IsFieldDeclaration(isEvent, isGlobalScriptLevel)` (LP 3445) */
private fun LanguageParser.isFieldDeclaration(isEvent: Boolean, isGlobalScriptLevel: Boolean): Boolean {
    if (currentKind !== SyntaxKind.IdentifierToken) return false
    if (currentContextualKind === SyntaxKind.GlobalKeyword && peekToken(1).kind === SyntaxKind.UsingKeyword) return false
    if (!isGlobalScriptLevel && isStartOfPropertyBody(1)) return false
    return when (peekToken(1).kind) {
        SyntaxKind.DotToken, SyntaxKind.ColonColonToken, SyntaxKind.LessThanToken, SyntaxKind.OpenBraceToken, SyntaxKind.EqualsGreaterThanToken -> false
        SyntaxKind.OpenParenToken -> isEvent
        else -> true
    }
}

/** `IsOperatorKeyword` (LP 3488) */
private fun LanguageParser.isOperatorKeyword(): Boolean =
    currentKind === SyntaxKind.ImplicitKeyword || currentKind === SyntaxKind.ExplicitKeyword || currentKind === SyntaxKind.OperatorKeyword

// ---- member names and explicit interface specifiers ------------------------------------------------------------------

/** What `ParseMemberName` produced. */
class MemberName(
    val explicitInterface: Node?,
    /** `IdentifierToken`, `ThisKeyword`, or null when there is no identifier. */
    val identifierKind: IElementType?,
    val hasTypeParameterList: Boolean,
    val typeParameterListHadErrors: Boolean,
) {
    val isEmpty: Boolean get() = explicitInterface == null && identifierKind == null && !hasTypeParameterList
}

/** The explicit interface name being accumulated (`AccumulateExplicitInterfaceName`, LP 6958). */
private class InterfaceName {
    var name: Node? = null
    /** The separator after [name] is a real `::` that makes the next part an `AliasQualifiedName`. */
    var aliasColonColon = false
    /** The last separator is a real `.` followed by a line break. */
    var lastDotEndsLine = false
}

/**
 * `AccumulateExplicitInterfaceName` (LP 6958). The fate of a `::` separator depends on what follows it, so [isFinal]
 * (speculative, at the token after the separator) decides it before the separator is eaten: a final `::` becomes a
 * missing `.` with the `::` skipped (`ConvertToMissingWithTrailingTrivia`, ERR_AliasQualAsExpression), and so does any
 * `::` after a non-identifier name (ERR_UnexpectedAliasedName); `global` before a non-final `::` becomes the keyword.
 */
private fun LanguageParser.accumulateExplicitInterfaceName(acc: InterfaceName, isFinal: () -> Boolean) {
    val saveTerm = termState
    termState = termState or TerminatorState.IsEndOfNameInExplicitInterface
    val left = acc.name
    if (left == null) {
        if (currentContextualKind === SyntaxKind.GlobalKeyword && peekToken(1).kind === SyntaxKind.ColonColonToken &&
            !speculate {
                eatToken()
                eatToken()
                isFinal()
            }
        ) remapCurrentToken(SyntaxKind.GlobalKeyword)
        val name = parseSimpleName(NameOptions.InTypeList)
        acc.name = name
        acc.aliasColonColon = false
        acc.lastDotEndsLine = false
        if (currentKind === SyntaxKind.ColonColonToken) {
            val final = speculate {
                eatToken()
                isFinal()
            }
            if (final || name.kind !== SyntaxKind.IdentifierName) {
                // `GetExpectedTokenError(DotToken, ...)` on the `::`, then `ConvertToMissingWithTrailingTrivia` (LP 7079)
                createMissingToken(SyntaxKind.DotToken, message = SKIPPED)
                skipTokens(1, SyntaxParser.expectedMessage(SyntaxKind.DotToken, SyntaxKind.ColonColonToken))
            } else {
                eatToken()
                acc.aliasColonColon = true
            }
        } else {
            eatSeparatorDot(acc)
        }
    } else {
        parseSimpleName(NameOptions.InTypeList)
        val wrapped = left.wrap(if (acc.aliasColonColon) SyntaxKind.AliasQualifiedName else SyntaxKind.QualifiedName)
        wrapped.left = left
        wrapped.isNameShaped = true
        acc.name = wrapped
        acc.aliasColonColon = false
        acc.lastDotEndsLine = false
        if (currentKind === SyntaxKind.ColonColonToken) {
            // `AddError(separator, ERR_UnexpectedAliasedName)`, then `ConvertToMissingWithTrailingTrivia` (LP 6988)
            createMissingToken(SyntaxKind.DotToken, message = SKIPPED)
            skipTokens(1, CSharpErrorCode.ERR_UnexpectedAliasedName.describe())
        } else {
            eatSeparatorDot(acc)
        }
    }
    termState = saveTerm
}

private fun LanguageParser.eatSeparatorDot(acc: InterfaceName) {
    // Raw steps of a Tok are relative to the current token: ask before eating.
    val endsLine = currentKind === SyntaxKind.DotToken && hasTrailingNewline(currentToken)
    if (eatToken(SyntaxKind.DotToken)) acc.lastDotEndsLine = endsLine
}

/**
 * The loop of `ParseMemberName`/`IsOperatorStart`/`tryParseExplicitInterfaceSpecifier`: parts are accumulated while
 * [isPart] holds; the `ExplicitInterfaceSpecifier` is completed right after the last separator. Null when no part.
 */
private fun LanguageParser.parseExplicitInterfaceParts(acc: InterfaceName, isPart: () -> Boolean): Node? {
    val ei = open()
    while (isPart()) accumulateExplicitInterfaceName(acc) { !isPart() }
    if (acc.name == null) {
        ei.drop()
        return null
    }
    return ei.done(SyntaxKind.ExplicitInterfaceSpecifier)
}

/**
 * `ParseMemberName(out explicitInterfaceOpt, out identifierOrThisOpt, out typeParameterListOpt, isEvent)` (LP 6820).
 * A type parameter list after the name of a property, an indexer or an event is skipped syntax in Roslyn
 * (`AddTrailingSkippedSyntax`, ERR_UnexpectedGenericName); which member it is follows from the tokens after the list,
 * so it is decided here by a speculative parse of the list. For an event, `this` becomes a missing identifier with
 * `this` skipped.
 */
private fun LanguageParser.parseMemberName(isEvent: Boolean, typeIsMissing: Boolean): MemberName {
    if (!isPossibleMemberName()) return MemberName(null, null, false, false)
    val acc = InterfaceName()
    val isPart = {
        currentKind !== SyntaxKind.ThisKeyword && speculate {
            scanNamedTypePart()
            isDotOrColonColon()
        }
    }
    val ei = parseExplicitInterfaceParts(acc, isPart)

    if (isEvent && ei != null && acc.lastDotEndsLine) {
        // Roslyn parses the identifier and resets to before it when the event has no accessor list after it.
        val reset = speculate {
            if (currentKind === SyntaxKind.ThisKeyword) eatToken() else parseIdentifierToken()
            if (currentKind === SyntaxKind.LessThanToken) parseTypeParameterList()
            currentKind !== SyntaxKind.OpenBraceToken && currentKind !== SyntaxKind.SemicolonToken
        }
        if (reset) return MemberName(ei, null, false, false)
    }

    val identifierKind: IElementType
    if (currentKind === SyntaxKind.ThisKeyword) {
        identifierKind = SyntaxKind.ThisKeyword
        if (isEvent) {
            // `ConvertToMissingWithTrailingTrivia(this)`, then `AddError(identifier, ERR_IdentifierExpected)` on the skipped `this` (LP 5181)
            createMissingToken(SyntaxKind.IdentifierToken, report = !typeIsMissing, message = CSharpErrorCode.ERR_IdentifierExpected.describe(anchor = CSharpDiagnosticAnchor.NEXT))
            skipTokens(1)
        } else {
            eatToken()
        }
    } else {
        identifierKind = SyntaxKind.IdentifierToken
        parseIdentifierToken()
    }

    var hasTpl = false
    var tplErrors = false
    if (currentKind === SyntaxKind.LessThanToken) {
        hasTpl = true
        val (errors, propertyBody) = speculate {
            val before = errorCount
            parseTypeParameterList()
            (errorCount > before) to isStartOfPropertyBody(0)
        }
        tplErrors = errors
        if (isEvent || identifierKind === SyntaxKind.ThisKeyword || propertyBody) {
            // `AddError(identifier, ERR_UnexpectedGenericName)` with the list as its skipped syntax: on the identifier (LP 4289)
            skipAsError(CSharpErrorCode.ERR_UnexpectedGenericName.describe(anchor = CSharpDiagnosticAnchor.PREVIOUS)) { parseTypeParameterList() }
        } else {
            parseTypeParameterList()
        }
    }
    return MemberName(ei, identifierKind, hasTpl, tplErrors)
}

/**
 * `IsOperatorStart(out explicitInterfaceOpt, advanceParser: true)` (LP 7005): non-null when an operator declaration
 * starts here, after its explicit interface specifier (if any) has been parsed; the value is the specifier or [Unit].
 */
private fun LanguageParser.isOperatorStart(advanceParser: Boolean): Any? {
    if (isOperatorKeyword()) return Unit
    if (currentKind !== SyntaxKind.IdentifierToken) return null
    val isPart = {
        !isOperatorKeyword() && speculate {
            scanNamedTypePart()
            isDotOrColonColon() || isOperatorKeyword()
        }
    }
    val found = speculate {
        val ei = parseExplicitInterfaceParts(InterfaceName(), isPart)
        isOperatorKeyword() && ei != null
    }
    if (!found) return null
    if (!advanceParser) return Unit
    return parseExplicitInterfaceParts(InterfaceName(), isPart) ?: Unit
}

/** `IsOperatorStart(out _, advanceParser: false)` */
private fun LanguageParser.isOperatorStartAhead(): Boolean = isOperatorStart(advanceParser = false) != null

// ---- methods, constructors, destructors ---------------------------------------------------------------------------------

/** `ParseMethodDeclaration` (LP 3695); the return type, explicit interface, name and type parameters are parsed. */
private fun LanguageParser.parseMethodDeclaration(st: MemberStart): Node {
    val saveTerm = termState
    termState = termState or TerminatorState.IsEndOfMethodSignature
    parseParenthesizedParameterList()
    if (currentContextualKind === SyntaxKind.WhereKeyword) {
        parseTypeParameterConstraintClauses()
    } else if (currentKind === SyntaxKind.ColonToken) {
        // A constructor initializer on a method: skipped after the parameter list (ERR_UnexpectedToken).
        skipAsError(CSharpErrorCode.ERR_UnexpectedToken.describe(":", anchor = CSharpDiagnosticAnchor.FIRST)) { parseConstructorInitializer() }
    }
    termState = saveTerm
    val saveAsync = isInAsync
    isInAsync = st.mods.has(SyntaxKind.AsyncKeyword)
    try {
        parseBlockAndExpressionBodiesWithSemicolon()
    } finally {
        isInAsync = saveAsync
    }
    return finishMember(st.m, SyntaxKind.MethodDeclaration, st.mods)
}

/** `ParseConstructorDeclaration` (LP 3525) */
private fun LanguageParser.parseConstructorDeclaration(st: MemberStart): Node {
    parseIdentifierToken()
    val saveTerm = termState
    termState = termState or TerminatorState.IsEndOfMethodSignature
    try {
        parseParenthesizedParameterList()
        tryParseConstructorInitializer()
        parseBlockAndExpressionBodiesWithSemicolon()
    } finally {
        termState = saveTerm
    }
    return finishMember(st.m, SyntaxKind.ConstructorDeclaration, st.mods)
}

/** `TryParseConstructorInitializer` (LP 3546) */
private fun LanguageParser.tryParseConstructorInitializer() {
    val k = currentKind
    val shouldParse = k === SyntaxKind.ColonToken ||
        (k === SyntaxKind.EqualsGreaterThanToken && peekToken(1).kind.let { it === SyntaxKind.ThisKeyword || it === SyntaxKind.BaseKeyword } &&
            peekToken(2).kind === SyntaxKind.OpenParenToken)
    if (shouldParse) parseConstructorInitializer()
}

/** `ParseConstructorInitializer` (LP 3560) */
private fun LanguageParser.parseConstructorInitializer() {
    val m = open()
    eatTokenAsKind(SyntaxKind.ColonToken)
    val kind: IElementType
    var tokenHasError = false
    if (currentKind === SyntaxKind.BaseKeyword || currentKind === SyntaxKind.ThisKeyword) {
        kind = if (currentKind === SyntaxKind.BaseKeyword) SyntaxKind.BaseConstructorInitializer else SyntaxKind.ThisConstructorInitializer
        eatToken()
    } else {
        kind = SyntaxKind.ThisConstructorInitializer
        createMissingToken(SyntaxKind.ThisKeyword, message = CSharpErrorCode.ERR_ThisOrBaseExpected.describe()) // EatToken(ThisKeyword, ERR_ThisOrBaseExpected)
        tokenHasError = true
    }
    if (currentKind === SyntaxKind.OpenParenToken) {
        parseParenthesizedArgumentList()
    } else {
        val a = open()
        eatToken(SyntaxKind.OpenParenToken, reportError = !tokenHasError)
        eatToken(SyntaxKind.CloseParenToken, reportError = !tokenHasError)
        a.done(SyntaxKind.ArgumentList)
    }
    m.done(kind)
}

/** `ParseDestructorDeclaration` (LP 3588) */
private fun LanguageParser.parseDestructorDeclaration(st: MemberStart): Node {
    eatToken(SyntaxKind.TildeToken)
    parseIdentifierToken()
    val p = open()
    eatToken(SyntaxKind.OpenParenToken)
    eatToken(SyntaxKind.CloseParenToken)
    p.done(SyntaxKind.ParameterList)
    parseBlockAndExpressionBodiesWithSemicolon()
    return finishMember(st.m, SyntaxKind.DestructorDeclaration, st.mods)
}

/**
 * `ParseBlockAndExpressionBodiesWithSemicolon(out blockBody, out expressionBody, out semicolon, parseSemicolonAfterBlock)`
 * (LP 3605); returns whether a block or an expression body was parsed. [localFunction]: the body of a local function
 * (an error-sensitive reparseable body, docs/csharp-psi/GRAMMAR.md "Reparseable bodies").
 */
fun LanguageParser.parseBlockAndExpressionBodiesWithSemicolon(parseSemicolonAfterBlock: Boolean = true, localFunction: Boolean = false): Boolean {
    if (currentKind === SyntaxKind.SemicolonToken) {
        eatToken()
        return false
    }
    val block = currentKind === SyntaxKind.OpenBraceToken
    if (block) parseMethodOrAccessorBodyBlock(isAccessorBody = false, localFunction)
    val arrow = currentKind === SyntaxKind.EqualsGreaterThanToken
    if (arrow) parseArrowExpressionClause()
    if (arrow || !block) {
        eatToken(SyntaxKind.SemicolonToken)
    } else if (parseSemicolonAfterBlock && currentKind === SyntaxKind.SemicolonToken) {
        eatTokenWithPrejudice(CSharpErrorCode.ERR_UnexpectedSemicolon.describe())
    }
    return block || arrow
}

/**
 * `ParseMethodOrAccessorBodyBlock(attributes: default, isAccessorBody)` (LP 9122): the one entry point of member,
 * accessor and local function bodies, a reparseable body ([parseBodyBlock]). A missing `{` of an accessor body has its
 * own diagnostic in Roslyn; the tree is the same as [parseBlock]'s. Roslyn's incremental reuse of a `Block` here
 * (`IsIncrementalAndFactoryContextMatches`) is the platform's body reparse in this port.
 */
fun LanguageParser.parseMethodOrAccessorBodyBlock(isAccessorBody: Boolean, localFunction: Boolean = false): Node =
    parseBodyBlock(errorSensitive = localFunction)

/** `ParseArrowExpressionClause` (LP 4437) */
fun LanguageParser.parseArrowExpressionClause(): Node {
    val m = open()
    eatToken(SyntaxKind.EqualsGreaterThanToken)
    parsePossibleRefExpression()
    return m.done(SyntaxKind.ArrowExpressionClause)
}

// ---- operators ---------------------------------------------------------------------------------------------------------

/** `TryParseConversionOperatorDeclaration(attributes, modifiers)` (LP 3776); null when this is not one (nothing consumed). */
private fun LanguageParser.tryParseConversionOperatorDeclaration(st: MemberStart): Node? {
    if (currentKind !== SyntaxKind.ImplicitKeyword && currentKind !== SyntaxKind.ExplicitKeyword) {
        val possibleConversion = speculate {
            var haveExplicitInterfaceName = false
            var separatorKind: IElementType? = null
            if (currentKind === SyntaxKind.IdentifierToken) {
                while (true) {
                    if (currentKind === SyntaxKind.OperatorKeyword) break
                    val rp = getResetPoint()
                    try {
                        val start = position
                        scanNamedTypePart()
                        if (isDotOrColonColon() || (position > start && currentKind !== SyntaxKind.OpenParenToken)) {
                            haveExplicitInterfaceName = true
                            if (isDotOrColonColon()) {
                                separatorKind = currentKind
                                eatToken()
                            } else {
                                separatorKind = null
                            }
                        } else {
                            rp.reset()
                            break
                        }
                    } finally {
                        rp.release()
                    }
                }
            }
            if (currentKind !== SyntaxKind.OperatorKeyword || (haveExplicitInterfaceName && separatorKind !== SyntaxKind.DotToken)) {
                false
            } else if (peekToken(1).kind === SyntaxKind.CheckedKeyword || peekToken(1).kind === SyntaxKind.UncheckedKeyword) {
                !CSharpSyntaxFacts.isAnyOverloadableOperator(peekToken(2).kind)
            } else {
                !CSharpSyntaxFacts.isAnyOverloadableOperator(peekToken(1).kind)
            }
        }
        if (!possibleConversion) return null
    }

    val isPart = {
        currentKind !== SyntaxKind.OperatorKeyword && speculate {
            val start = position
            scanNamedTypePart()
            isDotOrColonColon() || (position > start && currentKind !== SyntaxKind.OpenParenToken)
        }
    }
    val styleToken = currentToken
    val styleReal = currentKind === SyntaxKind.ImplicitKeyword || currentKind === SyntaxKind.ExplicitKeyword
    if (styleReal && hasTrailingNewline(styleToken) &&
        speculate {
            eatToken()
            val ei = if (currentKind === SyntaxKind.IdentifierToken) parseExplicitInterfaceParts(InterfaceName(), isPart) else null
            ei != null && currentKind !== SyntaxKind.OperatorKeyword
        }
    ) {
        // `implicit` + line break + a name that is not followed by `operator`: an incomplete conversion operator.
        eatToken()
        eatToken(SyntaxKind.OperatorKeyword)
        addError(createMissingIdentifierName())
        val p = open()
        createMissingToken(SyntaxKind.OpenParenToken, report = false)
        createMissingToken(SyntaxKind.CloseParenToken, report = false)
        p.done(SyntaxKind.ParameterList)
        createMissingToken(SyntaxKind.SemicolonToken, report = false)
        return finishMember(st.m, SyntaxKind.ConversionOperatorDeclaration, st.mods)
    }
    if (styleReal) eatToken() else createMissingToken(SyntaxKind.ExplicitKeyword)
    if (currentKind === SyntaxKind.IdentifierToken) parseExplicitInterfaceParts(InterfaceName(), isPart)
    eatToken(SyntaxKind.OperatorKeyword)
    tryEatCheckedOrHandleUnchecked()

    val couldBeParameterList = currentKind === SyntaxKind.OpenParenToken
    val isSingleElementTuple = couldBeParameterList && speculate {
        val type = parseType()
        type.kind === SyntaxKind.TupleType && lastTupleTypeElementCount < 2 && currentKind !== SyntaxKind.OpenParenToken
    }
    if (isSingleElementTuple) parseIdentifierName() else parseType()
    parseParenthesizedParameterList()
    parseBlockAndExpressionBodiesWithSemicolon()
    return finishMember(st.m, SyntaxKind.ConversionOperatorDeclaration, st.mods)
}

/** `TryEatCheckedOrHandleUnchecked(ref operatorKeyword)` (LP 4004) */
private fun LanguageParser.tryEatCheckedOrHandleUnchecked() {
    if (currentKind === SyntaxKind.UncheckedKeyword) {
        skipTokens(1, CSharpErrorCode.ERR_MisplacedUnchecked.describe())
        return
    }
    tryEatToken(SyntaxKind.CheckedKeyword)
}

/** `ParseOperatorDeclaration(attributes, modifiers, type, explicitInterfaceOpt)` (LP 4017) */
private fun LanguageParser.parseOperatorDeclaration(ctx: MemberContext, st: MemberStart, type: Node): Node {
    if ((currentKind === SyntaxKind.ExplicitKeyword || currentKind === SyntaxKind.ImplicitKeyword) && peekToken(1).kind === SyntaxKind.OperatorKeyword) {
        // A conversion operator after a type: the type becomes skipped text before `implicit`/`explicit`
        // (ERR_BadOperatorSyntax). Replayed from the start of the member with everything up to the keyword skipped.
        val keywordPosition = position
        // `AddError(type, ERR_BadOperatorSyntax, firstToken.Text)`: on the skipped type (LP 4036)
        val message = CSharpErrorCode.ERR_BadOperatorSyntax.describe(currentText)
        val again = startMember(ctx, reset = true)
        skipToPosition(keywordPosition, message)
        return tryParseConversionOperatorDeclaration(again) ?: error("conversion operator expected")
    }
    eatToken(SyntaxKind.OperatorKeyword)
    tryEatCheckedOrHandleUnchecked()

    if (CSharpSyntaxFacts.isAnyOverloadableOperator(currentKind)) {
        if (currentKind === SyntaxKind.GreaterThanToken) eatGreaterThanOperator() else eatToken()
    } else if (currentKind === SyntaxKind.ImplicitKeyword || currentKind === SyntaxKind.ExplicitKeyword) {
        // ConvertToMissingWithTrailingTrivia(EatToken(), PlusToken); ERR_BadOperatorSyntax on the type or the token.
        // the keyword becomes a missing `+`, ERR_BadOperatorSyntax on the type (here: on the keyword) (LP 4071)
        createMissingToken(SyntaxKind.PlusToken, message = SKIPPED)
        skipTokens(1, CSharpErrorCode.ERR_BadOperatorSyntax.describe("+"), countError = false)
    } else if (isAtDotDotToken()) {
        // `..` is eaten and, not being overloadable, converted to a missing `+` with the `..` skipped (LP 4192).
        // Roslyn keeps `..` and reports by the arity (ERR_OvlUnary/BinaryOperatorExpected, LP 4140); here the general one
        createMissingToken(SyntaxKind.PlusToken, message = SKIPPED)
        skipAsError(CSharpErrorCode.ERR_OvlOperatorExpected.describe()) { eatDotDotToken() }
    } else if (!isValidOperatorDeclarationKind(currentKind)) {
        // Any other token is eaten as the operator token and converted the same way.
        createMissingToken(SyntaxKind.PlusToken, message = SKIPPED)
        skipTokens(1, CSharpErrorCode.ERR_OvlOperatorExpected.describe())
    } else {
        eatToken()
    }
    parseParenthesizedParameterList()
    parseBlockAndExpressionBodiesWithSemicolon()
    return finishMember(st.m, SyntaxKind.OperatorDeclaration, st.mods)
}

private fun isValidOperatorDeclarationKind(kind: IElementType): Boolean =
    kind === SyntaxKind.IsKeyword || CSharpSyntaxFacts.isOverloadableUnaryOperator(kind) || CSharpSyntaxFacts.isOverloadableBinaryOperator(kind) ||
        CSharpSyntaxFacts.isOverloadableCompoundAssignmentOperator(kind)

/** The `>` cases of `ParseOperatorDeclaration` (LP 4085): adjacent `>`/`>=` tokens merge into `>>`, `>>>`, `>>=`, `>>>=`. */
private fun LanguageParser.eatGreaterThanOperator(): IElementType {
    val t0 = currentToken
    val t1 = peekToken(1)
    if (t1.kind === SyntaxKind.GreaterThanToken && noTriviaBetween(t0, t1)) {
        val t2 = peekToken(2)
        if (t2.kind === SyntaxKind.GreaterThanToken && noTriviaBetween(t1, t2)) return collapseOperator(3, SyntaxKind.GreaterThanGreaterThanGreaterThanToken)
        if (t2.kind === SyntaxKind.GreaterThanEqualsToken && noTriviaBetween(t1, t2)) return collapseOperator(3, SyntaxKind.GreaterThanGreaterThanGreaterThanEqualsToken)
        return collapseOperator(2, SyntaxKind.GreaterThanGreaterThanToken)
    }
    if (t1.kind === SyntaxKind.GreaterThanEqualsToken && noTriviaBetween(t0, t1)) return collapseOperator(2, SyntaxKind.GreaterThanGreaterThanEqualsToken)
    eatToken()
    return SyntaxKind.GreaterThanToken
}

private fun LanguageParser.collapseOperator(count: Int, kind: IElementType): IElementType {
    val m = builder.mark()
    repeat(count) { advance() }
    m.collapse(kind)
    return kind
}

// ---- properties, indexers, accessors ------------------------------------------------------------------------------------

/** `TryParseIndexerOrPropertyDeclaration` (LP 3191) */
private fun LanguageParser.tryParseIndexerOrPropertyDeclaration(st: MemberStart, name: MemberName): Node? {
    if (name.identifierKind === SyntaxKind.ThisKeyword) return parseIndexerDeclaration(st)
    if (isStartOfPropertyBody(0)) {
        if (name.identifierKind == null) createMissingToken(SyntaxKind.IdentifierToken)
        return parsePropertyDeclaration(st)
    }
    return null
}

/** `ParseIndexerDeclaration` (LP 4217) */
private fun LanguageParser.parseIndexerDeclaration(st: MemberStart): Node {
    parseBracketedParameterList()
    var semicolon = false
    if (currentKind === SyntaxKind.EqualsGreaterThanToken) {
        parseArrowExpressionClause()
        eatToken(SyntaxKind.SemicolonToken)
        semicolon = true
    } else {
        parseAccessorList(AccessorDeclaringKind.Indexer)
        if (currentKind === SyntaxKind.SemicolonToken) {
            eatTokenWithPrejudice(CSharpErrorCode.ERR_UnexpectedSemicolon.describe())
            semicolon = true
        }
    }
    if (currentKind === SyntaxKind.EqualsGreaterThanToken && !semicolon) {
        parseArrowExpressionClause()
        eatToken(SyntaxKind.SemicolonToken)
    }
    return finishMember(st.m, SyntaxKind.IndexerDeclaration, st.mods)
}

/** `ParsePropertyDeclaration` (LP 4277) */
private fun LanguageParser.parsePropertyDeclaration(st: MemberStart): Node {
    // `EatTokenEvenWithIncorrectKind(OpenBraceToken)` as skipped syntax of the identifier (LP 4295)
    if (currentKind === SyntaxKind.SemicolonToken) skipTokens(1, SyntaxParser.expectedMessage(SyntaxKind.OpenBraceToken, SyntaxKind.SemicolonToken, CSharpDiagnosticAnchor.FIRST_EXPECTED))
    if (currentKind === SyntaxKind.OpenBraceToken) parseAccessorList(AccessorDeclaringKind.Property)
    var bodyOrInitializer = false
    if (currentKind === SyntaxKind.EqualsGreaterThanToken) {
        val save = isInFieldKeywordContext
        isInFieldKeywordContext = true
        try {
            parseArrowExpressionClause()
        } finally {
            isInFieldKeywordContext = save
        }
        bodyOrInitializer = true
    } else if (currentKind === SyntaxKind.EqualsToken) {
        val ev = open()
        eatToken()
        parseVariableInitializer()
        ev.done(SyntaxKind.EqualsValueClause)
        bodyOrInitializer = true
    }
    if (bodyOrInitializer) {
        eatToken(SyntaxKind.SemicolonToken)
    } else if (currentKind === SyntaxKind.SemicolonToken) {
        eatTokenWithPrejudice(CSharpErrorCode.ERR_UnexpectedSemicolon.describe())
    }
    return finishMember(st.m, SyntaxKind.PropertyDeclaration, st.mods)
}

/** `AccessorDeclaringKind` (LP 4394) */
enum class AccessorDeclaringKind { Property, Indexer, Event }

/** `ParseAccessorList(declaringKind)` (LP 4400) */
private fun LanguageParser.parseAccessorList(declaringKind: AccessorDeclaringKind) {
    val m = open()
    val openReal = eatToken(SyntaxKind.OpenBraceToken)
    if (openReal || !isTerminator()) {
        val message = (if (declaringKind == AccessorDeclaringKind.Event) CSharpErrorCode.ERR_AddOrRemoveExpected else CSharpErrorCode.ERR_GetOrSetExpected)
            .describe(anchor = CSharpDiagnosticAnchor.FIRST)
        while (true) {
            if (currentKind === SyntaxKind.CloseBraceToken) break
            if (isPossibleAccessor()) {
                parseAccessorDeclaration(declaringKind)
            } else if (skipBadTokensWithErrorCode(
                    isNotExpected = { currentKind !== SyntaxKind.CloseBraceToken && !isPossibleAccessor() },
                    abort = { isTerminator() },
                    message,
                ) == PostSkipAction.Abort
            ) {
                break
            }
        }
    }
    eatToken(SyntaxKind.CloseBraceToken)
    m.done(SyntaxKind.AccessorList)
}

/** `IsPossibleAccessor` (LP 4463) */
private fun LanguageParser.isPossibleAccessor(): Boolean =
    currentKind === SyntaxKind.IdentifierToken || isPossibleAttributeDeclaration() ||
        CSharpSyntaxFacts.getAccessorDeclarationKind(currentContextualKind) != null ||
        currentKind === SyntaxKind.OpenBraceToken || currentKind === SyntaxKind.SemicolonToken || isPossibleAccessorModifier()

/** `IsPossibleAccessorModifier` (LP 4473) */
fun LanguageParser.isPossibleAccessorModifier(): Boolean {
    if (getModifierExcludingScoped(currentToken) == null) return false
    var peekIndex = 1
    while (getModifierExcludingScoped(peekToken(peekIndex)) != null) peekIndex++
    val token = peekToken(peekIndex)
    if (token.kind === SyntaxKind.CloseBraceToken || token.isEof) return true
    return when (token.contextualKind) {
        SyntaxKind.GetKeyword, SyntaxKind.SetKeyword, SyntaxKind.InitKeyword, SyntaxKind.AddKeyword, SyntaxKind.RemoveKeyword -> true
        else -> false
    }
}

/** `ParseAccessorDeclaration(declaringKind)` (LP 4674) */
private fun LanguageParser.parseAccessorDeclaration(declaringKind: AccessorDeclaringKind) {
    val m = open()
    val saveField = isInFieldKeywordContext
    isInFieldKeywordContext = declaringKind == AccessorDeclaringKind.Property
    try {
        parseAttributeDeclarations(inExpressionContext = false)
        parseModifiers(Modifiers(), forAccessors = true, forTopLevelStatements = false)
        val accessorKind: IElementType
        if (currentKind === SyntaxKind.IdentifierToken) {
            val known = CSharpSyntaxFacts.getAccessorDeclarationKind(currentContextualKind)
            if (known != null) {
                accessorKind = known
                convertToKeyword()
            } else {
                accessorKind = SyntaxKind.UnknownAccessorDeclaration
                errorCount++
                eatToken()
            }
        } else {
            accessorKind = SyntaxKind.UnknownAccessorDeclaration
            // EatToken(IdentifierToken, ERR_AddOrRemoveExpected / ERR_GetOrSetExpected) (LP 4688)
            createMissingToken(
                SyntaxKind.IdentifierToken,
                message = (if (declaringKind == AccessorDeclaringKind.Event) CSharpErrorCode.ERR_AddOrRemoveExpected else CSharpErrorCode.ERR_GetOrSetExpected).describe(),
            )
        }
        when (currentKind) {
            SyntaxKind.OpenBraceToken, SyntaxKind.EqualsGreaterThanToken -> parseBlockAndExpressionBodiesWithSemicolon()
            SyntaxKind.SemicolonToken -> eatToken()
            else -> if (accessorKind !== SyntaxKind.UnknownAccessorDeclaration) {
                if (!isTerminator()) parseMethodOrAccessorBodyBlock(isAccessorBody = true)
                else if (!tryEatToken(SyntaxKind.SemicolonToken)) createMissingToken(SyntaxKind.SemicolonToken, message = CSharpErrorCode.ERR_SemiOrLBraceOrArrowExpected.describe())  // EatToken(Semicolon, ERR_SemiOrLBraceOrArrowExpected) (LP 4700)
            }
        }
        m.done(accessorKind)
    } finally {
        isInFieldKeywordContext = saveField
    }
}

// ---- fields and events ------------------------------------------------------------------------------------------------

/** Field-declarator flags (`VariableFlags`, LP 5426). */
object VariableFlags {
    const val None = 0
    const val Fixed = 0x01
    const val Const = 0x02
    const val LocalOrField = 0x04
}

/** `ParseFixedSizeBufferDeclaration` (LP 5106) */
private fun LanguageParser.parseFixedSizeBufferDeclaration(st: MemberStart, parentKind: IElementType): Node {
    eatToken()
    st.mods.kinds += SyntaxKind.FixedKeyword
    val type = parseType()
    parseFieldDeclarationVariableDeclarators(type, VariableFlags.Fixed, parentKind)
    type.wrap(SyntaxKind.VariableDeclaration)
    eatToken(SyntaxKind.SemicolonToken)
    return finishMember(st.m, SyntaxKind.FieldDeclaration, st.mods)
}

/** `ParseConstantFieldDeclaration` (LP 5867) */
private fun LanguageParser.parseConstantFieldDeclaration(st: MemberStart, parentKind: IElementType): Node {
    eatToken(SyntaxKind.ConstKeyword)
    st.mods.kinds += SyntaxKind.ConstKeyword
    val type = parseType()
    parseFieldDeclarationVariableDeclarators(type, VariableFlags.Const, parentKind)
    type.wrap(SyntaxKind.VariableDeclaration)
    eatToken(SyntaxKind.SemicolonToken)
    return finishMember(st.m, SyntaxKind.FieldDeclaration, st.mods)
}

/** `ParseNormalFieldDeclaration` (LP 5242); the type is parsed. A trailing `scoped` modifier makes a `ScopedType`. */
private fun LanguageParser.parseNormalFieldDeclaration(st: MemberStart, type: Node, parentKind: IElementType): Node {
    val scoped = st.mods.scoped
    st.mods.scoped = null
    val declarationStart = scoped?.done(SyntaxKind.ScopedType) ?: type
    parseFieldDeclarationVariableDeclarators(declarationStart, VariableFlags.LocalOrField, parentKind)
    declarationStart.wrap(SyntaxKind.VariableDeclaration)
    eatToken(SyntaxKind.SemicolonToken)
    return finishMember(st.m, SyntaxKind.FieldDeclaration, st.mods)
}

/** `ParseEventDeclaration` (LP 5124) */
private fun LanguageParser.parseEventDeclaration(st: MemberStart, parentKind: IElementType): Node {
    eatToken()
    val type = parseType()
    if (isFieldDeclaration(isEvent = true, isGlobalScriptLevel = parentKind === SyntaxKind.CompilationUnit)) {
        // ParseEventFieldDeclaration (LP 5264)
        parseFieldDeclarationVariableDeclarators(type, VariableFlags.None, parentKind)
        if (currentKind === SyntaxKind.DotToken) errorCount++ // ERR_ExplicitEventFieldImpl
        type.wrap(SyntaxKind.VariableDeclaration)
        eatToken(SyntaxKind.SemicolonToken)
        return finishMember(st.m, SyntaxKind.EventFieldDeclaration, st.mods)
    }
    return parseEventDeclarationWithAccessors(st, type)
}

/** `ParseEventDeclarationWithAccessors` (LP 5139) */
private fun LanguageParser.parseEventDeclarationWithAccessors(st: MemberStart, type: Node): Node {
    val name = parseMemberName(isEvent = true, typeIsMissing = type.isMissing)
    if (name.explicitInterface != null && currentKind !== SyntaxKind.OpenBraceToken && currentKind !== SyntaxKind.SemicolonToken) {
        if (name.identifierKind == null) createMissingToken(SyntaxKind.IdentifierToken, report = false)
        val a = open()
        createMissingToken(SyntaxKind.OpenBraceToken, report = false)
        createMissingToken(SyntaxKind.CloseBraceToken, report = false)
        a.done(SyntaxKind.AccessorList)
        return finishMember(st.m, SyntaxKind.EventDeclaration, st.mods)
    }
    if (name.identifierKind == null) createMissingToken(SyntaxKind.IdentifierToken, report = !type.isMissing)
    if (name.explicitInterface != null && currentKind === SyntaxKind.SemicolonToken) {
        eatToken()
    } else {
        parseAccessorList(AccessorDeclaringKind.Event)
    }
    // EatUnexpectedTrailingSemicolon (LP 5229)
    if (currentKind === SyntaxKind.SemicolonToken) skipTokens(1, CSharpErrorCode.ERR_UnexpectedSemicolon.describe())
    return finishMember(st.m, SyntaxKind.EventDeclaration, st.mods)
}

/** `ParseFieldDeclarationVariableDeclarators(type, flags, parentKind)` (LP 5304) */
private fun LanguageParser.parseFieldDeclarationVariableDeclarators(type: Node, flags: Int, parentKind: IElementType) {
    val variableDeclarationsExpected = parentKind !== SyntaxKind.NamespaceDeclaration && parentKind !== SyntaxKind.FileScopedNamespaceDeclaration &&
        parentKind !== SyntaxKind.CompilationUnit
    val saveTerm = termState
    termState = termState or TerminatorState.IsEndOfFieldDeclaration
    // ParseVariableDeclarators (LP 5336), allowLocalFunctions: false, stopOnCloseParen: false
    parseFieldVariableDeclarator(type, flags, isFirst = true)
    while (true) {
        if (currentKind === SyntaxKind.SemicolonToken) break
        if (currentKind === SyntaxKind.CommaToken) {
            eatToken()
            parseFieldVariableDeclarator(type, flags, isFirst = false)
        } else if (!variableDeclarationsExpected || skipBadSeparatedListTokensWithExpectedKind(
                isNotExpected = { currentKind !== SyntaxKind.CommaToken },
                abort = { currentKind === SyntaxKind.SemicolonToken },
                SyntaxKind.CommaToken, SyntaxKind.SemicolonToken,
            ) == PostSkipAction.Abort
        ) {
            break
        }
    }
    termState = saveTerm
}

/**
 * `ParseVariableDeclarator(parentType, flags, isFirst, allowLocalFunctions: false)` (LP 5527) for fields. The local
 * declarations of LanguageParser_Statements.kt have their own port of the same method (to be merged).
 */
private fun LanguageParser.parseFieldVariableDeclarator(parentType: Node, flags: Int, isFirst: Boolean) {
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
                    return
                }
            }
        }
    }
    val isFixed = flags and VariableFlags.Fixed != 0
    val isConst = flags and VariableFlags.Const != 0
    val isLocalOrField = flags and VariableFlags.LocalOrField != 0
    val m = open()
    val errorsBeforeName = errorCount
    parseIdentifierToken()
    if (!isFirst && isTrueIdentifier()) errorCount++ // ERR_MultiTypeInDeclaration
    // `!ContainsErrorDiagnostic(name)` of `looksLikeVariableInitializer`: a missing name, `await` as a name in an async
    // context (ERR_BadAwaitAsIdentifier), a second type name (ERR_MultiTypeInDeclaration).
    val nameOk = errorCount == errorsBeforeName
    val saveTerm = termState

    fun equalsClause() {
        val ev = open()
        eatToken()
        if (isLocalOrField && !isConst && currentKind === SyntaxKind.RefKeyword && !isPossibleLambdaExpression(Precedence.Expression)) {
            val r = open()
            eatToken()
            parseVariableInitializer()
            r.done(SyntaxKind.RefExpression)
        } else {
            parseVariableInitializer()
        }
        ev.done(SyntaxKind.EqualsValueClause)
    }

    fun bracketCase() {
        termState = termState or TerminatorState.IsPossibleEndOfVariableDeclaration
        val sawNonOmittedSize = parseArrayRankSpecifier(asArgumentList = true)
        termState = saveTerm
        if (isFixed && !sawNonOmittedSize) errorCount++
        if (!isFixed) {
            errorCount++ // ERR_CStyleArray
            if (currentKind === SyntaxKind.EqualsToken) equalsClause()
        }
    }

    fun defaultCase() {
        if (looksLikeFieldInitializer(nameOk)) {
            val ev = open()
            eatToken(SyntaxKind.EqualsToken)
            parseVariableInitializer()
            ev.done(SyntaxKind.EqualsValueClause)
            return
        }
        if (isConst) {
            errorCount++
        } else if (isFixed) {
            if (parentType.kind === SyntaxKind.ArrayType) errorCount++ else bracketCase()
        }
    }

    when (currentKind) {
        SyntaxKind.EqualsToken -> if (isFixed) defaultCase() else equalsClause()
        SyntaxKind.OpenParenToken -> {
            termState = termState or TerminatorState.IsPossibleEndOfVariableDeclaration
            addError(parseBracketedArgumentList())
            termState = saveTerm
        }
        SyntaxKind.OpenBracketToken -> bracketCase()
        else -> defaultCase()
    }
    m.done(SyntaxKind.VariableDeclarator)
}

/** local `looksLikeVariableInitializer` of `ParseVariableDeclarator` (LP 5781) */
private fun LanguageParser.looksLikeFieldInitializer(nameOk: Boolean): Boolean {
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

// ---- local functions ----------------------------------------------------------------------------------------------------

/**
 * `TryParseLocalFunctionStatementBody(attributes, modifiers, type, identifier)` (LP 10985), the identifier eaten:
 * type parameters, parameters, constraints and body of a local function. False (nothing consumed) when `await x(...)`
 * in a non-async context turns out to be an expression.
 */
fun LanguageParser.tryParseLocalFunctionStatementBody(type: Node, modifiers: List<IElementType>): Boolean {
    return withResetPoint { rp ->
        var forceLocalFunc = !(type.kind === SyntaxKind.IdentifierName && type.identifierContextualKind === SyntaxKind.AwaitKeyword)
        if (modifiers.any { it === SyntaxKind.AsyncKeyword || it === SyntaxKind.UnsafeKeyword || it === SyntaxKind.SafeKeyword }) forceLocalFunc = true
        val saveAsync = isInAsync
        isInAsync = modifiers.contains(SyntaxKind.AsyncKeyword)
        try {
            parseTypeParameterList()
            parseParenthesizedParameterList()
            if (!forceLocalFunc) forceLocalFunc = parameterListHadCleanParameter
            if (currentContextualKind === SyntaxKind.WhereKeyword) {
                parseTypeParameterConstraintClauses()
                forceLocalFunc = true
            }
            val hasBody = parseBlockAndExpressionBodiesWithSemicolon(parseSemicolonAfterBlock = false, localFunction = true)
            if (!forceLocalFunc && !hasBody) {
                rp.reset()
                false
            } else {
                true
            }
        } finally {
            isInAsync = saveAsync
        }
    }
}
