package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
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

/**
 * The compiler warnings and the errors of task D2 (CSHARP_PSI_MIGRATION.md): CS0162, CS0168 / CS0219, CS4014, the simple nullable ones,
 * CS0120, CS1503 / CS1501 / CS7036 of generic, `params` and optional overloads, CS0029 / CS0266 of target-typed expressions. The expected
 * codes and spans are Roslyn's (`roslyndump semantics` of the same text).
 */
class CSharpSemanticWarningsTest : BasePlatformTestCase() {
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

    /** Each problem of [codes] as `CSxxxx text-under-it`, in the order of the text. */
    private fun problems(text: String, vararg codes: String): List<String> {
        val file = myFixture.addFileToProject("warnings/W${files++}.cs", text.trimIndent()) as CSharpFile
        val found = CSharpSemanticChecks(CSharpSemanticSession(project).resolver(file)).run()
        return found.filter { codes.isEmpty() || it.code in codes }.sortedBy { it.range.startOffset }.map { "${it.code} ${file.text.substring(it.range.startOffset, it.range.endOffset)}" }
    }

    fun testUnreachableCode() {
        assertEquals(
            listOf("CS0162 Console", "CS0162 int", "CS0162 Console", "CS0162 Console", "CS0162 x", "CS0162 x", "CS0162 Console", "CS0162 x", "CS0162 x"),
            problems(
                """
                using System;
                class Probe
                {
                    int A(int x) { return x; Console.WriteLine(x); x++; }
                    void B() { throw new InvalidOperationException(); int y = 1; }
                    void C(bool flag) { while (true) { } Console.WriteLine(); }
                    void D(int x) { return; { Console.WriteLine(); } }
                    void E(int x)
                    {
                        if (x > 0) { return; ; x++; }
                        else { throw new Exception(); }
                        x--;
                    }
                    void F() { return; void Local() { } Console.WriteLine(); }
                    void G(int x)
                    {
                        switch (x)
                        {
                            case 1: return; x++;
                            default: break;
                        }
                        for (;;) { }
                        x++;
                    }
                    void Fine(int x, bool flag)
                    {
                        if (flag) return;
                        x++;
                        while (flag) { if (x > 1) break; }
                        x++;
                        try { return; } catch (Exception) { }
                        x++;
                        System.Action a = () => { return; };
                        x++;
                    }
                }
                """,
                "CS0162",
            ),
        )
    }

    fun testUnreachableCodeKeepsPragmaAndGoto() {
        assertEquals(
            emptyList<String>(),
            problems(
                """
                using System;
                class Probe
                {
                    string A()
                    {
                        throw new Exception();
                #pragma warning disable CS0162
                        return "never";
                #pragma warning restore CS0162
                    }
                    void B(int x) { goto end; x++; end: x--; }
                }
                """,
                "CS0162",
            ),
        )
    }

    fun testUnusedLocals() {
        assertEquals(
            listOf("CS0168 a", "CS0219 b", "CS0219 c", "CS0219 d", "CS0219 g", "CS0219 k", "CS0219 m", "CS0219 n", "CS0219 p", "CS0219 q", "CS0219 i", "CS0219 _"),
            problems(
                """
                using System;
                class Probe
                {
                    static int Compute() => 1;
                    static void Use(out int x) { x = 1; }
                    void Unused()
                    {
                        int a;
                        int b = 5;
                        string c = null;
                        var d = "x";
                        int e = Compute();
                        object f = new object();
                        int g = 1; g = 2;
                        int h = 1; Console.WriteLine(h);
                        const int k = 1;
                        int m = default;
                        string n = "a" + "b";
                        int p; p = 5;
                        string q = "x"; q = null!;
                        int r = 1; r = Compute();
                        int s = 1; s += 1;
                        int t = 1; t++;
                        int u = 0; Action w = () => Console.WriteLine(u);
                        int v = 0; Use(out v);
                        for (int i = 0; ; ) { break; }
                        using var stream = new System.IO.MemoryStream();
                        int _ = 3;
                        string z = nameof(Compute);
                        Console.WriteLine(z);
                    }
                }
                """,
                "CS0168", "CS0219",
            ),
        )
    }

    fun testNotAwaitedCalls() {
        assertEquals(
            listOf("CS4014 Work()", "CS4014 Task.Delay(1)", "CS4014 Value()", "CS4014 this.Work()", "CS4014 Work()"),
            problems(
                """
                using System;
                using System.Threading.Tasks;
                class Probe
                {
                    async Task NotAwaited()
                    {
                        Work();
                        Task.Delay(1);
                        Value();
                        this.Work();
                        _ = Work();
                        await Work();
                        Task t = Work();
                        Func<Task> f = async () => { Work(); await t; };
                    }
                    void NotAsync() { Work(); }
                    Task Work() => Task.CompletedTask;
                    ValueTask Value() => default;
                }
                """,
                "CS4014",
            ),
        )
    }

