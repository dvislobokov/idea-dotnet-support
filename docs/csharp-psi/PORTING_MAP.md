# Porting map: Roslyn's expression parser onto PsiBuilder

Where things are in Roslyn at `roslynCommit` 35d9211b841e, for the port of `LanguageParser` (step 0 slice, step 5).
Paths under `.corpus/roslyn/src/Compilers/CSharp/Portable/`. **LP** = `Parser/LanguageParser.cs`,
**LPP** = `Parser/LanguageParser_Patterns.cs`, **SP** = `Parser/SyntaxParser.cs`. Line ranges, size in parentheses.
Line numbers are valid for this commit only: after a Roslyn bump, regenerate this map.

## 1. Expressions (LP)

**Entry, precedence.** `ParseExpression` 11123 → `ParseExpressionCore` 11130 → `ParseSubExpression(Precedence.Expression)`
11496 (recursion depth, stack guard) → `ParseSubExpressionCore` 11511–11587: statement keywords → missing
`IdentifierName` + error (`IsInvalidSubExpression` 11209); else `ParseExpressionContinued(parseUnaryOrPrimaryExpression(p), p)`.
`parseUnaryOrPrimaryExpression` 11527–11586: prefix unary (recursing at `GetPrecedence(op)`), leading `..`,
`IsAwaitExpression` 11440, `IsQueryExpression`, stray `from`, `throw` (`ParseThrowExpression` 11988),
`IsPossibleDeconstructionLeft` 12295 → `ParseDeclarationExpression` 11976, `ParsePrimaryExpression`. Casts are not unary:
they come from the `(` branch of the primary parser.

`ParseExpressionContinued` 11596–11795 (binary/assignment loop): `tryExpandExpression` 11621–11698 (precedence,
right associativity; `as` → `ParseType(AsExpression)`, `is` → `ParseIsExpression` 11995, `switch` → LPP
`ParseSwitchExpression` 593, `with` → `ParseWithExpression` 13519, range, `ParseAssignmentExpression` 11898 incl.
`= ref`, binary); precedence-inversion warning 11647; `consumeConditionalExpression` 11700–11767,
`containsTernaryCollectionToReinterpret` 11769. Operator scanning: `GetExpressionOperatorTokenKindAndExpressionKind`
11797–11855 (merges `..`, `>>`, `>>=`, `>>>`, `>>>=`; `switch {`, `with {`), `EatExpressionOperatorToken` 11857.
`Precedence` enum 11261, `GetPrecedence` 11287–11418, `IsRightAssociative` 11237, `IsPossibleExpression` 11151,
`CanStartExpression` 11138, range `IsAtDotDotToken` 11923 / `EatDotDotToken` 11940.

**Primary.** `ParsePrimaryExpression` 12006–12279: `parsePrimaryExpressionWithoutPostfix` 12015–12190 (switch:
`typeof` 12688, `default` 12697, `sizeof` 12714, `__makeref` 12723, `__reftype` 12732, `checked`/`unchecked` 12741,
`unsafe(...)` 12755, `__refvalue` 12764; `::` → `ParseAliasQualifiedName`; `=>`; `static`; identifier → anonymous
method / `TryParseLambdaExpression` / deconstruction / `field` 12073 / `ParseAliasQualifiedName(InExpression)`;
`[` → lambda or `ParseCollectionExpression` 13325; `this`, `base`, literals; interpolated and raw strings;
`(` → lambda or `ParseCastOrParenExpressionOrTuple`; `new` → `ParseNewExpression`; `stackalloc`; `delegate`; `ref`;
predefined type). `parsePostFixExpression` 12192–12278: `(`, `[`, `++`/`--`, `::` (recovery), `->`, `.` (newline
heuristic), `?` → `TryParseConditionalAccessExpression` 12368–12503 (`isStartOfElementBindingExpression` 12425 does a
speculative full parse; `parseWhenNotNull` 12447), `!`. Arguments: `ParseParenthesizedArgumentList` 12507,
`ParseBracketedArgumentList` 12523, `ParseArgumentList` 12539–12620, `ParseArgumentExpression` 12645 (`out` →
`ParseExpressionOrDeclaration`). `nameof` is a plain invocation.

