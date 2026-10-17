package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.codegen.RazorCodeDocument
import io.github.dotnetsupport.razor.lang.codegen.RazorOptimizationPass
import io.github.dotnetsupport.razor.lang.intermediate.CSharpIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.ComponentAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeReference
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeWalker
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.MarkupBlockIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MarkupElementIntermediateNode
import io.github.dotnetsupport.razor.lang.parser.ParserHelpers
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import java.util.TreeMap

/** The late markup passes of components (`ComponentMarkupDiagnosticPass.cs`, `ComponentMarkupBlockPass.cs`, `ComponentMarkupEncodingPass.cs`). */

/**
 * `ComponentMarkupDiagnosticPass`: an attribute used twice on an element (RZ10007, or RZ10008 when a directive attribute generated one of
 * them) or a parameter twice on a component (RZ10009, RZ10010); case-insensitive. Child contents have their own pass.
 */
class ComponentMarkupDiagnosticPass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = DEFAULT_ORDER

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val visitor = Visitor()
        visitor.visit(documentNode)
    }

    private class Visitor : IntermediateNodeWalker() {
        /** By the attribute name, ignoring case: the name as written and the node. */
        private val attributes = TreeMap<String, Pair<String, IntermediateNode>>(String.CASE_INSENSITIVE_ORDER)

        override fun visitMarkupElement(node: MarkupElementIntermediateNode) {
            for (child in node.children) {
                val attributeName = (child as? HtmlAttributeIntermediateNode)?.attributeName ?: continue
                val other = attributes[attributeName]
                if (other != null) {
                    val otherAttribute = other.second as HtmlAttributeIntermediateNode

                    // A directive or other construct that emitted the conflicting attribute is pointed out explicitly, by the original
                    // name of the lowered construct (`bind-value` sets `value` and `onchange`).
                    val originalAttributeName = child.originalAttributeName ?: otherAttribute.originalAttributeName

                    if (originalAttributeName != null) {
                        otherAttribute.addDiagnostic(
                            ComponentDiagnosticFactory.create_DuplicateMarkupAttributeDirective(other.first, originalAttributeName, otherAttribute.source ?: node.source),
                        )
                    } else {
                        // This is a conflict in the code the user wrote.
                        otherAttribute.addDiagnostic(ComponentDiagnosticFactory.create_DuplicateMarkupAttribute(other.first, otherAttribute.source ?: node.source))
                    }
                }

                // Replace the attribute we were previously tracking. Then if you have three, the two on the left will have diagnostics.
                attributes[attributeName] = attributeName to child
            }

            attributes.clear()
            super.visitMarkupElement(node)
        }

        override fun visitComponent(node: ComponentIntermediateNode) {
            for (child in node.children) {
                // Note that we don't handle ChildContent cases here. Those have their own pass for diagnostics.
                val attributeName = (child as? ComponentAttributeIntermediateNode)?.attributeName ?: continue
                val other = attributes[attributeName]
                if (other != null) {
                    val otherAttribute = other.second as ComponentAttributeIntermediateNode

                    // A directive or other construct that emitted the conflicting attribute is pointed out explicitly, by the original
                    // name of the lowered construct (`bind-Value` sets `Value` and `ValueChanged`).
                    val originalAttributeName = child.originalAttributeName ?: otherAttribute.originalAttributeName

                    if (originalAttributeName != null) {
                        other.second.addDiagnostic(
                            ComponentDiagnosticFactory.create_DuplicateComponentParameterDirective(other.first, originalAttributeName, other.second.source ?: node.source),
                        )
                    } else {
                        // This is a conflict in the code the user wrote.
                        other.second.addDiagnostic(ComponentDiagnosticFactory.create_DuplicateComponentParameter(other.first, other.second.source ?: node.source))
                    }
                }

                // Replace the attribute we were previously tracking. Then if you have three, the two on the left will have diagnostics.
                attributes[attributeName] = attributeName to child
            }

            attributes.clear()
            super.visitComponent(node)
        }
    }

    companion object {
        const val DEFAULT_ORDER = 10000
    }
}

