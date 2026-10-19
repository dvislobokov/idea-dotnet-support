package io.github.dotnetsupport.frameworks.razor

import io.github.dotnetsupport.razor.lang.codegen.IntermediateNodePassBase
import io.github.dotnetsupport.razor.lang.codegen.RazorCodeDocument
import io.github.dotnetsupport.razor.lang.codegen.RazorOptimizationPass
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentInjectIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentRenderModeDirectivePass
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.ClassDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan
import io.github.dotnetsupport.razor.lang.codegen.mvc.InjectIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MethodDeclarationIntermediateNode

/**
 * The layout of the IDE's document (RAZOR_PLAN.md, R3b): the platform concatenates the pieces of an injection in the order of their hosts,
 * so the C# of the Razor file must come out in the order of the file. The compiler's class has the render method first and the members of
 * `@code` / `@functions` and the properties of `@inject` after it; this pass, run in the IDE only, puts every member of the primary class at
 * its place in the file — a member whose source lies between two statements of the render method splits the method there, the rest of
 * the statements go to a second method of the same shape (`BuildRenderTree__2`), as a design-time document did. The C# stays valid, the
 * output of the compiler's gates is untouched (the pass is not part of the engine of the gates).
 *
 * An `@inject` between statements does not split the method: the locals of a code block before it were not seen by the markup after it
 * (nopCommerce's ProductReview/List.cshtml, `@inject IStoreService storeService` after `@{ var hideSearchBlock = … }`: CS0103 for
 * `hideSearchBlock` — the harness, 0.1.289). Its property goes after the method without a mapping to the file: the class is the same, the
 * pieces stay in the order of the file; its line has no C# of its own in the editor then (no completion in the type of such an `@inject`).
 */
