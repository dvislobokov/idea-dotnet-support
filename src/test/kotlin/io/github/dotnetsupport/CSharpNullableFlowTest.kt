package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticChecks
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpWarningContext
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import java.io.File

/**
 * The nullable flow analysis (`lang/semantic/CSharpNullableFlow`, 0.1.80): CS8600 / CS8601 / CS8602 / CS8603 / CS8604 / CS8618 / CS8625
 * through null tests, `??`, `?.`, `!`, early exits, loops, `try`, `switch`, the nullable attributes of the source and of the index.
 * `nullableFlow/Cases.cs` is checked against Roslyn's own diagnostics of it (`Cases.roslyn.txt`: `roslyndump semantics Cases.cs`).
 */
class CSharpNullableFlowTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpWarningContext.setNullableForTests(null, set = false)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun found(text: String): List<Triple<String, Int, Int>> {
        val file = myFixture.addFileToProject("flow/F${files++}.cs", text) as CSharpFile
        return CSharpSemanticChecks(CSharpSemanticSession(project).resolver(file)).run()
            .filter { it.code in CODES }.map { Triple(it.code, it.range.startOffset, it.range.endOffset) }.distinct().sortedWith(compareBy({ it.second }, { it.first }))
    }

    /** Each nullable warning as `CSxxxx text-under-it`, in the order of the text. */
    private fun problems(text: String): List<String> {
        val source = "#nullable enable\n" + text.trimIndent()
        return found(source).map { (code, start, end) -> "$code ${source.substring(start, end)}" }
    }

    fun testCasesAgreeWithRoslyn() {
        val text = File("src/test/resources/nullableFlow/Cases.cs").readText().replace("\r\n", "\n")
        val roslyn = File("src/test/resources/nullableFlow/Cases.roslyn.txt").readLines().filter { it.isNotBlank() }.map { line ->
            val (start, end, code) = line.split('\t')
            Triple(code, start.toInt(), end.toInt())
        }.distinct().sortedWith(compareBy({ it.second }, { it.first }))
        val ours = found(text)
        fun line(start: Int) = text.substring(0, start).count { it == '\n' } + 1
        fun show(items: List<Triple<String, Int, Int>>) = items.joinToString("\n") { (code, start, end) -> "$code ${text.substring(start, end)} @${line(start)}" }
        // what the analysis knowingly leaves out (precision first): a lambda's captured variables and its parameters typed by the
        // delegate; a partial type while the source generators are not known (never in the light tests)
        val missed = setOf("CS8602 s @118", "CS8602 x @119", "CS8618 Part @176")
        assertEquals(show(roslyn.filter { show(listOf(it)) !in missed }), show(ours))
    }

    fun testDereferenceAfterNullTests() {
        assertEquals(
            listOf("CS8602 s", "CS8602 b"),
            problems(
                """
                class C
                {
                    int A(string? s) => s.Length + s.Length;
                    void B(string? a, string? b)
                    {
                        if (a is not null && a.Length > 0) { }
                        if (a?.Length > 0) { }
                        if (b is { Length: > 0 }) System.Console.WriteLine(b.Length);
                        else System.Console.WriteLine(b.Length);
                    }
                }
                """,
            ),
        )
    }

    fun testIndexedAttributes() {
        assertEquals(
            listOf("CS8602 found", "CS8602 g.Maybe", "CS8604 Guards.Same(null)"),
            problems(
                """
                using Fixture;
                class C
                {
                    void A(string? s, Guards g, object? o)
                    {
                        if (!Guards.IsBlank(s)) System.Console.WriteLine(s.Length);
                        if (g.TryFind("k", out var found)) System.Console.WriteLine(found.Length);
                        else System.Console.WriteLine(found.Length);
                        Guards.Check(o);
                        System.Console.WriteLine(o.ToString());
                        Guards.Assert(s != null);
                        System.Console.WriteLine(s.Length);
                        g.Init();
                        System.Console.WriteLine(g.Name.Length);
                        System.Console.WriteLine(g.Maybe.Length);
                        System.Console.WriteLine(Guards.Same("x").Length);
                        Take(Guards.Same(null));
                    }
                    void B(string? s, Guards g)
                    {
                        if (g.HasName) System.Console.WriteLine(g.Name.Length);
                        if (s == null) Guards.Fail();
                        System.Console.WriteLine(s.Length);
                    }
                    void Take(string value) { }
                }
                """,
            ),
        )
    }

    fun testCorpusFalsePositives() {
        // System.Threading.Tasks.Dataflow: none of these is a warning of Roslyn
        assertEquals(
            emptyList<String>(),
            problems(
                """
                using System;
                using System.Diagnostics;
                using System.Threading.Tasks;
                public interface IBlock { void Fault(Exception e); }
                public class Core<TOutput>
                {
                    internal Info GetInfo() { return new Info(this); }
                    internal sealed class Info
                    {
                        private readonly Core<TOutput> _source;
                        public Info(Core<TOutput> source) { _source = source; }
                    }
                }
                public class State { public Task? Processing; }
                public class Join<T1, T2> : IBlock
                {
                    private readonly Core<Tuple<T1, T2>> _source = new();
                    private State? _state;
                    public void Fault(Exception e) { }
                    void Start(object? state)
                    {
                        var self = ((Join<T1, T2>)state!) as IBlock;
                        self.Fault(new Exception());
                        _state!.Processing = new Task(() => { });
                        Run(_state.Processing);
                    }
                    static void Run(Task task) { }
                    private sealed class DebugView
                    {
                        private readonly Join<T1, T2> _joinBlock;
                        private readonly Core<Tuple<T1, T2>>.Info _info;
                        public DebugView(Join<T1, T2> joinBlock)
                        {
                            Debug.Assert(joinBlock != null, "Need a block.");
                            _joinBlock = joinBlock;
                            _info = joinBlock._source.GetInfo();
                        }
                    }
                }
                """,
            ),
        )
    }

    fun testObliviousAndUnknownStaySilent() {
        assertEquals(
            emptyList<String>(),
            problems(
                """
                using Fixture;
                class C
                {
                    void A(Oblivious o, string? s)
                    {
                        System.Console.WriteLine(o.Field.Length);
                        string t = Oblivious.Make(null);
                        Unknown(s);
                        System.Console.WriteLine(s.Length);
                    }
                    void B<T>(T value) { System.Console.WriteLine(value.ToString()); }
                }
                """,
            ),
        )
    }

    fun testNoWarningsWithoutNullableContext() {
        val file = myFixture.addFileToProject("flow/Off${files++}.cs", "class C { int A(string? s) => s.Length; }") as CSharpFile
        val found = CSharpSemanticChecks(CSharpSemanticSession(project).resolver(file)).run().filter { it.code in CODES }
        assertEmpty(found)
    }

    fun testPartialTypesWaitForGenerators() {
        // the source generators are not known in the light tests: another part may set the field
        assertEquals(
            emptyList<String>(),
            problems(
                """
                partial class P { public string A; public P() { } }
                partial class P { }
                """,
            ),
        )
    }

    fun testPragmaSuppresses() {
        assertEquals(
            emptyList<String>(),
            problems(
                """
                class C
                {
                #pragma warning disable CS8602
                    int A(string? s) => s.Length;
                #pragma warning restore CS8602
                }
                """,
            ),
        )
    }

    companion object {
        private val CODES = setOf("CS8600", "CS8601", "CS8602", "CS8603", "CS8604", "CS8618", "CS8625")
    }
}
