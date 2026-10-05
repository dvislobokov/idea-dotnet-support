package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSpaceAutoPopup
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.NativeCSharpParameterInfo
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * `override |` (0.1.85): the members to override of bases of the solution, of files the build generates (gRPC's `Greeter.GreeterBase`
 * with `global::` and the `grpc::` alias) and of assemblies (`Exception`, `object`), generic bases, what is overridden or sealed left out,
 * the member written as Rider writes it with its `using` directives, `async` typed before; the list opening by itself after the space.
 */
class CSharpOverrideCompletionTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            settings.state.features = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun lookup(text: String): List<LookupElement> {
        myFixture.configureByText("Override${files++}.cs", text)
        myFixture.completeBasic()
        return myFixture.lookupElements?.filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.orEmpty()
    }

    private fun names(text: String): List<String> = lookup(text).map { it.lookupString }

    private fun choose(text: String, item: String): String {
        val elements = lookup(text)
        val element = elements.firstOrNull { it.lookupString == item } ?: error("no $item in ${elements.map { it.lookupString }}")
        myFixture.lookup.currentItem = element
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        return myFixture.editor.document.text
    }

    private fun addGrpcBase() {
        myFixture.addFileToProject("Grpc/Core.cs", "namespace Grpc.Core { public class ServerCallContext { } public abstract class ClientBase<T> { } }")
        myFixture.addFileToProject("Grpc/Greet.cs", "namespace Playground.Grpc { public sealed partial class HelloRequest { public string Name { get; set; } } public sealed partial class HelloReply { public string Message { get; set; } } }")
        myFixture.addFileToProject(
            "Grpc/obj/Debug/net9.0/Protos/GreetGrpc.cs",
            """
            #pragma warning disable 0414, 1591, 8981, 0612
            #region Designer generated code

            using grpc = global::Grpc.Core;

            namespace Playground.Grpc {
              public static partial class Greeter
              {
                static readonly string __ServiceName = "greet.Greeter";

                [grpc::BindServiceMethod(typeof(Greeter), "BindService")]
                public abstract partial class GreeterBase
                {
                  [global::System.CodeDom.Compiler.GeneratedCode("grpc_csharp_plugin", null)]
                  public virtual global::System.Threading.Tasks.Task<global::Playground.Grpc.HelloReply> SayHello(global::Playground.Grpc.HelloRequest request, grpc::ServerCallContext context)
                  {
                    throw new grpc::RpcException(new grpc::Status(grpc::StatusCode.Unimplemented, ""));
                  }
                }
              }
            }
            #endregion
            """.trimIndent(),
        )
    }

    fun testTheBaseOfGrpcGeneratedByTheBuild() {
        addGrpcBase()
        val text = "using Grpc.Core;\n\nnamespace Playground.Grpc;\n\npublic sealed class GreeterService : Greeter.GreeterBase\n{\n    public override <caret>\n}\n"
        val list = names(text)
        assertTrue(list.toString(), "SayHello" in list)
        assertTrue("object's members come from the assemblies: $list", list.containsAll(listOf("Equals", "GetHashCode", "ToString")))
        assertEquals("the real base's members first: $list", "SayHello", list.first())
        val item = lookup(text).first { it.lookupString == "SayHello" }
        val presentation = LookupElementPresentation.renderElement(item)
        assertEquals("Task<HelloReply>", presentation.typeText)
        assertTrue(presentation.tailText, presentation.tailText!!.startsWith("(HelloRequest request, ServerCallContext context)"))
        assertEquals(
            "using System.Threading.Tasks;\nusing Grpc.Core;\n\nnamespace Playground.Grpc;\n\npublic sealed class GreeterService : Greeter.GreeterBase\n{\n" +
                "    public override Task<HelloReply> SayHello(HelloRequest request, ServerCallContext context)\n    {\n        return base.SayHello(request, context);\n    }\n}\n",
            choose(text, "SayHello"),
        )
        val caretLine = myFixture.editor.document.text.substring(0, myFixture.caretOffset).substringAfterLast('\n')
        assertEquals("        return base.SayHello(request, context);", caretLine)
    }

    fun testOverrideWithoutAccessGetsTheBaseOne() {
        addGrpcBase()
        val text = "using Grpc.Core;\nusing System.Threading.Tasks;\nnamespace Playground.Grpc;\nclass S : Greeter.GreeterBase\n{\n    override <caret>\n}\n"
        assertTrue(choose(text, "SayHello").contains("    public override Task<HelloReply> SayHello(HelloRequest request, ServerCallContext context)\n"))
    }

    fun testLibraryBaseAndWhatIsOverriddenOrSealed() {
        val text = """
            using System;
            class Failure : Exception
            {
                public override string Message => "";
                public sealed override string ToString() => "";
            }
            class Deeper : Failure
            {
                override <caret>
            }
        """.trimIndent()
        val list = names(text)
        assertTrue(list.toString(), list.containsAll(listOf("StackTrace", "Equals", "GetHashCode")))
        assertFalse("sealed in Failure: $list", "ToString" in list)
        assertTrue("overridden in Failure, still virtual: $list", "Message" in list)
        assertFalse("not virtual: $list", "GetType" in list)
        assertFalse("no keywords after override: $list", "static" in list)
        assertTrue(choose(text, "StackTrace").contains("    public override string? StackTrace\n    {\n        get => base.StackTrace;\n    }"))
    }

    fun testWhatTheTypeOverridesIsNotOffered() {
        val text = "class A\n{\n    public override string ToString() => \"\";\n    public override <caret>\n}\n"
        val list = names(text)
        assertFalse(list.toString(), "ToString" in list)
        assertTrue(list.toString(), "Equals" in list)
    }

    fun testGenericBaseAndAbstractMembers() {
        val text = """
            abstract class Box<T>
            {
                public abstract T Open(T seal, out bool broken);
                protected virtual int Count { get; set; }
                public abstract string Label { get; }
            }
            class IntBox : Box<int>
            {
                <caret>
            }
        """.trimIndent().replace("<caret>", "override <caret>")
        assertEquals(
            "using System;\n\n" + """
            abstract class Box<T>
            {
                public abstract T Open(T seal, out bool broken);
                protected virtual int Count { get; set; }
                public abstract string Label { get; }
            }
            class IntBox : Box<int>
            {
                public override int Open(int seal, out bool broken)
                {
                    throw new NotImplementedException();
                }
            }
            """.trimIndent(),
            choose(text, "Open"),
        )
        assertTrue(choose(text, "Count").contains("    protected override int Count\n    {\n        get => base.Count;\n        set => base.Count = value;\n    }"))
        assertTrue(choose(text, "Label").contains("    public override string Label { get; }\n"))
    }

    fun testAsyncTypedBeforeAwaitsTheBase() {
        val text = """
            using System.Threading.Tasks;
            class Worker
            {
                protected virtual Task<int> RunAsync(int times) => Task.FromResult(times);
                protected virtual Task StopAsync() => Task.CompletedTask;
            }
            class Mine : Worker
            {
                protected async override <caret>
            }
        """.trimIndent()
        assertTrue(choose(text, "RunAsync").contains("    protected async override Task<int> RunAsync(int times)\n    {\n        return await base.RunAsync(times);\n    }"))
        assertTrue(choose(text, "StopAsync").contains("    protected async override Task StopAsync()\n    {\n        await base.StopAsync();\n    }"))
    }

    fun testWithoutAssembliesObjectMembersAreStillOffered() {
        CSharpSemanticEnvironment.setAssembliesForTests { null }
        val list = names("class A\n{\n    override <caret>\n}\n")
        assertTrue(list.toString(), list.containsAll(listOf("Equals", "GetHashCode", "ToString")))
    }

    fun testBaseDotListsTheMembersOfALibraryBase() {
        val list = names("using System;\nclass Failure : Exception\n{\n    public override string Message => base.<caret>;\n}\n")
        assertTrue(list.toString(), list.containsAll(listOf("Message", "StackTrace", "ToString", "GetHashCode")))
        assertFalse("the type's own members are not base's: $list", "Failure" in list)
        val grpcList = names("class S { public virtual int Run(int x) => x; }\nclass T : S\n{\n    public override int Run(int x) => base.<caret>;\n}\n")
        assertTrue(grpcList.toString(), "Run" in grpcList && "ToString" in grpcList)
    }

    fun testParameterInfoOfBaseAndThisConstructorInitializers() {
        myFixture.configureByText("Override${files++}.cs", "using System;\nclass Failure : Exception\n{\n    public Failure(string message) : base(<caret>) { }\n}\n")
        var list = NativeCSharpParameterInfo.listAt(myFixture.file, myFixture.caretOffset)!!
        val rows = NativeCSharpParameterInfo.rows(myFixture.file as CSharpFile, list).map { it.parameters.joinToString(", ") }
        assertTrue(rows.toString(), "string? message" in rows || "string message" in rows)
        myFixture.configureByText("Override${files++}.cs", "class Box\n{\n    public Box(int size, string label) { }\n    public Box() : this(1, <caret>) { }\n}\n")
        list = NativeCSharpParameterInfo.listAt(myFixture.file, myFixture.caretOffset)!!
        assertEquals("[(int size, string label) *, () ]".replace(" ]", "]"), NativeCSharpParameterInfo.rows(myFixture.file as CSharpFile, list).toString().replace(" ]", "]"))
        myFixture.configureByText("Override${files++}.cs", "class A(int size) { }\nclass B(int x) : A(<caret>) { }\n")
        list = NativeCSharpParameterInfo.listAt(myFixture.file, myFixture.caretOffset)!!
        assertEquals("int size", NativeCSharpParameterInfo.rows(myFixture.file as CSharpFile, list).single().parameters.single())
    }

    fun testASpaceAfterOverrideOpensTheList() {
        for (text in listOf("    override", "    public override", "    partial", "var x = new", "\toverride")) assertTrue(text, CSharpSpaceAutoPopup.opens(text, text.length))
        for (text in listOf("    overrides", "    public", "x.new", "renew", "")) assertFalse(text, CSharpSpaceAutoPopup.opens(text, text.length))
    }

    private companion object {
        fun bytes(name: String): ByteArray? = CSharpOverrideCompletionTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
