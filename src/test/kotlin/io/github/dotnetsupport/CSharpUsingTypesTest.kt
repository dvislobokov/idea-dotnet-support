package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.NativeCSharpDiagnostics
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The tails of task A8 that waited for the types of C2: CS1674 / CS8410 / CS8417 / CS8418 on a `using` resource that is not disposable, and
 * the `using (` / `using var x = ` list without the locals and members known not to be. Never on what is not known: unknown types,
 * unresolved bases, type parameters, the dispose pattern, assemblies without System.Runtime.
 */
class CSharpUsingTypesTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0
    private var assemblies: AssemblyIndexSet? = null
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        assemblies = ASSEMBLIES
        CSharpSemanticEnvironment.setAssembliesForTests { assemblies }
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun text(body: String, members: String = ""): String = """
        using System;
        using System.IO;
        using System.Threading;
        using System.Threading.Tasks;

        class Plain { }
        class Resource : IDisposable { public void Dispose() { } }
        class Odd : Missing { }
        struct Pattern { public void Dispose() { } }
        class AsyncPattern { public ValueTask DisposeAsync() => default; }

        class Sample
        {
        ${members.prependIndent("    ")}
            async Task Body<T>(T generic, Plain plain)
            {
        ${body.prependIndent("        ")}
            }
        }
    """.trimIndent()

    /** The errors of the native pass at the `using`s of [body]: the text under each and its message. */
    private fun errors(body: String, members: String = ""): List<String> {
        myFixture.configureByText("UsingTypes${files++}.cs", text(body, members))
        val text = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.description?.contains(" using statement ") == true }
            .map { text.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    fun testCs1674OnWhatIsNotDisposable() {
        assertEquals(
            listOf(
                "var n = 5 -> CS1674: 'int': type used in a using statement must implement 'System.IDisposable'.",
                "\"text\" -> CS1674: 'string': type used in a using statement must implement 'System.IDisposable'.",
                "Plain p = new Plain() -> CS1674: 'Plain': type used in a using statement must implement 'System.IDisposable'.",
                "var q = plain -> CS1674: 'Plain': type used in a using statement must implement 'System.IDisposable'.",
            ),
            errors("""
                using (var n = 5) { }
                using ("text") { }
                using (Plain p = new Plain()) { }
                using var q = plain;
            """.trimIndent()),
        )
    }

    fun testNoErrorOnDisposableOrUnknown() {
        assertEquals(
            emptyList<String>(),
            errors("""
                using (var s = new MemoryStream()) { }
                using var w = new StringWriter();
                using (var r = new Resource()) { }
                await using var m = new MemoryStream();
                using (var x = Unknown()) { }
                using (var o = new Odd()) { }
                using (generic) { }
                using (var p = new Pattern()) { }
                await using var a = new AsyncPattern();
                using (null) { }
            """.trimIndent()),
        )
    }

    fun testNullableStructs() {
        // a nullable of a disposable struct is a resource; of an int it is not, shown as `int?`
        assertEquals(
            listOf("var n = (int?)1 -> CS1674: 'int?': type used in a using statement must implement 'System.IDisposable'."),
            errors("""
                using (var n = (int?)1) { }
                using (Handle? h = null) { }
            """.trimIndent(), members = "struct Handle : IDisposable { public void Dispose() { } }"),
        )
    }

    fun testAwaitUsing() {
        assertEquals(
            listOf(
                "var c = new CancellationTokenSource() -> CS8417: 'System.Threading.CancellationTokenSource': type used in an asynchronous using statement " +
                    "must implement 'System.IAsyncDisposable' or implement a suitable 'DisposeAsync' method. Did you mean 'using' rather than 'await using'?",
                "var p = new Plain() -> CS8410: 'Plain': type used in an asynchronous using statement must implement 'System.IAsyncDisposable' or " +
                    "implement a suitable 'DisposeAsync' method.",
            ),
            errors("""
                await using var c = new CancellationTokenSource();
                await using (var p = new Plain()) { }
            """.trimIndent()),
        )
    }

    fun testNothingWithoutTheRuntime() {
        // assemblies without System.Runtime: `int` and `MemoryStream` are unknown, so nothing is said of them; `Plain` is known through and through
        assemblies = AssemblyIndexSet(listOf(fixture("IndexFixture")))
        assertEquals(
            listOf("Plain p = new Plain() -> CS1674: 'Plain': type used in a using statement must implement 'System.IDisposable'."),
            errors("""
                using (var n = 5) { }
                using (var s = new MemoryStream()) { }
                using (Plain p = new Plain()) { }
            """.trimIndent()),
        )
    }

    fun testTheServersCopyGivesWay() {
        myFixture.configureByText("UsingTypes${files++}.cs", text("using (var n = 5) { }"))
        myFixture.doHighlighting()
        val offset = myFixture.editor.document.text.indexOf("using (var n")
        assertTrue(NativeCSharpDiagnostics.repeatsNative(myFixture.file, "'int': type used in a using statement must implement 'System.IDisposable'.", offset))
        assertFalse(NativeCSharpDiagnostics.repeatsNative(myFixture.file, "'long': type used in a using statement must implement 'System.IDisposable'.", offset))
    }

    // ---- completion

    private fun native(body: String, members: String = ""): List<String> {
        myFixture.configureByText("UsingTypes${files++}.cs", text(body, members))
        myFixture.completeBasic()
        return myFixture.lookupElements?.filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }?.map { it.lookupString }.orEmpty()
    }

    fun testTheUsingListLeavesOutWhatIsNotDisposable() {
        val members = "private readonly MemoryStream _stream = new MemoryStream();\nprivate int _count;\nprivate Plain Plainly { get; } = new Plain();"
        val body = "var number = 5;\nvar stream = new MemoryStream();\nvar unknown = Unknown();\nusing (<caret>"
        val items = native(body, members)
        assertTrue(items.toString(), items.containsAll(listOf("stream", "unknown", "_stream", "generic", "var", "new")))
        assertFalse(items.toString(), items.any { it in setOf("number", "_count", "Plainly", "plain") })
        // `using var x = |` too
        val declared = native("var number = 5;\nvar stream = new MemoryStream();\nusing var s = <caret>")
        assertTrue(declared.toString(), "stream" in declared)
        assertFalse(declared.toString(), "number" in declared)
        // elsewhere nothing is left out
        assertTrue("number" in native("var number = 5;\nvar copy = <caret>"))
    }

    fun testTheAwaitUsingListAsksForIAsyncDisposable() {
        val items = native("var source = new CancellationTokenSource();\nvar stream = new MemoryStream();\nawait using (<caret>")
        assertTrue(items.toString(), "stream" in items)
        assertFalse(items.toString(), "source" in items)
    }

    private companion object {
        private fun bytes(name: String): ByteArray? = CSharpUsingTypesTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
