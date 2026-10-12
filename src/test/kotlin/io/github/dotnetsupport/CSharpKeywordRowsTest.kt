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

/** The keywords stay in the list next to the types of the assemblies: `publi` before a class, `ca` in a `switch` (the user's report, 0.1.166). */
class CSharpKeywordRowsTest : BasePlatformTestCase() {
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

    private fun items(text: String): List<String> {
        myFixture.configureByText("Keywords${counter++}.cs", text)
        myFixture.completeBasic()
        return myFixture.lookupElements?.map { it.lookupString }.orEmpty()
    }

    fun testPublicBeforeAClass() {
        val list = items("using System;\n\nnamespace Shop;\n\npubli<caret>\n\npublic class Controller\n{\n}\n")
        assertTrue(list.toString(), "public" in list)
    }

    fun testPublicAmongMembers() {
        val list = items("namespace Shop;\n\npublic class Controller\n{\n    publi<caret>\n\n    public void M() { }\n}\n")
        assertTrue(list.toString(), "public" in list)
    }

    fun testCaseInASwitch() {
        val list = items("namespace Shop;\n\npublic enum Kind { A, B }\n\npublic class C\n{\n    void M(Kind kind)\n    {\n        switch (kind)\n        {\n            case Kind.A:\n                break;\n            ca<caret>\n        }\n    }\n}\n")
        assertTrue(list.toString(), "case" in list)
    }

    fun testStatementKeywords() {
        val list = items("namespace Shop;\n\npublic class C\n{\n    void M(int count)\n    {\n        re<caret>\n    }\n}\n")
        assertTrue(list.toString(), "return" in list)
    }

    fun testPublicAfterTheAttributesOfAClass() {
        val list = items("using System;\n\nnamespace Shop;\n\n[Serializable]\n[Obsolete]\npubli<caret>\npublic class Controller\n{\n}\n")
        assertTrue(list.toString(), "public" in list)
    }

    fun testPublicBeforeTheAttributesOfAClass() {
        val list = items("using System;\n\nnamespace Shop;\n\npubli<caret>\n[Serializable]\npublic class Controller\n{\n}\n")
        assertTrue(list.toString(), "public" in list)
    }

    fun testPublicInABlockNamespace() {
        val list = items("namespace Shop\n{\n    publi<caret>\n    public class Controller\n    {\n    }\n}\n")
        assertTrue(list.toString(), "public" in list)
    }

    fun testPublicAfterAMemberAttribute() {
        val list = items("using System;\n\npublic class C\n{\n    [Obsolete]\n    publi<caret>\n    public void M() { }\n}\n")
        assertTrue(list.toString(), "public" in list)
    }

    fun testCaseAfterAReturn() {
        val list = items("public enum Kind { A, B }\npublic class C\n{\n    string M(Kind kind)\n    {\n        switch (kind)\n        {\n            case Kind.A:\n                return \"a\";\n            ca<caret>\n        }\n        return \"\";\n    }\n}\n")
        assertTrue(list.toString(), "case" in list)
    }

    fun testCaseInAnEmptySwitch() {
        val list = items("public enum Kind { A, B }\npublic class C\n{\n    void M(Kind kind)\n    {\n        switch (kind)\n        {\n            ca<caret>\n        }\n    }\n}\n")
        assertTrue(list.toString(), "case" in list)
    }

    fun testCaseUnderALabel() {
        val list = items("public enum Kind { A, B }\npublic class C\n{\n    void M(Kind kind)\n    {\n        switch (kind)\n        {\n            case Kind.A:\n            ca<caret>\n        }\n    }\n}\n")
        assertTrue(list.toString(), "case" in list)
    }

    /** The text checks the gray text runs on every key and every move of the list's selection must not throw anywhere in a file. */
    fun testTheTextChecksOfTheGrayTextNeverThrow() {
        val root = java.io.File("debug-playground/Console/Editor")
        val failures = ArrayList<String>()
        for (file in root.listFiles { f -> f.name.endsWith(".cs") }.orEmpty().sortedBy { it.name }) {
            val text = file.readText().replace("\r\n", "\n")
            for (offset in 0..text.length) {
                fun probe(what: String, block: () -> Unit) {
                    try { block() } catch (e: Throwable) { if (failures.size < 20) failures += "${file.name}@$offset $what: $e" }
                }
                probe("place") { io.github.dotnetsupport.lang.NativeCSharpTypingGhost.place(text, offset) }
                for (rule in io.github.dotnetsupport.lang.NativeCSharpTypingGhost.rules) probe(rule.javaClass.simpleName) { rule.place(text, offset) }
                probe("itemPlace") { io.github.dotnetsupport.lang.NativeCSharpTypingGhost.itemPlace(text, offset, "public") }
                probe("ghostText") { io.github.dotnetsupport.lang.CSharpGhostText.ghost(text, offset) }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    companion object {
        private var counter = 0
        private fun bytes(name: String): ByteArray? = CSharpKeywordRowsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