    fun testNullableWarningsWhereTheContextIsOn() {
        val source = """
            #nullable enable
            class Probe
            {
                string field;
                string? maybe;
                string ReturnsNull() { return null; }
                string ReturnsNull2() => null;
                string? Fine() => null;
                void Nullables(string? p)
                {
                    string s = null;
                    field = null;
                    Take(null);
                    string? ok = null;
                    var v = (string)null;
                    string forgiven = null!;
                    s = p!;
                }
                void Take(string s) { }
            }
            class NoCtor
            {
                public string Name;
                public string Prop { get; set; }
                public string? Fine;
                public string Init = "";
                public required string Required;
                public string Computed => "";
                public int Number;
                public static string Shared;
            }
            class WithCtor
            {
                public string Name;
                public WithCtor() { Name = ""; }
            }
            struct Value { public string Name; }
        """
        assertEquals(
            listOf("CS8618 field", "CS8603 null", "CS8603 null", "CS8600 null", "CS8625 null", "CS8625 null", "CS8600 (string)null", "CS8618 Name", "CS8618 Prop", "CS8618 Shared"),
            problems(source, "CS8600", "CS8603", "CS8618", "CS8625"),
        )
        assertEquals("no context, no warnings", emptyList<String>(), problems(source.replace("#nullable enable", ""), "CS8600", "CS8603", "CS8618", "CS8625"))
        CSharpWarningContext.setNullableForTests("enable")
        assertEquals("the project's <Nullable>", 10, problems(source.replace("#nullable enable", ""), "CS8600", "CS8603", "CS8618", "CS8625").size)
        assertEquals("#nullable disable", emptyList<String>(), problems(source.replace("#nullable enable", "#nullable disable"), "CS8600", "CS8603", "CS8618", "CS8625"))
    }

    fun testInstanceMemberFromStaticContext() {
        assertEquals(
            listOf("CS0120 instance", "CS0120 instance", "CS0120 Helper"),
            problems(
                """
                using System;
                class Probe
                {
                    int instance;
                    static int counter;
                    Probe Self => this;
                    static void StaticUse()
                    {
                        instance = 1;
                        Console.WriteLine(instance);
                        Helper();
                        counter++;
                        Console.WriteLine(nameof(instance));
                        var p = new Probe { instance = 2 };
                        Func<int> f = () => p.instance;
                    }
                    void Helper() { }
                    void InstanceUse() { instance = 1; Helper(); }
                    static Color Color => Color.Red;
                    int other = 1;
                }
                enum Color { Red }
                """,
                "CS0120",
            ),
        )
    }

    fun testArgumentsOfGenericParamsAndOptionalOverloads() {
        assertEquals(
            listOf("CS1503 \"x\"", "CS1501 One", "CS7036 Two", "CS7036 Two", "CS1503 \"y\"", "CS7036 Gen", "CS1503 \"x\""),
            problems(
                """
                using System;
                class Probe2
                {
                    static int One(int a) => a;
                    static int Two(int a, string b = "") => a;
                    static int Gen<T>(T a, int b) => b;
                    static int Many(params int[] xs) => 0;
                    void Calls(bool flag, string text)
                    {
                        One("x");
                        One(1, 2);
                        Two();
                        Two(b: "x");
                        Gen("x", "y");
                        Gen(1);
                        Many("x");
                        One(1); Two(1, b: "x"); Gen("x", 1); Many(); Many(1, 2); Many(new[] { 1 });
                    }
                }
                """,
                "CS1503", "CS1501", "CS7036",
            ),
        )
    }

    fun testTargetTypedConversions() {
        assertEquals(
            // `byte e = flag ? 1 : 2`: the natural type `int` decides, though each constant would fit (Roslyn's CS0266)
            listOf("CS0029 flag ? \"a\" : \"b\"", "CS0266 flag ? 1L : 2L", "CS0029 \"x\"", "CS0029 \"y\"", "CS0029 flag ? 1 : 2", "CS0266 flag ? 1 : 2"),
            problems(
                """
                class Probe
                {
                    void Calls(bool flag, string text)
                    {
                        int a = flag ? "a" : "b";
                        int b = flag ? 1L : 2L;
                        int c = text switch { "a" => "x", _ => "y" };
                        string d = flag ? 1 : 2;
                        byte e = flag ? 1 : 2;
                        long f = flag ? 1 : 2;
                        int g = text switch { "a" => 1, _ => 2 };
                    }
                }
                """,
                "CS0029", "CS0266",
            ),
        )
    }

