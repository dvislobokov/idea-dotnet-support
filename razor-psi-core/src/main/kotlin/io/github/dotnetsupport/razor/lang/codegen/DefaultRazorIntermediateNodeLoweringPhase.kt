package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.lang.codegen.components.ComponentDiagnosticFactory
import io.github.dotnetsupport.razor.lang.codegen.extensions.TemplateIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DirectiveTokenIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeBuilder
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeFactory
import io.github.dotnetsupport.razor.lang.intermediate.MalformedDirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MarkupElementIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperHtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UnresolvedAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UnresolvedAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UnresolvedElementIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UnresolvedExpressionAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UsingDirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.findDescendantReferences
import io.github.dotnetsupport.razor.lang.parser.RazorSyntaxTree
import io.github.dotnetsupport.razor.lang.syntax.AttributeStructure
import io.github.dotnetsupport.razor.lang.syntax.ChunkGenerator
import io.github.dotnetsupport.razor.lang.syntax.DirectiveDescriptor
import io.github.dotnetsupport.razor.lang.syntax.DirectiveKind
import io.github.dotnetsupport.razor.lang.syntax.DirectiveUsage
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticDescriptor
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticSeverity
import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind
import io.github.dotnetsupport.razor.lang.syntax.RazorList
import io.github.dotnetsupport.razor.lang.syntax.RazorParserOptions
import io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind
import io.github.dotnetsupport.razor.lang.syntax.RazorToken
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan
import io.github.dotnetsupport.razor.lang.syntax.SpanEditHandler
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDirectives
import io.github.dotnetsupport.razor.lang.syntax.red.BaseRazorDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpExplicitExpressionSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpExpressionLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpImplicitExpressionSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpStatementLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpStatementSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpTemplateBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupAttributeBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupCommentBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupDynamicAttributeValueSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupElementSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupEndTagSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupEphemeralTextLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupLiteralAttributeValueSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupMinimizedAttributeBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupStartTagSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupTextLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RazorDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RazorDocumentationDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RazorUsingDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RedNode
import io.github.dotnetsupport.razor.lang.syntax.red.RedSyntaxWalker
import io.github.dotnetsupport.razor.lang.syntax.red.RedToken
import io.github.dotnetsupport.razor.lang.syntax.red.createRed
import io.github.dotnetsupport.razor.lang.syntax.red.directiveBody
import io.github.dotnetsupport.razor.lang.syntax.red.getTagNameWithOptionalBang
import io.github.dotnetsupport.razor.lang.syntax.red.isSelfClosing
import io.github.dotnetsupport.razor.lang.syntax.red.isVoidElement
import io.github.dotnetsupport.razor.lang.syntax.red.legacyChildren
import io.github.dotnetsupport.razor.lang.syntax.CSharpExpressionLiteralSyntax as GreenCSharpExpressionLiteral
import io.github.dotnetsupport.razor.lang.syntax.MarkupTextLiteralSyntax as GreenMarkupTextLiteral

/**
 * `DefaultRazorIntermediateNodeLoweringPhase`: the syntax tree (and the imports) lowered to the intermediate tree — directives, C# code
 * and expressions, HTML content and attributes; in a legacy file every element is an [UnresolvedElementIntermediateNode] (any element
 * could be a tag helper), in a component every element could be a component: the tag helper resolution phase settles them.
 */
class DefaultRazorIntermediateNodeLoweringPhase : RazorEnginePhaseBase() {
    override fun executeCore(codeDocument: RazorCodeDocument): RazorCodeDocument {
        val syntaxTree = throwForMissingDocumentDependency(codeDocument.getSyntaxTree())
        val documentNode = DocumentIntermediateNode()
        val builder = IntermediateNodeBuilder.create(documentNode)
        documentNode.options = codeDocument.codeGenerationOptions

        val usingReferences = ArrayList<UsingReference>()
        val imports = codeDocument.getImportSyntaxTrees()
        val importedUsings = ArrayList<UsingReference>()
        if (imports.isNotEmpty()) importDirectives(documentNode, builder, syntaxTree.options, imports, importedUsings)

        val source = syntaxTree.source ?: RazorSourceDocument(syntaxTree.text, null)
        if (codeDocument.fileKind == RazorFileKind.ComponentImport && syntaxTree.options.allowComponentFileKind) {
            ComponentImportFileKindVisitor(documentNode, builder, usingReferences, syntaxTree.options).also { it.sourceDocument = source }.visit(syntaxTree.redRoot)
        } else if (codeDocument.fileKind.isComponent && syntaxTree.options.allowComponentFileKind) {
            ComponentFileKindVisitor(documentNode, builder, usingReferences, syntaxTree.options).also { it.sourceDocument = source }.visit(syntaxTree.redRoot)
        } else {
            LegacyFileKindVisitor(documentNode, builder, usingReferences, syntaxTree.options).also { it.sourceDocument = source }.visit(syntaxTree.redRoot)
        }

        for (j in importedUsings.indices.reversed()) {
            val importedUsing = importedUsings[j]
            val trimmed = tryRemoveGlobalPrefixFromDefaultUsing(importedUsing)
            if (!usingReferences.contains(importedUsing) && (trimmed == null || usingReferences.none { it.namespace == trimmed })) {
                usingReferences.add(0, importedUsing)
            }
        }

        var index = 0
        var lastDirective: UsingDirectiveIntermediateNode? = null
        for (reference in usingReferences) {
            val using = UsingDirectiveIntermediateNode().also {
                it.content = reference.namespace
                it.source = reference.source
                it.hasExplicitSemicolon = reference.hasExplicitSemicolon
            }
            builder.insert(index++, using)
            lastDirective = using
        }
        if (lastDirective != null) lastDirective.appendLineDefaultAndHidden = true

        postProcessImportedDirectives(documentNode)

        for (diagnostic in syntaxTree.diagnostics) documentNode.addDiagnostic(diagnostic)
        for (import in imports) for (diagnostic in import.diagnostics) documentNode.addDiagnostic(diagnostic)

        return codeDocument.withDocumentNode(documentNode)
    }

    /** `TryRemoveGlobalPrefixFromDefaultUsing`: the default import (no file path) written `global::X` matches a `@using X` of the file. */
    private fun tryRemoveGlobalPrefixFromDefaultUsing(usingReference: UsingReference): String? {
        val globalPrefix = "global::"
        val source = usingReference.source
        return if (source != null && source.filePath == null && usingReference.namespace.startsWith(globalPrefix)) usingReference.namespace.substring(globalPrefix.length) else null
    }

    private fun importDirectives(document: DocumentIntermediateNode, builder: IntermediateNodeBuilder, options: RazorParserOptions, imports: List<RazorSyntaxTree>, usings: MutableList<UsingReference>) {
        val importsVisitor = ImportsVisitor(document, builder, usings, options)
        for (import in imports) {
            importsVisitor.sourceDocument = import.source ?: RazorSourceDocument(import.text, null)
            importsVisitor.visit(import.redRoot)
        }
    }

