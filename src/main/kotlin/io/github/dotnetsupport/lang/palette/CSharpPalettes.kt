package io.github.dotnetsupport.lang.palette

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.JDOMUtil
import com.intellij.ui.ColorUtil
import io.github.dotnetsupport.lang.CSharpColors
import io.github.dotnetsupport.lang.CSharpSyntaxHighlighter
import org.jdom.Element
import java.awt.Color
import java.awt.Font

/**
 * A C# palette of a popular theme (`resources/csharpPalettes/<id>.json`, the sources of the colors are in the file): the attributes of the
 * C# keys, a dark and a light variant. Foreground, font style and effect only: a palette never paints a background, it goes on top of
 * whatever scheme is active and leaves everything that is not C# to it.
 */
class CSharpPalette(val id: String, val name: String, val dark: Map<String, TextAttributes>, val light: Map<String, TextAttributes>) {
    fun variant(dark: Boolean): Map<String, TextAttributes> = if (dark) this.dark else light

    override fun toString(): String = name
}

object CSharpPalettes {
    /** No palette: the C# keys inherit the Language Defaults of the scheme (the behavior since 0.1.83). */
    const val DEFAULT_ID = "default"

    /** The order of the popup and of the combo box of Settings | .NET. */
    val IDS = listOf("rider", "visualStudio", "vsCode", "nord", "dracula", "one", "solarized", "github")

    /** What every variant of every palette defines: the keys of the lexer and of the semantic highlighter. */
    val REQUIRED_KEYS: List<TextAttributesKey> = listOf(
        CSharpSyntaxHighlighter.KEYWORD, CSharpSyntaxHighlighter.STRING, CSharpSyntaxHighlighter.NUMBER, CSharpSyntaxHighlighter.LINE_COMMENT,
        CSharpSyntaxHighlighter.BLOCK_COMMENT, CSharpSyntaxHighlighter.DOC_COMMENT, CSharpSyntaxHighlighter.PREPROCESSOR, CSharpSyntaxHighlighter.ESCAPE,
        CSharpSyntaxHighlighter.ESCAPE_2, CSharpSyntaxHighlighter.FORMAT_ITEM, CSharpSyntaxHighlighter.FORMAT_ITEM_2,
    ) + CSharpColors.ALL

    val ALL: List<CSharpPalette> by lazy { IDS.map(::load) }

    fun find(id: String?): CSharpPalette? = ALL.firstOrNull { it.id == id }

    /** Every key some palette writes: what "IDE default" and a switch of palettes put back, so that nothing of the previous palette stays. */
    val WRITTEN_KEYS: List<TextAttributesKey> by lazy {
        (REQUIRED_KEYS.map { it.externalName } + ALL.flatMap { it.dark.keys + it.light.keys }).distinct().map { TextAttributesKey.find(it) }
    }

    private fun load(id: String): CSharpPalette {
        val text = CSharpPalettes::class.java.getResourceAsStream("/csharpPalettes/$id.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("No palette $id")
        return parse(text).also { check(it.id == id) { "$id.json has the id ${it.id}" } }
    }

    fun parse(json: String): CSharpPalette {
        val root = JsonParser.parseString(json).asJsonObject
        fun variant(name: String): Map<String, TextAttributes> {
            val entries = root.getAsJsonObject(name) ?: JsonObject()
            return entries.entrySet().associate { (key, value) ->
                // only C# keys: whatever else a file says, the plugin does not touch the rest of the scheme
                require(key.startsWith("CSHARP_")) { "$key is not a C# key" }
                key to attributes(value.asString)
            }
        }
        return CSharpPalette(root.get("id").asString, root.get("name").asString, variant("dark"), variant("light"))
    }

    /** `#RRGGBB [bold] [italic] [underline[:#RRGGBB]]`; the underline takes the foreground when it has no color of its own. */
    fun attributes(spec: String): TextAttributes {
        val parts = spec.trim().split(Regex("\\s+"))
        val foreground = color(parts.first())
        var fontType = Font.PLAIN
        var effect: EffectType? = null
        var effectColor: Color? = null
        for (part in parts.drop(1)) {
            when {
                part == "bold" -> fontType = fontType or Font.BOLD
                part == "italic" -> fontType = fontType or Font.ITALIC
                part == "underline" || part.startsWith("underline:") -> {
                    effect = EffectType.LINE_UNDERSCORE
                    effectColor = part.substringAfter(':', "").takeIf { it.isNotEmpty() }?.let(::color) ?: foreground
                }
                else -> throw IllegalArgumentException("Unknown style '$part' in '$spec'")
            }
        }
        return TextAttributes(foreground, null, effectColor, effect, fontType)
    }

    private fun color(text: String): Color {
        require(Regex("#[0-9A-Fa-f]{6}").matches(text)) { "Not a color: $text" }
        return ColorUtil.fromHex(text)
    }
}

/**
 * Writes a palette into a scheme and takes it out again. The scheme is the editable copy the IDE keeps of a bundled scheme
 * (`_@user_Darcula`…, what the Settings dialog edits too), so the colors persist with it. Before the first palette goes in, the C# attributes
 * the scheme had of its own are kept as XML ([backup]); taking the palette out puts exactly those back, and a key the scheme did not define
 * inherits again, so a user's own C# colors survive a round trip through the palettes.
 */
object CSharpPaletteWriter {
    fun isDark(scheme: EditorColorsScheme): Boolean = ColorUtil.isDark(scheme.defaultBackground)

