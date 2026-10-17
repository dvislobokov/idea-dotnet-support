package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorDocumentSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorNode
import io.github.dotnetsupport.razor.lang.syntax.RazorParserOptions

/**
 * `RazorSyntaxTree`: the document and the diagnostics of the parse (the parsers' sink and the nodes' own, by position). [source] is the
 * source document the engine parsed (null for a bare parse); [redRoot] the red tree over [root] the lowering phase walks (R3b).
 */
class RazorSyntaxTree(
    val root: RazorDocumentSyntax, val text: CharSequence, val options: RazorParserOptions, private val parseDiagnostics: List<RazorDiagnostic>,
    val source: io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument? = null,
) {
    val redRoot: io.github.dotnetsupport.razor.lang.syntax.red.RazorDocumentSyntax by lazy(LazyThreadSafetyMode.NONE) {
        io.github.dotnetsupport.razor.lang.syntax.red.RedTree.of(root) as io.github.dotnetsupport.razor.lang.syntax.red.RazorDocumentSyntax
    }

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
    /** The engine's parse: the tree remembers [source] for the lowering phase. */
    fun parse(source: io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument, options: RazorParserOptions): RazorSyntaxTree =
        parse(source.text, options, source.filePath, source)

    /**
     * [applyPasses]: the engine's syntax tree passes, in their order (the tree the tooling and the code generator see); the engine of
     * R3b runs them itself as features (`DefaultRazorSyntaxTreePhase`) and parses with false.
     */
    fun parse(
        text: CharSequence, options: RazorParserOptions, filePath: String? = null, source: io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument? = null,
        applyPasses: Boolean = true,
    ): RazorSyntaxTree {
        val context = ParserContext(text, options, filePath)
        val codeParser = CSharpCodeParser(options.directives, context)
        val markupParser = HtmlMarkupParser(context)
        codeParser.htmlParser = markupParser
        markupParser.codeParser = codeParser
        val root = markupParser.parseDocument()
        val diagnostics = context.errorSink.getErrorsAndClear()
        if (applyPasses) {
            RazorSyntaxTreePasses.verifyNestedSections(root, options, context.source)
            RazorSyntaxTreePasses.rewriteWhitespace(root)
        }
        return RazorSyntaxTree(root, text, options, diagnostics, source)
    }
}
