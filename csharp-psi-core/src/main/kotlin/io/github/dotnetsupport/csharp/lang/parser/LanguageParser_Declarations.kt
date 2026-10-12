// Ported from Roslyn: src/Compilers/CSharp/Portable/Parser/LanguageParser.cs, compilation unit, namespaces and type
// declarations (ParseCompilationUnitCore 180, ParseNamespaceDeclarationCore 247, IsPossibleStartOfTypeDeclaration 331,
// AddSkippedNamespaceText 361, ParseNamespaceBody 410, ParseNamespaceBodyWorker 562, Add/ReduceIncompleteMembers 848,
// IsPossibleNamespaceMemberDeclaration 869, ScanExternAliasDirective 921, ParseExternAliasDirective 934,
// ParseUsingDirective 958, IsPossibleGlobalAttributeDeclaration 1028, GetModifierExcludingScoped 1302, ParseModifiers
// 1363, ShouldContextualKeywordBeTreatedAsModifier 1546, IsNonContextualModifier 1659, ParseTypeDeclaration 1751,
// ParseMainTypeDeclaration 1784, SkipBadMemberListTokens 2078, ParseBaseList 2165, ParseTypeParameterConstraintClause(s)
// 2242, IsPossibleTypeParameterConstraint 2329, ParseTypeParameterConstraint 2346, CanStartMember 2427,
// IsTypeDeclarationStart 2483, ParseDelegateDeclaration 5881, ParseEnumDeclaration 5914, ParseEnumMemberDeclaration
// 5998, ParseTypeParameterList 6163, IsStartOfTypeParameter 6201, ParseTypeParameter 6214, IsTerminator's declaration
// predicates 94-135), roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c. Roslyn: Copyright (c) .NET Foundation and
// Contributors, MIT License (NOTICE.md). Departures: docs/csharp-psi/GRAMMAR.md, "Declarations".
package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.CSharpFeature
import io.github.dotnetsupport.csharp.lang.CSharpMissingTokenType
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpDiagnosticAnchor
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpErrorCode

// ---- terminators -----------------------------------------------------------------------------------------------------

/** The declaration predicates of `IsTerminator` (LP 94); the slice's predicates are in [LanguageParser.isTerminatorState]. */
fun LanguageParser.isDeclarationTerminatorState(flag: Int): Boolean = when (flag) {
    TerminatorState.IsNamespaceMemberStartOrStop -> currentKind === SyntaxKind.CloseBraceToken || isPossibleNamespaceMemberDeclaration()
    TerminatorState.IsPossibleAggregateClauseStartOrStop ->
        currentKind === SyntaxKind.ColonToken || currentKind === SyntaxKind.OpenBraceToken || isCurrentTokenWhereOfConstraintClause()
    TerminatorState.IsPossibleMemberStartOrStop -> canStartMember(currentKind) || currentKind === SyntaxKind.CloseBraceToken
    TerminatorState.IsEndOfFieldDeclaration -> currentKind === SyntaxKind.SemicolonToken
    TerminatorState.IsEndOfMethodSignature, TerminatorState.IsEndOfTypeSignature ->
        currentKind === SyntaxKind.SemicolonToken || currentKind === SyntaxKind.OpenBraceToken
    TerminatorState.IsEndOfNameInExplicitInterface -> currentKind === SyntaxKind.DotToken || currentKind === SyntaxKind.ColonColonToken
    else -> false
}

// ---- compilation unit ------------------------------------------------------------------------------------------------

/**
 * `ParseCompilationUnitCore` (LP 180): the members of the file. The caller owns the `CompilationUnit` marker (the
 * file element wraps exactly one `CompilationUnit` node, docs/csharp-psi/GRAMMAR.md "Declarations").
 */
fun LanguageParser.parseCompilationUnitCore() {
    parseNamespaceBody(SyntaxKind.CompilationUnit)
    // `EatToken(EndOfFileToken)`: the body loop of the compilation unit only stops at the end of the text.
    if (!currentToken.isEof) consumeUnexpectedTokens()
}

// ---- namespaces ------------------------------------------------------------------------------------------------------

/** `ParseNamespaceDeclaration(attributes, modifiers)` (LP 236); [m] is open before the attributes. */
fun LanguageParser.parseNamespaceDeclaration(m: Open, mods: Modifiers? = null): Node {
    recursionDepth++
    try {
        eatToken(SyntaxKind.NamespaceKeyword)
        parseQualifiedName()
        if (currentKind === SyntaxKind.SemicolonToken) {
            eatToken()
            parseNamespaceBody(SyntaxKind.FileScopedNamespaceDeclaration)
            return finishMember(m, SyntaxKind.FileScopedNamespaceDeclaration, mods)
        }
        if (currentKind === SyntaxKind.OpenBraceToken || isPossibleNamespaceMemberDeclaration()) {
            eatToken(SyntaxKind.OpenBraceToken)
        } else {
            // ConvertToMissingWithTrailingTrivia(EatTokenEvenWithIncorrectKind(OpenBraceToken)): a missing `{` and the
            // current token skipped.
            eatTokenAsKind(SyntaxKind.OpenBraceToken)
        }
        parseNamespaceBody(SyntaxKind.NamespaceDeclaration)
        eatToken(SyntaxKind.CloseBraceToken)
        tryEatToken(SyntaxKind.SemicolonToken)
        return finishMember(m, SyntaxKind.NamespaceDeclaration, mods)
    } finally {
        recursionDepth--
    }
}

/** `IsPossibleStartOfTypeDeclaration` (LP 331) */
fun isPossibleStartOfTypeDeclaration(kind: IElementType): Boolean = isTypeModifierOrTypeKeyword(kind) || kind === SyntaxKind.OpenBracketToken

/** `IsTypeModifierOrTypeKeyword` (LP 337) */
fun isTypeModifierOrTypeKeyword(kind: IElementType): Boolean = when (kind) {
    SyntaxKind.EnumKeyword, SyntaxKind.DelegateKeyword, SyntaxKind.ClassKeyword, SyntaxKind.InterfaceKeyword, SyntaxKind.StructKeyword,
    SyntaxKind.AbstractKeyword, SyntaxKind.InternalKeyword, SyntaxKind.NewKeyword, SyntaxKind.PrivateKeyword, SyntaxKind.ProtectedKeyword,
    SyntaxKind.PublicKeyword, SyntaxKind.SealedKeyword, SyntaxKind.StaticKeyword, SyntaxKind.UnsafeKeyword,
    -> true
    else -> false
}

/** `NamespaceParts` (LP 398) */
private object NamespaceParts {
    const val None = 0
    const val ExternAliases = 1
    const val Usings = 2
    const val GlobalAttributes = 3
    const val MembersAndStatements = 4
    const val TypesAndNamespaces = 5
    const val TopLevelStatementsAfterTypesAndNamespaces = 6
}

