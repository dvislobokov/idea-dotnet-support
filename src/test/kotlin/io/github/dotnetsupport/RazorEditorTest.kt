package io.github.dotnetsupport

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.psi.util.PsiUtilCore
import com.intellij.psi.xml.XmlTokenType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.frameworks.razor.RazorFile
import io.github.dotnetsupport.frameworks.razor.RazorFileType
import io.github.dotnetsupport.frameworks.razor.RazorLanguage
import io.github.dotnetsupport.frameworks.razor.RazorSyntaxHighlighter
import io.github.dotnetsupport.frameworks.razor.RazorTokenTypes
import io.github.dotnetsupport.lang.CSharpSyntaxHighlighter
import io.github.dotnetsupport.lang.CSharpTokenTypes

/**
 * RAZOR_PLAN.md, R1: `.razor` and `.cshtml` in the editor — the file type and its two trees (Razor, HTML), the colours of the three
 * languages.
 */
class RazorEditorTest : BasePlatformTestCase() {
    private val counter = """
        @page "/counter"
        @inject ILogger<Counter> Logger

        <h1 class="title">Count: @currentCount</h1>
        <Counter Step="2" />
        <button @onclick="Increment">Click</button>

        @if (currentCount > 3)
        {
            <p>Many</p>
        }

        @* A comment
           of two lines *@
        @code {
            [Parameter] public int Step { get; set; }
            private int currentCount;

            private void Increment()
            {
                currentCount += Step;
            }
        }
    """.trimIndent()

    private fun offset(of: String, after: Int = 0): Int = myFixture.editor.document.text.indexOf(of, after).also { assertTrue("'$of' in the text", it >= 0) }

    fun testFileTypeAndTrees() {
        val file = myFixture.configureByText("Counter.razor", counter)
        assertEquals(RazorFileType, file.fileType)
        assertEquals(setOf(RazorLanguage, HTMLLanguage.INSTANCE), file.viewProvider.languages)
        assertInstanceOf(file.viewProvider.getPsi(RazorLanguage), RazorFile::class.java)
        assertEquals(RazorFileType, myFixture.configureByText("Index.cshtml", "@model Foo\n<p>@Model.Name</p>").fileType)
        // markup is HTML (its features work there), the Razor is the base language
        myFixture.configureByText("Counter2.razor", counter)
        val html = myFixture.file.viewProvider.getPsi(HTMLLanguage.INSTANCE)
        assertNotNull(html)
        assertEquals(HTMLLanguage.INSTANCE, PsiUtilCore.getLanguageAtOffset(myFixture.file, offset("title")))
        assertEquals(RazorLanguage, PsiUtilCore.getLanguageAtOffset(myFixture.file, offset("currentCount</h1>")))
        val tags = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(html, com.intellij.psi.xml.XmlTag::class.java).map { it.name }
        assertEquals(listOf("h1", "Counter", "button", "p"), tags)
    }

    fun testColoursOfTheThreeLanguages() {
        myFixture.configureByText("Counter.razor", counter)
        val highlighter = (myFixture.editor as EditorEx).highlighter
        fun typeAt(offset: Int) = highlighter.createIterator(offset).tokenType
        assertEquals(XmlTokenType.XML_TAG_NAME, typeAt(offset("h1")))
        assertEquals(XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN, typeAt(offset("title\"") ))
        assertEquals(RazorTokenTypes.TRANSITION, typeAt(offset("@currentCount")))
        assertEquals(CSharpTokenTypes.IDENTIFIER, typeAt(offset("currentCount</h1>")))
        assertEquals(RazorTokenTypes.DIRECTIVE, typeAt(offset("page")))
        assertEquals(RazorTokenTypes.DIRECTIVE_ATTRIBUTE, typeAt(offset("@onclick")))
        assertEquals(CSharpTokenTypes.IDENTIFIER, typeAt(offset("Increment\"")))
        assertEquals(CSharpTokenTypes.KEYWORD, typeAt(offset("if (")))
        assertEquals(CSharpTokenTypes.KEYWORD, typeAt(offset("private int")))
        assertEquals(RazorTokenTypes.COMMENT, typeAt(offset("A comment")))
        assertEquals(XmlTokenType.XML_DATA_CHARACTERS, typeAt(offset("Many")))
        val razor = RazorSyntaxHighlighter(io.github.dotnetsupport.frameworks.razor.RazorFileKind.COMPONENT)
        assertEquals(RazorSyntaxHighlighter.DIRECTIVE, razor.getTokenHighlights(RazorTokenTypes.DIRECTIVE).single())
        assertEquals(CSharpSyntaxHighlighter.KEYWORD, razor.getTokenHighlights(CSharpTokenTypes.KEYWORD).single())
    }

    fun testNoErrorsOnAValidComponent() {
        myFixture.enableInspections(
            com.intellij.codeInspection.htmlInspections.HtmlUnknownTagInspection(),
            com.intellij.codeInspection.htmlInspections.HtmlUnknownAttributeInspection(),
        )
        // components and their parameters are not "unknown" (R5 knows them); an unknown tag of HTML still is
        myFixture.configureByText("Counter.razor", counter.replace("<h1 class", "<foo></foo>\n<h1 class"))
        val problems = myFixture.doHighlighting(HighlightSeverity.WARNING).filter { it.severity >= HighlightSeverity.WARNING }
            .map { myFixture.editor.document.text.substring(it.startOffset, it.endOffset) + ": " + it.description }
        assertEquals(listOf("foo: Unknown html tag foo", "foo: Unknown html tag foo"), problems)
    }

    fun testNoErrorsOfHtmlNextToRazor() {
        myFixture.configureByText(
            "Page.cshtml",
            "@model Foo\n<div class=\"@(x ? \"a\" : \"b\")\" @attrs>\n@if (x) {\n<span>\n} else {\n<span class=\"b\">\n}\n</span></div>\n<input value=@Model.Name />",
        )
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR }
            .map { myFixture.editor.document.text.substring(it.startOffset, it.endOffset) + ": " + it.description }
        assertEquals(emptyList<String>(), errors)
    }






}
