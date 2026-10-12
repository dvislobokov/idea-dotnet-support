package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpGenerateRunner
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * Alt+Enter «Implement missing members» / «Override members...» (0.1.85) on the header of a type, its base list and whitespace of its
 * body, not inside members; Code | Override Methods (Ctrl+O) and Implement Methods (Ctrl+I) on a base the build generates.
 */
class CSharpInheritedIntentionsTest : BasePlatformTestCase() {
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
    }

    override fun tearDown() {
        try {
            NativeCSharpGenerateRunner.setChooserForTests(null)
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun rows(text: String): List<String> {
        myFixture.configureByText("Intentions${files++}.cs", text.trimIndent() + "\n")
        return myFixture.availableIntentions.map { it.text }.filter { it == IMPLEMENT || it == OVERRIDE }
    }

    private val shapes = """
        using System;
        abstract class Shape
        {
            public abstract double Area();
            public virtual string Describe() => "";
        }
    """.trimIndent()

    fun testOnTheNameAndTheBaseListOfATypeThatMissesMembers() {
        assertEquals(listOf(IMPLEMENT, OVERRIDE), rows("$shapes\nclass Ci<caret>rcle : Shape, IDisposable\n{\n}"))
        assertEquals(listOf(IMPLEMENT, OVERRIDE), rows("$shapes\nclass Circle : Shape, IDispo<caret>sable\n{\n}"))
        assertEquals(listOf(IMPLEMENT, OVERRIDE), rows("$shapes\npublic sea<caret>led class Circle : Shape\n{\n}"))
    }

    fun testOnWhitespaceOfTheBody() {
        val circle = "$shapes\nclass Circle : Shape\n{\n    private int _r;<caret>\n\n    public void Grow() { _r++; }\n}"
        assertEquals("end of a member's line", listOf(IMPLEMENT, OVERRIDE), rows(circle))
        assertEquals("a blank line", listOf(IMPLEMENT, OVERRIDE), rows(circle.replace(";<caret>\n\n", ";\n<caret>\n")))
        assertEquals("not inside a member", emptyList<String>(), rows(circle.replace(";<caret>\n\n    public void Grow() { _r++; }", ";\n\n    public void Grow() { _r<caret>++; }")))
    }

    fun testOverrideMembersOnAPlainClassOnlyInItsBody() {
        assertEquals("no base class: not on the name", emptyList<String>(), rows("class Pl<caret>ain\n{\n    private int _x;\n}"))
        assertEquals("object's members in the body", listOf(OVERRIDE), rows("class Plain\n{\n    private int _x;\n    <caret>\n}"))
        assertEquals("all done: no rows", emptyList<String>(), rows("using System;\nclass Done : IDisposable\n{\n    public void Dispose() { }\n    <caret>\n}").filter { it == IMPLEMENT })
    }

    fun testImplementMissingMembersFromAltEnter() {
        myFixture.configureByText("Intentions${files++}.cs", "$shapes\nclass Circle : Shape, IDisposable\n{\n    <caret>\n}\n")
        myFixture.launchAction(myFixture.findSingleIntention(IMPLEMENT))
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("    public override double Area()\n    {\n        throw new NotImplementedException();\n    }"))
        assertTrue(text, text.contains("    public void Dispose()\n    {\n        throw new NotImplementedException();\n    }"))
        assertFalse("virtual is not missing: $text", text.contains("override string Describe"))
    }

    fun testCtrlOOverridesABaseTheBuildGenerates() {
        myFixture.addFileToProject("Grpc/Core.cs", "namespace Grpc.Core { public class ServerCallContext { } }")
        myFixture.addFileToProject("Grpc/Greet.cs", "namespace Playground.Grpc { public sealed partial class HelloRequest { } public sealed partial class HelloReply { } }")
        myFixture.addFileToProject(
            "Grpc/obj/Debug/net9.0/Protos/GreetGrpc.cs",
            "using grpc = global::Grpc.Core;\nnamespace Playground.Grpc {\n  public static partial class Greeter\n  {\n    public abstract partial class GreeterBase\n    {\n" +
                "      public virtual global::System.Threading.Tasks.Task<global::Playground.Grpc.HelloReply> SayHello(global::Playground.Grpc.HelloRequest request, grpc::ServerCallContext context)\n" +
                "      {\n        throw new global::System.NotImplementedException();\n      }\n    }\n  }\n}\n",
        )
        myFixture.configureByText("Greeter${files++}.cs", "using Grpc.Core;\nnamespace Playground.Grpc;\npublic sealed class GreeterService : Greeter.GreeterBase\n{\n    <caret>\n}\n")
        NativeCSharpGenerateRunner.setChooserForTests { choices -> choices.filter { it.text.startsWith("SayHello") } }
        myFixture.performEditorAction("OverrideMethods")
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("    public override Task<HelloReply> SayHello(HelloRequest request, ServerCallContext context)\n    {\n        return base.SayHello(request, context);\n    }"))
        assertTrue(text, text.startsWith("using System.Threading.Tasks;\n"))
        assertTrue(listOf(OVERRIDE).toString(), myFixture.availableIntentions.none { it.text == IMPLEMENT })
    }

    fun testCtrlIImplementsMissingMembers() {
        myFixture.configureByText("Intentions${files++}.cs", "$shapes\nclass Circle : Shape\n{\n    <caret>\n}\n")
        myFixture.performEditorAction("ImplementMethods")
        assertTrue(myFixture.editor.document.text.contains("public override double Area()"))
    }

    private companion object {
        const val IMPLEMENT = "Implement missing members"
        const val OVERRIDE = "Override members..."

        fun bytes(name: String): ByteArray? = CSharpInheritedIntentionsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
