package io.github.dotnetsupport

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSignatureParameter
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpChangeSignature
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Change Signature without the dialog (Jellyfin № 25 / 51, 0.1.174): the model of the new signature → the edits of the declaration, its
 * hierarchy, the bodies and every call of the solution; what cannot be updated is a conflict.
 */
class CSharpChangeSignatureTest : BasePlatformTestCase() {
    private var round = 0
    private lateinit var dir: String

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { null }
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.CONTEXT_ACTIONS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** [files] (name → text with `Ns` for a namespace of this round) in a folder of their own. */
    private fun files(vararg files: Pair<String, String>): Map<String, CSharpFile> {
        dir = "sig${round++}"
        val ns = "Sig${round}"
        return files.associate { (name, text) -> name to myFixture.addFileToProject("$dir/$name", text.trimIndent().replace("Ns", ns)) as CSharpFile }
    }

    private fun target(file: CSharpFile, at: String): PsiElement {
        val offset = file.text.indexOf(at)
        assertTrue("no '$at'", offset >= 0)
        return ReadAction.compute<PsiElement?, RuntimeException> { NativeCSharpChangeSignature.targetAt(file, offset) } ?: error("no target at '$at'")
    }

    private fun change(declaration: PsiElement, name: String? = null, returnType: String? = null, edit: (List<CSharpSignatureParameter>) -> List<CSharpSignatureParameter>): NativeCSharpChangeSignature.Plan {
        val current = NativeCSharpChangeSignature.current(declaration)
        val signature = io.github.dotnetsupport.lang.CSharpNewSignature(name ?: current.name, returnType ?: current.returnType, edit(current.parameters))
        assertNull(NativeCSharpChangeSignature.validate(declaration, signature))
        return ReadAction.compute<NativeCSharpChangeSignature.Plan, RuntimeException> { NativeCSharpChangeSignature.plan(project, declaration, signature) }
    }

    private fun applied(plan: NativeCSharpChangeSignature.Plan, vararg files: CSharpFile): List<String> {
        assertTrue(plan.conflicts.toString(), plan.conflicts.isEmpty())
        assertTrue(NativeCSharpChangeSignature.apply(project, plan))
        return files.map { PsiDocumentManager.getInstance(project).getDocument(it)!!.text }
    }

    private fun p(name: String, type: String, oldIndex: Int = -1, defaultValue: String? = null, callValue: String = "", modifier: String = "") =
        CSharpSignatureParameter(name, type, modifier, defaultValue, oldIndex, callValue)

    fun testReorderAcrossTheSolution() {
        val f = files(
            "Service.cs" to """
                namespace Ns;
                public class Service
                {
                    public void Save(string name, int count) { System.Console.WriteLine(name + count); }
                }
            """,
            "Client.cs" to """
                namespace Ns;
                public class Client
                {
                    public void Run(Service s) { s.Save("a", 1); s.Save(count: 2, name: "b"); }
                }
            """,
        )
        val plan = change(target(f.getValue("Service.cs"), "Save")) { ps -> listOf(ps[1], ps[0]) }
        val (service, client) = applied(plan, f.getValue("Service.cs"), f.getValue("Client.cs"))
        assertTrue(service, service.contains("public void Save(int count, string name)"))
        assertTrue(client, client.contains("""s.Save(1, "a");"""))
        assertTrue(client, client.contains("""s.Save(count: 2, name: "b");"""))
    }

    fun testAddWithAValueAndWithADefault() {
        val f = files("A.cs" to """
            namespace Ns;
            public class A
            {
                public int Sum(int a) => a;
                public void M() { var x = Sum(1); var y = Sum(a: 2); }
            }
        """)
        val plan = change(target(f.getValue("A.cs"), "Sum(int")) { ps -> ps + p("b", "int", callValue = "10") + p("c", "bool", defaultValue = "false") }
        val (text) = applied(plan, f.getValue("A.cs"))
        assertTrue(text, text.contains("public int Sum(int a, int b, bool c = false) => a;"))
        assertTrue(text, text.contains("var x = Sum(1, 10);"))
        assertTrue(text, text.contains("var y = Sum(a: 2, b: 10);"))
    }

    fun testRemoveAndRenameAParameter() {
        val f = files("A.cs" to """
            namespace Ns;
            public class A
            {
                public int Area(int width, int height, int unused) => width * height;
                public void M() { var x = Area(2, height: 3, unused: 4); }
            }
        """)
        val plan = change(target(f.getValue("A.cs"), "Area(int")) { ps -> listOf(ps[0], p("depth", "int", oldIndex = 1)) }
        val (text) = applied(plan, f.getValue("A.cs"))
        assertTrue(text, text.contains("public int Area(int width, int depth) => width * depth;"))
        assertTrue(text, text.contains("var x = Area(2, depth: 3);"))
    }

    fun testRenameAndReturnType() {
        val f = files("A.cs" to """
            namespace Ns;
            public class A
            {
                public int Count() => 1;
                public void M() { var x = Count(); }
            }
        """)
        val plan = change(target(f.getValue("A.cs"), "Count()"), name = "Total", returnType = "long") { it }
        val (text) = applied(plan, f.getValue("A.cs"))
        assertTrue(text, text.contains("public long Total() => 1;"))
        assertTrue(text, text.contains("var x = Total();"))
    }

