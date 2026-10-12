package io.github.dotnetsupport

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpExtractMethod
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * Extract Method of the native tree (CSHARP_PSI_MIGRATION.md, task C4d): parameters from the data flow of the locals, the value returned
 * (declared inside or assigned outside), `out` / `ref` for more, `async`, `static`, an expression; what is refused and why.
 */
class CSharpExtractMethodTest : BasePlatformTestCase() {
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

    private fun configure(body: String, usings: String = "using System;\nusing System.Threading.Tasks;\n\n") {
        myFixture.configureByText("Extract${files++}.cs", usings + body.trimIndent() + "\n")
    }

    private fun extract(body: String, usings: String = "using System;\nusing System.Threading.Tasks;\n\n"): String {
        configure(body, usings)
        NativeCSharpExtractMethod.perform(project, myFixture.editor, myFixture.file as CSharpFile)
        return myFixture.editor.document.text.removePrefix(usings).trimEnd()
    }

    private fun error(body: String): String? {
        configure(body)
        val selection = myFixture.editor.selectionModel
        return (NativeCSharpExtractMethod.analyze(myFixture.file as CSharpFile, TextRange(selection.selectionStart, selection.selectionEnd)) as? NativeCSharpExtractMethod.Result.Error)?.message
    }

    fun testStatementsWithParametersBecomeAStaticVoidMethod() {
        assertEquals(
            """
            class Sample
            {
                int M(int a, int b)
                {
                    NewMethod(a, b);
                    return a;
                }

                private static void NewMethod(int a, int b)
                {
                    var sum = a + b;
                    Console.WriteLine(sum);
                }
            }
            """.trimIndent(),
            extract("""
                class Sample
                {
                    int M(int a, int b)
                    {
                        <selection>var sum = a + b;
                        Console.WriteLine(sum);</selection>
                        return a;
                    }
                }
            """),
        )
    }

    fun testAVariableDeclaredInsideAndUsedAfterIsReturned() {
        val text = extract("""
            class Sample
            {
                int M(int a, int b)
                {
                    <selection>var sum = a + b;
                    sum *= 2;</selection>
                    return sum;
                }
            }
        """)
        assertTrue(text, text.contains("        var sum = NewMethod(a, b);\n        return sum;"))
        assertTrue(text, text.contains("    private static int NewMethod(int a, int b)\n    {\n        var sum = a + b;\n        sum *= 2;\n        return sum;\n    }"))
    }

    fun testAVariableOfTheMemberAssignedInsideIsReturnedAndAnotherGoesByOut() {
        val text = extract("""
            class Sample
            {
                void M(int a)
                {
                    int total;
                    string label;
                    <selection>total = a * 2;
                    label = total.ToString();</selection>
                    Console.WriteLine(total + label);
                }
            }
        """)
        assertTrue(text, text.contains("        total = NewMethod(a, out label);"))
        assertTrue(text, text.contains("    private static int NewMethod(int a, out string label)\n    {\n        int total;\n        total = a * 2;\n        label = total.ToString();\n        return total;\n    }"))
    }

    fun testAnExpressionWithInstanceMembers() {
        val text = extract("""
            class Sample
            {
                private int _factor = 3;

                int M(int a)
                {
                    return <selection>a * _factor + 1</selection>;
                }
            }
        """)
        assertTrue(text, text.contains("return NewMethod(a);"))
        assertTrue(text, text.contains("    private int NewMethod(int a)\n    {\n        return a * _factor + 1;\n    }"))
    }

    fun testAwaitMakesTheMethodAsync() {
        val text = extract("""
            class Sample
            {
                async Task<int> M(Task<int> work)
                {
                    <selection>var value = await work;</selection>
                    return value;
                }
            }
        """)
        assertTrue(text, text.contains("var value = await NewMethod(work);"))
        assertTrue(text, text.contains("private static async Task<int> NewMethod(Task<int> work)"))
    }

    fun testTheNameDoesNotRepeatAMember() {
        val text = extract("class Sample\n{\n    void NewMethod() { }\n\n    void M()\n    {\n        <selection>Console.WriteLine(1);</selection>\n    }\n}")
        assertTrue(text, text.contains("NewMethod1();") && text.contains("private static void NewMethod1()"))
    }

