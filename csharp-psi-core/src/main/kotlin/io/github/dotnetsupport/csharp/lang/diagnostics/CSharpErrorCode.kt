// The codes and English message formats of Microsoft.CodeAnalysis.CSharp 5.9.0 (roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c):
// `ErrorCode` and `CSharpResources.resx`, as `roslyndump errors` prints them. The entries are the codes the parser and the lexer
// of Roslyn report (`ErrorCode.X` in Parser/*.cs). Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
package io.github.dotnetsupport.csharp.lang.diagnostics

import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.SyntaxKind

/**
 * A diagnostic code of Roslyn's parser or lexer with its English message ([format], `{0}`-style arguments). The parser writes it into
 * the description of the error element it makes ([describe]), [CSharpSyntaxDiagnostics] reads it back with the position Roslyn gives it.
 */
@Suppress("EnumEntryName")
enum class CSharpErrorCode(val number: Int, val format: String) {
    ERR_ExplicitEventFieldImpl(71, "An explicit interface implementation of an event must use event accessor syntax"),
    ERR_BadMemberFlag(106, "The modifier '{0}' is not valid for this item"),
    ERR_NamespaceUnexpected(116, "A namespace cannot directly contain members such as fields, methods or statements"),
    ERR_ConstValueRequired(145, "A const field requires a value to be provided"),
    ERR_ConstantExpected(150, "A constant value is expected"),
    ERR_InvalidArray(178, "Invalid rank specifier: expected ',' or ']'"),
    ERR_BadForeachDecl(230, "Type and identifier are both required in a foreach statement"),
    ERR_ArraySizeInDeclaration(270, "Array size cannot be specified in a variable declaration (try initializing with a 'new' expression)"),
    ERR_ExternAfterElements(439, "An extern alias declaration must precede all other elements defined in the namespace"),
    ERR_ValueExpected(443, "Syntax error; value expected"),
    ERR_FloatOverflow(594, "Floating-point constant is outside the range of type '{0}'"),
    ERR_InvalidReal(595, "Invalid real literal."),
    ERR_CStyleArray(650, "Bad array declarator: To declare a managed array the rank specifier precedes the variable's identifier. To declare a fixed size buffer field, use the fixed keyword before the field type."),
    ERR_AliasQualAsExpression(687, "The namespace alias qualifier '::' always resolves to a type or namespace so is illegal here. Consider using '.' instead."),
    ERR_ExpectedSelectOrGroup(742, "A query body must end with a select clause or a group clause"),
    ERR_ExpectedContextualKeywordOn(743, "Expected contextual keyword 'on'"),
    ERR_ExpectedContextualKeywordEquals(744, "Expected contextual keyword 'equals'"),
    ERR_ExpectedContextualKeywordBy(745, "Expected contextual keyword 'by'"),
    ERR_MissingArgument(839, "Argument missing"),
    ERR_IdentifierExpected(1001, "Identifier expected"),
    ERR_SemicolonExpected(1002, "; expected"),
    ERR_SyntaxError(1003, "Syntax error, '{0}' expected"),
    ERR_IllegalEscape(1009, "Unrecognized escape sequence"),
    ERR_NewlineInConst(1010, "Newline in constant"),
    ERR_EmptyCharConst(1011, "Empty character literal"),
    ERR_TooManyCharsInConst(1012, "Too many characters in character literal"),
    ERR_InvalidNumber(1013, "Invalid number"),
    ERR_GetOrSetExpected(1014, "A get or set accessor expected"),
    ERR_ThisOrBaseExpected(1018, "Keyword 'this' or 'base' expected"),
    ERR_OvlUnaryOperatorExpected(1019, "Overloadable unary operator expected"),
    ERR_OvlBinaryOperatorExpected(1020, "Overloadable binary operator expected"),
    ERR_IntOverflow(1021, "Integral constant is too large"),
    ERR_EOFExpected(1022, "Type or namespace definition, or end-of-file expected"),
    ERR_PPDirectiveExpected(1024, "Preprocessor directive expected"),
    ERR_EndOfPPLineExpected(1025, "Single-line comment or end-of-line expected"),
    ERR_CloseParenExpected(1026, ") expected"),
    ERR_EndifDirectiveExpected(1027, "#endif directive expected"),
    ERR_UnexpectedDirective(1028, "Unexpected preprocessor directive"),
    ERR_ErrorDirective(1029, "#error: '{0}'"),
    WRN_WarningDirective(1030, "#warning: '{0}'"),
    ERR_TypeExpected(1031, "Type expected"),
    ERR_PPDefFollowsToken(1032, "Cannot define/undefine preprocessor symbols after first token in file"),
    ERR_OpenEndedComment(1035, "End-of-file found, '*/' expected"),
    ERR_OvlOperatorExpected(1037, "Overloadable operator expected"),
    ERR_EndRegionDirectiveExpected(1038, "#endregion directive expected"),
    ERR_UnterminatedStringLit(1039, "Unterminated string literal"),
    ERR_BadDirectivePlacement(1040, "Preprocessor directives must appear as the first non-whitespace character on a line"),
    ERR_IdentifierExpectedKW(1041, "Identifier expected; '{1}' is a keyword"),
    ERR_SemiOrLBraceExpected(1043, "{ or ; expected"),
    ERR_MultiTypeInDeclaration(1044, "Cannot use more than one type in a for, using, fixed, or declaration statement"),
    ERR_AddOrRemoveExpected(1055, "An add or remove accessor expected"),
    ERR_UnexpectedCharacter(1056, "Unexpected character '{0}'"),
    WRN_IdentifierOrNumericLiteralExpected(1072, "Expected identifier or numeric literal."),
    ERR_UnexpectedToken(1073, "Unexpected token '{0}'"),
    ERR_RbraceExpected(1513, "} expected"),
    ERR_LbraceExpected(1514, "{ expected"),
    ERR_InExpected(1515, "'in' expected"),
    ERR_InvalidPreprocExpr(1517, "Invalid preprocessor expression"),
    ERR_InvalidMemberDecl(1519, "Invalid token '{0}' in a member declaration"),
    ERR_MemberNeedsType(1520, "Method must have a return type"),
    ERR_ExpectedEndTry(1524, "Expected catch or finally"),
    ERR_InvalidExprTerm(1525, "Invalid expression term '{0}'"),
    ERR_BadNewExpr(1526, "A new expression requires an argument list or (), [], or {} after type"),
    ERR_BadVarDecl(1528, "Expected ; or = (cannot specify constructor arguments in declaration)"),
    ERR_UsingAfterElements(1529, "A using clause must precede all other elements defined in the namespace except extern alias declarations"),
    ERR_BadBinOpArgs(1534, "Overloaded binary operator '{0}' takes two parameters"),
    ERR_BadUnOpArgs(1535, "Overloaded unary operator '{0}' takes one parameter"),
    ERR_NoVoidParameter(1536, "Invalid parameter type 'void'"),
    ERR_NoVoidHere(1547, "Keyword 'void' cannot be used in this context"),
    ERR_BadArraySyntax(1552, "Array type specifier, [], must appear before parameter name"),
    ERR_BadOperatorSyntax(1553, "Declaration is not valid; use '{0} operator <dest-type> (...' instead"),
    WRN_XMLParseError(1570, "XML comment has badly formed XML -- '{0}'"),
    ERR_InvalidLineNumber(1576, "The line number specified for #line directive is missing or invalid"),
    ERR_MissingPPFile(1578, "Quoted file name, single-line comment or end-of-line expected"),
    ERR_BadModifierLocation(1585, "Member modifier '{0}' must precede the member type and name"),
    ERR_UnexpectedSemicolon(1597, "Semicolon after method or accessor block is not valid"),
    ERR_EmptyYield(1627, "Expression expected after yield return"),
    WRN_IllegalPragma(1633, "Unrecognized #pragma directive"),
    WRN_IllegalPPWarning(1634, "Expected 'disable' or 'restore'"),
    ERR_FixedDimsRequired(1641, "A fixed size buffer field must have the array size specifier after the field name"),
    ERR_ExpectedVerbatimLiteral(1646, "Keyword, identifier, or string expected after verbatim specifier: @"),
    WRN_TooManyLinesForDebugger(1687, "Source file has exceeded the limit of 16,707,565 lines representable in the PDB; debug information will be incorrect"),
    WRN_IllegalPPChecksum(1695, "Invalid #pragma checksum syntax; should be #pragma checksum \"filename\" \"{XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}\" \"XXXX...\""),
    WRN_EndOfPPLineExpected(1696, "Single-line comment or end-of-line expected"),
    ERR_GlobalAttributesNotFirst(1730, "Assembly and module attributes must precede all other elements defined in a file except using clauses and extern alias declarations"),
    ERR_ExpressionExpected(1733, "Expected expression"),
    ERR_IllegalVarianceSyntax(1960, "Invalid variance modifier. Only interface and delegate type parameters can be specified as variant."),
    ERR_LegacyObjectIdSyntax(2043, "'id#' syntax is no longer supported. Use '\$id' instead."),
    ERR_BadAwaitAsIdentifier(4003, "'await' cannot be used as an identifier within an async method or lambda expression"),
    ERR_UnexpectedAliasedName(7000, "Unexpected use of an aliased name"),
    ERR_UnexpectedGenericName(7002, "Unexpected use of a generic name"),
    ERR_PPReferenceFollowsToken(7009, "Cannot use #r after first token in file"),
    ERR_ExpectedPPFile(7010, "Quoted file name expected"),
    ERR_ReferenceDirectiveOnlyAllowedInScripts(7011, "#r is only allowed in scripts"),
    ERR_GlobalDefinitionOrStatementExpected(7017, "Member definition, statement, or end-of-file expected"),
    ERR_NamespaceNotAllowedInScript(7021, "Cannot declare namespace in script code"),
    ERR_UnclosedExpressionHole(8076, "Missing close delimiter '}' for interpolated expression started with '{'."),
    ERR_InsufficientStack(8078, "An expression is too long or complex to compile"),
    ERR_UnescapedCurly(8086, "A '{0}' character must be escaped (by doubling) in an interpolated string."),
    ERR_EscapedCurly(8087, "A '{0}' character may only be escaped by doubling '{0}{0}' in an interpolated string."),
    ERR_LoadDirectiveOnlyAllowedInScripts(8097, "#load is only allowed in scripts"),
    ERR_PPLoadFollowsToken(8098, "Cannot use #load after first token in file"),
    ERR_TupleTooFewElements(8124, "Tuple must contain at least two elements."),
    ERR_SemiOrLBraceOrArrowExpected(8180, "{ or ; or => expected"),
    ERR_Merge_conflict_marker_encountered(8300, "Merge conflict marker encountered"),
    ERR_CompilerAndLanguageVersion(8304, "Compiler version: '{0}'. Language version: {1}. Compiler path: '{2}'."),
    ERR_ConditionalInInterpolation(8361, "A conditional expression cannot be used directly in a string interpolation because the ':' ends the interpolation. Parenthesize the conditional expression."),
    ERR_InvalidStackAllocArray(8381, "\"Invalid rank specifier: expected ']'"),
    ERR_MissingPattern(8504, "Pattern missing"),
    ERR_SwitchGoverningExpressionRequiresParens(8515, "Parentheses are required around the switch governing expression."),
    ERR_DiscardPatternInSwitchStatement(8523, "The discard pattern is not permitted as a case label in a switch statement. Use 'case var _:' for a discard pattern, or 'case @_:' for a constant named '_'."),
    ERR_DesignatorBeforePropertyPattern(8525, "A variable designator must come after a property pattern."),
    ERR_TripleDotNotAllowed(8635, "Unexpected character sequence '...'"),
    ERR_NullableDirectiveQualifierExpected(8637, "Expected 'enable', 'disable', or 'restore'"),
    ERR_ElseCannotStartStatement(8641, "'else' cannot start a statement."),
    ERR_NullableDirectiveTargetExpected(8668, "Expected 'warnings', 'annotations', or end of directive"),
    ERR_TopLevelStatementAfterNamespaceOrType(8803, "Top-level statements must precede namespace and type declarations."),
    WRN_PrecedenceInversion(8848, "Operator '{0}' cannot be used here due to precedence. Use parentheses to disambiguate."),
    ERR_CannotSpecifyManagedWithUnmanagedSpecifiers(8888, "'managed' calling convention cannot be combined with unmanaged calling convention specifiers."),
    ERR_LineSpanDirectiveInvalidValue(8938, "The #line directive value is missing or out of range"),
    ERR_LineSpanDirectiveEndLessThanStart(8939, "The #line directive end position must be greater than or equal to the start position"),
    ERR_RawStringNotInDirectives(8996, "Raw string literals are not allowed in preprocessor directives."),
    ERR_UnterminatedRawString(8997, "Unterminated raw string literal."),
    ERR_TooManyQuotesForRawString(8998, "The raw string literal does not start with enough quote characters to allow this many consecutive quote characters as content."),
    ERR_LineDoesNotStartWithSameWhitespace(8999, "Line does not start with the same whitespace as the closing line of the raw string literal."),
    ERR_RawStringDelimiterOnOwnLine(9000, "Raw string literal delimiter must be on its own line."),
    ERR_RawStringMustContainContent(9002, "Multi-line raw string literals must contain at least one line of content."),
    ERR_LineContainsDifferentWhitespace(9003, "Line contains different whitespace than the closing line of the raw string literal: '{0}' versus '{1}'"),
    ERR_NotEnoughQuotesForRawString(9004, "Not enough quotes for raw string literal."),
    ERR_NotEnoughCloseBracesForRawString(9005, "The interpolation must end with the same number of closing braces as the number of '\$' characters that the raw string literal started with."),
    ERR_TooManyOpenBracesForRawString(9006, "The interpolated raw string literal does not start with enough '\$' characters to allow this many consecutive opening braces as content."),
    ERR_TooManyCloseBracesForRawString(9007, "The interpolated raw string literal does not start with enough '\$' characters to allow this many consecutive closing braces as content."),
    ERR_IllegalAtSequence(9008, "Sequence of '@' characters is not allowed. A verbatim string or identifier can only have one '@' character and a raw string cannot have any."),
    ERR_StringMustStartWithQuoteCharacter(9009, "String must start with quote character: \""),
    ERR_NoEnumConstraint(9010, "Keyword 'enum' cannot be used as a constraint. Did you mean 'struct, System.Enum'?"),
    ERR_NoDelegateConstraint(9011, "Keyword 'delegate' cannot be used as a constraint. Did you mean 'System.Delegate'?"),
    ERR_MisplacedRecord(9012, "Unexpected keyword 'record'. Did you mean 'record struct' or 'record class'?"),
    ERR_MisplacedUnchecked(9027, "Unexpected keyword 'unchecked'"),
    ERR_LineSpanDirectiveRequiresSpace(9028, "The #line span directive requires space before the first parenthesis, before the character offset, and before the file name"),
    ERR_BadStaticAfterUnsafe(9133, "'static' modifier must precede 'unsafe' modifier."),
    ERR_BadCaseInSwitchArm(9134, "A switch expression arm does not begin with a 'case' keyword."),
    ERR_NoModifiersOnUsing(9229, "Modifiers cannot be placed on using declarations"),
    ERR_ExtensionDisallowsName(9281, "Extension declarations may not have a name."),
    ERR_PPIgnoredFollowsToken(9297, "'#:' directives cannot be after first token in file"),
    ERR_PPIgnoredNeedsFileBasedProgram(9298, "'#:' directives can be only used in file-based programs ('-features:FileBasedProgram')"),
    ERR_PPIgnoredFollowsIf(9299, "'#:' directives cannot be after '#if' directive"),
    ERR_BadCompoundAssignmentOpArgs(9313, "Overloaded compound assignment operator '{0}' takes one parameter"),
    ERR_PPShebangInProjectBasedProgram(9314, "'#!' directives can be only used in scripts or file-based programs"),
    ERR_EqualityOperatorInPatternNotSupported(9344, "The '==' operator is not supported in a pattern."),
    ERR_InequalityOperatorInPatternNotSupported(9345, "The '!=' operator is not supported in a pattern. Use 'not' to represent a negated pattern."),
    ERR_PPShebangNotOnFirstLine(9378, "'#!' must be the first characters on the first line of the file");

