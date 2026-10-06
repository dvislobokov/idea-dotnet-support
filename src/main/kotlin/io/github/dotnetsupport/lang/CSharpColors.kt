package io.github.dotnetsupport.lang

import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import io.github.dotnetsupport.DotNetIcons
import javax.swing.Icon

/**
 * The semantic palette of C# identifiers, the keys of Rider (`ReSharper.CSHARP_*`, docs/rider-analysis/dumps/color-keys-csharp.txt) without
 * the `ReSharper.` prefix. [TYPE], [METHOD] and [MEMBER] are the coarse keys of the heuristics (`CSharpIdentifierAnnotator`), which knows no
 * more than that; every finer key falls back to one of them (or to a key of Language Defaults where Rider's does), so a scheme that has
 * only the three coarse ones, or none, still shows a file as before. Filled by the semantic tokens of the server (`RoslynPolicy`) and by
 * the plugin's own tree (`NativeCSharpSemanticColors`). The IDE's schemes get no colors from the plugin: where a kind has a Language
 * Defaults key of its own (interface, static method and field, constant, metadata, function declaration) it falls back to it, so C# looks
 * like the other languages of the scheme and follows its edits. Rider's palette is the bundled scheme "Rider Dark" / "Rider Light"
 * (`colorSchemes/Rider*.xml`); since 0.1.97 Rider's and other themes' palettes also go on top of any scheme (`lang/palette`, C# keys only).
 */
object CSharpColors {
    val TYPE = createTextAttributesKey("CSHARP_TYPE", Default.CLASS_NAME)
    val METHOD = createTextAttributesKey("CSHARP_METHOD", Default.FUNCTION_CALL)
    val MEMBER = createTextAttributesKey("CSHARP_MEMBER", Default.INSTANCE_FIELD)

    // types
    val CLASS = createTextAttributesKey("CSHARP_CLASS_IDENTIFIER", TYPE)
    val STATIC_CLASS = createTextAttributesKey("CSHARP_STATIC_CLASS_IDENTIFIER", CLASS)
    val RECORD = createTextAttributesKey("CSHARP_RECORD_IDENTIFIER", CLASS)
    val STRUCT = createTextAttributesKey("CSHARP_STRUCT_IDENTIFIER", TYPE)
    val RECORD_STRUCT = createTextAttributesKey("CSHARP_RECORD_STRUCT_IDENTIFIER", STRUCT)
    val INTERFACE = createTextAttributesKey("CSHARP_INTERFACE_IDENTIFIER", Default.INTERFACE_NAME)
    val ENUM = createTextAttributesKey("CSHARP_ENUM_IDENTIFIER", TYPE)
    val DELEGATE = createTextAttributesKey("CSHARP_DELEGATE_IDENTIFIER", TYPE)
    val TYPE_PARAMETER = createTextAttributesKey("CSHARP_TYPE_PARAMETER_IDENTIFIER", TYPE)
    val ATTRIBUTE = createTextAttributesKey("CSHARP_ATTRIBUTE_IDENTIFIER", Default.METADATA)
    // as in Rider: the color of types (its fallback is the plain identifier, its value the color of classes)
    val NAMESPACE = createTextAttributesKey("CSHARP_NAMESPACE_IDENTIFIER", TYPE)

    // methods
    val METHOD_DECLARATION = createTextAttributesKey("CSHARP_METHOD_DECLARATION_IDENTIFIER", Default.FUNCTION_DECLARATION)
    val METHOD_CALL = createTextAttributesKey("CSHARP_METHOD_CALL_IDENTIFIER", METHOD)
    val STATIC_METHOD_DECLARATION = createTextAttributesKey("CSHARP_STATIC_METHOD_DECLARATION_IDENTIFIER", Default.STATIC_METHOD)
    val STATIC_METHOD_CALL = createTextAttributesKey("CSHARP_STATIC_METHOD_CALL_IDENTIFIER", STATIC_METHOD_DECLARATION)
    val EXTENSION_METHOD_DECLARATION = createTextAttributesKey("CSHARP_EXTENSION_METHOD_DECLARATION_IDENTIFIER", METHOD_DECLARATION)
    val EXTENSION_METHOD_CALL = createTextAttributesKey("CSHARP_EXTENSION_METHOD_CALL_IDENTIFIER", METHOD_CALL)
    val LOCAL_FUNCTION = createTextAttributesKey("CSHARP_LOCAL_FUNCTION_IDENTIFIER", METHOD_DECLARATION)