/** A member of a namespace body, as `body.Members` of Roslyn holds it after the worker. */
private class BodyMember(val kind: IElementType, val node: Node, var cleanTypeDeclaration: Boolean)

/**
 * `ParseNamespaceBody` (LP 410) with its post-pass: type-only members (methods, fields, ...) right after a type
 * declaration that ended cleanly are moved into it (a misplaced `}`). The tree of a move cannot be rearranged after the
 * fact, so the body is parsed twice: the first pass records the members, the second pass, from the same position,
 * keeps the type's marker open over the moved members (docs/csharp-psi/GRAMMAR.md, "Declarations: moving members").
 */
private fun LanguageParser.parseNamespaceBody(parentKind: IElementType) {
    val isGlobal = parentKind === SyntaxKind.CompilationUnit
    val bodyStart = getResetPoint()
    try {
        val first = parseNamespaceBodyWorker(parentKind, isGlobal, plan = null)
        if (!first.sawTypeOnlyMember) return
        val plan = HashMap<Int, Int>()
        val members = first.members
        var i = 0
        while (i < members.size) {
            if (members[i].cleanTypeDeclaration && i + 1 < members.size && isMemberDeclarationOnlyValidWithinTypeDeclaration(members[i + 1].kind)) {
                var end = i + 2
                while (end < members.size && isMemberDeclarationOnlyValidWithinTypeDeclaration(members[end].kind)) end++
                plan[i] = end
                i = end
                continue
            }
            i++
        }
        if (plan.isEmpty()) return
        bodyStart.reset()
        parseNamespaceBodyWorker(parentKind, isGlobal, plan)
    } finally {
        bodyStart.release()
    }
}

/** `IsMemberDeclarationOnlyValidWithinTypeDeclaration` (LP 546) */
private fun isMemberDeclarationOnlyValidWithinTypeDeclaration(kind: IElementType): Boolean = when (kind) {
    SyntaxKind.ConstructorDeclaration, SyntaxKind.ConversionOperatorDeclaration, SyntaxKind.DestructorDeclaration,
    SyntaxKind.EventDeclaration, SyntaxKind.EventFieldDeclaration, SyntaxKind.FieldDeclaration, SyntaxKind.IndexerDeclaration,
    SyntaxKind.MethodDeclaration, SyntaxKind.OperatorDeclaration, SyntaxKind.PropertyDeclaration,
    -> true
    else -> false
}

private class BodyResult(val members: List<BodyMember>, val sawTypeOnlyMember: Boolean)

/**
 * `ParseNamespaceBodyWorker` (LP 562). [plan] maps the index of a type declaration to the end (exclusive) of the run of
 * members moved into it; null in the first pass.
 */
