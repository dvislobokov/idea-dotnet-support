package io.github.dotnetsupport.csharp.lang.lexer

import com.intellij.lexer.FlexAdapter
import com.intellij.lexer.FlexLexer
import com.intellij.psi.tree.IElementType

/**
 * The C# lexer (`CSharp.flex`): Roslyn's tokens and trivia as IntelliJ tokens, see `docs/csharp-psi/GRAMMAR.md`, "Lexer vs
 * Roslyn's tokens". [symbols] are the preprocessor symbols `#if` is evaluated with (Roslyn's
 * `CSharpParseOptions.PreprocessorSymbols`); empty by default, as `roslyndump` without `--define`. The IDE takes them
 * from [CSharpPreprocessorSymbols].
 *
 * Restarts (incremental relexing): the state of a token is 0 only where the lexer is as at the start of a file — no
 * `#define`/`#undef` in effect, no open `#if` (nor a `#region` above one), at a line start in leading trivia, not
 * inside a queued run of tokens. Everywhere else the state is a non-zero hash of that context, so IntelliJ restarts
 * at an earlier state-0 token and stops relexing only where the context is the same again. [start] always begins
 * with that empty context: a non-zero initial state is not decoded.
 */
class CSharpLexer(symbols: Set<String> = emptySet()) : FlexAdapter(Flex(_CSharpLexer(symbols))) {

    /** Reports [_CSharpLexer.currentState] instead of the JFlex lexical state, and resets the context on restart. */
    private class Flex(private val lexer: _CSharpLexer) : FlexLexer {
        override fun yybegin(state: Int) = lexer.yybegin(state)
        override fun yystate(): Int = lexer.currentState()
        override fun getTokenStart(): Int = lexer.tokenStart
        override fun getTokenEnd(): Int = lexer.tokenEnd
        override fun advance(): IElementType? = lexer.advance()
        override fun reset(buf: CharSequence, start: Int, end: Int, initialState: Int) {
            lexer.clearQueue()
            lexer.resetContext()
            lexer.reset(buf, start, end, _CSharpLexer.YYINITIAL)
        }
    }
}
