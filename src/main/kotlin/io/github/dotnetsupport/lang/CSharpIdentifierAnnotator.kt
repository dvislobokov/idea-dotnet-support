package io.github.dotnetsupport.lang

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.lsp.RoslynServerStatus
import javax.swing.Icon

/** Colors types, methods and members. Runs once per file: the PSI is flat, the classifier works on the token stream. */
class CSharpIdentifierAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is CSharpFile) return
        // the semantic tokens of the language server say the same exactly, in the same colors (also while they come from its cache)
        if (RoslynServerStatus.colorsIdentifiers(element.project, element.virtualFile)) return
        for ((range, kind) in CSharpIdentifierClassifier.classify(element.viewProvider.contents)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(keyFor(kind)).create()
        }
    }

    companion object {
        val TYPE = createTextAttributesKey("CSHARP_TYPE", Default.CLASS_NAME)
        val METHOD = createTextAttributesKey("CSHARP_METHOD", Default.FUNCTION_CALL)
        val MEMBER = createTextAttributesKey("CSHARP_MEMBER", Default.INSTANCE_FIELD)

        fun keyFor(kind: IdentifierKind): TextAttributesKey = when (kind) {
            IdentifierKind.TYPE -> TYPE
            IdentifierKind.METHOD -> METHOD
            IdentifierKind.MEMBER -> MEMBER
        }
    }
}

/** Settings | Editor | Color Scheme | C#. The defaults (Rider-like) come from `colorSchemes/CSharp*.xml`. */
class CSharpColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "C#"
    override fun getIcon(): Icon = DotNetIcons.CSharp
    override fun getHighlighter(): SyntaxHighlighter = CSharpSyntaxHighlighter()
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "type" to CSharpIdentifierAnnotator.TYPE,
        "method" to CSharpIdentifierAnnotator.METHOD,
        "member" to CSharpIdentifierAnnotator.MEMBER,
    )

    override fun getDemoText(): String = """
        #nullable enable
        using System.IO;

        namespace Demo;

        /// <summary>Documentation comment</summary>
        [<type>Serializable</type>]
        public class <type>Greeter</type> : <type>IDisposable</type>
        {
            private const int Retries = 3; // line comment

            public <type>Stream</type> <method>Open</method>(string path, <type>FileAccess</type> access)
            {
                /* block comment */
                var message = ${'$'}"Opening {path} with {Retries:D2}" + '\n';
                <type>Console</type>.<method>WriteLine</method>(message.<member>Length</member> * 1.5e3);
                return new <type>FileStream</type>(path, <type>FileMode</type>.<member>Open</member>, access);
            }
        }
    """.trimIndent()

    private companion object {
        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keyword", CSharpSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Identifiers//Type", CSharpIdentifierAnnotator.TYPE),
            AttributesDescriptor("Identifiers//Method", CSharpIdentifierAnnotator.METHOD),
            AttributesDescriptor("Identifiers//Property, field, enum member", CSharpIdentifierAnnotator.MEMBER),
            AttributesDescriptor("String and char", CSharpSyntaxHighlighter.STRING),
            AttributesDescriptor("Number", CSharpSyntaxHighlighter.NUMBER),
            AttributesDescriptor("Comments//Line comment", CSharpSyntaxHighlighter.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", CSharpSyntaxHighlighter.BLOCK_COMMENT),
            AttributesDescriptor("Comments//Documentation comment", CSharpSyntaxHighlighter.DOC_COMMENT),
            AttributesDescriptor("Preprocessor directive", CSharpSyntaxHighlighter.PREPROCESSOR),
            AttributesDescriptor("Braces and operators//Braces", CSharpSyntaxHighlighter.BRACES),
            AttributesDescriptor("Braces and operators//Parentheses", CSharpSyntaxHighlighter.PARENTHESES),
            AttributesDescriptor("Braces and operators//Brackets", CSharpSyntaxHighlighter.BRACKETS),
            AttributesDescriptor("Braces and operators//Operator", CSharpSyntaxHighlighter.OPERATOR),
            AttributesDescriptor("Braces and operators//Dot", CSharpSyntaxHighlighter.DOT),
            AttributesDescriptor("Braces and operators//Comma", CSharpSyntaxHighlighter.COMMA),
            AttributesDescriptor("Braces and operators//Semicolon", CSharpSyntaxHighlighter.SEMICOLON),
            AttributesDescriptor("Bad character", CSharpSyntaxHighlighter.BAD_CHARACTER),
        )
    }
}
