package io.github.dotnetsupport.lang

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiDocumentManager
import io.github.dotnetsupport.csharp.lang.CSharpFile
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * The declarations behind the variables of a stopped frame: the debugger knows a local by its name only, the source knows what it was
 * declared as (the element names of a tuple live there and nowhere in the running program, as Rider shows them).
 */
object CSharpFrameLocals {
    /**
     * The semantic types of the locals and parameters named [names] that the code at zero-based [line] of [file] sees: of each name the
     * declaration in the innermost scope around the line, declared before its end. Empty for the heuristic tree and in dumb mode. Under a
     * read action.
     */
    fun declaredTypes(file: CSharpFile, line: Int, names: Collection<String>): Map<String, SemanticType> {
        if (names.isEmpty() || file.compilationUnit == null || DumbService.isDumb(file.project)) return emptyMap()
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return emptyMap()
        if (line !in 0 until document.lineCount) return emptyMap()
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val text = document.immutableCharSequence
        // the statement of the line, not the indentation before it: that may still be outside a nested block
        val at = (lineStart until lineEnd).firstOrNull { !text[it].isWhitespace() } ?: lineStart
        val resolver = CSharpSemanticSession(file.project).resolver(file)
        val wanted = names.toSet()
        val found = HashMap<String, SemanticType>()
        resolver.syntax.scopes.symbols
            .filter { it.name in wanted && (it.kind == LocalSymbolKind.LOCAL || it.kind == LocalSymbolKind.PARAMETER) }
            .filter { it.scope.textRange.contains(at) && it.declaration.textRange.startOffset <= lineEnd }
            .groupBy { it.name }
            .forEach { (name, candidates) ->
                // the innermost scope hides the outer ones; in one scope the last declaration before the line is the live one
                val symbol = candidates.minWith(compareBy({ it.scope.textRange.length }, { -it.declaration.textRange.startOffset }))
                val type = try {
                    resolver.valueType(CSharpSymbol.Local(symbol))
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (_: RuntimeException) {
                    null
                }
                if (type != null) found[name] = type
            }
        return found
    }
}