    fun testWhatIsRefused() {
        assertNotNull(error("class S\n{\n    int M(int a)\n    {\n        <selection>if (a > 0) return 1;</selection>\n        return 0;\n    }\n}"))
        assertNotNull(error("class S\n{\n    void M()\n    {\n        while (true)\n        {\n            <selection>break;</selection>\n        }\n    }\n}"))
        assertNotNull(error("class S\n{\n    void M()\n    {\n        <selection>var a = 1;\n        var b = 2;</selection>\n        Console.WriteLine(a + b);\n    }\n}"))
        assertNotNull(error("class S\n{\n    void M()\n    {\n        var x = 1<selection> + </selection>2;\n    }\n}"))
        assertNull("a loop with its break is fine", error("class S\n{\n    void M()\n    {\n        <selection>while (true)\n        {\n            break;\n        }</selection>\n    }\n}"))
    }

    // ---- loops and jumps (0.1.81)

    fun testAVariableWrittenInsideAndReadByTheNextIterationIsReturned() {
        assertEquals(
            """
            class Sample
            {
                void M(int n)
                {
                    var last = -1;
                    for (var i = 0; i < n; i++)
                    {
                        if (last >= 0) Console.WriteLine(last);
                        last = NewMethod(i);
                    }
                }

                private static int NewMethod(int i)
                {
                    int last;
                    last = i * 2;
                    return last;
                }
            }
            """.trimIndent(),
            extract("""
                class Sample
                {
                    void M(int n)
                    {
                        var last = -1;
                        for (var i = 0; i < n; i++)
                        {
                            if (last >= 0) Console.WriteLine(last);
                            <selection>last = i * 2;</selection>
                        }
                    }
                }
            """),
        )
    }

    fun testAVariableReadThenWrittenInALoopComesInAndGoesBack() {
        val text = extract("""
            class Sample
            {
                void M(int[] items)
                {
                    var sum = 0;
                    foreach (var x in items)
                    {
                        <selection>Console.WriteLine(sum);
                        sum += x;</selection>
                    }
                }
            }
        """)
        assertTrue(text, text.contains("            sum = NewMethod(sum, x);"))
        assertTrue(text, text.contains("    private static int NewMethod(int sum, int x)\n    {\n        Console.WriteLine(sum);\n        sum += x;\n        return sum;\n    }"))
    }

    fun testTheVariableOfAForLoopWrittenInTheBody() {
        val text = extract("""
            class Sample
            {
                void M(int n, int step)
                {
                    for (int i = 0; i < n; i++)
                    {
                        <selection>i += step;</selection>
                    }
                }
            }
        """)
        assertTrue(text, text.contains("            i = NewMethod(i, step);"))
        assertTrue(text, text.contains("private static int NewMethod(int i, int step)"))
    }

    fun testAVariableWrittenOnSomePathsKeepsItsValueOnTheOthers() {
        val text = extract("""
            class Sample
            {
                int M(int[] a, int t)
                {
                    var found = -1;
                    for (var i = 0; i < a.Length; i++)
                    {
                        <selection>if (a[i] == t) found = i;</selection>
                    }
                    return found;
                }
            }
        """)
        assertTrue(text, text.contains("            found = NewMethod(a, i, t, found);"))
        assertTrue(text, text.contains("    private static int NewMethod(int[] a, int i, int t, int found)\n    {\n        if (a[i] == t) found = i;\n        return found;\n    }"))
    }

    fun testAnAssignmentReadingTheVariableItselfReadsItFirst() {
        val text = extract("""
            class Sample
            {
                int M(int total)
                {
                    <selection>total = total * 2;</selection>
                    return total;
                }
            }
        """)
        assertTrue(text, text.contains("        total = NewMethod(total);"))
        assertTrue(text, text.contains("private static int NewMethod(int total)\n    {\n        total = total * 2;\n        return total;"))
    }

    fun testABreakOutOfTheSelectionMakesTheMethodTellTheCallToBreak() {
        assertEquals(
            """
            class Sample
            {
                void M(int[] items)
                {
                    foreach (var x in items)
                    {
                        if (NewMethod(x)) break;
                    }
                }

                private static bool NewMethod(int x)
                {
                    if (x < 0)
                    {
                        Console.WriteLine("negative");
                        return true;
                    }

                    Console.WriteLine(x);
                    return false;
                }
            }
            """.trimIndent(),
            extract("""
                class Sample
                {
                    void M(int[] items)
                    {
                        foreach (var x in items)
                        {
                            <selection>if (x < 0)
                            {
                                Console.WriteLine("negative");
                                break;
                            }

                            Console.WriteLine(x);</selection>
                        }
                    }
                }
            """),
        )
    }

