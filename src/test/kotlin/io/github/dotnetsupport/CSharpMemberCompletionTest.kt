package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * COMPLETION after a dot on the plugin's semantics (CSHARP_PSI_MIGRATION.md, task C3): members of the type of a value (of the solution and
 * of the assemblies of src/test/resources/index, with System.Runtime), static members after a type, namespaces and types after a namespace,
 * extension methods in scope, what C# lets the place see, substituted type arguments, the merge with the server's list.
 */
class CSharpMemberCompletionTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport"))!!
        ApplicationManager.getApplication().extensionArea.getExtensionPoint(CompletionContributor.EP)
            .registerExtension(CompletionContributorEP("C#", CSharpCompletionNativeTest.FakeServer::class.java.name, plugin), testRootDisposable)
    }

    override fun tearDown() {
        try {
            CSharpCompletionNativeTest.FakeServer.items = emptyList()
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
        myFixture.configureByText("Members${counter++}.cs", text.trimIndent())
        myFixture.completeBasic()
        return myFixture.lookupElements?.toList().orEmpty()
    }

    private fun native(text: String): List<String> = lookup(text).filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.map { it.lookupString }

    private fun shown(element: LookupElement): String {
        val presentation = LookupElementPresentation.renderElement(element)
        return presentation.itemText + presentation.tailText.orEmpty() + (presentation.typeText?.let { " : $it" } ?: "")
    }

    private fun body(statements: String, usings: String = "using System;\nusing System.Collections.Generic;\nusing System.Linq;"): String =
        "$usings\nclass Sample\n{\n    void Run(string text, List<int> numbers, int[] array, int? maybe)\n    {\n        $statements\n    }\n}\n"

    // ---- assemblies

    fun testInstanceMembersOfALibraryType() {
        val list = native(body("text.<caret>"))
        assertTrue(list.toString(), list.containsAll(listOf("Length", "Substring", "ToUpper", "Equals", "GetHashCode")))
        assertFalse("a static member is not offered on a value: $list", "IsNullOrEmpty" in list)
        assertFalse("protected members of object only through `this`: $list", "MemberwiseClone" in list)
        assertFalse("no keywords after a dot", "if" in list)
    }

    fun testStaticMembersAfterAType() {
        val console = native(body("Console.<caret>"))
        assertTrue(console.toString(), console.containsAll(listOf("WriteLine", "ReadLine", "Out")))
        val string = native(body("string.<caret>"))
        assertTrue(string.toString(), string.containsAll(listOf("Join", "Empty", "IsNullOrEmpty")))
        assertFalse("an instance member is not offered on a type: $string", "Length" in string)
    }

    fun testNamespacesAndTypesAfterANamespace() {
        val system = native(body("System.<caret>"))
        assertTrue(system.toString(), system.containsAll(listOf("Collections", "Console", "String", "Linq")))
        val generic = native(body("System.Collections.Generic.<caret>"))
        assertTrue(generic.toString(), generic.containsAll(listOf("List", "Dictionary")))
        val element = lookup(body("System.Collections.Generic.<caret>")).first { it.lookupString == "List" }
        assertEquals("List<>", LookupElementPresentation.renderElement(element).itemText)
    }

    fun testTypeArgumentsAreSubstituted() {
        val add = lookup(body("numbers.<caret>")).first { it.lookupString == "Add" }
        assertTrue(shown(add), shown(add).startsWith("Add(int item)"))
        val count = lookup(body("numbers.<caret>")).first { it.lookupString == "Count" }
        assertEquals("Count : int", shown(count))
    }

    fun testExtensionMethodsInScope() {
        val withLinq = native(body("numbers.<caret>"))
        assertTrue(withLinq.toString(), withLinq.containsAll(listOf("Add", "Where", "Select", "First")))
        val withoutLinq = native(body("numbers.<caret>", usings = "using System;\nusing System.Collections.Generic;"))
        assertTrue(withoutLinq.contains("Add"))
        assertFalse("Enumerable is not imported: $withoutLinq", "Where" in withoutLinq)
        val where = lookup(body("numbers.<caret>")).first { it.lookupString == "Where" }
        assertFalse("the receiver is not a parameter of the call: ${shown(where)}", shown(where).contains("this "))
    }

    fun testArraysAndNullables() {
        val array = native(body("array.<caret>"))
        assertTrue(array.toString(), array.containsAll(listOf("Length", "Where")))
        val maybe = native(body("maybe.<caret>"))
        assertTrue(maybe.toString(), maybe.containsAll(listOf("HasValue", "Value", "GetValueOrDefault")))
        val bound = native(body("text?.<caret>"))
        assertTrue(bound.toString(), bound.contains("Length"))
    }

    fun testProtectedMembersOfALibraryBaseThroughThis() {
        val list = native("""
            using System.Collections.Generic;
            class Numbers : List<int>
            {
                int _extra;
                void M() { this.<caret> }
            }
        """)
        assertTrue(list.toString(), list.containsAll(listOf("_extra", "Add", "Count")))
        assertFalse("the destructor of object is not called (robot, E-81): $list", "Finalize" in list)    }

    /** As the server (robot, E-81): `System.Void` is no type for C#, an enum shows its members, not the static methods of System.Enum. */
    fun testWhatRoslynDoesNotOffer() {
        assertFalse("Void" in native(body("System.<caret>")))
        val state = native("enum State { New, Shipped }\nclass A { void M() { State.<caret> } }")
        assertEquals(setOf("New", "Shipped"), state.toSet())
    }

    // ---- the solution

    fun testMembersOfTheSolutionByAccess() {
        myFixture.addFileToProject("Shop/Order.cs", """
            namespace Shop;
            public class Entity { public int Id { get; set; } protected void Touch() { } private int _version; }
            public class Order : Entity
            {
                public decimal Total { get; set; }
                public static Order Empty => new Order();
                private int _secret;
                internal void Ship() { }
                public void Add(string item) { }
                public void Add(string item, int count) { }
                public enum State { New, Shipped }
            }
        """.trimIndent())
        val outside = native("""
            using Shop;
            class Client { void M(Order order) { order.<caret> } }
        """)
        assertTrue(outside.toString(), outside.containsAll(listOf("Total", "Ship", "Add", "Id", "ToString")))
        for (hidden in listOf("_secret", "_version", "Touch", "Empty", "State")) assertFalse("$hidden in $outside", hidden in outside)
        val statics = native("""
            using Shop;
            class Client { void M() { Order.<caret> } }
        """)
        assertTrue(statics.toString(), statics.containsAll(listOf("Empty", "State")))
        assertFalse(statics.contains("Total"))
        val add = lookup("using Shop;\nclass Client { void M(Order order) { order.<caret> } }").first { it.lookupString == "Add" }
        assertEquals("Add(string item) (+ 1) : void", shown(add))
        val enumMembers = native("using Shop;\nclass Client { void M() { Order.State.<caret> } }")
        assertTrue(enumMembers.toString(), enumMembers.containsAll(listOf("New", "Shipped")))
    }

    fun testPrivateMembersInsideTheType() {
        val list = native("""
            class Counter
            {
                private int _count;
                private static int s_total;
                void Merge(Counter other) { other.<caret> }
            }
        """)
        assertTrue(list.toString(), list.contains("_count"))
        assertFalse(list.contains("s_total"))
    }

    fun testSourceExtensionMethods() {
        myFixture.addFileToProject("Ext/OrderExtensions.cs", """
            namespace Ext;
            public static class TextExtensions { public static string Shout(this string text) => text; }
        """.trimIndent())
        assertTrue(native(body("text.<caret>", usings = "using System;\nusing Ext;")).contains("Shout"))
        assertFalse(native(body("text.<caret>", usings = "using System;")).contains("Shout"))
    }

    // ---- choosing and the merge

    fun testChoosingAMethodAddsTheCall() {
        fun choose(item: String): String {
            lookup(body("Console.<caret>"))
            myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == item }
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            return myFixture.editor.document.text
        }
        assertTrue("a value: no `;`", choose("ReadLine").contains("Console.ReadLine()\n"))
        assertTrue("void: the statement ends", choose("WriteLine").contains("Console.WriteLine();"))
    }

    /**
     * One row per name and arity (0.1.148), as Rider lists them: `AddSingleton(Type, Type)`, `AddSingleton<TService>()`,
     * `AddSingleton<TService, TImplementation>()`; a generic row whose parameters do not tell the type arguments inserts `<>()` with the
     * caret between the angle brackets, one whose parameters do inserts the plain call.
     */
    fun testGenericOverloadsHaveTheirOwnRows() {
        val text = """
            using System;
            static class Services
            {
                public static void AddSingleton(Type serviceType, Type implementationType) { }
                public static void AddSingleton(Type serviceType, object instance) { }
                public static void AddSingleton<TService>() { }
                public static void AddSingleton<TService>(TService instance) { }
                public static void AddSingleton<TService, TImplementation>() { }
                public static TResult Convert<TResult>(object value) => default;
                public static T Echo<T>(T value) => value;
            }
            class Client { void M() { Services.<caret> } }
        """
        // the order of the rows is the sorter's
        val rows = lookup(text).filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true && it.lookupString == "AddSingleton" }.map(::shown).sorted()
        assertEquals(
            listOf("AddSingleton(Type serviceType, Type implementationType) (+ 1) : void", "AddSingleton<TService, TImplementation>() : void", "AddSingleton<TService>() (+ 1) : void"),
            rows,
        )
        fun choose(presentable: String): String {
            lookup(text)
            myFixture.lookup.currentItem = myFixture.lookupElements!!.first { LookupElementPresentation.renderElement(it).itemText == presentable }
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            return myFixture.editor.document.text
        }
        choose("AddSingleton<TService>").let { assertTrue(it, it.contains("Services.AddSingleton<>()")) }
        choose("Convert<TResult>").let { assertTrue(it, it.contains("Services.Convert<>()")) }
        choose("Echo<T>").let { assertTrue(it, it.contains("Services.Echo()")) }
    }

    fun testTheServersDuplicatesAreDropped() {
        CSharpCompletionNativeTest.FakeServer.items = listOf(CSharpCompletionNativeTest.FakeServer.Item("Length", 40.0), CSharpCompletionNativeTest.FakeServer.Item("Something", 30.0))
        val all = lookup(body("text.<caret>"))
        val strings = all.map { it.lookupString }
        assertEquals("one Length, the native one", 1, strings.count { it == "Length" })
        assertEquals(true, all.first { it.lookupString == "Length" }.getUserData(NativeCSharpCompletion.NATIVE))
        assertTrue("what the native list has not is kept", "Something" in strings)
    }

    fun testAnUnknownTypeLeavesTheServersList() {
        CSharpCompletionNativeTest.FakeServer.items = listOf(CSharpCompletionNativeTest.FakeServer.Item("Frobnicate", 30.0))
        val all = lookup(body("Unknown.Thing.<caret>"))
        assertTrue(all.none { it.getUserData(NativeCSharpCompletion.NATIVE) == true })
        assertTrue(all.any { it.lookupString == "Frobnicate" })
    }

    private companion object {
        var counter = 0

        private fun bytes(name: String): ByteArray? = CSharpMemberCompletionTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