**Parens, casts, tuples, lambdas.** `ParseCastOrParenExpressionOrTuple` 12892–12944 (`ScanCast` then reset; else
`ParseExpressionOrDeclaration` 9889, tuple tail 12946 or `ParenthesizedExpression` with
`ParseErrantExpressionWhenNoCloseParenToken` 10047). `ScanCast` 12980–13111, `CanFollowCast` 13245.
`IsPossibleLambdaExpression` 13118–13243 → `ScanParenthesizedLambda` 12775,
`ScanImplicitlyTypedLambdaOrSimpleExplicitlyTypedParenthesizedLambda` 12785, `ScanExplicitlyTypedLambda` 12816.
`TryParseLambdaExpression` 13894, `ParseLambdaExpression` 13909, `ParseLambdaBody` 13963, `ParseLambdaParameterList`
13968, `IsPossibleLambdaParameter` 14001, `ParseLambdaParameter` 14025, `ShouldParseLambdaParameterType` 14056.
`ParseAnonymousMethodExpression` 13795, `ParseAnonymousFunctionModifiers` 13847, `IsAnonymousFunctionAsyncModifier`
13872, `IsPossibleAnonymousMethodExpression` 12347.

**new, initializers, collections.** `ParseNewExpression` 13306 → `ParseAnonymousTypeExpression` 13384,
`ParseImplicitlyTypedArrayCreation` 13680, `ParseArrayOrObjectCreationExpression` 13444, `IsImplicitObjectCreation`
13491. `ParseObjectOrCollectionInitializer` 13544 (kind decided after parsing), member 13590, named assignment 13629,
dictionary 13642, complex element 13653, `ParseArrayInitializer` 13720. `ParseCollectionElement` 13359.
`stackalloc` 13749–13793.

**Generic ambiguity** `a < b > (c)`: `ParseSimpleName` 6241 → `ScanTypeArgumentList` 6286–6413 (disambiguating token
switch 6338–6412, `NameOptions` 6048), `ScanPossibleTypeArgumentList` 6415–6601, `ParseTypeArgumentList` 6604,
`ParseTypeArgument` 6740, `IsOpenName` 6810.

**Patterns (LPP).** `ParseTypeOrPatternForIsOperator` 21 → `ParsePattern` 53 → `ParseDisjunctivePattern` 58 →
`ParseConjunctivePattern` 104 → `ParseNegatedPattern` 158 → `ParsePrimaryPattern` 187 → `ParsePatternContinued` 284.
`CanTokenFollowTypeInPattern` 257, `LooksLikeTypeOfPattern` 76, `IsValidPatternDesignation` 407,
`ParsePropertyPatternClause` 509, `ParseSubpatternElement` 528, `ParseListPattern` 660, `ConvertTypeToExpression` 479,
`ConvertExpressionToType` 33. LP: `ParseDesignation` 10705, `ParseSimpleDesignation` 10761, `ParseWhenClause` 10768.

Interpolated strings: `LanguageParser_InterpolatedString.cs` (the lexer splits them, docs/csharp-psi/GRAMMAR.md); queries LP
14101–14421: §3.

## 2. Types and scans (LP)

`ParseType` 7630, `ParseTypeCore` 7643–7744 (`?`, `*`, `[]` by `ParseTypeMode` 7616),
`TryEatNullableQualifierIfApplicable` 7746–7905 (speculative expression parse 7796), `ParseUnderlyingType` 8033,
`ParseQualifiedName` 7095, `ParseAliasQualifiedName` 7087, `ParseQualifiedNameRight` 7114, `ParseIdentifierName` 6091,
`ParseIdentifierToken` 6105, `IsTrueIdentifier` 6065 (pulls `IsPartialType`/`IsPartialMember` 1664–1732,
`IsCurrentTokenWhereOfConstraintClause` 2234), `ParseArrayRankSpecifier` 7924, `ParsePointerTypeMods` 8237,
`ParseTupleType` 7984, `ParseTupleElement` 8018, `ParseFunctionPointerTypeSyntax` 8068–8226, `ParseTypeOrVoid` 7605,
`ParseReturnType` 3754.