    /** `CS1002` */
    val id: String get() = "CS%04d".format(number)

    /** Roslyn names warnings `WRN_`: everything else here is an error. */
    val isWarning: Boolean get() = name.startsWith("WRN_")

    /** The message with [args] in place of `{0}`, `{1}` ... (`string.Format` without format specifiers, as the codes here use). */
    fun message(vararg args: Any?): String = PLACEHOLDER.replace(format) { m -> args.getOrNull(m.groupValues[1].toInt())?.toString() ?: "" }

    /** The description of an error element: `CS1002: ; expected`, or with an [anchor] `CS1003@first: Syntax error, ',' expected`. */
    fun describe(vararg args: Any?, anchor: CSharpDiagnosticAnchor = CSharpDiagnosticAnchor.ELEMENT): String =
        if (anchor == CSharpDiagnosticAnchor.ELEMENT) "$id: ${message(*args)}" else "$id@${anchor.tag}: ${message(*args)}"

    companion object {
        private val PLACEHOLDER = Regex("""\{(\d+)}""")
        private val byNumber = entries.associateBy { it.number }

        fun of(number: Int): CSharpErrorCode? = byNumber[number]

        /** `SyntaxParser.GetExpectedTokenErrorCode` (SP 700). */
        fun expectedTokenCode(expected: IElementType, actual: IElementType?): CSharpErrorCode = when (expected) {
            SyntaxKind.IdentifierToken -> if (CSharpSyntaxFacts.isReservedKeyword(actual)) ERR_IdentifierExpectedKW else ERR_IdentifierExpected
            SyntaxKind.SemicolonToken -> ERR_SemicolonExpected
            SyntaxKind.CloseParenToken -> ERR_CloseParenExpected
            SyntaxKind.OpenBraceToken -> ERR_LbraceExpected
            SyntaxKind.CloseBraceToken -> ERR_RbraceExpected
            else -> ERR_SyntaxError
        }

        /** `SyntaxParser.GetExpectedTokenError` (SP 674): the description of "[expected] expected" where [actual] stands. */
        fun expectedToken(expected: IElementType, actual: IElementType?, anchor: CSharpDiagnosticAnchor = CSharpDiagnosticAnchor.ELEMENT): String =
            when (val code = expectedTokenCode(expected, actual)) {
                ERR_SyntaxError -> code.describe(CSharpSyntaxFacts.getText(expected), anchor = anchor)
                ERR_IdentifierExpectedKW -> code.describe("", actual?.let(CSharpSyntaxFacts::getText), anchor = anchor)
                else -> code.describe(anchor = anchor)
            }
    }
}

