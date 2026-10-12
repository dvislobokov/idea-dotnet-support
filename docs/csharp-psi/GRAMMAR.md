# Grammar notes: Roslyn parser on PsiBuilder

Ambiguities and places where the port departs from Roslyn, with the Roslyn method each one comes from. Filled in by
step 0 (vertical slice) and step 5.

## Lexer vs Roslyn's tokens

The lexer is `csharp-psi-core/src/main/grammar/CSharp.flex` (`_CSharpLexer`, wrapped by `lang/lexer/CSharpLexer`); the
irregular parts are ported into `lang/lexer/CSharpLiteralScanner.kt` with Roslyn's method names, directives and `#if`
into `lang/lexer/CSharpPreprocessor.kt` (`DirectiveParser`, `DirectiveStack`, the directive mode of `Lexer`). Token
types are `SyntaxKind.*` (Roslyn's kind names), `TokenType.BAD_CHARACTER`, and the directive tokens
`CSharpDirectiveTokenType` (`CSharpTokenTypes.directive(kind)`, below). Checked by the token gate
`CSharpLexerDiffCorpusTest` (rules below, metrics `testData/metrics/*-lexer.json`, no preprocessor symbols as
`roslyndump` without `--define`): 0 mismatches, 0 bad characters, nothing skipped on Roslyn's `src`, `runtime`,
`aspnetcore` and `testData/lexer`, directive tokens and disabled text included.

What the lexer produces, by Roslyn method:

- `Lexer.ScanSyntaxToken`, `>`: always `GreaterThanToken` or `GreaterThanEqualsToken`. Roslyn's parser merges adjacent
  `>` into `>>` / `>>=` / `>>>` / `>>>=` (operator scanning in `LanguageParser`); ours does the same through
  `remapCurrentToken`. The gate splits the oracle's merged tokens back.
- `Lexer.ScanSyntaxToken`, `.`: `..` is two `DotToken`s (the parser merges them, `LanguageParser.EatDotDotToken`); `.5`
  is a real literal except right after a dot (`..5` is `.`, `.`, `5`).
- `Lexer.ScanIdentifierOrKeyword`: reserved keywords get their kinds; contextual keywords (`var`, `async`, `get`,
  `partial`, `where`, `_` ...) are `IdentifierToken`: the parser decides, as Roslyn's does (`roslyndump` marks them
  `ck=`; in the tree they may be `GetKeyword`, `UnderscoreToken`, ...; the gate maps any non-reserved `*Keyword` and
  `UnderscoreToken` of the oracle to `IdentifierToken`). `@` identifiers and identifiers with a unicode escape are
  never keywords (`int` is an identifier, as in Roslyn, even when the escape is rejected). Identifier characters
  follow `UnicodeCharacterUtilities` per UTF-16 unit with Java's Unicode tables (a supplementary letter is a bad
  character, as in Roslyn); formatting characters (Cf) inside an identifier are part of it.
- `Lexer.ScanNumericLiteral`: one `NumericLiteralToken` for all forms; malformed forms (`0x`, `1e`, `1_`) stay one token
  as in Roslyn (the error is a diagnostic there).
- `Lexer.ScanStringLiteral`, `ScanVerbatimStringLiteral`, `ScanRawStringLiteral`: one token for character, string,
  verbatim, raw (single/multi-line) literals and their `u8` forms. An escape takes the next character whatever it is:
  `"a\` + new line continues the string on the next line, as Roslyn's `NextChar`. Unterminated: to the end of the line
  (regular strings, characters) or of the input (verbatim, multi-line raw).
- Interpolated strings (`Lexer.ScanInterpolatedStringLiteral`, `InterpolatedOrRawStringScanner`): Roslyn's lexer makes
  one `InterpolatedStringToken` and the parser splits it (`LanguageParser.ParseInterpolatedOrRawStringToken`,
  `ParseInterpolation`). Ours scans the literal with the same scanner and emits the parser's parts: start token by
  kind, `InterpolatedStringTextToken`s, `OpenBraceToken` (`{{` for `$$`), the hole's tokens from a nested lexer over
  the expression text (`#` is a bad character there, `allowPreprocessorDirectives: false`), `ColonToken` and the format
  text, `CloseBraceToken`, the end token. Empty parts (Roslyn's missing tokens, an empty format) are left out. The
  parts after the first one have a non-zero lexer state (see "Lexer state" below).
  Departure: when Roslyn's parser skips an interpolated string in error recovery, its tree keeps the unsplit
  `InterpolatedStringToken`; the gate compares it with the union of our parts.
- Trivia (`Lexer.LexSyntaxTrivia`): `WhitespaceTrivia`, `EndOfLineTrivia` (one per new line), `SingleLineCommentTrivia`,
  `MultiLineCommentTrivia` (unterminated: to the end of the input; also Razor `@* *@`), `ConflictMarkerTrivia` and
  the `DisabledTextTrivia` of a merge conflict (`LexConflictMarkerTrivia`; not in holes).
- Doc comments (`Lexer.LexXmlDocComment`): one token each, `SingleLineDocumentationCommentTrivia` for consecutive
  `///` lines (not `////`), `MultiLineDocumentationCommentTrivia` for `/** */` (not `/**/`, `/***`), each parsed
  lazily into Roslyn's structured trivia (section "Doc comments" below). Departure: a single-line doc comment of
  Roslyn ends with the new line of its last line, ours ends before it (like a line comment, which is what the editor
  expects).
- Directives (`Lexer.LexDirectiveAndExcludedTrivia`, `LexExcludedDirectivesAndTrivia`, `LexSingleDirective`,
  `LexDisabledText`; `DirectiveParser.ParseDirective`): a `#` that is first on its line in leading trivia starts a
  directive, lexed as Roslyn's lexer in directive mode (`ScanDirectiveToken`, `LexDirectiveTrailingTrivia`) and with
  the kinds of Roslyn's *parsed* directive: `HashToken`, the directive keyword (`IfKeyword`, `ElifKeyword`,
  `DefineKeyword`, `PragmaKeyword`, `ReferenceKeyword` for `#r`, ...: `if`/`define`/`warning`... are identifiers to
  the lexer and become keywords only where `DirectiveParser` eats them with `EatContextualToken`, so `#if if` and
  `#define warning` keep `IdentifierToken`; `warning` after `#pragma` is `WarningKeyword`), `true`/`false`/`default`/
  `hidden`/`checksum`/`disable`/`restore`/`enable`/`warnings`/`annotations` always keywords, `IdentifierToken`,
  `NumericLiteralToken` (decimal digits only, `ScanInteger`), `StringLiteralToken` (no escapes; `"""` scans a raw
  literal, `ERR_RawStringNotInDirectives`), `!`, `!=`, `=`, `==`, `&&`, `||`, `(`, `)`, `,`, `-`, `:`, and `BadToken`
  for any other single character (a lone `&`, `|`, `'`, `@`, `/`, `.`...). Whitespace, `//` comments (`///` too) and the
  new line are ordinary trivia tokens. `#region`, `#endregion`, `#error`, `#warning` and `#!` take the rest of their
  line, after the keyword's trailing whitespace and `//` comment, as `PreprocessingMessageTrivia`
  (`LexOptionalPreprocessingMessage`); `#:` (no trivia between `#` and `:`, `ParseIgnoredDirective`) takes it as a
  `StringLiteralToken`. Extraneous tokens after a directive keep their lexed kinds (Roslyn's `SkippedTokensTrivia` of
  the `EndOfDirectiveToken`); the zero-width `EndOfDirectiveToken` itself has no token. A bad `#elif` (no `#if`
  before it, `ParseElifDirective`) turns its expression with its trailing trivia into `DisabledTextTrivia`, as Roslyn.
- Token types (decision): directive tokens are `CSharpDirectiveTokenType(roslynKind)`, a type per Roslyn kind distinct
  from the code token of that kind (debug name `IfKeywordInDirectiveTrivia`), so that the parser never sees them:
  together with `PreprocessingMessageTrivia` and `DisabledTextTrivia` they are in `CSharpTokenTypes.COMMENTS`, which
  `CSharpParserDefinition.getCommentTokens` returns (PsiBuilder skips them) and `SyntaxParser.isTrivia` reads (the
  token window skips them). Roslyn's structured directive nodes (`IfDirectiveTriviaSyntax` ...) are **not built**: a
  directive is a flat run of tokens, its structure lives only in the lexer (`DirectiveStack`). Highlighting needs the
  tokens; the tree gate (step 6) compares nodes without trivia, and directives are covered by the token gate. Nodes can
  be added later as a lazily parsed element over the directive's tokens if a feature needs them.
- `#if` evaluation (`DirectiveParser.ParseExpression` .. `ParsePrimary`, `Evaluate`, `IsDefined`; `DirectiveStack`):
  ported as is, quirks included — `||`, `&&`, `==`/`!=` (right-associative: `ParseEquality` recurses for the right
  operand), `!`, parentheses, `true`/`false`; an identifier is `bool.TryParse` first (case-insensitive, escapes
  decoded: `#if TRUE`, `#if true` are true), then `#define`/`#undef` above it in the stack, then the symbols
  (ordinal, case-sensitive); a missing operand or name is `""` (`#define` alone defines `""`); identifiers longer than
  128 characters are truncated (`TruncateIdentifier`). Branches: taken once (`PreviousBranchTaken`), `#elif`/`#else`
  after a taken branch excluded, a nested `#if` inside an excluded branch never taken but its `#elif`/`#else`/`#endif`
  matched (`LexExcludedDirectivesAndTrivia` with `endIsActive: false`); `#else`/`#elif`/`#endif` without `#if`, or with
  a `#region` open above the `#if` (`ERR_EndRegionDirectiveExpected`), are bad directives without effect, so an
  excluded branch with an open `#region` runs on until `#endregion`; an unterminated `#if` runs to the end of the file.
  `DirectiveStack.IsDefined` does not look at `isActive`: a `#define` in an excluded branch is seen by a later `#elif`
  of the same `#if` (and dropped at its `#endif`, `CompleteIf`). Inside an excluded branch every directive is still
  parsed (`#region`, `#pragma`, `#define` are directive tokens; `#define`/`#region` are pushed as Roslyn pushes them),
  the text between directives is one `DisabledTextTrivia` up to the start of the directive's line (its leading
  whitespace is a `WhitespaceTrivia`). Kept in the stack: `#if`/`#elif`/`#else`/`#region`/`#define`/`#undef` (Roslyn
  pushes every directive; no query looks at the others).
- Misplaced directives (`LexSyntaxTrivia`, case `#` with `isTrailing || !onlyWhitespaceOnLine`): Roslyn parses the
  directive without effect and makes it one `BadToken` (from `#` through its new line) in `SkippedTokensTrivia`.
  Departure: ours keeps the directive's tokens (no effect either); the gate compares the union of our tokens over the
  `BadToken`'s span (`misplacedDirectives`). The lexer tracks Roslyn's trivia position (`LineState`): after a token
  (trailing trivia) or after a comment on the line a directive is misplaced; a directive takes its new line, so in
  trailing trivia the next lines that start with `#` are misplaced too (`x; #define A` + `#define B`), until a new line
  outside a directive; a doc comment ends trailing trivia and leaves `onlyWhitespaceOnLine` as it is
  (`/** d */ #if X` is a directive; a single-line doc comment takes its new line, so after `/* c */ /// d` the next
  line's `#if` is misplaced).
- Symbols: `CSharpLexer(symbols)`, empty by default (gates, `roslyndump` without `--define`). Files take
  `CSharpPreprocessorSymbols.forFile`: `KEY` on the `PsiFile` or its `VirtualFile`, else `IDE_DEFAULT` = `DEBUG`,
  `TRACE`, `NET`, `NETCOREAPP`, `NET10_0`, `NET10_0_OR_GREATER` .. `NET5_0_OR_GREATER`, `NETCOREAPP3_1_OR_GREATER` ..
  `NETCOREAPP1_0_OR_GREATER` (a `Debug` build of `net10.0`); `CSharpFileElementType` parses with that lexer,
  `CSharpParserDefinition.createLexer` (no file) uses `IDE_DEFAULT`.
- Lexer state (incremental relexing): `LexerEditorHighlighter` restarts at a token of state 0 before a change and stops
  only at a state-0 token with the old type and shifted offset after it, so state 0 must mean "as at the start of a
  file". The `#if` stack does not fit an `int`, so it is not encoded; instead the state is 0 only when the directive
  stack has no entry that matters (no `#define`/`#undef`, no open `#if`/`#elif`/`#else`, no `#region` above one — a
  `#region` with no `#if` below it cannot change the rest of the lexing and is ignored, so files inside a plain
  `#region` keep their restart points), the line state is "leading trivia, only whitespace so far" (a line start) and
  no queued run (interpolated string parts, conflict regions, directives with their excluded text) is being returned;
  otherwise it is a non-zero hash of that context. Restart points are therefore line starts outside `#if` blocks and
  after no `#define`: an edit inside a long `#if` block (or anywhere after a `#define`) relexes from the block's start
  (from the top of the file). `CSharpLexer.start` always begins with the empty context: a non-zero initial state is
  not decoded (`Lexer.restore` of a mid-file `LexerPosition` is not supported). Tested by `checkCorrectRestart` on
  every lexed test text, `CSharpPreprocessorLexerTest.testStateIsZeroOnlyWithAnEmptyContext` and
  `CSharpPreprocessorPlatformTest.testIncrementalRelexingMatchesFullLexing` (a real `LexerEditorHighlighter`, 600
  random edits of directives against a full lexing).
- Bad characters (`ScanSyntaxToken`, default): `BAD_CHARACTER`, one character or a surrogate pair, a whole unicode
  escape, an `@` sequence; Roslyn's `BadToken`. Departures: Roslyn's limit of 200 bad tokens (then the rest of the file
  is one token) is not ported; `@:` gives `RazorContentToken` to the end of the line as in Roslyn.

## Parser: the step-0 slice (expressions, types, patterns, lambdas, minimal statements)

Files: `lang/parser/SyntaxParser.kt` (port of `SyntaxParser.cs`: token window, reset points, `EatToken` family,
missing and skipped tokens), `LanguageParser.kt` (expressions, `new`, lambdas, argument lists), `LanguageParser_Names.kt`
(names, types, scans, attributes, parameters), `LanguageParser_Patterns.kt` (`LanguageParser_Patterns.cs` whole),
`LanguageParser_Statements.kt` (every statement kind except local functions, step 5; section "Statements and
queries" below), `LanguageParser_Query.kt` (query expressions, step 5), `LanguageParser_InterpolatedString.kt`. Methods keep Roslyn's names (`parseSubExpression` = `ParseSubExpression`, in
a comment when the shape differs); `LP n` / `LPP n` / `LPI n` / `SP n` in comments are line numbers of
`LanguageParser.cs` / `_Patterns.cs` / `_InterpolatedString.cs` / `SyntaxParser.cs` at `roslynCommit`.
Entry for tests: `SliceParseHarness` (test sources) runs `parseExpression()` / `parseStatement()` over the whole text and
wraps what is left into one error element (`consumeUnexpectedTokens`, Roslyn `ConsumeUnexpectedTokens` LP 14709 with
`consumeFullText`). Not wired into `CSharpParserDefinition`: the file-level parser is step 5.

Gates: `ExpressionSliceDiffTest` (367 snippets in `testData/parser/slice`, valid ones must match exactly and carry no error element; invalid ones must match too) and
`ParsingTestsSliceCorpusTest` over `.corpus/parsing-tests` (metrics `testData/metrics/parsing-tests-slice.json`; the
`Script`/older-`LanguageVersion` tests are separate buckets, each input diffed at its own language version: section
"Language version").

### Roslyn's parser model on PsiBuilder

- **Token window** (`SyntaxParser.CurrentToken`, `PeekToken(n)`, `SP` blender-free path): `peekToken(n)` walks
  `rawLookup(step)` from `rawTokenIndex()`, skipping trivia (whitespace, comments, directives) and caches a `Tok`
  (kind, contextual kind, raw step, offsets) per raw position. Contextual keywords are `IdentifierToken` in `kind`
  and `*Keyword` in `contextualKind`, computed from the text as `SyntaxFacts.GetContextualKeywordKind(ValueText)`
  (`_` → `UnderscoreToken`, `@x` never a keyword; `var` is special-cased: it is absent from the generated map).
- **Pitfall, lazy whitespace**: `PsiBuilderImpl.advanceLexer()` stops *on* the whitespace after the token and skips it
  only when `getTokenType()`/`eof()` is called. `rawTokenIndex()`, `rawLookup()`, `mark()` and `remapCurrentToken()`
  used right after `advanceLexer()` work on the whitespace lexeme (symptom: a remapped `WithKeyword` whose range is a
  space, markers starting on whitespace). `SyntaxParser.advance()` therefore calls `builder.tokenType` after every
  `advanceLexer()` and after every `rollbackTo()` (`sync()`), and the constructor does it once before the first mark.
  The test harness marks its root *before* constructing the parser so leading whitespace is inside the file node.
- **Missing token** (`SP CreateMissingToken`, `EatToken(kind)` with the wrong token) = an empty composite of
  `CSharpMissingTokenType.of(kind)` (a *gap*, one element type per token kind) holding a zero-width error element
  with the message when Roslyn reports it, empty when it does not (`createMissingToken(kind, report = false)`: the
  missing `)` of `new T`, the `>` of a function pointer, `static` of `using unsafe static`). The kind is structural,
  never read back from the message (`'"' expected` is both `DoubleQuoteToken` and `InterpolatedStringEndToken`); the
  PSI uses it to assign the children to fields ("PSI"). `EatTokenEvenWithIncorrectKind` puts a gap of the expected
  kind before the token that stands in for it. Its Roslyn position (after the trailing trivia of the previous token
  up to the first line break) differs from the marker's position only in offsets, which the test mapping `PsiToDump`
  recomputes (`zeroWidthPosition`; a misplaced directive in trailing trivia is one `BadToken` that takes its line
  break, so the next line break ends the trivia). PsiBuilder keeps consecutive empty composites at one position
  (both `{` and `}` of an empty missing initializer are present). Where Roslyn's slot order puts a missing token
  before a real one parsed earlier (`using unsafe static`: `static` precedes `unsafe`), the gap is placed with
  `doneBefore`.
- **Skipped tokens** (`AddLeadingSkippedSyntax`, `AddTrailingSkippedSyntax`, `SkipBadTokens`) = a non-empty error
  element around them; `PsiToDump` does not let it extend the enclosing span, as `SkippedTokensTrivia` never extends
  Roslyn's `Span`. Order matters and follows Roslyn: `EatTokenAsKind` (SP) and the `!=` pattern (LPP 172) produce the
  missing token *first* and the skipped token after it (trailing trivia of the missing token: the enclosing node starts
  at the missing token); `ParseIdentifierToken` with a keyword does the same.
- **Parsed then demoted to skipped trivia** (`skipAsError { body }`): LP 10064 `ParseErrantExpressionWhenNoCloseParenToken`
  (`(a b)`), 13692–13699 `ParseImplicitlyTypedArrayCreation` (`new [A]`), 13766–13773 implicit `stackalloc[1]`,
  6790/6795/6800 `ParseTypeArgument` (attributes, `in`/`out`), LPP 168/176/385/644 — the construct is parsed to learn
  its extent, rolled back, and the same tokens are consumed into one error element. Roslyn's nodes disappear into
  trivia, so no node may survive inside the error element.
- **`AddError` on a node or token** (LP 11522, 11578, 11660, 12140, 12154–12184, 14208) = `errorCount++` only: no
  element, no position. Lexer errors of literals are counted the same way when the token is eaten
  (`SyntaxParser.advance` → `CSharpLexerDiagnostics.hasError`, a port of the checks of `ScanStringLiteral`,
  `ScanEscapeSequence`, `ScanVerbatimStringLiteral`, `ScanNumericLiteral` and the value conversions): Roslyn's
  decisions on `ContainsDiagnostics` see them (`int x 'ab';` has no initializer: `looksLikeVariableInitializer`).
  Not covered: raw and interpolated strings, identifiers with escapes, bad characters, unterminated comments.
- **Diagnostics** (0.1.54). An error element carries Roslyn's diagnostic in its description, `CSharpErrorCode.describe`:
  `CS1002: ; expected`, or with an anchor `CS1003@firstExpected: Syntax error, ',' expected` when the diagnostic is not
  where the element is (`CSharpDiagnosticAnchor`: first / second token of the element, the token after or before it, the
  node before it, ...); `SKIPPED` marks elements that report nothing themselves. `CSharpSyntaxDiagnostics.of(file,
  symbols)` turns them into Roslyn's spans after the parse — a missing token by `GetDiagnosticSpanForMissingNodeOrToken`
  (a new line in the trailing trivia of the token before: zero-width at its end; else on the current token; at the end
  of an interpolation hole: zero-width there) — and adds the lexer's diagnostics (`CSharpLexerDiagnostics.of`: literals,
  bad characters, `@@`, raw strings, unterminated `/*`) and the directives' (`CSharpPreprocessor.diagnostics`: the
  preprocessor is run again over the directives of the tree with the file's symbols, so a directive in excluded text
  reports what Roslyn reports with `isActive` false). `AddError` on a built node still only counts (`ERR_BadForeachDecl`,
  `ERR_SwitchGoverningExpressionRequiresParens`, `ERR_TopLevelStatementAfterNamespaceOrType`, a keyword as a member
  name, language versions): no element, so not reported. Checked against `roslyndump tree` (`D` lines) by
  `CSharpSyntaxDiagnosticsTest` of the plugin.