Scans (eat tokens, always under a reset point): `ScanType` 7224/7229/7258–7425, `ScanNamedTypePart` 7239,
`ScanTupleType` 7432, `ScanFunctionPointerType` 7476, `ScanPossibleTypeArgumentList`, `ScanTypeArgumentList`,
`ScanCast`, the `ScanParenthesizedLambda` family, `ScanDesignator` 12310, LPP `ScanDesignation` 119; `ScanTypeFlags`
7166. Speculative `Is*` checks with reset points: `IsPossibleLambdaExpression`, `IsPossibleDeconstructionLeft`,
`IsImplicitObjectCreation`, `IsPossibleDeclarationExpression` 9896, `IsQueryExpressionAfterFrom`,
`LooksLikeTupleArrayType`, `looksLikeCast`, `IsValidPatternDesignation`, `isStartOfElementBindingExpression`.

## 3. Statements pulled in by lambda bodies

`ParseBlock` 9162 → `ParseStatements` 9205 (`IsPossibleStatement` 9297, `IsDefiniteStatement` 9265,
`SkipBadStatementListTokens` 9251) → `ParsePossiblyAttributedStatement` 8255 → `ParseStatementAttributeDeclarations`
8258 → `ParseStatementCore` 8346 → `ParseStatementCoreRest` 8441. Minimum: block, expression statement 11101, `return`
10177, `throw` 10372, empty statement. Local declarations: `IsPossibleLocalDeclarationStatement` and helpers 8568–9113,
`ParseLocalDeclarationStatement` 10546, `ParseLocalDeclaration` 10808, `ParseVariableDeclarators` 5336–5823, local
functions 10985–11100. Lambda attributes `ParseAttributeDeclarations` 1075–1300; parameter modifiers 5033–5105,
`IsDefiniteScopedModifier` 10647; `delegate(...)` parameters 4792/4870/4980; `ParseNameEquals` 950.

**All statements (step 5, `LanguageParser_Statements.kt`).** Dispatch `ParseStatementCore` 8346 (switch 8362–8425),
`TryParseStatementStartingWithIdentifier` 8491 (`await foreach`, `await using (`, label, `yield`, `await` expression,
query statement), `ParseStatementStartingWithUsing` 8526, `TryParseStatementStartingWithUnsafe` 8530,
`ParsePossiblyAttributedBlock` 9114, `ParseStatements(stopOnSwitchSections)` 9205, `ParseEmbeddedStatement` 9352.
Per statement: `fixed` 9328 (`IsEndOfFixedStatement` 9347), `break` 9394, `continue` 9403, `try` 9412 (missing
blocks 9475, `IsEndOfTryBlock` 9483), `ParseCatchClause` 9488 (`IsEndOfCatchClause` 9547, `IsEndOfFilterClause` 9556,
`IsEndOfCatchBlock` 9564), `checked`/`unchecked` 9571, `do` 9588 (`IsEndOfDoWhileExpression` 9612),
`ParseForOrForEachStatement` 9617, `for` 9650 (locals `eatVariableDeclarationOrInitializers` 9692,
`eatCommaOrSemicolon` 9744, `eatUnexpectedTokensAndCloseParenToken` 9749, `parseForStatementExpressionList` 9763,
`IsEndOfForStatementArgument` 9787), `foreach` 9792 (`IsValidForeachVariable` 9993), `goto` 10011, `if` 10077,
`ParseMisplacedElse` 10142, `ParseElseClauseOpt` 10156, `lock` 10165, `yield` 10187, `switch` 10224 (header 10244,
`IsPossibleSwitchSection` 10294, `ParseSwitchSection` 10300; LPP `ParseExpressionOrPatternForSwitchStatement` 459),
`unsafe` 10382, `using (…)` 10391 (`ParseUsingExpression` 10414, `IsUsingStatementVariableDeclaration` 10507),
`while` 10518, labels 10530, `ParseParenthesizedVariableDeclaration` 10786 → `ParseLocalDeclaration` 10808 →
`ParseVariableDeclarators` 5336 (`stopOnCloseParen`, `VariableFlags.ForStatement` comma rule 5372).

