package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpParameterEdits
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The context actions on parameters and arguments (0.1.163, `NativeCSharpParameterActions.kt`): the null check of a parameter, the names
 * of the arguments of a call added and removed; and where each is not offered.
 */
class CSharpParameterActionsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.CONTEXT_ACTIONS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            settings.state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun apply(members: String, title: String): String {
        myFixture.configureByText("Param${files++}.cs", wrap(members))
        myFixture.launchAction(myFixture.findSingleIntention(title))
        return unwrap(myFixture.editor.document.text)
    }

    private fun available(members: String, title: String): Boolean {
        myFixture.configureByText("Param${files++}.cs", wrap(members))
        return myFixture.filterAvailableIntentions(title).any { it.text == title }
    }

    private fun wrap(members: String): String = HEAD + members.trimIndent().prependIndent("    ") + "\n}\n"

    private fun unwrap(text: String): String = text.removePrefix(HEAD).removeSuffix("\n}\n").lines().joinToString("\n") { it.removePrefix("    ") }

    // ---- Check parameter for null

    /** The check the fixture assemblies allow: `ThrowIfNull` when they know it (net6+), the `if` otherwise. */
    private fun check(name: String): String {
        val resolver = CSharpSemanticSession(project).resolver(myFixture.file as CSharpFile)
        return if (NativeCSharpParameterEdits.hasThrowIfNull(resolver)) "ArgumentNullException.ThrowIfNull($name);" else "if ($name == null) throw new ArgumentNullException(nameof($name));"
    }

    fun testCheckParameterForNullGoesFirstInTheBody() {
        val text = apply("void M(string na<caret>me, int count)\n{\n    Console.WriteLine(name);\n}", "Check parameter for null")
        assertEquals("void M(string name, int count)\n{\n    ${check("name")}\n    Console.WriteLine(name);\n}", text)
        // an empty body, a constructor
        val empty = apply("class Order { }\nSample(Or<caret>der order) { }", "Check parameter for null")
        assertEquals("class Order { }\nSample(Order order) {\n    ${check("order")}\n}", empty)
    }

    /** Jellyfin № 65: an `async Task` method whose body starts with a call — applied by the first run, and its preview is computed. */
    fun testCheckParameterForNullOnceAndItsPreview() {
        val members = "void ThrowIfInvalid(string s) { }\nasync System.Threading.Tasks.Task Rename(int id, string old<caret>Name, string newName)\n{\n    ThrowIfInvalid(newName);\n}"
        myFixture.configureByText("Param${files++}.cs", wrap(members))
        val action = myFixture.findSingleIntention("Check parameter for null")
        val preview = myFixture.getIntentionPreviewText(action)
        assertTrue(preview.toString(), preview != null && preview.contains("oldName") && preview.contains("ThrowIf") || preview?.contains("== null") == true)
        myFixture.launchAction(action)
        val text = unwrap(myFixture.editor.document.text)
        assertTrue(text, text.contains("{\n    ${check("oldName")}\n    ThrowIfInvalid(newName);"))
    }

    fun testCheckParameterForNullIsNotOffered() {
        // a value type, a nullable-annotated type, an expression body, a check there already
        assertFalse(available("void M(int co<caret>unt) { }", "Check parameter for null"))
        assertFalse(available("void M(string? na<caret>me) { }", "Check parameter for null"))
        assertFalse(available("int M(string na<caret>me) => name.Length;", "Check parameter for null"))
        assertFalse(available("void M(string na<caret>me)\n{\n    if (name == null) throw new ArgumentNullException(nameof(name));\n}", "Check parameter for null"))
        assertFalse(available("void M(string na<caret>me)\n{\n    ArgumentNullException.ThrowIfNull(name);\n}", "Check parameter for null"))
    }

    // ---- Add / Remove argument names

    fun testAddArgumentNames() {
        assertEquals(
            "void Save(string name, bool force) { }\nvoid M()\n{\n    Save(name: \"a\", force: true);\n}",
            apply("void Save(string name, bool force) { }\nvoid M()\n{\n    Save(\"a\", <caret>true);\n}", "Add argument names"),
        )
        // a creation; an argument named already keeps its name
        assertEquals(
            "class Order { public Order(string name, int count, bool force) { } }\nvoid M()\n{\n    var o = new Order(name: \"a\", count: 1, force: false);\n}",
            apply("class Order { public Order(string name, int count, bool force) { } }\nvoid M()\n{\n    var o = new Order(<caret>\"a\", count: 1, false);\n}", "Add argument names"),
        )
    }

    fun testAddArgumentNamesIsNotOffered() {
        // one positional argument (the one-argument action covers it), several values of a `params` parameter, named already
        assertFalse(available("void Save(string name) { }\nvoid M()\n{\n    Save(<caret>\"a\");\n}", "Add argument names"))
        assertFalse(available("void Log(string head, params string[] parts) { }\nvoid M()\n{\n    Log(<caret>\"a\", \"b\", \"c\");\n}", "Add argument names"))
        assertFalse(available("void Save(string name, bool force) { }\nvoid M()\n{\n    Save(name: \"a\", <caret>force: true);\n}", "Add argument names"))
    }

    fun testRemoveArgumentNames() {
        assertEquals(
            "void Save(string name, bool force) { }\nvoid M()\n{\n    Save(\"a\", true);\n}",
            apply("void Save(string name, bool force) { }\nvoid M()\n{\n    Save(name: \"a\", <caret>force: true);\n}", "Remove argument names"),
        )
        // the order differs from the parameters'; one positional
        assertFalse(available("void Save(string name, bool force) { }\nvoid M()\n{\n    Save(force: true, <caret>name: \"a\");\n}", "Remove argument names"))
        assertFalse(available("void Save(string name, bool force) { }\nvoid M()\n{\n    Save(\"a\", <caret>force: true);\n}", "Remove argument names"))
    }

    private companion object {
        const val HEAD = "using System;\nusing System.Collections.Generic;\n\nnamespace Demo;\n\nclass Sample\n{\n"

        private fun bytes(name: String): ByteArray? = CSharpParameterActionsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