- **Reset points** (`SP GetResetPoint`/`Reset`/`Release`) = `mark()`/`rollbackTo()` plus a snapshot of the parser
  state that a speculative parse may change and the caller does not restore itself: `termState`, `isInAsync`,
  `isInQuery`, `isInFieldKeywordContext` (Roslyn's `ResetPoint` fields), `errorCount`, `prevTokenMissing`,
  `ternaryCollectionCount`, `scanTypeLastTokenKind`, and the remap log. Not snapshotted, by design:
  `forceConditionalAccessExpression` and `interpolationHoleDepth` (set and restored by `try/finally` at their use
  sites, as Roslyn's `ParserSyntaxContextResetter`), `recursionDepth` (incremented and decremented by `try/finally`
  in the same frame; a reset point is always reset in the frame that took it, so the depth is already back),
  `isScript` (constant), `attributeArgumentAborts` (read right after the element that set it),
  `parenPatternDecisions` (a pure function of the input). `rollbackTo()` discards markers made after the reset point, so a reset
  point must be taken *before* the marker of the node that may be re-parsed (`parsePossiblyAttributedStatement`
  takes it before its statement marker; `ParseStatementCoreRest` LP 8470 re-parses `await …` with `isInAsync` after a
  failed first parse, including the attributes). Remaps (`>>`, `..`, `global`, contextual keywords) survive a rollback:
  a remap log (`staleRemaps`) re-remaps such tokens to `IdentifierToken` when they are reached again.
- **Speculation** (`speculate { }`, `withResetPoint { rp -> }`) is Roslyn's `using var resetPoint = GetDisposableResetPoint`.
  The scans (`ScanType`, `ScanParenthesizedLambda` 12775–12890, `ScanCast`, `ScanPossibleTypeArgumentList`,
  `ScanDesignation`) are ported as pure lookahead over the token window where Roslyn's are; the ones Roslyn implements
  as full parses (12440, 7796, 12875, 13148) stay full parses under a rollback.
- **Interpolated strings in the window.** Our lexer splits Roslyn's single `InterpolatedStringToken` into its parts;
  the token window merges them back into one `Tok` (`Tok.lastStep`: the literal's extent as `CSharpLiteralScanner`
  scans it), so skipping, scans and `PeekToken` see one token as Roslyn does (a skipped `$"x {d}"` is one skipped
  token, not a hole whose `d` starts a member). Only `ParseInterpolatedStringToken` splits it
  (`splitInterpolatedStringAt`), and its parts end at that extent: an unterminated `$"abc` ends at the line break,
  and a `{` on the next line is not a hole (missing end token).
- **`GetLastToken()` and the previous token**: `lastTokenOf(node)` and `previousToken()` look back from the node's end
  over trivia; `ParseVariableDeclarator`'s new-line check reads `parentType.GetLastToken().TrailingTrivia` (the type's
  last token, not the comma before the declarator).