    fun canWrite(scheme: EditorColorsScheme): Boolean = scheme is AbstractColorsScheme && !scheme.isReadOnly

    /** The palette's variant for this scheme, by the darkness of its background. */
    fun expected(scheme: EditorColorsScheme, palette: CSharpPalette): Map<String, TextAttributes> = palette.variant(isDark(scheme))

    fun matches(scheme: EditorColorsScheme, palette: CSharpPalette): Boolean =
        expected(scheme, palette).all { (key, attributes) -> scheme.getAttributes(TextAttributesKey.find(key)) == attributes }

    /** The C# attributes the scheme defines itself (not its parent): `<key name="…">` with a `<value>`, or `inherited="true"`. */
    fun backup(scheme: EditorColorsScheme): String {
        val own = (scheme as? AbstractColorsScheme)?.directlyDefinedAttributes.orEmpty()
        val root = Element("csharpColors")
        for (key in CSharpPalettes.WRITTEN_KEYS) {
            val attributes = own[key.externalName] ?: continue
            val element = Element("key").setAttribute("name", key.externalName)
            if (attributes === AbstractColorsScheme.INHERITED_ATTRS_MARKER) element.setAttribute("inherited", "true")
            else element.addContent(Element("value").also { attributes.writeExternal(it) })
            root.addContent(element)
        }
        return JDOMUtil.write(root)
    }

    /** Puts the palette in; [backup] first takes out what a previous palette wrote, so that nothing of it stays. */
    fun write(scheme: EditorColorsScheme, palette: CSharpPalette, backup: String) {
        restore(scheme, backup)
        for ((key, attributes) in expected(scheme, palette)) scheme.setAttributes(TextAttributesKey.find(key), attributes.clone())
        (scheme as? AbstractColorsScheme)?.setSaveNeeded(true)
    }

    /** Back to [backup]: a key the scheme had defines it again, any other inherits (from the parent scheme, else from its fallback key). */
    fun restore(scheme: EditorColorsScheme, backup: String) {
        val saved = runCatching { JDOMUtil.load(backup) }.getOrNull()?.getChildren("key").orEmpty().associateBy { it.getAttributeValue("name") }
        val parent = (scheme as? AbstractColorsScheme)?.parentScheme as? AbstractColorsScheme
        for (key in CSharpPalettes.WRITTEN_KEYS) {
            val element = saved[key.externalName]
            val attributes = when {
                element == null -> parent?.getDirectlyDefinedAttributes(key)?.takeUnless { it === AbstractColorsScheme.INHERITED_ATTRS_MARKER }?.clone()
                element.getAttributeValue("inherited") == "true" -> null
                else -> element.getChild("value")?.let { TextAttributes(it) }
            }
            scheme.setAttributes(key, attributes ?: AbstractColorsScheme.INHERITED_ATTRS_MARKER)
        }
        (scheme as? AbstractColorsScheme)?.setSaveNeeded(true)
    }

    /** Has the scheme C# colors of its own (the bundled "Rider Dark", a scheme of a user)? Then the IDE has nothing to suggest. */
    fun definesCSharpColors(scheme: EditorColorsScheme): Boolean {
        val colors = scheme as? AbstractColorsScheme ?: return true
        return CSharpPalettes.REQUIRED_KEYS.any { key -> colors.getDirectlyDefinedAttributes(key).let { it != null && it !== AbstractColorsScheme.INHERITED_ATTRS_MARKER } }
    }
}
