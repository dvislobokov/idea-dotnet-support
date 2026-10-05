package io.github.dotnetsupport

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseFieldDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpVariableDeclarator
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.CSharpFile
import io.github.dotnetsupport.csharp.semantic.CSharpSemanticModel
import io.github.dotnetsupport.csharp.semantic.CSharpSymbolRef
import io.github.dotnetsupport.csharp.semantic.CSharpTypeRef
import io.github.dotnetsupport.lang.CSharpLeaves
import io.github.dotnetsupport.lang.NativeCSharpNavigation
import io.github.dotnetsupport.lang.NativeCSharpScopes
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.NativeCSharpSemanticModel

/**
 * The baseline of the semantic gate (CSHARP_PSI_MIGRATION.md, task C0): what syntax alone resolves today, before layer 11a. Names: the
 * scopes of the file and the stub index, through Go to Declaration of the native tree (`NativeCSharpNavigation`, task A2: locals,
 * parameters, labels, members of the own type by name, types of the solution a `using` brings in) and the declarations the file makes.
 * Types: literals only. Diagnostics: none. A read-only adapter: the resolver behind it is not changed for the gate.
 */
object SyntacticSemanticModel : CSharpSemanticModel {
    override fun symbolAt(file: PsiFile, offset: Int): CSharpSymbolRef? {
        val leaf = file.findElementAt(offset)?.takeIf { it.textRange.startOffset == offset && CSharpLeaves.isIdentifier(it) } ?: return null
        NativeCSharpNavigation.targets(leaf)?.let { return CSharpSymbolRef(it) }
        val csharp = file as? CSharpFile ?: return null
        // the name of a local declaration resolves to itself
        NativeCSharpScopes.of(csharp).symbolAt(leaf)?.takeIf { it.declaration == leaf || nameOf(it.declaration) == leaf }?.let { return CSharpSymbolRef(listOf(leaf)) }
        val declaration = leaf.parent?.takeIf { it in NativeCSharpScopes.of(csharp).declarations } ?: return null
        return if (nameOf(declaration) == leaf) CSharpSymbolRef(listOf(leaf)) else null
    }

    override fun typeOf(file: PsiFile, range: TextRange): CSharpTypeRef? {
        var element: PsiElement? = file.findElementAt(range.startOffset)
        while (element != null && element !is PsiFile && element.textRange.startOffset == range.startOffset) {
            if (element.textRange == range) literalType(element)?.let { return CSharpTypeRef(it) }
            element = element.parent
        }
        return null
    }

    private fun literalType(element: PsiElement): String? = when (element.elementType) {
        SyntaxKind.StringLiteralExpression, SyntaxKind.InterpolatedStringExpression -> "string"
        SyntaxKind.CharacterLiteralExpression -> "char"
        SyntaxKind.TrueLiteralExpression, SyntaxKind.FalseLiteralExpression -> "bool"
        SyntaxKind.Utf8StringLiteralExpression -> "System.ReadOnlySpan<byte>"
        SyntaxKind.NumericLiteralExpression -> numericType(element.text)
        else -> null
    }

    /** C# spec 6.4.5.3: the suffix decides, else the first of int, uint, long, ulong that holds the value; a real without suffix is a double. */
    fun numericType(text: String): String? {
        val t = text.replace("_", "").lowercase()
        val hex = t.startsWith("0x")
        val binary = t.startsWith("0b")
        if (!hex && !binary) {
            if (t.endsWith("m")) return "decimal"
            if (t.endsWith("f")) return "float"
            if (t.endsWith("d") || '.' in t || 'e' in t) return "double"
        }
        val suffix = t.takeLastWhile { it == 'u' || it == 'l' }
        val digits = t.dropLast(suffix.length).let { if (hex || binary) it.drop(2) else it }
        val value = digits.toBigIntegerOrNull(if (hex) 16 else if (binary) 2 else 10) ?: return null
        val fitsInt = value <= Int.MAX_VALUE.toBigInteger()
        val fitsUInt = value <= 0xFFFFFFFFL.toBigInteger()
        val fitsLong = value <= Long.MAX_VALUE.toBigInteger()
        return when (suffix) {
            "" -> if (fitsInt) "int" else if (fitsUInt) "uint" else if (fitsLong) "long" else "ulong"
            "u" -> if (fitsUInt) "uint" else "ulong"
            "l" -> if (fitsLong) "long" else "ulong"
            else -> "ulong"
        }
    }