/**
 * `ComponentMarkupBlockPass`: contiguous subtrees of plain HTML become one [MarkupBlockIntermediateNode] (`AddMarkupContent`), a smaller
 * render tree; insignificant details (the closing style of tags, the quotes) are not preserved. Runs late, since it destroys structure,
 * and after the markup diagnostic pass, whose diagnostics it would destroy.
 */
class ComponentMarkupBlockPass(private val version: RazorLanguageVersion) : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = ComponentMarkupDiagnosticPass.DEFAULT_ORDER + 10

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        val findVisitor = FindHtmlTreeVisitor(version)
        findVisitor.visit(documentNode)

        val trees = findVisitor.trees
        val rewriteVisitor = RewriteVisitor(trees)
        while (trees.size > 0) {
            // Walk backwards since we did a postorder traversal.
            val reference = trees[trees.size - 1]

            // Forcibly remove a node to prevent infinite loops.
            trees.removeAt(trees.size - 1)

            // Siblings are folded together where possible: the index of the node, then the contiguous nodes before it that can be merged.
            var start = reference.parent.children.size - 1
            while (start >= 0) {
                if (reference.node === reference.parent.children[start]) break
                start--
            }

            // The left sibling, if a candidate, is next in the list to process (the order the nodes were found in).
            val end = start
            while (start - 1 >= 0) {
                val candidate = reference.parent.children[start - 1]
                // Out of nodes, or the left sibling is not in the list.
                if (trees.size == 0 || trees[trees.size - 1].node !== candidate) break

                // This means that the left sibling is valid to merge.
                start--

                // Remove this since we're combining it.
                trees.removeAt(trees.size - 1)
            }

            // As a degenerate case, don't bother rewriting an single HtmlContent node. It doesn't add any value.
            if (end - start == 0 && reference.node is HtmlContentIntermediateNode) continue

            // Now we know the range of nodes to rewrite (end is inclusive)
            var length = end + 1 - start
            while (length > 0) {
                // Keep using start since we're removing nodes.
                val node = reference.parent.children[start]
                reference.parent.children.removeAt(start)

                rewriteVisitor.visit(node)

                length--
            }

            reference.parent.children.add(start, MarkupBlockIntermediateNode().also { it.content = rewriteVisitor.builder.toString() })

            rewriteVisitor.builder.setLength(0)
        }
    }

    /**
     * `FindHtmlTreeVisitor`: the blocks of HTML in postorder, in an ordered list, so that redundant rewrites are avoided — with
     * `<div><a href="...">click me</a></div>` both are stored, the div visited first, and processing it (recursively) removes the a.
     */
    private class FindHtmlTreeVisitor(private val version: RazorLanguageVersion) : IntermediateNodeWalker() {
        private var foundNonHtml = false

        val trees: MutableList<IntermediateNodeReference<IntermediateNode>> = ArrayList()

        override fun visitDefault(node: IntermediateNode) {
            // If we get here, we found a non-HTML node. Keep traversing.
            foundNonHtml = true
            super.visitDefault(node)
        }

        override fun visitMarkupElement(node: MarkupElementIntermediateNode) {
            // The state is restored after this node: a leaf-block of HTML found here doesn't affect the parent's state.
            var originalState = foundNonHtml

            foundNonHtml = false

            // Treat node with errors as non-HTML - don't let the parent rewrite this either.
            if (node.hasDiagnostics) foundNonHtml = true

            if (version <= RazorLanguageVersion.VERSION_7_0 && "script".equals(node.tagName, ignoreCase = true)) {
                // Treat script tags as non-HTML in .NET 7 and earlier.
                foundNonHtml = true
            } else if ("option".equals(node.tagName, ignoreCase = true)) {
                // <option>...</option> under a select is non-HTML too, not coalesced, so that its "selected" attribute can be set.
                for (ancestor in ancestors) {
                    if (ancestor is MarkupElementIntermediateNode && "select".equals(ancestor.tagName, ignoreCase = true)) {
                        foundNonHtml = true
                        break
                    }
                }
            }

            super.visitDefault(node)

            if (!foundNonHtml) trees.add(IntermediateNodeReference(node, parent!!))

            originalState = originalState or foundNonHtml
            foundNonHtml = originalState
        }

        override fun visitHtmlAttribute(node: HtmlAttributeIntermediateNode) {
            // Treat node with errors as non-HTML
            if (node.hasDiagnostics) foundNonHtml = true

            // Visit Children
            super.visitDefault(node)
        }

        override fun visitHtmlAttributeValue(node: HtmlAttributeValueIntermediateNode) {
            // Treat node with errors as non-HTML
            if (node.hasDiagnostics) foundNonHtml = true

            // Visit Children
            super.visitDefault(node)
        }

        override fun visitHtml(node: HtmlContentIntermediateNode) {
            // The state is restored after this node: a leaf-block of HTML found here doesn't affect the parent's state.
            var originalState = foundNonHtml

            foundNonHtml = false

            // Treat node with errors as non-HTML
            if (node.hasDiagnostics) foundNonHtml = true

            // Visit Children
            super.visitDefault(node)

            if (!foundNonHtml) trees.add(IntermediateNodeReference(node, parent!!))

            originalState = originalState or foundNonHtml
            foundNonHtml = originalState
        }

        override fun visitToken(node: IntermediateToken) {
            // Treat node with errors as non-HTML
            if (node.hasDiagnostics) foundNonHtml = true

            if (node is CSharpIntermediateToken) foundNonHtml = true
        }
    }

    private class RewriteVisitor(private val trees: MutableList<IntermediateNodeReference<IntermediateNode>>) : IntermediateNodeWalker() {
        val builder = StringBuilder()

        override fun visitMarkupElement(node: MarkupElementIntermediateNode) {
            for (i in trees.indices) {
                // Remove this node if it's in the list. This ensures that we don't do redundant operations.
                if (trees[i].node === node) {
                    trees.removeAt(i)
                    break
                }
            }

            val tagName = node.tagName ?: ""
            val isVoid = ParserHelpers.isVoidElement(tagName)
            val hasBodyContent = node.body.isNotEmpty()

            builder.append('<')
            builder.append(tagName)

            for (attribute in node.attributes) visit(attribute)

            // If for some reason a void element contains body, then treat it as a start/end tag.
            if (!hasBodyContent && isVoid) {
                // void
                builder.append('>')
                return
            } else if (!hasBodyContent) {
                // In HTML5, we can't have self-closing non-void elements, so explicitly add a close tag
                builder.append("></")
                builder.append(tagName)
                builder.append('>')
                return
            }

            // start/end tag with body.
            builder.append('>')

            for (item in node.body) visit(item)

            builder.append("</")
            builder.append(tagName)
            builder.append('>')
        }

        override fun visitHtmlAttribute(node: HtmlAttributeIntermediateNode) {
            builder.append(' ')
            builder.append(node.attributeName ?: "")

            // Minimized attribute
            if (node.children.size == 0) return

            // The quote type of the original markup, from the prefix (" onfocus='" or " on focus=\"").
            val quoteType = if (node.prefix?.endsWith("'") == true) "'" else "\""

            builder.append('=')
            builder.append(quoteType)

            // Visit Children
            super.visitDefault(node)

            builder.append(quoteType)
        }

        override fun visitHtmlAttributeValue(node: HtmlAttributeValueIntermediateNode) {
            for (i in node.children.indices) {
                builder.append(node.prefix ?: "")

                val token = node.children[i]
                if (token is IntermediateToken) builder.append(token.content)
            }
        }

        override fun visitHtml(node: HtmlContentIntermediateNode) {
            for (i in node.children.indices) {
                val token = node.children[i]
                if (token is IntermediateToken) builder.append(token.content)
            }
        }
    }
}