private fun LanguageParser.parseNamespaceBodyWorker(parentKind: IElementType, isGlobal: Boolean, plan: Map<Int, Int>?): BodyResult {
    val members = ArrayList<BodyMember>()
    var sawTypeOnly = false
    val saveTerm = termState
    termState = termState or TerminatorState.IsNamespaceMemberStartOrStop
    var seen = NamespaceParts.None
    var reportUnexpectedToken = true

    // Pending incomplete members: already in the tree; a reset point before the first one lets ReduceIncompleteMembers
    // turn them into one skipped-tokens error element.
    var pendingCount = 0
    var pendingStart: SyntaxParser.ResetPoint? = null
    var pendingEnd = -1
    val pendingNodes = ArrayList<Node>()

    // The type declaration that is absorbing the members after it (second pass only).
    var absorbing: AbsorbedType? = null
    var absorbEnd = -1

    fun markLastDirty() {
        // AddSkippedNamespaceText: skipped text after the last member makes its last token carry diagnostics.
        if (members.isNotEmpty()) members[members.size - 1].cleanTypeDeclaration = false
    }

    fun commit(node: Node) {
        val absorbed = absorbing
        if (absorbed != null && members.size == absorbEnd) {
            // The run ends before this member: a missing `}` closes the type right before it (ERR_RbraceExpected).
            val err = node.marker.precede()
            err.errorBefore("'}' expected", node.marker)
            errorCount++
            absorbed.open.marker.doneBefore(absorbed.kind, node.marker)
            absorbing = null
        }
        members += BodyMember(node.kind, node, node === lastCleanTypeDeclaration)
        val end = plan?.get(members.size - 1)
        if (end != null) {
            absorbing = absorbedTypeDeclaration
            absorbedTypeDeclaration = null
            absorbEnd = end
            if (absorbing != null) members[members.size - 1].cleanTypeDeclaration = false
        }
    }

    fun addIncompleteMembers() {
        if (pendingCount > 0) {
            pendingStart?.release()
            pendingStart = null
            for (n in pendingNodes) commit(n)
            pendingNodes.clear()
            pendingCount = 0
        }
    }

    fun reduceIncompleteMembers() {
        if (pendingCount > 0) {
            val rp = pendingStart!!
            rp.reset()
            rp.release()
            pendingStart = null
            pendingNodes.clear()
            pendingCount = 0
            skipToPosition(pendingEnd, SKIPPED)
            markLastDirty()
        }
    }

    fun adjustStateAndReportStatementOutOfOrder(node: Node) {
        when (node.kind) {
            SyntaxKind.GlobalStatement -> {
                if (seen < NamespaceParts.MembersAndStatements) {
                    seen = NamespaceParts.MembersAndStatements
                } else if (seen == NamespaceParts.TypesAndNamespaces) {
                    seen = NamespaceParts.TopLevelStatementsAfterTypesAndNamespaces
                    errorCount++ // ERR_TopLevelStatementAfterNamespaceOrType
                }
            }
            SyntaxKind.NamespaceDeclaration, SyntaxKind.FileScopedNamespaceDeclaration, SyntaxKind.EnumDeclaration,
            SyntaxKind.StructDeclaration, SyntaxKind.UnionDeclaration, SyntaxKind.ClassDeclaration, SyntaxKind.InterfaceDeclaration,
            SyntaxKind.DelegateDeclaration, SyntaxKind.RecordDeclaration, SyntaxKind.RecordStructDeclaration,
            -> if (seen < NamespaceParts.TypesAndNamespaces) seen = NamespaceParts.TypesAndNamespaces
            else -> if (seen < NamespaceParts.MembersAndStatements) seen = NamespaceParts.MembersAndStatements
        }
    }

    fun parseUsingDirectiveInBody() {
        reduceIncompleteMembers()
        if (seen > NamespaceParts.Usings) {
            skipAsError(CSharpErrorCode.ERR_UsingAfterElements.describe()) { parseUsingDirective() }
            markLastDirty()
        } else {
            parseUsingDirective()
            seen = NamespaceParts.Usings
        }
    }

    try {
        loop@ while (true) {
            when (currentKind) {
                SyntaxKind.NamespaceKeyword -> {
                    addIncompleteMembers()
                    val node = parseNamespaceDeclaration(open())
                    adjustStateAndReportStatementOutOfOrder(node)
                    commit(node)
                    reportUnexpectedToken = true
                    continue@loop
                }
                SyntaxKind.CloseBraceToken -> {
                    if (isGlobal) {
                        reduceIncompleteMembers()
                        skipTokens(1, CSharpErrorCode.ERR_EOFExpected.describe())
                        markLastDirty()
                        reportUnexpectedToken = true
                        continue@loop
                    }
                    break@loop
                }
                SyntaxKind.EndOfFileToken -> break@loop
                SyntaxKind.ExternKeyword -> {
                    if (!(isGlobal && !scanExternAliasDirective())) {
                        reduceIncompleteMembers()
                        if (seen > NamespaceParts.ExternAliases) {
                            skipAsError(CSharpErrorCode.ERR_ExternAfterElements.describe(anchor = CSharpDiagnosticAnchor.FIRST)) { parseExternAliasDirective() }
                            markLastDirty()
                        } else {
                            parseExternAliasDirective()
                            seen = NamespaceParts.ExternAliases
                        }
                        reportUnexpectedToken = true
                        continue@loop
                    }
                }
                SyntaxKind.UsingKeyword -> {
                    if (!(isGlobal && (peekToken(1).kind === SyntaxKind.OpenParenToken || isPossibleTopLevelUsingLocalDeclarationStatement()))) {
                        parseUsingDirectiveInBody()
                        reportUnexpectedToken = true
                        continue@loop
                    }
                }
                SyntaxKind.IdentifierToken -> {
                    if (currentContextualKind === SyntaxKind.GlobalKeyword && peekToken(1).kind === SyntaxKind.UsingKeyword) {
                        parseUsingDirectiveInBody()
                        reportUnexpectedToken = true
                        continue@loop
                    }
                }
                SyntaxKind.OpenBracketToken -> {
                    if (isPossibleGlobalAttributeDeclaration()) {
                        val inExpressionContext = parentKind === SyntaxKind.CompilationUnit
                        val isAttribute = speculate { tryParseAttributeDeclaration(inExpressionContext) != null }
                        if (isAttribute) {
                            reduceIncompleteMembers()
                            if (!isGlobal || seen > NamespaceParts.GlobalAttributes) {
                                // on the target identifier (`assembly`), the second token
                                skipAsError(CSharpErrorCode.ERR_GlobalAttributesNotFirst.describe(anchor = CSharpDiagnosticAnchor.SECOND)) {
                                    tryParseAttributeDeclaration(inExpressionContext)
                                }
                                markLastDirty()
                            } else {
                                tryParseAttributeDeclaration(inExpressionContext)
                                seen = NamespaceParts.GlobalAttributes
                            }
                            reportUnexpectedToken = true
                            continue@loop
                        }
                    }
                }
            }

            // default:
            val index = members.size + pendingCount
            absorbNextTypeDeclaration = plan != null && plan.containsKey(index)
            val beforeMember = getResetPoint()
            val node = if (isGlobal) parseMemberDeclarationOrStatement(parentKind) else parseMemberDeclaration(parentKind)
            absorbNextTypeDeclaration = false
            if (node != null && isMemberDeclarationOnlyValidWithinTypeDeclaration(node.kind)) sawTypeOnly = true
            if (node == null) {
                beforeMember.release()
                reduceIncompleteMembers()
                // Eat one token and try to parse a declaration or statement again (ERR_EOFExpected once per run).
                // `reportUnexpectedToken && !skippedToken.ContainsDiagnostics` (LP 739)
                val message = if (reportUnexpectedToken && !currentTokenHasLexerError()) CSharpErrorCode.ERR_EOFExpected.describe() else SKIPPED
                skipTokens(1, message, countError = reportUnexpectedToken)
                reportUnexpectedToken = false
                markLastDirty()
            } else if (node.kind === SyntaxKind.IncompleteMember && seen < NamespaceParts.MembersAndStatements) {
                if (pendingStart == null) pendingStart = beforeMember else beforeMember.release()
                pendingNodes += node
                pendingCount++
                pendingEnd = position
                reportUnexpectedToken = true
            } else {
                beforeMember.release()
                addIncompleteMembers()
                adjustStateAndReportStatementOutOfOrder(node)
                commit(node)
                reportUnexpectedToken = true
            }
        }
    } finally {
        termState = saveTerm
        addIncompleteMembers()
    }

    absorbing?.let { absorbed ->
        // The run of moved members is the last one: the type takes the `}` that follows (the namespace's) or a missing one.
        eatToken(SyntaxKind.CloseBraceToken)
        absorbed.open.done(absorbed.kind)
    }
    return BodyResult(members, sawTypeOnly)
}

/** A type declaration left open by [parseMainTypeDeclaration] to absorb the members after it (second pass). */
class AbsorbedType(val open: Open, val kind: IElementType)

/** Eats tokens up to the raw position [target] into one error element (skipped syntax of a construct parsed before). */
fun LanguageParser.skipToPosition(target: Int, message: String) {
    if (position >= target) return
    val m = builder.mark()
    while (position < target && advance()) { /* eat */ }
    m.error(message)
    countSkippedError()
}

/** `IsPossibleNamespaceMemberDeclaration` (LP 869) */
fun LanguageParser.isPossibleNamespaceMemberDeclaration(): Boolean = when (currentKind) {
    SyntaxKind.ExternKeyword, SyntaxKind.UsingKeyword, SyntaxKind.NamespaceKeyword -> true
    SyntaxKind.IdentifierToken -> isPartialInNamespaceMemberDeclaration()
    else -> isPossibleStartOfTypeDeclaration(currentKind)
}

/** `IsPartialInNamespaceMemberDeclaration` (LP 884) */
private fun LanguageParser.isPartialInNamespaceMemberDeclaration(): Boolean =
    currentContextualKind === SyntaxKind.PartialKeyword && (isPartialType() || peekToken(1).kind === SyntaxKind.NamespaceKeyword)

/** `ScanExternAliasDirective` (LP 921) */
private fun LanguageParser.scanExternAliasDirective(): Boolean =
    currentKind === SyntaxKind.ExternKeyword &&
        peekToken(1).let { it.kind === SyntaxKind.IdentifierToken && it.contextualKind === SyntaxKind.AliasKeyword } &&
        peekToken(2).kind === SyntaxKind.IdentifierToken &&
        peekToken(3).kind === SyntaxKind.SemicolonToken

/** `ParseExternAliasDirective` (LP 934) */
private fun LanguageParser.parseExternAliasDirective(): Node {
    val m = open()
    eatToken(SyntaxKind.ExternKeyword)
    eatContextualToken(SyntaxKind.AliasKeyword)
    parseIdentifierToken()
    eatToken(SyntaxKind.SemicolonToken)
    return m.done(SyntaxKind.ExternAliasDirective)
}

