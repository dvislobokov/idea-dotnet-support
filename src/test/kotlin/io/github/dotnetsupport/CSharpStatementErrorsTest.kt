package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The errors of `return`, `await`, `switch` and the jump statements ([io.github.dotnetsupport.lang.semantic.CSharpStatementChecks]): Roslyn's
 * codes, messages and spans (checked against `dotnet build`), and silence where Roslyn decides by what the pass does not model.
 */
class CSharpStatementErrorsTest : BasePlatformTestCase() {
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
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Each error of [members] in a class `Probe` as `text under it -> CSxxxx: message`. */
    private fun errors(members: String): List<String> {
        val text = "using System;\nusing System.Collections.Generic;\nusing System.Threading.Tasks;\n\nnamespace Probe.Inner;\n\npublic class Probe\n{\n" +
            members.trimIndent() + "\n}\n"
        myFixture.configureByText("StatementErrors${files++}.cs", text)
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
            .sortedBy { it.startOffset }.map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    fun testReturnOfAValueWhereNoneGoes() {
        assertEquals(listOf(
            "return -> CS0127: Since 'Probe.Add(List<int>, ref int, params int[])' returns void, a return keyword must not be followed by an object expression",
            "return -> CS0127: Since 'Probe.P.set' returns void, a return keyword must not be followed by an object expression",
            "return -> CS0127: Since 'Probe.this[int].set' returns void, a return keyword must not be followed by an object expression",
            "return -> CS0127: Since 'Probe.Probe()' returns void, a return keyword must not be followed by an object expression",
            "return -> CS0127: Since 'Local<T>(T)' returns void, a return keyword must not be followed by an object expression",
            "return -> CS1997: Since 'Probe.SaveAsync()' is an async method that returns 'Task', a return keyword must not be followed by an object expression",
        ), errors("""
            public void Add(List<int> xs, ref int r, params int[] rest) { return 1; }
            public int P { get => 1; set { return 2; } }
            public int this[int i] { get => i; set { return 3; } }
            public Probe() { return 4; }
            public void Run() { void Local<T>(T t) { return t; } Local(1); }
            public async Task SaveAsync() { await Task.Delay(1); return 5; }
            public Func<int> Ok() { return () => { return 1; }; }
        """))
    }

    fun testEmptyReturnWhereAValueIsRequired() {
        assertEquals(listOf(
            "return -> CS0126: An object of a type convertible to 'int' is required",
            "return -> CS0126: An object of a type convertible to 'List<string>' is required",
            "return -> CS0126: An object of a type convertible to 'int?' is required",
            "return -> CS0126: An object of a type convertible to 'string' is required",
            "return -> CS0126: An object of a type convertible to 'Task' is required",
            "return -> CS0126: An object of a type convertible to 'string' is required",
        ), errors("""
            public int A() { return; }
            public List<string> B { get { return; } }
            public int? C() { return; }
            public async Task<string> D() { await Task.Delay(1); return; }
            public Task E() { return; }
            public void Ok() { return; }
            public async Task OkAsync() { await Task.Delay(1); return; }
            public string? Annotated() { return; }
            public Func<int> Lambda() => () => { return 1; };
        """))
    }

    fun testReturnInAnIterator() {
        assertEquals(listOf(
            "return -> CS1622: Cannot return a value from an iterator. Use the yield return statement to return a value, or yield break to end the iteration.",
            "return -> CS1622: Cannot return a value from an iterator. Use the yield return statement to return a value, or yield break to end the iteration.",
        ), errors("""
            public IEnumerable<int> A(bool b) { yield return 1; if (b) return; }
            public IEnumerator<int> B() { yield return 1; return null; }
            public IEnumerable<int> Ok() { Func<int> f = () => { return 1; }; yield return f(); }
        """))
    }

    fun testAwaitOutsideAnAsyncFunction() {
        assertEquals(listOf(
            "await -> CS4032: The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task<int>'.",
            "await -> CS4033: The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task'.",
            "await -> CS4032: The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task<Task<int>>'.",
            "await -> CS4033: The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task'.",
            "await -> CS4034: The 'await' operator can only be used within an async lambda expression. Consider marking this lambda expression with the 'async' modifier.",
            "await -> CS4034: The 'await' operator can only be used within an async anonymous method. Consider marking this anonymous method with the 'async' modifier.",
            "await -> CS4033: The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task'.",
        ), errors("""
            public int A() { await Task.Delay(1); return 1; }
            public Probe() { await Task.Delay(1); }
            public Task<int> B() { await Task.Delay(1); return Task.FromResult(1); }
            public void C()
            {
                await Task.Delay(1);
                Func<Task> f = () => { await Task.Delay(1); return Task.CompletedTask; };
                Action g = delegate { await Task.Delay(1); };
            }
            public void D(IAsyncEnumerable<int> xs) { await foreach (var x in xs) { } }
            public async Task Ok() { await Task.Delay(1); Func<Task> f = async () => await Task.Delay(1); await f(); }
        """))
    }

    fun testAwaitInALock() {
        assertEquals(listOf(
            "await -> CS1996: Cannot await in the body of a lock statement",
            "await -> CS4033: The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task'.",
            "await -> CS1996: Cannot await in the body of a lock statement",
        ), errors("""
            private readonly object _gate = new();
            public async Task A() { lock (_gate) { await Task.Delay(1); } }
            public void B() { lock (_gate) { await Task.Delay(1); } }
            public async Task Ok() { lock (await Task.FromResult(_gate)) { Func<Task> f = async () => await Task.Delay(1); } await Task.Delay(1); }
        """).let { it.take(1) + it.drop(1).sortedDescending() })
    }

    fun testDuplicateCaseLabels() {
        assertEquals(listOf(
            "case 0x1: -> CS0152: The switch statement contains multiple cases with the label value '1'",
            "case -2: -> CS0152: The switch statement contains multiple cases with the label value '-2'",
            "case \"a\": -> CS0152: The switch statement contains multiple cases with the label value '\"a\"'",
            "case null: -> CS0152: The switch statement contains multiple cases with the label value 'null'",
            "case 'c': -> CS0152: The switch statement contains multiple cases with the label value 'c'",
            "case 1L: -> CS0152: The switch statement contains multiple cases with the label value '1'",
        ), errors("""
            public void A(int x, string s, char c, long l)
            {
                switch (x) { case 1: break; case -2: break; case 0x1: break; case -2: break; case int n when n > 5: break; }
                switch (s) { case "a": break; case "b": break; case "a": break; case null: break; case null: break; }
                switch (c) { case 'c': case 'c': break; }
                switch (l) { case 1: break; case 1L: break; }
                switch (x) { case 1: break; case 2: break; case 3: break; }
            }
        """))
    }

    fun testFallThrough() {
        assertEquals(listOf(
            "case 1: -> CS0163: Control cannot fall through from one case label ('case 1:') to another",
            "case 3: -> CS0163: Control cannot fall through from one case label ('case 3:') to another",
            "default: -> CS8070: Control cannot fall out of switch from final case label ('default:')",
            "case 2: -> CS8070: Control cannot fall out of switch from final case label ('case 2:')",
        ), errors("""
            public void A(int x, bool b)
            {
                switch (x) { case 1: Console.WriteLine(); case 2: case 3: if (b) break; default: Console.WriteLine(); }
                switch (x) { case 1: break; case 2: }
                switch (x) { case 1: if (b) break; else return; case 2: while (true) { } case 3: throw new Exception(); }
                switch (x) { case 1: goto case 2; case 2: break; }
                switch (1) { case 2: Console.WriteLine(); case 1: break; }
                switch (x) { case int n: Console.WriteLine(n); break; }
            }
        """))
    }

    fun testJumpsWithoutATarget() {
        assertEquals(listOf(
            "break; -> CS0139: No enclosing loop out of which to break or continue",
            "continue; -> CS0139: No enclosing loop out of which to break or continue",
            "continue; -> CS0139: No enclosing loop out of which to break or continue",
            "missing -> CS0159: No such label 'missing' within the scope of the goto statement",
            "goto case 3; -> CS0159: No such label 'case 3:' within the scope of the goto statement",
            "goto default; -> CS0159: No such label 'default:' within the scope of the goto statement",
        ), errors("""
            public void A(int x)
            {
                if (x > 0) break;
                if (x > 1) continue;
                switch (x) { case 1: if (x > 0) continue; break; }
                while (x > 0) { x--; Action a = () => { }; if (x == 3) break; }
                if (x > 2) goto missing;
                switch (x) { case 1: if (x > 0) goto case 3; break; case 2: if (x > 0) goto default; break; }
                switch (x) { case 1: goto case 2; case 2: goto default; default: break; }
            }
            public void Ok() { var i = 0; again: i++; if (i < 3) goto again; }
        """))
    }

    fun testLeavingAFinally() {
        assertEquals(listOf(
            "return; -> CS0157: Control cannot leave the body of a finally clause",
            "break; -> CS0157: Control cannot leave the body of a finally clause",
            "goto again; -> CS0157: Control cannot leave the body of a finally clause",
            "break; -> CS0139: No enclosing loop out of which to break or continue",
        ), errors("""
            public void A(List<int> xs)
            {
                try { } finally { return; }
            }
            public void B(List<int> xs)
            {
                while (xs.Count > 0) { try { } finally { break; } }
            again:
                try { } finally { if (xs.Count > 0) goto again; }
            }
            public void C() { try { } finally { break; } }
            public void Ok(List<int> xs)
            {
                try { } finally { foreach (var x in xs) { if (x > 0) break; } Func<int> f = () => { return 1; }; goto inside; inside: ; }
            }
        """))
    }

    fun testDuplicateCaseLabelsByTheirConstantValues() {
        assertEquals(listOf(
            "case Color.Dark: -> CS0152: The switch statement contains multiple cases with the label value '0'",
            "case Color.Light - 1: -> CS0152: The switch statement contains multiple cases with the label value '6'",
            "case (Color)6: -> CS0152: The switch statement contains multiple cases with the label value '6'",
            "case 5 + 5: -> CS0152: The switch statement contains multiple cases with the label value '10'",
            "case 2 * 2 + 1: -> CS0152: The switch statement contains multiple cases with the label value '5'",
            "case 8: -> CS0152: The switch statement contains multiple cases with the label value '8'",
            "case ~0: -> CS0152: The switch statement contains multiple cases with the label value '-1'",
            "case 17: -> CS0152: The switch statement contains multiple cases with the label value '17'",
            "case \"hello\": -> CS0152: The switch statement contains multiple cases with the label value '\"hello\"'",
            "case \"ab\": -> CS0152: The switch statement contains multiple cases with the label value '\"ab\"'",
            "case 'x': -> CS0152: The switch statement contains multiple cases with the label value 'x'",
            "case 'A': -> CS0152: The switch statement contains multiple cases with the label value 'A'",
            "case 1099511627776: -> CS0152: The switch statement contains multiple cases with the label value '1099511627776'",
            "case Bits.A | Bits.B: -> CS0152: The switch statement contains multiple cases with the label value '3'",
            "case 3: -> CS0152: The switch statement contains multiple cases with the label value '3'",
            "case 2: -> CS0152: The switch statement contains multiple cases with the label value '2'",
        ), errors("""
            public enum Color { Red, Green = 5, Blue, Dark = Red, Light = Blue + 1, Mix = Green | Blue }
            [Flags] public enum Bits : byte { None = 0, A = 1, B = 1 << 1, C = A | B }
            const int Ten = 10;
            const int Five = Ten / 2;
            const string Hello = "hel" + "lo";
            const char Ch = 'x';
            const long Big = 1L << 40;
            public void M(Color c, int i, string s, char ch, long l, Bits b)
            {
                switch (c) { case Color.Red: break; case Color.Dark: break; case Color.Blue: break; case Color.Light - 1: break; case (Color)6: break; }
                switch (i)
                {
                    case Ten: break; case 5 + 5: break; case Five: break; case 2 * 2 + 1: break; case 1 << 3: break; case 8: break;
                    case -1: break; case ~0: break; case 0x10 | 0x01: break; case 17: break;
                }
                switch (s) { case Hello: break; case "hello": break; case "a" + "b": break; case "ab": break; }
                switch (ch) { case Ch: break; case 'x': break; case (char)65: break; case 'A': break; }
                switch (l) { case Big: break; case 1099511627776: break; }
                switch (b) { case Bits.C: break; case Bits.A | Bits.B: break; }
                const int local = 3;
                switch (i) { case local: break; case 3: break; }
                switch (c) { case Color.Mix: break; case Color.Green: break; case Color.Blue: break; }
                switch (i) { case 'a': break; case 98: break; case 1 << 33: break; case 2: break; }
            }
        """))
    }

    fun testGotoCaseOfEveryTypeAndJumpsThatGoNowhere() {
        assertEquals(listOf(
            "case 1: -> CS0163: Control cannot fall through from one case label ('case 1:') to another",
            "continue; -> CS0139: No enclosing loop out of which to break or continue",
            "case 2: -> CS0163: Control cannot fall through from one case label ('case 2:') to another",
            "goto case 7; -> CS0159: No such label 'case 7:' within the scope of the goto statement",
            "case \"a\": -> CS0163: Control cannot fall through from one case label ('case \"a\":') to another",
            "goto case \"b\"; -> CS0159: No such label 'case b:' within the scope of the goto statement",
            "case \"d\": -> CS0163: Control cannot fall through from one case label ('case \"d\":') to another",
            "goto case null; -> CS0159: No such label 'case :' within the scope of the goto statement",
            "case Color.Red: -> CS0163: Control cannot fall through from one case label ('case Color.Red:') to another",
            "goto case Color.Green; -> CS0159: No such label 'case 1:' within the scope of the goto statement",
            "case 'a': -> CS0163: Control cannot fall through from one case label ('case 'a':') to another",
            "goto case 'b'; -> CS0159: No such label 'case b:' within the scope of the goto statement",
            "case 1: -> CS0163: Control cannot fall through from one case label ('case 1:') to another",
            "goto case 2L; -> CS0159: No such label 'case 2:' within the scope of the goto statement",
            "case 1: -> CS0163: Control cannot fall through from one case label ('case 1:') to another",
            "goto case Seven; -> CS0159: No such label 'case 7:' within the scope of the goto statement",
            "case 2: -> CS0163: Control cannot fall through from one case label ('case 2:') to another",
            "goto case 3 + 4; -> CS0159: No such label 'case 7:' within the scope of the goto statement",
            "case 1: -> CS0163: Control cannot fall through from one case label ('case 1:') to another",
            "missing -> CS0159: No such label 'missing' within the scope of the goto statement",
        ), errors("""
            public enum Color { Red, Green }
            const int Seven = 7;
            public void M(int i, string s, Color c, char ch, long l)
            {
                switch (i) { case 1: continue; case 2: goto case 7; case 3: goto case 1; case 4: goto default; default: break; }
                switch (s) { case "a": goto case "b"; case "c": goto case "a"; case "d": goto case null; default: break; }
                switch (c) { case Color.Red: goto case Color.Green; default: break; }
                switch (ch) { case 'a': goto case 'b'; default: break; }
                switch (l) { case 1: goto case 2L; default: break; }
                switch (i) { case 1: goto case Seven; case 2: goto case 3 + 4; default: break; }
                switch (i) { case 1: goto missing; default: break; }
                switch (s) { case "x" + "y": break; case "z": goto case "xy"; }
                while (i > 0) { switch (i) { case 1: continue; default: break; } }
            }
        """))
    }

    fun testTopLevelStatements() {
        myFixture.configureByText("TopLevel${files++}.cs", """
            using System;
            int n = args.Length;
            break;
            continue;
            goto missing;
            goto done;
            for (int i = 0; i < 3; i++) { if (i == 1) break; }
            switch (n) { case 1: goto case 2; case 3: break; }
            done:
            Console.WriteLine(n);
        """.trimIndent() + "\n")
        val document = myFixture.editor.document.text
        assertEquals(listOf(
            "break; -> CS0139: No enclosing loop out of which to break or continue",
            "continue; -> CS0139: No enclosing loop out of which to break or continue",
            "missing -> CS0159: No such label 'missing' within the scope of the goto statement",
            "goto case 2; -> CS0159: No such label 'case 2:' within the scope of the goto statement",
        ), myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
            .sortedBy { it.startOffset }.map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description })
    }

