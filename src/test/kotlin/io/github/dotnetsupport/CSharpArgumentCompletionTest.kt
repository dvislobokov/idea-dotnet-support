package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCallPopups
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.NativeCSharpLambdaGhost
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * What the native list offers in an argument list without the server (0.1.86): lambdas for a delegate parameter by the overloads of the
 * plugin's semantics, the lambda and «Create method» at `Changed += `, named arguments and attribute properties, the parameter info and
 * the gray text after a method chosen in the list.
 */
class CSharpArgumentCompletionTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        settings.setSource(CSharpFeature.DOCUMENTATION, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun lookup(text: String): List<LookupElement> {
        myFixture.configureByText("Arguments${counter++}.cs", text.trimIndent())
        myFixture.completeBasic()
        return myFixture.lookupElements?.toList().orEmpty()
    }

    private fun native(text: String): List<String> = lookup(text).filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.map { it.lookupString }

    private fun choose(text: String, item: String): String {
        val elements = lookup(text)
        val element = elements.firstOrNull { it.lookupString == item } ?: error("no $item in ${elements.map { it.lookupString }}")
        myFixture.lookup.currentItem = element
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        return myFixture.editor.document.text
    }

    private fun code(statements: String, members: String = ""): String = """
        using System;
        using System.Collections.Generic;
        using System.Linq;

        public class LambdaOrder { public decimal Price { get; set; } }

        public class Sample
        {
            private readonly List<LambdaOrder> _orders = new();
            public event EventHandler? Changed;
            private void Each(Action<LambdaOrder> action) { }
            private void Retry(int attempts, Func<int, string, bool> shouldRetry) { }
            private void Place(int quantity, string name, bool urgent = false) { }
            private void Place(int quantity, string name, bool urgent, DateTime when) { }
            $members

            public void Use()
            {
                $statements
            }
        }
        """

    // ---- lambdas at a delegate parameter (Rider's dumps 15b, 24)

    fun testLambdaFirstAtAnActionOfTheSolution() {
        val list = native(code("Each(<caret>);"))
        assertEquals(list.toString(), "lambdaOrder => ", list.first())
        assertTrue(list.toString(), list[1].startsWith("lambdaOrder =>\n"))
        assertTrue("the values still follow: $list", "_orders" in list)
    }

    fun testLambdasOfBothOverloadsOfWhere() {
        val list = native(code("_orders.Where(<caret>);"))
        assertTrue(list.toString(), list.indexOf("lambdaOrder => ") == 0)
        assertTrue(list.toString(), list.indexOf("(lambdaOrder, i) => ") in 1 until list.indexOf("_orders"))
    }

    fun testLambdaOfTheSecondParameter() {
        assertEquals("(i, s) => ", native(code("Retry(3, <caret>);")).first())
    }

    fun testNoLambdaWhereNoOverloadTakesADelegate() {
        val list = native(code("Console.WriteLine(<caret>);"))
        assertTrue(list.toString(), list.none { "=>" in it })
    }

    // ---- a delegate that is no argument: `Changed += ` (dumps 24, 48)

    fun testEventSubscriptionOffersTheLambdaAndCreateMethod() {
        val elements = lookup(code("Changed += <caret>"))
        val list = elements.filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.map { it.lookupString }
        assertEquals(list.toString(), "(sender, e) => { };", list[0])
        assertEquals(list.toString(), "OnChanged", list[1])
        val shown = LookupElementPresentation.renderElement(elements.first { it.lookupString == "OnChanged" }).itemText
        assertEquals("Create method OnChanged(object?, EventArgs)", shown)
    }

    fun testCreateMethodForAnEvent() {
        val text = choose(code("Changed += <caret>"), "OnChanged")
        assertTrue(text, "Changed += OnChanged;" in text)
        assertTrue(text, "private void OnChanged(object? sender, EventArgs e)\n    {\n    }" in text)
    }

    fun testLambdaForAnEventKeepsTheCaretInTheBody() {
        val text = choose(code("Changed += <caret>"), "(sender, e) => { };")
        assertTrue(text, "Changed += (sender, e) => { };" in text)
        assertEquals("{ ", text.substring(myFixture.caretOffset - 2, myFixture.caretOffset))
    }

    // ---- named arguments (dump 5)

    fun testNamedArgumentFirstWhenItsPrefixIsTyped() {
        val list = native(code("Place(qu<caret>);"))
        assertEquals(list.toString(), "quantity:", list.first())
        val text = choose(code("Place(qu<caret>);"), "quantity:")
        assertTrue(text, "Place(quantity: );" in text)
        // robot 0.1.86: the caret went to the next line when the name ended the line
        assertEquals("Place(quantity: ", text.substring(myFixture.caretOffset - 16, myFixture.caretOffset))
        val open = choose(code("Place(qu<caret>\n"), "quantity:")
        assertTrue(open, open.substring(0, myFixture.caretOffset).endsWith("Place(quantity: "))
    }

    fun testNamedArgumentsAfterPositionalOnes() {
        val list = native(code("Place(3, <caret>);"))
        assertTrue(list.toString(), list.containsAll(listOf("name:", "urgent:", "when:")))
        assertFalse(list.toString(), "quantity:" in list)
        val named = native(code("Place(3, urgent: true, <caret>);"))
        assertFalse(named.toString(), "urgent:" in named)
        assertTrue(named.toString(), "name:" in named)
    }

    fun testAttributePropertiesAsNamedArguments() {
        myFixture.configureByText("Attribute${counter++}.cs", """
            using System;
            public class CheckAttribute : Attribute
            {
                public CheckAttribute(string reason) { }
                public string? Title { get; set; }
                public int Level;
                public static int Ignored { get; set; }
            }
            [Check(<caret>)]
            public class Target { }
        """.trimIndent())
        myFixture.completeBasic()
        val list = myFixture.lookupElements.orEmpty().map { it.lookupString }
        assertTrue(list.toString(), list.containsAll(listOf("reason:", "Title", "Level")))
        assertFalse(list.toString(), "Ignored" in list)
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == "Title" }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        val text = myFixture.editor.document.text
        assertTrue(text, text.substring(0, myFixture.caretOffset).endsWith("[Check(Title = "))
    }

    // ---- after a method chosen in the list

    fun testParameterInfoAfterAMethodChosenFromTheList() {
        val before = NativeCSharpCallPopups.requests
        val text = choose(code("Ret<caret>"), "Retry")
        assertTrue(text, "Retry(" in text)
        assertEquals(before + 1, NativeCSharpCallPopups.requests)
    }

    fun testGrayTextOfALambdaAndOfTheArguments() {
        fun ghost(statements: String): String? {
            myFixture.configureByText("Ghost${counter++}.cs", code(statements, "private void Save(LambdaOrder order, int attempts) { }").trimIndent())
            return NativeCSharpLambdaGhost.suggestion(myFixture.file as CSharpFile, myFixture.editor.document.charsSequence, myFixture.caretOffset)?.text
        }
        assertEquals("(i, s) => ", ghost("Retry(3, <caret>"))
        assertEquals("lambdaOrder => ", ghost("_orders.Where(<caret>"))
        assertEquals("order", ghost("var order = new LambdaOrder(); Save(<caret>"))
        assertNull(ghost("Console.WriteLine(<caret>"))
    }

    private companion object {
        var counter = 0

        private fun bytes(name: String): ByteArray? = CSharpArgumentCompletionTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
