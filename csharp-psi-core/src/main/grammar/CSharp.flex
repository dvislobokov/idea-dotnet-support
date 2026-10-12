// C# lexer: the tokens Roslyn's lexer (src/Compilers/CSharp/Portable/Parser/Lexer.cs at roslynCommit 35d9211b841e)
// hands to the parser, with trivia as tokens. Departures and their reasons: docs/csharp-psi/GRAMMAR.md, "Lexer vs Roslyn's tokens".
//  - Contextual keywords are identifiers; `>` is never merged (`>>`, `>>=`, `>>>` are the parser's); `..` is two dots.
//  - Interpolated strings are split into the parts the parser builds (LanguageParser_InterpolatedString.cs): the
//    literal is scanned by Roslyn's InterpolatedOrRawStringScanner (CSharpLiteralScanner), holes are lexed by a nested
//    lexer over the hole's expression text, and the parts are returned from a queue in the QUEUE state.
//  - Directives are lexed as Roslyn's DirectiveParser sees them (CSharpPreprocessor): directive tokens, messages and
//    the disabled text of #if branches that are not taken, evaluated with the symbols given to the constructor. The
//    lexer keeps the directive stack and the trivia position on the line (LineState) between tokens; the state it
//    reports is 0 only where both are as at the start of a file. Doc comments are one token each.
// Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
package io.github.dotnetsupport.csharp.lang.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts;
import io.github.dotnetsupport.csharp.lang.SyntaxKind;

%%

%public
%class _CSharpLexer
%implements FlexLexer
%function advanceImpl
%type IElementType
%unicode

// Returning the parts of an interpolated string, of a conflict marker region or of directives with their excluded
// text. Never the state of a token start that IntelliJ restarts from: the queue is filled by the first part, which is
// lexed in YYINITIAL.
%state QUEUE