- **Nodes**: `open()` returns the marker; `done(kind)` returns a `Node` carrying what Roslyn callers read off the green
  node (`kind`, `isMissing`, `containsErrors`, `containsErrorDiagnostic`, `lastTokenMissing`, `isNameShaped`, `inner`,
  `left`, `returnType`, `identifierContextualKind`). `containsErrors` is `ContainsDiagnostics` (every error counted
  while the node was open); `containsErrorDiagnostic` is `ContainsErrorDiagnostic` (LP 14726), which walks
  `ChildNodesAndTokens` and so does not see the errors of skipped tokens (trivia): `SyntaxParser.skippedErrorCount`
  counts those (`countSkippedError`: `skipTokens`, `skipAsError`, the `SkipBad*` loops, `ConsumeUnexpectedTokens`).
  Its five callers use it: `IsNoneOrIncompleteMember`, `looksLikePropertyType`, `looksLikeVariableInitializer` (a
  lambda initializer `F(this T x) => x` whose `this` is skipped is still an initializer), the attributed
  collection-expression check (LP 8330) and the `try` block (LP 9459); a reparseable body of a local or anonymous
  function keeps both facts in its key (`BodyContext.hadErrors`, `hadErrorDiagnostic`). A missing `IdentifierName`
  (`CreateMissingIdentifierName`) is name-shaped, so `ConvertExpressionToType` accepts it (`x is` + `if`); hard spot 7.2 of `docs/csharp-psi/PORTING_MAP.md` (`TryParseLambdaExpression` 13899,
  `ParsePrimaryPattern` LPP 237, `ParseTypeArgument` 6785, `ParseStatementCoreRest` 8470, LP 10062, LPP 640,
  `ParseArrayOrObjectCreationExpression` 13454) is served from these fields. `Node.wrap(kind)` = `precede()` + `done`:
  all left-associative bottom-up builds (binary and assignment operators, postfix and conditional access, type suffixes
  `?`/`*`/`[]`, qualified names, `OrPattern`/`AndPattern`) and the wrappers Roslyn makes around an already built node
  (`ScopedType`, `RefType`, `VariableDeclaration`, `NotPattern` over `!=`).

### Hard spots and how they were mapped (docs/csharp-psi/PORTING_MAP.md §7)

1. Forward reset, `consumeConditionalExpression` LP 11711–11742: Roslyn restores the *first* result after trying the
   second interpretation. A done marker cannot be restored after a rollback, so the first parse is repeated: three
   parses in the worst case (`a ? b?[c]` with no `:` after the retry). `containsTernaryCollectionToReinterpret` 11769
   walks the when-true subtree for a conditional whose when-true starts with `[` → every conditional increments
   `ternaryCollectionCount` *when it is built*; a change of the counter across the when-true parse means a nested one
   was built there. The conditional itself is counted after its own check, so `a ? [1]` at EOF does not retry
   (`SliceReviewRegressionTest`, counter `conditionalReparses`).
2. Decisions on built nodes → `Node` fields (above).
3. Pattern ↔ type ↔ expression re-tagging (LPP 21–31, 33–51, 479–498, 366, 330–339, 534–541, 294, 28/474): a done
   marker cannot change kind, so every conversion is a **reset and reparse with another entry**: a name-shaped pattern
   after `is` is reparsed as a type (`parseTypeOrPatternForIsOperator`; `MemberAccess` → `QualifiedName`); a type
   that is convertible to an expression and is followed by an operator is reparsed with
   `parseQualifiedName(asExpression = true)` (emits `SimpleMemberAccessExpression`) and continued with
   `parseExpressionContinued`; `(x)` in a pattern is reparsed as `ParenthesizedExpression` inside `ConstantPattern`;
   a subpattern's `name:` is decided by lookahead and reparsed as `NameColon`/`ExpressionColon`; `var` as a pattern
   is reparsed with the identifier remapped to `VarKeyword` (`eatCurrentAs`); `DiscardPattern`/`TypePattern` of a
   bare name are reparsed as expressions when the pattern turns out to be an expression. Cost: one extra parse of a
   name or small type — except parenthesized patterns (LPP 299–340): `(` … `)` is parsed as a positional pattern first
   and, when it holds a single plain subpattern, reparsed as `(pattern)` or `(expression)`; nested, that doubled per
   level (2^depth). The decision is memoised by the raw position of the `(` (`LanguageParser.parenPatternDecisions`),
   so an outer reparse takes the direct path for the inner levels: O(depth²) token steps in total, 120 nested
   parentheses in under 100 ms (`DeepNestingTest`).
4. Nodes reused in a new parent: the first `IdentifierName` of a tuple element becoming `NameColon` (12930–12937,
   12956) → `precede()` around the done `IdentifierName`; `ParseSimpleName` 6243–6267 (`IdentifierName` vs
   `GenericName` after scanning `<…>`) → one marker, `done()` with the kind decided late.
5. Left-associative builds → `precede()` (above).
6. Retroactive replacement: `ParseArrayRankSpecifier` 7969–7976 (sizes become `OmittedArraySizeExpression` when one
   is omitted) → reparse with the right element kind; `::` with a non-identifier left (7139, 7159, 12224) → the
   identifier is kept and the `::` + name are skipped into an error element after a missing `.`; `global` is remapped
   to `GlobalKeyword` when `::` follows (7146) through `remapCurrentToken` *before* eating it.
