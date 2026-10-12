package io.github.dotnetsupport.csharp.lang.oracle

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lang.WhitespacesBinders
import com.intellij.lexer.LexerBase
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

/**
 * A toy language for testing [PsiToDump] without the C# parser: statements of `+`/`*` expressions over names and
 * numbers, element types named after Roslyn's kinds, so its PSI can be diffed against `roslyndump tree`. An `@` is a
 * bad token wrapped in an error element; a missing operand becomes an empty `IdentifierName` plus an error, as the
 * port of `LanguageParser.CreateMissingIdentifierName` would do.
 */
object ToyLanguage : Language("CSharpOracleToy") {
    private fun readResolve(): Any = ToyLanguage
}

object ToyFileType : LanguageFileType(ToyLanguage) {
    override fun getName() = "CSharpOracleToy"
    override fun getDescription() = "Toy language of the oracle tests"
    override fun getDefaultExtension() = "toy"
    override fun getIcon(): Icon? = null
}

object ToyTypes {
    private fun t(name: String) = IElementType(name, ToyLanguage)
    val IDENTIFIER = t("IdentifierToken")
    val NUMBER = t("NumericLiteralToken")
    val PLUS = t("PlusToken")
    val ASTERISK = t("AsteriskToken")
    val OPEN_PAREN = t("OpenParenToken")
    val CLOSE_PAREN = t("CloseParenToken")
    val SEMICOLON = t("SemicolonToken")
    val BAD = t("BadToken")
    val WHITESPACE = t("WhitespaceTrivia")
    val END_OF_LINE = t("EndOfLineTrivia")
    val COMMENT = t("MultiLineCommentTrivia")

    val COMPILATION_UNIT = t("CompilationUnit")
    val GLOBAL_STATEMENT = t("GlobalStatement")
    val EXPRESSION_STATEMENT = t("ExpressionStatement")
    val ADD = t("AddExpression")
    val MULTIPLY = t("MultiplyExpression")
    val PARENTHESIZED = t("ParenthesizedExpression")
    val IDENTIFIER_NAME = t("IdentifierName")
    val NUMERIC_LITERAL = t("NumericLiteralExpression")

    val FILE = IFileElementType(ToyLanguage)
}

class ToyLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var end = 0
    private var start = 0
    private var tokenEnd = 0
    private var type: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.end = endOffset
        this.tokenEnd = startOffset
        advance()
    }

    override fun advance() {
        start = tokenEnd
        if (start >= end) {
            type = null
            return
        }
        val c = buffer[start]
        var i = start + 1
        fun skipWhile(p: (Char) -> Boolean) {
            while (i < end && p(buffer[i])) i++
        }
        type = when {
            c == ' ' || c == '\t' -> ToyTypes.WHITESPACE.also { skipWhile { it == ' ' || it == '\t' } }
            c == '\n' -> ToyTypes.END_OF_LINE
            c == '/' && i < end && buffer[i] == '*' -> {
                val close = buffer.indexOf("*/", i + 1)
                i = if (close < 0) end else close + 2
                ToyTypes.COMMENT
            }
            c.isLetter() || c == '_' -> ToyTypes.IDENTIFIER.also { skipWhile { it.isLetterOrDigit() || it == '_' } }
            c.isDigit() -> ToyTypes.NUMBER.also { skipWhile { it.isDigit() } }
            c == '+' -> ToyTypes.PLUS
            c == '*' -> ToyTypes.ASTERISK
            c == '(' -> ToyTypes.OPEN_PAREN
            c == ')' -> ToyTypes.CLOSE_PAREN
            c == ';' -> ToyTypes.SEMICOLON
            else -> ToyTypes.BAD
        }
        tokenEnd = i
    }

    override fun getState() = 0
    override fun getTokenType() = type
    override fun getTokenStart() = start
    override fun getTokenEnd() = tokenEnd
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = end
}

class ToyParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        val unit = builder.mark()
        while (!builder.eof()) {
            val statement = builder.mark()
            val expressionStatement = builder.mark()
            expression(builder)
            if (builder.tokenType == ToyTypes.SEMICOLON) builder.advanceLexer() else builder.error("';' expected")
            expressionStatement.done(ToyTypes.EXPRESSION_STATEMENT)
            statement.done(ToyTypes.GLOBAL_STATEMENT)
        }
        unit.done(ToyTypes.COMPILATION_UNIT)
        // Whitespace and comments around the unit belong to it: PsiToDump must trim them.
        unit.setCustomEdgeTokenBinders(WhitespacesBinders.GREEDY_LEFT_BINDER, WhitespacesBinders.GREEDY_RIGHT_BINDER)
        file.done(root)
        return builder.treeBuilt
    }

    private fun expression(b: PsiBuilder) {
        var left = b.mark()
        multiplicative(b)
        while (b.tokenType == ToyTypes.PLUS) {
            b.advanceLexer()
            multiplicative(b)
            left.done(ToyTypes.ADD)
            left = left.precede()
        }
        left.drop()
    }

    private fun multiplicative(b: PsiBuilder) {
        var left = b.mark()
        primary(b)
        while (b.tokenType == ToyTypes.ASTERISK) {
            b.advanceLexer()
            primary(b)
            left.done(ToyTypes.MULTIPLY)
            left = left.precede()
        }
        left.drop()
    }

    private fun primary(b: PsiBuilder) {
        if (b.tokenType == ToyTypes.BAD) {
            val error = b.mark()
            b.advanceLexer()
            error.error("unexpected character")
        }
        val m = b.mark()
        when (b.tokenType) {
            ToyTypes.IDENTIFIER -> { b.advanceLexer(); m.done(ToyTypes.IDENTIFIER_NAME) }
            ToyTypes.NUMBER -> { b.advanceLexer(); m.done(ToyTypes.NUMERIC_LITERAL) }
            ToyTypes.OPEN_PAREN -> {
                b.advanceLexer()
                expression(b)
                if (b.tokenType == ToyTypes.CLOSE_PAREN) b.advanceLexer() else b.error("')' expected")
                m.done(ToyTypes.PARENTHESIZED)
            }
            else -> {
                // Roslyn's missing identifier sits at the next token, after trivia; so does an empty PsiBuilder marker
                // with the default edge binders (PsiToDumpTest.testMissingOperandMatchesRoslyn).
                m.done(ToyTypes.IDENTIFIER_NAME)
                b.error("expression expected")
            }
        }
    }
}

class ToyParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = ToyLexer()
    override fun createParser(project: Project?) = ToyParser()
    override fun getFileNodeType() = ToyTypes.FILE
    override fun getWhitespaceTokens() = TokenSet.create(ToyTypes.WHITESPACE, ToyTypes.END_OF_LINE)
    override fun getCommentTokens() = TokenSet.create(ToyTypes.COMMENT)
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = object : PsiFileBase(viewProvider, ToyLanguage) {
        override fun getFileType() = ToyFileType
    }
}
