package io.github.dotnetsupport

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFeatures
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpServerActions
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The context actions of the native tree (CSHARP_PSI_MIGRATION.md, task A7): `if` ↔ `?:`, block ↔ expression body, `var` ↔ explicit
 * type (types of C2 over the fixture assemblies), introduce / inline variable; where each is not offered; the server's rows they stand for.
 */
class CSharpContextActionsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.CONTEXT_ACTIONS, CSharpFeatureSource.NATIVE)
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            settings.state.enabled = true
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** [body] as the members of a class with the usual usings; after [title] at `<caret>`, the members back (the class around them cut off). */
    private fun apply(members: String, title: String, usings: String = USINGS): String {
        myFixture.configureByText("Context${files++}.cs", wrap(members, usings))
        myFixture.launchAction(myFixture.findSingleIntention(title))
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)
        return unwrap(myFixture.editor.document.text, usings)
    }

    private fun available(members: String, title: String, usings: String = USINGS): Boolean {
        myFixture.configureByText("Context${files++}.cs", wrap(members, usings))
        return myFixture.filterAvailableIntentions(title).any { it.text == title }
    }

    private fun wrap(members: String, usings: String): String = usings + "class Sample\n{\n" + members.trimIndent().prependIndent("    ") + "\n}\n"

    private fun unwrap(text: String, usings: String): String =
        text.removePrefix(usings).removePrefix("class Sample\n{\n").removeSuffix("\n}\n").lines().joinToString("\n") { it.removePrefix("    ") }

    // ---- if ↔ ?:

    fun testIfWithReturnsBecomesConditional() {
        assertEquals(
            """
            string M(int count)
            {
                return count > 0 ? "some" : "none";
            }
            """.trimIndent(),
            apply("""
                string M(int count)
                {
                    <caret>if (count > 0)
                    {
                        return "some";
                    }
                    else
                        return "none";
                }
            """, "Convert to '?:' expression"),
        )
    }

    fun testIfFollowedByReturn() {
        assertEquals(
            "int M(bool flag)\n{\n    var x = 1;\n    return flag ? x : 2;\n}",
            apply("int M(bool flag)\n{\n    var x = 1;\n    i<caret>f (flag) return x;\n    return 2;\n}", "Convert to '?:' expression"),
        )
    }

    fun testIfWithAssignments() {
        assertEquals(
            "void M(bool flag)\n{\n    int x;\n    x = (flag = true) ? 1 : 2;\n}",
            apply("void M(bool flag)\n{\n    int x;\n    <caret>if (flag = true) x = 1; else { x = 2; }\n}", "Convert to '?:' expression"),
        )
    }

    fun testIfToConditionalIsNotOffered() {
        // different targets, a comment that would be lost, a branch of two statements, `else if`, the caret in the body
        assertFalse(available("void M(bool f)\n{\n    int x, y;\n    <caret>if (f) x = 1; else y = 2;\n}", "Convert to '?:' expression"))
        assertFalse(available("int M(bool f)\n{\n    <caret>if (f) return 1; // one\n    else return 2;\n}", "Convert to '?:' expression"))
        assertFalse(available("int M(bool f)\n{\n    <caret>if (f) { M(f); return 1; } else return 2;\n}", "Convert to '?:' expression"))
        assertFalse(available("int M(bool f, bool g)\n{\n    <caret>if (f) return 1; else if (g) return 2; else return 3;\n}", "Convert to '?:' expression"))
        assertFalse(available("int M(bool f)\n{\n    if (f) return <caret>1; else return 2;\n}", "Convert to '?:' expression"))
    }

    fun testConditionalBecomesIf() {
        assertEquals(
            "string M(int count)\n{\n    if (count > 0)\n    {\n        return \"some\";\n    }\n    else\n    {\n        throw new System.InvalidOperationException();\n    }\n}",
            apply("string M(int count)\n{\n    return count > 0 <caret>? \"some\" : throw new System.InvalidOperationException();\n}", "Convert '?:' to 'if' statement"),
        )
        assertEquals(
            "void M(bool flag)\n{\n    int x;\n    if (flag)\n    {\n        x += 1;\n    }\n    else\n    {\n        x += 2;\n    }\n}",
            apply("void M(bool flag)\n{\n    int x;\n    x += (fl<caret>ag) ? 1 : 2;\n}", "Convert '?:' to 'if' statement"),
        )
        // an argument is no statement of its own
        assertFalse(available("void M(bool flag)\n{\n    System.Console.WriteLine(flag <caret>? 1 : 2);\n}", "Convert '?:' to 'if' statement"))
    }

    // ---- bodies

    fun testToExpressionBody() {
        assertEquals("int Twice(int value) => value * 2;", apply("int <caret>Twice(int value)\n{\n    return value * 2;\n}", "To expression body"))
        assertEquals("void Log(string text) => System.Console.WriteLine(text);", apply("void <caret>Log(string text)\n{\n    System.Console.WriteLine(text);\n}", "To expression body"))
        assertEquals("int Fail() => throw new System.Exception();", apply("int <caret>Fail()\n{\n    throw new System.Exception();\n}", "To expression body"))
        assertEquals("int Count => 42;", apply("int <caret>Count\n{\n    get { return 42; }\n}", "To expression body"))
        assertEquals("int Count { get => 42; set { } }", apply("int Count { <caret>get { return 42; } set { } }", "To expression body"))
        assertEquals(
            "void M()\n{\n    int Local(int a) => a + 1;\n}",
            apply("void M()\n{\n    int <caret>Local(int a)\n    {\n        return a + 1;\n    }\n}", "To expression body"),
        )
        assertEquals("Sample(int a) => System.Console.WriteLine(a);", apply("<caret>Sample(int a)\n{\n    System.Console.WriteLine(a);\n}", "To expression body"))
    }

    fun testToExpressionBodyIsNotOffered() {
        assertFalse(available("int <caret>Two()\n{\n    var a = 1;\n    return a + 1;\n}", "To expression body"))
        assertFalse(available("int <caret>One()\n{\n    // the answer\n    return 1;\n}", "To expression body"))
        assertFalse("a return in a void method is no expression", available("void <caret>None()\n{\n    return;\n}", "To expression body"))
        assertFalse("in the body", available("int One()\n{\n    return <caret>1;\n}", "To expression body"))
        assertFalse("a setter too", available("int <caret>Count { get { return 1; } set { } }", "To expression body"))
        assertFalse("an auto-property", available("int <caret>Count { get; }", "To expression body"))
    }

    fun testToBlockBody() {
        assertEquals("int Twice(int value)\n{\n    return value * 2;\n}", apply("int <caret>Twice(int value) => value * 2;", "To block body"))
        assertEquals("void Log(string text)\n{\n    System.Console.WriteLine(text);\n}", apply("void <caret>Log(string text) => System.Console.WriteLine(text);", "To block body"))
        assertEquals(
            "async System.Threading.Tasks.Task Run()\n{\n    await System.Threading.Tasks.Task.Delay(1);\n}",
            apply("async System.Threading.Tasks.Task <caret>Run() => await System.Threading.Tasks.Task.Delay(1);", "To block body"),
        )
        assertEquals("int Fail()\n{\n    throw new System.Exception();\n}", apply("int <caret>Fail() => throw new System.Exception();", "To block body"))
        assertEquals("int Count\n{\n    get { return 42; }\n}", apply("int <caret>Count => 42;", "To block body"))
        assertEquals("int Count { get { return 42; } set { } }", apply("int Count { <caret>get => 42; set { } }", "To block body"))
        assertEquals(
            "int _x;\nint X\n{\n    get => _x;\n    set\n    {\n        _x = value;\n    }\n}",
            apply("int _x;\nint X\n{\n    get => _x;\n    <caret>set => _x = value;\n}", "To block body"),
        )
    }

    // ---- var ↔ explicit type

    fun testUseExplicitType() {
        assertEquals("void M()\n{\n    List<int> list = new List<int>();\n}", apply("void M()\n{\n    <caret>var list = new List<int>();\n}", "Use explicit type"))
        assertEquals("void M()\n{\n    int n = 1 + 2;\n}", apply("void M()\n{\n    var <caret>n = 1 + 2;\n}", "Use explicit type"))
        assertEquals(
            "void M(List<string> names)\n{\n    foreach (string name in names) { }\n}",
            apply("void M(List<string> names)\n{\n    foreach (<caret>var name in names) { }\n}", "Use explicit type"),
        )
        // the namespace is written where it is not imported
        assertEquals(
            "void M()\n{\n    System.Collections.Generic.List<int> list = new System.Collections.Generic.List<int>();\n}",
            apply("void M()\n{\n    <caret>var list = new System.Collections.Generic.List<int>();\n}", "Use explicit type", usings = ""),
        )
        assertFalse("no type", available("void M()\n{\n    <caret>var x = Unknown();\n}", "Use explicit type"))
        assertFalse("not on an explicit type", available("void M()\n{\n    <caret>int x = 1;\n}", "Use explicit type"))
    }

    fun testUseVar() {
        assertEquals("void M()\n{\n    var n = 1;\n}", apply("void M()\n{\n    <caret>int n = 1;\n}", "Use 'var'"))
        assertEquals("void M()\n{\n    var list = new List<int>();\n}", apply("void M()\n{\n    List<int> <caret>list = new List<int>();\n}", "Use 'var'"))
        assertFalse("another type", available("void M()\n{\n    <caret>long n = 1;\n}", "Use 'var'"))
        assertFalse("an interface", available("void M()\n{\n    <caret>IList<int> list = new List<int>();\n}", "Use 'var'"))
        assertFalse("target-typed", available("void M()\n{\n    <caret>List<int> list = new();\n}", "Use 'var'"))
        assertFalse("null", available("void M()\n{\n    <caret>string s = null;\n}", "Use 'var'"))
        assertFalse("const", available("void M()\n{\n    const <caret>int n = 1;\n}", "Use 'var'"))
        assertFalse("no value", available("void M()\n{\n    <caret>int n;\n}", "Use 'var'"))
    }

    // ---- introduce / inline

    fun testIntroduceVariable() {
        assertEquals(
            "void M(List<int> numbers)\n{\n    var count = numbers.Count;\n    Console.WriteLine(count * 2);\n}",
            apply("void M(List<int> numbers)\n{\n    Console.WriteLine(numbers.Cou<caret>nt * 2);\n}", "Introduce variable"),
        )
        // the name of the call; a taken name gets a number
        assertEquals(
            "string Text() => \"\";\nvoid M(int text)\n{\n    var text1 = Text();\n    Console.WriteLine(text1.Length);\n}",
            apply("string Text() => \"\";\nvoid M(int text)\n{\n    Console.WriteLine(Te<caret>xt().Length);\n}", "Introduce variable"),
        )
        // a call that is the whole statement
        assertEquals(
            "string Text() => \"\";\nvoid M()\n{\n    var text = Text();\n}",
            apply("string Text() => \"\";\nvoid M()\n{\n    <caret>Text();\n}", "Introduce variable"),
        )
    }

    fun testIntroduceVariableOfASelection() {
        myFixture.configureByText("Context${files++}.cs", wrap("int M(int a, int b)\n{\n    return <selection>a * b </selection>+ 2;\n}", USINGS))
        myFixture.launchAction(myFixture.findSingleIntention("Introduce variable"))
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)
        assertEquals("int M(int a, int b)\n{\n    var value = a * b;\n    return value + 2;\n}", unwrap(myFixture.editor.document.text, USINGS))
    }

    fun testIntroduceVariableIsNotOffered() {
        assertFalse("evaluated only sometimes", available("bool Check() => true;\nbool M(bool a)\n{\n    return a && Che<caret>ck();\n}", "Introduce variable"))
        assertFalse("a loop's condition", available("bool Check() => true;\nvoid M()\n{\n    while (Che<caret>ck()) { }\n}", "Introduce variable"))
        assertFalse("in a lambda", available("int Get() => 1;\nvoid M()\n{\n    Func<int> f = () => Ge<caret>t();\n}", "Introduce variable"))
        assertFalse("void", available("void Run() { }\nvoid M()\n{\n    Ru<caret>n();\n}", "Introduce variable"))
        assertFalse("a local already", available("void M(int a)\n{\n    Console.WriteLine(<caret>a);\n}", "Introduce variable"))
    }

    fun testInlineVariable() {
        assertEquals(
            "int M(int a, int b)\n{\n    return (a + b) * 2 + Math.Abs(a + b);\n}",
            apply("int M(int a, int b)\n{\n    var <caret>sum = a + b;\n    return sum * 2 + Math.Abs(sum);\n}", "Inline variable"),
        )
        assertEquals(
            "int M(List<int> list)\n{\n    Console.WriteLine(list.Count);\n    return list.Count;\n}",
            apply("int M(List<int> list)\n{\n    var count = list.Count;\n    Console.WriteLine(cou<caret>nt);\n    return count;\n}", "Inline variable"),
        )
        assertFalse("written again", available("int M()\n{\n    var <caret>x = 1;\n    x++;\n    return x;\n}", "Inline variable"))
        assertFalse("in nameof", available("string M()\n{\n    var <caret>x = 1;\n    return nameof(x);\n}", "Inline variable"))
        assertFalse("unused", available("void M()\n{\n    var <caret>x = 1;\n}", "Inline variable"))
        assertFalse("a parameter", available("int M(int <caret>a)\n{\n    return a;\n}", "Inline variable"))
        assertFalse("a lambda", available("int M()\n{\n    Func<int> <caret>f = () => 1;\n    return f();\n}", "Inline variable"))
        assertFalse("target-typed", available("int M()\n{\n    List<int> <caret>list = new();\n    return list.Count;\n}", "Inline variable"))
    }

    // ---- the server

    fun testTheServersRowsStandBackForNativeOnes() {
        assertTrue(CSharpFeatures.hasNative(CSharpFeature.CONTEXT_ACTIONS))
        assertEquals("Language server until the robot has checked them", CSharpFeatureSource.ROSLYN, CSharpFeature.CONTEXT_ACTIONS.defaultSource)
        for (title in listOf("Use expression body for method", "Use block body for property", "Convert to conditional expression", "Use explicit type",
            "Use implicit type", "Introduce local for 'a + b'", "Inline temporary variable")) {
            assertTrue(title, NativeCSharpServerActions.shadowed(title, project))
        }
        assertFalse("a constant is not the native action's", NativeCSharpServerActions.shadowed("Introduce local constant for '1'", project))
        settings.setSource(CSharpFeature.CONTEXT_ACTIONS, CSharpFeatureSource.ROSLYN)
        assertFalse(NativeCSharpServerActions.shadowed("Use expression body for method", project))
        // the server is not ready in tests: the native actions answer under ROSLYN as well
        assertTrue(available("int <caret>One() => 1;", "To block body"))
    }

    private companion object {
        const val USINGS = "using System;\nusing System.Collections.Generic;\n\n"

        private fun bytes(name: String): ByteArray? = CSharpContextActionsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