    fun testReturnsOfLambdasByTheirDelegateType() {
        assertEquals(listOf(
            "return -> CS8030: Anonymous function converted to a void returning delegate cannot return a value",
            "return -> CS8030: Anonymous function converted to a void returning delegate cannot return a value",
            "return -> CS8031: Async lambda expression converted to a 'Task' returning delegate cannot return a value",
            "return -> CS0126: An object of a type convertible to 'int' is required",
            "return -> CS0126: An object of a type convertible to 'int' is required",
            "return -> CS8030: Anonymous function converted to a void returning delegate cannot return a value",
            "return -> CS8030: Anonymous function converted to a void returning delegate cannot return a value",
            "return -> CS8031: Async lambda expression converted to a 'ValueTask' returning delegate cannot return a value",
            "return -> CS0126: An object of a type convertible to 'string' is required",
            "return -> CS8030: Anonymous function converted to a void returning delegate cannot return a value",
            "return -> CS8030: Anonymous function converted to a void returning delegate cannot return a value",
            "return -> CS8030: Anonymous function converted to a void returning delegate cannot return a value",
            "return -> CS8031: Async lambda expression converted to a 'Task' returning delegate cannot return a value",
            "return -> CS0126: An object of a type convertible to 'string' is required",
            "return -> CS0126: An object of a type convertible to 'int?' is required",
            "return -> CS0126: An object of a type convertible to 'int[]' is required",
            "return -> CS0126: An object of a type convertible to 'Dictionary<string, int>' is required",
        ), errors("""
            public delegate void Handler(int x);
            public delegate Task Job();
            public void L(List<int> xs)
            {
                Action a = () => { return 1; };
                Action<int> b = x => { if (x > 0) return; return x; };
                Func<Task> f = async () => { await Task.Yield(); return 1; };
                Func<int> g = () => { return; };
                Func<Task<int>> h = async () => { await Task.Yield(); return; };
                Action d = delegate { return 2; };
                Action a2 = async () => { await Task.Yield(); return 1; };
                Func<ValueTask> v = async () => { await Task.Yield(); return 1; };
                Func<Task<string?>> s = async () => { await Task.Yield(); return; };
                Func<List<string?>> t = () => { return; };
                Action w;
                w = () => { return 5; };
                var c = (Action)(() => { return 3; });
                Handler hd = x => { return x; };
                Job j = async () => { await Task.Yield(); return 2; };
                Func<string?> fs = () => { return; };
                Func<int?> fi = () => { return; };
                Func<int[]> fa = () => { return; };
                Func<Dictionary<string, int>> fd = () => { return; };
                Func<int> ok = () => { Action inner = () => { return; }; return 1; };
                Func<Task<int>> okAsync = async () => { await Task.Yield(); return 1; };
                xs.ForEach(x => { return; });
            }
        """))
    }

