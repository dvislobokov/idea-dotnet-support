package io.github.dotnetsupport

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpGotoDeclarationHandler
import io.github.dotnetsupport.lang.CSharpHighlightUsagesHandlerFactory
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * NAVIGATION on the native tree (CSHARP_PSI_MIGRATION.md, step 9, task A2): Go to Declaration / Ctrl + hover and the usages under the caret
 * by syntax and the scopes of C#. A case marks the usage with `/*U:tag*/` right before the name and every expected target with
 * `/*D:tag*/` right before the declared name; no `/*D:tag*/` means "not resolved", left to the server.
 */
class CSharpNavigationNativeTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var dirs = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.NAVIGATION, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            settings.state.enabled = true
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun addFiles(texts: Array<out String>): List<PsiFile> {
        val dir = "NavNative${dirs++}"
        return texts.mapIndexed { i, text -> myFixture.addFileToProject("$dir/F$i.cs", text) }
    }

    /** The offsets right after the markers `/*kind:tag1,tag2*/` that carry [tag]. */
    private fun offsets(text: String, kind: Char, tag: String): List<Int> =
        Regex("""/\*$kind:([\w,]+)\*/""").findAll(text).filter { tag in it.groupValues[1].split(',') }.map { it.range.last + 1 }.toList()

    /** Expected and actual targets of `/*U:tag*/` as `file:offset`; actual null when the handler gives nothing. */
    private fun targets(files: List<PsiFile>, tag: String): Pair<Set<String>, Set<String>?> {
        val expected = files.flatMap { file -> offsets(file.text, 'D', tag).map { "${file.name}:$it" } }.toSet()
        val usageFile = files.single { offsets(it.text, 'U', tag).isNotEmpty() }
        val offset = offsets(usageFile.text, 'U', tag).single()
        val found = CSharpGotoDeclarationHandler().getGotoDeclarationTargets(usageFile.findElementAt(offset), offset, null)
        return expected to found?.map { "${it.containingFile.name}:${it.textOffset}" }?.toSet()
    }

    /** Every tag of the files resolves to its `/*D:tag*/` (none: to nothing). */
    private fun check(vararg texts: String) {
        val files = addFiles(texts)
        val tags = files.flatMap { Regex("""/\*U:(\w+)\*/""").findAll(it.text).map { m -> m.groupValues[1] }.toList() }
        assertTrue("no usages marked", tags.isNotEmpty())
        for (tag in tags) {
            val (expected, actual) = targets(files, tag)
            assertEquals("tag $tag", expected.ifEmpty { null }, actual)
        }
    }

    // ---- locals

    fun testLocalsOfEveryKind() = check(
        """
        using System;
        class Locals
        {
            void M(object o, string s, (int, int) pair, System.Collections.Generic.Dictionary<int, string> map)
            {
                var /*D:v*/total = 1;
                Use(/*U:v*/total);
                if (int.TryParse(s, out var /*D:out*/n)) { }
                Use(/*U:out*/n);
                if (o is string /*D:pat*/text && /*U:pat*/text.Length > 0) { }
                foreach (var /*D:each*/item in map) Use(/*U:each*/item);
                for (int /*D:for*/i = 0; /*U:for*/i < 3; i++) { }
                using (var /*D:using*/stream = new System.IO.MemoryStream()) Use(/*U:using*/stream);
                try { } catch (Exception /*D:catch,catch2*/ex) when (/*U:catch2*/ex != null) { Use(/*U:catch*/ex); }
                var (/*D:deconstruct*/a, b) = pair;
                Use(/*U:deconstruct*/a);
                foreach (var (/*D:eachKey*/key, value) in map) Use(/*U:eachKey*/key);
                switch (o) { case int /*D:case,case2*/k when /*U:case2*/k > 0: Use(/*U:case*/k); break; }
                var r = o switch { string /*D:arm*/z => /*U:arm*/z, _ => "" };
            }
            void Use(object x) { }
        }
        """.trimIndent(),
    )

    fun testParameters() = check(
        """
        using System;
        class Order(int /*D:primary*/seed)
        {
            int Seed => /*U:primary*/seed;
            Order(int /*D:ctor*/count) : this(/*U:ctor*/count) { }
            int Total(int /*D:method*/discount) => /*U:method*/discount * 2;
            int this[int /*D:indexer*/index] => /*U:indexer*/index;
            void Lambdas()
            {
                Func<int, int> f = /*D:simple*/x => /*U:simple*/x + 1;
                Func<int, int, int> g = (int /*D:paren*/p, int q) => /*U:paren*/p + q;
                Func<int, int> h = delegate (int /*D:anon*/y) { return /*U:anon*/y; };
                int Local(int /*D:localParam*/w) => /*U:localParam*/w;
                Use(/*U:localFunction*/Twice(2));
                int /*D:localFunction*/Twice(int t) => t * 2;
            }
            void Use(int v) { }
        }
        record Point(int /*D:record*/X, int Y) { public int Sum => /*U:record*/X + Y; }
        """.trimIndent(),
    )

    fun testLabelsQueriesAndTypeParameters() = check(
        """
        using System.Linq;
        class Q</*D:constraint,classTypeParam*/T> where /*U:constraint*/T : class
        {
            void Jump()
            {
                /*D:label*/again:
                if (true) { goto /*U:label*/again; }
            }
            object Query(int[] xs) =>
                from /*D:from*/x in xs
                let /*D:let*/y = /*U:from*/x * 2
                join /*D:join*/z in xs on x equals z
                join w in xs on x equals w into /*D:into*/ws
                where /*U:let*/y > /*U:join*/z
                select /*U:into*/ws into /*D:cont*/g
                select /*U:cont*/g;
            /*U:classTypeParam*/T Get</*D:methodTypeParam*/U>(/*U:methodTypeParam*/U u) => default;
        }
        """.trimIndent(),
    )

    // ---- scopes

    fun testTheNearestScopeWins() = check(
        """
        using System;
        class Shadow
        {
            int /*D:field,fieldFromOther*/count;
            int /*D:other*/other;
            void M(int /*D:param*/count)
            {
                Use(/*U:param*/count);
                Func<int, int> f = /*D:lambda*/count => /*U:lambda*/count;
                { var other = 1; Use(other); }
                { Use(/*U:other*/other); }
                Use(this./*U:field*/count);
            }
            void N() => Use(/*U:fieldFromOther*/count);
            void Use(int x) { }
        }
        """.trimIndent(),
    )

    // ---- members and types

    fun testMembersOfTheTypeAndItsPartialParts() = check(
        """
        namespace Shop;
        partial class Cart
        {
            decimal /*D:prop*/Total { get; set; }
            void /*D:overload*/Add(int a) { }
            void /*D:overload*/Add(string s) { }
            void Use()
            {
                /*U:overload*/Add(1);
                var t = /*U:prop*/Total;
                /*U:partial*/Clear();
                /*U:nested*/Line l = null;
                int /*D:declarator*/x, y = /*U:declarator*/x;
            }
            class /*D:nested*/Line { }
        }
        """.trimIndent(),
        """
        namespace Shop
        {
            partial class Cart { void /*D:partial*/Clear() { } }
            partial class Other { void Clear() { } }
        }
        """.trimIndent(),
    )

    fun testTypesOfTheSolutionByName() = check(
        """
        using Shop.Models;
        using Shop.Other;
        namespace Shop.App
        {
            [/*U:attribute*/Note]
            class Usage
            {
                /*U:imported*/Customer c;
                /*U:sameNs*/Helper h;
                /*U:outerNs*/Root r;
                /*U:generic*/Box<int> b;
                /*U:plain*/Box p;
                /*U:twice*/Twin t;
                /*U:notImported*/Hidden x;
                /*U:missing*/Nowhere n;
                /*U:colorType*/Color Color { get; }
            }
        }
        """.trimIndent(),
        """
        namespace Shop.Models { class /*D:imported*/Customer { } class /*D:generic*/Box<T> { } class /*D:plain*/Box { } class /*D:twice*/Twin { } class /*D:colorType*/Color { } }
        namespace Shop.Other { class /*D:twice*/Twin { } class /*D:attribute*/NoteAttribute : System.Attribute { } }
        namespace Shop.App { class /*D:sameNs*/Helper { } }
        namespace Shop { class /*D:outerNs*/Root { } }
        namespace Shop.Secret { class Hidden { } }
        """.trimIndent(),
    )

    fun testWhatSyntaxCannotTellIsLeftToTheServer() = check(
        """
        class Left
        {
            // a member set by an object initializer of a type of the solution is resolved since the resolvers were merged (0.1.53); a member
            // of a value of known type and a named argument since the name resolution of layer 11a (0.1.57)
            int /*D:init,access,conditional*/Total;
            void M(Left other, int /*D:named*/count)
            {
                var a = other./*U:access*/Total;
                M(/*U:named*/count: 1, other: null);
                var b = new Left { /*U:init*/Total = 1 };
                var c = System./*U:qualified*/Console.Out;
                var d = /*U:unknown*/Missing;
                var e = other?./*U:conditional*/Total;
            }
        }
        """.trimIndent(),
    )

    /** Since the one resolver (0.1.53): what the colors already resolved — base classes, `base.X`, `Type.X`, `using static`. */
    fun testMembersOfBaseTypesAndQualifiers() = check(
        """
        using static Tools;
        static class Tools { public static int /*D:imported*/Twice(int x) => x * 2; }
        class Base { protected int /*D:inherited,viaBase*/ticks; public static int /*D:viaType*/Count; }
        class Derived : Base
        {
            void M()
            {
                /*U:inherited*/ticks++;
                base./*U:viaBase*/ticks = 0;
                var c = Base./*U:viaType*/Count + /*U:imported*/Twice(1);
            }
        }
        """.trimIndent(),
    )

    fun testScopesOfCSharp() = check(
        """
        using System.Linq;
        class Scopes
        {
            int /*D:field*/count;
            object M(int[] xs, object o)
            {
                Use(/*U:beforeDeclaration*/later);
                var /*D:beforeDeclaration*/later = 1;
                while (o is int /*D:whileVar*/w) Use(/*U:whileVar*/w);
                if (o is string /*D:ifVar*/s) { }
                Use(/*U:ifVar*/s.Length);
                { Use(/*U:field*/count); }
                return from x in xs group x by x into /*D:cont*/g select /*U:cont*/g.Count() + /*U:hidden*/x;
            }
            void Use(object v) { }
        }
        """.trimIndent(),
    )

    /** As the server (robot, 0.1.60): `new T()` goes to the declared constructor, to the type when there is none; static ones do not count. */
    fun testNewGoesToTheConstructor() = check(
        """
        namespace Ctors
        {
            class /*D:implicit*/Plain { }
            class /*D:asType*/WithCtor { static WithCtor() { } public /*D:declared,qualified*/WithCtor() { } }
            class Two { public /*D:overloads*/Two() { } public /*D:overloads*/Two(int x) { } }
            class /*D:primary*/Primary(int x) { public Primary() : this(0) { } }
            class Usage
            {
                /*U:asType*/WithCtor field;
                void M()
                {
                    var a = new /*U:implicit*/Plain();
                    var b = new /*U:declared*/WithCtor();
                    var c = new /*U:overloads*/Two(1);
                    var d = new Ctors./*U:qualified*/WithCtor();
                    var e = new /*U:primary*/Primary(1);
                }
            }
        }
        """.trimIndent(),
    )

    fun testTheDeclarationItselfGoesNowhere() = check(
        """
        class Self { void M() { var /*U:self*/x = 1; } int /*U:field*/F; }
        """.trimIndent(),
    )

    /** The EXPECT of `debug-playground/Console/Editor/Navigation.cs` (`TYPE:nav-*`), with the anchors of `goto_declaration.js`. */
    fun testThePlaygroundScenario() {
        val text = java.io.File("debug-playground/Console/Editor/Navigation.cs").readText().replace("\r\n", "\n")
        val files = addFiles(arrayOf(text, "namespace Playground.Editor; public class UsageSample { public int Read() => 0; }", "namespace Playground.Lib; public static class UsageLog { }"))
        val main = files[0]
        fun line(file: PsiFile, offset: Int) = file.text.substring(file.text.lastIndexOf('\n', offset - 1) + 1, file.text.indexOf('\n', offset).let { if (it < 0) file.text.length else it }).trim()
        fun goto(anchor: String): List<String>? {
            val at = main.text.indexOf(anchor)
            assertTrue(anchor, at >= 0)
            return CSharpGotoDeclarationHandler().getGotoDeclarationTargets(main.findElementAt(at), at, null)?.map { line(it.containingFile, it.textOffset) }
        }
        assertEquals(listOf("var total = 0;"), goto("total + parsed"))
        assertEquals(listOf("if (int.TryParse(input, out var parsed)) total += parsed;"), goto("parsed + first"))
        assertEquals(listOf("var (first, second) = (1, 2);"), goto("first + second"))
        assertEquals(listOf("public int Locals(object value, string input, List<int> items)"), goto("input.Length"))
        assertEquals(listOf("if (value is string text && text.Length > 0) total += text.Length;"), goto("text.Length > 0"))
        assertEquals(listOf("foreach (var item in items) total += item;"), goto("item;"))
        assertEquals(listOf("for (int index = 0; index < 2; index++) total += index;"), goto("index;"))
        assertEquals(listOf("Func<int, int> doubled = x => x * 2;"), goto("x * 2;"))
        assertEquals(listOf("int Twice(int n) => n * 2;"), goto("Twice(3) +"))
        assertEquals(listOf("int Twice(int n) => n * 2;"), goto("n * 2;"))
        assertEquals(listOf("public partial class Navigation(int seed)"), goto("seed;"))
        assertEquals(listOf("retry:"), goto("retry;"))
        assertEquals(listOf("let doubled = o * 2"), goto("doubled > 2"))
        assertEquals(listOf("var query = from o in numbers"), goto("o * 2"))
        assertEquals(listOf("group o by o % 2 into g"), goto("g.Key"))
        assertEquals(listOf("public List<T> Pick<T>(IEnumerable<T> source, int[] numbers)"), goto("T>();"))
        assertEquals(listOf("private int _count;"), goto("_count = Total"))
        assertEquals(listOf("public int Total { get; set; }"), goto("Total;\n        this"))
        assertEquals(listOf("private int _count;"), goto("_count++"))
        assertEquals(2, goto("Add(1)")!!.size)
        assertEquals(listOf("public void Reset() => _count = 0;"), goto("Reset();"))
        assertEquals(listOf("private sealed class Entry"), goto("Entry entry"))
        assertEquals(listOf("namespace Playground.Editor; public class UsageSample { public int Read() => 0; }"), goto("UsageSample sample"))
        assertEquals(listOf("namespace Playground.Lib; public static class UsageLog { }"), goto("UsageLog.Record"))
        assertNull("after the dot: the server's", goto("Record(\"navigation"))
        assertNull("after the dot: the server's", goto("Count;"))
    }

    fun testBrokenCodeDoesNotThrow() {
        val file = addFiles(arrayOf("class Broken { void M(int a { var x = ; if (x > a { foo(x, ; } int Y => x. ; class { from q in ")).single()
        val handler = CSharpGotoDeclarationHandler()
        for (leaf in SyntaxTraverser.psiTraverser(file).filter { it.firstChild == null }) handler.getGotoDeclarationTargets(leaf, leaf.textRange.startOffset, null)
    }

    fun testRoslynLeavesItToTheServer() {
        settings.setSource(CSharpFeature.NAVIGATION, CSharpFeatureSource.ROSLYN)
        val files = addFiles(arrayOf("class R { void M() { var /*D:a*/x = 1; System.Console.Write(/*U:a*/x); } }"))
        assertNull(targets(files, "a").second)
    }

    fun testCtrlBMovesTheCaret() {
        myFixture.configureByText("CtrlB.cs", "class C { void M() { var total = 1; Use(tot<caret>al); } void Use(int x) { } }")
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        assertEquals(myFixture.file.text.indexOf("total"), myFixture.editor.caretModel.offset)
    }

    // ---- usages in the file

    private fun highlights(text: String): Pair<List<String>, List<String>>? {
        myFixture.configureByText("Highlight${dirs++}.cs", text)
        val handler = CSharpHighlightUsagesHandlerFactory().createHighlightUsagesHandler(myFixture.editor, myFixture.file) ?: return null
        @Suppress("UNCHECKED_CAST")
        val typed = handler as HighlightUsagesHandlerBase<PsiElement>
        typed.computeUsages(typed.targets)
        fun render(ranges: List<com.intellij.openapi.util.TextRange>) = ranges.sortedBy { it.startOffset }.map { "${it.substring(myFixture.file.text)}@${it.startOffset}" }
        return render(typed.readUsages) to render(typed.writeUsages)
    }

    fun testUsagesOfALocalAreReadAndWritten() {
        val text = """
            class H
            {
                void A() { var x = 1; x = x + 1; x++; Use(out x); }
                void B() { var x = 2; Use(x); }
                void Use(out int v) { v = 0; }
                void Use(int v) { }
            }
        """.trimIndent()
        val (read, write) = highlights(text.replaceFirst("x = x", "x = <caret>x"))!!
        val xs = Regex("""\bx\b""").findAll(text).map { it.range.first }.filter { it < text.indexOf("void B") }.toList()
        assertEquals(5, xs.size)
        assertEquals("the second x of `x = x + 1` is read", listOf("x@${xs[2]}"), read)
        assertEquals("declaration, assignment, ++, out — and nothing of B", listOf("x@${xs[0]}", "x@${xs[1]}", "x@${xs[3]}", "x@${xs[4]}"), write)
    }

    fun testUsagesFromTheDeclarationAndOfAParameter() {
        val text = "class P { int M(int count) { count += 1; return count; } int N(int count) => count; }"
        val (read, write) = highlights(text.replaceFirst("int count", "int <caret>count"))!!
        val at = Regex("""\bcount\b""").findAll(text).map { it.range.first }.toList()
        assertEquals(listOf("count@${at[2]}"), read)
        assertEquals("the declaration and `+=`, nothing of N", listOf("count@${at[0]}", "count@${at[1]}"), write)
    }

    fun testMembersKeepTheTextOccurrences() {
        // a member is used through `a.B` too, which the tree does not resolve: the occurrences of the text, as before (the server once ready)
        val text = "class M { int Total; int Get(M o) => o.Total + Total; }"
        val (read, write) = highlights(text.replace("+ Total", "+ To<caret>tal"))!!
        assertEquals(Regex("""\bTotal\b""").findAll(text).map { "Total@${it.range.first}" }.toList(), read)
        assertEquals(emptyList<String>(), write)
    }
}