    // properties and variables
    val FIELD = createTextAttributesKey("CSHARP_FIELD_IDENTIFIER", MEMBER)
    val STATIC_FIELD = createTextAttributesKey("CSHARP_STATIC_FIELD_IDENTIFIER", Default.STATIC_FIELD)
    /** `const` fields and enum members, as in Rider (bold). */
    val CONSTANT = createTextAttributesKey("CSHARP_CONSTANT_IDENTIFIER", Default.CONSTANT)
    val PROPERTY = createTextAttributesKey("CSHARP_PROPERTY_IDENTIFIER", MEMBER)
    val STATIC_PROPERTY = createTextAttributesKey("CSHARP_STATIC_PROPERTY_IDENTIFIER", Default.STATIC_FIELD)
    val EVENT = createTextAttributesKey("CSHARP_EVENT_IDENTIFIER", MEMBER)
    val LOCAL_VARIABLE = createTextAttributesKey("CSHARP_LOCAL_VARIABLE_IDENTIFIER", Default.LOCAL_VARIABLE)
    /** A local written after its declaration: underlined, as Rider's and the platform's "reassigned local variable". */
    val MUTABLE_LOCAL_VARIABLE = createTextAttributesKey("CSHARP_MUTABLE_LOCAL_VARIABLE_IDENTIFIER", Default.REASSIGNED_LOCAL_VARIABLE)
    val PARAMETER = createTextAttributesKey("CSHARP_PARAMETER_IDENTIFIER", Default.PARAMETER)
    val PRIMARY_CONSTRUCTOR_PARAMETER = createTextAttributesKey("CSHARP_PRIMARY_CONSTRUCTOR_PARAMETER_IDENTIFIER", PARAMETER)
    val LABEL = createTextAttributesKey("CSHARP_LABEL_IDENTIFIER", Default.LABEL)

    /** The text of an inactive `#if` branch (Rider's `Preprocessor//Inactive branch`): gray, as unused code. Not an identifier, so not in [ALL]. */
    val INACTIVE_BRANCH = createTextAttributesKey("CSHARP_PREPROCESSOR_INACTIVE_BRANCH", CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES)

    /** Every key of the palette, coarse ones first: the color page and its test. */
    val ALL: List<TextAttributesKey> = listOf(
        TYPE, METHOD, MEMBER, CLASS, STATIC_CLASS, RECORD, STRUCT, RECORD_STRUCT, INTERFACE, ENUM, DELEGATE, TYPE_PARAMETER, ATTRIBUTE, NAMESPACE,
        METHOD_DECLARATION, METHOD_CALL, STATIC_METHOD_DECLARATION, STATIC_METHOD_CALL, EXTENSION_METHOD_DECLARATION, EXTENSION_METHOD_CALL, LOCAL_FUNCTION,
        FIELD, STATIC_FIELD, CONSTANT, PROPERTY, STATIC_PROPERTY, EVENT, LOCAL_VARIABLE, MUTABLE_LOCAL_VARIABLE, PARAMETER, PRIMARY_CONSTRUCTOR_PARAMETER, LABEL,
    )
}

