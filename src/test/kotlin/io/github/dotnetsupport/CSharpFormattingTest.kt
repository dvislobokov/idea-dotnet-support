package io.github.dotnetsupport

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.CSharpPreprocessorSymbols
import io.github.dotnetsupport.format.DotNetFormattingService
import io.github.dotnetsupport.format.DotNetFormattingSettings
import io.github.dotnetsupport.format.FormatterChoice
import io.github.dotnetsupport.lang.CSharpEditorConfig
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lang.CSharpFormatOptions
import io.github.dotnetsupport.lang.NativeCSharpFormatting
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The native formatter (CSharpFeature.FORMATTING, 0.1.49). The `*.after.cs` files of `resources/formatting` are what
 * `dotnet format whitespace --folder` (SDK 10) made of the inputs with the `editorconfig` of their folder; the oracle over the
 * corpus is `./gradlew formatOracle` (tools/csharp-psi/format-oracle.sh), outside `test`, because it runs `dotnet`.
 */
class CSharpFormattingTest : BasePlatformTestCase() {
    private val serverSettings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        serverSettings.setSource(CSharpFeature.FORMATTING, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            serverSettings.state.features = mutableMapOf()
            serverSettings.state.enabled = true
            DotNetFormattingSettings.getInstance(project).formatter = FormatterChoice.AUTO
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun resource(path: String): String = javaClass.getResource("/formatting/$path")!!.readText().replace("\r\n", "\n")

    /** No `#if` symbols, as `dotnet format --folder`: `DEBUG` is disabled text, left as it is. */
    private fun lightFile(text: String, name: String = "Formatting.cs"): PsiFile =
        PsiManager.getInstance(project).findFile(LightVirtualFile(name, CSharpFileType, text).apply { putUserData(CSharpPreprocessorSymbols.KEY, emptySet()) })!!

    private fun reformat(file: PsiFile): String {
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(file) }
        documents.commitDocument(document)
        return document.text
    }

    private fun withIndent(useTabs: Boolean, size: Int, action: () -> Unit) {
        val settings: CodeStyleSettings = CodeStyle.createTestSettings(CodeStyle.getSettings(project))
        settings.getIndentOptions(CSharpFileType).apply {
            USE_TAB_CHARACTER = useTabs
            INDENT_SIZE = size
            TAB_SIZE = size
            CONTINUATION_INDENT_SIZE = size
        }
        CodeStyle.doWithTemporarySettings(project, settings, Runnable { action() })
    }

    private fun code(text: String) = text.filterNot { it.isWhitespace() }

    private fun assertFormats(input: String, expected: String, format: (String) -> String) {
        val once = format(input)
        assertEquals(expected, once)
        assertEquals("the same text again: nothing to do", once, format(once))
        assertEquals("only whitespace changes", code(input), code(once))
    }

    fun testTheDefaultStyleIsTheOneOfDotnetFormat() {
        for (name in listOf("Basics", "Structure")) {
            assertFormats(resource("default/$name.cs"), resource("default/$name.after.cs")) { reformat(lightFile(it, "$name.cs")) }
        }
    }

    fun testIndentWithTabs() = withIndent(useTabs = true, size = 4) {
        assertFormats(resource("tabs/Options.cs"), resource("tabs/Options.after.cs")) { reformat(lightFile(it, "Options.cs")) }
    }

    /** `csharp_*` options of the nearest `.editorconfig`: braces at the end of the line, no switch label indent, spaces of calls and parentheses. */
    fun testOptionsOfEditorConfig() = withIndent(useTabs = false, size = 2) {
        myFixture.addFileToProject("FormattingOptions/.editorconfig", resource("options/editorconfig"))
        val file = myFixture.addFileToProject("FormattingOptions/Options.cs", resource("options/Options.cs"))
        assertEquals("none", CSharpEditorConfig.of(file.virtualFile)["csharp_new_line_before_open_brace"])
        val expected = resource("options/Options.after.cs")
        assertEquals(expected, reformat(file))
        assertEquals("idempotent", expected, reformat(file))
    }