**Queries (step 5, `LanguageParser_Query.kt`).** `IsQueryExpression` 14142, `IsQueryExpressionAfterFrom` 14149,
`ParseQueryExpression` 14200 (`IsInQuery` for the whole query), `ParseQueryBody` 14212, `ParseFromClause` 14259,
`ParseJoinClause` 14292 (`JoinIntoClause`), `ParseLetClause` 14312, `ParseWhereClause` 14325, `ParseOrderByClause` 14333,
`ParseOrdering` 14378, `ParseSelectClause` 14396, `ParseGroupClause` 14404, `ParseQueryContinuation` 14414. Query
keywords stop names through `IsTokenQueryContextualKeyword` 14104 (`IsTrueIdentifier`, `ParseIdentifierToken`); a stray
`from` in a query is skipped (11564).

## 4. SyntaxParser infrastructure → PsiBuilder

| Roslyn | PsiBuilder |
|---|---|
| `CurrentToken.Kind` / `.ContextualKind` (SP 316) | `tokenType`; contextual kind from `tokenText` via `CSharpSyntaxFacts` |
| `PeekToken(n)` 466 | `lookAhead(n)`; its text (`PeekToken(1).ContextualKind` at 11457, 12251, 7841, 13160) via `rawLookup`/`rawTokenTypeStart` or mark/advance/rollback |
| `EatToken()` 486, `TryEatToken` 497 | `advanceLexer` |
| `EatToken(kind)` 521 (missing token + diagnostic) | advance if matches, else `error("X expected")` without advancing |
| `EatTokenAsKind` 537, `EatTokenEvenWithIncorrectKind` 609, `EatTokenWithPrejudice` 638 | advance inside an error marker |
| `EatContextualToken` 645, `ConvertToKeyword` 1104 | `remapCurrentToken(keyword)` + advance |
| `GetResetPoint`/`Reset`/`Release` SP 158–217, LP wrapper 14634–14707 (also snapshots `_termState`, `IsInAsync`, `IsInQuery`, `IsInFieldKeywordContext`) | `mark()`/`rollbackTo()`/`drop()` + snapshot of the parser state |
| `AddError` 749/760/898, `WithAdditionalDiagnostics` 732, `AddErrorToFirstToken` 903 | `error`/`marker.error`; warnings (`WRN_PrecedenceInversion`) are not parse errors |
| `AddLeadingSkippedSyntax` 958, `AddTrailingSkippedSyntax` 970, `AddSkippedSyntax` 1018 | error element around the tokens |
| `CreateMissingIdentifierName` 6037, missing tokens SP 552 | `error(...)`, no element |
| `TerminatorState` LP 58, `IsTerminator` 94 | bit set + predicate |
| `ParseCommaSeparatedSyntaxList` 14508, `SkipBad*` 4523–4672, `IsMakingProgress` SP 1178 | ported as is |
| `CurrentNode`/`EatNode`/blender SP 250–314 | not ported (lazy reparseable bodies instead) |
| `CheckFeatureAvailability` SP 1147 | not in the parser (language level annotator later) |

## 5. Parser state

`_termState` (set in `IsEndOfArgumentList` 12557, `IsEndOfParameterList` 13972, `IsEndOfTypeArgumentList` 6753/6220,
function pointers 7506/7545/8098/8186, `IsPatternInSwitchExpressionArm` LPP 620, case labels LPP 462,
`IsPossibleStatementStartOrStop` 9208, `IsEndOfReturnType` 3756); `_recursionDepth`; `SyntaxFactoryContext` 14452
via `ParserSyntaxContextResetter` 4344: `IsInAsync` (lambdas 13798/13915, retry 8482; read by `IsAwaitExpression`,
`ParseIdentifierToken` 6125, `IsPossibleDeclarationExpression` 9901), `IsInQuery`, `IsInFieldKeywordContext` (12073),
`ForceConditionalAccessExpression` (11726, 12433). `ParseTypeMode`, `NameOptions` are parameters. `IsScript` affects
`ParseExpressionStatement` 11109.

## 6. `>>` and contextual keywords

The lexer produces only `>` and `>=` (`Lexer.cs` 605, comment 3777). The parser merges adjacent `>` (no trivia between,
`NoTriviaBetween` 5033) in `GetExpressionOperatorTokenKindAndExpressionKind` 11813–11838 and eats them in
`EatExpressionOperatorToken` 11864–11890; operator declarations 4100–4137. PsiBuilder: `mark(); advanceLexer()×n;
collapse(GreaterThanGreaterThanToken)` after checking adjacency by offsets. `..` is merged likewise (11923–11972).
Contextual keywords: the lexer returns `IdentifierToken` with `ContextualKind` (`Lexer.ScanIdentifierOrKeyword`
1815–1862); `IsIdentifierVar` in `Syntax/SyntaxFacts.cs` 443.