    /** The playground's scenario file gives what `roslyndump semantics` gave for it (the codes of D2), nothing more. */
    fun testPlaygroundScenarios() {
        val text = java.io.File("debug-playground/Broken/SemanticErrors2.cs").readText().replace("\r\n", "\n")
            .replace("using System.Threading.Tasks;", "using System;\nusing System.Threading.Tasks;")
        assertEquals(
            listOf(
                "CS0162 Console", "CS0162 Console", "CS0168 a", "CS0219 b", "CS0219 c", "CS0120 _instance", "CS0120 Helper", "CS4014 Work()", "CS4014 Task.Delay(1)",
                "CS8603 null", "CS8600 null", "CS8625 null", "CS8625 null", "CS1503 \"x\"", "CS7036 Two", "CS1503 \"y\"", "CS1503 \"z\"",
                "CS0029 flag ? \"a\" : \"b\"", "CS0029 \"x\"", "CS0029 \"y\"", "CS0266 flag ? 1 : 2", "CS8618 Name", "CS8618 Title",
            ),
            problems(text, "CS0162", "CS0168", "CS0219", "CS0120", "CS4014", "CS8600", "CS8603", "CS8618", "CS8625", "CS1503", "CS1501", "CS7036", "CS0029", "CS0266"),
        )
    }

    fun testQuickFixes() {
        val file = myFixture.addFileToProject("warnings/Fixes.cs", """
            using System.Threading.Tasks;
            class Probe
            {
                async Task Run() { int unused = 1; int a = 1, b = 2; Work(); Console.WriteLine(b); }
                Task Work() => Task.CompletedTask;
                static void Resize(int width, int height) { }
                void Call() { Resize(640, 480); }
            }
        """.trimIndent()) as CSharpFile
        val text = file.text
        val removal = io.github.dotnetsupport.lang.NativeCSharpWarningFixes.removeVariable(file, text.indexOf("unused"))!!
        assertEquals("int unused = 1;", text.substring(removal.range.startOffset, removal.range.endOffset).trim())
        val second = io.github.dotnetsupport.lang.NativeCSharpWarningFixes.removeVariable(file, text.indexOf("a = 1"))!!
        assertEquals("a = 1, ", text.substring(second.range.startOffset, second.range.endOffset))
        assertNull("b is read", io.github.dotnetsupport.lang.NativeCSharpWarningFixes.removeVariable(file, text.indexOf("b = 2")))
        val await = io.github.dotnetsupport.lang.NativeCSharpWarningFixes.addAwait(file, text.indexOf("Work();"))!!
        assertEquals("await ", await.text)
        val resolver = CSharpSemanticSession(project).resolver(file)
        val names = io.github.dotnetsupport.lang.NativeCSharpWarningFixes.addArgumentNames(file, text.indexOf("640"), resolver)!!
        assertEquals("width: 640, height: 480", names.text)
        assertEquals("640, 480", text.substring(names.range.startOffset, names.range.endOffset))
    }

    /** The fixes from the editor: each edits its own place after another fix has moved the text (a stale offset would hit the wrong line). */
    fun testQuickFixesFromTheEditorAfterAnEdit() {
        myFixture.configureByText("FixesInEditor.cs", """
            using System.Threading.Tasks;
            class Probe
            {
                async Task Run()
                {
                    int unused;
                    Work();
                    Task.Delay(1);
                }
                Task Work() => Task.CompletedTask;
            }
        """.trimIndent())
        fun apply(text: String, at: String) {
            myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf(at) + 1)
            myFixture.launchAction(myFixture.findSingleIntention(text))
        }
        myFixture.doHighlighting()
        apply("Remove unused variable", "unused")
        apply("Add 'await'", "Work();")
        val body = myFixture.editor.document.text.substringAfter("Run()").substringBefore("Task Work")
        assertEquals(listOf("await Work();", "Task.Delay(1);"), body.lines().map { it.trim() }.filter { it.endsWith(";") })
    }

    /** Warnings are yellow, unused locals and unreachable code gray, as in Rider. */
    fun testSeverities() {
        myFixture.configureByText("Severities.cs", """
            using System;
            class Probe
            {
                int A(int x) { return x; Console.WriteLine(x); }
                void B() { int unused = 1; }
            }
        """.trimIndent())
        val infos = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.description?.startsWith("CS") == true }
        val text = myFixture.editor.document.text
        val shown = infos.sortedBy { it.startOffset }.map { "${it.description.substringBefore(':')} ${it.severity.name} ${text.substring(it.startOffset, it.endOffset)}" }
        assertEquals(listOf("CS0162 WARNING Console.WriteLine(x);", "CS0219 WARNING unused"), shown)
    }
}
