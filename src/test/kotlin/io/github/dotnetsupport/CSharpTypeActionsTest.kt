package io.github.dotnetsupport

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCoverTestIntention
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetRunConfiguration

/**
 * Rider's actions on members and types of 0.1.173 (`NativeCSharpTypeActions.kt`): "Add exception documentation", "Import static members",
 * "Inline Method" at a call and at all calls, "Create derived type", "Copy type", "Cover" on a test; where each is not offered.
 */
class CSharpTypeActionsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
    }

    override fun tearDown() {
        try {
            NativeCSharpCoverTestIntention.runForTests = null
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** [text] with `<caret>`, a namespace of its own (the light project keeps the files of the tests before). */
    private fun configure(text: String): String {
        val namespace = "TypeActions${counter++}"
        myFixture.configureByText("$namespace.cs", text.trimIndent().replace("__NS__", namespace))
        return namespace
    }

    private fun apply(text: String, title: String): String {
        val namespace = configure(text)
        myFixture.launchAction(myFixture.findSingleIntention(title))
        return myFixture.editor.document.text.replace(namespace, "__NS__")
    }

    private fun available(text: String, title: String): Boolean {
        configure(text)
        return myFixture.filterAvailableIntentions(title).any { it.text == title }
    }

    // ---- Add exception documentation

    fun testExceptionDocumentationAfterTheSummary() {
        val result = apply("""
            using System;
            namespace __NS__;
            class Orders
            {
                /// <summary>Saves.</summary>
                public void Save(string name)
                {
                    if (name == null) th<caret>row new ArgumentNullException(nameof(name));
                }
            }
        """, "Add exception documentation")
        assertTrue(result, result.contains("    /// <summary>Saves.</summary>\n    /// <exception cref=\"ArgumentNullException\"></exception>\n    public void Save"))
    }

    fun testExceptionDocumentationWithoutADocCommentAndOncePerType() {
        val result = apply("""
            using System;
            namespace __NS__;
            class Orders
            {
                [Obsolete]
                public string Count(string name) => name ?? thr<caret>ow new InvalidOperationException("none");
            }
        """, "Add exception documentation")
        assertTrue(result, result.contains("    /// <exception cref=\"InvalidOperationException\"></exception>\n    [Obsolete]\n    public string Count"))
        assertFalse(available("""
            using System;
            namespace __NS__;
            class Orders
            {
                /// <exception cref="ArgumentNullException">When null.</exception>
                public void Save(string name) { if (name == null) th<caret>row new ArgumentNullException(nameof(name)); }
            }
        """, "Add exception documentation"))
        // thrown out of a lambda: not the member's exception
        assertFalse(available("""
            using System;
            namespace __NS__;
            class Orders
            {
                public void Save() { Action a = () => th<caret>row new InvalidOperationException(); }
            }
        """, "Add exception documentation"))
    }

    // ---- Import static members

    fun testImportStaticMembersOfAType() {
        val result = apply("""
            using System;
            namespace __NS__;
            class Shapes
            {
                int M(int a, int b) => Math.Ma<caret>x(a, b) + Math.Min(a, b) + Convert.ToInt32("1");
            }
        """, "Import static members")
        assertTrue(result, result.contains("using static System.Math;"))
        assertTrue(result, result.contains("int M(int a, int b) => Max(a, b) + Min(a, b) + Convert.ToInt32(\"1\");"))
    }

    fun testImportStaticMembersNotWhereANameWouldClash() {
        assertFalse(available("""
            using System;
            namespace __NS__;
            class Shapes
            {
                int Max(int a) => a;
                int M(int a, int b) => Math.Ma<caret>x(a, b);
            }
        """, "Import static members"))
        // an instance member: no static import
        assertFalse(available("""
            namespace __NS__;
            class Shapes
            {
                int M(string s) => s.Leng<caret>th;
            }
        """, "Import static members"))
    }

    // ---- Inline Method

    fun testInlineMethodAtACall() {
        val result = apply("""
            namespace __NS__;
            class Prices
            {
                int Total(int price, int count) => Mul<caret>tiply(price + 1, count) * 2;
                private int Multiply(int a, int b) => a * b;
            }
        """, "Inline Method")
        assertTrue(result, result.contains("int Total(int price, int count) => ((price + 1) * count) * 2;"))
        assertTrue(result, result.contains("private int Multiply(int a, int b) => a * b;"))
    }

    fun testInlineMethodOfStatements() {
        val result = apply("""
            using System;
            namespace __NS__;
            class Log
            {
                void Run(string name)
                {
                    Wr<caret>ite(name);
                }
                private void Write(string text)
                {
                    Console.WriteLine(text);
                    Console.WriteLine(text.Length);
                }
            }
        """, "Inline Method")
        assertTrue(result, result.contains("    void Run(string name)\n    {\n        Console.WriteLine(name);\n        Console.WriteLine(name.Length);\n    }"))
    }

    fun testInlineMethodNotWithAnArgumentThatDoesSomethingTwice() {
        assertFalse(available("""
            namespace __NS__;
            class Prices
            {
                int Next() => 1;
                int Total() => Squ<caret>are(Next());
                private int Square(int a) => a * a;
            }
        """, "Inline Method"))
    }

    fun testInlineMethodAtAllCallsRemovesIt() {
        val result = apply("""
            namespace __NS__;
            class Prices
            {
                int A(int x) => Twice(x);
                int B(int y) => Twice(y) + 1;

                /// <summary>Doubles.</summary>
                private int Twi<caret>ce(int v) => v * 2;
            }
        """, "Inline Method")
        assertTrue(result, result.contains("int A(int x) => x * 2;"))
        assertTrue(result, result.contains("int B(int y) => (y * 2) + 1;"))
        assertFalse(result, result.contains("Twice") || result.contains("Doubles"))
    }

    // ---- Create derived type, Copy type

    fun testCreateDerivedTypeOfAnInterface() {
        val result = apply("""
            namespace __NS__;
            public interface IGre<caret>eter
            {
                string Greet(string name);
            }
        """, "Create derived type")
        assertTrue(result, result.contains("public class Greeter : IGreeter\n{"))
        assertTrue(result, result.contains("public string Greet(string name)"))
    }

    fun testCreateDerivedTypeOfAClassAndWhereNot() {
        val result = apply("""
            namespace __NS__;
            public abstract class Sha<caret>pe
            {
                public abstract double Area();
            }
        """, "Create derived type")
        assertTrue(result, result.contains("public class ShapeImpl : Shape"))
        assertTrue(result, result.contains("public override double Area()"))
        assertFalse(available("namespace __NS__;\npublic sealed class Do<caret>ne { }\n", "Create derived type"))
        assertFalse(available("namespace __NS__;\npublic class Need<caret>s { public Needs(int x) { } }\n", "Create derived type"))
        assertFalse(available("namespace __NS__;\npublic class Body { void M() { int x<caret> = 1; } }\n", "Create derived type"))
    }

    fun testCopyTypeToANewFile() {
        val namespace = configure("""
            using System;

            namespace __NS__;

            /// <summary>An order.</summary>
            public class Ord<caret>er
            {
                public Order() { }
                public Order Clone() => new Order();
            }
        """)
        myFixture.launchAction(myFixture.findSingleIntention("Copy type"))
        val copy = myFixture.file.containingDirectory.findFile("Order1.cs")
        assertNotNull(copy)
        val text = copy!!.text
        assertTrue(text, text.startsWith("using System;\n\nnamespace $namespace;\n\n/// <summary>An order.</summary>\npublic class Order1\n{"))
        assertTrue(text, text.contains("public Order1() { }") && text.contains("public Order1 Clone() => new Order1();"))
    }

    // ---- Cover

    fun testCoverATestMethod() {
        myFixture.addFileToProject("CoverTests/CoverTests.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><ItemGroup><PackageReference Include=\"xunit\" Version=\"2.9.0\" /></ItemGroup></Project>")
        val file = myFixture.addFileToProject("CoverTests/OrderTests.cs", """
            using Xunit;
            namespace CoverTests;
            public class OrderTests
            {
                [Fact]
                public void Ad<caret>ds()
                {
                    var x = 1;
                }
            }
        """.trimIndent().replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Adds") + 2)
        var ran: RunnerAndConfigurationSettings? = null
        NativeCSharpCoverTestIntention.runForTests = { ran = it }
        myFixture.launchAction(myFixture.findSingleIntention("Cover"))
        val options = (ran!!.configuration as DotNetRunConfiguration).options
        assertEquals(DotNetCommand.TEST, options.command)
        assertTrue(options.collectCoverage)
        assertTrue(options.testFilter.orEmpty(), options.testFilter.orEmpty().contains("OrderTests.Adds"))
        // not in the body of the test
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("var x"))
        assertTrue(myFixture.filterAvailableIntentions("Cover").none { it.text == "Cover" })
    }

    companion object {
        private var counter = 0
        private fun bytes(name: String): ByteArray? = CSharpTypeActionsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
