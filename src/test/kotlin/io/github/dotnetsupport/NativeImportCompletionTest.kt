package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.NativeCSharpImportCompletion
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The native completion list beyond the stubs of the solution (0.1.87, [NativeCSharpImportCompletion]): the types of the assemblies of the
 * namespaces a place sees with nothing typed, the types of the solution and of the assemblies of other namespaces with their `using`,
 * unimported extension methods after a dot, attributes of the assemblies. Assemblies: the fixtures of src/test/resources/index.
 */
class NativeImportCompletionTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
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
        myFixture.configureByText("Imports${counter++}.cs", text.trimIndent())
        myFixture.completeBasic()
        return myFixture.lookupElements?.toList().orEmpty()
    }

    private fun shown(element: LookupElement): String {
        val presentation = LookupElementPresentation.renderElement(element)
        return presentation.itemText + presentation.tailText.orEmpty() + (presentation.typeText?.let { " : $it" } ?: "")
    }

    private fun rows(text: String): List<String> = lookup(text).filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.map(::shown)

    private fun choose(text: String, row: String): String {
        val element = lookup(text).firstOrNull { shown(it) == row } ?: error("no `$row` among ${myFixture.lookupElements?.map(::shown)}")
        myFixture.lookup.currentItem = element
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        val document = myFixture.editor.document.text
        val caret = myFixture.editor.caretModel.offset
        return document.substring(0, caret) + "|" + document.substring(caret)
    }

    private fun body(statements: String, usings: String = "using System;"): String =
        "$usings\nclass Sample\n{\n    void Run(string text, System.Collections.Generic.List<int> numbers)\n    {\n        $statements\n    }\n}\n"

    // ---- task 1: the types of the namespaces the place sees

    fun testLibraryTypesOfImportedNamespacesWithNothingTyped() {
        val imported = rows(body("<caret>", usings = "using System;\nusing System.Collections.Generic;"))
        assertTrue(imported.toString(), "List<> (System.Collections.Generic)" in imported)
        assertTrue("System is imported: $imported", imported.any { it.startsWith("Console (System)") })
        val notImported = rows(body("<caret>"))
        assertFalse("not imported, nothing typed: not offered ($notImported)", notImported.any { it.startsWith("List<>") })
        val typed = rows(body("Li<caret>", usings = "using System;\nusing System.Collections.Generic;"))
        assertTrue(typed.toString(), "List<> (System.Collections.Generic)" in typed)
    }

    fun testATypeArgumentAndADeclaration() {
        val argument = rows(body("var map = new Dictionary<string, Li<caret>", usings = "using System.Collections.Generic;"))
        assertTrue(argument.toString(), "List<> (System.Collections.Generic)" in argument)
        val member = rows("using System.Collections.Generic;\nclass Holder\n{\n    public Li<caret>\n}\n")
        assertTrue(member.toString(), "List<> (System.Collections.Generic)" in member)
    }

    fun testChoosingAGenericLibraryTypeWritesItsBrackets() {
        assertEquals("using System.Collections.Generic;\nclass Sample\n{\n    void Run(string text, System.Collections.Generic.List<int> numbers)\n    {\n        List<|>\n    }\n}",
            choose(body("Lis<caret>", usings = "using System.Collections.Generic;"), "List<> (System.Collections.Generic)"))
    }

    fun testGlobalUsingsMakeANamespaceVisible() {
        val global = myFixture.addFileToProject("GlobalImports87/Usings.cs", "global using System.Collections.Generic;\n")
        try {
            val found = rows(body("Lis<caret>"))
            assertTrue(found.toString(), "List<> (System.Collections.Generic)" in found)
            assertFalse("no using to add: $found", found.any { it.contains("(in System.Collections.Generic)") })
        } finally {
            WriteAction.runAndWait<Throwable> { global.virtualFile.delete(this) }
        }
    }

    // ---- what is not imported

    fun testLibraryTypeOfAnotherNamespaceAddsItsUsing() {
        val found = rows(body("StringBu<caret>"))
        assertTrue(found.toString(), "StringBuilder (in System.Text)" in found)
        val text = choose(body("StringBu<caret>"), "StringBuilder (in System.Text)")
        assertTrue(text, text.startsWith("using System;\nusing System.Text;\n"))
        assertTrue(text, text.contains("        StringBuilder|\n"))
    }

    fun testSolutionTypeOfANeighbourNamespace() {
        myFixture.addFileToProject("Neighbours87/Models/Invoice87.cs", "namespace Shop87.Models;\npublic class Invoice87 { }\npublic class Ledger87<T> { }\n")
        val text = "namespace Shop87.Services;\nclass Billing\n{\n    void Run()\n    {\n        Invoic<caret>\n    }\n}\n"
        assertTrue(rows(text).toString(), "Invoice87 (in Shop87.Models)" in rows(text))
        val chosen = choose(text, "Invoice87 (in Shop87.Models)")
        assertTrue(chosen, chosen.startsWith("using Shop87.Models;\n\nnamespace Shop87.Services;"))
        assertTrue(chosen, chosen.contains("        Invoice87|\n"))
        val generic = choose("namespace Shop87.Services;\nclass Billing\n{\n    Ledg<caret>\n}\n", "Ledger87<> (in Shop87.Models)")
        assertTrue(generic, generic.contains("    Ledger87<|>\n") && generic.startsWith("using Shop87.Models;"))
        // after `new`: the parentheses too
        val created = choose("namespace Shop87.Services;\nclass Billing\n{\n    object M() => new Invoic<caret>\n}\n", "Invoice87 (in Shop87.Models)")
        assertTrue(created, created.contains("new Invoice87(|)"))
    }

    fun testNothingNotImportedWithoutALetter() {
        myFixture.addFileToProject("Neighbours87/Quiet/Quiet87.cs", "namespace Quiet87;\npublic class Hushed87 { }\n")
        assertFalse(rows(body("<caret>")).any { it.contains("(in ") })
        assertTrue(rows(body("Hush<caret>")).contains("Hushed87 (in Quiet87)"))
    }

    fun testATypeOfANameTheFileSeesIsWrittenQualified() {
        myFixture.addFileToProject("Neighbours87/Widgets.cs", "namespace Alpha87 { public class Widget87 { } }\nnamespace Beta87 { public class Widget87 { } }\n")
        val text = "using Alpha87;\nclass Panel\n{\n    void Run()\n    {\n        Widg<caret>\n    }\n}\n"
        val found = rows(text)
        assertTrue(found.toString(), "Widget87 (Alpha87)" in found && "Widget87 (in Beta87)" in found)
        val chosen = choose(text, "Widget87 (in Beta87)")
        assertTrue("no using that would make the name ambiguous: $chosen", chosen.startsWith("using Alpha87;\nclass Panel") && chosen.contains("        Beta87.Widget87|\n"))
    }

    // ---- extension methods after a dot

    fun testUnimportedExtensionMethodsOfTheAssemblies() {
        val found = rows(body("numbers.Su<caret>"))
        assertTrue(found.toString(), found.any { it.startsWith("Sum() (in Fixture)") })
        assertTrue("Enumerable's Sum without System.Linq: $found", found.any { it.startsWith("Sum(") && it.contains("(in System.Linq)") })
        assertFalse("not for a string: ${rows(body("text.Su<caret>"))}", rows(body("text.Su<caret>")).any { it.contains("(in Fixture)") })
        assertTrue(rows(body("numbers.Whe<caret>")).any { it.startsWith("Where(") && it.contains("(in System.Linq)") })
        val imported = rows(body("numbers.Whe<caret>", usings = "using System;\nusing System.Linq;"))
        assertFalse("imported: an ordinary row ($imported)", imported.any { it.contains("(in System.Linq)") })
        assertFalse("nothing before a letter", rows(body("numbers.<caret>")).any { it.contains("(in ") })
    }

    /** `Twice<T>(this T shape) where T : Shape`: for a shape, not for a string (the constraint, as Roslyn checks it). */
    fun testTheConstraintsOfAGenericExtensionMethod() {
        assertTrue(rows("class A { void M(Fixture.Shape shape) { shape.Twi<caret> } }").any { it.startsWith("Twice() (in Fixture)") })
        assertFalse(rows(body("text.Twi<caret>")).any { it.contains("Twice") })
    }

    fun testChoosingAnUnimportedExtensionMethodAddsTheUsing() {
        val text = choose(body("numbers.Su<caret>"), rows(body("numbers.Su<caret>")).first { it.startsWith("Sum() (in Fixture)") })
        assertTrue(text, text.startsWith("using System;\nusing Fixture;\n"))
        assertTrue(text, text.contains("numbers.Sum()|"))
    }

    fun testUnimportedExtensionMethodsOfTheSolution() {
        myFixture.addFileToProject("Neighbours87/TextExtensions87.cs", "namespace Texts87;\npublic static class TextExtensions87 { public static string Shout87(this string text) => text; }\n")
        val found = rows(body("text.Shou<caret>"))
        assertTrue(found.toString(), found.any { it.startsWith("Shout87() (in Texts87)") })
        assertFalse(rows(body("numbers.Shou<caret>")).any { it.contains("Shout87") })
    }

    // ---- attributes

    fun testAttributesOfTheAssemblies() {
        val found = rows("using System;\n[Obs<caret>]\nclass Old { }\n")
        assertTrue(found.toString(), found.any { it.startsWith("Obsolete (System)") })
        val other = found.firstOrNull { it.startsWith("ObsoletedOSPlatform") }
        assertEquals("ObsoletedOSPlatform (in System.Runtime.Versioning)", other)
        val chosen = choose("using System;\n[Obs<caret>]\nclass Old { }\n", "ObsoletedOSPlatform (in System.Runtime.Versioning)")
        assertTrue(chosen, chosen.startsWith("using System;\nusing System.Runtime.Versioning;\n") && chosen.contains("[ObsoletedOSPlatform|]"))
    }

    private companion object {
        var counter = 0

        private fun bytes(name: String): ByteArray? = NativeImportCompletionTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