## 7. What does not map directly onto PsiBuilder

1. Forward reset: `consumeConditionalExpression` 11711–11742 restores an *earlier* result after trying another →
   third parse. `containsTernaryCollectionToReinterpret` 11769 walks the built tree → a flag.
2. Decisions by inspecting built nodes: `TryParseLambdaExpression` 13899 (`NullableType` return type),
   `ParsePrimaryPattern` 237 / `ParseTypeArgument` 6785 (`IsMissing`), `ParseStatementCoreRest` 8470
   (`ContainsDiagnostics`), `ParseErrantExpressionWhenNoCloseParenToken` 10062 (`GetLastToken().IsMissing`), switch arm
   `FullWidth == 0` LPP 640, `ParseArrayOrObjectCreationExpression` 13454 (`type.Kind == ArrayType`) → parse functions
   return what they built (kind, missing, error count).
3. Pattern ↔ type ↔ expression rewriting: LPP 21–31, 33–51 (`MemberAccess` → `QualifiedName`), 479–498 and 366
   (`QualifiedName` → `MemberAccess` then `ParseExpressionContinued`), 330–339 (parenthesized constant pattern →
   `ParenthesizedExpression`), 534–541 (pattern → `NameColon`/`ExpressionColon`), 294 (`var` → keyword), 28/474
   (`DiscardPattern` → `IdentifierName`). Done markers cannot be re-tagged.
4. Nodes reused in a new parent: tuple's first `IdentifierName` → `NameColon` (12930–12937, 12956); `ParseSimpleName`
   reuses the identifier token in `GenericName` (6243–6267) → `precede()` or delayed `done()`.
5. Left-associative bottom-up builds (binary, postfix, conditional access, type suffixes, qualified names,
   `BinaryPattern`) → `precede()` on the returned done marker.
6. Retroactive replacement: `ParseArrayRankSpecifier` 7969–7976, shared `Omitted*` (6614, 7931), `::` → missing `.`
   + skipped (`ConvertToMissingWithTrailingTrivia` 7159, 7139, 12224), `global` → keyword after the fact (7146).
7. Parsed subtrees turned into skipped trivia: 10064, 13692–13699, 13766–13773, 6790/6795/6800, LPP 168/176/385/644,
   11567, 12230, 11967, `ConsumeUnexpectedTokens` 14709 → error elements.
8. Diagnostics on tokens or nodes: `AddError`/`WithAdditionalDiagnostics` (11522, 11578, 11660, 12140, 12154–12184,
   14208), missing tokens (SP 552).
9. Interpolated strings: nested lexer and parser.
10. Speculative full parses in scans (12440, 7796, 12875, 13148): fine with mark/rollback, quadratic in pathological
    input.

## 8. Size (C# lines; Kotlin ≈ 0.8–1×)

Expressions ~3,450; names and types ~1,900; patterns ~680; declaration expressions and designations ~320; lambda
dependencies ~650 → core slice ≈ 7,000; + minimal statements ≈ 7,550; + local declarations and functions ≈ 9,000;
+ all statements ≈ 10,500; queries + interpolated strings ~950 more. SyntaxParser infrastructure to port ≈ 350.

## 9. Declarations (LP), step 5

Roslyn line → Kotlin function (`LanguageParser_Declarations.kt` unless marked **M** = `LanguageParser_Members.kt`).
Departures: docs/csharp-psi/GRAMMAR.md, "Declarations".