7. Parsed subtrees → skipped trivia: `skipAsError` (above).
8. Diagnostics: `errorCount` and the descriptions of error elements (above).
9. Interpolated strings (LPI 20, 120): the lexer already emits the parts (section above), so there is no nested lexer.
   Roslyn's nested *parser* over the hole text ends before the format `:`; here the token window reports the end of
   the hole's expression (below; a `:` with nothing open that is followed by the format text or the hole's `}`) as
   `EndOfFileToken`, so
   `a ? b : d` inside `{}` is a conditional with a missing colon and operand, as in Roslyn (`CS8361`), and the format
   clause is `: d`. Alignment `,` is a normal token inside the hole, as in Roslyn.
   The end of the hole's text follows `Lexer.ScanInterpolatedStringLiteralHoleBalancedText`
   (`interpolationHoleEndStep`, LanguageParser_InterpolatedString.kt): brackets opened in the hole are balanced, a
   closing bracket that does not match the innermost open one belongs to the hole, and the hole ends at a `}` with
   nothing open, a format `:` with nothing open, or the string's next part (then the `}` is missing). The token window
   reports that end as `EndOfFileToken` (`SyntaxParser.interpolationHoleEnd`, only for the innermost hole: a format
   `:` of a hole in a string nested in another hole is no end for the outer one, `reg_interpolation_nested_format.cs`,
   runtime's `StressClient.cs`); tokens left before it are skipped, as
   the rest of the nested parser's text. So in `$"{D(.E}"` the `}` is skipped inside the argument list and the hole's
   `}` and the `)` are missing (ParsingTests' `MismatchedInterpolatedStringContents_01/_02`). The nested parser starts
   with no terminator state and outside a query (`ParserSyntaxContextResetter`, LPI 508): `parseInterpolation` saves
   and clears `termState` and `isInQuery`.
10. Speculative full parses: fine; quadratic in pathological nesting, as in Roslyn.

Other departures and approximations:

- `parseWhenNotNull` (LP 12447): Roslyn eats trailing `!`s and rolls them back when no access follows; a rollback
  cannot undo the `precede()` wrappers, so the `!`s are eaten only when lookahead shows a dependent access, a
  conditional access or an assignment after them; otherwise they belong to the enclosing postfix loop.
- `IsFeatureEnabled` reads the file's language version (section "Language version"). `IsScript` is a constant `false`
  field (`isScript`), kept where Roslyn reads it (`IsPossibleAwaitExpressionStatement` LP 11435).
- `immediatelyAbort` of `ParseAttributeArgumentList` (LP 1246) is ported without token diagnostics: an argument aborts
  the list when it is a regular string literal with no closing quote on its line (Roslyn: `ERR_NewlineInConst` on the
  token) or an interpolated string whose end token is missing (`invalid_attr_unterminated_*.expr`).
- `tokenBreaksTypeArgumentList` (LP 6677) tests `GetContextualKeywordKind(token.ValueText)`: `@or` and `@and` break the
  list too (`contextualKindOfValueText`, `invalid_generic_at_or.expr`).
- `SkipBadTokensWithExpectedKind` (LP 4621): one error element and one counted error for a run of skipped tokens (the
  "expected X" of the first token; the others carry no diagnostic in Roslyn either).
- **Depth guard** (departure): Roslyn guards only real stack exhaustion (`StackGuard.EnsureSufficientExecutionStack`
  in `ParseSubExpression` LP 11500 and `ParseStatementCore`) and then gives up on the whole file
  (`ParseWithStackGuard`: a compilation unit of skipped tokens, `ERR_InsufficientStack`). The JVM cannot measure the
  remaining stack, so `SyntaxParser.MAX_DEPTH` (600) limits a *weighted* depth: `parseSubExpression`,
  `parseStatementCore`, `scanType`, `scanDesignation`, a query continuation (`into`) add 1, `parseLambdaExpression` and
  `parseCastOrParenExpressionOrTuple` one more, `parsePattern` 4 (the
  frames a level takes, measured by `DeepNestingTest.testReportStackHeadroom` on the default 1 MB stack: parenthesized
  patterns overflow at 250–500 levels, lambdas and parentheses at 500–750, blocks at 1250–1500). Past the limit the
  operand is a missing name with an error, the rest of the construct is skipped (a statement: up to its `;`, but not
  past a `}` that closes an enclosing block; a query continuation: an empty `QueryBody` with an error). Below it legal code parses as Roslyn
  does: 450-term `?:` / `??` / `=` chains, 550 prefix operators, 250 nested parentheses or lambdas, 120 nested
  parenthesized patterns, 500 nested blocks. A 1 MB .NET stack lets Roslyn go roughly twice as deep, so the limit is
  a departure for absurd nesting only. Second line: `SyntaxParser.parseWithStackGuard` catches a real
  `StackOverflowError` (deeper call stack than measured), rolls back to the root and makes the whole text one error
  element, as Roslyn (`testRealOverflowDegradesLikeRoslyn` on a 96 KB thread).
- Nothing is out of scope any more: local functions (`TryParseLocalFunctionStatementBody` LP 10985) are parsed,
  including those starting with `unsafe (` (LP 8541, above). The step-0 machinery for unported constructs (the
  parser's `outOfScope` flag, `parseOutOfScopeStatement`, `SliceGate.outOfScopeKinds` and the gates'
  `scopeDisagreements` / `outOfScopeFiles` metrics) is removed: every snippet is diffed against the oracle.
- `ParseStatementCoreRest` LP 8470 returning `null` (`ParseLocalDeclarationStatement` LP 10605: no attributes, an
  accessibility modifier first, no local function) is kept, and the statement marker is dropped with the reset; with
  attributes the declaration is a `LocalDeclarationStatement` (`if (a) [A] public int x;`); Roslyn's caller then parses a member declaration (step 5).

### Statements and queries (step 5)

Every statement of `ParseStatementCore` (LP 8346) is ported in `LanguageParser_Statements.kt`, queries (LP 14101-14421)
in `LanguageParser_Query.kt`. Line map: `docs/csharp-psi/PORTING_MAP.md` §3. Departures and approximations, by Roslyn method:

- `ParseIfStatement` (LP 10077) parses an `else if` chain with an explicit stack; here the `IfStatement` and
  `ElseClause` markers of the chain stay open and are completed innermost first, so a chain costs no recursion
  (`DeepNestingTest`: 700 links).
- `ParseSwitchStatement`, local `parseSwitchHeader` (LP 10244): a `ParenthesizedExpression` governing expression is
  taken apart (its parentheses become the statement's). A done marker cannot be removed, so the expression is parsed
  under a reset point and, when it is a `ParenthesizedExpression`, reparsed as `(` + `ParseExpressionOrDeclaration
  (FirstElementOfPossibleTupleLiteral)` + `ParseErrantExpressionWhenNoCloseParenToken` + `)`, the path of
  `ParseCastOrParenExpressionOrTuple` (LP 12918-12943) that built it. Cost: the governing expression is parsed twice;
  nested switch statements in a lambda in the governing expression would double per level, so the decision is
  memoised by raw position and context (`LanguageParser.switchHeaderDecisions`, as `parenPatternDecisions`): O(n²).
- `ParseSwitchSection` (LP 10300) with `ParseExpressionOrPatternForSwitchStatement` (LPP 459) and
  `ConvertPatternToExpressionIfPossible` (LPP 468), in `LanguageParser_Patterns.kt`: the label is parsed as a pattern;
  a `ConstantPattern` or `DiscardPattern` is reparsed as the bare expression (`exprOnly` of `parsePrimaryPattern`,
  with the `==` that `ParseNegatedPattern` skips) for a `CaseSwitchLabel`, or kept as `ConstantPattern` when `when`
  follows (a discard becomes `ConstantPattern(IdentifierName(_))`, as Roslyn's `node = ConstantPattern(ex)`). A
  `TypePattern` never converts here: with `permitTypeArguments: false` the port builds a `TypePattern` only for types
  `ConvertTypeToExpression` rejects. The outcome (pattern, expression, expression + `when`) is memoised by raw position
  (`LanguageParser.caseLabelDecisions`): without it a lambda with switch statements in a label doubled per level.
- `ParseExpressionStatementOrLocalFunctionStartingWithUnsafe` (LP 8541): Roslyn parses a local declaration once and
  resets only when it is not a `LocalFunctionStatement`. PsiBuilder cannot keep a speculative result, so the port
  parses the local function (body included) under a reset point to decide, then parses it again for real. Nested
  local functions starting with `unsafe (` doubled per level (depth 18: 0.8 s, depth 24: 23 s); the decision is
  memoised by raw position and context (`LanguageParser.unsafeLocalFunctionDecisions`, as `switchHeaderDecisions`),
  so the real parse of an outer level takes the inner decisions from the memo: depth 60 in a few ms (`DeepNestingTest`).
- `ParseForEachStatement` (LP 9792): Roslyn parses the variable with `ParseExpressionOrDeclaration` and takes a
  `DeclarationExpression` with a non-parenthesized designation apart (type and identifier go to `ForEachStatement`,
  LP 9831). Here the decision is made after the type: a declaration marker is opened before the type and completed as
  `DeclarationExpression` when `(` (a parenthesized designation) follows, dropped otherwise; a discard designation is
  left an `IdentifierToken` (Roslyn reverts the `UnderscoreToken`). `for (T x in` (`ParseForOrForEachStatement` LP 9617,
  a scan under a reset point) is a `foreach` whose missing keyword precedes the skipped `for` (`eatTokenAsKind`).
- `ParseForStatement` (LP 9650): ported with Roslyn's quirk that the embedded statement is parsed while
  `IsEndOfForStatementArgument` is still set. `eatUnexpectedTokensAndCloseParenToken` (LP 9749): the run of stray
  `;`/`,` before `)` is one error element with one counted error per token. `VariableFlags.ForStatement` (the comma
  rule of `ParseVariableDeclarators`, LP 5372) and `stopOnCloseParen` are parameters of `parseVariableDeclarators`.
- `ParseUsingExpression` (LP 10414): the `name? id = expr :` check reads `NullableTypeSyntax.ElementType` from
  `Node.inner`, which `parseTypeCore` now sets on `NullableType`; the declarator count comes from
  `parseVariableDeclarators`. All branches reset to the reset point taken after `(`, as Roslyn's.
- `ParseTryStatement` (LP 9412): the fully missing blocks (misplaced `catch`/`finally`, the synthesized `finally { }`)
  are empty `Block` nodes without an error element (Roslyn's `MissingToken`s carry no diagnostic); the missing `try`
  is a counted zero-width error; `ERR_ExpectedEndTry` is `errorCount++` only. `ParseCatchClause` (LP 9488): the early
  `catch (...) if (...)` filter is a missing `when` followed by the skipped `if`.
- `CreateMissingIdentifierName` (LP 6037) has no diagnostic in Roslyn: `createMissingIdentifierNameWithoutError` is an
  empty `IdentifierName` (`case` without `switch`, a query body without `select`/`group`); the slice's
  `createMissingIdentifierName(message)` (with an error) stays for the callers that report one.
- `ParseStatements` (LP 9205): skipped tokens hang on the previous node in Roslyn (the open brace, the last statement,
  the last switch label); here they are an error element in place, between the statements. Spans agree because skipped
  tokens never extend a span on either side.
- Diagnostics on built nodes or tokens (`ERR_BadForeachDecl`, `ERR_EmptyYield`, `ERR_SwitchGoverningExpressionRequiresParens`,
  `ERR_ExpectedEndTry`, the modifiers of local declarations) are `errorCount++` only, as in the slice. Modifiers:
  `ParseLocalDeclarationStatementModifiers` (LP 10885) counts `ERR_NoModifiersOnUsing` on every modifier of a using
  declaration and `ERR_BadMemberFlag` on `readonly`/`volatile`; `ParseLocalDeclarationStatement` (LP 10619) counts
  `ERR_BadMemberFlag` on the local-function-only modifiers of a local. The count matters: it drives the `await` retry
  of `ParseStatementCoreRest` (`{ await using readonly R r = x; }` is an `await` expression statement and a local).
- `ParseQueryExpression` (LP 14200): `WRN_PrecedenceInversion` on a query below assignment precedence is a warning,
  not counted. `ParseFromClause` (LP 14259): a keyword or literal in the name's place (`ConvertToMissingWithTrailingTrivia`)
  is a missing identifier followed by the skipped token. `ParseOrderByClause` (LP 14333): the skip branch of Roslyn's
  loop is unreachable (the loop runs only on a comma) and not ported. A statement starting with a query
  (`TryParseStatementStartingWithIdentifier` LP 8491) parses the query alone, without `ParseExpressionContinued`, as
  Roslyn's `ParseExpressionStatement(attributes, ParseQueryExpression(0))`.
- `ParseEmbeddedStatement` (LP 9352): the `IsScript` branch (a missing `;` error on an embedded expression statement) is
  not ported (`isScript` is false). A `null` statement (accessibility modifier, the reset of `ParseStatementCoreRest`)
  becomes an `EmptyStatement` with a missing `;`; the reset here goes back before the attributes, Roslyn's after them
  (unchanged from the slice; the member-declaration fallback of the caller is part of the member work).

### What the gates compare, and what they do not

`SliceGate` (both tests) diffs every snippet (the out-of-scope bucket of the step-0 slice is gone):
- Valid snippets (no `D` record): every node and token by kind and span, and **no error element and no counted
  error** in our tree (class `spurious error`): a zero-width missing token has no counterpart in the oracle's tree
  after normalisation and would otherwise pass unnoticed.
- Invalid snippets: nodes and tokens by kind and span under these normalisations — Roslyn's missing tokens are
  dropped; our zero-width elements are placed with Roslyn's rule (`PsiToDump.zeroWidthPosition`: after the previous
  token's trailing trivia up to the first line break; right after the previous part inside an interpolated string);
  non-empty error elements (skipped tokens) do not extend spans; tokens inside the oracle's `SkippedTokensTrivia` are
  stripped from our tree. **Not checked**: which tokens are missing and exactly where, how many diagnostics there
  are, which tokens are skipped (only that the surviving structure agrees). The informational metrics
  `invalidErrorElements` (ours) against `invalidOracleDiagnostics` and `invalidOracleSkipped` give a rough measure.

### Where PsiBuilder was insufficient

Nowhere, so far: every Roslyn construction that mutates or re-parents built nodes mapped onto `precede()`, a delayed
`done()`, or a reset-and-reparse of a bounded prefix. The costs are the extra parses in 1, 3 and 6 and the ordering
discipline for missing/skipped tokens. The only thing with no PsiBuilder counterpart is the *position* of a missing
token (after the previous token's trailing trivia): the PSI has the zero-width element at the next token, and the
mapping in tests compensates.

## Declarations (step 5): compilation unit, namespaces, types, members, local functions

Code: `LanguageParser_Declarations.kt` (compilation unit, namespaces, usings, extern aliases, global attributes,
modifiers, type declarations, base lists, constraints, enums, delegates, type parameters) and
`LanguageParser_Members.kt` (`ParseMemberDeclarationOrStatement`, `ParseMemberDeclaration`, members, accessors, field
and event declarators, `TryParseLocalFunctionStatementBody`). Line map: docs/csharp-psi/PORTING_MAP.md §9. `IsScript` is false and
the script branches are not ported; the language-version checks (`record`, `union`, `extension`, `file`, `required`,
partial constructors, ...) are listed in section "Language version".

### The file element

`CSharpParserDefinition.createParser` marks the file element and, inside it, one `CompilationUnit` composite
(`CSharpParserDefinition.parseCompilationUnit`, under `parseWithStackGuard`): the file element is IntelliJ's, the
`CompilationUnit` is Roslyn's root node. Leading trivia stays inside the file element with PsiBuilder's default edge
binders; `PsiToDump` unwraps the file element and maps `CompilationUnit` like any node, ending it at the end of the
text (Roslyn's `EndOfFileToken`). A unit with no token at all (only trivia) is zero-width at the end of the text, as
Roslyn's lone `EndOfFileToken`. Every composite gets the PSI class generated for its kind (section "PSI");
`CSharpFile.compilationUnit` is the root node.

### Replay instead of forward resets

`ParseMemberDeclarationOrStatementCore` (LP 2605) and `ParseMemberDeclarationCore` (LP 3254) keep reset points after
the attributes, after the modifiers, after the type and after the member name, and reset *forward* to them after a
failed attempt (a local declaration, a local function, a statement). A PsiBuilder rollback drops every marker made
after the point, and the attributes, modifiers and type are children of the member marker, so the port keeps one reset
point before the attributes (`MemberContext.start`) and replays the deterministic prefix: attributes, modifiers (with
the `async` modifier added when the type was reconsidered, `ReconsiderTypeAsAsyncModifier` LP 3422), the return type,
and `ParseMemberName` where Roslyn resets to after it. The replay sees the same tokens and the same state, so it builds
the same nodes; the cost is a bounded number of extra parses of a member's prefix (and, for the statement and local
function attempts at the top level, of its body). `DeclarationNestingTest.testManyTopLevelMembersAreLinear` guards it.

Roslyn's `Reset` also restores `_termState`: the `_termState |= IsPossibleStatementStartOrStop` before the field
attempt (LP 2835) is undone by the reset that follows it, so it is not applied there; it stays in effect for the
statement attempts (LP 2784 and `tryParseStatement` LP 2961), where the port sets it around the statement parse. Statement and
local declaration attempts run with `isInAsync = true` (top-level code is async).

`isAcceptableNonDeclarationStatement` (LP 2997) inspects the built statement: an expression statement made of a lone
identifier with a missing `;` is rejected from the kind of the statement node, its `lastTokenMissing`, and the
position of the previous token.

### Moving members into the preceding type (`ParseNamespaceBody`, LP 410)

Roslyn parses the namespace body, then, if a type-only member (method, field, property, ...) follows a type
declaration that ended cleanly (a real `}` without diagnostics and no `;`), rebuilds the type with those members moved
inside (a misplaced `}` closed it too early). PsiBuilder cannot re-parent built nodes, so the port parses the body
twice when the first pass saw such a member: the first pass records the members (kind, whether the type ended cleanly),
plans the runs to move, rolls back to the start of the body and parses it again. In the second pass the type
declaration that starts a run is told (`absorbNextTypeDeclaration`) to skip its own `}` as an error element (the
`}` becomes skipped text, as Roslyn's `moveSiblingMembersIntoPrecedingType` turns it into a missing `}` with the real
one skipped) and to leave its marker open (`AbsorbedType`); the run's members are parsed inside it, and the marker is
completed before the next member with a zero-width missing `}` (`doneBefore`). For the last run of the body the type
takes the namespace's `}` (or a missing one) after the loop. A type is "clean" only when no namespace text was skipped
after it (`markLastDirty`), which is what `CloseBraceToken.ContainsDiagnostics` means after Roslyn attached skipped
text to it. Nested namespaces each run their own two passes: invalid code with misplaced members at every level costs
`2^depth` body parses (`testMisplacedMembersInNestedNamespacesAreBounded`, depth 10). Measured in review: synthetic
hoisted members at every namespace level, depth 8, 1.2 s on 176 KB; realistic edits of a 668 KB file 46–151 ms. Not
memoised: only invalid code pays, and only per level that has a misplaced member.

`absorbNextTypeDeclaration` and `absorbedTypeDeclaration` are not in the reset point snapshot: they are set by the
second pass right before the member that starts a run and consumed by `parseMainTypeDeclaration`, and no reset point
taken before that type declaration is reset after it has been parsed (a type declaration is never parsed
speculatively: `parseMemberDeclarationOrStatementCore` returns it directly; the pending-incomplete-member reset only
spans `IncompleteMember`s; the rollback between the two passes happens before either is set). A new rollback over a
parsed type declaration must add them to the snapshot.

Pending incomplete members (`AddIncompleteMembers`/`ReduceIncompleteMembers` LP 848) are a run of attribute-and-modifier
members that Roslyn may merge into the next member: the port keeps a reset point before the first of them and, when they
must be folded, resets and skips to the end of the run.

### Member names, explicit interfaces, type parameter lists

- `AccumulateExplicitInterfaceName` (LP 6958) decides what a `::` separator becomes only after seeing what follows
  it. The port decides before eating it, by a speculative look at the next part: a final `::`, a `::` after anything
  but an identifier name, and any non-first `::` become a missing `.` with the `::` skipped
  (`ConvertToMissingWithTrailingTrivia`); a first `::` after an identifier stays a real `::` of an
  `AliasQualifiedName`, `global` before it becoming `GlobalKeyword`. The `ExplicitInterfaceSpecifier` is completed
  right after the last separator.
- The CS0071 recovery of `ParseMemberName` (an event `I.` + line break + a name not followed by `{`/`;`): Roslyn parses
  the identifier and resets to before it; the port checks the same condition speculatively and does not parse it.
  Whether a `.` ends a line is asked while it is the current token (raw steps of a `Tok` are relative to the current
  token).
- A type parameter list after the name of a property, indexer or event is skipped syntax in Roslyn
  (`AddTrailingSkippedSyntax`, ERR_UnexpectedGenericName), decided after the member kind is known. The port parses the
  list speculatively, decides from what follows it (`this`, an event, or the start of a property body), then parses it
  for real either as a node or inside an error element.
- For an event, `this` becomes a missing identifier with `this` skipped (`ConvertToMissingWithTrailingTrivia`).

### Other mappings

- `scoped` as a member modifier: `ParseModifiers` opens a marker at `scoped`; `ParseNormalFieldDeclaration` completes it
  as the `ScopedType` around the type, every other declaration drops it.
- A conversion operator after a type (`int implicit operator ...`, ERR_BadOperatorSyntax in `ParseOperatorDeclaration`):
  the port replays the member from its start and skips everything up to `implicit`/`explicit`, then parses the
  conversion operator. Roslyn's tree for `int I.implicit operator` and `int I.explicit operator(` **drops** the `I.`
  tokens (the tree's text is shorter than the source); the port keeps them as skipped text, so these inputs cannot be
  compared and are not in the snippets.
- Operator tokens: `>` `>` (`>>`, `>>>`, `>>=`, `>>>=`) are merged by collapsing adjacent tokens with no trivia between
  them, as `ParseOperatorDeclaration` does with `NoTriviaBetween`; a token that is not an overloadable operator is a
  missing `+` with the token skipped.
- `ParseMethodOrAccessorBodyBlock` (LP 9122) is one function (`parseMethodOrAccessorBodyBlock`), a reparseable body
  (section "Reparseable bodies").
- Field and event declarators (`ParseFieldDeclarationVariableDeclarators`, `ParseVariableDeclarator` with `Fixed` /
  `Const` / `LocalOrField` flags) are a second port next to the local declarators of `LanguageParser_Statements.kt`; the
  two are to be merged into one `ParseVariableDeclarator` with flags.
- Diagnostics without tree effect (ERR_BadModifierLocation, ERR_NamespaceUnexpected, ERR_InvalidMemberDecl, ...) are
  counted (`errorCount`), not reported as error elements, as elsewhere in the port.
- A diagnostic added to a built node (`addError`) marks it as containing errors (`ContainsDiagnostics`): `ref` in
  `looksLikeVariableInitializer`'s speculative initializer is an error and no initializer is assumed.
- Trailing trivia never contains a documentation comment (`LexSyntaxTrivia`, isTrailing): `hasTrailingNewline` stops
  at one, and so does the position of a missing token in `PsiToDump`.
- Top-level statements (`ParseMemberDeclarationOrStatementCore`): the attributes are parsed once, under the outer
  terminator state, and `IsPossibleStatementStartOrStop` is added only for the statement after them
  (`parsePossiblyAttributedStatement(statementTerm)`, also re-applied by the `await` retry of `ParseStatementCoreRest`).
  `isAcceptableNonDeclarationStatement` reads the expression statement's expression (`Node.inner`): only a bare
  `IdentifierName` with a missing `;` is rejected, not `await` with a missing operand.
- `looksLikeVariableInitializer`'s `ContainsErrorDiagnostic(name)`: any error counted while the name was parsed (a
  missing name, `await` as a name in an async context, a second type name).
- `ParseTypeOrPatternForIsOperator` with a constant pattern that is a member access on a missing name (`x is .A`):
  `ConvertExpressionToType` makes it `QualifiedName(missing, ., A)`, which a reparse as a type would not produce; the
  port builds that name directly.
- `ParseMemberDeclaration()` (LP 2570, the `roslyndump member` entry): `parseMemberDeclaration(StructDeclaration)`; when
  it returns null Roslyn returns no node and the test harness maps nothing.

### Known differences in the gates

- `ExpressionParsingTests/ParseBigExpression.cs` (valid): 740 nested parentheses go past `SyntaxParser.MAX_DEPTH`
  (a parenthesis level costs 2), so the innermost operand is a missing name. The documented depth departure, now
  visible because `.cs` inputs are gated.
- `ParsingErrorRecoveryTests/MissingNodeWithSkippedTokens1.cs` (invalid): a `#` after a token on its line (`i,(#`)
  is one `BadToken` over `#` and the line break in Roslyn, a misplaced directive (`HashTokenInDirectiveTrivia` and an
  end of line) in our lexer, so the skipped token ends one character earlier. A lexer class (docs: "Lexer"), not
  fixed by the directives merge.
- Inputs with directives and disabled text are diffed with the rest since the lexer evaluates `#if` (0 mismatches in
  the former `preprocessor` bucket: 133 `.cs`, 4 `.member` files).

### Known differences on invalid code

Classes the mutation gate (docs/csharp-psi/TESTING.md, "Mutation gate") still reports, not fixed:
- `namespace` followed only by trivia up to the end of the file (a missing name, `{` and `}`): Roslyn's
  `ConvertToMissingWithTrailingTrivia(EatTokenEvenWithIncorrectKind(OpenBraceToken))` eats the end-of-file token and
  hangs all its leading trivia on the missing `{`, so the missing `}` sits at the end of the file and the namespace
  spans the trivia; ours ends after the keyword's trailing trivia. Positions of missing tokens only.

## Language version

Roslyn's parser reads `CSharpParseOptions.LanguageVersion` in a handful of places (`SyntaxParser.IsFeatureEnabled`,
`CheckFeatureAvailability`, `Lexer.CheckFeatureAvailability`): most language-version errors are reported by the binder,
but some paths of the parser change the tree, and some parser and lexer diagnostics make nodes `ContainsDiagnostics`,
which a few decisions read. The port follows all of them; there is no "every feature enabled" mode any more.

**The option.** `lang/CSharpLanguageVersion.kt`: `CSharpLanguageVersion` mirrors Roslyn's `LanguageVersion` (values
included; `CSharp1` .. `CSharp14`, `Preview`, and the specified-only `Default`, `Latest`, `LatestMajor`, which
`effective()` maps to `CSharp14` as `MapSpecifiedToEffectiveVersion` does at `roslynCommit`); `parse` is
`LanguageVersionFacts.TryParse` (`7.3`, `latest`, `preview`, `default`, `latestMajor`, `iso-1` ...). `CSharpFeature`
lists the `MessageID` features the syntax layer reads with their `RequiredVersion()`.

**Per file** (`CSharpLanguageLevel`), the same way as the `#if` symbols: `KEY` on the `PsiFile`, its `VirtualFile` or
the files it is a copy of (`forFile`, the walk of `CSharpPreprocessorSymbols.forFile`), else `IDE_DEFAULT` =
`Default` (effective C# 14: what the SDK uses for `net10.0`, the framework `CSharpPreprocessorSymbols.IDE_DEFAULT`
assumes; Preview-only syntax — `union`, `closed`, `safe` as modifiers — is not parsed as such by default). The project
model will set it from `LangVersion` and the framework's default (`net4x` → 7.3, `net10.0` → 14). Changing it does not
reparse an open file by itself.

**Into the parser.** `CSharpFileElementType.doParseContents` puts `forFile(file)` on the `PsiBuilder`
(`builder.putUserData(CSharpLanguageLevel.KEY, ...)`); `SyntaxParser`/`LanguageParser` take a `languageVersion`
constructor parameter defaulting to `CSharpLanguageLevel.forBuilder(builder)` (the builder's key, else `IDE_DEFAULT`),
store the effective version in `SyntaxParser.languageVersion` and answer `isFeatureEnabled(CSharpFeature)`. **Every
parse of a part of a file restores the file's version** the same way: a reparseable body takes
`CSharpLanguageLevel.forFile` of its host file (`BodyFileSettings.of`), puts it on its builder and passes it to
`LanguageParser(builder, version)` (section "Reparseable bodies"); otherwise it would parse at `IDE_DEFAULT`, and e.g.
`field` in an accessor would change kind between a full parse and a body reparse below C# 14
(`CSharpBodyReparseTest.testFieldInAccessorFollowsFileLanguageVersion`).
Tests: `CSharpParsingTestCase.createFile` puts `Preview` (the oracle's default) next to the empty `#if` symbols
(`languageVersion` is overridable); `SliceParseHarness.parse(text, mode, version)` puts `version` on its builder.

**Version-dependent paths of the parser** (all of them at `roslynCommit`; LP = LanguageParser.cs line):

| Roslyn method | Feature (version) | Effect when disabled | Port |
|---|---|---|---|
| `ParseModifiers.parseAsModifier` LP 1497 | `FileTypes` (11), `RequiredMembers` (11), `ClosedClasses` (preview), `UnsafeEvolution` (preview, `safe` outside accessors) | the contextual keyword is a modifier only when `ShouldContextualKeywordBeTreatedAsModifier` says so (as `async`), not always | `parseModifiers`, `parseAsModifier(feature)` |
| `ParseModifiers.isStructOrRecordOrUnionKeyword` LP 1514 | `Records` (9), `Unions` (preview) | `ref` before `record`/`union` is not a modifier | `parseModifiers` |
| `IsPartialType` LP 1664 | `Records` (9), `Unions` (preview) | `partial record` / `partial union` is not a partial type | `isPartialType` |
| `IsPartialMember` LP 1700 | `PartialEventsAndConstructors` (14) | `partial C(` is not a partial member (no fall-through to the method scan) | `isPartialMember` |
| `IsTypeDeclarationStart` LP 2483 | `Records` (9), `Unions` (preview) | `record` / `union` do not start a type declaration | `isTypeDeclarationStartExact` (`isTypeDeclarationStart` of the statements is the same function now) |
| `IsExtensionContainerStart` LP 3412 | `Extensions` (14) | `extension` starts an extension block only before `<` (recovery) | `isExtensionContainerStart` |
| `IsCurrentTokenFieldInKeywordContext` LP 6156 | `FieldKeyword` (14) | `field` in an accessor is an `IdentifierName`, not a `FieldExpression` | `isCurrentTokenFieldInKeywordContext` |
| `IsDefiniteScopedModifier` LP 10647 | `SimpleLambdaParameterModifiers` (14) | in a lambda parameter `scoped` is a modifier only by the general scan (`scoped scoped` is type + name) | `isDefiniteScopedModifier` |
| `ParseTypeArgumentList` LP 6609, `CheckFeatureAvailability(open, Generics)` | `Generics` (2) | a diagnostic on `<` | `errorCount++` after `<` (`ContainsDiagnostics` of the list) |
| `Lexer.ScanNumericLiteral` (`CheckFeatureAvailability`) | `BinaryLiteral` (7), `DigitSeparator` (7), `LeadingDigitSeparator` (7.2) | a diagnostic on the token | `SyntaxParser.countLexerFeatureDiagnostics`, below |
| `Lexer.ScanEscapeSequence` | `StringEscapeCharacter` (13, `\e`) | a diagnostic on the token | the same, for string and character literals |

Not ported, because they only choose a diagnostic or report one that no decision reads:
`EatAccessorSemicolon` LP 4775 and `ParseMethodOrAccessorBodyBlock` LP 9137 (`ExpressionBodiedAccessor`: which error
code a missing `;`/`{` gets; the error is there either way); `DocumentationCommentParser` (`forceWarning: true`:
warnings, and doc comments are not parsed yet); `DirectiveParser` `#error version:` (an error on an `#error` directive,
which is an error anyway). `DirectiveParser`'s `Options.Kind` reads (`#r`, `#load`, `#!`, `#:` in scripts and
file-based programs) depend on the source kind, not the version, and stay with `IsScript` (not ported).

**Diagnostics that steer decisions.** `ContainsDiagnostics` is read by `ParseStatementCoreRest` LP 8470 (the `await`
retry), `TryParseLocalFunctionStatementBody` LP 11063 (`forceLocalFunc`), `ReconsideredTypeAsAsyncModifier` LP 3174,
`ParseNamespaceBody` LP 446 (a clean `}`) and a few error-reporting choices. A language-version diagnostic on a token
therefore can change the tree: `await x = 0b1;` is a local declaration at C# 7 and an `await` expression statement at
C# 6 (`testData/parser/langversion/await_retry_lexer_feature.v7.stmt`). Our lexer carries no diagnostics, so
`SyntaxParser.advance` counts the lexer's version diagnostics when it eats a literal (only below C# 13, where any can
occur): a `0b`/`0B` prefix before 7, a `_` before 7 (right after `0x`/`0b`: before 7.2; a misplaced `_` is
`ERR_InvalidNumber` in every version, an error all the same), `\e` in a regular string or character literal before 13.
Departures: `\e` in the text of an interpolated string is not counted (the part's string kind is not at hand there);
the directive diagnostics `#nullable` before C# 8 and `#pragma` before C# 2 (`DirectiveParser`, on trivia, only in an
active branch) are not counted, as no lexer or directive diagnostic is otherwise (a pre-existing simplification of the
port: Roslyn's `ContainsDiagnostics` includes trivia).

**Gates.** `roslyndump --langversion <v>` (all commands; `preview` without it) and `--include <paths|@file>`.
- `LanguageVersionDiffTest` (`test`): `testData/parser/langversion/<name>.v<version>.<ext>`, one snippet per path above,
  each diffed at its version and the one before, both sides at that version; it also checks that Roslyn's parse really
  changes at the boundary and that our parser reports an error wherever Roslyn has one.
- The parsing-test gates (`ParsingTests*CorpusTest`): the inputs the tests wrote for an explicit version or a script
  (the former `scriptOrOldLangVersion` bucket, same selection) are diffed at the test's own version
  (`ParsingTestsOptions.resolve`: `TestOptions.RegularN`, `WithLanguageVersion(LanguageVersion.X)`, the class default
  `options ?? X`; `cond ? A : B` at both; a version held in a variable — a theory parameter — at every effective
  version). Script inputs (`TestOptions.Script`: 183 `.cs`, none in the other modes) are a bucket of their own, diffed
  as regular code at their version, since `IsScript` is not ported. 2026-10-04: expr/stmt 326 inputs, 2812 diffs (146
  inputs at every version), `.member` 169 / 1359 (70), `.cs` 493 regular / 3923 (201) and 183 script: 0 mismatches,
  0 spurious errors, 0 exceptions. With every feature forced on, the same buckets show 24 + 1 / 119 + 54 / 259 + 26
  mismatched diffs (valid + invalid), so the inputs do exercise the paths.
- `RuntimeNetFrameworkCSharp73TreeCorpusTest` (`corpusTest`, `runtime-netfx-cs7.3-tree.json`): the 90 libraries of
  `runtime/src/libraries` whose `src` project targets .NET Framework, both sides at `--langversion 7.3`: 3257 files, 3248
  valid at 7.3, 9 with Roslyn errors; 0 mismatches. With every feature forced on: 144 mismatches (`field` in
  accessors) in 59 valid files, and an extension block parsed at 7.3.

## Reparseable bodies (step 5)

Code: `lang/parser/CSharpBodyBlockType.kt` (`BodyContext`, `BodyFileSettings`, `CSharpBodyBlockType`),
`LanguageParser_Statements.kt` (`parseBodyBlock`, `parseBodyBlockContents`). Roslyn's incremental parser reuses a
`Block` in `ParseMethodOrAccessorBodyBlock` (LP 9122, `IsIncrementalAndFactoryContextMatches`) through its blender;
here the platform's `BlockSupportImpl` reparses one body alone instead. The requirement is exactness: after a body
reparse the file's tree is element for element (types included) the tree a full parse of the new text gives, hence
Roslyn's.

**Which blocks.** Bodies of methods, constructors, destructors, operators, conversion operators, accessors and local
functions (`ParseMethodOrAccessorBodyBlock` LP 9122, reached through `ParseBlockAndExpressionBodiesWithSemicolon` LP
3605 and `ParseAccessorDeclaration` LP 4674), of lambdas (`ParseLambdaBody` LP 13963) and anonymous methods
(`ParseAnonymousMethodExpression` LP 13795). Expression bodies (`=> expr;`) and nested statement blocks are not
reparse roots: an edit there reparses the enclosing body. Not reparseable (plain `Block`): a body inside an
interpolation hole (its tokens come from a queued lexer run), a body whose `}` is missing, a body that was parsed at
the same position in two different contexts during the file's parse (below), an empty body (no statement) with
directive tokens inside, and bodies of contexts beyond `CSharpBodyBlockType.MAX_TYPES` (4096).

**Eager, not lazy.** Bodies are parsed with the file and their nodes are already-parsed `LazyParseableElement`s of an
`IReparseableElementType`. A truly lazy body (collapsed by the outer parse, parsed on first access) needs its extent
without parsing it, but Roslyn's block ends where the parse of the statements ends: at `public` (the enclosing type's
`IsPossibleMemberStartOrStop`), at `namespace`, at a `}` of a skipped construct... A brace-matching extent would change
the trees of invalid code (go-psi accepted such recovery differences; the gates here do not), so the extent comes from
the real parse. Lazy parsing could later be added for the stub path only (step 8: stubs need no body contents; a
lazily expanded body would have to be parsed with its recorded context, which the element type already carries).

**The context.** Everything a block parse reads from the parser state it starts in, audited over `SyntaxParser` and
`LanguageParser` (`BodyContext`, one interned `CSharpBodyBlockType` per context, debug name `Block`; `capture` checks
that the `TerminatorState` bits fit its mask):
- `termState` (Roslyn `_termState`): the terminator predicates of the enclosing constructs stay active inside a body
  and decide recovery and the body's extent (`public int x;` typed into a method body ends the body at `public` and
  makes a field of the type; a constructor body runs with `IsEndOfMethodSignature`);
- `isInAsync` (the `async` modifier of the member, local function, lambda or anonymous method, or top-level code;
  decides `await`), `isInQuery`, `isInFieldKeywordContext` (property accessors: `field`), `forceConditionalAccessExpression`;
- `recursionDepth`, exactly: the depth guard (`MAX_DEPTH`) depends on it, and the bodies nested in a reparsed body must
  get the types (with their own start depths) the full parse gives them. Bucketing it would make those differ.

Not context: `value` and iterators are not parser concepts in Roslyn (`yield` is contextual everywhere in a statement
position); `errorCount`, `ternaryCollectionCount` are only read as differences; `prevTokenMissing` is reset by `{`;
`scanTypeLastTokenKind`, `parameterListHadCleanParameter`, `attributeArgumentAborts`, `lastTupleTypeElementCount` are
written before they are read; type declarations (`absorbNextTypeDeclaration`, `lastCleanTypeDeclaration`) do not occur
in bodies; the remap log starts empty (stale remaps are a rollback artefact, re-remapped when passed); the decision
memos are per parse and keyed by the whole context (below). File-level inputs come from the host file
(`BodyFileSettings`, of the file the `DummyHolder` of a reparse belongs to): the preprocessor symbols
(`CSharpPreprocessorSymbols.forFile`) for the lexer and the language version (`CSharpLanguageLevel.forFile`, on the
builder and passed to `LanguageParser`), the same inputs `CSharpFileElementType.doParseContents` gives the full parse.

*The language version is not in the body type's key.* A body's parse does depend on the version (`field`, `scoped` in
lambdas, `record` as a local type name, version diagnostics counted into `errorCount` and so into the error bit of
error-sensitive keys), but the type does not decide anything by itself: every parse of the body, full or alone, reads
the version of the file it is in, so a reparse takes the decisions the full parse of that file takes. Two files with
different versions may therefore share a body type for the same context without sharing a decision, and the result
bits of the key (`hadErrors`, `hadTernaryCollection`) were computed at that file's version by the full parse and are
re-checked at the same version by the reparse. What the key would add is only a guard against a file whose version
changed after its last full parse; that is the general "changing the version does not reparse the file" item (Open),
not a body matter, and the symbols are handled the same way.

**The proof obligations** of "a body reparse equals the full parse", each with its check:
1. *Same tokens.* The new text lexed alone (initial state, empty directive context) gives the tokens the file's lexer
   gives it, and the file's lexer leaves it in the state it entered it: true when neither the old nor the new text has
   a directive, disabled text or conflict marker (`isReparseable`: the old body's leaves, the new text lexed; the first
   character is `{`, so the line state at the start does not matter, and no queued run is open at the start: bodies in
   interpolation holes are not reparseable). Lexer state 0 is not required at the body's start: the state only
   encodes the directive context and the line position, both irrelevant without directives.
2. *Same extent.* The parse alone in the restored context ends with a real `}` token whose end is exactly the end of
   the text, with nothing (no token, no trivia) between it and the end (`parseBodyBlockContents` returns the end of the
   `}` it eats; `isValidReparse` after `doParseContents`; otherwise the full parse would end the body elsewhere: an
   extra `}`, a member keyword, an unterminated comment or string...). Reaching EOF is not enough: the text's last
   character is `}` too when `} // done`, `} /*` or `}` and a doc comment are typed before the old `}`, which the
   comment then swallows in the parse alone, while the full parse goes on after the new `}` (rejected as "trivia after
   the closing brace"; `CSharpBodyReparseTest.testTriviaAfterTypedBraceFallsBack`). The parse of the body is a function
   of the context and the tokens, so the full parse would parse the same body text the same way.
3. *Nothing outside observes the change.* What an enclosing decision can read of a body besides its extent: whether a
   range containing it has errors (`containsErrors` / `errorCount` deltas: `ParseStatementCoreRest`'s `await` retry LP
   8470, `looksLikeVariableInitializer` LP 5781, the clean parameter of `ParseParameterList` LP 4870 for local functions
   named `await`, `type.ContainsDiagnostics` of types with array ranks, the speculative type parameter list after a member name),
   and whether it contains a conditional whose when-true starts with `[` (`ContainsTernaryCollectionToReinterpret` LP
   11769, which walks into lambdas). Member and accessor bodies are never inside such a range (no decision reads the
   errors of a member or a type declaration), so for them nothing is recorded. Local function and anonymous function
   bodies are *error-sensitive*: their key holds whether their parse had errors and a ternary collection, and a reparse
   must reproduce both (typing an error into a lambda falls back to the enclosing method body).
4. *The same context in every parse of the body.* Speculative parses may parse a body before the final parse
   (top-level statement attempts, `looksLikeVariableInitializer`, collection-or-attribute speculation, the `await`
   retry, replays). A decision taken on a speculative parse in *another* context (other `termState` or `isInAsync`)
   observes that parse, which (1)-(3) do not cover; so a body parsed at one position in two contexts during the file's
   parse is a plain `Block` (`LanguageParser.bodyContexts`, `conflictedBodies`; top-level local functions are the
   usual case). Parses in the same context give the same results.
5. *Memos.* The decision memos (`parenPatternDecisions`, `switchHeaderDecisions`, `caseLabelDecisions`,
   `unsafeLocalFunctionDecisions`) now key by the whole context (`LanguageParser.decisionKey`: position, `termState`,
   `isInAsync`, `isInQuery`, `isInFieldKeywordContext`, `forceConditionalAccessExpression`); before, a decision made in
   one `termState` could be reused in another, so a full parse (a speculative parse first, the memo reused) and a body
   reparse (no memo) could disagree. Gates unchanged by this (they were 0 already).
6. *Stack overflow.* A real `StackOverflowError` in a reparse rejects it; the full parse is then the judge
   (`parseWithStackGuard`). The JVM stack at a reparse differs from the full parse's, so a body at the edge of real
   stack exhaustion is the only departure from exactness, beyond the depth guard that makes it practically unreachable.

The type carries the context, so a change of context (adding `async`) makes the enclosing reparse produce a body of
another type, which the tree diff replaces. Error-sensitive bodies whose error state flips during typing are replaced
the same way by the reparse of the enclosing body.

**Element types.** A reparseable body's node has a `CSharpBodyBlockType` (debug name `Block`, so the dumps and gates see
Roslyn's kind), not `SyntaxKind.Block`; the types are created per context on demand, so no static `TokenSet` lists
them. Code matching blocks by element type (the PSI classes, the formatter, folding, brace matching) must use
`CSharpBodyBlockType.isBlock(type)`, not `=== SyntaxKind.Block` or a `TokenSet` of `SyntaxKind.Block` alone.

**Checks.** `CSharpBodyReparseTest` (platform: document edits and commits, tree equal to a fresh parse with types,
the reparsed node, PSI identity outside it; method, accessor, constructor with initializer, operator, conversion
operator, destructor, local function, lambda, anonymous method, `await` in async method and lambda, `field`,
iterator, query lambda, a typing sequence, fallbacks for directives, unbalanced braces, an unterminated comment and
`public int x;`); the fuzz gates' incremental-equals-full rule on every mutant (docs/csharp-psi/TESTING.md).

**Stubs (step 8).** Bodies are not stubbed (section "Stubs"). Building stubs parses the whole file (bodies included):
measured, the stub walk and serialization add little to the parse (`CSharpStubBenchmark`), so the lazy variant is not
done. It would collapse bodies into chameleons of their context type and parse them only on access, with
`BodyFileSettings` taken from the host file. Light-tree (`ILightLazyParseableElementType`) support is not needed while
bodies are eager.

## Doc comments (step 5): `DocumentationCommentParser`

**Element.** The two doc comment kinds are `CSharpDocCommentElementType`, an `IReparseableElementType`: for the
lexer and PsiBuilder a doc comment is still one comment token (`getCommentTokens`), so the file parse does not touch
its XML; `createNode` makes a `CSharpDocCommentImpl` (`LazyParseablePsiElement` + `PsiComment`, so TODO, spell
checking and comment handling see a comment), parsed on first access by `DocCommentTreeBuilder` over the port in
`lang/doc`: `DocCommentLexer` (the XML modes of `Lexer`: `LexXmlToken` .. `LexXmlWhitespaceAndNewLineTrivia`,
`ScanXmlCrefToken`, `ScanIdentifier_CrefSlowPath`, `TryScanXmlEntity`) and `DocumentationCommentParser` (all of it,
with the token window of `SyntaxParser`). An edit inside the comment reparses only the comment when the new text is
still exactly one token of the same kind (a `/** */` must end with `*/`): `isReparseable`.

**Tree.** Roslyn's structure (`XmlElement`, `XmlElementStartTag`, `XmlName`, `XmlTextAttribute`, `XmlCrefAttribute`,
`XmlNameAttribute`, `XmlText`, `XmlEmptyElement`, `XmlCDataSection`, `XmlComment`, `XmlProcessingInstruction`,
`TypeCref`, `QualifiedCref`, `NameMemberCref`, ..., tokens `XmlTextLiteralToken`, `XmlTextLiteralNewLineToken`, ...)
under the comment element, with these mappings:
- trivia: every XML token has only leading trivia in Roslyn (`Create(info, leading, null, ...)`):
  `DocumentationCommentExteriorTrivia` (`///` with its indentation, the delimiters and leading `*` of `/** */`) is a
  leaf of that kind, `WhitespaceTrivia` and `EndOfLineTrivia` (tag, cref and name modes) are `WHITE_SPACE`. Trivia
  before the first token of a node goes before the node, so node ranges are Roslyn's `Span`s;
- zero-width tokens are empty composites: missing tokens (`SyntaxFactory.MissingToken`) of `CSharpMissingTokenType`
  (`"<Kind> (missing)"`), placed where Roslyn's are (after the previous token, before the next token's trivia);
  `EndOfDocumentationCommentToken` (after its trivia) and `OmittedArraySizeExpressionToken` of their own kind;
- skipped tokens (`AddTrailingSkippedSyntax`: `SkipBadTokens`, the rest of a cref or name value, a non-ASCII quote,
  `unchecked` after `operator`, `readonly` after `in`/`out`) are one `SkippedTokensTrivia` composite per token, as
  `SyntaxParser.AddSkippedSyntax` makes them (their leading trivia separate), after all nodes that end with the token
  they trail (trailing trivia is outside a `Span`);
- lists (`SyntaxList`, separated lists) are flattened, as everywhere.

**Token window** (`SyntaxParser` with the blender, which the doc comment parser always has: `allowModeReset`): a token
is lexed from the end of the previous token in the window with the location (`XmlDocCommentLocation`) that token left;
a mode change (`Mode` setter, `SetMode`, `ResetMode`) drops the window from the current token on; `Reset` restores
position and mode and keeps tokens lexed in another mode. This matters for `IsVerbatimCref` (DCP 517), which looks
ahead in `XmlCharacter` mode and resets: when the value does not start with a quote, the next tokens stay
`XmlCharacter` tokens until the next mode change re-lexes them.

**Departures and decisions.**
- The final new line of a `///` comment (`Lexer.LexXmlDocComment`, `XmlDocCommentStyle.SingleLine`): Roslyn's comment
  ends after it, its last `XmlText` holds an `XmlTextLiteralNewLineToken` (or the tag, cref and name modes have an
  `EndOfLineTrivia`) and `EndOfDocumentationCommentToken` sits at the start of the next line. Ours ends before it, so
  that token, and an `XmlText` of only that new line, are not in our tree, and `EndOfDocumentationCommentToken` is at
  the end of the comment's text. Kept because the comment is a comment token: the new line belongs to the white space
  between it and the next line (indentation, folding, Enter handling, the formatter treat it like a line comment's),
  and a lazily parsed element cannot see past its own text. No parse decision depends on that new line: it is either
  trivia or a text token followed only by the end of the comment. The gates cut Roslyn's structure at our end
  (docs/csharp-psi/TESTING.md, "Doc comments").
- A consequence for missing tokens at the end of a comment whose last line is an empty `///` (`/// <summary>` +
  Enter): in text and attribute text modes that line's new line is a token in Roslyn, its missing tokens (the end tag,
  an end quote) follow it, after the line's `///`; ours have no token to follow and would sit before that `///`. The
  tree builder puts them after it when `EndOfDocumentationCommentToken`'s leading trivia (ours) starts with an exterior,
  i.e. the dropped new line was a token (tag, cref and name modes make it `EndOfLineTrivia`: the missing tokens stay
  before it, as Roslyn's). At the end of a file without a final new line Roslyn's are before that `///`; ours, which
  cannot see what follows the comment, are after it. `testData/parser/doc/EmptyLastLine.cs`.
- Depth guard: Roslyn's `DocumentationCommentParser` recurses per nested element (`ParseXmlElement` ->
  `ParseXmlNodes` -> `ParseXmlNode`), type argument list (`ParseCrefType` <-> `ParseTypeArguments`) and extension
  member cref, without a guard of its own. Ours counts that nesting; past 200 levels it gives up: an element's content
  ends (every open element gets missing end tags, the rest of the comment becomes the `XmlText` of `ParseRemainder`), a
  cref type becomes a missing name and the rest of the cref value is skipped (`ParseCrefAttributeValue`). Left-nested
  forms built by loops (`A.A.A...`, `int***...`) are not limited: the tree builder and `IsMissing` are iterative. A
  `StackOverflowError` that still escapes (a caller deep in the stack already) leaves the comment flat as below,
  without a logged error.
- Roslyn's own process hangs (minutes, gigabytes of memory) on `/// <see cref="A￿B"/>` (U+FFFF, the
  `SlidingTextWindow.InvalidCharacter` sentinel, inside a cref); ours parses it (`DocCommentParsingTest`).
  `RoslynDump.runProcess` has a timeout for that reason (docs/csharp-psi/TESTING.md).
- No diagnostics and no error elements in doc comments. With `DocumentationMode.Parse`, the IDE's mode and the
  oracle's, Roslyn attaches none (the `WithAdditionalDiagnostics` override, DCP 846; the errors of the XML scanners,
  cref errors included, are dropped by `Lexer.Create` inside a doc comment, Lexer.cs 417); the tree still has the
  recovery (missing tokens, skipped tokens). `ERR_OpenEndedComment` of an unterminated `/**` (`LexXmlDocComment`) is
  not reported either, as before: errors outside the parser belong to a later annotator.
