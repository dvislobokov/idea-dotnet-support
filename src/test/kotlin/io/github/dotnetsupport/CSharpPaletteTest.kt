package io.github.dotnetsupport

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpColors
import io.github.dotnetsupport.lang.CSharpSyntaxHighlighter
import io.github.dotnetsupport.lang.palette.CSharpPaletteChoice
import io.github.dotnetsupport.lang.palette.CSharpPaletteSchemeListener
import io.github.dotnetsupport.lang.palette.CSharpPaletteService
import io.github.dotnetsupport.lang.palette.CSharpPaletteWriter
import io.github.dotnetsupport.lang.palette.CSharpPalettes
import java.awt.Color
import java.awt.Font

/** The C# palettes (lang/palette): their files, their contrast, and what writing them into a scheme touches. */
class CSharpPaletteTest : BasePlatformTestCase() {
    private val manager get() = EditorColorsManager.getInstance()

    override fun tearDown() {
        try {
            CSharpPaletteService.getInstance().choose(CSharpPalettes.DEFAULT_ID)
            CSharpPaletteService.getInstance().promptDismissed = false
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** An editable copy of a bundled scheme, as the IDE keeps one for each (`_@user_Darcula`): what the palette is written into. */
    private fun editableCopy(base: EditorColorsScheme, name: String): EditorColorsScheme =
        ((base as AbstractColorsScheme).clone() as AbstractColorsScheme).apply { setName(name) }

    /** IntelliJ's new UI schemes "Dark" and "Light" (parents Darcula and Default) from the platform's resources. */
    private fun newUiScheme(dark: Boolean): EditorColorsScheme {
        val path = if (dark) "/themes/expUI/expUI_darkScheme.xml" else "/themes/expUI/expUI_lightScheme.xml"
        val element = checkNotNull(EditorColorsManager::class.java.getResourceAsStream(path)) { path }.use { JDOMUtil.load(it) }
        return EditorColorsSchemeImpl(null).apply { readExternal(element) }
    }

    private fun bases(): List<Pair<String, EditorColorsScheme>> = listOf(
        "Darcula" to editableCopy(manager.getScheme("Darcula")!!, "_@user_Darcula palette test"),
        "Default" to editableCopy(manager.getScheme("Default")!!, "_@user_Default palette test"),
        "Dark" to newUiScheme(dark = true),
        "Light" to newUiScheme(dark = false),
    )

    fun testThePaletteFilesDefineEveryCSharpKeyAndNothingElse() {
        assertEquals(CSharpPalettes.IDS, CSharpPalettes.ALL.map { it.id })
        val required = CSharpPalettes.REQUIRED_KEYS.map { it.externalName }.toSet()
        assertTrue("every key of the semantic highlighter", required.containsAll(CSharpColors.ALL.map { it.externalName }))
        for (palette in CSharpPalettes.ALL) {
            for (dark in listOf(true, false)) {
                val variant = palette.variant(dark)
                assertEquals("${palette.id} dark=$dark misses", emptySet<String>(), required - variant.keys)
                for ((key, attributes) in variant) {
                    assertTrue(key, key.startsWith("CSHARP_"))
                    assertNull("${palette.id} $key: a palette never paints a background", attributes.backgroundColor)
                    assertNotNull("${palette.id} $key", attributes.foregroundColor)
                }
            }
        }
        assertEquals(listOf("IDE default", "Rider", "Visual Studio", "VS Code", "Nord", "Dracula", "One Dark / One Light", "Solarized", "GitHub"),
            CSharpPaletteChoice.all().map { it.name })
    }

    fun testTheSpecOfAnAttribute() {
        assertEquals(TextAttributes(Color(0x66, 0xC3, 0xCC), null, null, null, Font.BOLD), CSharpPalettes.attributes("#66C3CC bold"))
        assertEquals(TextAttributes(Color(0xBD, 0xBD, 0xBD), null, Color(0x78, 0x78, 0x78), EffectType.LINE_UNDERSCORE, Font.PLAIN),
            CSharpPalettes.attributes("#BDBDBD underline:#787878"))
        assertEquals(Font.BOLD or Font.ITALIC, CSharpPalettes.attributes("#000000 italic bold").fontType)
        // runCatching, not assertThrows: a lambda of a test method compiles to `test…$lambda$0`, which JUnit 3 takes for a test
        assertTrue(runCatching { CSharpPalettes.attributes("#12345 bold") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue("only C# keys", runCatching { CSharpPalettes.parse("""{"id":"x","name":"X","dark":{"DEFAULT_KEYWORD":"#FFFFFF"}}""") }.exceptionOrNull() is IllegalArgumentException)
    }

    fun testEveryColorReadsOnTheBackgroundsOfIntelliJ() {
        fun luminance(color: Color): Double {
            fun channel(value: Int): Double = (value / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
        }
        fun contrast(a: Color, b: Color): Double = listOf(luminance(a), luminance(b)).sortedDescending().let { (it[0] + 0.05) / (it[1] + 0.05) }

        val low = mutableListOf<String>()
        for ((name, scheme) in bases()) {
            val dark = CSharpPaletteWriter.isDark(scheme)
            assertEquals(name, name == "Darcula" || name == "Dark", dark)
            for (palette in CSharpPalettes.ALL) {
                for ((key, attributes) in palette.variant(dark)) {
                    // comments are meant to recede
                    val needed = if ("COMMENT" in key) 2.0 else 3.0
                    val ratio = contrast(attributes.foregroundColor, scheme.defaultBackground)
                    if (ratio < needed) low += "${palette.id} on $name: $key %.2f".format(ratio)
                }
            }
        }
        assertEquals(emptyList<String>(), low)
    }

    /** `ListOrders(status, customerId, page ?? 1)`: the arguments, the locals, the types, the call and the keywords tell apart in every palette. */
    fun testParametersAndLocalsStandApartFromTypesMethodsAndKeywords() {
        fun distance(a: Color, b: Color): Double =
            Math.sqrt(((a.red - b.red) * (a.red - b.red) + (a.green - b.green) * (a.green - b.green) + (a.blue - b.blue) * (a.blue - b.blue)).toDouble())
        val identifiers = listOf(CSharpColors.PARAMETER, CSharpColors.PRIMARY_CONSTRUCTOR_PARAMETER, CSharpColors.LOCAL_VARIABLE, CSharpColors.MUTABLE_LOCAL_VARIABLE)
        val others = listOf(CSharpColors.TYPE, CSharpColors.CLASS, CSharpColors.RECORD, CSharpColors.STRUCT, CSharpColors.INTERFACE, CSharpColors.ENUM,
            CSharpColors.METHOD_CALL, CSharpColors.METHOD, CSharpSyntaxHighlighter.KEYWORD)
        val close = mutableListOf<String>()
        for (palette in CSharpPalettes.ALL) {
            for (dark in listOf(true, false)) {
                val variant = palette.variant(dark)
                for (identifier in identifiers) for (other in others) {
                    val a = variant.getValue(identifier.externalName).foregroundColor
                    val b = variant.getValue(other.externalName).foregroundColor
                    if (distance(a, b) < 60) close += "${palette.id} dark=$dark ${identifier.externalName} ~ ${other.externalName} (%.0f)".format(distance(a, b))
                }
            }
        }
        assertEquals(emptyList<String>(), close)
    }

    /** What a palette must not touch: the background, the selection, the caret row, the keys of the other languages. */
    private fun untouched(scheme: EditorColorsScheme): List<Any?> = listOf(
        scheme.defaultBackground, scheme.defaultForeground, scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR), scheme.getColor(EditorColors.CARET_ROW_COLOR),
        scheme.getColor(EditorColors.CARET_COLOR), scheme.getAttributes(HighlighterColors.TEXT), scheme.getAttributes(DefaultLanguageHighlighterColors.KEYWORD),
        scheme.getAttributes(DefaultLanguageHighlighterColors.CLASS_NAME), scheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT),
        scheme.getAttributes(DefaultLanguageHighlighterColors.STRING), scheme.getAttributes(CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES),
        scheme.getAttributes(TextAttributesKey.find("JAVA_KEYWORD")), scheme.getAttributes(TextAttributesKey.find("XML_TAG")),
        scheme.getAttributes(TextAttributesKey.find("JAVA_STRING")),
    )

    private fun csharp(scheme: EditorColorsScheme): Map<String, TextAttributes?> = CSharpPalettes.WRITTEN_KEYS.associate { it.externalName to scheme.getAttributes(it) }

    fun testWritingAndTakingOutTouchesOnlyCSharp() {
        for ((name, scheme) in bases()) {
            val before = untouched(scheme)
            val plain = csharp(scheme)
            val backup = CSharpPaletteWriter.backup(scheme)
            for (palette in CSharpPalettes.ALL) {
                CSharpPaletteWriter.write(scheme, palette, backup)
                assertTrue("${palette.id} on $name", CSharpPaletteWriter.matches(scheme, palette))
                val expected = palette.variant(CSharpPaletteWriter.isDark(scheme))
                assertEquals(expected.getValue("CSHARP_CLASS_IDENTIFIER"), scheme.getAttributes(CSharpColors.CLASS))
                assertEquals(expected.getValue("CSHARP_KEYWORD"), scheme.getAttributes(CSharpSyntaxHighlighter.KEYWORD))
                assertEquals("${palette.id} on $name", before, untouched(scheme))
            }
            CSharpPaletteWriter.restore(scheme, backup)
            assertEquals("back to what $name had: Language Defaults", plain, csharp(scheme))
            assertEquals(before, untouched(scheme))
            assertFalse(name, CSharpPaletteWriter.definesCSharpColors(scheme))
        }
    }

    fun testOwnCSharpColorsOfTheSchemeComeBack() {
        val scheme = editableCopy(manager.getScheme("Darcula")!!, "_@user_Darcula own colors")
        val own = TextAttributes(Color.ORANGE, null, null, null, Font.BOLD)
        scheme.setAttributes(CSharpSyntaxHighlighter.KEYWORD, own)
        val backup = CSharpPaletteWriter.backup(scheme)
        CSharpPaletteWriter.write(scheme, CSharpPalettes.find("nord")!!, backup)
        CSharpPaletteWriter.write(scheme, CSharpPalettes.find("dracula")!!, backup)
        assertEquals(CSharpPalettes.find("dracula")!!.dark.getValue("CSHARP_KEYWORD"), scheme.getAttributes(CSharpSyntaxHighlighter.KEYWORD))
        CSharpPaletteWriter.restore(scheme, backup)
        assertEquals(own, scheme.getAttributes(CSharpSyntaxHighlighter.KEYWORD))
        assertEquals(scheme.getAttributes(DefaultLanguageHighlighterColors.STRING), scheme.getAttributes(CSharpSyntaxHighlighter.STRING))
    }

    fun testTheBundledRiderSchemeKeepsItsColors() {
        val scheme = editableCopy(riderScheme(dark = true), "_@user_Rider Dark palette test")
        assertTrue(CSharpPaletteWriter.definesCSharpColors(scheme))
        val rider = csharp(scheme)
        val backup = CSharpPaletteWriter.backup(scheme)
        CSharpPaletteWriter.write(scheme, CSharpPalettes.find("github")!!, backup)
        assertEquals(CSharpPalettes.find("github")!!.dark.getValue("CSHARP_METHOD"), scheme.getAttributes(CSharpColors.METHOD))
        CSharpPaletteWriter.restore(scheme, backup)
        assertEquals(rider, csharp(scheme))
    }

    fun testTheVariantFollowsTheBackgroundOfTheScheme() {
        val service = CSharpPaletteService.getInstance()
        service.choose("vsCode")
        val dark = editableCopy(manager.getScheme("Darcula")!!, "_@user_Darcula switch test")
        val light = editableCopy(manager.getScheme("Default")!!, "_@user_Default switch test")
        val darkBefore = untouched(dark)
        val lightBefore = untouched(light)
        val vsCode = CSharpPalettes.find("vsCode")!!
        service.ensureApplied(dark)
        service.ensureApplied(light)
        assertEquals(vsCode.dark.getValue("CSHARP_METHOD_CALL_IDENTIFIER"), dark.getAttributes(CSharpColors.METHOD_CALL))
        assertEquals(vsCode.light.getValue("CSHARP_METHOD_CALL_IDENTIFIER"), light.getAttributes(CSharpColors.METHOD_CALL))
        assertEquals(darkBefore, untouched(dark))
        assertEquals(lightBefore, untouched(light))
    }

    fun testChoosingPreviewingAndIdeDefaultOnTheGlobalScheme() {
        val service = CSharpPaletteService.getInstance()
        val global = manager.globalScheme
        assertTrue("the global scheme of the tests is an editable copy: ${global.name}", CSharpPaletteWriter.canWrite(global))
        val before = untouched(global)
        val plain = csharp(global)
        var events = 0
        val connection = com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
        connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { events++ })

        service.choose("rider")
        assertTrue(CSharpPaletteWriter.matches(global, CSharpPalettes.find("rider")!!))
        assertTrue("the editors are told to repaint", events > 0)

        service.preview("solarized")
        assertTrue(CSharpPaletteWriter.matches(global, CSharpPalettes.find("solarized")!!))
        assertEquals("rider", service.paletteId)
        // the popup closed with Esc
        service.endPreview()
        assertTrue(CSharpPaletteWriter.matches(global, CSharpPalettes.find("rider")!!))

        service.choose("one")
        assertTrue(CSharpPaletteWriter.matches(global, CSharpPalettes.find("one")!!))
        // a switch of the scheme: the listener writes the palette again where it is missing
        CSharpPaletteWriter.restore(global, CSharpPaletteWriter.backup(editableCopy(manager.getScheme("Default")!!, "x")))
        CSharpPaletteSchemeListener().globalSchemeChange(global)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(CSharpPaletteWriter.matches(global, CSharpPalettes.find("one")!!))
        assertEquals(before, untouched(global))

        service.choose(CSharpPalettes.DEFAULT_ID)
        assertEquals("nothing of the palettes stays", plain, csharp(global))
        assertEquals(before, untouched(global))
    }
}