    fun testAContinueClosingTheSelectionStaysAtTheCall() {
        val text = extract("""
            class Sample
            {
                void M(int[] items)
                {
                    foreach (var x in items)
                    {
                        if (x > 0)
                        {
                            <selection>Console.WriteLine(x);
                            continue;</selection>
                        }
                        Console.WriteLine(-x);
                    }
                }
            }
        """)
        assertTrue(text, text.contains("                NewMethod(x);\n                continue;\n"))
        assertTrue(text, text.contains("    private static void NewMethod(int x)\n    {\n        Console.WriteLine(x);\n    }"))
    }

    fun testAReturnOfAVoidMethod() {
        val text = extract("""
            class Sample
            {
                void M(string a)
                {
                    <selection>if (a == null) return;
                    Console.WriteLine(a);</selection>
                    Console.WriteLine("done");
                }
            }
        """)
        assertTrue(text, text.contains("        if (NewMethod(a)) return;\n        Console.WriteLine(\"done\");"))
        assertTrue(text, text.contains("    private static bool NewMethod(string a)\n    {\n        if (a == null) return true;\n        Console.WriteLine(a);\n        return false;\n    }"))
    }

    fun testABreakWithAVariableReadInTheLoopGoesByRef() {
        val text = extract("""
            class Sample
            {
                int M(int[] items)
                {
                    var sum = 0;
                    foreach (var x in items)
                    {
                        <selection>if (x < 0) break;
                        sum += x;</selection>
                    }
                    return sum;
                }
            }
        """)
        assertTrue(text, text.contains("            if (NewMethod(x, ref sum)) break;"))
        assertTrue(text, text.contains("private static bool NewMethod(int x, ref int sum)"))
    }

    fun testWhatLoopsAndJumpsRefuse() {
        val both = "class S\n{\n    void M(int[] a)\n    {\n        foreach (var x in a)\n        {\n            <selection>if (x < 0) break;\n            if (x == 0) continue;\n            Console.WriteLine(x);</selection>\n        }\n    }\n}"
        assertTrue(error(both).orEmpty(), error(both).orEmpty().contains("'break' and 'continue'"))
        val assigned = "class S\n{\n    int M(int[] a)\n    {\n        int last;\n        foreach (var x in a)\n        {\n            <selection>if (x < 0) break;\n            last = x;</selection>\n        }\n        return 0;\n    }\n}"
        assertNull("last is not used after: nothing to return", error(assigned))
        val used = "class S\n{\n    int M(int[] a)\n    {\n        int last = 0;\n        foreach (var x in a)\n        {\n            <selection>if (x < 0) break;\n            var y = x * 2;</selection>\n            last = y;\n        }\n        return last;\n    }\n}"
        assertNotNull(error(used))
        assertNotNull(error("class S\n{\n    int M(int a)\n    {\n        <selection>if (a > 0) return 1;</selection>\n        return 0;\n    }\n}"))
        assertNotNull("return of a lambda", error("class S\n{\n    void M()\n    {\n        System.Action f = () =>\n        {\n            <selection>if (true) return;\n            Console.WriteLine();</selection>\n        };\n    }\n}"))
    }

    // ---- introduce field

    private fun introduceField(body: String): String {
        configure(body)
        io.github.dotnetsupport.lang.NativeCSharpIntroduceField.perform(project, myFixture.editor, myFixture.file as CSharpFile)
        return myFixture.editor.document.text.removePrefix("using System;\nusing System.Threading.Tasks;\n\n").trimEnd()
    }

    fun testIntroduceFieldInitializedWhereDeclared() {
        assertEquals(
            """
            class Sample
            {
                private int _count;
                private readonly string _concat = string.Concat("a", "b");

                void M()
                {
                    Console.WriteLine(_concat);
                }
            }
            """.trimIndent(),
            introduceField("""
                class Sample
                {
                    private int _count;

                    void M()
                    {
                        Console.WriteLine(<selection>string.Concat("a", "b")</selection>);
                    }
                }
            """),
        )
    }

    fun testIntroduceFieldOfAnExpressionOfLocalsIsAssignedInTheMember() {
        val text = introduceField("class Sample\n{\n    void M(int a)\n    {\n        Console.WriteLine(<selection>a * 2</selection>);\n    }\n}")
        assertTrue(text, text.contains("class Sample\n{\n    private int _value;\n"))
        assertTrue(text, text.contains("        _value = a * 2;\n        Console.WriteLine(_value);"))
    }

    private companion object {
        fun bytes(name: String): ByteArray? = CSharpExtractMethodTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