/** Settings | Editor | Color Scheme | C#. The groups and names are Rider's; the defaults come from the scheme: its Language Defaults, or Rider's palette in "Rider Dark" / "Rider Light". */
class CSharpColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "C#"
    override fun getIcon(): Icon = DotNetIcons.CSharp
    override fun getHighlighter(): SyntaxHighlighter = CSharpSyntaxHighlighter()
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getDemoText(): String = """
        #nullable enable
        #if NEVER
        <inactive>using Skipped;</inactive>
        #endif
        using <ns>System</ns>.<ns>IO</ns>;

        namespace <ns>Demo</ns>;

        /// <summary>Documentation comment</summary>
        [<attr>Serializable</attr>]
        public class <class>Greeter</class>(<type>ILogger</type> <primary>logger</primary>) : <iface>IDisposable</iface>
        {
            private const int <const>Retries</const> = 3; // line comment
            private static int <sfield>_opened</sfield>;
            private readonly <type>List</type><string> <field>_names</field> = new();

            public event <delegate>Action</delegate>? <event>Opened</event>;
            public string <prop>Name</prop> { get; set; } = "";
            public static <sclass>Greeter</sclass> <sprop>Default</sprop> { get; } = new(null!);

            public <type>Stream</type> <method>Open</method><<tparam>T</tparam>>(string <param>path</param>, <type>FileAccess</type> <param>access</param>)
            {
                /* block comment */
                var <local>message</local> = ${'$'}"Opening {<param>path</param>,-20} with {<const>Retries</const>:D2} of {<const>Retries</const> + 1}\t\r\n" + '\n';
            var <local>table</local> = string.<scall>Format</scall>("<format>{0}</format><format2>{1,5:N2}</format2> <format>{2}</format>", <param>path</param>, 1.5, 2) + "\q is no escape" + @"a ""quoted"" word";
                int <mutable>count</mutable> = 0;
                <mutable>count</mutable>++;
                <localfn>Log</localfn>(<local>message</local>);
                <sfield>_opened</sfield> += <mutable>count</mutable>;
                // before the analysis of the file: the coarse colors of the heuristics
                <type>Trace</type>.<anymethod>Write</anymethod>(<param>path</param>.<member>Length</member>);
                <event>Opened</event>?.<call>Invoke</call>();
                <sclass>Console</sclass>.<scall>WriteLine</scall>(<local>message</local>.<ext>Shout</ext>() + <const>Kind</const>.<const>Text</const>);
                var <local>pairs</local> = <type>Enumerable</type>.<scall>Range</scall><b1>(</b1>0, <b2>(</b2><b3>(</b3><const>Retries</const> + 1<b3>)</b3> * 2<b2>)</b2><b1>)</b1>.<call>Select</call><b1>(</b1>i => new <b2>[</b2><b3>{</b3> i <b3>}</b3><b2>]</b2><b1>)</b1>;
            retry:
                if (<sfield>_opened</sfield> < 0) goto <label>retry</label>;
                return new <class>FileStream</class>(<param>path</param>, <enum>FileMode</enum>.<const>Open</const>, <param>access</param>);

                void <localfn>Log</localfn>(string <param>text</param>) => <primary>logger</primary>.<call>Write</call>(<param>text</param>);
            }

            public static <sclass>Greeter</sclass> <smethod>Create</smethod>() => new(null!);
            public void <method>Dispose</method>() { }
        }

        public static class <sclass>StringExtensions</sclass>
        {
            public static string <extdecl>Shout</extdecl>(this string <param>text</param>) => <param>text</param>.<call>ToUpper</call>();
        }

        public record <record>Point</record>(int <primary>X</primary>, int <primary>Y</primary>);
        public record struct <rstruct>Size</rstruct>(int <primary>Width</primary>, int <primary>Height</primary>);
        public struct <struct>Pair</struct> { }
        public enum <enum>Kind</enum> { <const>Text</const>, <const>Binary</const> }
        public delegate void <delegate>Handler</delegate>(object <param>sender</param>);
    """.trimIndent()

    private companion object {
        val TAGS: Map<String, TextAttributesKey> = mapOf(
            "type" to CSharpColors.TYPE, "class" to CSharpColors.CLASS, "sclass" to CSharpColors.STATIC_CLASS, "record" to CSharpColors.RECORD,
            "struct" to CSharpColors.STRUCT, "rstruct" to CSharpColors.RECORD_STRUCT, "iface" to CSharpColors.INTERFACE, "enum" to CSharpColors.ENUM,
            "delegate" to CSharpColors.DELEGATE, "tparam" to CSharpColors.TYPE_PARAMETER, "attr" to CSharpColors.ATTRIBUTE, "ns" to CSharpColors.NAMESPACE,
            "method" to CSharpColors.METHOD_DECLARATION, "call" to CSharpColors.METHOD_CALL, "smethod" to CSharpColors.STATIC_METHOD_DECLARATION,
            "scall" to CSharpColors.STATIC_METHOD_CALL, "extdecl" to CSharpColors.EXTENSION_METHOD_DECLARATION, "ext" to CSharpColors.EXTENSION_METHOD_CALL,
            "localfn" to CSharpColors.LOCAL_FUNCTION, "field" to CSharpColors.FIELD, "sfield" to CSharpColors.STATIC_FIELD, "const" to CSharpColors.CONSTANT,
            "prop" to CSharpColors.PROPERTY, "sprop" to CSharpColors.STATIC_PROPERTY, "event" to CSharpColors.EVENT, "local" to CSharpColors.LOCAL_VARIABLE,
            "mutable" to CSharpColors.MUTABLE_LOCAL_VARIABLE, "param" to CSharpColors.PARAMETER, "primary" to CSharpColors.PRIMARY_CONSTRUCTOR_PARAMETER,
            "label" to CSharpColors.LABEL, "member" to CSharpColors.MEMBER, "anymethod" to CSharpColors.METHOD, "inactive" to CSharpColors.INACTIVE_BRANCH,
            "format" to CSharpSyntaxHighlighter.FORMAT_ITEM, "format2" to CSharpSyntaxHighlighter.FORMAT_ITEM_2,
            "b1" to CSharpBracketColors.LEVELS[0], "b2" to CSharpBracketColors.LEVELS[1], "b3" to CSharpBracketColors.LEVELS[2],
        )

        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keyword", CSharpSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Types//Any type (before the analysis)", CSharpColors.TYPE),
            AttributesDescriptor("Types//Class", CSharpColors.CLASS),
            AttributesDescriptor("Types//Static class", CSharpColors.STATIC_CLASS),
            AttributesDescriptor("Types//Record", CSharpColors.RECORD),
            AttributesDescriptor("Types//Struct", CSharpColors.STRUCT),
            AttributesDescriptor("Types//Record struct", CSharpColors.RECORD_STRUCT),
            AttributesDescriptor("Types//Interface", CSharpColors.INTERFACE),
            AttributesDescriptor("Types//Enum", CSharpColors.ENUM),
            AttributesDescriptor("Types//Delegate", CSharpColors.DELEGATE),
            AttributesDescriptor("Types//Type parameter", CSharpColors.TYPE_PARAMETER),
            AttributesDescriptor("Types//Attribute", CSharpColors.ATTRIBUTE),
            AttributesDescriptor("Types//Namespace", CSharpColors.NAMESPACE),
            AttributesDescriptor("Methods//Any method (before the analysis)", CSharpColors.METHOD),
            AttributesDescriptor("Methods//Method declaration", CSharpColors.METHOD_DECLARATION),
            AttributesDescriptor("Methods//Method call", CSharpColors.METHOD_CALL),
            AttributesDescriptor("Methods//Static method declaration", CSharpColors.STATIC_METHOD_DECLARATION),
            AttributesDescriptor("Methods//Static method call", CSharpColors.STATIC_METHOD_CALL),
            AttributesDescriptor("Methods//Extension method declaration", CSharpColors.EXTENSION_METHOD_DECLARATION),
            AttributesDescriptor("Methods//Extension method call", CSharpColors.EXTENSION_METHOD_CALL),
            AttributesDescriptor("Methods//Local function", CSharpColors.LOCAL_FUNCTION),
            AttributesDescriptor("Properties and variables//Any member (before the analysis)", CSharpColors.MEMBER),
            AttributesDescriptor("Properties and variables//Field", CSharpColors.FIELD),
            AttributesDescriptor("Properties and variables//Static field", CSharpColors.STATIC_FIELD),
            AttributesDescriptor("Properties and variables//Constant", CSharpColors.CONSTANT),
            AttributesDescriptor("Properties and variables//Property", CSharpColors.PROPERTY),
            AttributesDescriptor("Properties and variables//Static property", CSharpColors.STATIC_PROPERTY),
            AttributesDescriptor("Properties and variables//Event", CSharpColors.EVENT),
            AttributesDescriptor("Properties and variables//Local variable", CSharpColors.LOCAL_VARIABLE),
            AttributesDescriptor("Properties and variables//Mutable local variable", CSharpColors.MUTABLE_LOCAL_VARIABLE),
            AttributesDescriptor("Properties and variables//Parameter", CSharpColors.PARAMETER),
            AttributesDescriptor("Properties and variables//Primary constructor parameter", CSharpColors.PRIMARY_CONSTRUCTOR_PARAMETER),
            AttributesDescriptor("Properties and variables//Label", CSharpColors.LABEL),
            // as Rider's Language Defaults | String
            AttributesDescriptor("String//String text", CSharpSyntaxHighlighter.STRING),
            AttributesDescriptor("String//Escape sequence//Valid", CSharpSyntaxHighlighter.ESCAPE),
            AttributesDescriptor("String//Escape sequence//Valid 2", CSharpSyntaxHighlighter.ESCAPE_2),
            AttributesDescriptor("String//Escape sequence//Invalid", CSharpSyntaxHighlighter.INVALID_ESCAPE),
            AttributesDescriptor("String//Format item", CSharpSyntaxHighlighter.FORMAT_ITEM),
            AttributesDescriptor("String//Format item 2", CSharpSyntaxHighlighter.FORMAT_ITEM_2),
            AttributesDescriptor("Number", CSharpSyntaxHighlighter.NUMBER),
            AttributesDescriptor("Comments//Line comment", CSharpSyntaxHighlighter.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", CSharpSyntaxHighlighter.BLOCK_COMMENT),
            AttributesDescriptor("Comments//Documentation comment", CSharpSyntaxHighlighter.DOC_COMMENT),
            AttributesDescriptor("Preprocessor//Directive", CSharpSyntaxHighlighter.PREPROCESSOR),
            AttributesDescriptor("Preprocessor//Inactive branch", CSharpColors.INACTIVE_BRANCH),
            AttributesDescriptor("Braces and operators//Braces", CSharpSyntaxHighlighter.BRACES),
            AttributesDescriptor("Braces and operators//Parentheses", CSharpSyntaxHighlighter.PARENTHESES),
            AttributesDescriptor("Braces and operators//Brackets", CSharpSyntaxHighlighter.BRACKETS),
            AttributesDescriptor("Braces and operators//Matching brackets//Level 1", CSharpBracketColors.LEVELS[0]),
            AttributesDescriptor("Braces and operators//Matching brackets//Level 2", CSharpBracketColors.LEVELS[1]),
            AttributesDescriptor("Braces and operators//Matching brackets//Level 3", CSharpBracketColors.LEVELS[2]),
            AttributesDescriptor("Braces and operators//Operator", CSharpSyntaxHighlighter.OPERATOR),
            AttributesDescriptor("Braces and operators//Dot", CSharpSyntaxHighlighter.DOT),
            AttributesDescriptor("Braces and operators//Comma", CSharpSyntaxHighlighter.COMMA),
            AttributesDescriptor("Braces and operators//Semicolon", CSharpSyntaxHighlighter.SEMICOLON),
            AttributesDescriptor("Bad character", CSharpSyntaxHighlighter.BAD_CHARACTER),
        )
    }
}
