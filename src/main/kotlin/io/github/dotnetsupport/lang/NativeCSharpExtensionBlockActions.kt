package io.github.dotnetsupport.lang

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.CSharpLanguageLevel
import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion
import io.github.dotnetsupport.csharp.lang.psi.*

/**
 * The extension methods of a static class turned into a C# 14 `extension` block (0.1.173), as Rider's "Convert to extension block (N
 * methods)" (every method of the class with the same receiver) and "Convert this method to extension block": the receiver goes to the
 * block's header, `static` and the `this` parameter leave the members. Only where the file's language version is C# 14 or newer; not for
 * generic methods (the block would need the method's type parameters), nested or generic classes.
 */
object NativeCSharpExtensionBlockEdits {
    /** The file's `LangVersion` allows extension blocks (C# 14, `preview`, `latest`, the default of `net10.0`). */
    fun allowed(file: PsiFile): Boolean = CSharpLanguageLevel.forFile(file).effective().value >= CSharpLanguageVersion.CSharp14.value

    /** The extension method whose header (attributes and modifiers to the end of the parameter list) holds [offset]. */
    fun methodAt(file: PsiFile, offset: Int): CSharpMethodDeclaration? {
        val leaves = listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)
        for (leaf in leaves) {
            val method = PsiTreeUtil.getParentOfType(leaf, CSharpMethodDeclaration::class.java, false) ?: continue
            val end = method.parameterList?.textRange?.endOffset ?: continue
            if (offset < method.textRange.startOffset || offset > end) continue
            return method.takeIf(::convertible)
        }
        return null
    }

    private fun receiver(method: CSharpMethodDeclaration): CSharpParameter? =
        method.parameterList?.parameters?.firstOrNull()?.takeIf { p -> p.modifiers.any { it.text == "this" } }

    /** An extension method of a top-level, non-generic static class, itself not generic. */
    fun convertible(method: CSharpMethodDeclaration): Boolean {
        if (method.typeParameterList != null || method.modifiers.none { it.text == "static" }) return false
        receiver(method) ?: return false
        val owner = method.parent as? CSharpClassDeclaration ?: return false
        if (owner.typeParameterList != null || owner.modifiers.none { it.text == "static" }) return false
        return owner.parent !is CSharpBaseTypeDeclaration
    }

    /** The receiver as the block's header writes it: its modifiers but `this`, its type and name. */
    private fun receiverKey(method: CSharpMethodDeclaration): String? {
        val p = receiver(method) ?: return null
        val modifiers = p.modifiers.map { it.text }.filter { it != "this" }
        return (modifiers + listOfNotNull(p.type?.text, p.identifier?.text)).joinToString(" ")
    }

    /** The methods of [method]'s class with the same receiver (type and name), in their order. */
    fun group(method: CSharpMethodDeclaration): List<CSharpMethodDeclaration> {
        val key = receiverKey(method) ?: return emptyList()
        val owner = method.parent as? CSharpClassDeclaration ?: return emptyList()
        return owner.members.filterIsInstance<CSharpMethodDeclaration>().filter { convertible(it) && receiverKey(it) == key }
    }

    private fun lineStart(text: CharSequence, offset: Int): Int = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    private fun lineEnd(text: CharSequence, offset: Int): Int = text.indexOf('\n', offset).let { if (it < 0) text.length else it + 1 }

    /** The lines of [method] with its doc comment and comments right above it: from their line start to the end of its last line. */
    private fun chunk(method: CSharpMethodDeclaration, text: CharSequence): IntRange {
        var start = lineStart(text, method.textRange.startOffset)
        while (start > 0) {
            val previous = lineStart(text, start - 1)
            val line = text.subSequence(previous, start).trim()
            if (line.startsWith("//") || line.startsWith("[")) start = previous else break
        }
        return start until lineEnd(text, method.textRange.endOffset)
    }

    /** The member of the block: `static` and the receiver parameter gone, each line one [unit] deeper. */
    private fun member(method: CSharpMethodDeclaration, text: CharSequence, range: IntRange, unit: String): String {
        val base = range.first
        val builder = StringBuilder(text.subSequence(range.first, range.last + 1))
        val removals = ArrayList<Pair<Int, Int>>()
        method.modifiers.firstOrNull { it.text == "static" }?.let { m ->
            var to = m.textRange.endOffset
            while (to < text.length && (text[to] == ' ' || text[to] == '\t')) to++
            removals += m.textRange.startOffset to to
        }
        val parameters = method.parameterList!!.parameters
        val first = parameters.first()
        val to = if (parameters.size > 1) parameters[1].textRange.startOffset else first.textRange.endOffset
        removals += first.textRange.startOffset to to
        for ((from, end) in removals.sortedByDescending { it.first }) builder.delete(from - base, end - base)
        return builder.toString().trimEnd('\n').lines().joinToString("\n") { if (it.isBlank()) "" else unit + it } + "\n"
    }

    /**
     * [methods] (one class, one receiver) replaced by `extension(<receiver>) { … }` where the first of them was, the members as they were
     * but for `static` and the receiver, a blank line between them.
     */
    fun convert(methods: List<CSharpMethodDeclaration>, text: CharSequence, unit: String): CSharpTextEdit? {
        if (methods.isEmpty()) return null
        val owner = methods.first().parent as? CSharpClassDeclaration ?: return null
        val header = receiverKey(methods.first()) ?: return null
        val chunks = methods.map { chunk(it, text) }
        val indent = text.subSequence(lineStart(text, methods.first().textRange.startOffset), methods.first().textRange.startOffset).takeWhile { it == ' ' || it == '\t' }
        val block = StringBuilder()
        block.append(indent).append("extension(").append(header).append(")\n").append(indent).append("{\n")
        block.append(methods.indices.joinToString("\n") { i -> member(methods[i], text, chunks[i], unit) })
        block.append(indent).append("}\n")
        // the class's text with the chunks cut out and the block where the first chunk was; a later chunk takes the blank line after it
        // along (or the one before it, when it is the last member), so no double blank lines are left
        val range = owner.textRange
        val cuts = chunks.mapIndexed { i, c ->
            var from = c.first
            var end = c.last + 1
            if (i > 0) {
                val next = lineEnd(text, end)
                if (end < range.endOffset && next > end && text.subSequence(end, next).isBlank()) end = next
                else if (from > 0) {
                    val previous = lineStart(text, from - 1)
                    if (text.subSequence(previous, from).isBlank()) from = previous
                }
            }
            from to end
        }.sortedBy { it.first }.toMutableList()
        for (i in 1 until cuts.size) if (cuts[i].first < cuts[i - 1].second) cuts[i] = cuts[i - 1].second to cuts[i].second
        val out = StringBuilder(text.subSequence(range.startOffset, range.endOffset))
        for (i in cuts.indices.reversed()) {
            val (from, end) = cuts[i]
            out.delete(from - range.startOffset, end - range.startOffset)
            if (i == 0) out.insert(from - range.startOffset, block.toString())
        }
        return CSharpTextEdit(range, out.toString())
    }
}

/** Alt+Enter on an extension method: every extension method of the class with its receiver in one `extension` block (Rider's "(N methods)"). */
class NativeCSharpConvertToExtensionBlockIntention : NativeCSharpContextAction("Convert to extension block") {
    @Volatile private var count = 0

    override fun getText(): String = "Convert to extension block ($count methods)"

    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        if (!NativeCSharpExtensionBlockEdits.allowed(file)) return null
        val method = NativeCSharpExtensionBlockEdits.methodAt(file, editor.caretModel.offset) ?: return null
        val group = NativeCSharpExtensionBlockEdits.group(method)
        if (group.size < 2) return null
        count = group.size
        return NativeCSharpExtensionBlockEdits.convert(group, editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}

/** Alt+Enter on an extension method: that one method in an `extension` block of its receiver (Rider: "Convert this method to extension block"). */
class NativeCSharpConvertMethodToExtensionBlockIntention : NativeCSharpContextAction("Convert this method to extension block") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        if (!NativeCSharpExtensionBlockEdits.allowed(file)) return null
        val method = NativeCSharpExtensionBlockEdits.methodAt(file, editor.caretModel.offset) ?: return null
        return NativeCSharpExtensionBlockEdits.convert(listOf(method), editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}
