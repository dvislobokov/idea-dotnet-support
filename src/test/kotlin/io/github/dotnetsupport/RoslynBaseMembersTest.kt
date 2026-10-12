package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpDeclarations
import io.github.dotnetsupport.lang.DeclarationKind
import io.github.dotnetsupport.roslyn.HierarchyItem
import io.github.dotnetsupport.roslyn.RoslynBaseMembers
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import java.io.File

/** Go to Base of a member: the supertypes of the server (captured traffic of server 5.12) and the members the scanner finds in their files. */
class RoslynBaseMembersTest : BasePlatformTestCase() {
    private val gson = MessageJsonHandler(emptyMap()).gson
    private val capture = File("src/test/resources/roslyn/capture-5.12")

    /** The scenario of `tools/roslyn-lsp/capture.py` on the lines where the captured `Scenarios.cs` had it (243-246). */
    private val scenario = "namespace Playground;\n" + "\n".repeat(242) + """
        public interface ICaptureShape { double Area(); }
        public abstract class CaptureShapeBase : ICaptureShape { public abstract double Area(); }
        public class CaptureSquare(double side) : CaptureShapeBase { public override double Area() => side * side; }
        public class CaptureCircle(double radius) : CaptureShapeBase { public override double Area() => 3.14 * radius * radius; }
    """.trimIndent().lines().joinToString("\n") { it.trimStart() }

    private fun items(number: String): List<HierarchyItem> =
        JsonParser.parseString(capture.listFiles()!!.first { it.name.startsWith("$number-") }.readText()).asJsonObject["result"].asJsonArray
            .map { HierarchyItem.of(gson.fromJson(it, TypeHierarchyItem::class.java)) }

    private fun offsetOf(text: String, position: Position): Int = text.lineSequence().take(position.line).sumOf { it.length + 1 } + position.character

    fun testAnOverrideGoesToTheNearestBaseClassAndAnAbstractMemberToTheInterface() {
        val structure = CSharpDeclarations.scan(scenario)
        val caret = RoslynBaseMembers.memberAt(structure, scenario.indexOf("Area() => side"))!!
        assertEquals("CaptureSquare", caret.type.name)
        assertEquals("Area", caret.member.name)
        assertFalse(caret.inBody)

        // CaptureSquare → CaptureShapeBase (42, a direct base) → ICaptureShape (43, its supertype)
        val base = items("42").single()
        val iface = items("43").single()
        fun found(item: HierarchyItem) = RoslynBaseMembers.typeIn(structure, item.name, offsetOf(scenario, item.selectionRange.start))!!.let { RoslynBaseMembers.matches(caret.member, it.children) }
        val bases = listOf(RoslynBaseMembers.Base(iface, 2, iface.isInterface, found(iface)), RoslynBaseMembers.Base(base, 1, base.isInterface, found(base)))
        assertEquals(listOf(1, 1), bases.map { it.found!!.size })

        val choice = RoslynBaseMembers.choose(caret.member, bases)
        assertEquals(listOf("CaptureShapeBase"), choice.members.map { it.first.name })
        assertEquals("Area(): double", choice.members.single().second.presentation)
        assertEquals(scenario.indexOf("Area(); }\npublic class CaptureSquare"), choice.members.single().second.nameRange.startOffset)

        // the abstract member of CaptureShapeBase implements the interface one
        val abstract = RoslynBaseMembers.memberAt(structure, scenario.indexOf("abstract double Area") + 1)!!
        assertEquals("CaptureShapeBase", abstract.type.name)
        val up = RoslynBaseMembers.choose(abstract.member, listOf(RoslynBaseMembers.Base(iface, 1, true, found(iface))))
        assertEquals(listOf("ICaptureShape"), up.members.map { it.first.name })
        assertTrue(up.types.isEmpty())
    }

