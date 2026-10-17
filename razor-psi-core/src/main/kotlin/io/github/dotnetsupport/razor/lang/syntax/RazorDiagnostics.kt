package io.github.dotnetsupport.razor.lang.syntax

/** `SourceLocation`: an absolute offset with its line and character (0-based), the path of the file for messages. */
data class SourceLocation(val filePath: String?, val absoluteIndex: Int, val lineIndex: Int, val characterIndex: Int) {
    override fun toString(): String = "($absoluteIndex:$lineIndex,$characterIndex)"

    companion object {
        val UNDEFINED = SourceLocation(null, -1, -1, -1)
        val ZERO = SourceLocation(null, 0, 0, 0)
    }
}

/** `SourceSpan`: printed as the compiler prints it in the baselines, `(15:0,15 [1] )` (path last, empty when none). */
data class SourceSpan(val filePath: String?, val absoluteIndex: Int, val lineIndex: Int, val characterIndex: Int, val length: Int) {
    constructor(location: SourceLocation, contentLength: Int) : this(location.filePath, location.absoluteIndex, location.lineIndex, location.characterIndex, contentLength)

    val endIndex: Int get() = absoluteIndex + length

    override fun toString(): String = "($absoluteIndex:$lineIndex,$characterIndex [$length] ${filePath ?: ""})"

    companion object {
        val UNDEFINED = SourceSpan(SourceLocation.UNDEFINED, 0)
    }
}

/** `RazorDiagnosticDescriptor`: the id (`RZ1000`) and the message format (`{0}` placeholders). */
class RazorDiagnosticDescriptor(val id: String, val messageFormat: String)

/** `RazorDiagnostic`: a diagnostic of the parser at [span]; [message] is the format with the arguments substituted. */
class RazorDiagnostic(val descriptor: RazorDiagnosticDescriptor, val span: SourceSpan, val args: List<Any?> = emptyList()) {
    val id: String get() = descriptor.id

    val message: String
        get() = descriptor.messageFormat.replace(Regex("\\{(\\d+)}")) { m -> args.getOrNull(m.groupValues[1].toInt())?.toString() ?: "" }

    /** As the baselines print a diagnostic next to a token or a directive: `RZ1036(15:0,15 [1] )`. */
    override fun toString(): String = "$id$span"
}

/**
 * `RazorDiagnosticFactory`, the parsing part (RZ1000–RZ2005): the ids, the messages of Resources.resx and the factory methods the
 * tokenizers and parsers call, named as the compiler's `CreateParsing_*` (the prefix dropped).
 */
object RazorDiagnosticFactory {
    private fun descriptor(code: Int, message: String) = RazorDiagnosticDescriptor("RZ$code", message)

