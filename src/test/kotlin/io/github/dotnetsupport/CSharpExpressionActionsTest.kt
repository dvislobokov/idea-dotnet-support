package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * The context actions on expressions (0.1.171, `NativeCSharpExpressionActions.kt`, rows № 30–34 of the comparison with Rider on Jellyfin):
 * To safe cast, string forms, extension call ↔ static call, Generate try-catch block, Convert initializer to assignment; where each is not offered.
 */
class CSharpExpressionActionsTest : BasePlatformTestCase() {
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
    }

    override fun tearDown() {
        try {
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun apply(members: String, title: String): String {
        val head = head()
        myFixture.configureByText("Expression$files.cs", wrap(head, members))
        myFixture.launchAction(myFixture.findSingleIntention(title))
        return unwrap(head, myFixture.editor.document.text)
    }

    private fun available(members: String, title: String): Boolean {
        val head = head()
        myFixture.configureByText("Expression$files.cs", wrap(head, members))
        return myFixture.filterAvailableIntentions(title).any { it.text == title }
    }

    /** A namespace of its own per file: the light project keeps the files of the earlier tests, with the same types in them. */
    private fun head(): String = HEAD.replace("namespace Demo;", "namespace Demo${++files};")

    private fun wrap(head: String, members: String): String = head + members.trimIndent().prependIndent("    ") + "\n}\n"

    private fun unwrap(head: String, text: String): String = text.removePrefix(head).removeSuffix("\n}\n").lines().joinToString("\n") { it.removePrefix("    ") }

    // ---- To safe cast

    fun testToSafeCast() {
        assertEquals("string M(object item) => (item as Folder).ToString();", apply("string M(object item) => ((<caret>Folder)item).ToString();", "To safe cast"))
        assertEquals("void M(object item) { var f = item as Folder; }", apply("void M(object item) { var f = (<caret>Folder)item; }", "To safe cast"))
        // a cast that stood as an operand of something tighter keeps parentheses round the `as`
        assertEquals("string M(object item) => (item as Folder)?.ToString();", apply("string M(object item) => ((<caret>Folder)item)?.ToString();", "To safe cast"))
        assertEquals("void M(object o) { var n = o as int?; }", apply("void M(object o) { var n = (<caret>int?)o; }", "To safe cast"))
    }

    fun testToSafeCastIsNotOfferedForAValueType() {
        assertFalse(available("void M(object o) { var n = (<caret>int)o; }", "To safe cast"))
        assertFalse(available("void M(object o) { var p = (<caret>Point)o; }", "To safe cast"))
    }

    // ---- strings

    fun testStringForms() {
        assertEquals("""string P = "^\\w+$";""", apply("""string P = @"^\w<caret>+$";""", "To regular string"))
        assertEquals("""string P = @"C:\temp\a.txt";""", apply("""string P = "C:\\te<caret>mp\\a.txt";""", "To verbatim string"))
        assertEquals("string P = \"\"\"a\"b\"\"\";", apply("string P = @\"a\"\"<caret>b\";", "To raw string"))
        assertEquals("string P = \"\"\"\"a\"\"\"b\"\"\"\";", apply("string P = @\"a\"\"\"\"\"\"<caret>b\";", "To raw string"))
        assertEquals("string M(string name) => \$@\"Hi {name}\\x\";", apply("string M(string name) => \$\"Hi {name}\\\\<caret>x\";", "To verbatim string"))
        assertEquals("string M(string name) => \"Hi\";", apply("string M(string name) => \"\"\"H<caret>i\"\"\";", "To regular string"))
    }

    fun testStringFormsAreNotOfferedWhereNotExact() {
        // `\n` has no verbatim form of one line, a quote at the edge no raw one, `{{` no raw interpolated form with one `$`
        assertFalse(available("""string P = "a\nb<caret>";""", "To verbatim string"))
        assertFalse(available("""string P = @"""" + "\"\"" + """<caret>a";""", "To raw string"))
        assertFalse(available("string M(int x) => \$\"{{<caret>{x}}}\";", "To raw string"))
        assertFalse(available("""string P = "ab<caret>"u8.ToString();""", "To verbatim string"))
    }

    // ---- To static method invocation / To extension method invocation

    fun testToStaticMethodInvocation() {
        assertEquals("void M(List<User> users) { var ids = Enumerable.Select(users, u => u.Age); }",
            apply("void M(List<User> users) { var ids = users.Sel<caret>ect(u => u.Age); }", "To static method invocation"))
        assertEquals("int M() => Ext.Twice(1);", apply("int M() => 1.Tw<caret>ice();", "To static method invocation"))
    }

    fun testToExtensionMethodInvocation() {
        assertEquals("void M(List<User> users) { var ids = users.Select(u => u.Age); }",
            apply("void M(List<User> users) { var ids = Enumerable.Sel<caret>ect(users, u => u.Age); }", "To extension method invocation"))
        assertEquals("int M(int a, int b) => (a + b).Twice();", apply("int M(int a, int b) => Ext.Tw<caret>ice(a + b);", "To extension method invocation"))
    }

    fun testInvocationFormsAreNotOfferedForOrdinaryMethods() {
        assertFalse(available("void M(List<User> users) { users.Ad<caret>d(new User()); }", "To static method invocation"))
        assertFalse(available("int M() => Math.Ab<caret>s(1);", "To extension method invocation"))
    }

    // ---- Generate try-catch block

    fun testGenerateTryCatchBlock() {
        assertEquals(
            """
            void M()
            {
                try
                {
                    Save();
                }
                catch (Exception e)
                {
                    Console.WriteLine(e);
                    throw;
                }
            }
            void Save() { }
            """.trimIndent(),
            apply("void M()\n{\n    Sa<caret>ve();\n}\nvoid Save() { }", "Generate try-catch block"),
        )
    }

    fun testGenerateTryCatchIsNotOfferedWhereTheVariableIsUsedFurther() {
        assertFalse(available("void M()\n{\n    var x = Ma<caret>ke();\n    Use(x);\n}\nint Make() => 1;\nvoid Use(int x) { }", "Generate try-catch block"))
        assertFalse(available("void M()\n{\n    var <caret>x = 1;\n}", "Generate try-catch block"))
    }

    // ---- Convert initializer to assignment

    fun testConvertInitializerToAssignment() {
        assertEquals(
            "void M()\n{\n    var user = new User();\n    user.Name = \"a\";\n    user.Age = 1;\n}",
            apply("void M()\n{\n    va<caret>r user = new User { Name = \"a\", Age = 1 };\n}", "Convert initializer to assignment"),
        )
        assertEquals(
            "void M(User u)\n{\n    u = new User(\"x\");\n    u.Age = 2;\n}",
            apply("void M(User u)\n{\n    u = n<caret>ew User(\"x\") { Age = 2 };\n}", "Convert initializer to assignment"),
        )
    }

    fun testConvertInitializerIsNotOfferedForACollectionOrANestedInitializer() {
        assertFalse(available("void M()\n{\n    var l = ne<caret>w List<int> { 1, 2 };\n}", "Convert initializer to assignment"))
        assertFalse(available("void M()\n{\n    var b = ne<caret>w Box { Items = { 1 } };\n}", "Convert initializer to assignment"))
    }

    private companion object {
        val HEAD = "using System;\nusing System.Collections.Generic;\nusing System.Linq;\n\nnamespace Demo;\n\nclass Folder { }\nstruct Point { }\nclass User { public string Name { get; set; } public int Age { get; set; } public User() { } public User(string name) { } }\nclass Box { public List<int> Items { get; } = new(); }\nstatic class Ext { public static int Twice(this int x) => x * 2; }\n\nclass Sample\n{\n"

        private fun bytes(name: String): ByteArray? = CSharpExpressionActionsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