    fun testANewMemberGoesToTheBaseClassAndToAllInterfaces() {
        val text = """
            interface IA { void Run(int a); }
            interface IB : IA { new void Run(int a); }
            class Base { public virtual void Run(int a) { } }
            class Middle : Base { }
            class Impl : Middle, IB { public new void Run(int a) { } }
        """.trimIndent()
        val structure = CSharpDeclarations.scan(text)
        val member = RoslynBaseMembers.memberAt(structure, text.indexOf("new void Run(int a) { }"))!!.member
        fun base(name: String, distance: Int, isInterface: Boolean) =
            RoslynBaseMembers.Base(name, distance, isInterface, RoslynBaseMembers.matches(member, RoslynBaseMembers.typeIn(structure, name, null)!!.children))
        val choice = RoslynBaseMembers.choose(member, listOf(base("IA", 2, true), base("Middle", 1, false), base("Base", 2, false), base("IB", 1, true)))
        assertEquals(listOf("Base", "IB", "IA"), choice.members.map { it.first })
    }

    fun testOverloadsAreToldApartByTheNumberOfParameters() {
        val text = """
            class Base {
                public virtual void Save(string path) { }
                public virtual void Save(string path, Dictionary<string, int> options, string separator = ",") { }
                public virtual int this[int index] => 0;
                public virtual int this[int row, int column] => 0;
                public virtual string Name { get; set; }
                public event EventHandler Changed;
                public string Changed2;
            }
            class Derived : Base {
                public override void Save(string path, Dictionary<string, int> options, string separator = ",") { }
                public override int this[int row, int column] => 1;
                public override string Name { get => ""; set { } }
                public new event EventHandler Changed;
            }
        """.trimIndent()
        val structure = CSharpDeclarations.scan(text)
        val candidates = RoslynBaseMembers.typeIn(structure, "Base", null)!!.children
        fun baseOf(snippet: String) = RoslynBaseMembers.matches(RoslynBaseMembers.memberAt(structure, text.lastIndexOf(snippet) + 1)!!.member, candidates)

        assertEquals(listOf("(string path, Dictionary<string, int> options, string separator = \",\")"), baseOf("void Save").map { it.parameters })
        assertEquals(listOf("[int row, int column]"), baseOf("int this[").map { it.parameters })
        assertEquals(listOf(DeclarationKind.PROPERTY), baseOf("string Name").map { it.kind })
        assertEquals(listOf(DeclarationKind.EVENT), baseOf("event EventHandler Changed").map { it.kind })
    }

    fun testTheCaretInABodyIsSeenAsSuch() {
        val text = "class A : B { public override void Run() { var x = 1; } }"
        val caret = RoslynBaseMembers.memberAt(CSharpDeclarations.scan(text), text.indexOf("var x"))!!
        assertTrue(caret.inBody)
        assertFalse(RoslynBaseMembers.memberAt(CSharpDeclarations.scan(text), text.indexOf("Run"))!!.inBody)
        // on the type itself it is Go to Base of the type
        assertNull(RoslynBaseMembers.memberAt(CSharpDeclarations.scan(text), text.indexOf("A :")))
    }

    fun testParameterCount() {
        assertEquals(0, RoslynBaseMembers.parameterCount("()"))
        assertEquals(0, RoslynBaseMembers.parameterCount(null))
        assertEquals(1, RoslynBaseMembers.parameterCount("[int i]"))
        assertEquals(2, RoslynBaseMembers.parameterCount("(Func<int, string> f, (int a, int b) pair)"))
        assertEquals(2, RoslynBaseMembers.parameterCount("(string s = \",\", char c = ',')"))
        assertEquals(3, RoslynBaseMembers.parameterCount("(int[,] grid, this string s, params object[] rest)"))
    }

    fun testSupertypesOutsideOfTheSourcesAreTheFallback() {
        val text = "class Shape : System.IDisposable { public void Dispose() { } }"
        val member = RoslynBaseMembers.memberAt(CSharpDeclarations.scan(text), text.indexOf("Dispose"))!!.member
        // IDisposable is metadata: no file to scan; a source base class without the member does not count
        val choice = RoslynBaseMembers.choose(member, listOf(RoslynBaseMembers.Base("Object", 2, false, null), RoslynBaseMembers.Base("IDisposable", 1, true, null),
            RoslynBaseMembers.Base("Local", 1, false, emptyList())))
        assertTrue(choice.members.isEmpty())
        assertEquals(listOf("IDisposable", "Object"), choice.types)
    }
}
