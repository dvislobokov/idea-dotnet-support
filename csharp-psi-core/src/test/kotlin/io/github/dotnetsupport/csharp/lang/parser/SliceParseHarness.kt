package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilderFactory
import io.github.dotnetsupport.csharp.lang.CSharpLanguageLevel
import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion
import io.github.dotnetsupport.csharp.lang.CSharpParserDefinition
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexer
import io.github.dotnetsupport.csharp.lang.oracle.DumpNode

/**
 * The test entry of the parser, one per `roslyndump` mode, with the real lexer through a [com.intellij.lang.PsiBuilder]:
 *  - [Mode.Expr] / [Mode.Stmt]: the whole text is one expression (`roslyndump expr`) or one statement
 *    (`roslyndump stmt`); tokens after the construct become one error element, as Roslyn's `consumeFullText` puts them
 *    into `SkippedTokensTrivia`;
 *  - [Mode.Member]: `SyntaxFactory.ParseMemberDeclaration` (`roslyndump member`): `ParseMemberDeclaration(parentKind:
 *    StructDeclaration)`, then the rest skipped; when no member starts, Roslyn returns null and the dump is empty;
 *  - [Mode.File]: the file parser of [CSharpParserDefinition] (`roslyndump tree`), the `CompilationUnit` node included.
 */
object SliceParseHarness {
    enum class Mode(val dumpMode: String) { Expr("expr"), Stmt("stmt"), Member("member"), File("tree") }

    class Result(
        val root: ASTNode,
        val roots: List<DumpNode>,
        val errorCount: Int,
        val errorElements: Int,
        /** Reparses of a conditional's when-true part (hard spot 7.1). */
        val conditionalReparses: Int = 0,
        /** The stack guard fired: the whole text is one error element. */
        val stackOverflow: Boolean = false,
    )

    private val definition = CSharpParserDefinition()

    fun parse(text: String, statement: Boolean): Result = parse(text, if (statement) Mode.Stmt else Mode.Expr)

    /** Parses [text] as [mode] at [version] (`Preview` by default, as `roslyndump` without `--langversion`). */
    fun parse(text: String, mode: Mode, version: CSharpLanguageVersion = CSharpLanguageVersion.Preview): Result {
        val builder = PsiBuilderFactory.getInstance().createBuilder(definition, CSharpLexer(), text)
        builder.putUserData(CSharpLanguageLevel.KEY, version)
        var noMember = false
        val parser: LanguageParser
        val overflowed: Boolean
        if (mode == Mode.File) {
            val file = builder.mark()
            val guarded = CSharpParserDefinition.parseCompilationUnit(builder)
            file.done(CSharpParserDefinition.FILE)
            parser = guarded.parser
            overflowed = guarded.overflowed
        } else {
            val guarded = SyntaxParser.parseWithStackGuard(builder, builder.mark() /* before the parser skips leading whitespace */, ::LanguageParser) { p ->
                when (mode) {
                    Mode.Stmt -> p.parseStatement()
                    Mode.Expr -> p.parseExpression()
                    else -> if (p.parseMemberDeclaration(SyntaxKind.StructDeclaration) == null) noMember = true
                }
                if (!noMember) p.consumeUnexpectedTokens()
                else while (!p.currentToken.isEof) p.eatToken()
            }
            parser = guarded.parser
            overflowed = guarded.overflowed
            guarded.root.done(CSharpParserDefinition.FILE)
        }
        val ast = builder.treeBuilt
        check(ast.textLength == text.length) { "tree text length ${ast.textLength} != ${text.length}" }
        val dump = ParserGateSupport.newDump()
        val roots = if (noMember) emptyList() else dump.map(ast)
        return Result(ast, roots, if (noMember) 0 else parser.errorCount, dump.errorElements, parser.conditionalReparses, overflowed)
    }
}