/**
 * Where Roslyn puts the diagnostic of an error element, beyond what the shape of the element tells ([CSharpSyntaxDiagnostics]).
 * [ELEMENT]: a missing token's diagnostic by `GetDiagnosticSpanForMissingNodeOrToken`, a zero-width element's on the token after it,
 * skipped tokens' on all of them. [FIRST]: on the first skipped token (`EatTokenWithPrejudice` of `SkipBadTokensWithErrorCode`).
 * [SECOND]: on the second (the target of a misplaced `[assembly: ...]`).
 * [FIRST_EXPECTED]: the first skipped token read as a missing one (`EatTokenEvenWithIncorrectKind` of `SkipBadTokensWithExpectedKind`):
 * on it when it is on the line of the token before, else zero-width after that token. [NEXT]: on the token after the element (a
 * missing token with skipped syntax, `GetDiagnosticSpanForMissingNodeOrToken` → `getOffsetAndWidthOfSkippedToken`). [PREVIOUS]: on
 * the token before the element (`AddError(identifier, ...)` after skipped syntax was attached to the identifier). [PREVIOUS_START]:
 * zero-width at the start of the token before the element (`ERR_TripleDotNotAllowed` on `..`, the third dot skipped). [HERE]:
 * zero-width where the element is (`ERR_ValueExpected` with the width of an omitted array size). [EACH_EXPECTED]: [FIRST_EXPECTED]
 * for every skipped token (`for (;;;)`). [PREVIOUS_SIBLING]: on the whole node before the element (`ConsumeUnexpectedTokens`).
 */
enum class CSharpDiagnosticAnchor(val tag: String) {
    ELEMENT(""), FIRST("first"), SECOND("second"), FIRST_EXPECTED("firstExpected"), NEXT("next"), PREVIOUS("previous"), PREVIOUS_START("previousStart"), HERE("here"),
    EACH_EXPECTED("eachExpected"), PREVIOUS_SIBLING("previousSibling");

    companion object {
        fun of(tag: String?): CSharpDiagnosticAnchor = entries.firstOrNull { it.tag == (tag ?: "") } ?: ELEMENT
    }
}
