package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorDocumentSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorNode
import io.github.dotnetsupport.razor.lang.syntax.RazorParserOptions

/** `RazorSyntaxTree`: the document and the diagnostics of the parse (the parsers' sink and the nodes' own, by position). */
class RazorSyntaxTree(val root: RazorDocumentSyntax, val text: CharSequence, val options: RazorParserOptions, private val parseDiagnostics: List<RazorDiagnostic>) {
    val diagnostics: List<RazorDiagnostic> by lazy(LazyThreadSafetyMode.NONE) {
        val all = LinkedHashSet<DiagnosticKey>()
        val result = ArrayList<RazorDiagnostic>()
        fun add(d: RazorDiagnostic) {
            if (all.add(DiagnosticKey(d))) result += d
        }
        parseDiagnostics.forEach(::add)
        collect(root, ::add)
        result.sortedBy { it.span.absoluteIndex }
    }

    private fun collect(node: RazorNode, add: (RazorDiagnostic) -> Unit) {
        node.diagnostics.forEach(add)
        for (i in 0 until node.slotCount) node.slot(i)?.let { collect(it, add) }
    }

    /** `RazorDiagnostic.Equals`: the descriptor, the span and the arguments. */
    private data class DiagnosticKey(val id: String, val span: io.github.dotnetsupport.razor.lang.syntax.SourceSpan, val args: List<Any?>) {
        constructor(d: RazorDiagnostic) : this(d.id, d.span, d.args)
    }
}

/** `RazorParser`: the entry — the markup parser drives, the code parser is its partner. */
object RazorParser {
    fun parse(text: CharSequence, options: RazorParserOptions, filePath: String? = null): RazorSyntaxTree {
        val context = ParserContext(text, options, filePath)
        val codeParser = CSharpCodeParser(options.directives, context)
        val markupParser = HtmlMarkupParser(context)
        codeParser.htmlParser = markupParser
        markupParser.codeParser = codeParser
        val root = markupParser.parseDocument()
        val diagnostics = context.errorSink.getErrorsAndClear()
        // the engine's syntax tree passes, in their order: the tree the tooling and the code generator see
        RazorSyntaxTreePasses.verifyNestedSections(root, options, context.source)
        RazorSyntaxTreePasses.rewriteWhitespace(root)
        return RazorSyntaxTree(root, text, options, diagnostics)
    }
}