class RazorIdeLayoutPass : IntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = Int.MAX_VALUE / 2 // after every optimization pass of the compiler

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val path = codeDocument.source.filePath ?: return
        val clazz = documentNode.findPrimaryClass() ?: return
        mapRenderMode(codeDocument, clazz, documentNode)
        val method = documentNode.findPrimaryMethod() ?: return
        val methodIndex = clazz.children.indexOf(method)
        if (methodIndex < 0) return
        val members = clazz.children.toList()
        val statements = method.children.toList()
        val statementOffsets = statements.mapNotNull { firstOffset(it, path) }
        val first = statementOffsets.minOrNull()
        val last = statementOffsets.maxOrNull()
        // an `@inject` that would split the method: unmapped, after it
        val unmapped = HashMap<IntermediateNode, IntermediateNode>()
        val placed = members.filter { it !== method }.mapNotNull { m ->
            val offset = firstOffset(m, path) ?: return@mapNotNull null
            if (first != null && last != null && offset > first && offset < last && isInject(m)) { unmapped[m] = withoutMapping(m); null } else m to offset
        }.sortedBy { it.second }
        if (placed.isEmpty() && unmapped.isEmpty()) return
        val unmappedBefore = members.subList(0, methodIndex).filter { m -> placed.none { it.first === m } && m !in unmapped }
        val unmappedAfter = members.subList(methodIndex + 1, members.size).filter { m -> placed.none { it.first === m } && m !in unmapped } +
            members.mapNotNull { unmapped[it] }
        method.children.clear()

        val result = ArrayList<IntermediateNode>(unmappedBefore)
        var run: MethodDeclarationIntermediateNode? = null
        var runs = 0
        var next = 0
        var lastOffset = -1
        fun flush() {
            run?.let { if (it.children.isNotEmpty()) result += it }
            run = null
        }
        for (statement in statements) {
            val offset = firstOffset(statement, path) ?: lastOffset
            lastOffset = maxOf(lastOffset, offset)
            while (next < placed.size && placed[next].second < offset) {
                flush()
                result += placed[next++].first
            }
            if (run == null) {
                // the first run keeps the compiler's method (the override); the next ones are private methods of the same shape
                run = if (++runs == 1) method else MethodDeclarationIntermediateNode().also {
                    it.name = method.name + "__" + runs
                    it.returnType = method.returnType
                    it.modifiers = listOf("private") + method.modifiers.filter { m -> m != "override" && m != "public" && m != "protected" && m != "private" }
                    it.parameters = method.parameters
                }
            }
            run!!.children.add(statement)
        }
        flush()
        while (next < placed.size) result += placed[next++].first
        if (runs == 0) result += method // no statement at all: the override stays, empty
        result += unmappedAfter
        clazz.children.clear()
        clazz.children.addAll(result)
    }

    /**
     * `@rendermode InteractiveServer`: below Razor 11 the compiler writes the mode without a mapping (hot reload), so in a .NET 8 / 9
     * project its line had no C# in the editor — no completion of `@rendermode Inter` (the WSL sandbox, 0.1.295). The editor's document maps
     * it at every version: the mode's token and its node get the directive token's span, as Razor 11 does; the output of the compiler is
     * untouched (this pass is the IDE's). Before the layout below, which puts the attribute class at the directive's place in the file. The
     * directive's node is gone by now (`DirectiveRemovalOptimizationPass`): the span is the mode's text right after `@rendermode`.
     */
    private fun mapRenderMode(codeDocument: RazorCodeDocument, clazz: IntermediateNode, documentNode: DocumentIntermediateNode) {
        val holder = (clazz.children + documentNode.findPrimaryNamespace()?.children.orEmpty())
            .firstOrNull { it is ClassDeclarationIntermediateNode && it.name == ComponentRenderModeDirectivePass.GENERATED_RENDER_MODE_ATTRIBUTE_NAME } ?: return
        val text = codeDocument.source.text
        for (code in holder.children) for (inner in code.children) {
            if (inner !is CSharpCodeIntermediateNode || inner.source != null) continue
            val mode = inner.children.singleOrNull() as? CSharpIntermediateToken ?: continue
            val content = mode.content
            if (mode.source != null || content.isBlank()) continue
            val start = RENDER_MODE.findAll(text).map { it.range.last + 1 }
                .firstOrNull { it + content.length <= text.length && text.subSequence(it, it + content.length).toString() == content } ?: return
            val line = (0 until start).count { text[it] == '\n' }
            val column = start - (text.lastIndexOf('\n', start - 1) + 1)
            val source = SourceSpan(codeDocument.source.filePath, start, line, column, content.length)
            inner.source = source
            mode.source = source
            return
        }
    }

    private val RENDER_MODE = Regex("""@rendermode[ \t]+""")

    private fun isInject(node: IntermediateNode): Boolean = node is InjectIntermediateNode || node is ComponentInjectIntermediateNode

    /** [node] with no mapping to the file: its C# is written as glue. */
    private fun withoutMapping(node: IntermediateNode): IntermediateNode = when (node) {
        is InjectIntermediateNode -> node.also { it.typeSource = null; it.memberSource = null; it.source = null }
        is ComponentInjectIntermediateNode -> ComponentInjectIntermediateNode(node.typeName, node.memberName, null, null)
        else -> node
    }

    /** The first offset of [path] a node or its descendants map to; null when nothing maps. */
    private fun firstOffset(node: IntermediateNode, path: String): Int? {
        var best: Int? = null
        fun consider(offset: Int?) {
            if (offset != null && (best == null || offset < best!!)) best = offset
        }
        fun walk(n: IntermediateNode) {
            n.source?.takeIf { it.filePath == path }?.let { consider(it.absoluteIndex) }
            when (n) {
                is ComponentInjectIntermediateNode -> { consider(n.typeSpan?.takeIf { it.filePath == path }?.absoluteIndex); consider(n.memberSpan?.takeIf { it.filePath == path }?.absoluteIndex) }
                is InjectIntermediateNode -> { consider(n.typeSource?.takeIf { it.filePath == path }?.absoluteIndex); consider(n.memberSource?.takeIf { it.filePath == path }?.absoluteIndex) }
                else -> {}
            }
            for (child in n.children) walk(child)
        }
        walk(node)
        return best
    }
}