/** `ParseUsingDirective` (LP 958) */
private fun LanguageParser.parseUsingDirective(): Node {
    val m = open()
    if (currentContextualKind === SyntaxKind.GlobalKeyword) convertToKeyword()
    eatToken(SyntaxKind.UsingKeyword)
    val hadStatic = tryEatToken(SyntaxKind.StaticKeyword)
    // The missing `static` of `using unsafe static` goes into its slot, before `unsafe` (gaps follow slot order).
    val beforeUnsafe = builder.mark()
    val unsafeStart = builder.mark()
    val hadUnsafe = tryEatToken(SyntaxKind.UnsafeKeyword)
    if (!hadStatic && hadUnsafe && currentKind === SyntaxKind.StaticKeyword) {
        // `using unsafe static`: a missing `static`, the real one skipped after `unsafe` (ERR_BadStaticAfterUnsafe).
        beforeUnsafe.doneBefore(CSharpMissingTokenType.of(SyntaxKind.StaticKeyword), unsafeStart)
        unsafeStart.drop()
        skipTokens(1, CSharpErrorCode.ERR_BadStaticAfterUnsafe.describe())
    } else {
        unsafeStart.drop()
        beforeUnsafe.drop()
    }
    val hasAlias = isTrueIdentifier() && peekToken(1).kind === SyntaxKind.EqualsToken
    if (hasAlias) parseNameEquals()
    val isAliasToFunctionPointer = hasAlias && currentKind === SyntaxKind.DelegateKeyword
    if (!isAliasToFunctionPointer && isPossibleNamespaceMemberDeclaration()) {
        createMissingIdentifierName(SyntaxParser.expectedMessage(SyntaxKind.IdentifierToken, currentKind))
        createMissingToken(SyntaxKind.SemicolonToken, report = false)
    } else {
        val type = if (!hasAlias) parseQualifiedName() else parseType()
        if (type.isMissing && peekToken(1).kind === SyntaxKind.SemicolonToken) skipTokens(1)
        eatToken(SyntaxKind.SemicolonToken)
    }
    return m.done(SyntaxKind.UsingDirective)
}

/** `IsPossibleGlobalAttributeDeclaration` (LP 1028) */
private fun LanguageParser.isPossibleGlobalAttributeDeclaration(): Boolean =
    currentKind === SyntaxKind.OpenBracketToken && isGlobalAttributeTarget(peekToken(1)) && peekToken(2).kind === SyntaxKind.ColonToken

/** `IsGlobalAttributeTarget` (LP 1035): `ToAttributeLocation` is `assembly` or `module` (by value text). */
private fun LanguageParser.isGlobalAttributeTarget(token: Tok): Boolean {
    if (!isSomeWord(token.kind)) return false
    var text = unescapeIdentifier(builder.originalText.subSequence(token.start, token.end).toString())
    if (text.startsWith("@")) text = text.substring(1)
    return text == "assembly" || text == "module"
}