    private fun postProcessImportedDirectives(document: DocumentIntermediateNode) {
        val seenDirectives = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<DirectiveDescriptor, Boolean>())
        val references = document.findDescendantReferences<DirectiveIntermediateNode>()
        for (i in references.indices.reversed()) {
            val reference = references[i]
            val directive = reference.node
            val descriptor = directive.directive ?: continue
            val seenDirective = !seenDirectives.add(descriptor)
            if (!directive.isImported) continue
            when (descriptor.kind) {
                DirectiveKind.SingleLine -> {
                    if (seenDirective && descriptor.usage == DirectiveUsage.FileScopedSinglyOccurring) {
                        // falls through to the removal below
                    } else continue
                }
                DirectiveKind.RazorBlock, DirectiveKind.CodeBlock -> {
                    if (descriptor.usage == DirectiveUsage.FileScopedSinglyOccurring) {
                        document.addDiagnostic(LoweringDiagnostics.createDirective_BlockDirectiveCannotBeImported(descriptor.directive))
                    }
                }
            }
            reference.remove()
        }
    }

    /** `UsingReference`: a `using` of the document or an import, equal by namespace. */
    class UsingReference(val namespace: String, val source: SourceSpan?, val hasExplicitSemicolon: Boolean) {
        override fun equals(other: Any?): Boolean = other is UsingReference && other.namespace == namespace
        override fun hashCode(): Int = namespace.hashCode()
    }

    /** `ChildNodesHelper`: the children from [start] on, with the casts the lowering needs. */
    private class ChildNodesHelper(private val list: List<RedNode>, private val start: Int = 0) {
        val count: Int get() = maxOf(list.size - start, 0)
        operator fun get(index: Int): RedNode = list[start + index]
        fun skip(count: Int): ChildNodesHelper = ChildNodesHelper(list, start + count)
        fun firstOrDefault(): RedNode? = if (count > 0) this[0] else null

        /** `TryCast<TNode>`: every remaining child is a [T] (an empty list counts), else null. */
        inline fun <reified T : RedNode> tryCast(): List<T>? {
            val result = ArrayList<T>(count)
            for (i in start until list.size) {
                val node = list[i] as? T ?: return null
                result.add(node)
            }
            return result
        }
    }

    open class LoweringVisitor(
        protected val document: DocumentIntermediateNode,
        protected val builder: IntermediateNodeBuilder,
        protected val usings: MutableList<UsingReference>,
        protected val options: RazorParserOptions,
    ) : RedSyntaxWalker() {
        protected var insideUnresolvedAttribute: Boolean = false

        lateinit var sourceDocument: RazorSourceDocument

        override fun visitRazorUsingDirective(node: RazorUsingDirectiveSyntax) = visitDirective(node, null)
        override fun visitRazorDirective(node: RazorDirectiveSyntax) = visitDirective(node, node.directiveDescriptor)
        override fun visitRazorDocumentationDirective(node: RazorDocumentationDirectiveSyntax) = visitDirective(node, node.directiveDescriptor)

        private fun visitDirective(node: BaseRazorDirectiveSyntax, descriptor: DirectiveDescriptor?) {
            if (descriptor != null) {
                val diagnostics = node.getDiagnostics()
                // This is an extensible directive.
                val directiveNode: IntermediateNode = if (isMalformed(diagnostics)) {
                    MalformedDirectiveIntermediateNode().also {
                        it.directiveName = descriptor.directive
                        it.directive = descriptor
                        it.source = buildSourceSpanFromNode(node)
                    }
                } else {
                    DirectiveIntermediateNode().also {
                        it.directiveName = descriptor.directive
                        it.directive = descriptor
                        it.source = buildSourceSpanFromNode(node)
                    }
                }
                for (diagnostic in diagnostics) directiveNode.addDiagnostic(diagnostic)
                builder.push(directiveNode)
            }

            if (node is RazorDocumentationDirectiveSyntax) {
                val content = node.body.descendantNodes().filterIsInstance<CSharpStatementLiteralSyntax>().firstOrNull()
                builder.add(DocumentationIntermediateNode(
                    content = content?.getContent() ?: "",
                    isValid = content != null && !isMalformed(node.getDiagnostics()),
                ).also { it.source = if (content == null) null else buildSourceSpanFromNode(content) })
            } else {
                visit(node.body)
            }

            if (descriptor != null) builder.pop()
        }

        override fun visitCSharpStatementLiteral(node: CSharpStatementLiteralSyntax) {
            when (val generator = node.chunkGenerator) {
                null -> {
                    super.visitCSharpStatementLiteral(node)
                    return
                }
                is ChunkGenerator.DirectiveToken -> builder.add(DirectiveTokenIntermediateNode().also {
                    it.content = node.getContent()
                    it.directiveToken = generator.descriptor
                    it.source = buildSourceSpanFromNode(node)
                })
                is ChunkGenerator.AddImport -> usings.add(UsingReference(generator.namespace, buildSourceSpanFromNode(node), generator.hasExplicitSemicolon))
                is ChunkGenerator.AddTagHelper -> tagHelperDirective(node, TagHelperDirectives.addTagHelper, generator.diagnostics, generator.lookupText)
                is ChunkGenerator.RemoveTagHelper -> tagHelperDirective(node, TagHelperDirectives.removeTagHelper, generator.diagnostics, generator.lookupText)
                is ChunkGenerator.TagHelperPrefix -> tagHelperDirective(node, TagHelperDirectives.tagHelperPrefix, generator.diagnostics, generator.prefix)
                else -> {}
            }
            super.visitCSharpStatementLiteral(node)
        }

        /** `@addTagHelper` / `@removeTagHelper` / `@tagHelperPrefix`: a directive node with one token, malformed when the generator carries an error. */
        private fun tagHelperDirective(node: CSharpStatementLiteralSyntax, descriptor: DirectiveDescriptor, diagnostics: List<RazorDiagnostic>, content: String) {
            val directiveNode: IntermediateNode = if (isMalformed(diagnostics)) {
                MalformedDirectiveIntermediateNode().also {
                    it.directiveName = descriptor.directive
                    it.directive = descriptor
                    it.source = buildSourceSpanFromNode(node)
                }
            } else {
                DirectiveIntermediateNode().also {
                    it.directiveName = descriptor.directive
                    it.directive = descriptor
                    it.source = buildSourceSpanFromNode(node)
                }
            }
            for (diagnostic in diagnostics) directiveNode.addDiagnostic(diagnostic)
            builder.push(directiveNode)
            builder.add(DirectiveTokenIntermediateNode().also {
                it.content = content
                it.directiveToken = descriptor.tokens[0]
                it.source = buildSourceSpanFromNode(node)
            })
            builder.pop()
        }

        protected fun buildSourceSpanFromNode(node: RedNode?): SourceSpan? = node?.getSourceSpan(sourceDocument)

        /** The "not valid in a component" diagnostic reads best over the whole directive: the span of the directive the literal is in. */
        protected fun buildTagHelperDirectiveSourceSpan(node: CSharpStatementLiteralSyntax): SourceSpan? {
            val directive = node.firstAncestorOrSelf { it is BaseRazorDirectiveSyntax }
            return buildSourceSpanFromNode(directive ?: node)
        }

        protected fun inferAttributeStructure(node: MarkupAttributeBlockSyntax): AttributeStructure {
            if (node.equalsToken == null && node.value == null) return AttributeStructure.Minimized
            val lastToken = node.getLastToken()
            if (lastToken != null) {
                if (lastToken.content == "\"") return AttributeStructure.DoubleQuotes
                if (lastToken.content == "'") return AttributeStructure.SingleQuotes
            }
            // Has an equals sign but no value/quotes (e.g. `type=`). Treated as DoubleQuotes by convention.
            return AttributeStructure.DoubleQuotes
        }

        /** `MergeTokenLists`: the green tokens of the lists that are there, in order. */
        protected fun mergeTokenLists(vararg lists: List<RedToken>?): List<RazorToken> {
            val result = ArrayList<RazorToken>()
            for (list in lists) if (list != null) for (token in list) result.add(token.token)
            return result
        }

        protected fun tokenListOf(token: RedToken?): List<RedToken> = if (token == null) emptyList() else listOf(token)

        /** `MergeAttributeValue`: the prefix and the value of a literal attribute value as one text literal, with the markup chunk generator when it had a handler. */
        protected fun mergeAttributeValue(node: MarkupLiteralAttributeValueSyntax): MarkupTextLiteralSyntax {
            val valueTokens = mergeTokenLists(node.prefix?.literalTokens, node.value?.literalTokens)
            val template = node.prefix ?: node.value
            val generator = template?.chunkGenerator
            val handler = template?.editHandler
            val green = if (handler != null) GreenMarkupTextLiteral(RazorList(valueTokens), ChunkGenerator.Markup, handler) else GreenMarkupTextLiteral(RazorList(valueTokens), generator, handler)
            return green.createRed(node, node.position) as MarkupTextLiteralSyntax
        }

        override fun visitMarkupDynamicAttributeValue(node: MarkupDynamicAttributeValueSyntax) {
            var containsExpression = false
            for (child in node.descendantNodes { it.parent !is io.github.dotnetsupport.razor.lang.syntax.red.CSharpCodeBlockSyntax }) {
                if (child is CSharpImplicitExpressionSyntax || child is CSharpExplicitExpressionSyntax) {
                    containsExpression = true
                    break
                }
            }

            if (insideUnresolvedAttribute) {
                val unresolvedNode = UnresolvedExpressionAttributeValueIntermediateNode().also {
                    it.prefix = node.prefix?.getContent() ?: ""
                    it.containsExpression = containsExpression
                    it.source = buildSourceSpanFromNode(node)
                }
                builder.push(unresolvedNode)
                visit(node.value)
                builder.pop()
                return
            }

            if (containsExpression) {
                builder.push(CSharpExpressionAttributeValueIntermediateNode().also {
                    it.prefix = node.prefix?.getContent() ?: ""
                    it.source = buildSourceSpanFromNode(node)
                })
            } else {
                builder.push(CSharpCodeAttributeValueIntermediateNode().also {
                    it.prefix = node.prefix?.getContent() ?: ""
                    it.source = buildSourceSpanFromNode(node)
                })
            }
            visit(node.value)
            builder.pop()
        }

        override fun visitMarkupLiteralAttributeValue(node: MarkupLiteralAttributeValueSyntax) {
            if (insideUnresolvedAttribute) {
                val unresolvedNode = UnresolvedAttributeValueIntermediateNode().also {
                    it.prefix = node.prefix?.getContent() ?: ""
                    it.source = buildSourceSpanFromNode(node)
                }
                unresolvedNode.children.add(IntermediateNodeFactory.htmlToken(node, { it.value?.getContent() ?: "" }, buildSourceSpanFromNode(node.value)))
                builder.add(unresolvedNode)
                return
            }

            builder.push(HtmlAttributeValueIntermediateNode().also {
                it.prefix = node.prefix?.getContent() ?: ""
                it.source = buildSourceSpanFromNode(node)
            })
            builder.add(IntermediateNodeFactory.htmlToken(node, { it.value?.getContent() ?: "" }, buildSourceSpanFromNode(node.value)))
            builder.pop()
        }

        override fun visitCSharpTemplateBlock(node: CSharpTemplateBlockSyntax) {
            val templateNode = TemplateIntermediateNode()
            builder.push(templateNode)
            super.visitCSharpTemplateBlock(node)
            builder.pop()
            computeSourceSpanFromChildren(templateNode)
        }

        override fun visitCSharpExpressionLiteral(node: CSharpExpressionLiteralSyntax) {
            if (builder.current is TagHelperHtmlAttributeIntermediateNode) {
                // If we are top level in a tag helper HTML attribute, we want to be rendered as markup.
                // This case happens for duplicate non-string bound attributes. They would be initially be categorized as
                // CSharp but since they are duplicate, they should just be markup.
                val markupLiteral = GreenMarkupTextLiteral(RazorList(node.literalTokens.map { it.token }), null, null).createRed(node.parent, node.position)
                visit(markupLiteral)
                return
            }
            builder.add(IntermediateNodeFactory.csharpToken(node, { it.getContent() }, buildSourceSpanFromNode(node)))
            super.visitCSharpExpressionLiteral(node)
        }

        /** `VisitAttributeValue`: the children of an attribute value merged into one literal of their kind, the `@@` escape joined to them. */
        protected fun visitAttributeValue(node: RedNode?) {
            if (node == null) return
            var children = ChildNodesHelper(node.childNodesAndTokens())
            var position = node.position
            var escapedAtTokens: List<RedToken> = emptyList()
            val first = children.firstOrDefault()
            if (first is MarkupBlockSyntax && first.children.size == 2 && first.children[0] is MarkupTextLiteralSyntax && first.children[1] is MarkupEphemeralTextLiteralSyntax) {
                // This is a special case when we have an attribute like attr="@@foo".
                // Extract the literal @ token from the first child so it can be merged with the rest of the attribute value.
                // The ephemeral @ token is ignored.
                escapedAtTokens = (first.children[0] as MarkupTextLiteralSyntax).literalTokens
                children = children.skip(1)
                position = if (children.count > 0) children[0].position else position
            }

            val attributeLiteralArray = children.tryCast<MarkupLiteralAttributeValueSyntax>()
            if (attributeLiteralArray != null) {
                val tokens = ArrayList<RazorToken>()
                escapedAtTokens.forEach { tokens.add(it.token) }
                for (literal in attributeLiteralArray) tokens.addAll(mergeAttributeValue(literal).literalTokens.map { it.token })
                visit(GreenMarkupTextLiteral(RazorList(tokens), null, null).createRed(node.parent, position))
                return
            }
            val markupLiteralArray = children.tryCast<MarkupTextLiteralSyntax>()
            if (markupLiteralArray != null) {
                val tokens = ArrayList<RazorToken>()
                escapedAtTokens.forEach { tokens.add(it.token) }
                for (literal in markupLiteralArray) tokens.addAll(literal.literalTokens.map { it.token })
                visit(GreenMarkupTextLiteral(RazorList(tokens), null, null).createRed(node.parent, position))
                return
            }
            val expressionLiteralArray = children.tryCast<CSharpExpressionLiteralSyntax>()
            if (expressionLiteralArray != null) {
                val tokens = ArrayList<RazorToken>()
                escapedAtTokens.forEach { tokens.add(it.token) }
                var editHandler: SpanEditHandler? = null
                var generator: ChunkGenerator? = null
                for (literal in expressionLiteralArray) {
                    generator = literal.chunkGenerator
                    editHandler = literal.editHandler
                    tokens.addAll(literal.literalTokens.map { it.token })
                }
                visit(GreenCSharpExpressionLiteral(RazorList(tokens), generator, editHandler).createRed(node.parent, position))
                return
            }
            if (escapedAtTokens.isNotEmpty()) {
                // If we have escaped @ tokens but no other content to merge with, create a MarkupTextLiteral just for the escaped @ tokens
                visit(GreenMarkupTextLiteral(RazorList(escapedAtTokens.map { it.token }), null, null).createRed(node.parent, position))
            } else {
                visit(node)
            }
        }

        protected fun combine(node: HtmlContentIntermediateNode, item: RedNode) {
            node.children.add(IntermediateNodeFactory.htmlToken(item, { it.getContent() }, buildSourceSpanFromNode(item)))
            val source = node.source
            if (source != null) {
                node.source = SourceSpan(source.filePath, source.absoluteIndex, source.lineIndex, source.characterIndex, source.length + item.width, source.lineCount, source.endCharacterIndex)
            }
        }

        /** The span of just the attribute value content (between the quotes); an empty value and `name=` give a zero-width span. */
        protected fun computeAttributeValueSourceSpan(node: MarkupAttributeBlockSyntax): SourceSpan? {
            val value = node.value
            if (value != null) {
                val valueStart = node.valuePrefix?.endPosition ?: value.position
                val valueEnd = node.valueSuffix?.position ?: value.endPosition
                val valueLength = valueEnd - valueStart
                if (valueLength > 0) {
                    val location = sourceDocument.linePosition(valueStart)
                    val endLocation = sourceDocument.linePosition(valueEnd)
                    return SourceSpan(sourceDocument.filePath, valueStart, location.line, location.character, valueLength, endLocation.line - location.line, endLocation.character)
                } else {
                    val emptyPos = value.position
                    val location = sourceDocument.linePosition(emptyPos)
                    return SourceSpan(sourceDocument.filePath, emptyPos, location.line, location.character, 0, 0, location.character)
                }
            } else if (node.equalsToken != null) {
                val valueStart = node.valuePrefix?.endPosition ?: node.equalsToken!!.endPosition
                val location = sourceDocument.linePosition(valueStart)
                return SourceSpan(sourceDocument.filePath, valueStart, location.line, location.character, 0, 0, location.character)
            }
            return null
        }

        /** `ExtractAttributeData`: the attribute names and values of a start tag, for the tag helper matching. */
        protected fun extractAttributeData(node: MarkupElementSyntax): List<Pair<String, String>> {
            val result = ArrayList<Pair<String, String>>()
            val startTag = node.markupStartTag ?: return result
            for (attr in startTag.attributes) {
                when (attr) {
                    is MarkupAttributeBlockSyntax -> result.add(attr.name.getContent() to (attr.value?.getContent() ?: ""))
                    is MarkupMinimizedAttributeBlockSyntax -> result.add(attr.name.getContent() to "")
                    else -> {}
                }
            }
            return result
        }

        /** `ComputeSourceSpanFromChildren`: the first child's position, the children's lengths summed. */
        protected fun computeSourceSpanFromChildren(node: IntermediateNode) {
            if (node.children.isNotEmpty()) {
                val sourceRangeStart = node.children.firstOrNull { it.source != null }?.source
                if (sourceRangeStart != null) {
                    val contentLength = node.children.sumOf { it.source?.length ?: 0 }
                    node.source = SourceSpan(
                        sourceRangeStart.filePath ?: sourceDocument.filePath, sourceRangeStart.absoluteIndex, sourceRangeStart.lineIndex, sourceRangeStart.characterIndex,
                        contentLength, sourceRangeStart.lineCount, sourceRangeStart.endCharacterIndex,
                    )
                }
            }
        }
    }

    /**
     * `LegacyFileKindVisitor`: .cshtml files (MVC views, Razor Pages). Treats HTML markup as text content (merged tokens) and supports
     * tag helpers: elements inside potential tag helpers are unresolved.
     */
    class LegacyFileKindVisitor(document: DocumentIntermediateNode, builder: IntermediateNodeBuilder, usings: MutableList<UsingReference>, options: RazorParserOptions) :
        LoweringVisitor(document, builder, usings, options) {
        private var insideUnresolvedElement = false

        override fun visitMarkupElement(node: MarkupElementSyntax) {
            // Markup transitions (e.g., @: or <text>) are not tag helpers and should fall through to the base visitor. We only check
            // StartTag here because legacy files don't support markup transitions as end tags -- that scenario is only relevant for
            // component files (handled by ComponentFileKindVisitor).
            val startTag = node.markupStartTag
            if (startTag != null && startTag.isMarkupTransition) {
                super.visitMarkupElement(node)
                return
            }

            val endTag = node.markupEndTag
            val tagName = startTag?.name?.content ?: endTag?.name?.content ?: ""
            val attributeData = extractAttributeData(node)

            var isSelfClosing = false
            if (startTag != null) {
                val lastToken = startTag.getLastToken()
                isSelfClosing = lastToken?.parent?.getContent()?.endsWith("/>") ?: false
            }

            val element = UnresolvedElementIntermediateNode().also {
                it.tagName = tagName
                it.source = buildSourceSpanFromNode(node)
                it.isComponent = false
                it.isEscaped = startTag?.bang.let { bang -> bang != null && bang.width > 0 }
                it.isSelfClosing = isSelfClosing
                it.hasEndTag = endTag != null
                it.endTagName = endTag?.getTagNameWithOptionalBang()
                it.endTagSpan = if (endTag != null) buildSourceSpanFromNode(endTag) else null
                it.isVoidElement = startTag?.isVoidElement() ?: false
                it.startTagNameSpan = startTag?.name?.getSourceSpan(sourceDocument)
                it.startTagSpan = if (startTag != null) buildSourceSpanFromNode(startTag) else null
                it.attributeData = attributeData
                it.hasMissingCloseAngle = startTag?.closeAngle?.isMissing ?: false
                it.hasMissingEndCloseAngle = endTag?.closeAngle?.isMissing ?: false
            }

            builder.push(element)
            val previousInsideFlag = insideUnresolvedElement
            insideUnresolvedElement = true

            if (startTag != null) visitMarkupStartTag(startTag)

            // Now that all start-tag children have been lowered, check if any are C# expressions (e.g. <div @expr>) and record this on
            // the element so the resolution phase doesn't need to scan children itself.
            for (child in element.children) {
                if (child is CSharpExpressionIntermediateNode || child is CSharpCodeIntermediateNode) {
                    element.hasDynamicExpressionChild = true
                    break
                }
            }

            insideUnresolvedElement = false
            element.startTagEndIndex = element.children.size

            for (item in node.body) visit(item)

            element.bodyEndIndex = element.children.size

            if (endTag != null) visitMarkupEndTag(endTag)

            insideUnresolvedElement = previousInsideFlag
            builder.pop()
        }

        // Example
        // <input` checked="hello-world @false"`/>
        //  Name=checked
        //  Prefix= checked="
        //  Suffix="
        override fun visitMarkupAttributeBlock(node: MarkupAttributeBlockSyntax) {
            val prefixTokens = mergeTokenLists(node.namePrefix?.literalTokens, node.name.literalTokens, node.nameSuffix?.literalTokens, tokenListOf(node.equalsToken), node.valuePrefix?.literalTokens)
            val position = node.namePrefix?.position ?: node.name.position
            val prefix = GreenMarkupTextLiteral(RazorList(prefixTokens), null, null).createRed(node, position) as MarkupTextLiteralSyntax
            val name = node.name.getContent()

            if (!insideUnresolvedElement) {
                lowerAttributeAsHtml(node, name, prefix)
                return
            }

            // Unresolved path: create deferred attribute node with fallback forms.
            val valueSourceSpan = computeAttributeValueSourceSpan(node)

            builder.push(UnresolvedAttributeIntermediateNode().also {
                it.attributeName = name
                it.isMinimized = false
                it.source = buildSourceSpanFromNode(node)
                it.valueContent = node.value?.getContent()
                it.valueSourceSpan = valueSourceSpan
                it.attributeStructure = inferAttributeStructure(node)
                it.attributeNameSpan = buildSourceSpanFromNode(node.name)
            })

            // Capture the pre-lowered fallback form (the non-tag-helper HTML form) by temporarily resetting state and lowering into the
            // unresolved node's children. We then extract those children as the fallback before adding the unresolved form.
            insideUnresolvedElement = false
            lowerAttributeAsHtml(node, name, prefix)
            insideUnresolvedElement = true

            val unresolvedAttrNode = builder.current as UnresolvedAttributeIntermediateNode
            var legacyFallback: IntermediateNode? = null
            if (unresolvedAttrNode.children.size == 1) {
                legacyFallback = unresolvedAttrNode.children[0]
            } else if (unresolvedAttrNode.children.isNotEmpty()) {
                val container = MarkupElementIntermediateNode()
                container.children.addAll(unresolvedAttrNode.children)
                legacyFallback = container
            }
            unresolvedAttrNode.asTagHelperAttribute = legacyFallback
            unresolvedAttrNode.asMarkupAttribute = legacyFallback
            unresolvedAttrNode.children.clear()

            // Create HtmlAttribute with unresolved value children.
            builder.push(HtmlAttributeIntermediateNode().also {
                it.attributeName = name
                it.prefix = prefix.getContent()
                it.suffix = node.valueSuffix?.getContent() ?: ""
                it.source = buildSourceSpanFromNode(node)
            })
            insideUnresolvedAttribute = true
            lowerUnresolvedAttributeValue(node.value)
            insideUnresolvedAttribute = false
            builder.pop()

            // Store the HtmlAttribute child directly on the unresolved node for O(1) access.
            (builder.current as? UnresolvedAttributeIntermediateNode)?.let { current -> current.htmlAttributeNode = current.children[current.children.size - 1] as HtmlAttributeIntermediateNode }

            builder.pop()
        }

        /** `LowerAttributeAsHtml`: the attribute in its non-tag-helper HTML form. */
        private fun lowerAttributeAsHtml(node: MarkupAttributeBlockSyntax, name: String, prefix: MarkupTextLiteralSyntax) {
            val value = node.value
            if (!options.allowConditionalDataDashAttributes && name.startsWith("data-", ignoreCase = true)) {
                visit(prefix)
                visit(value)
                visit(node.valueSuffix)
            } else if (value != null) {
                val children = ChildNodesHelper(value.childNodesAndTokens())
                val attributeLiteralArray = children.tryCast<MarkupLiteralAttributeValueSyntax>()
                if (attributeLiteralArray != null) {
                    val tokens = ArrayList<RazorToken>()
                    for (literal in attributeLiteralArray) tokens.addAll(mergeAttributeValue(literal).literalTokens.map { it.token })
                    val mergedLiterals = mergeTokenLists(prefix.literalTokens, tokens.map { RedToken(it, null, 0) }, node.valueSuffix?.literalTokens)
                    val mergedAttribute = GreenMarkupTextLiteral(RazorList(mergedLiterals), null, null).createRed(node.parent, node.position)
                    visit(mergedAttribute)
                    return
                }

                builder.push(HtmlAttributeIntermediateNode().also {
                    it.attributeName = name
                    it.prefix = prefix.getContent()
                    it.suffix = node.valueSuffix?.getContent() ?: ""
                    it.source = buildSourceSpanFromNode(node)
                })
                visitAttributeValue(value)
                builder.pop()
            } else {
                // No value -- create empty HtmlAttribute.
                builder.push(HtmlAttributeIntermediateNode().also {
                    it.attributeName = name
                    it.prefix = prefix.getContent()
                    it.suffix = node.valueSuffix?.getContent() ?: ""
                    it.source = buildSourceSpanFromNode(node)
                })
                visitAttributeValue(value)
                builder.pop()
            }
        }

        /** The value of an attribute of an unresolved element: the `@@` escape merged with the literals after it. */
        private fun lowerUnresolvedAttributeValue(value: RedNode?) {
            if (value == null) {
                visitAttributeValue(value)
                return
            }

            // Check for @@ escape pattern in unresolved attribute values.
            val valueChildren = value.childNodesAndTokens()
            val first = valueChildren.firstOrNull()
            if (valueChildren.size >= 2 && first is MarkupBlockSyntax && first.children.size == 2 && first.children[0] is MarkupTextLiteralSyntax && first.children[1] is MarkupEphemeralTextLiteralSyntax) {
                val atLiteral = first.children[0] as MarkupTextLiteralSyntax
                // Check if all remaining children are literals (can merge everything).
                var allLiteral = true
                for (i in 1 until valueChildren.size) {
                    if (valueChildren[i] !is MarkupLiteralAttributeValueSyntax) {
                        allLiteral = false
                        break
                    }
                }

                val rewritten: RedNode
                if (allLiteral) {
                    // All-literal: merge @ + all literal content into one token.
                    val mergedTokens = ArrayList<RazorToken>()
                    atLiteral.literalTokens.forEach { mergedTokens.add(it.token) }
                    for (i in 1 until valueChildren.size) {
                        val literal = valueChildren[i] as MarkupLiteralAttributeValueSyntax
                        mergedTokens.addAll(mergeAttributeValue(literal).literalTokens.map { it.token })
                    }
                    rewritten = GreenMarkupTextLiteral(RazorList(mergedTokens), null, null).createRed(value.parent, atLiteral.position)
                } else {
                    // Mixed: just the @ literal; remaining children are visited below.
                    rewritten = GreenMarkupTextLiteral(RazorList(atLiteral.literalTokens.map { it.token }), null, null).createRed(value.parent, atLiteral.position)
                }

                val rewrittenSource = buildSourceSpanFromNode(rewritten)
                val unresolvedNode = UnresolvedAttributeValueIntermediateNode().also {
                    it.prefix = ""
                    it.source = rewrittenSource
                }
                unresolvedNode.children.add(IntermediateNodeFactory.htmlToken(rewritten as MarkupTextLiteralSyntax, { it.getContent() }, rewrittenSource))
                builder.add(unresolvedNode)

                if (!allLiteral) {
                    // Visit remaining children (expressions, etc.)
                    for (i in 1 until valueChildren.size) visit(valueChildren[i])
                }
            } else {
                visitAttributeValue(value)
            }
        }

        override fun visitMarkupMinimizedAttributeBlock(node: MarkupMinimizedAttributeBlockSyntax) {
            if (insideUnresolvedElement) {
                // Produce the fallback: what this minimized attribute looks like as plain HTML.
                // Minimized attributes are just html content (e.g. "checked" -> HtmlContent " checked").
                val fallbackTokens = mergeTokenLists(node.namePrefix?.literalTokens, node.name.literalTokens)
                val fallbackLiteral = GreenMarkupTextLiteral(RazorList(fallbackTokens), null, null).createRed(node.parent, node.position) as MarkupTextLiteralSyntax
                val fallbackSource = buildSourceSpanFromNode(fallbackLiteral)
                val fallback = HtmlContentIntermediateNode().also { it.source = fallbackSource }
                fallback.children.add(IntermediateNodeFactory.htmlToken(fallbackLiteral, { it.getContent() }, fallbackSource))

                builder.add(UnresolvedAttributeIntermediateNode().also {
                    it.attributeName = node.name.getContent()
                    it.isMinimized = true
                    it.source = buildSourceSpanFromNode(node)
                    it.attributeStructure = AttributeStructure.Minimized
                    it.attributeNameSpan = buildSourceSpanFromNode(node.name)
                    it.asMarkupAttribute = fallback
                })
                return
            }

            if (!options.allowConditionalDataDashAttributes) {
                val name = node.name.getContent()
                if (name.startsWith("data-", ignoreCase = true)) {
                    super.visitMarkupMinimizedAttributeBlock(node)
                    return
                }
            }

            // Minimized attributes are just html content.
            val literals = mergeTokenLists(node.namePrefix?.literalTokens, node.name.literalTokens)
            val literal = GreenMarkupTextLiteral(RazorList(literals), null, null).createRed(node.parent, node.position)
            visit(literal)
        }

        // CSharp expressions are broken up into blocks and spans because Razor allows Razor comments inside an expression.
        // Ex:
        //      @DateTime.@*This is a comment*@Now
        //
        // We need to capture this in the IR so that we can give each piece the correct source mappings
        override fun visitCSharpExplicitExpression(node: CSharpExplicitExpressionSyntax) {
            if (builder.current is CSharpExpressionAttributeValueIntermediateNode) {
                super.visitCSharpExplicitExpression(node)
                return
            }
            val expressionNode = CSharpExpressionIntermediateNode()
            builder.push(expressionNode)
            super.visitCSharpExplicitExpression(node)
            builder.pop()
            computeSourceSpanFromChildren(expressionNode)
        }

        override fun visitCSharpImplicitExpression(node: CSharpImplicitExpressionSyntax) {
            if (builder.current is CSharpExpressionAttributeValueIntermediateNode) {
                super.visitCSharpImplicitExpression(node)
                return
            }
            val expressionNode = CSharpExpressionIntermediateNode()
            builder.push(expressionNode)
            super.visitCSharpImplicitExpression(node)
            builder.pop()
            computeSourceSpanFromChildren(expressionNode)
        }

        override fun visitCSharpStatementLiteral(node: CSharpStatementLiteralSyntax) {
            val generator = node.chunkGenerator
            if (generator == null || generator === ChunkGenerator.Statement) {
                val isAttributeValue = builder.current is CSharpCodeAttributeValueIntermediateNode
                if (!isAttributeValue) {
                    builder.push(CSharpCodeIntermediateNode().also { it.source = buildSourceSpanFromNode(node) })
                }
                builder.add(IntermediateNodeFactory.csharpToken(node, { it.getContent() }, buildSourceSpanFromNode(node)))
                if (!isAttributeValue) builder.pop()
            }
            super.visitCSharpStatementLiteral(node)
        }

        override fun visitMarkupTextLiteral(node: MarkupTextLiteralSyntax) {
            if (node.chunkGenerator === ChunkGenerator.None) return
            if (node.literalTokens.size == 1 && node.literalTokens[0].kind == RazorSyntaxKind.Marker && node.literalTokens[0].content.isEmpty()) {
                // We don't want to create IR nodes for marker tokens.
                return
            }
            visitHtmlContent(node)
        }

        override fun visitMarkupStartTag(node: MarkupStartTagSyntax) {
            if (node.isMarkupTransition) return // No need to visit <text> tags.
            for (child in node.legacyChildren) visit(child)
        }

        override fun visitMarkupEndTag(node: MarkupEndTagSyntax) {
            if (node.isMarkupTransition) return // No need to visit </text> tags.
            for (child in node.legacyChildren) visit(child)
        }

        private fun visitHtmlContent(node: RedNode?) {
            if (node == null) return
            val source = buildSourceSpanFromNode(node)
            val currentChildren = builder.current!!.children

            // Don't merge HtmlContent across element region boundaries (start-tag -> body -> end-tag). The pre-computed indices mark
            // where each region begins, so when we're about to add the first child of a new region, we must start a fresh HtmlContent node.
            val current = builder.current
            val atBoundary = current is UnresolvedElementIntermediateNode && (currentChildren.size == current.startTagEndIndex || currentChildren.size == current.bodyEndIndex)

            if (!atBoundary && currentChildren.isNotEmpty() && currentChildren[currentChildren.size - 1] is HtmlContentIntermediateNode) {
                val existingHtmlContent = currentChildren[currentChildren.size - 1] as HtmlContentIntermediateNode
                val existingSource = existingHtmlContent.source
                if (existingSource == null && source == null) {
                    combine(existingHtmlContent, node)
                    return
                }
                if (source != null && existingSource != null && existingSource.filePath == source.filePath && existingSource.absoluteIndex + existingSource.length == source.absoluteIndex) {
                    combine(existingHtmlContent, node)
                    return
                }
            }

            val contentNode = HtmlContentIntermediateNode().also { it.source = source }
            builder.push(contentNode)
            builder.add(IntermediateNodeFactory.htmlToken(node, { it.getContent() }, source))
            builder.pop()
        }
    }

    /**
     * `ComponentFileKindVisitor`: .razor files (Blazor components). Treats HTML markup as structured nodes and supports components: every
     * element is wrapped in an [UnresolvedElementIntermediateNode] because any element could match a component.
     */
    class ComponentFileKindVisitor(document: DocumentIntermediateNode, builder: IntermediateNodeBuilder, usings: MutableList<UsingReference>, options: RazorParserOptions) :
        LoweringVisitor(document, builder, usings, options) {
        private var isCurrentAttributeNameEscaped = false

        override fun visitMarkupElement(node: MarkupElementSyntax) {
            val startTag = node.markupStartTag
            val endTag = node.markupEndTag
            if ((startTag != null && startTag.isMarkupTransition) || (endTag != null && endTag.isMarkupTransition)) {
                super.visitMarkupElement(node)
                return
            }

            val tagName = startTag?.name?.content ?: endTag?.name?.content ?: ""
            val attributeData = extractAttributeData(node)

            val element = UnresolvedElementIntermediateNode().also {
                it.source = buildSourceSpanFromNode(node)
                it.tagName = tagName
                it.isComponent = true
                it.isEscaped = startTag?.bang.let { bang -> bang != null && bang.width > 0 }
                it.isSelfClosing = startTag?.isSelfClosing() ?: false
                it.hasEndTag = endTag != null
                it.endTagName = endTag?.getTagNameWithOptionalBang()
                it.endTagSpan = if (endTag != null) buildSourceSpanFromNode(endTag) else null
                it.isVoidElement = startTag?.isVoidElement() ?: false
                it.startTagNameSpan = startTag?.name?.getSourceSpan(sourceDocument)
                it.startTagSpan = if (startTag != null) buildSourceSpanFromNode(startTag) else null
                it.attributeData = attributeData
                it.hasMissingCloseAngle = startTag?.closeAngle?.isMissing ?: false
                it.hasMissingEndCloseAngle = endTag?.closeAngle?.isMissing ?: false
            }

            // Preserve diagnostics from the original ComponentFileKindVisitor logic.
            if (startTag != null && endTag != null && startTag.isVoidElement()) {
                element.addDiagnostic(ComponentDiagnosticFactory.create_UnexpectedClosingTagForVoidElement(buildSourceSpanFromNode(endTag), endTag.getTagNameWithOptionalBang()))
            } else if (startTag != null && endTag == null && !startTag.isVoidElement() && !startTag.isSelfClosing()) {
                element.addDiagnostic(ComponentDiagnosticFactory.create_UnclosedTag(buildSourceSpanFromNode(startTag), startTag.getTagNameWithOptionalBang()))
            } else if (startTag == null && endTag != null) {
                element.addDiagnostic(ComponentDiagnosticFactory.create_UnexpectedClosingTag(buildSourceSpanFromNode(endTag), endTag.getTagNameWithOptionalBang()))
            }

            builder.push(element)
            super.visitMarkupElement(node)
            builder.pop()
        }

        override fun visitMarkupStartTag(node: MarkupStartTagSyntax) {
            // We want to skip over the other misc tokens that make up a start tag, and just process the attributes.
            var previousWasEscape = false
            for (block in node.attributes) {
                isCurrentAttributeNameEscaped = previousWasEscape
                when (block) {
                    is MarkupAttributeBlockSyntax -> visitMarkupAttributeBlock(block)
                    is MarkupMinimizedAttributeBlockSyntax -> visitMarkupMinimizedAttributeBlock(block)
                    else -> {}
                }
                previousWasEscape = block is MarkupEphemeralTextLiteralSyntax
            }
            isCurrentAttributeNameEscaped = false
        }

        override fun visitMarkupEndTag(node: MarkupEndTagSyntax) {
            // Nothing to do here
        }

        // Example
        // <input` checked="hello-world @false"`/>
        //  Name=checked
        //  Prefix= checked="
        //  Suffix="
        override fun visitMarkupAttributeBlock(node: MarkupAttributeBlockSyntax) {
            val name = node.name.getContent()
            val isUnresolved = builder.current is UnresolvedElementIntermediateNode

            if (isUnresolved) {
                val valueSourceSpan = computeAttributeValueSourceSpan(node)
                val source = buildSourceSpanFromNode(node)

                builder.push(UnresolvedAttributeIntermediateNode().also {
                    it.attributeName = name
                    it.isMinimized = false
                    it.isDirectiveAttributeCandidate = isDirectiveAttributeCandidate(node.name)
                    it.source = source
                    it.valueContent = node.value?.getContent()
                    it.valueSourceSpan = valueSourceSpan
                    it.attributeStructure = inferAttributeStructure(node)
                    it.attributeNameSpan = buildSourceSpanFromNode(node.name)
                })

                // Capture value-only fallback (merged adjacent literal tokens) by temporarily lowering into the unresolved node's
                // children with unresolved state reset.
                val fallbackContainer = HtmlAttributeIntermediateNode().also {
                    it.attributeName = name
                    it.prefix = GreenMarkupTextLiteral(RazorList(mergeTokenLists(node.namePrefix?.literalTokens, node.name.literalTokens, node.nameSuffix?.literalTokens, tokenListOf(node.equalsToken), node.valuePrefix?.literalTokens)), null, null).text()
                    it.suffix = node.valueSuffix?.getContent() ?: ""
                    it.source = source
                }

                if (node.value != null) {
                    builder.push(fallbackContainer)
                    visitAttributeValue(node.value)
                    builder.pop()
                }
                val unresolvedAttrNode = builder.current as UnresolvedAttributeIntermediateNode
                // Remove fallbackContainer from children -- it was temporarily added by Push.
                unresolvedAttrNode.children.remove(fallbackContainer)
                unresolvedAttrNode.asTagHelperAttribute = fallbackContainer

                // Capture AsMarkupAttribute fallback by lowering the whole attribute in non-unresolved context. Push a temporary container
                // so builder.current is not an UnresolvedElementIntermediateNode, which causes visitMarkupAttributeBlock to take the
                // non-unresolved path.
                val fullFallbackContainer = MarkupElementIntermediateNode()
                builder.push(fullFallbackContainer)
                visit(node)
                builder.pop()
                unresolvedAttrNode.children.remove(fullFallbackContainer)
                unresolvedAttrNode.asMarkupAttribute = when {
                    fullFallbackContainer.children.size == 1 -> fullFallbackContainer.children[0]
                    fullFallbackContainer.children.isNotEmpty() -> fullFallbackContainer
                    else -> null
                }
            }

            val prefixTokens = mergeTokenLists(node.namePrefix?.literalTokens, node.name.literalTokens, node.nameSuffix?.literalTokens, tokenListOf(node.equalsToken), node.valuePrefix?.literalTokens)
            val position = node.namePrefix?.position ?: node.name.position
            val prefix = GreenMarkupTextLiteral(RazorList(prefixTokens), null, null).createRed(node, position) as MarkupTextLiteralSyntax

            builder.push(HtmlAttributeIntermediateNode().also {
                it.attributeName = name
                it.prefix = prefix.getContent()
                it.suffix = node.valueSuffix?.getContent() ?: ""
                it.source = buildSourceSpanFromNode(node)
            })

            insideUnresolvedAttribute = isUnresolved
            val value = node.value
            val valueChildren = value?.childNodesAndTokens()
            val first = valueChildren?.firstOrNull()
            if (isUnresolved && valueChildren != null && valueChildren.size >= 2 && first is MarkupBlockSyntax && first.children.size == 2 &&
                first.children[0] is MarkupTextLiteralSyntax && first.children[1] is MarkupEphemeralTextLiteralSyntax && valueChildren[1] is MarkupLiteralAttributeValueSyntax
            ) {
                val atLiteral = first.children[0] as MarkupTextLiteralSyntax
                // @@ escape pattern: merge the escaped @ with all following literal children into one unresolved node.
                val mergedTokens = ArrayList<RazorToken>()
                atLiteral.literalTokens.forEach { mergedTokens.add(it.token) }
                for (i in 1 until valueChildren.size) {
                    val literal = valueChildren[i] as? MarkupLiteralAttributeValueSyntax ?: break // Mixed content after @@ -- fall through to normal Visit
                    mergedTokens.addAll(mergeAttributeValue(literal).literalTokens.map { it.token })
                }

                val rewritten = GreenMarkupTextLiteral(RazorList(mergedTokens), null, null).createRed(value.parent, atLiteral.position)
                val rewrittenSource = buildSourceSpanFromNode(rewritten)
                val unresolvedNode = UnresolvedAttributeValueIntermediateNode().also {
                    it.prefix = ""
                    it.source = rewrittenSource
                }
                unresolvedNode.children.add(IntermediateNodeFactory.htmlToken(rewritten as MarkupTextLiteralSyntax, { it.getContent() }, rewrittenSource))
                builder.add(unresolvedNode)
            } else {
                visit(value)
            }
            insideUnresolvedAttribute = false

            builder.pop()

            // Store the HtmlAttribute child directly on the unresolved node for O(1) access.
            if (isUnresolved) {
                (builder.current as? UnresolvedAttributeIntermediateNode)?.let { current -> current.htmlAttributeNode = current.children[current.children.size - 1] as HtmlAttributeIntermediateNode }
                builder.pop()
            }
        }

        override fun visitMarkupMinimizedAttributeBlock(node: MarkupMinimizedAttributeBlockSyntax) {
            val prefixTokens = mergeTokenLists(node.namePrefix?.literalTokens, node.name.literalTokens)
            val position = node.namePrefix?.position ?: node.name.position
            val prefix = GreenMarkupTextLiteral(RazorList(prefixTokens), null, null).createRed(node, position) as MarkupTextLiteralSyntax

            val name = node.name.getContent()
            val source = buildSourceSpanFromNode(node)
            val htmlAttr = HtmlAttributeIntermediateNode().also {
                it.attributeName = name
                it.prefix = prefix.getContent()
                it.suffix = null
                it.source = source
            }

            if (builder.current is UnresolvedElementIntermediateNode) {
                builder.add(UnresolvedAttributeIntermediateNode().also {
                    it.attributeName = name
                    it.isMinimized = true
                    it.isDirectiveAttributeCandidate = isDirectiveAttributeCandidate(node.name)
                    it.source = source
                    it.attributeStructure = AttributeStructure.Minimized
                    it.attributeNameSpan = buildSourceSpanFromNode(node.name)
                    it.asMarkupAttribute = htmlAttr
                })
            } else {
                builder.add(htmlAttr)
            }
        }

        private fun isDirectiveAttributeCandidate(name: MarkupTextLiteralSyntax): Boolean {
            val tokens = name.literalTokens
            return !isCurrentAttributeNameEscaped && tokens.isNotEmpty() && tokens[0].kind == RazorSyntaxKind.Transition &&
                (tokens.size == 1 || tokens[1].kind != RazorSyntaxKind.Transition)
        }

        // Example
        // <input checked="hello-world `@false`"/>
        //  Prefix= (space)
        //  Children will contain a token for @false.
        override fun visitMarkupTextLiteral(node: MarkupTextLiteralSyntax) {
            if (builder.current is HtmlAttributeIntermediateNode) {
                val attrValueSource = buildSourceSpanFromNode(node)
                val childNode: IntermediateNode = if (insideUnresolvedAttribute) {
                    UnresolvedAttributeValueIntermediateNode().also {
                        it.prefix = ""
                        it.source = attrValueSource
                    }
                } else {
                    HtmlAttributeValueIntermediateNode().also {
                        it.prefix = ""
                        it.source = attrValueSource
                    }
                }
                childNode.children.add(IntermediateNodeFactory.htmlToken(node, { it.getContent() }, attrValueSource))
                builder.add(childNode)
                return
            }

            if (node.chunkGenerator === ChunkGenerator.None) return
            if (node.literalTokens.size == 1 && node.literalTokens[0].kind == RazorSyntaxKind.Marker && node.literalTokens[0].content.isEmpty()) {
                // We don't want to create IR nodes for marker tokens.
                return
            }

            // Combine chunks of HTML literal text if possible.
            val source = buildSourceSpanFromNode(node)
            val currentChildren = builder.current!!.children
            if (currentChildren.isNotEmpty()) {
                val existingHtmlContent = currentChildren[currentChildren.size - 1] as? HtmlContentIntermediateNode
                if (existingHtmlContent != null) {
                    val existingSource = existingHtmlContent.source
                    if (existingSource == null && source == null) {
                        combine(existingHtmlContent, node)
                        return
                    }
                    if (source != null && existingSource != null && existingSource.filePath == source.filePath && existingSource.absoluteIndex + existingSource.length == source.absoluteIndex) {
                        combine(existingHtmlContent, node)
                        return
                    }
                }
            }

            builder.add(HtmlContentIntermediateNode().also {
                it.source = source
                it.children.add(IntermediateNodeFactory.htmlToken(node, { n -> n.getContent() }, source))
            })
        }

        override fun visitMarkupCommentBlock(node: MarkupCommentBlockSyntax) {
            // Comments are ignored by components. We skip over anything that appears inside.
        }

        // CSharp expressions are broken up into blocks and spans because Razor allows Razor comments inside an expression.
        override fun visitCSharpExplicitExpression(node: CSharpExplicitExpressionSyntax) {
            if (builder.current is HtmlAttributeIntermediateNode) {
                // This can happen inside a data- attribute
                builder.push(CSharpExpressionAttributeValueIntermediateNode().also {
                    it.prefix = ""
                    it.source = buildSourceSpanFromNode(node)
                })
                super.visitCSharpExplicitExpression(node)
                builder.pop()
                return
            }
            if (builder.current is CSharpExpressionAttributeValueIntermediateNode) {
                super.visitCSharpExplicitExpression(node)
                return
            }
            val expressionNode = CSharpExpressionIntermediateNode()
            builder.push(expressionNode)
            super.visitCSharpExplicitExpression(node)
            builder.pop()
            computeSourceSpanFromChildren(expressionNode)
        }

        override fun visitCSharpImplicitExpression(node: CSharpImplicitExpressionSyntax) {
            if (builder.current is HtmlAttributeIntermediateNode) {
                // This can happen inside a data- attribute
                builder.push(CSharpExpressionAttributeValueIntermediateNode().also {
                    it.prefix = ""
                    it.source = buildSourceSpanFromNode(node)
                })
                super.visitCSharpImplicitExpression(node)
                builder.pop()
                return
            }
            if (builder.current is CSharpExpressionAttributeValueIntermediateNode) {
                super.visitCSharpImplicitExpression(node)
                return
            }
            val expressionNode = CSharpExpressionIntermediateNode()
            builder.push(expressionNode)
            super.visitCSharpImplicitExpression(node)
            builder.pop()
            computeSourceSpanFromChildren(expressionNode)
        }

        override fun visitCSharpStatementLiteral(node: CSharpStatementLiteralSyntax) {
            when (val generator = node.chunkGenerator) {
                // @addTagHelper / @removeTagHelper / @tagHelperPrefix are not valid in a component document. Tag-helper discovery runs
                // after IR lowering, so the diagnostic is attached to the chunk generator here; the base visitor copies it onto the IR node.
                is ChunkGenerator.AddTagHelper -> generator.diagnostics.add(ComponentDiagnosticFactory.create_UnsupportedTagHelperDirective(buildTagHelperDirectiveSourceSpan(node)))
                is ChunkGenerator.RemoveTagHelper -> generator.diagnostics.add(ComponentDiagnosticFactory.create_UnsupportedTagHelperDirective(buildTagHelperDirectiveSourceSpan(node)))
                is ChunkGenerator.TagHelperPrefix -> generator.diagnostics.add(ComponentDiagnosticFactory.create_UnsupportedTagHelperDirective(buildTagHelperDirectiveSourceSpan(node)))
                null, ChunkGenerator.Statement -> {
                    val isAttributeValue = builder.current is CSharpCodeAttributeValueIntermediateNode
                    if (!isAttributeValue) builder.push(CSharpCodeIntermediateNode().also { it.source = buildSourceSpanFromNode(node) })
                    builder.add(IntermediateNodeFactory.csharpToken(node, { it.getContent() }, buildSourceSpanFromNode(node)))
                    if (!isAttributeValue) builder.pop()
                }
                else -> {}
            }
            super.visitCSharpStatementLiteral(node)
        }
    }

    /**
     * `ComponentImportFileKindVisitor`: `_Imports.razor` files. Only directives (`@using`, `@namespace`) and code expressions; markup and
     * expressions produce diagnostics.
     */
    class ComponentImportFileKindVisitor(document: DocumentIntermediateNode, builder: IntermediateNodeBuilder, usings: MutableList<UsingReference>, options: RazorParserOptions) :
        LoweringVisitor(document, builder, usings, options) {

        override fun visitCSharpStatementLiteral(node: CSharpStatementLiteralSyntax) {
            when (val generator = node.chunkGenerator) {
                is ChunkGenerator.AddTagHelper -> generator.diagnostics.add(ComponentDiagnosticFactory.create_UnsupportedTagHelperDirective(buildTagHelperDirectiveSourceSpan(node)))
                is ChunkGenerator.RemoveTagHelper -> generator.diagnostics.add(ComponentDiagnosticFactory.create_UnsupportedTagHelperDirective(buildTagHelperDirectiveSourceSpan(node)))
                is ChunkGenerator.TagHelperPrefix -> generator.diagnostics.add(ComponentDiagnosticFactory.create_UnsupportedTagHelperDirective(buildTagHelperDirectiveSourceSpan(node)))
                else -> {}
            }
            super.visitCSharpStatementLiteral(node)
        }

        override fun visitMarkupElement(node: MarkupElementSyntax) {
            document.addDiagnostic(ComponentDiagnosticFactory.create_UnsupportedComponentImportContent(buildSourceSpanFromNode(node)))
            super.visitMarkupElement(node)
        }

        override fun visitMarkupCommentBlock(node: MarkupCommentBlockSyntax) {
            document.addDiagnostic(ComponentDiagnosticFactory.create_UnsupportedComponentImportContent(buildSourceSpanFromNode(node)))
            super.visitMarkupCommentBlock(node)
        }

        override fun visitCSharpExplicitExpression(node: CSharpExplicitExpressionSyntax) {
            document.addDiagnostic(ComponentDiagnosticFactory.create_UnsupportedComponentImportContent(buildSourceSpanFromNode(node)))
            super.visitCSharpExplicitExpression(node)
        }

        override fun visitCSharpImplicitExpression(node: CSharpImplicitExpressionSyntax) {
            // We typically don't want C# in imports files except for directives. But since Razor directive intellisense is tied to C#
            // intellisense during design time, we want to still generate an IR node for implicit expressions. Otherwise, there will be no
            // source mapping when someone types an `@` leading to no intellisense.
            if (node.firstAncestorOrSelf { it is MarkupStartTagSyntax || it is MarkupEndTagSyntax } != null) {
                // We don't care about implicit expression in attributes.
                return
            }
            val expressionNode = CSharpExpressionIntermediateNode()
            builder.push(expressionNode)
            super.visitCSharpImplicitExpression(node)
            builder.pop()
            computeSourceSpanFromChildren(expressionNode)
            document.addDiagnostic(ComponentDiagnosticFactory.create_UnsupportedComponentImportContent(expressionNode.source))
            super.visitCSharpImplicitExpression(node)
        }

        override fun visitCSharpExpressionLiteral(node: CSharpExpressionLiteralSyntax) {
            if (node.firstAncestorOrSelf { it is CSharpImplicitExpressionSyntax } == null) {
                // We only care about implicit expressions.
                return
            }
            builder.add(IntermediateNodeFactory.csharpToken(node, { it.getContent() }, buildSourceSpanFromNode(node)))
        }

        override fun visitCSharpStatement(node: CSharpStatementSyntax) {
            document.addDiagnostic(ComponentDiagnosticFactory.create_UnsupportedComponentImportContent(buildSourceSpanFromNode(node)))
            super.visitCSharpStatement(node)
        }
    }

    /** `ImportsVisitor`: the lowering of an import, every node it adds marked imported. */
    class ImportsVisitor(document: DocumentIntermediateNode, builder: IntermediateNodeBuilder, usings: MutableList<UsingReference>, options: RazorParserOptions) :
        LoweringVisitor(document, ImportBuilder(builder), usings, options) {

        private class ImportBuilder(private val inner: IntermediateNodeBuilder) : IntermediateNodeBuilder() {
            override val current: IntermediateNode? get() = inner.current
            override fun add(node: IntermediateNode) {
                node.isImported = true
                inner.add(node)
            }
            override fun build(): IntermediateNode? = inner.build()
            override fun insert(index: Int, node: IntermediateNode) {
                node.isImported = true
                inner.insert(index, node)
            }
            override fun pop(): IntermediateNode = inner.pop()
            override fun push(node: IntermediateNode) {
                node.isImported = true
                inner.push(node)
            }
        }
    }

    companion object {
        fun looksLikeAComponentName(document: DocumentIntermediateNode, startTagName: String): Boolean {
            val category = Character.getType(startTagName.codePointAt(0))
            return category == Character.UPPERCASE_LETTER.toInt() ||
                (document.options?.supportLocalizedComponentNames == true && (category == Character.TITLECASE_LETTER.toInt() || category == Character.OTHER_LETTER.toInt()))
        }

        fun isMalformed(diagnostics: Iterable<RazorDiagnostic>): Boolean = diagnostics.any { it.severity == RazorDiagnosticSeverity.ERROR }
    }
}

/** `RazorDiagnosticFactory`'s general errors used by the lowering. */
object LoweringDiagnostics {
    val Directive_BlockDirectiveCannotBeImported = RazorDiagnosticDescriptor("RZ0000", "Block directive '{0}' cannot be imported.", RazorDiagnosticSeverity.ERROR)
    fun createDirective_BlockDirectiveCannotBeImported(directive: String) = RazorDiagnostic(Directive_BlockDirectiveCannotBeImported, SourceSpan.UNDEFINED, listOf(directive))
}