    val Parsing_UnterminatedStringLiteral = descriptor(1000, "Unterminated string literal.  Strings that start with a quotation mark (\") must be terminated before the end of the line.  However, strings that start with @ and a quotation mark (@\") can span multiple lines.")
    val Parsing_BlockCommentNotTerminated = descriptor(1001, "End of file was reached before the end of the block comment.  All comments started with \"/*\" sequence must be terminated with a matching \"*/\" sequence.")
    val Parsing_HelperDirectiveNotAvailable = descriptor(1002, "The {0} directive is not supported.")
    val Parsing_UnexpectedWhiteSpaceAtStartOfCodeBlock = descriptor(1003, "A space or line break was encountered after the \"@\" character.  Only valid identifiers, keywords, comments, \"(\" and \"{\" are valid at the start of a code block and they must occur immediately following \"@\" with no space in between.")
    val Parsing_UnexpectedEndOfFileAtStartOfCodeBlock = descriptor(1004, "End-of-file was found after the \"@\" character.  \"@\" must be followed by a valid code block.  If you want to output an \"@\", escape it using the sequence: \"@@\"")
    val Parsing_UnexpectedCharacterAtStartOfCodeBlock = descriptor(1005, "\"{0}\" is not valid at the start of a code block.  Only identifiers, keywords, comments, \"(\" and \"{\" are valid.")
    val Parsing_ExpectedEndOfBlockBeforeEOF = descriptor(1006, "The {0} block is missing a closing \"{1}\" character.  Make sure you have a matching \"{1}\" character for all the \"{2}\" characters within this block, and that none of the \"{1}\" characters are being interpreted as markup.")
    val Parsing_ReservedWord = descriptor(1007, "\"{0}\" is a reserved word and cannot be used in implicit expressions.  An explicit expression (\"@()\") must be used.")
    val Parsing_SingleLineControlFlowStatementsNotAllowed = descriptor(1008, "Single-statement control-flow statements in Razor documents statements cannot contain markup. Markup should be enclosed in \"{\" and \"}\".")
    val Parsing_AtInCodeMustBeFollowedByColonParenOrIdentifierStart = descriptor(1009, "The \"@\" character must be followed by a \":\", \"(\", or a C# identifier.  If you intended to switch to markup, use an HTML start tag, for example:\n\n@if(isLoggedIn) {\n    <p>Hello, @user!</p>\n}")
    val Parsing_UnexpectedNestedCodeBlock = descriptor(1010, "Unexpected \"{\" after \"@\" character. Once inside the body of a code block (@if {}, @{}, etc.) you do not need to use \"@{\" to switch to code.")
    val Parsing_DirectiveTokensMustBeSeparatedByWhitespace = descriptor(1011, "The '{0}' directives value(s) must be separated by whitespace.")
    val Parsing_UnexpectedEOFAfterDirective = descriptor(1012, "Unexpected end of file following the '{0}' directive. Expected '{1}'.")
    val Parsing_DirectiveExpectsTypeName = descriptor(1013, "The '{0}' directive expects a type name.")
    val Parsing_DirectiveExpectsNamespace = descriptor(1014, "The '{0}' directive expects a namespace name.")
    val Parsing_DirectiveExpectsIdentifier = descriptor(1015, "The '{0}' directive expects an identifier.")
    val Parsing_DirectiveExpectsQuotedStringLiteral = descriptor(1016, "The '{0}' directive expects a string surrounded by double quotes.")
    val Parsing_UnexpectedDirectiveLiteral = descriptor(1017, "Unexpected literal following the '{0}' directive. Expected '{1}'.")
    val Parsing_DirectiveMustHaveValue = descriptor(1018, "Directive '{0}' must have a value.")
    val Parsing_IncompleteQuotesAroundDirective = descriptor(1019, "Optional quote around the directive '{0}' is missing the corresponding opening or closing quote.")
    val Parsing_InvalidTagHelperPrefixValue = descriptor(1020, "Invalid tag helper directive '{0}' value. '{1}' is not allowed in prefix '{2}'.")
    val Parsing_MarkupBlockMustStartWithTag = descriptor(1021, "Markup in a code block must start with a tag and all start tags must be matched with end tags.  Do not use unclosed tags like \"<br>\".  Instead use self-closing tags like \"<br/>\".")
    val Parsing_OuterTagMissingName = descriptor(1022, "Outer tag is missing a name. The first character of a markup block must be an HTML tag with a valid name.")
    val Parsing_TextTagCannotContainAttributes = descriptor(1023, "\"<text>\" and \"</text>\" tags cannot contain attributes.")
    val Parsing_UnfinishedTag = descriptor(1024, "End of file or an unexpected character was reached before the \"{0}\" tag could be parsed.  Elements inside markup blocks must be complete. They must either be self-closing (\"<br />\") or have matching end tags (\"<p>Hello</p>\").  If you intended to display a \"<\" character, use the \"&lt;\" HTML entity.")
    val Parsing_MissingEndTag = descriptor(1025, "The \"{0}\" element was not closed.  All elements must be either self-closing or have a matching end tag.")
    val Parsing_UnexpectedEndTag = descriptor(1026, "Encountered end tag \"{0}\" with no matching start tag.  Are your start/end tags properly balanced?")
    val Parsing_ExpectedCloseBracketBeforeEOF = descriptor(1027, "An opening \"{0}\" is missing the corresponding closing \"{1}\".")
    val Parsing_RazorCommentNotTerminated = descriptor(1028, "End of file was reached before the end of the block comment.  All comments that start with the \"@*\" sequence must be terminated with a matching \"*@\" sequence.")
    val Parsing_InvalidTagHelperLookupText = descriptor(1036, "Invalid tag helper directive look up text '{0}'. The correct look up text format is: \"name, assemblyName\".")
    val Parsing_DirectiveExpectsCSharpAttribute = descriptor(1037, "The '{0}' directive expects a C# attribute.")
    val Parsing_DirectiveExpectsBooleanLiteral = descriptor(1038, "The '{0}' directive expects a boolean literal.")
    val Parsing_GenericTypeParameterIdentifierMismatch = descriptor(1039, "The type parameter in the generic type constraint '{1}' does not match the type parameter '{2}' defined in the directive '{0}'.")
    val Parsing_UnexpectedIdentifier = descriptor(1040, "'{0}' is not valid in this position. Valid options are '{1}'")
    val Parsing_DirectiveExpectsIdentifierOrExpression = descriptor(1041, "The '{0}' directive expects an identifier or explicit razor expression (\"@()\").")
    val Parsing_PreprocessorDirectivesMustBeAtTheStartOfLine = descriptor(1043, "C# preprocessor directives must be at the start of the line, except for whitespace.")
    val Parsing_PossibleMisplacedPreprocessorDirective = descriptor(1044, "Possible C# preprocessor directive is misplaced. C# preprocessor directives must be at the start of the line, except for whitespace.")
    val Parsing_DefineAndUndefNotAllowed = descriptor(1045, "'#define' and '#undef' cannot be used in razor markup.")
    val Parsing_DocumentationCommentTerminator = descriptor(1047, "The '@documentation' directive cannot contain '*/'. Use an XML character reference instead.")
    val Parsing_DocumentationDirectiveWillChangeMeaning = descriptor(1048, "The '@documentation' syntax will be treated as a directive in Razor 12.0. Use an explicit expression ('@(...)') to preserve the current meaning.")
    val Parsing_DocumentationShouldStartWithTag = descriptor(1049, "Documentation should start with an XML tag, such as '<summary>'.")
    val Parsing_DuplicateDirective = descriptor(2001, "The '{0}' directive may only occur once per document.")
    val Parsing_SectionsCannotBeNested = descriptor(2002, "Section blocks (\"{0}\") cannot be nested.  Only one level of section blocks are allowed.")
    val Parsing_InlineMarkupBlocksCannotBeNested = descriptor(2003, "Inline markup blocks (@<p>Content</p>) cannot be nested.  Only one level of inline markup is allowed.")
    val Parsing_NamespaceImportAndTypeAliasCannotExistWithinCodeBlock = descriptor(2004, "Namespace imports and type aliases cannot be placed within code blocks.  They must immediately follow an \"@\" character in markup.  It is recommended that you put them at the top of the page, as in the following example:\n\n@using System.Drawing;\n@{\n    // OK here to use types from System.Drawing in the page.\n}")
    val Parsing_DirectiveMustAppearAtStartOfLine = descriptor(2005, "The '{0}' directive must appear at the start of the line.")

