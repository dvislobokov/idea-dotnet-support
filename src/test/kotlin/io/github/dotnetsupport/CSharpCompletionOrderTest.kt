package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The order of the list where Rider's differs (the comparison on Jellyfin, `docs/compare/jellyfin-2026-10-12.md`, № 8, 13, 14; 0.1.169):
 * names in scope above `new` in a condition and an argument, `throw` above the statics of the assemblies at `Thro`, an argument ranked
 * by the parameter's type with `nameof` / `null` above the members of other types and a type's name (`String`) low.
 */
class CSharpCompletionOrderTest : BasePlatformTestCase() {
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

    /** The rows of the list at `<caret>` of [body] (a statement line of a method of a service with fields), in the order shown. */
    private fun rows(body: String): List<String> {
        val text = """
            using System;

            namespace Shop;

            public class UserService
            {
                private readonly string _prefix = "";
                private readonly int _count;

                private void ThrowIfInvalidUsername(string name) { }

                public void ChangePassword(Guid userId, string password) { }

                public void RenameUser(Guid userId, string oldName, string newName)
                {
                    $body
                }
            }
        """.trimIndent()
        myFixture.configureByText("Order${counter++}.cs", text)
        myFixture.completeBasic()
        return myFixture.lookupElements?.map { it.lookupString }.orEmpty()
    }

    private fun index(rows: List<String>, row: String): Int = rows.indexOf(row).also { assertTrue("$row in $rows", it >= 0) }

    fun testNamesInScopeAboveNewInACondition() {
        val list = rows("if (<caret>) { }")
        for (name in listOf("newName", "oldName", "userId")) assertTrue(list.take(12).toString(), index(list, name) < index(list, "new"))
    }

    fun testNamesInScopeAboveNewInAnArgument() {
        val list = rows("ChangePassword(userId, <caret>);")
        assertTrue(list.take(12).toString(), index(list, "newName") < index(list, "new"))
        assertTrue(list.take(12).toString(), index(list, "oldName") < index(list, "new"))
    }

    fun testAnArgumentIsRankedByTheParameterType() {
        val list = rows("ChangePassword(userId, <caret>);")
        // the strings first; `nameof` and `null` (they fit a string) above the members of other types; the name of the type `String` low
        assertEquals(list.take(12).toString(), setOf("newName", "oldName"), list.take(2).toSet())
        assertTrue(list.take(12).toString(), index(list, "nameof") < index(list, "_count"))
        assertTrue(list.take(12).toString(), index(list, "null") < index(list, "_count"))
        val string = list.indexOf("String")
        assertTrue(list.take(16).toString(), string < 0 || string > index(list, "_count"))
    }

    fun testThrowAboveTheStaticsOfTheAssemblies() {
        val list = rows("Thro<caret>")
        assertEquals(list.take(6).toString(), "ThrowIfInvalidUsername", list[0])
        assertEquals(list.take(6).toString(), "throw", list[1])
    }

    companion object {
        private var counter = 0
        private fun bytes(name: String): ByteArray? = CSharpCompletionOrderTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