    fun testOverridesAndImplementationsFollow() {
        val f = files(
            "IShape.cs" to """
                namespace Ns;
                public interface IShape { double Scale(double factor, bool round); }
            """,
            "Circle.cs" to """
                namespace Ns;
                public class Circle : IShape
                {
                    public virtual double Scale(double factor, bool round) => round ? 1 : factor;
                }
                public class Ring : Circle
                {
                    public override double Scale(double factor, bool round) => factor;
                }
            """,
            "Use.cs" to """
                namespace Ns;
                public class Use
                {
                    public void M(IShape s, Ring r) { s.Scale(2, true); r.Scale(3, false); }
                }
            """,
        )
        val plan = change(target(f.getValue("IShape.cs"), "Scale")) { ps -> listOf(ps[1], ps[0]) }
        val (shape, circle, use) = applied(plan, f.getValue("IShape.cs"), f.getValue("Circle.cs"), f.getValue("Use.cs"))
        assertTrue(shape, shape.contains("double Scale(bool round, double factor);"))
        assertTrue(circle, circle.contains("public virtual double Scale(bool round, double factor)"))
        assertTrue(circle, circle.contains("public override double Scale(bool round, double factor)"))
        assertTrue(use, use.contains("s.Scale(true, 2);") && use.contains("r.Scale(false, 3);"))
    }

    fun testConstructorAndLocalFunctionCalls() {
        val f = files("A.cs" to """
            namespace Ns;
            public class Point
            {
                public Point(int x, int y) { }
                public Point(int x) : this(x, 0) { }
                public static Point Make()
                {
                    int Twice(int v, int w) => v * 2;
                    Point p = new(1, 2);
                    return new Point(Twice(3, 4), 5);
                }
            }
        """)
        val file = f.getValue("A.cs")
        var plan = change(target(file, "Point(int x, int y)")) { ps -> listOf(ps[1], ps[0]) }
        var (text) = applied(plan, file)
        assertTrue(text, text.contains("public Point(int y, int x)"))
        assertTrue(text, text.contains(": this(0, x)"))
        assertTrue(text, text.contains("Point p = new(2, 1);"))
        assertTrue(text, text.contains("return new Point(5, Twice(3, 4));"))
        plan = change(target(file, "Twice(int")) { ps -> listOf(ps[0]) }
        text = applied(plan, file).single()
        assertTrue(text, text.contains("int Twice(int v)") && text.contains("new Point(5, Twice(3))"))
    }

    fun testAMethodGroupAndNameofAreNotCallsAndAConflictIsReported() {
        val f = files("A.cs" to """
            namespace Ns;
            public class A
            {
                public int Pick(int a, int b) => b;
                public void M()
                {
                    System.Func<int, int, int> f = Pick;
                    var n = nameof(Pick);
                    var x = Pick(1, 2, 3);
                }
            }
        """)
        val plan = change(target(f.getValue("A.cs"), "Pick(int")) { ps -> listOf(ps[1], ps[0]) }
        assertTrue(plan.conflicts.toString(), plan.conflicts.any { it.contains("method group") })
        assertTrue(plan.conflicts.toString(), plan.conflicts.any { it.contains("more arguments") })
        val text = plan.edits.values.flatten().joinToString("|") { it.text }
        assertFalse(text, text.contains("nameof"))
    }

    fun testARemovedParameterUsedInTheBodyIsAConflict() {
        val f = files("A.cs" to """
            namespace Ns;
            public class A
            {
                public int Twice(int x, int y) => x * 2 + y;
            }
        """)
        val plan = change(target(f.getValue("A.cs"), "Twice")) { ps -> listOf(ps[0]) }
        assertTrue(plan.conflicts.toString(), plan.conflicts.any { it.contains("'y' is used") })
    }

    fun testAnExtensionMethodCalledOnItsReceiver() {
        val f = files("E.cs" to """
            namespace Ns;
            public class Num { }
            public static class Ext
            {
                public static int Add(this Num x, int a, int b) => a + b;
                public static void M(Num n) { var v = n.Add(2, 3); var w = Ext.Add(n, 2, 3); }
            }
        """)
        val plan = change(target(f.getValue("E.cs"), "Add(this")) { ps -> listOf(ps[0], ps[2], ps[1]) }
        val (text) = applied(plan, f.getValue("E.cs"))
        assertTrue(text, text.contains("Add(this Num x, int b, int a)"))
        assertTrue(text, text.contains("n.Add(3, 2)"))
        assertTrue(text, text.contains("Ext.Add(n, 3, 2)"))
    }

    fun testOfferedOnTheHeaderAndCtrlF6FindsTheMember() {
        myFixture.configureByText("SigOffer${round++}.cs", "namespace SigOffer;\npublic class A\n{\n    public void Sa<caret>ve(int a) { var x = a; }\n}\n")
        assertTrue(myFixture.availableIntentions.any { it.text == "Change signature…" })
        val handler = com.intellij.lang.LanguageRefactoringSupport.getInstance().forContext(myFixture.file)?.changeSignatureHandler
        assertNotNull(handler)
        assertTrue(handler!!.findTargetMember(myFixture.file.findElementAt(myFixture.caretOffset)!!) is io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration)
        myFixture.configureByText("SigBody${round++}.cs", "namespace SigBody;\npublic class A\n{\n    public void Save(int a) { var x<caret> = a; }\n}\n")
        assertFalse(myFixture.availableIntentions.any { it.text == "Change signature…" })
    }

    fun testValidation() {
        val f = files("A.cs" to """
            namespace Ns;
            public class A { public void M(int a, params int[] rest) { } }
        """)
        val declaration = target(f.getValue("A.cs"), "M(int")
        val current = NativeCSharpChangeSignature.current(declaration)
        val reversed = io.github.dotnetsupport.lang.CSharpNewSignature("M", "void", current.parameters.reversed())
        assertNotNull(NativeCSharpChangeSignature.validate(declaration, reversed))
        val twice = io.github.dotnetsupport.lang.CSharpNewSignature("M", "void", listOf(current.parameters[0], current.parameters[0]))
        assertNotNull(NativeCSharpChangeSignature.validate(declaration, twice))
    }
}