- Crefs and name attribute values are parsed by the cref grammar of `DocumentationCommentParser` (`ParseCrefAttributeValue`
  DCP 887, `ParseMemberCref`, `ParseCrefType`, `ParseCrefParameterList`, ..., `ParseNameAttributeValue` DCP 1632), not
  through `LanguageParser` entry points: Roslyn does the same, because the syntax differs (`{`/`}` for `<`/`>`,
  entities, operator and conversion members, `extension(...)` members, type arguments that must be identifiers).
- `XmlCharType` (`IsStartNCNameCharXml4e`, `IsNCNameCharXml4e`) is generated by `roslyndump gen-kinds` from the
  Roslyn assembly (`lang/doc/XmlCharType.kt`): the source file is not in the sparse checkout.
- `ScanIdentifier_FastPath` is not ported: in cref and name values it returns what `ScanIdentifier_CrefSlowPath`
  returns for the inputs it accepts. Keyword lookup is `LexerCache.TryGetKeywordKind` (reserved, then contextual, at
  most 10 characters); `@` and escapes prevent it, name values never have keywords and keep `@` in the value.
- `DocumentationCommentXmlNames`: `AttributeEquals` is ordinal (`CREF` is a text attribute), `ElementEquals` ignores
  case in C# (`<PARAM name>` has a name attribute); both checked by `DocCommentDiffTest`.
