package io.github.dotnetsupport.frameworks.razor

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.OuterLanguageElementType
import com.intellij.psi.tree.TokenSet

class RazorTokenType(debugName: String) : IElementType(debugName, RazorLanguage) {
    override fun toString(): String = "Razor:" + super.toString()
}

class RazorElementType(debugName: String, language: com.intellij.lang.Language = RazorLanguage) : IElementType(debugName, language) {
    override fun toString(): String = "Razor:" + super.toString()
}

/**
 * The tokens of [RazorLexer], after the spans of the Razor compiler (`.cspans.txt`: Markup, Transition, MetaCode, Code, Comment): what is
 * not markup is Razor's own or C#. The editor's lexer splits [CSHARP] and [CSHARP_CODE] into the tokens of the plugin's C# lexer.
 */
object RazorTokenTypes {
    /** Markup: the text of the HTML file of the view ([RazorFileViewProvider]). */
    @JvmField val MARKUP = RazorTokenType("MARKUP")

    /** `@` starting C#, a directive or `@:` (the Transition span). */
    @JvmField val TRANSITION = RazorTokenType("TRANSITION")

    /** The first `@` of `@@`: the second one is markup. */
    @JvmField val ESCAPE = RazorTokenType("ESCAPE")

    /** The name of a directive: `page`, `code`, `section`... (MetaCode). */
    @JvmField val DIRECTIVE = RazorTokenType("DIRECTIVE")

    /** The braces and parentheses of Razor itself: `@{ }`, `@( )`, `@code { }`, `@section Name { }` (MetaCode). */
    @JvmField val LBRACE = RazorTokenType("LBRACE")
    @JvmField val RBRACE = RazorTokenType("RBRACE")
    @JvmField val LPAREN = RazorTokenType("LPAREN")
    @JvmField val RPAREN = RazorTokenType("RPAREN")

    /** `:` of `@:` (MetaCode). */
    @JvmField val COLON = RazorTokenType("COLON")

    /** `@* ... *@`, delimiters included. */
    @JvmField val COMMENT = RazorTokenType("COMMENT")

    /** `<text>` and `</text>` in code: markup without a tag of its own. */
    @JvmField val TEXT_TAG = RazorTokenType("TEXT_TAG")

    /** `@onclick`, `@bind-Value:after` — a directive attribute of a component. */
    @JvmField val DIRECTIVE_ATTRIBUTE = RazorTokenType("DIRECTIVE_ATTRIBUTE")

    /** `=` and the quotes around the value of a directive attribute, a string value of it (`@bind:event="oninput"`). */
    @JvmField val ATTRIBUTE_VALUE = RazorTokenType("ATTRIBUTE_VALUE")

    /** The argument of `@addTagHelper`, `@removeTagHelper`, `@tagHelperPrefix`: a string, not C#. */
    @JvmField val DIRECTIVE_VALUE = RazorTokenType("DIRECTIVE_VALUE")

    /** C# of an expression or of a directive's argument (`@Model.Name`, `@(x + 1)`, `@inject IFoo Foo`). */
    @JvmField val CSHARP = RazorTokenType("CSHARP")

    /** C# statements and members: `@{ ... }`, `@code { ... }`, `@if (...) { ... }`. */
    @JvmField val CSHARP_CODE = RazorTokenType("CSHARP_CODE")

    /** The Razor of the HTML file: what its tree has instead of the C# and the directives. */
    @JvmField val OUTER = OuterLanguageElementType("RAZOR_FRAGMENT", RazorLanguage)

    @JvmField val COMMENTS = TokenSet.create(COMMENT)
    @JvmField val CODE = TokenSet.create(CSHARP, CSHARP_CODE)

    /** Everything but markup: the HTML parser's errors next to them are not errors of the page ([RazorErrorFilter]). */
    @JvmField val TEMPLATE = TokenSet.create(
        TRANSITION, ESCAPE, DIRECTIVE, LBRACE, RBRACE, LPAREN, RPAREN, COLON, COMMENT, TEXT_TAG, DIRECTIVE_ATTRIBUTE, ATTRIBUTE_VALUE,
        DIRECTIVE_VALUE, CSHARP, CSHARP_CODE,
    )
}

/** The nodes of the tree of [RazorParser]: flat but for these. */
object RazorElementTypes {
    /** `@page "/x"`, `@code { ... }`, `@section Scripts { ... }`: the transition, the name, the argument or the block. */
    @JvmField val DIRECTIVE = RazorElementType("DIRECTIVE")

    /** `@{ ... }`. */
    @JvmField val CODE_BLOCK = RazorElementType("CODE_BLOCK")

    /** A token of [RazorTokenTypes.CSHARP_CODE], of [RazorCodeLanguage]. */
    @JvmField val CODE = RazorElementType("CODE", RazorCodeLanguage)
}