/**
 * `ComponentMarkupEncodingPass`: markup content is rendered with `AddContent` (encoded when pre-rendering, low perf impact) as much as
 * possible, and with `AddMarkupContent` (as markup, high perf impact) when it has line breaks, tabs, angle brackets or non-ASCII
 * characters, so that those are not encoded aggressively when pre-rendering; HTML entities are decoded for `AddContent`, and content
 * with an unknown one stays markup. From 8.0 the content of `<script>` is never encoded. Runs after the markup block pass.
 */
class ComponentMarkupEncodingPass(private val version: RazorLanguageVersion) : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = ComponentMarkupDiagnosticPass.DEFAULT_ORDER + 20

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        val rewriter = Rewriter(version)
        rewriter.visit(documentNode)
    }

    private class Rewriter(version: RazorLanguageVersion) : IntermediateNodeWalker() {
        private val avoidEncodingScripts = version >= RazorLanguageVersion.VERSION_8_0

        private var avoidEncodingContent = false

        override fun visitMarkupElement(node: MarkupElementIntermediateNode) {
            // We don't want to HTML-encode literal content inside <script> tags.
            val oldAvoidEncodingContent = avoidEncodingContent

            avoidEncodingContent = avoidEncodingContent || (avoidEncodingScripts && "script".equals(node.tagName, ignoreCase = true))

            try {
                super.visitMarkupElement(node)
            } finally {
                avoidEncodingContent = oldAvoidEncodingContent
            }
        }

        override fun visitHtml(node: HtmlContentIntermediateNode) {
            if (avoidEncodingContent) {
                node.hasEncodedContent = true
                return
            }

            // We'll count any ampersand ('&') characters we find.
            var ampersandCount = 0

            for (child in node.children) {
                // We only care about Html tokens.
                if (child !is HtmlIntermediateToken || child.content.isEmpty()) continue

                for (ch in child.content) {
                    // ASCII range is 0 - 127
                    if (ch.code > 127 || ch in ENCODED_CHARACTERS) {
                        node.hasEncodedContent = true
                        return
                    }

                    if (ch == '&') ampersandCount++
                }
            }

            // No new lines, tabs or non-ASCII characters in this node; without ampersands no HTML character entities to decode.
            if (ampersandCount == 0) return

            val toUpdate = ArrayList<Pair<HtmlIntermediateToken, String>>(ampersandCount * 2 + 1)

            for (child in node.children) {
                // We only care about Html tokens.
                if (child !is HtmlIntermediateToken || child.content.isEmpty()) continue

                val decoded = tryDecodeHtmlEntities(child.content)
                if (decoded != null) {
                    toUpdate.add(child to decoded)
                } else {
                    node.hasEncodedContent = true
                    return
                }
            }

            // All the content decoded: replace all token content with the decoded value.
            for ((token, content) in toUpdate) token.updateContent(content)
        }

        /** `TryDecodeHtmlEntities`: the content with its entities replaced; null at a `&` that is no known entity. */
        private fun tryDecodeHtmlEntities(content: String): String? {
            if (content.isEmpty()) return ""

            val builder = StringBuilder()
            var rest = 0
            while (rest < content.length) {
                val ampersandIndex = content.indexOf('&', rest)

                if (ampersandIndex == -1) {
                    // No more entities, add the remaining content
                    builder.append(content, rest, content.length)
                    break
                }

                // A '&' that we don't know what to do with: no further decoding.
                val (entityLength, replacement) = HtmlEntities.tryGetHtmlEntity(content, ampersandIndex) ?: return null

                // The text before the entity, then the replacement text of the entity.
                if (ampersandIndex > rest) builder.append(content, rest, ampersandIndex)

                builder.append(replacement)

                // Skip past the processed entity and continue.
                rest = ampersandIndex + entityLength
            }

            assert(builder.isNotEmpty()) { "How could builder be empty if content was not?" }

            return builder.toString()
        }

        private companion object {
            val ENCODED_CHARACTERS = setOf('\r', '\n', '\t', '<', '>')
        }
    }
}

