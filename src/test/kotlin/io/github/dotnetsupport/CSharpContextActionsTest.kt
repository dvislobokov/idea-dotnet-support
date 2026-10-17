package io.github.dotnetsupport

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatures
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpIntroduceVariableIntention
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * The context actions of the native tree (CSHARP_PSI_MIGRATION.md, task A7): `if` ↔ `?:`, block ↔ expression body, `var` ↔ explicit
 * type (types of C2 over the fixture assemblies), introduce / inline variable; where each is not offered; the server's rows they stand for.
 */
class CSharpContextActionsTest : BasePlatformTestCase() {
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
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

    // ---- Split variable declaration and initialization (№ 27)

    fun testSplitDeclarationAndInitialization() {
        assertEquals(
            "void M()\n{\n    Dictionary<string, bool> incoming;\n    incoming = new Dictionary<string, bool>();\n}",
            apply("void M()\n{\n    var inco<caret>ming = new Dictionary<string, bool>();\n}", "Split variable declaration and initialization"),
        )
        assertEquals("void M()\n{\n    int count;\n    count = 1 + 2;\n}", apply("void M()\n{\n    int cou<caret>nt = 1 + 2;\n}", "Split variable declaration and initialization"))
        // const, an array initializer, an anonymous type, no value
        assertFalse(available("void M()\n{\n    const int c<caret>ount = 1;\n}", "Split variable declaration and initialization"))
        assertFalse(available("void M()\n{\n    int[] it<caret>ems = { 1, 2 };\n}", "Split variable declaration and initialization"))
        assertFalse(available("void M()\n{\n    var p<caret>oint = new { X = 1 };\n}", "Split variable declaration and initialization"))
        assertFalse(available("void M()\n{\n    int co<caret>unt;\n}", "Split variable declaration and initialization"))
    }

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
        // the condition must stay where it runs
        assertFalse(available("bool Check() => true;\nvoid M(bool flag)\n{\n    Console.WriteLine(flag || (Check() <caret>? true : false));\n}", "Convert '?:' to 'if' statement"))
        assertFalse(available("void M(bool flag)\n{\n    Func<int> f = () => flag <caret>? 1 : 2;\n}", "Convert '?:' to 'if' statement"))
    }

    fun testConditionalInAnArgumentSplitsTheStatement() {
        assertEquals(
            "void M(bool flag)\n{\n    if (flag)\n    {\n        Console.WriteLine(\"on\" + 1);\n    }\n    else\n    {\n        Console.WriteLine(\"on\" + 2);\n    }\n}",
            apply("void M(bool flag)\n{\n    Console.WriteLine(\"on\" + (flag <caret>? 1 : 2));\n}", "Convert '?:' to 'if' statement"),
        )
        assertEquals(
            "int Twice(int x) => x * 2;\nint M(bool flag)\n{\n    if (flag)\n    {\n        return Twice(1);\n    }\n    else\n    {\n        return Twice(2);\n    }\n}",
            apply("int Twice(int x) => x * 2;\nint M(bool flag)\n{\n    return Twice(flag <caret>? 1 : 2);\n}", "Convert '?:' to 'if' statement"),
        )
    }

    fun testConditionalInALocalsValueDeclaresTheLocalFirst() {
        assertEquals(
            "int Twice(int x) => x * 2;\nvoid M(bool flag)\n{\n    int value;\n    if (flag)\n    {\n        value = Twice(1);\n    }\n    else\n    {\n        value = Twice(2);\n    }\n    Console.WriteLine(value);\n}",
            apply("int Twice(int x) => x * 2;\nvoid M(bool flag)\n{\n    var value = Twice(flag <caret>? 1 : 2);\n    Console.WriteLine(value);\n}", "Convert '?:' to 'if' statement"),
        )
        assertEquals(
            "void M(bool flag)\n{\n    string s;\n    if (flag)\n    {\n        s = \"a\";\n    }\n    else\n    {\n        s = \"b\";\n    }\n}",
            apply("void M(bool flag)\n{\n    string s = flag <caret>? \"a\" : \"b\";\n}", "Convert '?:' to 'if' statement"),
        )
    }

    fun testIntroduceVariableForAllOccurrences() {
        NativeCSharpIntroduceVariableIntention.allOccurrencesForTests = true
        try {
            assertEquals(
                "void M(List<int> numbers)\n{\n    var count = numbers.Count;\n    Console.WriteLine(count * 2);\n    if (count > 3)\n    {\n        Console.WriteLine(count);\n    }\n}",
                apply("void M(List<int> numbers)\n{\n    Console.WriteLine(numbers.Count * 2);\n    if (numbers.Cou<caret>nt > 3)\n    {\n        Console.WriteLine(numbers.Count);\n    }\n}", "Introduce variable"),
            )
        } finally {
            NativeCSharpIntroduceVariableIntention.allOccurrencesForTests = false
        }
        // one occurrence only by default (the chooser's first row)
        assertEquals(
            "void M(List<int> numbers)\n{\n    Console.WriteLine(numbers.Count * 2);\n    var count = numbers.Count;\n    Console.WriteLine(count);\n}",
            apply("void M(List<int> numbers)\n{\n    Console.WriteLine(numbers.Count * 2);\n    Console.WriteLine(numbers.Cou<caret>nt);\n}", "Introduce variable"),
        )
    }

    /** Jellyfin № 63: the occurrences of the body, not of the caret's block alone — the variable goes before the first one, in the block that holds them all. */
    fun testIntroduceVariableForOccurrencesInOuterAndNestedBlocks() {
        NativeCSharpIntroduceVariableIntention.allOccurrencesForTests = true
        try {
            assertEquals("void M(List<int> numbers, bool flag)\n{\n    var count = numbers.Count;\n    Console.WriteLine(count);\n    if (flag)\n    {\n        Console.WriteLine(count + 1);\n    }\n}", apply("void M(List<int> numbers, bool flag)\n{\n    Console.WriteLine(numbers.Count);\n    if (flag)\n    {\n        Console.WriteLine(numbers.Cou<caret>nt + 1);\n    }\n}", "Introduce variable"))
        } finally {
            NativeCSharpIntroduceVariableIntention.allOccurrencesForTests = false
        }
    }

    /** An occurrence in a lambda runs at another time, and one after an assignment of what the expression reads is another value: not replaced. */
    fun testOccurrencesNotAcrossALambdaOrAnAssignment() {
        fun count(code: String): Int {
            myFixture.configureByText("Context${files++}.cs", wrap(code, USINGS))
            val file = myFixture.file as io.github.dotnetsupport.lang.CSharpFile
            val extraction = io.github.dotnetsupport.lang.NativeCSharpContextEdits.extractionAt(file, null, myFixture.editor.caretModel.offset,
                io.github.dotnetsupport.lang.semantic.CSharpSemanticSession(project).resolver(file))!!
            return io.github.dotnetsupport.lang.NativeCSharpContextEdits.occurrences(extraction).size
        }
        // the one in the lambda is left alone, the two others are replaced
        assertEquals(2, count("void M(List<int> numbers)\n{\n    Console.WriteLine(numbers.Cou<caret>nt);\n    Action a = () => Console.WriteLine(numbers.Count);\n    Console.WriteLine(numbers.Count + 1);\n}"))
        assertEquals(0, count("void M(List<int> numbers, List<int> other)\n{\n    Console.WriteLine(numbers.Cou<caret>nt);\n    numbers = other;\n    Console.WriteLine(numbers.Count);\n}"))
    }

    fun testOccurrencesOneVariableCannotServe() {
        myFixture.configureByText("Context${files++}.cs", wrap("void M(List<int> numbers)\n{\n    Console.WriteLine(numbers.Count);\n    {\n        var other = numbers;\n        Console.WriteLine(other.Cou<caret>nt);\n        Console.WriteLine(other.Count);\n    }\n}", USINGS))
        val file = myFixture.file as io.github.dotnetsupport.lang.CSharpFile
        val extraction = io.github.dotnetsupport.lang.NativeCSharpContextEdits.extractionAt(file, null, myFixture.editor.caretModel.offset,
            io.github.dotnetsupport.lang.semantic.CSharpSemanticSession(project).resolver(file))!!
        // the two in the nested block: `other` is declared there
        assertEquals(2, io.github.dotnetsupport.lang.NativeCSharpContextEdits.occurrences(extraction).size)
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
        // a value that does something, used twice: inlined it would do it twice (two objects, two calls) — Jellyfin № 19
        assertFalse("a creation used twice", available("object M()\n{\n    var <caret>args = new List<int>();\n    Console.WriteLine(args);\n    return args;\n}", "Inline variable"))
        assertFalse("a call used twice", available("int M(List<int> list)\n{\n    var <caret>n = list.IndexOf(1);\n    return n + n;\n}", "Inline variable"))
        // used once the meaning stays
        assertTrue("a creation used once", available("object M()\n{\n    var <caret>args = new List<int>();\n    return args;\n}", "Inline variable"))
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

    /** The places of the Jellyfin comparison where Rider offers Introduce Variable and the plugin did not (0.1.170). */
    fun testIntroduceVariableAtRidersPlaces() {
        // `await` of a task with no result: what it awaits
        assertEquals(
            "async System.Threading.Tasks.Task Publish() { }\nasync System.Threading.Tasks.Task M()\n{\n    var publish = Publish();\n    await publish;\n}",
            apply("async System.Threading.Tasks.Task Publish() { }\nasync System.Threading.Tasks.Task M()\n{\n    aw<caret>ait Publish();\n}", "Introduce variable"),
        )
        // the caret on the receiver: the member it reads
        assertEquals(
            "void M(List<int> numbers)\n{\n    var count = numbers.Count;\n    if (count > 0) { }\n}",
            apply("void M(List<int> numbers)\n{\n    if (num<caret>bers.Count > 0) { }\n}", "Introduce variable"),
        )
        // the caret on the type of a cast: the cast
        assertEquals(
            "class Folder { public int Size; }\nvoid M(object item)\n{\n    var folder = (Folder)item;\n    Console.WriteLine(folder.Size);\n}",
            apply("class Folder { public int Size; }\nvoid M(object item)\n{\n    Console.WriteLine(((Fol<caret>der)item).Size);\n}", "Introduce variable"),
        )
        // a switch expression of collection expressions: the type its place gives
        assertTrue(available("int[] M(bool b)\n{\n    int[] all;\n    all = b sw<caret>itch { true => [1], false => [2] };\n    return all;\n}", "Introduce variable"))
        // the expression body of a lambda: the lambda gets a block
        assertEquals(
            "int Get() => 1;\nvoid M()\n{\n    Func<int> f = () => {\n        var get = Get();\n        return get + 1;\n    };\n}",
            apply("int Get() => 1;\nvoid M()\n{\n    Func<int> f = () => Ge<caret>t() + 1;\n}", "Introduce variable"),
        )
    }

    fun testIntroduceVariableIsNotOffered() {
        assertFalse("evaluated only sometimes", available("bool Check() => true;\nbool M(bool a)\n{\n    return a && Che<caret>ck();\n}", "Introduce variable"))
        assertFalse("a loop's condition", available("bool Check() => true;\nvoid M()\n{\n    while (Che<caret>ck()) { }\n}", "Introduce variable"))
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
        // on `var` and on the type, as the server offers it (robot, E-78)
        assertEquals(
            "int M(string a)\n{\n    return a.Length + 2;\n}",
            apply("int M(string a)\n{\n    v<caret>ar length = a.Length;\n    return length + 2;\n}", "Inline variable"),
        )
        assertTrue("on the explicit type", available("int M(int a)\n{\n    in<caret>t sum = a + 1;\n    return sum + 2;\n}", "Inline variable"))
        assertFalse("two variables", available("int M(int a)\n{\n    in<caret>t x = a, y = 2;\n    return x + y;\n}", "Inline variable"))
    }

    // ---- the server

    private companion object {
        const val USINGS = "using System;\nusing System.Collections.Generic;\n\n"

        private fun bytes(name: String): ByteArray? = CSharpContextActionsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
