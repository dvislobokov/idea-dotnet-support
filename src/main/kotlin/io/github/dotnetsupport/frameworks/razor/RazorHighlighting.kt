package io.github.dotnetsupport.frameworks.razor

import com.intellij.lang.html.HTMLLanguage
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.XmlHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.editor.ex.util.LayerDescriptor
import com.intellij.openapi.editor.ex.util.LayeredLexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.fileTypes.EditorHighlighterProvider
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.lang.CSharpLanguage
import io.github.dotnetsupport.lang.CSharpSyntaxHighlighter
import javax.swing.Icon

/**
 * The colours of Razor's own tokens; the C# is the plugin's C# highlighter's ([RazorLexer] with `split` gives its tokens), the markup is
 * HTML's (the layer of [RazorEditorHighlighter]).
 */
class RazorSyntaxHighlighter(private val kind: RazorFileKind) : SyntaxHighlighterBase() {
    private val csharp = CSharpSyntaxHighlighter()

    override fun getHighlightingLexer(): Lexer = RazorLexer(kind, split = true)

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        if (tokenType.language == CSharpLanguage) csharp.getTokenHighlights(tokenType) else pack(KEYS[tokenType])

    companion object {
        val TRANSITION = createTextAttributesKey("RAZOR_TRANSITION", Default.KEYWORD)
        val DIRECTIVE = createTextAttributesKey("RAZOR_DIRECTIVE", Default.KEYWORD)
        val BRACES = createTextAttributesKey("RAZOR_BRACES", Default.BRACES)
        val PARENTHESES = createTextAttributesKey("RAZOR_PARENTHESES", Default.PARENTHESES)
        val COMMENT = createTextAttributesKey("RAZOR_COMMENT", Default.BLOCK_COMMENT)
        val TEXT_TAG = createTextAttributesKey("RAZOR_TEXT_TAG", Default.KEYWORD)
        val DIRECTIVE_ATTRIBUTE = createTextAttributesKey("RAZOR_DIRECTIVE_ATTRIBUTE", Default.METADATA)
        val ATTRIBUTE_VALUE = createTextAttributesKey("RAZOR_ATTRIBUTE_VALUE", XmlHighlighterColors.HTML_ATTRIBUTE_VALUE)
        val DIRECTIVE_VALUE = createTextAttributesKey("RAZOR_DIRECTIVE_VALUE", Default.STRING)

        private val KEYS: Map<IElementType, TextAttributesKey> = mapOf(
            RazorTokenTypes.TRANSITION to TRANSITION,
            RazorTokenTypes.ESCAPE to TRANSITION,
            RazorTokenTypes.COLON to TRANSITION,
            RazorTokenTypes.DIRECTIVE to DIRECTIVE,
            RazorTokenTypes.LBRACE to BRACES,
            RazorTokenTypes.RBRACE to BRACES,
            RazorTokenTypes.LPAREN to PARENTHESES,
            RazorTokenTypes.RPAREN to PARENTHESES,
            RazorTokenTypes.COMMENT to COMMENT,
            RazorTokenTypes.TEXT_TAG to TEXT_TAG,
            RazorTokenTypes.DIRECTIVE_ATTRIBUTE to DIRECTIVE_ATTRIBUTE,
            RazorTokenTypes.ATTRIBUTE_VALUE to ATTRIBUTE_VALUE,
            RazorTokenTypes.DIRECTIVE_VALUE to DIRECTIVE_VALUE,
        )
    }
}

class RazorSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = RazorSyntaxHighlighter(RazorFileKind.of(virtualFile?.name))
}

/** Razor and C# from [RazorSyntaxHighlighter], HTML laid over the markup ([RazorTokenTypes.MARKUP]) by HTML's own highlighter. */
class RazorEditorHighlighter(project: Project?, file: VirtualFile?, colors: EditorColorsScheme) :
    LayeredLexerEditorHighlighter(RazorSyntaxHighlighter(RazorFileKind.of(file?.name)), colors) {
    init {
        val html = SyntaxHighlighterFactory.getSyntaxHighlighter(HTMLLanguage.INSTANCE, project, file)
        if (html != null) registerLayer(RazorTokenTypes.MARKUP, LayerDescriptor(html, ""))
    }
}

class RazorEditorHighlighterProvider : EditorHighlighterProvider {
    override fun getEditorHighlighter(project: Project?, fileType: FileType, virtualFile: VirtualFile?, colors: EditorColorsScheme): EditorHighlighter =
        RazorEditorHighlighter(project, virtualFile, colors)
}

/** Settings | Editor | Color Scheme | Razor: the keys of Razor itself; the C# and the HTML have their own pages. */
class RazorColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Razor"
    override fun getIcon(): Icon = DotNetIcons.Razor
    override fun getHighlighter(): SyntaxHighlighter = RazorSyntaxHighlighter(RazorFileKind.COMPONENT)
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor("Transition (@)", RazorSyntaxHighlighter.TRANSITION),
        AttributesDescriptor("Directive", RazorSyntaxHighlighter.DIRECTIVE),
        AttributesDescriptor("Braces", RazorSyntaxHighlighter.BRACES),
        AttributesDescriptor("Parentheses", RazorSyntaxHighlighter.PARENTHESES),
        AttributesDescriptor("Comment", RazorSyntaxHighlighter.COMMENT),
        AttributesDescriptor("<text> tag", RazorSyntaxHighlighter.TEXT_TAG),
        AttributesDescriptor("Directive attribute", RazorSyntaxHighlighter.DIRECTIVE_ATTRIBUTE),
        AttributesDescriptor("Directive attribute value", RazorSyntaxHighlighter.ATTRIBUTE_VALUE),
        AttributesDescriptor("Directive argument", RazorSyntaxHighlighter.DIRECTIVE_VALUE),
    )

    override fun getDemoText(): String = """
        @page "/counter"
        @inject ILogger<Counter> Logger
        @* A comment *@

        <h1 class="title">Count: @currentCount</h1>
        <button @onclick="Increment" @bind-Value:event="oninput">Click @(currentCount + 1)</button>

        @if (currentCount > 3)
        {
            <text>Many</text>
        }

        @code {
            private int currentCount;
            private void Increment() => currentCount++;
        }
    """.trimIndent()
}
