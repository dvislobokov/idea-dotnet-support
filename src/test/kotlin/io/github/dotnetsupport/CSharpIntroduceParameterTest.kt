package io.github.dotnetsupport

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpIntroduceParameter
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * Introduce Parameter of the native tree (0.1.81): the expression becomes a parameter, the calls of the solution pass it (with the
 * arguments put in place of the parameters it reads), or it stays as the default value; what is refused and why.
 */
class CSharpIntroduceParameterTest : BasePlatformTestCase() {
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
    }

    override fun tearDown() {
        try {
            NativeCSharpIntroduceParameter.setOptionalForTests(null)
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val usings = "using System;\n\n"

    /** [body] in a file of its own (unique type names per test: the light project is shared), introduced at the selection. */
    private fun introduce(body: String): String {
        myFixture.configureByText("IntroduceParameter${files++}.cs", usings + body.trimIndent() + "\n")
        NativeCSharpIntroduceParameter.perform(project, myFixture.editor, myFixture.file as CSharpFile)
        return myFixture.editor.document.text.removePrefix(usings).trimEnd()
    }

    private fun error(body: String): String? {
        myFixture.configureByText("IntroduceParameter${files++}.cs", usings + body.trimIndent() + "\n")
        val selection = myFixture.editor.selectionModel
        val file = myFixture.file as CSharpFile
        val range = if (selection.hasSelection()) TextRange(selection.selectionStart, selection.selectionEnd) else null
        return when (val plan = NativeCSharpIntroduceParameter.analyze(file, range, myFixture.editor.caretModel.offset)) {
            is NativeCSharpIntroduceParameter.Result.Error -> plan.message
            is NativeCSharpIntroduceParameter.Result.Ok -> (NativeCSharpIntroduceParameter.edits(project, plan.value) as? NativeCSharpIntroduceParameter.Result.Error)?.message
        }
    }

    fun testAConstantBecomesAParameterAndEveryCallPassesIt() {
        val caller = myFixture.addFileToProject("ParamCaller1.cs", "class ParamCaller1\n{\n    int Get(Plan1 p) => p.Area(5);\n}\n")
        val text = introduce("""
            class Plan1
            {
                public int Area(int width)
                {
                    return width * <selection>10</selection>;
                }

                void Use()
                {
                    Console.WriteLine(Area(3));
                }
            }
        """)
        val name = Regex("""public int Area\(int width, int (\w+)\)""").find(text)?.groupValues?.get(1)
        assertNotNull(text, name)
        assertEquals(
            """
            class Plan1
            {
                public int Area(int width, int $name)
                {
                    return width * $name;
                }

                void Use()
                {
                    Console.WriteLine(Area(3, 10));
                }
            }
            """.trimIndent(),
            text,
        )
        assertEquals("class ParamCaller1\n{\n    int Get(Plan1 p) => p.Area(5, 10);\n}\n", caller.text)
    }

    fun testTheParametersTheExpressionReadsAreTheArgumentsOfEachCall() {
        val text = introduce("""
            class Plan2
            {
                int Twice(int width)
                {
                    return <selection>width * 2</selection> + 1;
                }

                void Use(int a, int b)
                {
                    Twice(3);
                    Twice(a + b);
                    Twice(width: a);
                }
            }
        """)
        val name = Regex("""int Twice\(int width, int (\w+)\)""").find(text)?.groupValues?.get(1)
        assertNotNull(text, name)
        assertTrue(text, text.contains("return $name + 1;"))
        assertTrue(text, text.contains("Twice(3, 3 * 2);"))
        assertTrue(text, text.contains("Twice(a + b, (a + b) * 2);"))
        assertTrue(text, text.contains("Twice(width: a, $name: a * 2);"))
    }

    fun testAConstantCanStayAsTheDefaultValue() {
        NativeCSharpIntroduceParameter.setOptionalForTests(true)
        val text = introduce("""
            class Plan3
            {
                string Greet(string who)
                {
                    return <selection>"Hello, "</selection> + who;
                }

                void Use() => Greet("me");
            }
        """)
        assertTrue(text, Regex("""string Greet\(string who, string \w+ = "Hello, "\)""").containsMatchIn(text))
        assertTrue(text, text.contains("void Use() => Greet(\"me\");"))
    }

    fun testTheNewParameterGoesBeforeTheOptionalOnes() {
        val text = introduce("""
            class Plan4
            {
                void Log(string text, int level = 1)
                {
                    Console.WriteLine(text + <selection>42</selection> + level);
                }

                void Use()
                {
                    Log("a");
                    Log("b", 2);
                    Log("c", level: 3);
                }
            }
        """)
        val name = Regex("""void Log\(string text, int (\w+), int level = 1\)""").find(text)?.groupValues?.get(1)
        assertNotNull(text, name)
        assertTrue(text, text.contains("Log(\"a\", 42);"))
        assertTrue(text, text.contains("Log(\"b\", 42, 2);"))
        assertTrue(text, text.contains("Log(\"c\", level: 3, $name: 42);"))
    }

    fun testAConstructorAndItsCalls() {
        val text = introduce("""
            class Plan5
            {
                private readonly int _size;

                public Plan5()
                {
                    _size = <selection>16</selection>;
                }

                public Plan5(string name) : this()
                {
                }

                static Plan5 Make() => new Plan5();
            }
        """)
        assertTrue(text, Regex("""public Plan5\(int \w+\)""").containsMatchIn(text))
        assertTrue(text, text.contains(": this(16)"))
        assertTrue(text, text.contains("new Plan5(16)"))
    }

    fun testWhatIsRefused() {
        assertNotNull("a local", error("class Plan6\n{\n    int M()\n    {\n        var a = 1;\n        return <selection>a + 1</selection>;\n    }\n}"))
        myFixture.addFileToProject("ParamCaller7.cs", "class ParamCaller7\n{\n    int Get(Plan7 p) => p.M();\n}\n")
        assertNotNull("an instance member and a call on another object",
            error("class Plan7\n{\n    int _n;\n\n    public int M()\n    {\n        return <selection>_n + 1</selection>;\n    }\n}"))
        assertNotNull("a method group", error("class Plan8\n{\n    int M()\n    {\n        return <selection>1</selection>;\n    }\n\n    System.Func<int> F() => M;\n}"))
        assertNotNull("an override", error("class Plan9\n{\n    public override string ToString()\n    {\n        return <selection>\"x\"</selection>;\n    }\n}"))
        assertNull("this. members are fine for calls without a receiver",
            error("class Plan10\n{\n    int _n;\n\n    int M()\n    {\n        return <selection>_n + 1</selection>;\n    }\n\n    int N() => M() + this.M();\n}"))
    }

    private companion object {
        fun bytes(name: String): ByteArray? = CSharpIntroduceParameterTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
