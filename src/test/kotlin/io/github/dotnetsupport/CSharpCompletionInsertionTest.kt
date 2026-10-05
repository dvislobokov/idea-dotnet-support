package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupFocusDegree
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSuggestionMode
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.NativeCSharpParameterInfo
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The leftovers of 0.1.85–0.1.91 (0.1.92): a method chosen by `.` / `;` or inserted by itself gets its parentheses, the return types of
 * extension methods of the assemblies are written with what the receiver gives (`List<Order>`, not `List<TSource>`), a lambda place is
 * found by the resolved type of the parameter (a delegate of any name), and only exceptions go first in `catch (`.
 */
class CSharpCompletionInsertionTest : BasePlatformTestCase() {
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
            runCatching { myFixture.lookup?.hideLookup(true) }
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
        myFixture.configureByText("Insertion${counter++}.cs", text)
        myFixture.completeBasic()
        return myFixture.lookupElements?.toList().orEmpty()
    }

    private fun native(text: String): List<String> = lookup(text).filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.map { it.lookupString }

    /** The list as it opens by itself at `<caret>`. */
    private fun autoPopup(text: String): LookupImpl? {
        myFixture.configureByText("Auto${counter++}.cs", text)
        CodeCompletionHandlerBase(CompletionType.BASIC, false, true, true).invokeCompletion(project, myFixture.editor, 0)
        UIUtil.dispatchAllInvocationEvents()
        return myFixture.lookup as LookupImpl?
    }

    /** The document with `|` where the caret is. */
    private fun withCaret(): String {
        val text = myFixture.editor.document.text
        val offset = myFixture.editor.caretModel.offset
        return text.substring(0, offset) + "|" + text.substring(offset)
    }

    private fun commit(code: String, item: String, char: Char): String {
        val lookup = autoPopup(code)
        assertNotNull("a list in $code", lookup)
        assertEquals(item, lookup!!.currentItem?.lookupString)
        myFixture.type(char)
        return withCaret()
    }

    // ---- 1. parentheses by a commit character and by auto-insert

    fun testDotAfterAMethodWritesTheCall() {
        val result = commit("class C { int Total() => 1; void M() { var x = Tot<caret> } }", "Total", '.')
        assertTrue(result, result.contains("var x = Total().| }"))
    }

    fun testSemicolonAfterAMethodEndsTheCall() {
        val none = commit("class C { void Recalculate() { } void M() { Recalcul<caret> } }", "Recalculate", ';')
        assertTrue(none, none.contains("{ Recalculate();| }"))
        myFixture.lookup?.hideLookup(true)
        // as Enter: the caret between the parentheses of a method with arguments
        val arguments = commit("class C { void Register(int id) { } void M() { Registe<caret> } }", "Register", ';')
        assertTrue(arguments, arguments.contains("{ Register(|); }"))
    }

    fun testASubscriptionKeepsTheMethodGroup() {
        val group = commit("class C { event System.Action Changed; void OnRecalculated() { } void M() { Changed += OnRecalc<caret> } }", "OnRecalculated", ';')
        assertTrue(group, group.contains("Changed += OnRecalculated;|"))
    }

    fun testTheOnlyItemInsertedByItselfGetsTheCall() {
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = true
        myFixture.configureByText("Auto${counter++}.cs", "class C\n{\n    void Recalculate(int id) { }\n    void M()\n    {\n        Recalcula<caret>\n    }\n}\n")
        myFixture.completeBasic()
        assertNull("inserted by itself", myFixture.lookup)
        assertTrue(withCaret(), withCaret().contains("        Recalculate(|);\n"))
        myFixture.configureByText("Auto${counter++}.cs", "class Widgetry { } class C { void M() { var w = new Widgetr<caret> } }")
        myFixture.completeBasic()
        assertNull("inserted by itself", myFixture.lookup)
        assertTrue(withCaret(), withCaret().contains("new Widgetry(|)"))
    }

    // ---- 2. the return types of extension methods with the receiver's type arguments

    fun testExtensionMethodTypesAreSubstituted() {
        val elements = lookup("using System.Linq;\nusing System.Collections.Generic;\nclass Order { }\nclass C { void M(List<Order> orders) { orders.ToL<caret> } }")
        val toList = elements.firstOrNull { it.lookupString == "ToList" } ?: error("no ToList in ${elements.map { it.lookupString }}")
        assertEquals("List<Order>", LookupElementPresentation.renderElement(toList).typeText)
        myFixture.lookup?.hideLookup(true)
        val first = lookup("using System.Linq;\nusing System.Collections.Generic;\nclass Order { }\nclass C { void M(List<Order> orders) { orders.Firs<caret> } }")
            .firstOrNull { it.lookupString == "First" } ?: error("no First")
        val presentation = LookupElementPresentation.renderElement(first)
        assertEquals("Order", presentation.typeText)
    }

    fun testParameterInfoOfAnExtensionMethodIsSubstituted() {
        val source = "using System.Linq;\nusing System.Collections.Generic;\nclass Order { }\nclass C { void M(List<Order> orders) { orders.Where(|); } }"
        val offset = source.indexOf('|')
        val file = myFixture.configureByText("Info${counter++}.cs", source.removeRange(offset, offset + 1)) as CSharpFile
        val list = NativeCSharpParameterInfo.listAt(file, offset)!!
        val rows = NativeCSharpParameterInfo.rows(file, list).map { it.toString() }
        assertTrue(rows.toString(), rows.any { it.contains("Func<Order, bool> predicate") })
        assertFalse(rows.toString(), rows.any { it.contains("TSource") })
    }

    // ---- 3. lambda places by the resolved parameter type

    fun testADelegateOfAnyNameIsALambdaPlace() {
        fun at(code: String): Boolean {
            val dummy = code.replace("<caret>", "IntellijIdeaRulezzz ")
            val file = myFixture.configureByText("Lambda${counter++}.cs", dummy) as CSharpFile
            return CSharpSuggestionMode.isLambdaParameterPlace(file.findElementAt(code.indexOf("<caret>"))!!)
        }
        val declarations = "class Order { } delegate bool Rule(Order o); delegate void Step();"
        assertTrue(at("$declarations class A { void Check(Rule rule) { } void M() { Check(<caret> } }"))
        assertTrue(at("$declarations class A { void Run(int n, Step step) { } void M() { Run(1, <caret> } }"))
        assertTrue(at("$declarations class A { A(Rule rule) { } void M() { new A(<caret> } }"))
        assertFalse(at("$declarations class A { void Check(Order order) { } void M() { Check(<caret> } }"))
        // the names of the types alone, when the type does not resolve: as before
        assertTrue(at("class A { void Check(UnknownCallback c) { } void M() { Check(<caret> } }"))
    }

    fun testADelegateOfAnyNameMakesTheListASuggestion() {
        val lookup = autoPopup("class Order { } delegate bool Rule(Order o); class A { int order; void Check(Rule rule) { } void M() { Check(or<caret> } }")
        assertNotNull(lookup)
        assertEquals(LookupFocusDegree.UNFOCUSED, lookup!!.lookupFocusDegree)
    }

    // ---- 4. only exceptions first in `catch (`

    fun testCatchPutsOnlyExceptionsFirst() {
        val names = native("""
            using System;
            public enum Ex { A }
            public struct Err { }
            public class Exc { }
            public class OrderException : Exception { }
            class Sample { void M() { try { } catch (<caret> } }
        """.trimIndent())
        val exception = names.indexOf("OrderException")
        assertTrue(names.toString(), exception >= 0)
        for (other in listOf("Ex", "Err", "Exc")) {
            val index = names.indexOf(other)
            assertTrue("$other under the exceptions: $names", index < 0 || index > exception)
        }
        val argument = names.indexOf("ArgumentException")
        assertTrue(names.toString(), argument >= 0)
        for (other in listOf("Ex", "Err", "Exc")) {
            val index = names.indexOf(other)
            assertTrue("$other under ArgumentException: $names", index < 0 || index > argument)
        }
    }

    private companion object {
        var counter = 0

        private fun bytes(name: String): ByteArray? = CSharpCompletionInsertionTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