- `ParseXmlAttributes` (DCP 323) compares the current token with `SyntaxKind.IdentifierName`, a node kind: the test is
  always true, so a bad token in a tag skips everything up to `>`, `/>`, `<`, `</` or the end, and ends the attributes.
  Ported as is.
- `ScanXmlElementTagToken` (LX 3206) asserts that a new line, white space or `*/` never reaches it (the leading trivia
  takes them) and would make no progress; ours takes one character as a `BadToken` so that the parser always moves.
  Never seen in the gates. More generally the token window (`addNewToken`) accepts zero-width tokens only for
  `EndOfDocumentationCommentToken` and `EndOfFileToken`: any other token that consumed nothing becomes a one-character
  `BadToken` (a text that is not a doc comment of its kind, such as `/***/` built as a delimited one, looped).
- A failure of the doc comment parser is logged and leaves the comment as one `XmlTextLiteralToken` leaf, so the file
  stays usable. Never seen in the gates.

## PSI (step 3)

Typed PSI generated from Roslyn's `Syntax.xml` by `roslyndump gen-psi` (`tools/csharp-psi/roslyndump/GenPsi.cs`, one command,
deterministic: `dotnet tools/csharp-psi/roslyndump/bin/Release/net10.0/RoslynDump.dll gen-psi .corpus/roslyn --out csharp-psi-core`).
Pure syntax: no icons, presentation, names for the UI, settings or texts (the host plugin owns those).

