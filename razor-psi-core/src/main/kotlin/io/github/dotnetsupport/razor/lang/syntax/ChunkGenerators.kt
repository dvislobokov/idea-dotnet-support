package io.github.dotnetsupport.razor.lang.syntax

/**
 * `ISpanChunkGenerator`: what a span means to the code generator — markup, a C# statement, an expression, a directive token...
 * Printed by the serializer as `Gen<…>` exactly as the compiler's `ToString` does, so the gates can compare it.
 */
sealed class ChunkGenerator {
    /** `SpanChunkGenerator.Null`: a span that generates nothing (transitions, meta code, comments). */
    object None : ChunkGenerator() {
        override fun toString() = "None"
    }

    object Markup : ChunkGenerator() {
        override fun toString() = "Markup"
    }

    object Statement : ChunkGenerator() {
        override fun toString() = "Stmt"
    }

    /** `ExpressionChunkGenerator`: the compiler makes a new instance per span; the parser checks `is ExpressionChunkGenerator`. */
    object Expression : ChunkGenerator() {
        override fun toString() = "Expr"
    }

    /** `LiteralAttributeChunkGenerator`: the prefix (whitespace) and the value of a literal attribute value, with their locations. */
    class LiteralAttribute(val prefix: String, val prefixLocation: SourceLocation, val value: String, val valueLocation: SourceLocation) : ChunkGenerator() {
        override fun toString() = "LitAttr:$prefix@$prefixLocation"
    }

    class DirectiveToken(val descriptor: DirectiveTokenDescriptor) : ChunkGenerator() {
        override fun toString() = "DirectiveToken {${descriptor.name};${descriptor.kind};Opt:${if (descriptor.optional) "True" else "False"}}"
    }

    class AddTagHelper(val lookupText: String, val directiveText: String, val typePattern: String?, val assemblyName: String?, val diagnostics: List<RazorDiagnostic>) : ChunkGenerator() {
        override fun toString() = "AddTagHelper:{$lookupText;$directiveText;${typePattern ?: ""};${assemblyName ?: ""}}" + diagnosticsSuffix(diagnostics)
    }

    class RemoveTagHelper(val lookupText: String, val directiveText: String, val typePattern: String?, val assemblyName: String?, val diagnostics: List<RazorDiagnostic>) : ChunkGenerator() {
        override fun toString() = "RemoveTagHelper:{$lookupText;$directiveText;${typePattern ?: ""};${assemblyName ?: ""}}" + diagnosticsSuffix(diagnostics)
    }

    class TagHelperPrefix(val prefix: String, val directiveText: String, val diagnostics: List<RazorDiagnostic>) : ChunkGenerator() {
        override fun toString() = "TagHelperPrefix:{$prefix;$directiveText}" + diagnosticsSuffix(diagnostics)
    }

    /** `AddImportChunkGenerator`: the `using` directive's content after the keyword, the namespace as parsed, `static`, the `;`. */
    class AddImport(val namespace: String, val parsedNamespace: String, val isStatic: Boolean, val hasExplicitSemicolon: Boolean) : ChunkGenerator() {
        override fun toString() = "Import: $namespace;"
    }

    protected fun diagnosticsSuffix(diagnostics: List<RazorDiagnostic>): String =
        if (diagnostics.isEmpty()) "" else " [" + diagnostics.joinToString(", ") + "]"
}

/** `AcceptedCharactersInternal`: what an edit inside a span may add without reparsing (flags). */
object AcceptedCharacters {
    const val NONE = 0
    const val NEW_LINE = 1
    const val WHITESPACE = 2
    const val NON_WHITESPACE = 4
    const val ALL_WHITESPACE = NEW_LINE or WHITESPACE
    const val ANY = ALL_WHITESPACE or NON_WHITESPACE
    const val ANY_EXCEPT_NEWLINE = NON_WHITESPACE or WHITESPACE

    /** The names of the flags as .NET prints the enum: `None`, `Any`, `AnyExceptNewline`, `NewLine, Whitespace`... */
    fun name(value: Int): String = when (value) {
        NONE -> "None"
        NEW_LINE -> "NewLine"
        WHITESPACE -> "Whitespace"
        NON_WHITESPACE -> "NonWhitespace"
        ALL_WHITESPACE -> "AllWhitespace"
        ANY -> "Any"
        ANY_EXCEPT_NEWLINE -> "AnyExceptNewline"
        else -> value.toString()
    }
}

/**
 * `SpanEditHandler` and its subclasses: the tooling's view of a span (what edits it accepts). The parser builds them only when
 * [RazorParserOptions.enableSpanEditHandlers]; what matters here is the printed form (`SpanEditHandler;Accepts:Any`) and the
 * accepted characters, which R7 (typing) will use. The change-application logic of the compiler is not ported.
 */
open class SpanEditHandler(val acceptedCharacters: Int) {
    override fun toString(): String = javaClass.simpleName + ";Accepts:" + AcceptedCharacters.name(acceptedCharacters)
}

class ImplicitExpressionEditHandler(acceptedCharacters: Int, val acceptTrailingDot: Boolean, val keywords: KeywordSet) : SpanEditHandler(acceptedCharacters) {
    override fun toString(): String = super.toString() + ";ImplicitExpression[" + (if (acceptTrailingDot) "ATD" else "RTD") + "];K" + keywords.count
}

class CodeBlockEditHandler(acceptedCharacters: Int) : SpanEditHandler(acceptedCharacters) {
    override fun toString(): String = super.toString() + ";CodeBlock"
}

class DirectiveTokenEditHandler(acceptedCharacters: Int) : SpanEditHandler(acceptedCharacters)

/** `AutoCompleteEditHandler`: the `}` the editor may add; whether it can is known only after the block is parsed ([AutoCompleteStringAccessor]). */
class AutoCompleteEditHandler(acceptedCharacters: Int, val accessor: AutoCompleteStringAccessor, val autoCompleteAtEndOfSpan: Boolean) : SpanEditHandler(acceptedCharacters) {
    val autoCompleteString: String? get() = if (accessor.canAcceptCloseBrace) "}" else null

    override fun toString(): String = super.toString() + ",AutoComplete:[" + (autoCompleteString ?: "<null>") + "]" + (if (autoCompleteAtEndOfSpan) ";AtEnd" else ";AtEOL")

    class AutoCompleteStringAccessor {
        var canAcceptCloseBrace: Boolean = false
    }
}

/** `SpanEditHandlerBuilder`: the handler the next span gets, as the parsers configure it; [factory] makes the special handlers. */
class SpanEditHandlerBuilder {
    var factory: ((Int) -> SpanEditHandler)? = null

    /** The compiler's `Tokenizer` property: set, it marks the builder as configured for the default handler; kept as a flag. */
    var hasTokenizer: Boolean = false

    fun build(acceptedCharacters: Int): SpanEditHandler = factory?.invoke(acceptedCharacters) ?: SpanEditHandler(acceptedCharacters)

    fun reset() {
        factory = null
        hasTokenizer = false
    }
}

/** `KeywordSet`: the C# keywords and directives an implicit expression stops at. */
class KeywordSet(private val set: Set<String>) {
    val count: Int get() = set.size
    operator fun contains(keyword: String): Boolean = keyword in set
}
