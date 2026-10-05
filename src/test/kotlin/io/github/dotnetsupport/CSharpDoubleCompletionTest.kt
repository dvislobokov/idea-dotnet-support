package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.NativeCSharpDoubleCompletion
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Double completion (0.1.96, [NativeCSharpDoubleCompletion]): the second Ctrl+Space shows the members the place does not see (grayed) and
 * the types of the assemblies the project does not reference (with the `using` and an offer of the reference); the second Ctrl+Shift+Space
 * the chains `order.Customer` of the expected type; the first press advertises the second. Assemblies: the fixtures of src/test/resources/index;
 * `System.Collections` stands for a package of the solution the project does not reference.
 */
class CSharpDoubleCompletionTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true
    private var autocompleteSmart = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { REFERENCED }
        NativeCSharpDoubleCompletion.setSourcesForTests(listOf(NativeCSharpDoubleCompletion.Source(fixture("System.Collections"), "System.Collections 9.0.0", package_ = "System.Collections" to "9.0.0")))
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        val code = CodeInsightSettings.getInstance()
        autocomplete = code.AUTOCOMPLETE_ON_CODE_COMPLETION
        autocompleteSmart = code.AUTOCOMPLETE_ON_SMART_TYPE_COMPLETION
        code.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        code.AUTOCOMPLETE_ON_SMART_TYPE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            val code = CodeInsightSettings.getInstance()
            code.AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            code.AUTOCOMPLETE_ON_SMART_TYPE_COMPLETION = autocompleteSmart
            settings.state.features = mutableMapOf()
            NativeCSharpDoubleCompletion.setSourcesForTests(null)
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun lookup(text: String, type: CompletionType = CompletionType.BASIC, presses: Int = 1): List<LookupElement> {
        myFixture.configureByText("Double${counter++}.cs", text.trimIndent())
        myFixture.complete(type, presses)
        return myFixture.lookupElements?.toList().orEmpty()
    }

    private fun shown(element: LookupElement): String {
        val presentation = LookupElementPresentation.renderElement(element)
        return presentation.itemText + presentation.tailText.orEmpty() + (presentation.typeText?.let { " : $it" } ?: "")
    }

    private fun rows(text: String, type: CompletionType = CompletionType.BASIC, presses: Int = 1): List<String> =
        lookup(text, type, presses).filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.map(::shown)

    private fun choose(text: String, row: String, presses: Int = 2, type: CompletionType = CompletionType.BASIC): String {
        val element = lookup(text, type, presses).firstOrNull { shown(it) == row } ?: error("no `$row` among ${myFixture.lookupElements?.map(::shown)}")
        myFixture.lookup.currentItem = element
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        val document = myFixture.editor.document.text
        val caret = myFixture.editor.caretModel.offset
        return document.substring(0, caret) + "|" + document.substring(caret)
    }

    private val MODEL = """
        using System;
        class Customer { public string Name { get; set; } }
        class Vault
        {
            private int secret;
            protected string Owner { get; set; }
            public int Shown { get; set; }
            private static Vault Open() => new Vault();
        }
        class Order
        {
            public Customer Customer { get; set; }
            public int Count() => 0;
            public string Comment { get; set; }
        }
    """.trimIndent()

    private fun body(statements: String): String = "$MODEL\nclass Sample\n{\n    Order field;\n    void Run(string text, Vault vault, Order order)\n    {\n        $statements\n    }\n}\n"

    // ---- inaccessible members

    fun testTheSecondPressShowsThePrivateMembersOfAnotherTypeGrayed() {
        val first = rows(body("vault.<caret>"))
        assertTrue(first.toString(), "Shown : int" in first)
        assertFalse("the first press keeps to what is seen: $first", first.any { it.startsWith("secret") || it.startsWith("Owner") })
        val second = lookup(body("vault.<caret>"), presses = 2).filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }
        val texts = second.map(::shown)
        assertTrue(texts.toString(), "secret (not accessible) : int" in texts && "Owner (not accessible) : string" in texts)
        assertTrue(texts.toString(), "Shown : int" in texts)
        assertFalse("a static one is not an instance member: $texts", texts.any { it.startsWith("Open") })
        val gray = second.first { shown(it).startsWith("secret") }
        assertEquals(true, gray.getUserData(NativeCSharpDoubleCompletion.INACCESSIBLE))
        assertNull(second.first { shown(it) == "Shown : int" }.getUserData(NativeCSharpDoubleCompletion.INACCESSIBLE))
    }

    fun testTheSecondPressShowsTheProtectedMembersOfALibraryType() {
        val first = rows(body("text.<caret>"))
        assertFalse(first.toString(), first.any { it.startsWith("MemberwiseClone") })
        val second = rows(body("text.<caret>"), presses = 2)
        assertTrue(second.toString(), second.any { it.startsWith("MemberwiseClone() (not accessible)") })
        assertTrue(second.toString(), second.any { it.startsWith("Length") })
    }

    fun testChoosingAnInaccessibleMemberWritesItAsItIs() {
        val text = choose(body("vault.sec<caret>"), "secret (not accessible) : int")
        assertTrue(text, text.contains("        vault.secret|\n"))
        val call = choose(body("text.Memb<caret>"), "MemberwiseClone() (not accessible) : object")
        assertTrue(call, call.contains("        text.MemberwiseClone()|"))
    }

    fun testTheFirstPressAdvertisesTheSecond() {
        lookup(body("vault.<caret>"))
        val advertisements = (myFixture.lookup as LookupImpl).advertisements
        assertTrue(advertisements.toString(), advertisements.any { it.contains("again to show members that are not accessible here") })
        lookup(body("Con<caret>"))
        val types = (myFixture.lookup as LookupImpl).advertisements
        assertTrue(types.toString(), types.any { it.contains("again to show types of packages the project does not reference") })
    }

    // ---- types of unreferenced assemblies

    fun testTheSecondPressOffersTheTypesOfAnUnreferencedPackage() {
        val first = rows(body("Lis<caret>"))
        assertFalse(first.toString(), first.any { it.startsWith("List<>") })
        val second = rows(body("Lis<caret>"), presses = 2)
        assertTrue(second.toString(), "List<> (in System.Collections.Generic, System.Collections 9.0.0)" in second)
        // a type the referenced assemblies have is not offered from the package too
        assertFalse(second.toString(), second.any { it.contains("(in System, System.Collections 9.0.0)") && it.startsWith("Lis") })
        val nothingTyped = rows(body("<caret>"), presses = 2)
        assertFalse("the whole cache is not listed: $nothingTyped", nothingTyped.any { it.contains("System.Collections 9.0.0") })
    }

    fun testChoosingAnUnreferencedTypeAddsTheUsingAndOffersThePackage() {
        val text = choose(body("Lis<caret>"), "List<> (in System.Collections.Generic, System.Collections 9.0.0)")
        assertTrue(text, text.startsWith("using System;\nusing System.Collections.Generic;\n"))
        assertTrue(text, text.contains("        List<|>\n"))
        assertEquals("Add package System.Collections 9.0.0", NativeCSharpDoubleCompletion.lastOfferForTests)
    }

    // ---- chains in smart completion

    fun testTheSecondSmartPressAddsTheChainsOfTheExpectedType() {
        val first = rows(body("Customer c = <caret>"), CompletionType.SMART)
        assertFalse(first.toString(), first.any { it.startsWith("order.Customer") })
        val second = rows(body("Customer c = <caret>"), CompletionType.SMART, presses = 2)
        assertTrue(second.toString(), "order.Customer : Customer" in second && "field.Customer : Customer" in second)
        assertFalse("a string is no root, a member of another type is not offered: $second", second.any { it.startsWith("order.Comment") || it.startsWith("text.") })
        val count = rows(body("int n = <caret>"), CompletionType.SMART, presses = 2)
        assertTrue(count.toString(), "order.Count() : int" in count && "vault.Shown : int" in count)
    }

    fun testChoosingAChainWritesIt() {
        val text = choose(body("int n = <caret>"), "order.Count() : int", type = CompletionType.SMART)
        assertTrue(text, text.contains("        int n = order.Count();|"))
        val property = choose(body("Customer c = ord<caret>"), "order.Customer : Customer", type = CompletionType.SMART)
        assertTrue(property, property.contains("        Customer c = order.Customer|"))
    }

    fun testTheFirstSmartPressAdvertisesTheChains() {
        lookup(body("Customer c = <caret>"), CompletionType.SMART)
        val advertisements = (myFixture.lookup as LookupImpl).advertisements
        assertTrue(advertisements.toString(), advertisements.any { it.contains("again to show members of values of the expected type") })
    }

    private companion object {
        var counter = 0

        private fun bytes(name: String): ByteArray? = CSharpDoubleCompletionTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val REFERENCED: AssemblyIndexSet by lazy { AssemblyIndexSet(listOf("System.Runtime", "System.Console").map(::fixture)) }
    }
}