    fun testNullableAnnotationsInTheMessages() {
        assertEquals(listOf(
            "return -> CS0126: An object of a type convertible to 'string' is required",
            "return -> CS0126: An object of a type convertible to 'List<string?>' is required",
            "return -> CS0126: An object of a type convertible to 'Dictionary<string, int?>' is required",
            "return -> CS0126: An object of a type convertible to 'List<string?>' is required",
            "return -> CS0126: An object of a type convertible to 'string?[]' is required",
            "return -> CS0126: An object of a type convertible to 'int?[]' is required",
            "return -> CS0126: An object of a type convertible to '(string?, int)' is required",
            "return -> CS0126: An object of a type convertible to '(string? a, int b)' is required",
            "return -> CS0126: An object of a type convertible to 'List<string?>' is required",
            "return -> CS0127: Since 'Probe.P1(string?)' returns void, a return keyword must not be followed by an object expression",
            "return -> CS0127: Since 'Probe.P2(List<string?>, int?)' returns void, a return keyword must not be followed by an object expression",
            "return -> CS0127: Since 'Probe.P3((string? a, int b), string?[])' returns void, a return keyword must not be followed by an object expression",
            "await -> CS4032: The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task<string>'.",
            "await -> CS4032: The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task<List<string?>>'.",
            "await -> CS4032: The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task<string?[]>'.",
            "await -> CS4032: The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task<int?>'.",
            "await -> CS4032: The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task<Task<string?>>'.",
        ), errors("""
            public string? R1() { return; }
            public List<string?> R2() { return; }
            public Dictionary<string, int?> R3() { return; }
            public async Task<List<string?>?> R4() { await Task.Yield(); return; }
            public string?[]? R5() { return; }
            public int?[] R6() { return; }
            public (string?, int) R7() { return; }
            public (string? a, int b) R9() { return; }
            public System.Collections.Generic.List<string?> R8() { return; }
            public void P1(string? s) { return 1; }
            public void P2(List<string?> s, int? i) { return 1; }
            public void P3((string? a, int b) t, string?[] arr) { return 1; }
            public string? N1() { await Task.Yield(); return null; }
            public List<string?> N2() { await Task.Yield(); return new(); }
            public string?[] N3() { await Task.Yield(); return new string?[0]; }
            public int? N4() { await Task.Yield(); return null; }
            public Task<string?> N5() { await Task.Yield(); return Task.FromResult<string?>(null); }
        """))
    }
}
