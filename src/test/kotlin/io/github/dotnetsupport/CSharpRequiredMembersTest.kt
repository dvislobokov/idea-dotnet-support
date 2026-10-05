package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.NativeCSharpObjectInitializers
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Object initializers and C# 11 `required` members (0.1.98, [NativeCSharpObjectInitializers]): CS9035 and its fix, `new T` completed with
 * the initializer, the fill rows and `Name = ` in an initializer, «Initialize members» — on the assemblies of src/test/resources/index.
 */
class CSharpRequiredMembersTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        // the initializer of `new T` is a live template (0.1.102)
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
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

    private fun code(body: String): String = """
        using System;
        using System.Diagnostics.CodeAnalysis;
        using Fixture;

        public abstract class Entity
        {
            public long Id { get; set; }
            public required string Tenant { get; init; }
        }
        public sealed class OrderLine : Entity
        {
            public required string Sku { get; set; }
            public required string Title { get; set; }
            public decimal Price { get; set; }
            public int Quantity { get; private set; }
        }
        public class Money
        {
            public required decimal Amount;
            public Money() { }
            [SetsRequiredMembers]
            public Money(decimal amount) { Amount = amount; }
        }
        public class Tagged
        {
            public Tagged(string tag) { }
            public required string Name { get; set; }
        }
        public class Plain { public int A { get; set; } public int B { get; set; } }
        class Sample
        {
            void Run()
            {
                BODY
            }
        }
    """.trimIndent().replace("BODY", body.trimIndent().replace("\n", "\n        "))

    private fun errors(text: String): List<String> {
        myFixture.configureByText("Required${counter++}.cs", text)
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.description?.startsWith("CS9035") == true }.sortedBy { it.startOffset }
            .map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    private fun lookup(text: String): List<LookupElement> {
        myFixture.configureByText("Required${counter++}.cs", text)
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElements?.toList().orEmpty()
    }

    private fun choose(text: String, item: String): String {
        val elements = lookup(text)
        val element = elements.firstOrNull { it.lookupString == item } ?: error("no $item in ${elements.map { it.lookupString }}")
        myFixture.lookup.currentItem = element
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        return withCaret()
    }

    private fun withCaret(): String {
        val text = myFixture.editor.document.text
        val caret = myFixture.editor.caretModel.offset
        return text.substring(0, caret) + "<caret>" + text.substring(caret)
    }

    /** The lines of the method body, as they are after the edit. */
    private fun body(text: String): String = text.substringAfter("void Run()\n    {\n").substringBefore("\n    }\n}").trimIndent()

    // ---- CS9035

    fun testMissingRequiredMembersAreErrors() {
        assertEquals(listOf(
            "OrderLine -> CS9035: Required member 'Entity.Tenant' must be set in the object initializer or attribute constructor.",
            "OrderLine -> CS9035: Required member 'OrderLine.Title' must be set in the object initializer or attribute constructor.",
        ), errors(code("var line = new OrderLine() { Sku = \"A\" };")))
    }

    fun testAllSetOrSetByTheConstructorIsFine() {
        assertEmpty(errors(code("""
            var line = new OrderLine { Tenant = "t", Sku = "A", Title = "B" };
            var money = new Money(1m);
            var plain = new Plain();
        """)))
        assertEquals(listOf("Money -> CS9035: Required member 'Money.Amount' must be set in the object initializer or attribute constructor."), errors(code("var money = new Money();")))
    }

    fun testTargetTypedAndLibraryTypes() {
        assertEquals(listOf("new -> CS9035: Required member 'Tagged.Name' must be set in the object initializer or attribute constructor."), errors(code("Tagged t = new(\"x\");")))
        assertEquals(listOf("Circle -> CS9035: Required member 'Circle.Name' must be set in the object initializer or attribute constructor."), errors(code("var c = new Circle(1.0);")))
        assertEmpty(errors(code("var c = new Circle(1.0) { Name = \"c\" };")))
    }

    fun testQuickFixAddsTheInitializer() {
        errors(code("var line = new Order<caret>Line();"))
        myFixture.launchAction(myFixture.findSingleIntention("Add initializer for required members"))
        assertEquals("var line = new OrderLine\n{\n    Tenant = <caret>,\n    Sku = ,\n    Title = \n};", body(withCaret()))
    }

    fun testQuickFixFillsAnExistingInitializer() {
        errors(code("var line = new Order<caret>Line { Sku = \"A\" };"))
        myFixture.launchAction(myFixture.findSingleIntention("Add initializer for required members"))
        assertEquals("var line = new OrderLine\n{\n    Sku = \"A\",\n    Tenant = <caret>,\n    Title = \n};", body(withCaret()))
    }

    fun testQuickFixAppendsToAMultiLineInitializer() {
        errors(code("var line = new Order<caret>Line\n{\n    Sku = \"A\",\n    Title = \"B\"\n};"))
        myFixture.launchAction(myFixture.findSingleIntention("Add initializer for required members"))
        assertEquals("var line = new OrderLine\n{\n    Sku = \"A\",\n    Title = \"B\",\n    Tenant = <caret>\n};", body(withCaret()))
    }

    fun testAWriteOfOneMemberStaysOnTheLine() {
        errors(code("var c = new Cir<caret>cle(1.0);"))
        myFixture.launchAction(myFixture.findSingleIntention("Add initializer for required members"))
        assertEquals("var c = new Circle(1.0) { Name = <caret> };", body(withCaret()))
    }

    // ---- completion of `new T`

    fun testNewOfATypeWithRequiredMembersWritesTheInitializer() {
        val text = choose(code("var line = new OrderL<caret>"), "OrderLine")
        assertEquals("var line = new OrderLine\n{\n    Tenant = <caret>,\n    Sku = ,\n    Title = \n}", body(text))
    }

    fun testExpectedTypeRowShowsTheMembers() {
        val elements = lookup(code("OrderLine line = new <caret>"))
        val row = elements.first { it.lookupString == "OrderLine" && it.getUserData(NativeCSharpCompletion.NATIVE) == true }
        assertEquals("OrderLine { Tenant, Sku, Title }", LookupElementPresentation.renderElement(row).itemText)
    }

    fun testAConstructorWithParametersKeepsTheParentheses() {
        assertEquals("Tagged t = new Tagged(<caret>)", body(choose(code("Tagged t = new Tagg<caret>"), "Tagged")))
        assertEquals("var p = new Plain(<caret>)", body(choose(code("var p = new Pla<caret>"), "Plain")))
    }

    // ---- the row `T { … }` (0.1.102)

    private fun initializerRow(elements: List<LookupElement>, name: String): LookupElement? =
        elements.firstOrNull { it.lookupString == name && NativeCSharpObjectInitializers.isInitializerRow(it) }

    fun testTheInitializerRowStandsUnderTheType() {
        val elements = lookup(code("var p = new Pla<caret>"))
        val row = initializerRow(elements, "Plain") ?: error("no Plain { … } in ${elements.map { LookupElementPresentation.renderElement(it).itemText }}")
        assertEquals("Plain { … }", LookupElementPresentation.renderElement(row).itemText)
        val plain = elements.indexOfFirst { it.lookupString == "Plain" && !NativeCSharpObjectInitializers.isInitializerRow(it) }
        assertTrue("`Plain()` first", plain in 0 until elements.indexOf(row))
        assertNull("required members: the type's own row writes them", initializerRow(lookup(code("var line = new OrderL<caret>")), "OrderLine"))
        assertNull("no constructor without arguments", initializerRow(lookup(code("var t = new Tagg<caret>")), "Tagged"))
        assertNull("one letter typed: not for every type of the list", initializerRow(lookup(code("var x = new P<caret>")), "Plain"))
        assertNotNull("the type the variable names", initializerRow(lookup(code("var plain = new <caret>")), "Plain"))
        assertNotNull("the expected type", initializerRow(lookup(code("Plain p = new <caret>")), "Plain"))
    }

    fun testTheInitializerRowWritesEveryMemberWithItsValue() {
        val elements = lookup(code("int b = 2;\nvar p = new Pla<caret>"))
        myFixture.lookup.currentItem = initializerRow(elements, "Plain")
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("int b = 2;\nvar p = new Plain\n{\n    A = <caret>,\n    B = b\n}", body(withCaret()))
        // Tab goes to the next value, the one found is written in
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_NEXT_TEMPLATE_VARIABLE)
        assertEquals("int b = 2;\nvar p = new Plain\n{\n    A = ,\n    B = b<caret>\n}", body(withCaret()))
    }

    fun testRequiredMembersGetTheirValuesToo() {
        val text = choose(code("string sku = \"\";\nvar line = new OrderL<caret>"), "OrderLine")
        assertEquals("string sku = \"\";\nvar line = new OrderLine\n{\n    Tenant = <caret>,\n    Sku = sku,\n    Title = \n}", body(text))
    }

    // ---- inside the initializer

    fun testFillRowsInAnInitializer() {
        val names = lookup(code("var line = new OrderLine { Sku = \"A\", <caret> };")).map { it.lookupString }
        assertTrue(names.toString(), NativeCSharpObjectInitializers.FILL_REQUIRED in names && NativeCSharpObjectInitializers.FILL_ALL in names)
        assertFalse(names.toString(), "Quantity" in names || "Sku" in names)
        val plain = lookup(code("var p = new Plain { <caret> };")).map { it.lookupString }
        assertTrue(plain.toString(), NativeCSharpObjectInitializers.FILL_ALL in plain && NativeCSharpObjectInitializers.FILL_REQUIRED !in plain)
    }

    fun testFillRequiredMembers() {
        val text = choose(code("var line = new OrderLine { <caret> };"), NativeCSharpObjectInitializers.FILL_REQUIRED)
        assertEquals("var line = new OrderLine\n{\n    Tenant = <caret>,\n    Sku = ,\n    Title = \n};", body(text))
    }

    fun testFillAllMembers() {
        val text = choose(code("var line = new OrderLine { Sku = \"A\", <caret> };"), NativeCSharpObjectInitializers.FILL_ALL)
        assertEquals("var line = new OrderLine\n{\n    Sku = \"A\",\n    Tenant = <caret>,\n    Title = ,\n    Id = ,\n    Price = \n};", body(text))
    }

    fun testAMemberIsWrittenWithItsAssignment() {
        assertEquals("var p = new Plain { A = <caret> };", body(choose(code("var p = new Plain { <caret> };"), "A")))
    }

    // ---- Alt+Enter

    fun testInitializeMembers() {
        myFixture.configureByText("Required${counter++}.cs", code("var p = new Plain { <caret> };"))
        myFixture.launchAction(myFixture.findSingleIntention("Initialize members"))
        assertEquals("var p = new Plain\n{\n    A = <caret>,\n    B = \n};", body(withCaret()))
    }

    fun testInitializeRequiredMembersStandsBackForTheFix() {
        myFixture.configureByText("Required${counter++}.cs", code("var line = new Order<caret>Line { };"))
        myFixture.doHighlighting()
        assertEmpty(myFixture.filterAvailableIntentions("Initialize required members"))
        assertNotEmpty(myFixture.filterAvailableIntentions("Add initializer for required members"))
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Line { }") + 6)
        myFixture.launchAction(myFixture.findSingleIntention("Initialize required members"))
        assertEquals("var line = new OrderLine\n{\n    Tenant = <caret>,\n    Sku = ,\n    Title = \n};", body(withCaret()))
    }

    private companion object {
        var counter = 0
    }
}
