package io.github.dotnetsupport.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil

/**
 * [CSharpSyntaxModel] over the heuristics of today: [CSharpDeclarations] scans the declarations, [CSharpTreeBuilder] groups the
 * tokens into a [CSharpDeclaration] node per declaration, and the attributed methods are found by tokens. Goes away with the
 * swap of the parser (CSHARP_PSI_MIGRATION.md, step 7).
 */
object HeuristicCSharpSyntaxModel : CSharpSyntaxModel {
    override fun declarations(text: CharSequence): CSharpFileStructure = CSharpDeclarations.scan(text)

    override fun declarations(file: PsiFile): CSharpFileStructure = CSharpStructure.of(file)

    override fun declarationOf(element: PsiElement): CSharpDeclarationInfo? = (element as? CSharpDeclaration)?.info

    override fun childDeclarations(parent: PsiElement): List<NavigatablePsiElement> = PsiTreeUtil.getChildrenOfTypeAsList(parent, CSharpDeclaration::class.java)

    override fun declarationElementAt(file: PsiFile, offset: Int): NavigatablePsiElement? =
        file.findElementAt(offset)?.let { PsiTreeUtil.getParentOfType(it, CSharpDeclaration::class.java) }

    override fun attributedMethods(text: CharSequence, attributes: Set<String>): CSharpAttributedMethods = AttributedMethodScanner.scan(text, attributes)
}

/**
 * Methods preceded by an attribute of a given name, by tokens: a method directly inside a type (`class`, `struct`, `record`; not an
 * interface), the attribute in a list of its own or among others (`[Theory, Trait("a", "b")]`, `[Xunit.Fact]`). There is no parser, so
 * attributes are matched by the simple name as written.
 */
private object AttributedMethodScanner {
    private val TYPE_KEYWORDS = setOf("class", "struct", "record")

    private class Token(val type: IElementType, val text: String, val start: Int, val end: Int)
    private class Scope(val name: String, val isType: Boolean, val depth: Int, val nameRange: TextRange?)

    fun scan(text: CharSequence, attributes: Set<String>): CSharpAttributedMethods {
        val tokens = tokenize(text)
        val methods = ArrayList<CSharpAttributedMethod>()
        val types = ArrayList<Pair<String, TextRange>>()
        val scopes = ArrayList<Scope>()
        var fileNamespace = ""
        var depth = 0
        var parentheses = 0
        var pendingScope: Scope? = null   // `class Foo` / `namespace A.B` seen, waiting for its `{`
        var attributeSeen = false

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val inTypeBody = scopes.lastOrNull()?.let { it.isType && depth == it.depth + 1 } == true
            when {
                token.type == CSharpTokenTypes.LPAREN -> parentheses++
                token.type == CSharpTokenTypes.RPAREN -> parentheses--
                token.type == CSharpTokenTypes.LBRACE -> {
                    pendingScope?.let { scope ->
                        scopes += scope
                        if (scope.isType) types += qualifiedName(fileNamespace, scopes) to scope.nameRange!!
                    }
                    pendingScope = null
                    attributeSeen = false
                    depth++
                }
                token.type == CSharpTokenTypes.RBRACE -> {
                    depth--
                    while (scopes.isNotEmpty() && scopes.last().depth >= depth) scopes.removeAt(scopes.lastIndex)
                    attributeSeen = false
                }
                token.type == CSharpTokenTypes.SEMICOLON && parentheses == 0 -> {
                    // `namespace A.B;` applies to the rest of the file; `record Foo(int X);` has no body
                    pendingScope?.takeIf { !it.isType }?.let { fileNamespace = it.name }
                    pendingScope = null
                    attributeSeen = false
                }
                token.type == CSharpTokenTypes.KEYWORD && token.text == "namespace" -> {
                    val name = StringBuilder()
                    while (tokens.getOrNull(i + 1)?.let { it.type == CSharpTokenTypes.IDENTIFIER || it.type == CSharpTokenTypes.DOT } == true) name.append(tokens[++i].text)
                    pendingScope = Scope(name.toString(), isType = false, depth = depth, nameRange = null)
                }
                token.type == CSharpTokenTypes.KEYWORD && token.text in TYPE_KEYWORDS && tokens.getOrNull(i + 1)?.type == CSharpTokenTypes.IDENTIFIER &&
                    tokens.getOrNull(i - 1)?.let { it.type == CSharpTokenTypes.OPERATOR && it.text == ":" } != true -> {
                    val name = tokens[++i]
                    pendingScope = Scope(name.text, isType = true, depth = depth, nameRange = TextRange(name.start, name.end))
                }
                // attributes of a member: [Fact], [Theory, Trait("a", "b")], [Xunit.Fact]
                inTypeBody && pendingScope == null && parentheses == 0 && token.type == CSharpTokenTypes.LBRACKET -> {
                    var nesting = 0
                    while (i < tokens.size) {
                        val inner = tokens[i]
                        if (inner.type == CSharpTokenTypes.LBRACKET || inner.type == CSharpTokenTypes.LPAREN) nesting++
                        if (inner.type == CSharpTokenTypes.RBRACKET || inner.type == CSharpTokenTypes.RPAREN) nesting--
                        if (nesting == 1 && inner.type == CSharpTokenTypes.IDENTIFIER && inner.text in attributes) attributeSeen = true
                        if (nesting == 0) break
                        i++
                    }
                }
                inTypeBody && attributeSeen && parentheses == 0 && token.type == CSharpTokenTypes.IDENTIFIER &&
                    tokens.getOrNull(i + 1)?.let { it.type == CSharpTokenTypes.LPAREN || (it.type == CSharpTokenTypes.OPERATOR && it.text == "<") } == true -> {
                    methods += CSharpAttributedMethod(qualifiedName(fileNamespace, scopes), token.text, TextRange(token.start, token.end))
                    attributeSeen = false
                }
            }
            i++
        }
        return CSharpAttributedMethods(methods, types)
    }

    private fun qualifiedName(fileNamespace: String, scopes: List<Scope>): String {
        val namespaces = (listOf(fileNamespace) + scopes.filter { !it.isType }.map { it.name }).filter { it.isNotEmpty() }
        val types = scopes.filter { it.isType }.joinToString("+") { it.name }
        return (namespaces + types).joinToString(".")
    }

    private fun tokenize(text: CharSequence): List<Token> {
        val tokens = ArrayList<Token>()
        val lexer = CSharpLexer()
        lexer.start(text)
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in CSharpTokenTypes.COMMENTS && type != CSharpTokenTypes.PREPROCESSOR) {
                tokens += Token(type, lexer.tokenText, lexer.tokenStart, lexer.tokenEnd)
            }
            lexer.advance()
        }
        return tokens
    }
}
