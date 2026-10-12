package io.github.dotnetsupport.csharp.semantic

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * The semantics of C# files, as Roslyn's `SemanticModel` answers it (CSHARP_PSI_MIGRATION.md, step 11): what a name binds to (layer 11a),
 * the type of an expression (11b), the compiler's diagnostics (11e). The native resolver implements it layer by layer; every layer is
 * measured against Roslyn by the semantic gate (`./gradlew semanticGate`, the oracle `roslyndump semantics`). Null: not known (yet).
 */
interface CSharpSemanticModel {
    /** The symbol the identifier starting at [offset] of [file] stands for, or declares. */
    fun symbolAt(file: PsiFile, offset: Int): CSharpSymbolRef?

    /** The natural type of the expression spanning [range] of [file] (Roslyn's `TypeInfo.Type`). */
    fun typeOf(file: PsiFile, range: TextRange): CSharpTypeRef?

    /** Errors and warnings of [file], as the compiler reports them. */
    fun diagnostics(file: PsiFile): List<CSharpDiagnosticRef> = emptyList()
}

/**
 * A symbol by identity: the name identifiers (or declarations) that declare it in source — every part of a partial type — and, when the
 * resolver knows it, the documentation comment id of its definition (`T:System.Console`, `M:N.C.M(System.Int32)`: Roslyn's
 * `DocumentationCommentId`), the identity of a symbol of a referenced assembly.
 */
class CSharpSymbolRef(
    val declarations: List<PsiElement> = emptyList(),
    val id: String? = null,
    /** When the resolver cannot pick one symbol (overloads it does not tell apart, two imported types of one name): every candidate. */
    val candidates: List<CSharpSymbolRef> = emptyList(),
)

/** A type by its fully qualified display string: `System.Collections.Generic.List<int>`, `int[]`, `(int a, string b)` (no `global::`, keywords for special types). */
class CSharpTypeRef(val display: String)

/** A diagnostic of the compiler: its id (`CS0103`) and range. */
class CSharpDiagnosticRef(val code: String, val range: TextRange, val isError: Boolean = true)