| LP | Roslyn | Port |
|---|---|---|
| 94–135 | `IsTerminator` (declaration flags) | `isDeclarationTerminatorState`, `LanguageParser.isTerminatorState` |
| 168, 180 | `ParseCompilationUnit`, `ParseCompilationUnitCore` | `CSharpParserDefinition.parseCompilationUnit`, `parseCompilationUnitCore` |
| 236, 247 | `ParseNamespaceDeclaration(Core)` | `parseNamespaceDeclaration` |
| 331, 337 | `IsPossibleStartOfTypeDeclaration`, `IsTypeModifierOrTypeKeyword` | same names |
| 361 | `AddSkippedNamespaceText` | skipped error elements + `markLastDirty` |
| 410, 547 | `ParseNamespaceBody` (move members into the preceding type) | `parseNamespaceBody` (two passes), `AbsorbedType` |
| 562 | `ParseNamespaceBodyWorker` | `parseNamespaceBodyWorker` |
| 848 | `AddIncompleteMembers` / `ReduceIncompleteMembers` | local `addIncompleteMembers` / `reduceIncompleteMembers` |
| 869, 884 | `IsPossibleNamespaceMemberDeclaration`, `IsPartialInNamespaceMemberDeclaration` | same names |
| 921, 934, 958 | `ScanExternAliasDirective`, `ParseExternAliasDirective`, `ParseUsingDirective` | same names |
| 1028, 1035 | `IsPossibleGlobalAttributeDeclaration`, `IsGlobalAttributeTarget` | same names (value text, escapes decoded) |
| 1119 | `TryParseAttributeDeclaration` | `LanguageParser_Names.kt` (now internal) |
| 1299–1360 | `GetModifierExcludingScoped` | `getModifierExcludingScoped` |
| 1363 | `ParseModifiers` | `parseModifiers` → `Modifiers` |
| 1546, 1659 | `ShouldContextualKeywordBeTreatedAsModifier(false)`, `IsNonContextualModifier` | `shouldContextualKeywordBeTreatedAsDeclarationModifier`, `isNonContextualModifierToken` (the statement variants: `LanguageParser_Statements.kt`) |
| 1751, 1784 | `ParseTypeDeclaration`, `ParseMainTypeDeclaration` | same names |
| 2078, 2094 | `SkipBadMemberListTokens` | `skipBadMemberListTokens` |
| 2165 | `ParseBaseList` | `parseBaseList` |
| 2242–2420 | constraint clauses and constraints | `parseTypeParameterConstraintClause(s)`, `isPossibleTypeParameterConstraint`, `parseTypeParameterConstraint` |
| 2427, 2483, 3412 | `CanStartMember`, `IsTypeDeclarationStart`, `IsExtensionContainerStart` | same names (`isTypeDeclarationStartExact`) |
| 2570 | `ParseMemberDeclaration()` (test entry) | `SliceParseHarness`, `Mode.Member` |
| 2590, 2605 | `ParseMemberDeclarationOrStatement(Core)` | **M** `parseMemberDeclarationOrStatement(Core)` |
| 2934–3030 | locals `tryParseLocalDeclarationStatement(FromStartPoint)`, `tryParseStatement`, `isAcceptableNonDeclarationStatement`, `isFollowedByPossibleUsingDirective` | **M** same names |
| 3039, 3064, 3168, 3191, 3219 | `IsMisplacedModifier`, `IsNoneOrIncompleteMember`, `ReconsideredTypeAsAsyncModifier`, `TryParseIndexerOrPropertyDeclaration`, `IsStartOfPropertyBody` | **M** same names |
| 3239, 3254 | `ParseMemberDeclaration(Core)` | **M** `parseMemberDeclaration(Core)` |
| 3422, 3445, 3488, 3493 | `ReconsiderTypeAsAsyncModifier`, `IsFieldDeclaration`, `IsOperatorKeyword`, `IsComplete` | **M** same names |
| 3525–3605 | constructors, initializers, destructors | **M** `parseConstructorDeclaration`, `tryParseConstructorInitializer`, `parseConstructorInitializer`, `parseDestructorDeclaration` |
| 3609 | `ParseBlockAndExpressionBodiesWithSemicolon` | **M** same name |
| 3695 | `ParseMethodDeclaration` | **M** `parseMethodDeclaration` |
| 3776, 4004, 4017 | `TryParseConversionOperatorDeclaration`, `TryEatCheckedOrHandleUnchecked`, `ParseOperatorDeclaration` | **M** same names, `eatGreaterThanOperator` |
| 4217, 4277 | `ParseIndexerDeclaration`, `ParsePropertyDeclaration` | **M** same names |
| 4400, 4437, 4463, 4473, 4674 | accessor list, arrow clause, accessors | **M** `parseAccessorList`, `parseArrowExpressionClause`, `isPossibleAccessor`, `isPossibleAccessorModifier`, `parseAccessorDeclaration` |
| 4792, 4803, 4870, 4980 | parameter lists (`forExtensionOrUnion`, bracketed, `identifierIsOptional`) | `LanguageParser_Names.kt` |
| 5106–5304 | fixed buffers, events, normal and event fields, `EatUnexpectedTrailingSemicolon` | **M** `parseFixedSizeBufferDeclaration`, `parseEventDeclaration(WithAccessors)`, `parseNormalFieldDeclaration`, `parseFieldDeclarationVariableDeclarators` |
| 5336, 5527, 5781 | `ParseVariableDeclarators`, `ParseVariableDeclarator`, `looksLikeVariableInitializer` (fields) | **M** `parseFieldVariableDeclarator`, `looksLikeFieldInitializer` (locals: `LanguageParser_Statements.kt`) |
| 5824 | `IsLocalFunctionAfterIdentifier` | `LanguageParser_Statements.kt` |
| 5867, 5881, 5914, 5998, 6021 | const fields, delegates, enums | **M** `parseConstantFieldDeclaration`; `parseDelegateDeclaration`, `parseEnumDeclaration`, `parseEnumMemberDeclaration`, `isPossibleEnumMemberDeclaration` |
| 6163, 6201, 6214 | `ParseTypeParameterList`, `IsStartOfTypeParameter`, `ParseTypeParameter` | same names |
| 6820, 6958, 7005 | `ParseMemberName`, `AccumulateExplicitInterfaceName`, `IsOperatorStart` | **M** same names, `parseExplicitInterfaceParts` |
| 8540 | `ParseExpressionStatementOrLocalFunctionStartingWithUnsafe` | `LanguageParser_Statements.kt` |
| 8719, 8939, 8987 | `IsPossibleTopLevelUsingLocalDeclarationStatement`, `IsAnonymousDelegateExpression`, `IsPossibleNewExpression` | **M** same names |
| 9122 | `ParseMethodOrAccessorBodyBlock` | **M** `parseMethodOrAccessorBodyBlock` |
| 10985 | `TryParseLocalFunctionStatementBody` | **M** `tryParseLocalFunctionStatementBody` (called from the local declarator) |

