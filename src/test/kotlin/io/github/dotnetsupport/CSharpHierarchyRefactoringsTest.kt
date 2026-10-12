package io.github.dotnetsupport

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpHierarchyEdits
import io.github.dotnetsupport.lang.NativeCSharpPullMembersUpRow
import io.github.dotnetsupport.lang.NativeCSharpPushMembersDownRow
import io.github.dotnetsupport.lang.NativeCSharpUseBaseTypeRow
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession

/** Rider's Pull Members Up, Push Members Down and Use Base Type Where Possible in Refactor This (Jellyfin № 53, № 55). */
class CSharpHierarchyRefactoringsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
    }

    override fun tearDown() {
        try {
            NativeCSharpHierarchyEdits.answer = null
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun add(path: String, text: String): VirtualFile = myFixture.addFileToProject(path, text).virtualFile

    private fun open(file: VirtualFile, anchor: String) {
        myFixture.configureFromExistingVirtualFile(file)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf(anchor))
    }

    private fun run(row: IntentionAction, answer: NativeCSharpHierarchyEdits.Answer) {
        NativeCSharpHierarchyEdits.answer = answer
        assertTrue(row.text, row.isAvailable(project, myFixture.editor, myFixture.file))
        row.invoke(project, myFixture.editor, myFixture.file)
    }

    private fun text(file: VirtualFile): String = FileDocumentManager.getInstance().getDocument(file)!!.text

    fun testPullAMethodAndAPropertyUpWithTheirUsings() {
        val base = add("pull1/Animal.cs", "namespace Zoo.Core;\n\npublic class Animal\n{\n    public string Name { get; set; } = \"\";\n}\n")
        val dog = add("pull1/Dog.cs", "using System.Collections.Generic;\nusing Zoo.Core;\n\nnamespace Zoo;\n\npublic class Dog : Animal\n{\n" +
            "    public List<string> Tricks { get; } = new List<string>();\n\n    private int Double(int x) => x * 2;\n\n    public int Score() => Double(3);\n}\n")
        open(dog, "Double(int")
        run(NativeCSharpPullMembersUpRow(), NativeCSharpHierarchyEdits.Answer(target = "Animal", members = listOf("Tricks", "Double")))
        val animal = text(base)
        assertTrue(animal, animal.contains("using System.Collections.Generic;"))
        assertTrue(animal, animal.contains("public class Animal\n{\n    public string Name { get; set; } = \"\";\n\n" +
            "    public List<string> Tricks { get; } = new List<string>();\n\n    protected int Double(int x) => x * 2;\n}\n"))
        // the private method the rest of the class calls is protected now; the class keeps what stays
        assertEquals("using System.Collections.Generic;\nusing Zoo.Core;\n\nnamespace Zoo;\n\npublic class Dog : Animal\n{\n    public int Score() => Double(3);\n}\n", text(dog))
    }

    fun testAMemberThatUsesWhatStaysIsAConflict() {
        val file = add("pull2/Cat.cs", "namespace Zoo;\n\npublic class Pet { }\n\npublic class Cat : Pet\n{\n    private int _purrs;\n\n    public void Purr()\n    {\n        _purrs++;\n    }\n}\n")
        val before = text(file)
        open(file, "void Purr")
        val type = (myFixture.file as CSharpFile).let { NativeCSharpHierarchyEdits.siteAt(it, myFixture.caretOffset)!!.first }
        val session = CSharpSemanticSession(project)
        val base = NativeCSharpHierarchyEdits.bases(type, session).single()
        val plan = NativeCSharpHierarchyEdits.pullUp(type, base, NativeCSharpHierarchyEdits.pullCandidates(type).filter { NativeCSharpHierarchyEdits.nameOf(it) == "Purr" }, emptySet(), session)
        assertEquals(listOf("'Purr' uses '_purrs', which stays in 'Cat'"), plan.conflicts)
        // refused at the conflicts: nothing changes
        run(NativeCSharpPullMembersUpRow(), NativeCSharpHierarchyEdits.Answer(target = "Pet", members = listOf("Purr"), confirm = false))
        assertEquals(before, text(file))
    }

    fun testPullAsAnAbstractDeclaration() {
        val file = add("pull3/Shapes.cs", "namespace Geo;\n\npublic class Shape\n{\n}\n\npublic class Circle : Shape\n{\n    public virtual double Area() => 3.14;\n}\n")
        open(file, "Area()")
        run(NativeCSharpPullMembersUpRow(), NativeCSharpHierarchyEdits.Answer(target = "Shape", members = listOf("Area"), abstract = setOf("Area")))
        assertEquals("namespace Geo;\n\npublic abstract class Shape\n{\n    public abstract double Area();\n}\n\npublic class Circle : Shape\n{\n    public override double Area() => 3.14;\n}\n",
            text(file))
    }

    fun testPullToAnInterfaceDeclaresTheMember() {
        val file = add("pull4/Jobs.cs", "namespace Work;\n\npublic interface IRunner\n{\n}\n\npublic class Job : IRunner\n{\n    public void Run(int times)\n    {\n    }\n\n    public string Name { get; set; }\n}\n")
        open(file, "class Job")
        run(NativeCSharpPullMembersUpRow(), NativeCSharpHierarchyEdits.Answer(target = "IRunner", members = listOf("Run", "Name")))
        assertEquals("namespace Work;\n\npublic interface IRunner\n{\n    void Run(int times);\n\n    string Name { get; set; }\n}\n\n" +
            "public class Job : IRunner\n{\n    public void Run(int times)\n    {\n    }\n\n    public string Name { get; set; }\n}\n", text(file))
    }

    fun testPushAMemberDownIntoTheDerivedTypes() {
        val base = add("push1/Vehicle.cs", "namespace Roads;\n\npublic class Vehicle\n{\n    public int Wheels() => 4;\n\n    public void Start() { }\n}\n")
        val car = add("push1/Car.cs", "namespace Roads;\n\npublic class Car : Vehicle\n{\n    public string Model = \"\";\n}\n")
        val bike = add("push1/Bike.cs", "namespace Roads;\n\npublic class Bike : Vehicle\n{\n}\n")
        open(base, "Wheels()")
        run(NativeCSharpPushMembersDownRow(), NativeCSharpHierarchyEdits.Answer(members = listOf("Wheels")))
        assertEquals("namespace Roads;\n\npublic class Vehicle\n{\n    public void Start() { }\n}\n", text(base))
        assertEquals("namespace Roads;\n\npublic class Car : Vehicle\n{\n    public string Model = \"\";\n\n    public int Wheels() => 4;\n}\n", text(car))
        assertEquals("namespace Roads;\n\npublic class Bike : Vehicle\n{\n    public int Wheels() => 4;\n}\n", text(bike))
    }

    fun testPushingAMemberTheBaseUsesIsAConflict() {
        val base = add("push2/Engine.cs", "namespace Motors;\n\npublic class Engine\n{\n    public void Start() { }\n\n    public void Go() => Start();\n}\n\npublic class Diesel : Engine\n{\n}\n")
        open(base, "void Start")
        val type = NativeCSharpHierarchyEdits.siteAt(myFixture.file as CSharpFile, myFixture.caretOffset)!!.first
        val session = CSharpSemanticSession(project)
        val derived = NativeCSharpHierarchyEdits.derivedTypes(type, project, session)
        assertEquals(listOf("Diesel"), derived.map { it.identifier?.text })
        val plan = NativeCSharpHierarchyEdits.pushDown(type, derived, NativeCSharpHierarchyEdits.pushCandidates(type).filter { NativeCSharpHierarchyEdits.nameOf(it) == "Start" }, emptySet(), session)
        assertEquals(listOf("'Engine' uses 'Start', which goes to the derived types"), plan.conflicts)
    }

    fun testPushDownKeepingAnAbstractDeclaration() {
        val file = add("push3/Payments.cs", "namespace Pay;\n\npublic class Payment\n{\n    public virtual decimal Fee() => 1m;\n}\n\npublic class Card : Payment\n{\n}\n")
        open(file, "Fee()")
        run(NativeCSharpPushMembersDownRow(), NativeCSharpHierarchyEdits.Answer(members = listOf("Fee"), abstract = setOf("Fee")))
        assertEquals("namespace Pay;\n\npublic abstract class Payment\n{\n    public abstract decimal Fee();\n}\n\npublic class Card : Payment\n{\n    public override decimal Fee() => 1m;\n}\n",
            text(file))
    }

    fun testUseBaseTypeWhereOnlyItsMembersAreUsed() {
        val file = add("base1/Report.cs", "namespace Geo;\n\npublic interface IShape\n{\n    double Area();\n}\n\npublic class Square : IShape\n{\n" +
            "    public double Area() => 1;\n    public double Side => 1;\n}\n\npublic class Report\n{\n" +
            "    public double Total(Square s) => s.Area();\n    public double Edge(Square s) => s.Side;\n    public bool Missing(Square s) => s == null;\n}\n")
        open(file, "class Square")
        run(NativeCSharpUseBaseTypeRow(), NativeCSharpHierarchyEdits.Answer(target = "IShape"))
        val result = text(file)
        assertTrue(result, result.contains("public double Total(IShape s) => s.Area();"))
        assertTrue(result, result.contains("public bool Missing(IShape s) => s == null;"))
        // `Side` is not a member of IShape: that parameter keeps its type
        assertTrue(result, result.contains("public double Edge(Square s) => s.Side;"))
    }

    fun testWhereTheRowsAreOffered() {
        val file = add("rows/Things.cs", "namespace Things;\n\npublic class Thing\n{\n    public void Use() { }\n}\n\npublic sealed class Box : Thing\n{\n    public void Open()\n    {\n        Use();\n    }\n}\n")
        open(file, "class Thing")
        assertFalse("no base list", NativeCSharpPullMembersUpRow().isAvailable(project, myFixture.editor, myFixture.file))
        assertTrue(NativeCSharpPushMembersDownRow().isAvailable(project, myFixture.editor, myFixture.file))
        open(file, "class Box")
        assertTrue(NativeCSharpPullMembersUpRow().isAvailable(project, myFixture.editor, myFixture.file))
        assertFalse("sealed", NativeCSharpPushMembersDownRow().isAvailable(project, myFixture.editor, myFixture.file))
        assertTrue(NativeCSharpUseBaseTypeRow().isAvailable(project, myFixture.editor, myFixture.file))
        open(file, "Use();")
        assertFalse("in a body", NativeCSharpPullMembersUpRow().isAvailable(project, myFixture.editor, myFixture.file))
    }

    companion object {
        private fun bytes(name: String): ByteArray? = CSharpHierarchyRefactoringsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
