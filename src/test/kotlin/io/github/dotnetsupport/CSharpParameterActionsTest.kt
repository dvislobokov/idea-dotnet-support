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

    /** № 26: a local of a reference type — its check right after the declaration (Rider: "Check variable for null"). */
    fun testCheckVariableForNull() {
        val text = apply("void M()\n{\n    var inco<caret>ming = new List<int>();\n    incoming.Add(1);\n}", "Check variable for null")
        assertEquals("void M()\n{\n    var incoming = new List<int>();\n    ${check("incoming")}\n    incoming.Add(1);\n}", text)
        assertFalse(available("void M()\n{\n    var co<caret>unt = 1;\n}", "Check variable for null"))
        assertFalse(available("void M()\n{\n    var inco<caret>ming = new List<int>();\n    ArgumentNullException.ThrowIfNull(incoming);\n}", "Check variable for null"))
    }

    /** № 26: a value that may be null — its statement under `if (… != null)`, or `Debug.Assert` before it. */
    fun testCheckExpressionForNullAndAssert() {
        val members = "class User { public string Name { get; set; } public int Age { get; set; } }\nvoid Use(string s) { }\nvoid M(User user)\n{\n    Use(user.Na<caret>me);\n}"
        assertEquals(
            "class User { public string Name { get; set; } public int Age { get; set; } }\nvoid Use(string s) { }\nvoid M(User user)\n{\n    if (user.Name != null)\n    {\n        Use(user.Name);\n    }\n}",
            apply(members, "Check expression for null"),
        )
        assertEquals(
            "class User { public string Name { get; set; } public int Age { get; set; } }\nvoid Use(string s) { }\nvoid M(User user)\n{\n    System.Diagnostics.Debug.Assert(user.Name != null, \"user.Name != null\");\n    Use(user.Name);\n}",
            apply(members, "Assert expression is not null"),
        )
        // a value type, a literal, a creation, the target of an assignment: never null or no value read
        assertFalse(available("class User { public int Age { get; set; } }\nvoid M(User user)\n{\n    var a = user.A<caret>ge;\n}", "Check expression for null"))
        assertFalse(available("void M()\n{\n    var s = \"a<caret>b\";\n}", "Check expression for null"))
        assertFalse(available("void M()\n{\n    var l = new List<<caret>int>();\n}", "Assert expression is not null"))
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

    /** The caret in a string literal of an attribute's argument (Jellyfin p22, `[GeneratedRegex(@"…")]`): one name, in the title as Rider has it (0.1.170). */
    fun testAddArgumentNameInAnAttributeLiteral() {
        assertEquals(
            "class RxAttribute : Attribute { public RxAttribute(string pattern, int options = 0) { } }\n[Rx(pattern: @\"^ab$\")]\nvoid M() { }",
            apply("class RxAttribute : Attribute { public RxAttribute(string pattern, int options = 0) { } }\n[Rx(@\"^a<caret>b$\")]\nvoid M() { }", "Add argument name 'pattern'"),
        )
        // two positional arguments from the caret: the plain title, both named
        assertEquals(
            "class RxAttribute : Attribute { public RxAttribute(string pattern, int options = 0) { } }\n[Rx(pattern: \"a\", options: 1)]\nvoid M() { }",
            apply("class RxAttribute : Attribute { public RxAttribute(string pattern, int options = 0) { } }\n[Rx(\"<caret>a\", 1)]\nvoid M() { }", "Add argument name"),
        )
        // in a call too, the caret inside the literal
        assertEquals(
            "void Save(string name) { }\nvoid M()\n{\n    Save(name: \"abc\");\n}",
            apply("void Save(string name) { }\nvoid M()\n{\n    Save(\"a<caret>bc\");\n}", "Add argument name 'name'"),
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

    // ---- Create overload without parameter, Chop parameters list, Put parameters on one line (0.1.173)

    fun testCreateOverloadWithoutParameter() {
        assertEquals(
            "public async Task<int> Save(string name, bool fo<caret>rce)\n{\n    return 1;\n}\n\npublic Task<int> Save(string name) => Save(name, default);".replace("<caret>", ""),
            apply("public async Task<int> Save(string name, bool fo<caret>rce)\n{\n    return 1;\n}", "Create overload without parameter")
                .replace("using System.Threading.Tasks;", ""),
        )
        // the default value of an optional parameter is passed on; `ref` arguments keep their kind
        assertEquals(
            "private static void Copy(ref int a, int retries = 3) { }\n\nprivate static void Copy(ref int a) => Copy(ref a, 3);",
            apply("private static void Copy(ref int a, int <caret>retries = 3) { }", "Create overload without parameter"),
        )
        // an overload of that signature exists, the receiver of an extension method, a generic method
        assertFalse(available("void Save(string name, bool <caret>force) { }\nvoid Save(string name) { }", "Create overload without parameter"))
        assertFalse(available("void Save<T>(T value, bool <caret>force) { }", "Create overload without parameter"))
    }

    fun testChopAndJoinParameters() {
        assertEquals(
            "void Save(string name,\n          bool force,\n          int retries) { }",
            apply("void Save(string <caret>name, bool force, int retries) { }", "Chop parameters list"),
        )
        assertEquals(
            "void Save(string name, bool force) { }",
            apply("void Save(string <caret>name,\n          bool force) { }", "Put parameters on one line"),
        )
        // one parameter; already one per line; already on one line
        assertFalse(available("void Save(string <caret>name) { }", "Chop parameters list"))
        assertFalse(available("void Save(string <caret>name,\n          bool force) { }", "Chop parameters list"))
        assertFalse(available("void Save(string <caret>name, bool force) { }", "Put parameters on one line"))
    }

    private companion object {
        const val HEAD ="using System;\nusing System.Collections.Generic;\n\nnamespace Demo;\n\nclass Sample\n{\n"

        private fun bytes(name: String): ByteArray? = CSharpParameterActionsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
