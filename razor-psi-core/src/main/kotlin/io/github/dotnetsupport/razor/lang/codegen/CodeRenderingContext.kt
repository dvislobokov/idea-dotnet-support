package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeVisitor
import io.github.dotnetsupport.razor.lang.intermediate.getAllDiagnostics
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/**
 * `CodeRenderingContext` (`CodeGeneration/CodeRenderingContext.cs`): the state of one rendering — the writer, the stack of node writers
 * (a tag helper or a component swaps its own in for its children), the ancestors, and what the rendering collects: the diagnostics, the
 * source mappings and the line pragmas.
 */
class CodeRenderingContext(
    nodeWriter: IntermediateNodeWriter,
    val sourceDocument: RazorSourceDocument,
    private val documentNode: DocumentIntermediateNode,
    val options: RazorCodeGenerationOptions,
    reportDiagnostics: Boolean = true,
) : AutoCloseable {
    val codeWriter: CodeWriter = CodeWriter(options)

    private val ancestorStack = ArrayList<IntermediateNode>()
    private val scopeStack = ArrayList<IntermediateNodeWriter>()

    private val diagnostics = ArrayList<RazorDiagnostic>()
    private val sourceMappings = ArrayList<SourceMapping>()
    private val linePragmas = ArrayList<LinePragma>()

    private var visitorValue: IntermediateNodeVisitor? = null
    val visitor: IntermediateNodeVisitor get() = visitorValue ?: throw IllegalStateException("The visitor of the rendering context is not set.")

    val documentKind: String? get() = documentNode.documentKind

    init {
        scopeStack.add(nodeWriter)
        if (reportDiagnostics) diagnostics.addAll(documentNode.getAllDiagnostics())
    }

    override fun close() {
        codeWriter.close()
    }

    /** `SetVisitor`: called by the document writer when the context is live. */
    fun setVisitor(visitor: IntermediateNodeVisitor) {
        visitorValue = visitor
    }

    val nodeWriter: IntermediateNodeWriter get() = scopeStack[scopeStack.size - 1]

    val parent: IntermediateNode? get() = ancestorStack.lastOrNull()

    fun addDiagnostic(diagnostic: RazorDiagnostic) {
        diagnostics.add(diagnostic)
    }

    /** `GetDiagnostics`: those of a warning level up to the configured one (level 0 always), ordered (stably) by the start of the span. */
    fun getDiagnostics(): List<RazorDiagnostic> {
        val warningLevel = options.razorWarningLevel
        return diagnostics.filter { it.warningLevel <= warningLevel }.sortedBy { it.span.absoluteIndex }
    }

    fun addSourceMappingFor(node: IntermediateNode) {
        val nodeSource = node.source ?: return
        addSourceMappingFor(nodeSource)
    }

    fun addSourceMappingFor(source: SourceSpan, offset: Int = 0) {
        // No mappings for the nodes of the imports.
        if (sourceDocument.filePath != null && !sourceDocument.filePath.equals(source.filePath, ignoreCase = true)) return

        val location = codeWriter.location
        val currentLocation = location.copy(absoluteIndex = location.absoluteIndex + offset, characterIndex = location.characterIndex + offset)
        val endCharacterIndex = if (source.lineCount == 0) currentLocation.characterIndex + source.length else source.endCharacterIndex

        val generatedLocation = SourceSpan(
            currentLocation.filePath, currentLocation.absoluteIndex, currentLocation.lineIndex, currentLocation.characterIndex, source.length,
            lineCount = source.lineCount, endCharacterIndex = endCharacterIndex,
        )
        sourceMappings.add(SourceMapping(source, generatedLocation))
    }

    /** `GetSourceMappings`: `ToImmutableAndClear`. */
    fun getSourceMappings(): List<SourceMapping> = sourceMappings.toList().also { sourceMappings.clear() }

    fun renderChildren(node: IntermediateNode) {
        ancestorStack.add(node)
        var i = 0
        while (i < node.children.size) {
            visitor.visit(node.children[i])
            i++
        }
        ancestorStack.removeAt(ancestorStack.size - 1)
    }

    fun renderChildren(node: IntermediateNode, writer: IntermediateNodeWriter) {
        scopeStack.add(writer)
        ancestorStack.add(node)
        var i = 0
        while (i < node.children.size) {
            visitor.visit(node.children[i])
            i++
        }
        ancestorStack.removeAt(ancestorStack.size - 1)
        scopeStack.removeAt(scopeStack.size - 1)
    }

    fun renderNode(node: IntermediateNode) {
        visitor.visit(node)
    }

    fun renderNode(node: IntermediateNode, writer: IntermediateNodeWriter) {
        scopeStack.add(writer)
        visitor.visit(node)
        scopeStack.removeAt(scopeStack.size - 1)
    }

    fun addLinePragma(linePragma: LinePragma) {
        linePragmas.add(linePragma)
    }

    /** `GetLinePragmas`: `ToImmutableAndClear`. */
    fun getLinePragmas(): List<LinePragma> = linePragmas.toList().also { linePragmas.clear() }

    fun pushAncestor(node: IntermediateNode) {
        ancestorStack.add(node)
    }

    // CodeRenderingContextExtensions.cs

    /** `BuildLinePragma`: a `#line N "path"` scope; none without a span with a file path. */
    fun buildLinePragma(span: SourceSpan?, suppressLineDefaultAndHidden: Boolean = false): LinePragmaScope =
        if (span != null && !span.filePath.isNullOrEmpty()) LinePragmaScope.standard(this, span, suppressLineDefaultAndHidden) else LinePragmaScope.NONE

    /** `BuildEnhancedLinePragma`: a `#line (l,c)-(l,c) offset "path"` scope with its source mapping; none without a span with a file path. */
    fun buildEnhancedLinePragma(span: SourceSpan?, characterOffset: Int = 0, suppressLineDefaultAndHidden: Boolean = false): LinePragmaScope =
        if (span != null && !span.filePath.isNullOrEmpty()) LinePragmaScope.enhanced(this, span, characterOffset, suppressLineDefaultAndHidden) else LinePragmaScope.NONE

    /**
     * `CodeRenderingContextExtensions.LinePragmaScope`: the compiler's `ref struct` disposed by `using`; [close] writes the end of the
     * region (`#line default`, `#line hidden`, `#nullable disable`), records the [LinePragma] and restores the indent.
     */
    class LinePragmaScope private constructor(
        private val context: CodeRenderingContext?,
        private val span: SourceSpan?,
        private val isEnhanced: Boolean,
        private val originalIndent: Int,
        private val suppressLineDefaultAndHidden: Boolean,
    ) : AutoCloseable {
        override fun close() {
            // The None scope.
            val context = context ?: return
            val span = span!!
            val writer = context.codeWriter
            val options = context.options

            writer.ensureNewLine()

            // Always at least one empty line to separate the code from the pragmas.
            writer.writeLine()

            context.addLinePragma(LinePragma(isEnhanced, span.filePath!!, span.lineIndex, endLineIndex = writer.location.lineIndex))

            if (!suppressLineDefaultAndHidden) {
                writer
                    .writeLine("#line default")
                    .writeLine("#line hidden")
            }

            if (!options.suppressNullabilityEnforcement) writer.writeLine("#nullable disable")

            writer.currentIndent = originalIndent
        }

        companion object {
            val NONE = LinePragmaScope(null, null, false, 0, false)

            fun standard(context: CodeRenderingContext, span: SourceSpan, suppressLineDefaultAndHidden: Boolean): LinePragmaScope {
                val writer = context.codeWriter
                val (originalIndent, ensurePathBackSlashes) = writePreamble(writer, context.options)
                writer.writeLineNumberDirective(span, ensurePathBackSlashes)
                return LinePragmaScope(context, span, isEnhanced = false, originalIndent, suppressLineDefaultAndHidden)
            }

            fun enhanced(context: CodeRenderingContext, span: SourceSpan, characterOffset: Int, suppressLineDefaultAndHidden: Boolean): LinePragmaScope {
                val writer = context.codeWriter
                val options = context.options

                if (!options.useEnhancedLinePragma) {
                    val standardScope = standard(context, span, suppressLineDefaultAndHidden)
                    // An enhanced directive asked for, a standard one written: the padding and the mapping the enhanced one would not need.
                    writer.writePadding(offset = 0, span, context)
                    context.addSourceMappingFor(span)
                    return standardScope
                }

                val (originalIndent, ensurePathBackSlashes) = writePreamble(writer, options)
                writer.writeEnhancedLineNumberDirective(span, characterOffset, ensurePathBackSlashes)
                context.addSourceMappingFor(span, characterOffset)
                return LinePragmaScope(context, span, isEnhanced = true, originalIndent, suppressLineDefaultAndHidden)
            }

            private fun writePreamble(writer: CodeWriter, options: RazorCodeGenerationOptions): Pair<Int, Boolean> {
                // The directives are written at indent 0.
                val originalIndent = writer.currentIndent
                writer.currentIndent = 0

                writer.ensureNewLine()

                if (!options.suppressNullabilityEnforcement) writer.writeLine("#nullable restore")

                val ensurePathBackSlashes = options.remapLinePragmaPathsOnWindows && PlatformInformation.isWindows
                return originalIndent to ensurePathBackSlashes
            }
        }
    }
}

/** `PlatformInformation`. */
object PlatformInformation {
    val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")
}