    /** The name identifier of a declaration: its own identifier leaf (a field by its declarator). */
    fun nameOf(declaration: PsiElement): PsiElement? {
        if (CSharpLeaves.isIdentifier(declaration)) return declaration
        (declaration as? PsiNameIdentifierOwner)?.nameIdentifier?.let { return it }
        if (declaration is CSharpBaseFieldDeclaration) return PsiTreeUtil.findChildOfType(declaration, CSharpVariableDeclarator::class.java)?.identifier
        var child = declaration.firstChild
        while (child != null) {
            if (CSharpLeaves.isIdentifier(child)) return child
            child = child.nextSibling
        }
        return null
    }
}

/**
 * The gate's model since task C1 (layer 11a): the native resolver ([NativeCSharpSemanticModel], [CSharpNameResolver]) for names — its
 * candidates count as no answer — and, where it has none, the syntactic baseline (declarations name themselves). Types (task C2): the
 * resolver's expressionType, the baseline's literals where it has none. One [CSharpSemanticSession] for an input whose files do not change
 * while they are compared; [reset] between inputs.
 */
class ResolvingSemanticModel(private val project: com.intellij.openapi.project.Project) : CSharpSemanticModel {
    private var model = NativeCSharpSemanticModel(CSharpSemanticSession(project))

    fun reset() {
        model = NativeCSharpSemanticModel(CSharpSemanticSession(project))
    }

    override fun symbolAt(file: PsiFile, offset: Int): CSharpSymbolRef? {
        val native = model.symbolAt(file, offset)
        return native ?: SyntacticSemanticModel.symbolAt(file, offset)
    }

    override fun typeOf(file: PsiFile, range: TextRange): CSharpTypeRef? = model.typeOf(file, range) ?: SyntacticSemanticModel.typeOf(file, range)
}

/**
 * A [CSharpSemanticModel] over one PSI file, in the terms of the oracle: places are `path:offset` of the declared names with paths relative
 * to [root] (where the gate put the sources, mirroring the dump's root).
 */
class SemanticModelAnswers(private val model: CSharpSemanticModel, private val file: PsiFile, private val root: VirtualFile) : SemanticAnswers {
    override fun symbolAt(offset: Int): SemanticAnswer? {
        val symbol = model.symbolAt(file, offset) ?: return null
        return answer(symbol)
    }

    private fun answer(symbol: CSharpSymbolRef): SemanticAnswer? {
        val places = symbol.declarations.mapNotNull(::place)
        if (places.isEmpty() && symbol.id == null) {
            val candidates = symbol.candidates.mapNotNull(::answer)
            return if (candidates.isEmpty()) null else SemanticAnswer(emptyList(), null, candidates)
        }
        return SemanticAnswer(places, symbol.id)
    }

    override fun typeOf(start: Int, end: Int): String? = model.typeOf(file, TextRange(start, end))?.display

    override fun diagnostics(): List<SemanticDump.Diagnostic> =
        model.diagnostics(file).map { SemanticDump.Diagnostic(it.range.startOffset, it.range.endOffset, it.code, it.isError) }

    private fun place(element: PsiElement): String? {
        val vFile = element.containingFile?.virtualFile ?: return null
        val path = VfsUtilCore.getRelativePath(vFile, root) ?: return null
        val name = SyntacticSemanticModel.nameOf(element) ?: element
        return "$path:${name.textRange.startOffset}"
    }
}
