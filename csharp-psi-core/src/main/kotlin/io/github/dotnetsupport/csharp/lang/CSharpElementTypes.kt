package io.github.dotnetsupport.csharp.lang

import com.intellij.psi.tree.IElementType

/** A token or trivia kind of Roslyn's `SyntaxKind` (see [SyntaxKind]); the debug name is the Roslyn kind name. */
class CSharpTokenType(debugName: String) : IElementType(debugName, CSharpLanguage) {
    override fun toString(): String = debugName
}

/** A node kind of Roslyn's `SyntaxKind` (see [SyntaxKind]); the debug name is the Roslyn kind name. */
class CSharpElementType(debugName: String) : IElementType(debugName, CSharpLanguage) {
    override fun toString(): String = debugName
}