/**
 * `ParserHelpers.TryGetHtmlEntity` and its tables (`Legacy/ParserHelpers.cs`): the tables are the resource `HtmlEntities.txt`,
 * generated from the compiler's source.
 */
internal object HtmlEntities {
    private val namedHtmlEntities: Map<String, String>
    private val htmlEntityCodePoints: Map<Int, String>

    init {
        val named = HashMap<String, String>()
        val codePoints = HashMap<Int, String>()
        val stream = HtmlEntities::class.java.getResourceAsStream("HtmlEntities.txt") ?: throw IllegalStateException("HtmlEntities.txt is missing")
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split('\t')
                val value = String(parts[2].split(' ').map { it.toInt(16).toChar() }.toCharArray())
                when (parts[0]) {
                    "N" -> named[parts[1]] = value
                    "C" -> codePoints[parts[1].toInt()] = value
                }
            }
        }
        namedHtmlEntities = named
        htmlEntityCodePoints = codePoints
    }

    /**
     * The entity at [start] of [content] (which is a `&`): its length and its replacement; null when it is no known entity. The entity
     * runs to the first `;` after letters, digits and `#`; `&#N;` / `&#xN;` (and `&#0xN;`, technically illegal but supported by Razor)
     * are code points — the special ones of the table, or the printable characters of the BMP outside the surrogates.
     */
    fun tryGetHtmlEntity(content: CharSequence, start: Int): Pair<Int, String>? {
        if (start >= content.length || content[start] != '&') return null

        // We're at a '&'. Scan forward to find the end of the entity.
        var entityEnd = -1
        for (i in start + 1 until content.length) {
            val ch = content[i]

            if (!Character.isLetterOrDigit(ch) && ch != '#') {
                // Found the end of an entity. +1 to include the ';'
                if (ch == ';') entityEnd = i + 1

                // Invalid character for an entity. Bail.
                break
            }

            // Keep going until we find a non-entity character.
        }

        // Did not find the end of an entity.
        if (entityEnd < 0) return null

        val entity = content.subSequence(start, entityEnd).toString()
        val length = entity.length

        if (entity[1] == '#') {
            // Extract the codepoint and map it to an entity: '&#****;'.
            var digits = entity.substring(2, length - 1)
            var hex = false

            if (digits.length >= 1 && (digits[0] == 'x' || digits[0] == 'X')) {
                // &#x41; or &#X41;
                hex = true
                digits = digits.substring(1)
            } else if (digits.length >= 2 && digits[0] == '0' && (digits[1] == 'x' || digits[1] == 'X')) {
                // &#0x41; or &#0X41; (Technically illegal but supported by Razor)
                hex = true
                digits = digits.substring(2)
            }

            val codePoint = if (hex) parseHex(digits) else parseDecimal(digits)

            if (codePoint != null) {
                // First try the special HTML entity code points dictionary
                htmlEntityCodePoints[codePoint]?.let { return length to it }

                // Basic printable Unicode characters, converted directly (as browsers typically do): from 0x20 (no control characters)
                // to 0xFFFF (the Basic Multilingual Plane), without the surrogate range 0xD800-0xDFFF.
                if (codePoint in 0x20..0xFFFF && (codePoint < 0xD800 || codePoint > 0xDFFF)) return length to codePoint.toChar().toString()
            }
        } else {
            namedHtmlEntities[entity]?.let { return length to it }
        }

        // The '&' is not part of a known HTML entity.
        return null
    }

    /** `int.TryParse(digits, NumberStyles.Integer, InvariantCulture)` of ASCII digits; null on failure or overflow. */
    private fun parseDecimal(digits: String): Int? {
        if (digits.isEmpty()) return null
        var value = 0L
        for (c in digits) {
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
            if (value > Int.MAX_VALUE) return null
        }
        return value.toInt()
    }

    /** `int.TryParse(digits, NumberStyles.HexNumber, InvariantCulture)`: up to 32 bits, the high bit making it negative. */
    private fun parseHex(digits: String): Int? {
        if (digits.isEmpty()) return null
        var value = 0L
        for (c in digits) {
            val d = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> return null
            }
            value = value * 16 + d
            if (value > 0xFFFFFFFFL) return null
        }
        return value.toInt()
    }
}