/** The `ValueText` of an identifier: `\uXXXX` and `\UXXXXXXXX` escapes decoded (`[assembly: A]`). */
private fun unescapeIdentifier(text: String): String {
    if ('\\' !in text) return text
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val digits = if (c == '\\' && i + 1 < text.length) when (text[i + 1]) { 'u' -> 4; 'U' -> 8; else -> 0 } else 0
        val code = if (digits > 0 && i + 2 + digits <= text.length) text.substring(i + 2, i + 2 + digits).toIntOrNull(16) else null
        if (code != null && Character.isValidCodePoint(code)) {
            sb.appendCodePoint(code)
            i += 2 + digits
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

// ---- modifiers -------------------------------------------------------------------------------------------------------

/**
 * The modifiers of a declaration (`SyntaxListBuilder modifiers`): their (contextual) kinds in order. A `scoped`
 * modifier opens [scoped], the marker of a `ScopedType` that a field declaration completes around its type
 * (`ParseNormalFieldDeclaration`); every other declaration drops it in [finishMember].
 */
class Modifiers {
    val kinds = ArrayList<IElementType>(4)
    var scoped: Open? = null
    val count: Int get() = kinds.size
    fun has(kind: IElementType) = kinds.contains(kind)
}

/** Completes a declaration node; the unused `ScopedType` marker of a `scoped` modifier is dropped first. */
fun LanguageParser.finishMember(m: Open, kind: IElementType, mods: Modifiers?): Node {
    mods?.scoped?.let {
        it.drop()
        mods.scoped = null
    }
    return m.done(kind)
}

/** `GetModifierExcludingScoped(token)` (LP 1302): the modifier's keyword kind, or null. */
fun getModifierExcludingScoped(token: Tok): IElementType? = when (token.kind) {
    SyntaxKind.PublicKeyword, SyntaxKind.InternalKeyword, SyntaxKind.ProtectedKeyword, SyntaxKind.PrivateKeyword, SyntaxKind.SealedKeyword,
    SyntaxKind.AbstractKeyword, SyntaxKind.StaticKeyword, SyntaxKind.VirtualKeyword, SyntaxKind.ExternKeyword, SyntaxKind.NewKeyword,
    SyntaxKind.OverrideKeyword, SyntaxKind.ReadOnlyKeyword, SyntaxKind.VolatileKeyword, SyntaxKind.UnsafeKeyword, SyntaxKind.RefKeyword,
    -> token.kind
    SyntaxKind.IdentifierToken -> when (token.contextualKind) {
        SyntaxKind.PartialKeyword, SyntaxKind.AsyncKeyword, SyntaxKind.RequiredKeyword, SyntaxKind.FileKeyword, SyntaxKind.ClosedKeyword,
        SyntaxKind.SafeKeyword,
        -> token.contextualKind
        else -> null
    }
    else -> null
}

/** `IsNonContextualModifier` (LP 1659) */
fun isNonContextualModifierToken(token: Tok): Boolean =
    !CSharpSyntaxFacts.isContextualKeyword(token.contextualKind) && getModifierExcludingScoped(token) != null

/** `ParseModifiers(tokens, forAccessors, forTopLevelStatements, out isPossibleTypeDeclaration)` (LP 1363) */
fun LanguageParser.parseModifiers(mods: Modifiers, forAccessors: Boolean, forTopLevelStatements: Boolean): Boolean {
    var isPossibleTypeDeclaration = true

    // `parseAsModifier(requiredFeature)` (LP 1497): with the feature enabled the contextual keyword is always a modifier
    // (except in top-level statements); otherwise the `async` heuristic decides.
    fun parseAsModifier(requiredFeature: CSharpFeature): Boolean {
        if ((!isFeatureEnabled(requiredFeature) || forTopLevelStatements) && !shouldContextualKeywordBeTreatedAsDeclarationModifier()) return false
        convertToKeyword()
        return true
    }

    // `isStructOrRecordOrUnionKeyword` (LP 1514): `record` / `union` only when the feature is enabled.
    fun isStructOrRecordOrUnionKeyword(token: Tok): Boolean =
        token.kind === SyntaxKind.StructKeyword ||
            (token.contextualKind === SyntaxKind.RecordKeyword && isFeatureEnabled(CSharpFeature.Records)) ||
            (token.contextualKind === SyntaxKind.UnionKeyword && isFeatureEnabled(CSharpFeature.Unions))

    while (true) {
        val newMod = getModifierExcludingScoped(currentToken)
        if (newMod == null) {
            if (!forAccessors && isDefiniteScopedModifier(isFunctionPointerParameter = false, isLambdaParameter = false)) {
                mods.scoped = open()
                eatContextualToken(SyntaxKind.ScopedKeyword)
                mods.kinds += SyntaxKind.ScopedKeyword
                isPossibleTypeDeclaration = false
            }
            break
        }
        when (newMod) {
            SyntaxKind.PartialKeyword -> {
                val next = peekToken(1)
                if (isPartialType() || isPartialMember()) {
                    convertToKeyword()
                } else if (next.kind === SyntaxKind.NamespaceKeyword) {
                    convertToKeyword()
                } else if (next.kind === SyntaxKind.EnumKeyword || next.kind === SyntaxKind.DelegateKeyword ||
                    (isPossibleStartOfTypeDeclaration(next.kind) && getModifierExcludingScoped(next) != null)
                ) {
                    convertToKeyword()
                } else {
                    return isPossibleTypeDeclaration
                }
            }
            SyntaxKind.RefKeyword -> {
                val next = peekToken(1)
                if (isStructOrRecordOrUnionKeyword(next) || (next.contextualKind === SyntaxKind.PartialKeyword && isStructOrRecordOrUnionKeyword(peekToken(2)))) {
                    eatToken()
                } else if (forAccessors && isPossibleAccessorModifier()) {
                    eatToken()
                } else {
                    return isPossibleTypeDeclaration
                }
            }
            SyntaxKind.FileKeyword -> if (!parseAsModifier(CSharpFeature.FileTypes)) return isPossibleTypeDeclaration
            SyntaxKind.ClosedKeyword -> if (!parseAsModifier(CSharpFeature.ClosedClasses)) return isPossibleTypeDeclaration
            SyntaxKind.RequiredKeyword -> if (!parseAsModifier(CSharpFeature.RequiredMembers)) return isPossibleTypeDeclaration
            SyntaxKind.AsyncKeyword -> {
                if (!shouldContextualKeywordBeTreatedAsDeclarationModifier()) return isPossibleTypeDeclaration
                convertToKeyword()
            }
            SyntaxKind.SafeKeyword -> {
                if (forAccessors) {
                    if (!isPossibleAccessorModifier()) return isPossibleTypeDeclaration
                    convertToKeyword()
                } else if (!parseAsModifier(CSharpFeature.UnsafeEvolution)) {
                    return isPossibleTypeDeclaration
                }
            }
            else -> eatToken()
        }
        mods.kinds += newMod
    }
    return isPossibleTypeDeclaration
}

/** `ShouldContextualKeywordBeTreatedAsModifier(parsingStatementNotDeclaration: false)` (LP 1546) */
fun LanguageParser.shouldContextualKeywordBeTreatedAsDeclarationModifier(): Boolean {
    if (isNonContextualModifierToken(peekToken(1))) return true
    return speculate {
        eatToken()
        if (currentContextualKind === SyntaxKind.PartialKeyword) eatToken()
        val k = currentKind
        if (isTypeModifierOrTypeKeyword(k) || k === SyntaxKind.EventKeyword ||
            ((k === SyntaxKind.ExplicitKeyword || k === SyntaxKind.ImplicitKeyword) && peekToken(1).kind === SyntaxKind.OperatorKeyword)
        ) {
            true
        } else if (scanType() != ScanTypeFlags.NotType) {
            val c = currentKind
            isPossibleMemberName() || c === SyntaxKind.EndOfFileToken || c === SyntaxKind.CloseBraceToken ||
                CSharpSyntaxFacts.isPredefinedType(c) || isNonContextualModifierToken(currentToken) || isTypeDeclarationStartExact() ||
                c === SyntaxKind.NamespaceKeyword || c === SyntaxKind.OperatorKeyword
        } else {
            false
        }
    }
}

/**
 * `IsTypeDeclarationStart` (LP 2483): `record` and `union` start a type declaration only when the feature is enabled
 * (Roslyn: "an unusual use of LangVersion"), `extension` as [isExtensionContainerStart]. `isTypeDeclarationStart` of
 * LanguageParser_Statements.kt is this function.
 */
fun LanguageParser.isTypeDeclarationStartExact(): Boolean = when (currentKind) {
    SyntaxKind.ClassKeyword, SyntaxKind.EnumKeyword, SyntaxKind.InterfaceKeyword, SyntaxKind.StructKeyword -> true
    SyntaxKind.DelegateKeyword -> !isFunctionPointerStart()
    SyntaxKind.IdentifierToken -> when (currentContextualKind) {
        SyntaxKind.RecordKeyword -> isFeatureEnabled(CSharpFeature.Records)
        SyntaxKind.UnionKeyword -> isFeatureEnabled(CSharpFeature.Unions)
        else -> isExtensionContainerStart()
    }
    else -> false
}

/** `IsExtensionContainerStart` (LP 3412): `extension`; before C# 14 only when `<` follows (error recovery). */
fun LanguageParser.isExtensionContainerStart(): Boolean =
    currentContextualKind === SyntaxKind.ExtensionKeyword &&
        (isFeatureEnabled(CSharpFeature.Extensions) || peekToken(1).kind === SyntaxKind.LessThanToken)

/** `CanStartMember` (LP 2427) */
fun canStartMember(kind: IElementType): Boolean = when (kind) {
    SyntaxKind.AbstractKeyword, SyntaxKind.BoolKeyword, SyntaxKind.ByteKeyword, SyntaxKind.CharKeyword, SyntaxKind.ClassKeyword,
    SyntaxKind.ConstKeyword, SyntaxKind.DecimalKeyword, SyntaxKind.DelegateKeyword, SyntaxKind.DoubleKeyword, SyntaxKind.EnumKeyword,
    SyntaxKind.EventKeyword, SyntaxKind.ExternKeyword, SyntaxKind.FixedKeyword, SyntaxKind.FloatKeyword, SyntaxKind.IntKeyword,
    SyntaxKind.InterfaceKeyword, SyntaxKind.InternalKeyword, SyntaxKind.LongKeyword, SyntaxKind.NewKeyword, SyntaxKind.ObjectKeyword,
    SyntaxKind.OverrideKeyword, SyntaxKind.PrivateKeyword, SyntaxKind.ProtectedKeyword, SyntaxKind.PublicKeyword, SyntaxKind.ReadOnlyKeyword,
    SyntaxKind.SByteKeyword, SyntaxKind.SealedKeyword, SyntaxKind.ShortKeyword, SyntaxKind.StaticKeyword, SyntaxKind.StringKeyword,
    SyntaxKind.StructKeyword, SyntaxKind.UIntKeyword, SyntaxKind.ULongKeyword, SyntaxKind.UnsafeKeyword, SyntaxKind.UShortKeyword,
    SyntaxKind.VirtualKeyword, SyntaxKind.VoidKeyword, SyntaxKind.VolatileKeyword, SyntaxKind.IdentifierToken, SyntaxKind.TildeToken,
    SyntaxKind.OpenBracketToken, SyntaxKind.ImplicitKeyword, SyntaxKind.ExplicitKeyword, SyntaxKind.OpenParenToken, SyntaxKind.RefKeyword,
    -> true
    else -> false
}

// ---- type declarations -------------------------------------------------------------------------------------------------

/** `ParseTypeDeclaration(attributes, modifiers)` (LP 1751) */
fun LanguageParser.parseTypeDeclaration(m: Open, mods: Modifiers): Node = when (currentKind) {
    SyntaxKind.DelegateKeyword -> parseDelegateDeclaration(m, mods)
    SyntaxKind.EnumKeyword -> parseEnumDeclaration(m, mods)
    else -> parseMainTypeDeclaration(m, mods)
}

/** `ParseMainTypeDeclaration(attributes, modifiers)` (LP 1784) */
fun LanguageParser.parseMainTypeDeclaration(m: Open, mods: Modifiers): Node {
    val absorb = absorbNextTypeDeclaration
    absorbNextTypeDeclaration = false

    val keyword: IElementType
    var recordModifier: IElementType? = null
    if (currentContextualKind === SyntaxKind.RecordKeyword) {
        // tryScanRecordStart
        convertToKeyword()
        keyword = SyntaxKind.RecordKeyword
        if (currentKind === SyntaxKind.ClassKeyword || currentKind === SyntaxKind.StructKeyword) {
            recordModifier = currentKind
            eatToken()
        }
    } else if ((currentKind === SyntaxKind.StructKeyword || currentKind === SyntaxKind.ClassKeyword) &&
        peekToken(1).contextualKind === SyntaxKind.RecordKeyword && peekToken(2).kind === SyntaxKind.IdentifierToken
    ) {
        // `struct record R`: the misplaced keyword is skipped before `record`, a missing one follows it (ERR_MisplacedRecord).
        recordModifier = currentKind
        // ERR_MisplacedRecord on `record`, the misplaced keyword its leading skipped syntax (LP 1947)
        skipTokens(1, CSharpErrorCode.ERR_MisplacedRecord.describe(anchor = CSharpDiagnosticAnchor.NEXT))
        convertToKeyword()
        keyword = SyntaxKind.RecordKeyword
        createMissingToken(recordModifier, report = false)
    } else {
        keyword = if (currentKind === SyntaxKind.IdentifierToken) currentContextualKind else currentKind
        convertToKeyword()
    }

    val isExtension = keyword === SyntaxKind.ExtensionKeyword
    val isUnion = keyword === SyntaxKind.UnionKeyword
    val outerSaveTerm = termState
    termState = termState or TerminatorState.IsEndOfTypeSignature
    val saveTerm = termState
    termState = termState or TerminatorState.IsPossibleAggregateClauseStartOrStop

    if (isExtension) {
        if (currentKind === SyntaxKind.IdentifierToken) skipTokens(1, CSharpErrorCode.ERR_ExtensionDisallowsName.describe())
    } else {
        parseIdentifierToken()
    }
    parseTypeParameterList()
    if (currentKind === SyntaxKind.OpenParenToken || isExtension) parseParenthesizedParameterList(forExtensionOrUnion = isExtension || isUnion)
    if (!isExtension) parseBaseList()
    termState = saveTerm

    if (currentContextualKind === SyntaxKind.WhereKeyword) parseTypeParameterConstraintClauses()
    termState = outerSaveTerm

    val kind = when (keyword) {
        SyntaxKind.ClassKeyword -> SyntaxKind.ClassDeclaration
        SyntaxKind.StructKeyword -> SyntaxKind.StructDeclaration
        SyntaxKind.InterfaceKeyword -> SyntaxKind.InterfaceDeclaration
        SyntaxKind.UnionKeyword -> SyntaxKind.UnionDeclaration
        SyntaxKind.ExtensionKeyword -> SyntaxKind.ExtensionBlockDeclaration
        else -> if (recordModifier === SyntaxKind.StructKeyword) SyntaxKind.RecordStructDeclaration else SyntaxKind.RecordDeclaration
    }

    if (currentKind === SyntaxKind.SemicolonToken) {
        eatToken()
        return finishMember(m, kind, mods)
    }
    val openBraceReal = eatToken(SyntaxKind.OpenBraceToken)
    if (openBraceReal) {
        while (true) {
            val k = currentKind
            if (canStartMember(k)) {
                val saveTerm2 = termState
                termState = termState or TerminatorState.IsPossibleMemberStartOrStop
                val member = parseMemberDeclaration(keyword)
                if (member == null) skipBadMemberListTokens()
                termState = saveTerm2
            } else if (k === SyntaxKind.CloseBraceToken || k === SyntaxKind.EndOfFileToken || isTerminator()) {
                break
            } else {
                skipBadMemberListTokens()
            }
        }
    }
    if (absorb && openBraceReal && currentKind === SyntaxKind.CloseBraceToken) {
        // Second pass of ParseNamespaceBody: this `}` becomes skipped text of the first moved member (ERR_InvalidMemberDecl)
        // and the declaration stays open over the members that follow it in the namespace.
        skipTokens(1, CSharpErrorCode.ERR_InvalidMemberDecl.describe("}"))
        mods.scoped?.let {
            it.drop()
            mods.scoped = null
        }
        absorbedTypeDeclaration = AbsorbedType(m, kind)
        return Node(m.marker, kind, m.startRaw, m.errorsAtStart, isMissing = false, containsErrors = true, lastTokenMissing = false)
    }
    val closeBraceReal = if (!openBraceReal) {
        createMissingToken(SyntaxKind.CloseBraceToken)
        false
    } else {
        eatToken(SyntaxKind.CloseBraceToken)
    }
    val semicolon = tryEatToken(SyntaxKind.SemicolonToken)
    val node = finishMember(m, kind, mods)
    if (closeBraceReal && !semicolon && kind !== SyntaxKind.EnumDeclaration) lastCleanTypeDeclaration = node
    return node
}

/** `SkipBadMemberListTokens(ref previousNode)` (LP 2094): the tokens become one error element. */
private fun LanguageParser.skipBadMemberListTokens() {
    val m = builder.mark()
    var curlyCount = 0
    val message = CSharpErrorCode.ERR_InvalidMemberDecl.describe(currentText, anchor = CSharpDiagnosticAnchor.FIRST)
    advance() // the first token, with ERR_InvalidMemberDecl
    while (true) {
        val kind = currentKind
        if (canStartMember(kind) &&
            !(kind === SyntaxKind.DelegateKeyword && (peekToken(1).kind === SyntaxKind.OpenBraceToken || peekToken(1).kind === SyntaxKind.OpenParenToken))
        ) break
        if (kind === SyntaxKind.OpenBraceToken) {
            curlyCount++
        } else if (kind === SyntaxKind.CloseBraceToken) {
            if (curlyCount-- == 0) break
        } else if (kind === SyntaxKind.EndOfFileToken) {
            break
        }
        advance()
    }
    m.error(message)
    countSkippedError()
}

/** `ParseBaseList` (LP 2165) */
private fun LanguageParser.parseBaseList() {
    if (currentKind !== SyntaxKind.ColonToken) return
    val m = open()
    eatToken()
    val firstType = parseType()
    if (currentKind === SyntaxKind.OpenParenToken) {
        val bt = firstType.precede()
        parseParenthesizedArgumentList()
        bt.done(SyntaxKind.PrimaryConstructorBaseType)
    } else {
        firstType.wrap(SyntaxKind.SimpleBaseType)
    }
    while (true) {
        if (currentKind === SyntaxKind.OpenBraceToken || currentKind === SyntaxKind.SemicolonToken || isCurrentTokenWhereOfConstraintClause()) break
        if (currentKind === SyntaxKind.CommaToken) {
            eatToken()
            parseType().wrap(SyntaxKind.SimpleBaseType)
            continue
        }
        if (getModifierExcludingScoped(currentToken) != null) break
        if (isPossibleType()) {
            eatToken(SyntaxKind.CommaToken)
            parseType().wrap(SyntaxKind.SimpleBaseType)
            continue
        }
        val action = skipBadSeparatedListTokensWithExpectedKind(
            isNotExpected = { currentKind !== SyntaxKind.CommaToken && !isTrueIdentifier() },
            abort = { currentKind === SyntaxKind.OpenBraceToken || isCurrentTokenWhereOfConstraintClause() },
            SyntaxKind.CommaToken, SyntaxKind.CommaToken,
        )
        if (action == PostSkipAction.Abort) break
    }
    m.done(SyntaxKind.BaseList)
}

/** `ParseTypeParameterConstraintClauses` (LP 2242) */
fun LanguageParser.parseTypeParameterConstraintClauses() {
    while (currentContextualKind === SyntaxKind.WhereKeyword) parseTypeParameterConstraintClause()
}

/** `ParseTypeParameterConstraintClause` (LP 2250) */
private fun LanguageParser.parseTypeParameterConstraintClause() {
    val m = open()
    eatContextualToken(SyntaxKind.WhereKeyword)
    if (!isTrueIdentifier()) addError(createMissingIdentifierName()) else parseIdentifierName()
    eatToken(SyntaxKind.ColonToken)

    fun missingTypeConstraint() {
        val tc = open()
        createMissingIdentifierName(CSharpErrorCode.ERR_TypeExpected.describe())
        tc.done(SyntaxKind.TypeConstraint)
    }

    if (currentKind === SyntaxKind.OpenBraceToken || isCurrentTokenWhereOfConstraintClause()) {
        missingTypeConstraint()
    } else {
        var constraint = parseTypeParameterConstraint()
        while (true) {
            if (currentKind === SyntaxKind.OpenBraceToken ||
                (termState and TerminatorState.IsEndOfTypeSignature != 0 && currentKind === SyntaxKind.SemicolonToken) ||
                currentKind === SyntaxKind.EqualsGreaterThanToken || currentContextualKind === SyntaxKind.WhereKeyword
            ) break
            val haveComma = currentKind === SyntaxKind.CommaToken
            if (haveComma || isPossibleTypeParameterConstraint()) {
                if (constraint === SyntaxKind.AllowsConstraintClause && haveComma &&
                    !speculate { eatToken(); isPossibleTypeParameterConstraint() }
                ) {
                    skipTokens(1, CSharpErrorCode.ERR_UnexpectedToken.describe(","))
                    break
                }
                eatToken(SyntaxKind.CommaToken)
                if (isCurrentTokenWhereOfConstraintClause()) {
                    missingTypeConstraint()
                    break
                }
                constraint = parseTypeParameterConstraint()
            } else if (skipBadSeparatedListTokensWithExpectedKind(
                    isNotExpected = { currentKind !== SyntaxKind.CommaToken && !isPossibleTypeParameterConstraint() },
                    abort = { currentKind === SyntaxKind.OpenBraceToken || isCurrentTokenWhereOfConstraintClause() },
                    SyntaxKind.CommaToken, SyntaxKind.CommaToken,
                ) == PostSkipAction.Abort
            ) {
                break
            }
        }
    }
    m.done(SyntaxKind.TypeParameterConstraintClause)
}

/** `IsPossibleTypeParameterConstraint` (LP 2329) */
private fun LanguageParser.isPossibleTypeParameterConstraint(): Boolean = when (currentKind) {
    SyntaxKind.NewKeyword, SyntaxKind.ClassKeyword, SyntaxKind.StructKeyword, SyntaxKind.DefaultKeyword -> true
    SyntaxKind.IdentifierToken -> (currentContextualKind === SyntaxKind.AllowsKeyword && peekToken(1).kind === SyntaxKind.RefKeyword) || isTrueIdentifier()
    else -> CSharpSyntaxFacts.isPredefinedType(currentKind)
}

/** `ParseTypeParameterConstraint` (LP 2346): returns the constraint's kind. */
private fun LanguageParser.parseTypeParameterConstraint(): IElementType {
    val m = open()
    when (currentKind) {
        SyntaxKind.NewKeyword -> {
            eatToken()
            eatToken(SyntaxKind.OpenParenToken)
            eatToken(SyntaxKind.CloseParenToken)
            return m.done(SyntaxKind.ConstructorConstraint).kind
        }
        SyntaxKind.StructKeyword -> {
            eatToken()
            if (currentKind === SyntaxKind.QuestionToken) {
                errorCount++ // ERR_UnexpectedToken on the `?`, which stays in the tree
                eatToken()
            }
            return m.done(SyntaxKind.StructConstraint).kind
        }
        SyntaxKind.ClassKeyword -> {
            eatToken()
            tryEatToken(SyntaxKind.QuestionToken)
            return m.done(SyntaxKind.ClassConstraint).kind
        }
        SyntaxKind.DefaultKeyword -> {
            eatToken()
            return m.done(SyntaxKind.DefaultConstraint).kind
        }
        SyntaxKind.EnumKeyword -> {
            addError(createMissingIdentifierName(CSharpErrorCode.ERR_NoEnumConstraint.describe()))
            skipTokens(1)
            return m.done(SyntaxKind.TypeConstraint).kind
        }
        SyntaxKind.DelegateKeyword -> {
            if (peekToken(1).kind === SyntaxKind.AsteriskToken) {
                parseType()
            } else {
                addError(createMissingIdentifierName(CSharpErrorCode.ERR_NoDelegateConstraint.describe()))
                skipTokens(1)
            }
            return m.done(SyntaxKind.TypeConstraint).kind
        }
    }
    if (currentContextualKind === SyntaxKind.AllowsKeyword && peekToken(1).kind === SyntaxKind.RefKeyword) {
        eatContextualToken(SyntaxKind.AllowsKeyword)
        while (true) {
            val r = open()
            eatToken(SyntaxKind.RefKeyword)
            eatToken(SyntaxKind.StructKeyword)
            r.done(SyntaxKind.RefStructConstraint)
            if (currentKind === SyntaxKind.CommaToken && peekToken(1).kind === SyntaxKind.RefKeyword) {
                eatToken()
                continue
            }
            break
        }
        return m.done(SyntaxKind.AllowsConstraintClause).kind
    }
    parseType()
    return m.done(SyntaxKind.TypeConstraint).kind
}

/** `ParseDelegateDeclaration` (LP 5881) */
private fun LanguageParser.parseDelegateDeclaration(m: Open, mods: Modifiers): Node {
    eatToken(SyntaxKind.DelegateKeyword)
    parseReturnType()
    val saveTerm = termState
    termState = termState or TerminatorState.IsEndOfMethodSignature
    parseIdentifierToken()
    parseTypeParameterList()
    parseParenthesizedParameterList()
    if (currentContextualKind === SyntaxKind.WhereKeyword) parseTypeParameterConstraintClauses()
    termState = saveTerm
    eatToken(SyntaxKind.SemicolonToken)
    return finishMember(m, SyntaxKind.DelegateDeclaration, mods)
}

/** `ParseEnumDeclaration` (LP 5914) */
private fun LanguageParser.parseEnumDeclaration(m: Open, mods: Modifiers): Node {
    eatToken(SyntaxKind.EnumKeyword)
    parseIdentifierToken()
    if (currentKind === SyntaxKind.LessThanToken) {
        skipAsError(CSharpErrorCode.ERR_UnexpectedGenericName.describe(anchor = CSharpDiagnosticAnchor.PREVIOUS)) { parseTypeParameterList() }
    }
    if (currentKind === SyntaxKind.ColonToken) {
        val b = open()
        eatToken()
        parseType().wrap(SyntaxKind.SimpleBaseType)
        b.done(SyntaxKind.BaseList)
    }
    if (currentKind === SyntaxKind.SemicolonToken) {
        eatToken()
    } else {
        if (eatToken(SyntaxKind.OpenBraceToken)) {
            parseCommaSeparatedSyntaxList(
                SyntaxKind.CloseBraceToken,
                isPossibleElement = { isPossibleEnumMemberDeclaration() },
                parseElement = { parseEnumMemberDeclaration() },
                skipBadTokens = { expected, close ->
                    skipBadSeparatedListTokensWithExpectedKind(
                        isNotExpected = { currentKind !== SyntaxKind.CommaToken && currentKind !== SyntaxKind.SemicolonToken && !isPossibleEnumMemberDeclaration() },
                        abort = { c -> currentKind === c },
                        expected, close,
                    )
                },
                allowTrailingSeparator = true,
                requireOneElement = false,
                allowSemicolonAsSeparator = true,
            )
        }
        eatToken(SyntaxKind.CloseBraceToken)
        tryEatToken(SyntaxKind.SemicolonToken)
    }
    return finishMember(m, SyntaxKind.EnumDeclaration, mods)
}

/** `ParseEnumMemberDeclaration` (LP 5998) */
private fun LanguageParser.parseEnumMemberDeclaration() {
    val m = open()
    parseAttributeDeclarations(inExpressionContext = false)
    parseIdentifierToken()
    if (currentKind === SyntaxKind.EqualsToken) {
        val ev = open()
        eatToken()
        if (currentKind === SyntaxKind.CommaToken || currentKind === SyntaxKind.CloseBraceToken) parseIdentifierName(CSharpErrorCode.ERR_ConstantExpected.describe())
        else parseExpressionCore()
        ev.done(SyntaxKind.EqualsValueClause)
    }
    m.done(SyntaxKind.EnumMemberDeclaration)
}

/** `IsPossibleEnumMemberDeclaration` (LP 6021) */
private fun LanguageParser.isPossibleEnumMemberDeclaration(): Boolean = currentKind === SyntaxKind.OpenBracketToken || isTrueIdentifier()

// ---- type parameters ----------------------------------------------------------------------------------------------------

/** `ParseTypeParameterList` (LP 6163); null when there is no `<`. */
fun LanguageParser.parseTypeParameterList(): Node? {
    if (currentKind !== SyntaxKind.LessThanToken) return null
    val saveTerm = termState
    termState = termState or TerminatorState.IsEndOfTypeParameterList
    val m = open()
    eatToken()
    parseCommaSeparatedSyntaxList(
        SyntaxKind.GreaterThanToken,
        isPossibleElement = { isStartOfTypeParameter() },
        parseElement = { parseTypeParameter() },
        skipBadTokens = { expected, close ->
            skipBadSeparatedListTokensWithExpectedKind(
                isNotExpected = { currentKind !== SyntaxKind.CommaToken },
                abort = { c -> currentKind === c },
                expected, close,
            )
        },
        allowTrailingSeparator = false,
        requireOneElement = true,
        allowSemicolonAsSeparator = false,
    )
    termState = saveTerm
    eatToken(SyntaxKind.GreaterThanToken)
    return m.done(SyntaxKind.TypeParameterList)
}

/** `IsStartOfTypeParameter` (LP 6201) */
private fun LanguageParser.isStartOfTypeParameter(): Boolean {
    if (isCurrentTokenWhereOfConstraintClause()) return false
    if (currentKind === SyntaxKind.OpenBracketToken || currentKind === SyntaxKind.InKeyword || currentKind === SyntaxKind.OutKeyword) return true
    return isTrueIdentifier()
}

/** `ParseTypeParameter` (LP 6214) */
private fun LanguageParser.parseTypeParameter() {
    val m = open()
    if (currentKind === SyntaxKind.OpenBracketToken) {
        val saveTerm = termState
        termState = TerminatorState.IsEndOfTypeArgumentList
        parseAttributeDeclarations(inExpressionContext = false)
        termState = saveTerm
    }
    if (isCurrentTokenWhereOfConstraintClause() || isCurrentTokenPartialKeywordOfPartialMemberOrType()) {
        createMissingToken(SyntaxKind.IdentifierToken)
    } else {
        if (currentKind === SyntaxKind.InKeyword || currentKind === SyntaxKind.OutKeyword) eatToken()
        parseIdentifierToken()
    }
    m.done(SyntaxKind.TypeParameter)
}