## 10. Doc comments (DCP, Lexer XML modes), step 5

**DCP** = `Parser/DocumentationCommentParser.cs` (1,680 lines), **LX** = `Parser/Lexer.cs`. Ported whole, not onto
PsiBuilder: a doc comment is one lexer token, parsed lazily into a small green tree (`lang/doc`) that
`DocCommentTreeBuilder` turns into AST nodes (docs/csharp-psi/GRAMMAR.md, "Doc comments").

| Roslyn | Ours |
|---|---|
| DCP 64–129 `ParseDocumentationComment`, `ParseRemainder`; 131–200 `ParseXmlNodes`, `ParseXmlNode`, `ParseXmlText` | `DocumentationCommentParser`, same names |
| DCP 202–519 elements, attributes, `SkipBadTokens`, `IsVerbatimCref`; 553–788 attribute values, quotes, names, comments, CDATA, processing instructions | same names |
| DCP 855–1628 crefs (`ParseCrefAttributeValue` .. `IsEndOfCrefAttribute`); 1630–1676 name attribute values | same names |
| SP 170, 229, 466–540, 980, 1104: `Reset`, `Mode`, `PeekToken`, `EatToken`, `TryEatToken`, `AddTrailingSkippedSyntax`, `ConvertToKeyword` | token window of `DocumentationCommentParser` (blender semantics) |
| LX 2825–3560 `LexXmlToken` .. `ScanXmlCharacter`; 3576–3990 `ScanXmlCrefToken`, `AdvanceIfMatches`; 3989–4360 CDATA, comment, PI text; 4366–4560 `LexXmlDocCommentLeadingTrivia*`, `LexXmlWhitespaceAndNewLineTrivia`; 4630, 4739 `ScanUnicodeEscape`, `TryScanXmlEntity`; 1634 `ScanIdentifier_CrefSlowPath` | `DocCommentLexer`, same names |
| `Roslyn.Utilities.XmlCharType` (Core, not in the sparse checkout) | `doc/XmlCharType.kt`, generated by `roslyndump gen-kinds` |