    private fun create(descriptor: RazorDiagnosticDescriptor, span: SourceSpan, vararg args: Any?) = RazorDiagnostic(descriptor, span, args.toList())

    fun unterminatedStringLiteral(location: SourceSpan) = create(Parsing_UnterminatedStringLiteral, location)
    fun blockCommentNotTerminated(location: SourceSpan) = create(Parsing_BlockCommentNotTerminated, location)
    fun helperDirectiveNotAvailable(location: SourceSpan) = create(Parsing_HelperDirectiveNotAvailable, location, SyntaxConstants.CSharp.HELPER_KEYWORD)
    fun unexpectedWhiteSpaceAtStartOfCodeBlock(location: SourceSpan) = create(Parsing_UnexpectedWhiteSpaceAtStartOfCodeBlock, location)
    fun unexpectedEndOfFileAtStartOfCodeBlock(location: SourceSpan) = create(Parsing_UnexpectedEndOfFileAtStartOfCodeBlock, location)
    fun unexpectedCharacterAtStartOfCodeBlock(location: SourceSpan, content: String) = create(Parsing_UnexpectedCharacterAtStartOfCodeBlock, location, content)
    fun expectedEndOfBlockBeforeEOF(location: SourceSpan, blockName: String, closeBlock: String, openBlock: String) =
        create(Parsing_ExpectedEndOfBlockBeforeEOF, location, blockName, closeBlock, openBlock)
    fun reservedWord(location: SourceSpan, content: String) = create(Parsing_ReservedWord, location, content)
    fun singleLineControlFlowStatementsCannotContainMarkup(location: SourceSpan) = create(Parsing_SingleLineControlFlowStatementsNotAllowed, location)
    fun atInCodeMustBeFollowedByColonParenOrIdentifierStart(location: SourceSpan) = create(Parsing_AtInCodeMustBeFollowedByColonParenOrIdentifierStart, location)
    fun unexpectedNestedCodeBlock(location: SourceSpan) = create(Parsing_UnexpectedNestedCodeBlock, location)
    fun directiveTokensMustBeSeparatedByWhitespace(location: SourceSpan, directiveName: String) = create(Parsing_DirectiveTokensMustBeSeparatedByWhitespace, location, directiveName)
    fun unexpectedEOFAfterDirective(location: SourceSpan, directiveName: String, expectedToken: String) = create(Parsing_UnexpectedEOFAfterDirective, location, directiveName, expectedToken)
    fun directiveExpectsTypeName(location: SourceSpan, directiveName: String) = create(Parsing_DirectiveExpectsTypeName, location, directiveName)
    fun directiveExpectsNamespace(location: SourceSpan, directiveName: String) = create(Parsing_DirectiveExpectsNamespace, location, directiveName)
    fun directiveExpectsIdentifier(location: SourceSpan, directiveName: String) = create(Parsing_DirectiveExpectsIdentifier, location, directiveName)
    fun directiveExpectsQuotedStringLiteral(location: SourceSpan, directiveName: String) = create(Parsing_DirectiveExpectsQuotedStringLiteral, location, directiveName)
    fun unexpectedDirectiveLiteral(location: SourceSpan, directiveName: String, expected: String) = create(Parsing_UnexpectedDirectiveLiteral, location, directiveName, expected)
    fun directiveMustHaveValue(location: SourceSpan, directiveName: String) = create(Parsing_DirectiveMustHaveValue, location, directiveName)
    fun incompleteQuotesAroundDirective(location: SourceSpan, directiveName: String) = create(Parsing_IncompleteQuotesAroundDirective, location, directiveName)
    fun invalidTagHelperPrefixValue(location: SourceSpan, directiveName: String, character: Char, prefix: String) =
        create(Parsing_InvalidTagHelperPrefixValue, location, directiveName, character, prefix)
    fun markupBlockMustStartWithTag(location: SourceSpan) = create(Parsing_MarkupBlockMustStartWithTag, location)
    fun outerTagMissingName(location: SourceSpan) = create(Parsing_OuterTagMissingName, location)
    fun textTagCannotContainAttributes(location: SourceSpan) = create(Parsing_TextTagCannotContainAttributes, location)
    fun unfinishedTag(location: SourceSpan, tagName: String) = create(Parsing_UnfinishedTag, location, tagName)
    fun missingEndTag(location: SourceSpan, tagName: String) = create(Parsing_MissingEndTag, location, tagName)
    fun unexpectedEndTag(location: SourceSpan, tagName: String) = create(Parsing_UnexpectedEndTag, location, tagName)
    fun expectedCloseBracketBeforeEOF(location: SourceSpan, openBrace: String, closeBrace: String) = create(Parsing_ExpectedCloseBracketBeforeEOF, location, openBrace, closeBrace)
    fun razorCommentNotTerminated(location: SourceSpan) = create(Parsing_RazorCommentNotTerminated, location)
    fun invalidTagHelperLookupText(location: SourceSpan, lookupText: String) = create(Parsing_InvalidTagHelperLookupText, location, lookupText)
    fun directiveExpectsCSharpAttribute(location: SourceSpan, directiveName: String) = create(Parsing_DirectiveExpectsCSharpAttribute, location, directiveName)
    fun directiveExpectsBooleanLiteral(location: SourceSpan, directiveName: String) = create(Parsing_DirectiveExpectsBooleanLiteral, location, directiveName)
    fun genericTypeParameterIdentifierMismatch(location: SourceSpan, directiveName: String, constraintIdentifier: String, originalIdentifier: String) =
        create(Parsing_GenericTypeParameterIdentifierMismatch, location, directiveName, constraintIdentifier, originalIdentifier)
    fun unexpectedIdentifier(location: SourceSpan, content: String, vararg options: String) = create(Parsing_UnexpectedIdentifier, location, content, options.joinToString(", "))
    fun directiveExpectsIdentifierOrExpression(location: SourceSpan, directiveName: String) = create(Parsing_DirectiveExpectsIdentifierOrExpression, location, directiveName)
    fun preprocessorDirectivesMustBeAtTheStartOfLine(location: SourceSpan) = create(Parsing_PreprocessorDirectivesMustBeAtTheStartOfLine, location)
    fun possibleMisplacedPreprocessorDirective(location: SourceSpan) = create(Parsing_PossibleMisplacedPreprocessorDirective, location)
    fun defineAndUndefNotAllowed(location: SourceSpan) = create(Parsing_DefineAndUndefNotAllowed, location)
    fun documentationCommentTerminator(location: SourceSpan) = create(Parsing_DocumentationCommentTerminator, location)
    fun documentationDirectiveWillChangeMeaning(location: SourceSpan) = create(Parsing_DocumentationDirectiveWillChangeMeaning, location)
    fun documentationShouldStartWithTag(location: SourceSpan) = create(Parsing_DocumentationShouldStartWithTag, location)
    fun duplicateDirective(location: SourceSpan, directive: String) = create(Parsing_DuplicateDirective, location, directive)
    fun sectionsCannotBeNested(location: SourceSpan) = create(Parsing_SectionsCannotBeNested, location, "@section Header { ... }")
    fun inlineMarkupBlocksCannotBeNested(location: SourceSpan) = create(Parsing_InlineMarkupBlocksCannotBeNested, location)
    fun namespaceImportAndTypeAliasCannotExistWithinCodeBlock(location: SourceSpan) = create(Parsing_NamespaceImportAndTypeAliasCannotExistWithinCodeBlock, location)
    fun directiveMustAppearAtStartOfLine(location: SourceSpan, directiveName: String) = create(Parsing_DirectiveMustAppearAtStartOfLine, location, directiveName)
}

/** `SyntaxConstants`: the characters and keywords Razor itself knows. */
object SyntaxConstants {
    const val TEXT_TAG_NAME = "text"
    const val TRANSITION_CHARACTER = '@'
    const val TRANSITION_STRING = "@"
    const val START_COMMENT_SEQUENCE = "@*"
    const val END_COMMENT_SEQUENCE = "*@"

    object CSharp {
        const val USING_KEYWORD_LENGTH = 5
        const val USING_KEYWORD = "using"
        const val TAG_HELPER_PREFIX_KEYWORD = "tagHelperPrefix"
        const val ADD_TAG_HELPER_KEYWORD = "addTagHelper"
        const val REMOVE_TAG_HELPER_KEYWORD = "removeTagHelper"
        const val INHERITS_KEYWORD = "inherits"
        const val FUNCTIONS_KEYWORD = "functions"
        const val SECTION_KEYWORD = "section"
        const val ELSE_IF_KEYWORD = "else if"
        const val NAMESPACE_KEYWORD = "namespace"
        const val CLASS_KEYWORD = "class"
        const val HELPER_KEYWORD = "helper"
    }
}
