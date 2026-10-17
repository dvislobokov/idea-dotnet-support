package io.github.dotnetsupport.frameworks.razor

import io.github.dotnetsupport.razor.lang.codegen.IntermediateNodePassBase
import io.github.dotnetsupport.razor.lang.codegen.RazorCodeDocument
import io.github.dotnetsupport.razor.lang.codegen.RazorOptimizationPass
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentInjectIntermediateNode
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
 */
class RazorIdeLayoutPass : IntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = Int.MAX_VALUE / 2 // after every optimization pass of the compiler

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val path = codeDocument.source.filePath ?: return
        val clazz = documentNode.findPrimaryClass() ?: return
        val method = documentNode.findPrimaryMethod() ?: return
        val methodIndex = clazz.children.indexOf(method)
        if (methodIndex < 0) return
        val members = clazz.children.toList()
        val placed = members.filter { it !== method }.mapNotNull { m -> firstOffset(m, path)?.let { m to it } }.sortedBy { it.second }
        if (placed.isEmpty()) return
        val unmappedBefore = members.subList(0, methodIndex).filter { m -> placed.none { it.first === m } }
        val unmappedAfter = members.subList(methodIndex + 1, members.size).filter { m -> placed.none { it.first === m } }
        val statements = method.children.toList()
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
