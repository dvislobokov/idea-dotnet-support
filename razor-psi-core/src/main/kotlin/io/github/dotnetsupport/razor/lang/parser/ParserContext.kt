package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.razor.lang.syntax.AcceptedCharacters
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorParserOptions

/** `ErrorSink`: the diagnostics of a parse, or of one directive while its scope is pushed ([ParserContext.pushNewErrorScope]). */
class ErrorSink {
    private var errors: ArrayList<RazorDiagnostic>? = null

    fun onError(error: RazorDiagnostic) {
        (errors ?: ArrayList<RazorDiagnostic>().also { errors = it }).add(error)
    }

    fun getErrorsAndClear(): List<RazorDiagnostic> {
        val result = errors ?: return emptyList()
        errors = null
        return result
    }
}

/** `ParserContext`: the source, the options and the state the two parsers share while they hand the document to each other. */
class ParserContext(val text: CharSequence, val options: RazorParserOptions, val filePath: String? = null) {
    val source = SeekableTextReader(text, filePath)
    private val errorSinkStack = ArrayDeque<ErrorSink>().apply { addLast(ErrorSink()) }
    val seenDirectives = HashSet<String>()

    var whiteSpaceIsSignificantToAncestorBlock = false
    var nullGenerateWhitespaceAndNewLine = false
    var inTemplateContext = false
    var startOfLine = true
    var makeMarkerNode = true
    var currentAcceptedCharacters: Int = AcceptedCharacters.ANY

    val errorSink: ErrorSink get() = errorSinkStack.last()
    val isEndOfFile: Boolean get() = source.peek() == -1

    fun pushNewErrorScope(sink: ErrorSink) {
        errorSinkStack.addLast(sink)
    }

    fun popErrorScope() {
        errorSinkStack.removeLast()
    }

    inline fun <R> withErrorScope(sink: ErrorSink, block: () -> R): R {
        pushNewErrorScope(sink)
        try {
            return block()
        } finally {
            popErrorScope()
        }
    }
}