**Files.** Generated (header with the Roslyn file and `roslynCommit`, committed, never edited; `gen-psi` refuses to run when `.corpus/roslyn` is not at the pinned
`roslynCommit` and writes nothing when the model has errors), in
`csharp-psi-core/src/main/kotlin/io/github/dotnetsupport/csharp/lang/psi/`: `CSharpPsi.kt` (interfaces),
`CSharpVisitor.kt`, `impl/CSharpPsiImpl.kt` (implementations, their field shapes, the factory table
`CSharpPsiImplTable`), `impl/CSharpSyntaxKindSets.kt` (the node kinds of every class used as a field type); in the
test sources `lang/psi/CSharpPsiFieldTable.kt` (the accessor table of the gate). Hand-written: `CSharpElement.kt`
(root interface, `accept(CSharpVisitor)`), `impl/CSharpElementImpl.kt` (base class: slot accessors over
`ASTWrapperPsiElement`), `impl/CSharpStubElementImpl.kt` (the same accessors over `StubBasedPsiElementBase`, the base of
the classes of `GenPsi.Stubbed`, section "Stubs"), `impl/CSharpSyntaxShape.kt` (the field model and the matcher below), `impl/CSharpPsiFactory.kt`
(`createElement`, called by `CSharpParserDefinition`).

