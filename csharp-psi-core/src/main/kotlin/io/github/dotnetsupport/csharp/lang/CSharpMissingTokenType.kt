package io.github.dotnetsupport.csharp.lang

import com.intellij.psi.tree.IElementType
import java.util.concurrent.ConcurrentHashMap

/**
 * A missing token of Roslyn's tree (`CreateMissingToken(kind)`, `SyntaxFactory.MissingToken(kind)`): an empty composite
 * whose type names the token kind, so the PSI (`CSharpSyntaxShape`) knows which field the gap stands for without
 * reading a message. In a file's tree the parser makes one for every missing token (`SyntaxParser.createMissingToken`);
 * when Roslyn reports the token, the composite holds a zero-width error element with the diagnostic (highlighting is
 * unchanged), otherwise it is empty. Inside a doc comment (`DocCommentTreeBuilder`) it is always empty: Roslyn reports
 * nothing about the XML with DocumentationMode.Parse. Not a node of Roslyn's tree: the gates unwrap it, its PSI is a
 * plain `ASTWrapperPsiElement` that no accessor returns (docs/csharp-psi/GRAMMAR.md, "PSI").
 */
class CSharpMissingTokenType private constructor(val tokenKind: IElementType) :
    IElementType("$tokenKind (missing)", CSharpLanguage) {
    companion object {
        private val types = ConcurrentHashMap<IElementType, CSharpMissingTokenType>()

        fun of(kind: IElementType): CSharpMissingTokenType = types.computeIfAbsent(kind, ::CSharpMissingTokenType)
    }
}
