package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.lang.CSharpDocCommentItems
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * COMPLETION of preprocessor directives (task 2.3 of docs/COMPLETION_GAPS.md, Rider's dump 36) and in XML documentation comments (task
 * 2.4, dumps 35 / 35b): directives, symbols, `#nullable` / `#pragma` arguments and warning codes; tags with their closing part, closing
 * tags, parameter and type parameter names not documented yet, `cref` targets.
 */
class CSharpDirectiveAndDocCompletionTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            settings.state.features = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun lookup(text: String): List<LookupElement> {
        myFixture.configureByText("Directives${counter++}.cs", text)
        myFixture.completeBasic()
        return myFixture.lookupElements?.toList().orEmpty()
    }

    private fun strings(text: String): List<String> = lookup(text).map { it.lookupString }

    private fun choose(text: String, item: String): String {
        val elements = lookup(text)
        val element = elements.firstOrNull { it.lookupString == item } ?: error("no $item in ${elements.map { it.lookupString }}")
        myFixture.lookup.currentItem = element
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        return myFixture.editor.document.text
    }

    private fun caretLine(): String {
        val document = myFixture.editor.document
        val offset = myFixture.editor.caretModel.offset
        val line = document.getLineNumber(offset)
        val start = document.getLineStartOffset(line)
        return document.text.substring(start, offset) + "|" + document.text.substring(offset, document.getLineEndOffset(line))
    }

    // ---- 2.3: preprocessor

    fun testDirectivesAfterAHash() {
        val items = strings("class A\n{\n#<caret>\n}\n")
        assertEquals(listOf("if", "region", "define", "elif", "else", "endif", "endregion", "error", "line", "nullable", "pragma", "undef", "warning").sorted(), items.sorted())
        assertEquals(listOf("endif", "endregion"), strings("class A\n{\n    #end<caret>\n}\n").sorted())
        assertTrue(choose("#<caret>\nclass A {}\n", "if").startsWith("#if \nclass"))
    }

    fun testTheListOpensByItselfAfterAHashAtTheStartOfALine() {
        myFixture.configureByText("Hash${counter++}.cs", "class A\n{\n    <caret>\n}\n")
        myFixture.type("#")
        CodeCompletionHandlerBase(CompletionType.BASIC, false, true, true).invokeCompletion(project, myFixture.editor, 0)
        UIUtil.dispatchAllInvocationEvents()
        assertTrue(myFixture.lookup?.items?.map { it.lookupString }.orEmpty().contains("region"))
    }

    fun testSymbolsAfterIfAndElif() {
        val items = strings("#define FEATURE_X\n#if <caret>\nclass A {}\n#endif\n")
        assertTrue(items.toString(), items.containsAll(listOf("FEATURE_X", "DEBUG", "TRACE", "true", "false")))
        assertTrue(strings("#if DEBUG && !<caret>\n#endif\n").contains("TRACE"))
        assertTrue(strings("#if DEBUG\n#elif TR<caret>\n#endif\n").contains("TRACE"))
        assertEquals("not in the text of a string", emptyList<String>(), strings("class A { string s = \"#if <caret>\"; }"))
    }

    fun testNullableAndPragma() {
        assertEquals(listOf("disable", "enable", "restore"), strings("#nullable <caret>\nclass A {}").sorted())
        assertEquals(listOf("annotations", "warnings"), strings("#nullable enable <caret>\nclass A {}").sorted())
        assertEquals(listOf("checksum", "warning"), strings("#pragma <caret>\nclass A {}").sorted())
        assertEquals(listOf("disable", "restore"), strings("#pragma warning <caret>\nclass A {}").sorted())
    }

    fun testWarningCodes() {
        val text = "#pragma warning disable IDE0051\nclass A {}\n#pragma warning restore IDE0051\n#pragma warning disable CS0168, <caret>\n"
        val items = strings(text)
        assertEquals("the code the file already names comes first: $items", "IDE0051", items.first())
        assertTrue(items.containsAll(listOf("CS8602", "CS1591", "CS0618")))
        assertFalse("written on this line already", "CS0168" in items)
    }

    // ---- 2.4: XML documentation

    private val method = "class Repository<TItem>\n{\n    /// <summary>Finds.</summary>\n    /// <param name=\"id\">The id.</param>\n    /// LINE\n" +
        "    [Obsolete]\n    public TResult Find<TResult, TKey>(int id, string? name = \"a,b\", params object[] rest) => default!;\n}\n"

    private fun doc(line: String): String = method.replace("LINE", line)

    fun testTagsAfterALessThan() {
        val items = strings(doc("<<caret>"))
        assertTrue(items.toString(), items.containsAll(listOf("summary", "param", "typeparam", "returns", "remarks", "example", "exception", "see", "seealso",
            "paramref", "typeparamref", "inheritdoc", "value", "code", "c", "para", "list")))
        assertTrue(items.toString(), "if" !in items && "Find" !in items)
    }

    fun testATagIsWrittenWithItsClosingPart() {
        choose(doc("<ret<caret>"), "returns")
        assertEquals("    /// <returns>|</returns>", caretLine())
        choose(doc("<par<caret>"), "param")
        assertEquals("    /// <param name=\"|\"></param>", caretLine())
        choose(doc("<inh<caret>"), "inheritdoc")
        assertEquals("    /// <inheritdoc/>|", caretLine())
    }

    fun testParametersNotDocumentedYet() {
        assertEquals(listOf("name", "rest"), strings(doc("<param name=\"<caret>")))
        assertEquals(listOf("TResult", "TKey"), strings(doc("<typeparam name=\"<caret>")))
        assertEquals(listOf("id", "name", "rest"), strings(doc("<paramref name=\"<caret>\"/>")))
        choose(doc("<param name=\"<caret>\"></param>"), "rest")
        assertEquals("    /// <param name=\"rest\">|</param>", caretLine())
    }

    fun testClosingTags() {
        assertEquals(listOf("remarks"), strings(doc("<remarks>Text <c>x</c></<caret>")))
    }

    fun testCrefTargets() {
        val items = strings(doc("<see cref=\"<caret>\"/>"))
        assertTrue(items.toString(), items.containsAll(listOf("Find", "Repository", "string")))
        val exceptions = strings(doc("<exception cref=\"<caret>\"></exception>"))
        assertEquals("ArgumentNullException", exceptions.first())
    }

    fun testTheDeclarationUnderTheComment() {
        val parsed = CSharpDocCommentItems.parse("public string this[int index, [Required] string key]")
        assertEquals(listOf("index", "key"), parsed.parameters)
        val type = CSharpDocCommentItems.parse("public sealed record Pair<TFirst, TSecond>(TFirst First, TSecond Second) : IPair<TFirst>")
        assertEquals(listOf("TFirst", "TSecond"), type.typeParameters)
        assertEquals(listOf("First", "Second"), type.parameters)
        assertEquals(listOf("T"), CSharpDocCommentItems.parse("public delegate void Handler<in T>(T value)").typeParameters)
    }

    private companion object {
        var counter = 0
    }
}