**Naming.** Interface = `CSharp` + the Roslyn class name without `Syntax` (`MethodDeclarationSyntax` →
`CSharpMethodDeclaration`), in Roslyn's hierarchy (`CSharpMethodDeclaration : CSharpBaseMethodDeclaration :
CSharpMemberDeclaration : CSharpElement`); `CSharpSyntaxNode` is `CSharpElement`, `StructuredTriviaSyntax`
`CSharpStructuredTrivia`. Implementation = interface + `Impl` (`impl` package), one per concrete class, shared by all its
kinds (`CSharpBinaryExpressionImpl` for the 22 binary kinds). Visitor method = `visit` + the name without `CSharp`
(`visitMethodDeclaration`), each delegating to its Roslyn base up to `visitCSharpElement` and `visitElement`. Accessors
are Kotlin properties named after the field in lower camel case (`OperatorToken` → `operatorToken`, Java
`getOperatorToken()`), with two exceptions: `Name` → `nameElement` (PsiElement's `getName(): String?` is taken) and
`Else` → `` `else` `` (a Kotlin keyword; Java `getElse()`). An interface declares a field where Roslyn's class
introduces it, and redeclares it (`override val`) only where a subclass narrows its type.

**Field types.** Node → the interface, nullable (`left: CSharpExpression?`); token → the leaf, `PsiElement?`; list →
`List<T>`; token list (`Modifiers`) → `List<PsiElement>`; separated list → `List<T>` of the elements plus
`<field>Separators: List<PsiElement>`; `SyntaxNodeOrTokenList` (`BadNamespaceMemberDeclaration.Nodes`) →
`List<PsiElement>`. Absent (optional) and missing (recovery) children are null or left out of lists: a missing token
is an empty gap, never returned, and missing separators are not in `Separators`. Fields inside a `<Choice>` are optional;
`<ContextualKind>` counts as a kind (`RecordDeclaration.Keyword` is `RecordKeyword`); an `Override` without kinds keeps
the kinds of the field it overrides. Not generated: the `bool` fields of directives (`IsActive`, `BranchTaken`,
`ConditionValue`: not slots, they need the preprocessor state). Generated but never instantiated: the classes of
directives (directive tokens are leaves of the file's tree) and `CSharpDocumentationCommentTriviaImpl` (see "Doc
comment PSI" below).

**Hand-written overrides** (abstract fields a concrete class does not repeat, Roslyn implements them in partial
classes; `GenPsi.HandWrittenOverrides`): `NameColonSyntax.Expression` = `nameElement` (`NameColonSyntax.cs`),
`ExtensionBlockDeclarationSyntax.Identifier` and `.BaseList` = null (an extension block has neither).

**Assigning children to fields** (`CSharpSyntaxShape.match`). Roslyn's lists are not nodes in our tree, so a node's
children are the concatenation of its slots and are assigned left to right; each accessor runs the assignment over
the node's children once and caches the slots in the element (`CSharpElementImpl.slots`): accessors are O(1) after
the first call (5,000 members asking their class for its name: `PsiSlotsTest`), list accessors return views over the
cached nodes. The cache is dropped in `subtreeChanged()`, which `CompositeElement.subtreeChanged` calls on the PSI of
every ancestor of a change; writes run under the write lock, so two readers can only both compute the same slots.
Children are leaves and composites; whitespace, comments, directive tokens and disabled text are not children; a
non-empty error element (skipped tokens, Roslyn's `SkippedTokensTrivia`) is passed over; a body block
(`CSharpBodyBlockType`) is a `Block`. A `CSharpMissingTokenType` composite is a *gap* of its token kind (reported or
not, see "Missing token"; an unreported one still keeps a later token of its kind out of its field: `for (; )`
with the first `;` missing); a zero-width error element outside one is a diagnostic on the next token
(`EatTokenWithPrejudice`'s `Unexpected ';'` and the like), passed over. Inside a doc comment
`DocumentationCommentExteriorTrivia` and `SkippedTokensTrivia` composites are not children, and an empty composite of
a token kind (`EndOfDocumentationCommentToken`, `OmittedArraySizeExpressionToken`) is a token. Rules:
- token field: a gap of a kind it accepts is its missing token (null, consumed); a gap of another kind belongs to a
  later field and stops the search; otherwise the next token if the field accepts its kind;
- node field: the next node of its kinds (diagnostic gaps passed over); lists: nodes or tokens of their kinds while
  they come;
- separated list: element, separator, element ...; the separator is `,` or, after a `','` gap, `;` (Roslyn's
  `EatTokenEvenWithIncorrectKind(CommaToken)` in `ParseCommaSeparatedSyntaxList(allowSemicolonAsSeparator)`: `for`
  incrementors, initializers, switch expression arms: `{ a = 1; }`); a `','` gap or nothing between two elements is a
  missing separator (`(int x` + a missing tuple element), a `','` gap after the last element the missing trailing one;
  a gap of another kind ends the list (`for (a, )`: the `';'` gap is the first semicolon's);
- a token field or token list without kinds in `Syntax.xml` takes any token that the following fields (up to the first
  required one) cannot start with (`Field.stop`): every `Modifiers` list (stops at `class`, `record`, `get`, the
  identifier of a parameter ...), `InterpolationAlignmentClause.CommaToken`, `InterpolationFormatClause.ColonToken`,
  `AttributeTargetSpecifier.Identifier`, `BadDirectiveTrivia.Identifier`.

`gen-psi` checks the shapes statically: an optional field or a list followed by a field that can start with the same
kind is an *overlap* and needs a decision in `GenPsi.Resolved` (the command fails otherwise, so a new Roslyn shows new
cases). At `roslynCommit` there are two: `LocalDeclarationStatement.AwaitKeyword` and `.UsingKeyword` before
`Modifiers` (taken first; a local's modifiers are never `await` or `using`). No field needed a hand-written accessor.
Gates (docs/csharp-psi/TESTING.md, "PSI accessor gate"): every accessor of every node of Roslyn `src`, runtime, aspnetcore, the
playground and Roslyn's parsing tests, doc comment structure included, returns what Roslyn's field holds.

**Factory.** `CSharpPsiFactory.createElement`: the generated constructor of the node's kind
(`CSharpPsiImplTable.constructors`); `roslynKind(type)` maps element types that are not `SyntaxKind` constants onto
their kind, today the body blocks (`CSharpBodyBlockType.isBlock` → `Block`, so a body is a `CSharpBlockImpl`). A type
without a generated class gets an `ASTWrapperPsiElement`: only the token composites of doc comments (below); no node
of a parsed file (gated).

**Doc comment PSI.** The composites `DocCommentTreeBuilder` makes inside a parsed doc comment go through
`CSharpParserDefinition.createElement` like any other: XML and cref nodes get their generated classes
(`CSharpXmlElementImpl`, `CSharpQualifiedCrefImpl`, ...), a `SkippedTokensTrivia` composite `CSharpSkippedTokensTriviaImpl`.
The doc comment element itself is created by `CSharpDocCommentElementType.createNode`: `CSharpDocCommentImpl`, a
`LazyParseablePsiElement` and `PsiComment` that also implements `CSharpDocumentationCommentTrivia` (`content`,
`endOfComment`, matched with the shape of the generated `CSharpDocumentationCommentTriviaImpl`, which is never
instantiated; an accessor parses the comment). `accept`: a `CSharpVisitor` gets `visitDocumentationCommentTrivia`
(whose default chain is `visitStructuredTrivia`, `visitCSharpElement`, `visitElement`, not `visitComment`), any other
visitor `visitComment`. Missing tokens (`CSharpMissingTokenType`) are null for their accessors, as in the file's
tree; zero-width tokens are returned (`endOfComment` is the empty `EndOfDocumentationCommentToken` composite, an
`ASTWrapperPsiElement`), unlike the file's tree, which has no element for them. Roslyn's final new line of a `///`
comment (an `XmlText` token) is not in our comment ("Doc comments"), so not in `content`.

## Stubs (step 8)

Code: `lang/psi/stubs/` (`CSharpStubs.kt`, `CSharpStubIndexes.kt`), `impl/CSharpStubElementImpl.kt`, registration in
`META-INF/csharp-psi-core.xml`. The platform's registry API (2025.1+: `languageStubDefinition`,
`stubElementRegistryExtension`): the element types stay the `SyntaxKind` constants the parser uses (no
`IStubElementType`, no change to the parser or the gates); a `CSharpStubElementFactory` per kind is registered for them,
and a serializer for the file element type `CSharpParserDefinition.FILE`.

**Which classes are stub-based.** `GenPsi.Stubbed`: the compilation unit, namespaces, types, delegates, extension
blocks, members, enum members, the variable declaration and declarators (fields). Their generated implementations
extend `CSharpStubElementImpl` (`StubBasedPsiElementBase` + `StubBasedPsiElement`, `getIElementType` the kind) and have
a second constructor from a stub (`CSharpPsiImplTable.stubConstructors`); accessors are the same slot accessors, so an
accessor of an element made from a stub loads the AST. `getName` answers from the stub. All other classes stay
`CSharpElementImpl`. A class is stub-based for every node of its kinds; whether a node gets a stub is
`CSharpStubRules.isStubbed`.

**Which nodes get a stub.** A node of a stub-based kind whose parent got one, by kind: the compilation unit under the
file; namespaces under the unit or a namespace; types and delegates there or in a class, struct, interface, record;
extension blocks in those types; members in those types or their extension blocks; enum members in an enum; the
variable declaration of a field, its declarators. So the parent of a stub is always the parent of its node
(`StubBasedPsiElementBase.getParent` answers by the stub: the intermediate nodes — unit, extension block, variable
declaration — are stubbed for that), and nothing inside bodies, attribute arguments or expressions is visited
(`CSharpStubBuilder.skipChildProcessingWhenBuildingStubs`). Not stubbed, as in the host's declaration model: members at
the level of a namespace or the file, local functions and locals, top-level statements.

**What a stub holds** (`CSharpStub`, syntax only, as written): `name` = `CSharpDeclarationNames.name` (null for the
unit, an extension block, a variable declaration, a field with several declarators); modifier bits (`CSharpStubs.MODIFIERS`,
append only) and `EXTENSION` (a method whose first parameter is `this`); arity (type parameters of a type, delegate,
method); the parameter list as written with whitespace collapsed (methods, constructors, destructors, operators,
indexers, delegates, primary constructors); base types as written; the simple names of the attributes of a member
(`Fact` of `[Xunit.Fact]`). `CSharpStubs.VERSION` is bumped on any change of what is stubbed or serialized; the golden
`testData/stubs/stubs.txt` (`CSharpStubTest`) fails while the dump changes under the same version.

**Indexes** (`CSharpStubRules.index`): `csharp.type.name` (types and delegates), `csharp.member.name` (members, a field
by its declarator when it has one, each declarator of a field with several, enum members), `csharp.extension.method`,
`csharp.attribute` (types and methods by attribute name without the `Attribute` suffix). A declaration under a
namespace or type whose name is missing is not indexed (the declaration model drops it with its contents).

**The file's `#if` symbols and version.** The indexer parses the content with the keys of the indexed `VirtualFile`
(`CSharpPreprocessorSymbols.forFile` / `CSharpLanguageLevel.forFile` also look at `PsiFile.getVirtualFile`, which is
the indexed file during indexing), so stubs and the AST agree. When the keys of a file change, the host asks for a
reindex of files that can parse differently (`#if` in the text, a version other than the default).

**In the plugin.** The platform finds the stubs of a language through the file element type of the registered parser
definition: the host's switch (`SYNTAX_TREE`). With the heuristic tree there is no serializer for its file element type,
so C# files have no stubs; switching rebuilds the stub index (`CSharpDeclarationIndex.requestRebuild`).

## Open

- Parser (step 5): merging the field and local declarators.
- Changing a file's preprocessor symbols (`CSharpPreprocessorSymbols.KEY`) does not reparse or rehighlight it by itself.
- Bodies are reparseable but parsed eagerly, for stubs too (section "Reparseable bodies"); lazy bodies only if the
  indexing time asks for them.
- Changing a file's language version (`CSharpLanguageLevel.KEY`) does not reparse it by itself; the version per
  project (`LangVersion`, the framework's default) comes with the project model.
- `#if` symbols per project, TFM and configuration (`DefineConstants`) from the project model; until then the fixed
  `CSharpPreprocessorSymbols.IDE_DEFAULT` or a `KEY` set by hand.
