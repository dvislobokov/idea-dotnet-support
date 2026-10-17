package io.github.dotnetsupport

import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpNavigationIntentions
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * The navigation Rider lists in Alt+Enter on a member's header (Jellyfin p01, p16, p19, p21, p24; 0.1.170): "Incoming Calls", "Outgoing
 * Calls", "Implements method from 'IStore' interface" / "Overrides method from 'Base' class" — only on the header, never in a body.
 */
class CSharpNavigationIntentionsTest : BasePlatformTestCase() {
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
    }

    override fun tearDown() {
        try {
            NativeCSharpNavigationIntentions.openForTests = null
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun titles(text: String): List<String> {
        myFixture.configureByText("Nav${files++}.cs", text)
        return myFixture.availableIntentions.map { it.text }
    }

    private val code = """
        using System;

        namespace Shop;

        public interface IStore { void Save(string name); }
        public abstract class Base { public abstract int Count { get; } }

        public class Store : Base, IStore
        {
            public Store(int size) { }
            public void <1>Save(string name) { Validate(name); }
            private void Validate(string name) { <2>Console.WriteLine(name); }
            public override int <3>Count => 0;
        }
    """.trimIndent()

    private fun at(marker: String): String = code.replace("<$marker>", "<caret>").replace(Regex("<\\d>"), "")

    fun testCallsAndTheImplementedMemberOnAMethodHeader() {
        val titles = titles(at("1"))
        assertTrue(titles.toString(), "Incoming Calls" in titles && "Outgoing Calls" in titles)
        assertTrue(titles.toString(), "Implements method from 'IStore' interface" in titles)
    }

    fun testTheOverriddenProperty() {
        val titles = titles(at("3"))
        assertTrue(titles.toString(), "Overrides property from 'Base' class" in titles)
    }

    fun testNothingInABody() {
        val titles = titles(at("2"))
        assertFalse(titles.toString(), titles.any { it == "Incoming Calls" || it == "Outgoing Calls" || it.startsWith("Implements ") || it.startsWith("Overrides ") })
    }

    fun testNoBaseMemberForAPrivateMethodAndTheCallsOpenTheirView() {
        val titles = titles(code.replace("void Validate", "void <caret>Validate").replace(Regex("<\\d>"), ""))
        assertTrue(titles.toString(), "Incoming Calls" in titles)
        assertFalse(titles.toString(), titles.any { it.startsWith("Implements ") || it.startsWith("Overrides ") })
        var opened: Pair<String?, Boolean>? = null
        NativeCSharpNavigationIntentions.openForTests = { target: PsiElement, callers: Boolean -> opened = CSharpDeclarationNames.name(target) to callers }
        myFixture.launchAction(myFixture.findSingleIntention("Outgoing Calls"))
        assertEquals(Pair<String?, Boolean>("Validate", false), opened)
        myFixture.launchAction(myFixture.findSingleIntention("Incoming Calls"))
        assertEquals(Pair<String?, Boolean>("Validate", true), opened)
    }

    companion object {
        private fun bytes(name: String): ByteArray? = CSharpNavigationIntentionsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