    fun testOptionsAndTheirDefaults() {
        val defaults = CSharpFormatOptions()
        assertTrue(defaults.braceOnNewLine("methods"))
        assertTrue(defaults.indentSwitchLabels)
        assertEquals("before_and_after", defaults.binaryOperators)
        val options = CSharpFormatOptions(editorConfig = CSharpEditorConfig.parse(
            """
            root = true
            [*.cs]
            csharp_new_line_before_open_brace = types, methods
            csharp_indent_case_contents = false:warning
            csharp_space_after_cast = true # a comment
            """.trimIndent(),
        ))
        assertTrue(options.braceOnNewLine("types"))
        assertFalse(options.braceOnNewLine("control_blocks"))
        assertFalse("the old form with a severity", options.indentCaseContents)
        assertTrue(options.spaceAfterCast)
    }

    /** Broken code: no exception, no change but in whitespace, and a second run has nothing to do. */
    fun testBrokenCode() {
        val inputs = listOf(
            "class A {\nvoid M() {\nif (x\n}\n",
            "namespace N {\nclass A {\nint X => ;\n",
            "class A { void M() { var s = $\"{ 1 +  ; } }",
            "}}}{{{\n#if\n#endif\n#region\n",
            "class A\n{\n    void M(\n    {\n        foo(1,,2);\n    }\n}\n",
            "/* unterminated\nclass A { }",
            "class A { string s = @\"\n  open verbatim",
            "",
        )
        for (input in inputs) {
            val once = reformat(lightFile(input))
            assertEquals(input, code(input), code(once))
            assertEquals(input, once, reformat(lightFile(once)))
        }
    }

    /** Reformat of a selection touches the selected lines only. */
    fun testASelection() {
        val text = "class A\n{\nvoid M(){int a=1;}\nvoid N(){int b=2;}\n}\n"
        val file = lightFile(text)
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val start = text.indexOf("void N")
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformatText(file, start, text.length - 2) }
        assertEquals("class A\n{\nvoid M(){int a=1;}\n    void N() { int b = 2; }\n}\n", document.text)
    }

    /** "Built-in" chosen, or "Auto" with the switch NATIVE: the native formatter; "dotnet format", CSharpier and "None" are what they say. */
    fun testWhenTheNativeFormatterAnswers() {
        val file = myFixture.addFileToProject("FormattingWiring/Program.cs", "class Program{}")
        val formatting = DotNetFormattingSettings.getInstance(project)
        val service = DotNetFormattingService()
        assertTrue("NATIVE, AUTO without CSharpier: the native formatter", NativeCSharpFormatting.engaged(file))
        assertFalse("and dotnet format stands down", service.canFormat(file))
        assertEquals("class Program { }", reformat(file))

        formatting.formatter = FormatterChoice.DOTNET_FORMAT
        assertFalse("dotnet format chosen: dotnet format, whatever the switch", NativeCSharpFormatting.engaged(file))
        assertTrue(service.canFormat(file))
        formatting.formatter = FormatterChoice.BUILT_IN
        assertTrue(NativeCSharpFormatting.engaged(file))
        assertFalse(service.canFormat(file))
        formatting.formatter = FormatterChoice.CSHARPIER
        assertFalse("CSharpier chosen: CSharpier", NativeCSharpFormatting.engaged(file))
        assertTrue(service.canFormat(file))
        formatting.formatter = FormatterChoice.NONE
        assertFalse(NativeCSharpFormatting.engaged(file))
        assertEquals("None: the platform's fallback to the builder of the language changes nothing", "class None{}",
            reformat(myFixture.addFileToProject("FormattingWiring/None.cs", "class None{}")))
        formatting.formatter = FormatterChoice.AUTO

        serverSettings.setSource(CSharpFeature.FORMATTING, CSharpFeatureSource.ROSLYN)
        assertFalse("ROSLYN: today's path", NativeCSharpFormatting.engaged(file))
        assertTrue(service.canFormat(file))
        formatting.formatter = FormatterChoice.BUILT_IN
        assertTrue("Built-in chosen: the switch decides only for Auto", NativeCSharpFormatting.engaged(file))
        formatting.formatter = FormatterChoice.AUTO
        serverSettings.state.enabled = false
        assertTrue("no server: the native one, by the rule of CSharpFeatures", NativeCSharpFormatting.engaged(file))
        assertFalse("not a C# file", NativeCSharpFormatting.engaged(myFixture.addFileToProject("FormattingWiring/a.txt", "x")))
    }
}