%{
  public _CSharpLexer() {
    this(java.util.Collections.<String>emptySet());
  }

  /** [symbols]: the preprocessor symbols defined for the file (`#if` evaluation, as Roslyn's `PreprocessorSymbols`). */
  public _CSharpLexer(java.util.Set<String> symbols) {
    this((java.io.Reader) null);
    this.symbols = symbols;
  }

  private java.util.Set<String> symbols = java.util.Collections.emptySet();

  /** Roslyn's `Lexer._directives`. */
  private DirectiveStack directives = DirectiveStack.EMPTY;

  /** Where the next token starts in Roslyn's trivia scan ([LineState]). */
  private int lineState = LineState.CLEAN;

  /** Set by a rule that already put [lineState] after the tokens it queued. */
  private boolean lineStateFixed;

  private final DirectiveTokenSink sink = this::enqueue;

  /** Back to the start of a file: called with [clearQueue] before every (re)start, which is always at state 0. */
  public void resetContext() {
    directives = DirectiveStack.EMPTY;
    lineState = LineState.CLEAN;
  }

  /** The state of the next token start: 0 only where lexing may restart (see [LineState.state]). */
  public int currentState() {
    if (queueIndex < queueSize) return queueState[queueIndex];
    return LineState.state(directives, lineState, false);
  }

  /** `Lexer.LexSyntaxTrivia`: how a token moves `isTrailing` / `onlyWhitespaceOnLine` ([LineState]). */
  private static int nextLineState(int state, IElementType type) {
    if (type == SyntaxKind.EndOfLineTrivia) return state == LineState.DIRTY_DOC ? LineState.DIRTY : LineState.CLEAN;
    if (type == SyntaxKind.WhitespaceTrivia || type == SyntaxKind.ConflictMarkerTrivia) return state;
    if (type == SyntaxKind.SingleLineCommentTrivia || type == SyntaxKind.MultiLineCommentTrivia) {
      return state == LineState.TRAILING ? LineState.TRAILING : LineState.DIRTY;
    }
    // A doc comment ends trailing trivia and leaves onlyWhitespaceOnLine alone; the single-line one takes the new line.
    if (type == SyntaxKind.SingleLineDocumentationCommentTrivia) {
      return state == LineState.TRAILING || state == LineState.CLEAN ? LineState.CLEAN : LineState.DIRTY_DOC;
    }
    if (type == SyntaxKind.MultiLineDocumentationCommentTrivia) return state == LineState.TRAILING ? LineState.CLEAN : state;
    return LineState.TRAILING;
  }

  /** A lexer of an interpolation hole: no directives (`#` is a bad character) and no conflict markers. */
  private boolean inHole;

  private IElementType[] queueType = new IElementType[16];
  private int[] queueStart = new int[16];
  private int[] queueEnd = new int[16];
  private int[] queueState = new int[16];
  private int queueSize;
  private int queueIndex;

  /** Drops queued tokens: called before every (re)start, the generated reset() cannot be extended. */
  public void clearQueue() {
    queueSize = 0;
    queueIndex = 0;
  }

  @Override
  public IElementType advance() throws java.io.IOException {
    if (queueIndex < queueSize) {
      int i = queueIndex++;
      zzStartRead = queueStart[i];
      zzMarkedPos = zzCurrentPos = queueEnd[i];
      if (queueIndex == queueSize) {
        clearQueue();
        yybegin(YYINITIAL);
      }
      return queueType[i];
    }
    lineStateFixed = false;
    IElementType type = advanceImpl();
    if (type != null && !lineStateFixed) lineState = nextLineState(lineState, type);
    lineStateFixed = false;
    return type;
  }

  private void enqueue(IElementType type, int start, int end) {
    enqueue(type, start, end, LineState.state(directives, lineState, true));
  }

  private void enqueue(IElementType type, int start, int end, int state) {
    if (start >= end) return;
    if (queueSize == queueType.length) {
      int n = queueSize * 2;
      queueType = java.util.Arrays.copyOf(queueType, n);
      queueStart = java.util.Arrays.copyOf(queueStart, n);
      queueEnd = java.util.Arrays.copyOf(queueEnd, n);
      queueState = java.util.Arrays.copyOf(queueState, n);
    }
    queueType[queueSize] = type;
    queueStart[queueSize] = start;
    queueEnd[queueSize] = end;
    queueState[queueSize] = state;
    queueSize++;
  }

  /** Returns the first queued token now; the rest come from advance() in the QUEUE state. */
  private IElementType startQueue() {
    queueIndex = 0;
    if (queueSize == 0) {
      // Cannot happen: every queue starts with a non-empty token. Keep the lexer moving.
      zzMarkedPos = Math.min(zzStartRead + 1, zzEndRead);
      return TokenType.BAD_CHARACTER;
    }
    if (queueSize > 1) yybegin(QUEUE);
    try {
      return advance();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private IElementType token(IElementType type, int end) {
    zzMarkedPos = end;
    return type;
  }

  private CSharpLiteralScanner scanner() {
    return new CSharpLiteralScanner(zzBuffer, zzEndRead, zzStartRead);
  }

  private char charAt(int i) {
    return i < zzEndRead ? zzBuffer.charAt(i) : CSharpLiteralScanner.INVALID;
  }

  private int endOfLine(int i) {
    while (i < zzEndRead && !CSharpLiteralScanner.isNewLine(zzBuffer.charAt(i))) i++;
    return i;
  }

  private int newLineWidth(int i) {
    return charAt(i) == '\r' && charAt(i + 1) == '\n' ? 2 : 1;
  }

  /** Identifier or reserved keyword at the token start (`Lexer.ScanIdentifierOrKeyword`); null if there is none. */
  private IElementType identifierOrKeyword() {
    CSharpLiteralScanner s = scanner();
    if (!s.scanIdentifier()) return null;
    zzMarkedPos = s.pos;
    if (!s.identifierIsVerbatimOrEscaped) {
      IElementType keyword = CSharpSyntaxFacts.reservedKeywords.get(yytext().toString());
      if (keyword != null) return keyword;
    }
    return SyntaxKind.IdentifierToken;
  }

  /** ASCII identifier matched by the rule; continues by Roslyn's rules when an escape or a non-ASCII char follows. */
  private IElementType asciiIdentifier() {
    char next = charAt(zzMarkedPos);
    if (next == '\\' || (next > 127 && zzMarkedPos < zzEndRead)) return identifierOrKeyword();
    IElementType keyword = CSharpSyntaxFacts.reservedKeywords.get(yytext().toString());
    return keyword != null ? keyword : SyntaxKind.IdentifierToken;
  }

  /** A bad character (`ScanSyntaxToken`, default): one char, or a surrogate pair. */
  private IElementType badCharacter() {
    int end = zzStartRead + 1;
    if (Character.isHighSurrogate(charAt(zzStartRead)) && end < zzEndRead && Character.isLowSurrogate(zzBuffer.charAt(end))) end++;
    return token(TokenType.BAD_CHARACTER, end);
  }

  /** A non-ASCII character that is not whitespace or a new line: identifier start or a bad character. */
  private IElementType nonAscii() {
    if (CSharpLiteralScanner.isIdentifierStartCharacter(charAt(zzStartRead))) {
      IElementType type = identifierOrKeyword();
      if (type != null) return type;
    }
    return badCharacter();
  }

  /** `\`: an identifier starting with a unicode escape, or a bad token (the whole escape when it is one). */
  private IElementType backslash() {
    CSharpLiteralScanner s = scanner();
    char next = charAt(zzStartRead + 1);
    if (next == 'u' || next == 'U') {
      char escaped = (char) s.scanUnicodeEscape();
      if (CSharpLiteralScanner.isIdentifierStartCharacter(escaped)) {
        IElementType type = identifierOrKeyword();
        if (type != null) return type;
      }
      return token(TokenType.BAD_CHARACTER, s.pos);
    }
    return token(TokenType.BAD_CHARACTER, zzStartRead + 1);
  }

  /** `@`: verbatim string, interpolated string, verbatim identifier, Razor content or a bad `@` sequence. */
  private IElementType at() {
    CSharpLiteralScanner s = scanner();
    int i = zzStartRead;
    while (charAt(i) == '@') i++;
    if (charAt(i) == '"') return token(s.scanVerbatimStringLiteral(), s.pos);
    if (charAt(i) == '$') return interpolatedString();
    IElementType identifier = identifierOrKeyword();
    if (identifier != null) return identifier;
    if (charAt(zzStartRead + 1) == ':') return token(SyntaxKind.RazorContentToken, endOfLine(zzStartRead));
    return token(TokenType.BAD_CHARACTER, i);
  }

  /** `$`: an interpolated string when followed by `$`, `@` or `"`; a bad character otherwise. */
  private IElementType dollar() {
    char next = charAt(zzStartRead + 1);
    if (next == '$' || next == '@' || next == '"') return interpolatedString();
    return token(TokenType.BAD_CHARACTER, zzStartRead + 1);
  }

  private IElementType stringLiteral() {
    CSharpLiteralScanner s = scanner();
    IElementType type = s.scanStringLiteral();
    return token(type, s.pos);
  }

  /**
   * An interpolated string from the token start, split as `LanguageParser.ParseInterpolatedOrRawStringToken` does:
   * start, text, `{`, the hole's tokens (a nested lexer over the expression text), `:` and the format text, `}`,
   * text, end. Empty parts (missing tokens in Roslyn) are left out.
   */
  private IElementType interpolatedString() {
    int start = zzStartRead;
    InterpolatedString literal = scanner().scanInterpolatedStringLiteral();
    clearQueue();
    enqueue(startKind(literal.kind), start, literal.openQuoteEnd);
    int current = literal.openQuoteEnd;
    for (Interpolation hole : literal.interpolations) {
      enqueue(SyntaxKind.InterpolatedStringTextToken, current, hole.openBraceStart);
      enqueue(SyntaxKind.OpenBraceToken, hole.openBraceStart, hole.openBraceEnd);
      int expressionEnd = hole.colon >= 0 ? hole.colon : hole.closeBraceStart;
      lexHole(hole.openBraceEnd, expressionEnd);
      if (hole.colon >= 0) {
        enqueue(SyntaxKind.ColonToken, hole.colon, hole.colon + 1);
        enqueue(SyntaxKind.InterpolatedStringTextToken, hole.colon + 1, hole.closeBraceStart);
      }
      enqueue(SyntaxKind.CloseBraceToken, hole.closeBraceStart, hole.closeBraceEnd);
      current = hole.closeBraceEnd;
    }
    enqueue(SyntaxKind.InterpolatedStringTextToken, current, literal.closeQuoteStart);
    boolean raw = literal.kind == InterpolatedStringKind.SingleLineRaw || literal.kind == InterpolatedStringKind.MultiLineRaw;
    enqueue(raw ? SyntaxKind.InterpolatedRawStringEndToken : SyntaxKind.InterpolatedStringEndToken,
            literal.closeQuoteStart, literal.closeQuoteEnd);
    return startQueue();
  }

  private static IElementType startKind(InterpolatedStringKind kind) {
    switch (kind) {
      case Verbatim: return SyntaxKind.InterpolatedVerbatimStringStartToken;
      case SingleLineRaw: return SyntaxKind.InterpolatedSingleLineRawStringStartToken;
      case MultiLineRaw: return SyntaxKind.InterpolatedMultiLineRawStringStartToken;
      default: return SyntaxKind.InterpolatedStringStartToken;
    }
  }

  /** The expression text of a hole, lexed as Roslyn's parser does (`ParseInterpolation`: a lexer over that text). */
  private void lexHole(int start, int end) {
    if (start >= end) return;
    _CSharpLexer nested = new _CSharpLexer();
    nested.inHole = true;
    nested.reset(zzBuffer, start, end, YYINITIAL);
    try {
      IElementType type;
      while ((type = nested.advance()) != null) {
        enqueue(type, nested.getTokenStart(), nested.getTokenEnd());
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** `//` comment; `///` (not `////`) starts a doc comment over consecutive `///` lines (`Lexer.LexXmlDocComment`). */
  private IElementType lineComment() {
    int start = zzStartRead;
    if (!(charAt(start + 2) == '/' && charAt(start + 3) != '/')) return SyntaxKind.SingleLineCommentTrivia;
    int end = zzMarkedPos;
    while (CSharpLiteralScanner.isNewLine(charAt(end)) && end < zzEndRead) {
      int next = end + newLineWidth(end);
      while (next < zzEndRead && CSharpLiteralScanner.isWhitespace(zzBuffer.charAt(next))) next++;
      if (!(charAt(next) == '/' && charAt(next + 1) == '/' && charAt(next + 2) == '/' && charAt(next + 3) != '/')) break;
      end = endOfLine(next);
    }
    return token(SyntaxKind.SingleLineDocumentationCommentTrivia, end);
  }

  /** `/* ... *&#47;` to the end of input when unterminated; `/**` (not `/**&#47;`, `/***`) is a doc comment. */
  private IElementType blockComment(char delimiter) {
    int start = zzStartRead;
    boolean doc = delimiter == '/' && charAt(start + 2) == '*' && charAt(start + 3) != '*' && charAt(start + 3) != '/';
    int i = start + 2;
    while (i < zzEndRead && !(zzBuffer.charAt(i) == '*' && charAt(i + 1) == delimiter)) i++;
    int end = i < zzEndRead ? i + 2 : zzEndRead;
    return token(doc ? SyntaxKind.MultiLineDocumentationCommentTrivia : SyntaxKind.MultiLineCommentTrivia, end);
  }

  /**
   * `#`: a directive with the excluded text after it (`Lexer.LexDirectiveAndExcludedTrivia`) when only whitespace
   * precedes it in leading trivia, a misplaced directive otherwise (no effect; Roslyn's `ERR_BadDirectivePlacement`,
   * which keeps trailing trivia going); a bad character in a hole (`allowPreprocessorDirectives: false`).
   */
  private IElementType directive() {
    if (inHole) return token(TokenType.BAD_CHARACTER, zzStartRead + 1);
    clearQueue();
    CSharpPreprocessor preprocessor = new CSharpPreprocessor(zzBuffer, zzEndRead, symbols, directives, sink);
    if (lineState == LineState.CLEAN) {
      preprocessor.lexDirectiveAndExcludedTrivia(zzStartRead);
      directives = preprocessor.stack;
    } else {
      preprocessor.lexMisplacedDirective(zzStartRead);
      if (lineState != LineState.TRAILING) lineState = LineState.CLEAN;
    }
    lineStateFixed = true;
    return startQueue();
  }

  /** `Lexer.IsConflictMarkerTrivia` at [position] (the seven characters are checked by the caller's rule). */
  private boolean isConflictMarker(int position) {
    if (inHole) return false;
    if (position != 0 && !CSharpLiteralScanner.isNewLine(charAt(position - 1))) return false;
    char first = charAt(position);
    if (position + 7 > zzEndRead) return false;
    for (int i = 0; i < 7; i++) if (charAt(position + i) != first) return false;
    if (first == '|' || first == '=') return true;
    return position + 7 < zzEndRead && charAt(position + 7) == ' ';
  }

  /** `<<<<<<<`, `|||||||`, `=======` at a line start (`Lexer.LexConflictMarkerTrivia`); operators otherwise. */
  private IElementType conflictMarker(IElementType operator) {
    if (!isConflictMarker(zzStartRead)) {
      yypushback(yylength() - 2);
      return operator;
    }
    clearQueue();
    lexConflictMarker(zzStartRead);
    return startQueue();
  }

  private void lexConflictMarker(int position) {
    char first = charAt(position);
    int headerEnd = endOfLine(position);
    enqueue(SyntaxKind.ConflictMarkerTrivia, position, headerEnd);
    int i = headerEnd;
    while (i < zzEndRead && CSharpLiteralScanner.isNewLine(zzBuffer.charAt(i))) i++;
    enqueue(SyntaxKind.EndOfLineTrivia, headerEnd, i);
    if (first != '|' && first != '=') return;
    boolean atSecondMiddleMarker = first == '=';
    int textStart = i;
    while (i < zzEndRead) {
      char ch = zzBuffer.charAt(i);
      if ((!atSecondMiddleMarker && ch == '=' || ch == '>') && isConflictMarker(i)) {
        enqueue(SyntaxKind.DisabledTextTrivia, textStart, i);
        lexConflictMarker(i);
        return;
      }
      i++;
    }
    enqueue(SyntaxKind.DisabledTextTrivia, textStart, i);
  }

  /** `.5`: a real literal, except after a dot (`..5` is two dots and 5: `ScanSyntaxToken`, case '.'). */
  private IElementType dotNumber() {
    if (zzStartRead >= 1 && zzBuffer.charAt(zzStartRead - 1) == '.') {
      yypushback(yylength() - 1);
      return SyntaxKind.DotToken;
    }
    return SyntaxKind.NumericLiteralToken;
  }
%}

NEW_LINE = \r\n | [\r\n\u0085\u2028\u2029]
NOT_NEW_LINE = [^\r\n\u0085\u2028\u2029]
// SyntaxFacts.IsWhitespace: Zs, tab, vertical tab, form feed, U+FEFF, U+001A.
WHITESPACE = [ \t\u000B\u000C\u001A\u00A0\uFEFF\u1680\u2000-\u200A\u202F\u205F\u3000]

// Lexer.ScanNumericLiteral: '_' anywhere after the first digit; a real needs a digit after the dot; 'e' takes an
// optional sign and any digits (none is an error, not a shorter token).
DEC_DIGITS = [0-9] [0-9_]*
FRACTION = "." [0-9] [0-9_]*
EXPONENT = [eE] [+-]? [0-9_]*
REAL_SUFFIX = [fFdDmM]
LONG_SUFFIX = [lL] [uU]? | [uU] [lL]?
INTEGER = {DEC_DIGITS} ({REAL_SUFFIX} | {LONG_SUFFIX})?
REAL = {DEC_DIGITS} ({FRACTION} {EXPONENT}? | {EXPONENT}) {REAL_SUFFIX}?
HEX = 0 [xX] [0-9a-fA-F_]* {LONG_SUFFIX}?
BINARY = 0 [bB] [01_]* {LONG_SUFFIX}?
DOT_REAL = {FRACTION} {EXPONENT}? {REAL_SUFFIX}?

%%

{WHITESPACE}+             { return SyntaxKind.WhitespaceTrivia; }
{NEW_LINE}                { return SyntaxKind.EndOfLineTrivia; }
"//" {NOT_NEW_LINE}*      { return lineComment(); }
"/*"                      { return blockComment('/'); }
"@*"                      { return blockComment('@'); }
"#"                       { return directive(); }

"<<<<<<<"                 { return conflictMarker(SyntaxKind.LessThanLessThanToken); }
"|||||||"                 { return conflictMarker(SyntaxKind.BarBarToken); }
"======="                 { return conflictMarker(SyntaxKind.EqualsEqualsToken); }

[a-zA-Z_] [a-zA-Z0-9_]*   { return asciiIdentifier(); }
"\\"                      { return backslash(); }
"@"                       { return at(); }
"$"                       { return dollar(); }
\" | "'"                  { return stringLiteral(); }

{INTEGER} | {REAL} | {HEX} | {BINARY} { return SyntaxKind.NumericLiteralToken; }
{DOT_REAL}                { return dotNumber(); }

"~"                       { return SyntaxKind.TildeToken; }
"!"                       { return SyntaxKind.ExclamationToken; }
"!="                      { return SyntaxKind.ExclamationEqualsToken; }
"%"                       { return SyntaxKind.PercentToken; }
"%="                      { return SyntaxKind.PercentEqualsToken; }
"^"                       { return SyntaxKind.CaretToken; }
"^="                      { return SyntaxKind.CaretEqualsToken; }
"&"                       { return SyntaxKind.AmpersandToken; }
"&&"                      { return SyntaxKind.AmpersandAmpersandToken; }
"&="                      { return SyntaxKind.AmpersandEqualsToken; }
"*"                       { return SyntaxKind.AsteriskToken; }
"*="                      { return SyntaxKind.AsteriskEqualsToken; }
"("                       { return SyntaxKind.OpenParenToken; }
")"                       { return SyntaxKind.CloseParenToken; }
"-"                       { return SyntaxKind.MinusToken; }
"--"                      { return SyntaxKind.MinusMinusToken; }
"-="                      { return SyntaxKind.MinusEqualsToken; }
"->"                      { return SyntaxKind.MinusGreaterThanToken; }
"+"                       { return SyntaxKind.PlusToken; }
"++"                      { return SyntaxKind.PlusPlusToken; }
"+="                      { return SyntaxKind.PlusEqualsToken; }
"="                       { return SyntaxKind.EqualsToken; }
"=="                      { return SyntaxKind.EqualsEqualsToken; }
"=>"                      { return SyntaxKind.EqualsGreaterThanToken; }
"{"                       { return SyntaxKind.OpenBraceToken; }
"}"                       { return SyntaxKind.CloseBraceToken; }
"["                       { return SyntaxKind.OpenBracketToken; }
"]"                       { return SyntaxKind.CloseBracketToken; }
"|"                       { return SyntaxKind.BarToken; }
"||"                      { return SyntaxKind.BarBarToken; }
"|="                      { return SyntaxKind.BarEqualsToken; }
":"                       { return SyntaxKind.ColonToken; }
"::"                      { return SyntaxKind.ColonColonToken; }
";"                       { return SyntaxKind.SemicolonToken; }
"<"                       { return SyntaxKind.LessThanToken; }
"<="                      { return SyntaxKind.LessThanEqualsToken; }
"<<"                      { return SyntaxKind.LessThanLessThanToken; }
"<<="                     { return SyntaxKind.LessThanLessThanEqualsToken; }
","                       { return SyntaxKind.CommaToken; }
// Never `>>`, `>>=`, `>>>`, `>>>=`: the parser merges adjacent `>` (LanguageParser, shift operators and type args).
">"                       { return SyntaxKind.GreaterThanToken; }
">="                      { return SyntaxKind.GreaterThanEqualsToken; }
// Never `..`: the parser merges two dots (LanguageParser.EatDotDotToken).
"."                       { return SyntaxKind.DotToken; }
"?"                       { return SyntaxKind.QuestionToken; }
"??"                      { return SyntaxKind.QuestionQuestionToken; }
"??="                     { return SyntaxKind.QuestionQuestionEqualsToken; }
"/"                       { return SyntaxKind.SlashToken; }
"/="                      { return SyntaxKind.SlashEqualsToken; }

[^\u0000-\u007F]          { return nonAscii(); }
[^]                       { return badCharacter(); }
