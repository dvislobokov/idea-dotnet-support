package io.github.dotnetsupport

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.find.usages.api.PsiUsage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usages.Usage
import com.intellij.usages.UsageGroup
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewSettings
import com.intellij.usages.impl.rules.UsageTypeGroupingRule
import com.intellij.usages.rules.UsageGroupingRule
import io.github.dotnetsupport.lang.CSharpDeclarations
import io.github.dotnetsupport.lang.CSharpMemberGroupRuleProvider
import io.github.dotnetsupport.lang.CSharpUsageGroupingRuleProvider
import io.github.dotnetsupport.lang.CSharpUsageKind
import io.github.dotnetsupport.lang.CSharpUsageKind.*
import io.github.dotnetsupport.lang.CSharpUsageKinds
import io.github.dotnetsupport.lang.CSharpUsages
import io.github.dotnetsupport.lang.NativeCSharpUsageKinds
import io.github.dotnetsupport.lang.CSharpFeature
import java.io.File

/**
 * Find Usages with grouping, as in Rider: the kind of a usage by its tokens, the type and member it stands in, and both on the answers of
 * the real server (`tools/roslyn-lsp/capture_references.py`, fixtures in `roslyn/capture-5.12-references`), checked against what the
 * server says about the same usages to a Visual Studio client and in `documentHighlight`.
 */
class CSharpFindUsagesTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The kind of the usage marked with `|X|` (the bars are removed before the text is classified) by the tokens and by the tree. */
    private fun kinds(marked: String): Pair<CSharpUsageKind, CSharpUsageKind> {
        val start = marked.indexOf('|')
        val end = marked.indexOf('|', start + 1) - 1
        val text = marked.replaceFirst("|", "").replaceFirst("|", "")
        return CSharpUsageKinds.classify(text, TextRange(start, end)) to NativeCSharpUsageKinds.classify(text, TextRange(start, end))
    }

    /** The kind both classifiers agree on (`USAGE_KINDS` ROSLYN | NATIVE): every case of the heuristics holds on the tree as well. */
    private fun kind(marked: String): CSharpUsageKind {
        val (heuristic, native) = kinds(marked)
        assertEquals("the tree and the tokens disagree on $marked", heuristic, native)
        return heuristic
    }

    private fun member(body: String) = "class C\n{\n    void M()\n    {\n        $body\n    }\n}\n"

    fun testReadsAndWrites() {
        assertEquals(WRITE, kind(member("|count| = 1;")))
        assertEquals(WRITE, kind(member("this.|count| = 1;")))
        assertEquals(WRITE, kind(member("other.|count| += 2;")))
        assertEquals(WRITE, kind(member("|count| ??= 2;")))
        assertEquals(WRITE, kind(member("|count| <<= 2;")))
        assertEquals(WRITE, kind(member("|count|++;")))
        assertEquals(WRITE, kind(member("--|count|;")))
        assertEquals(WRITE, kind(member("Bump(ref |count|);")))
        assertEquals(WRITE, kind(member("Reset(out |count|);")))
        assertEquals(WRITE, kind(member("var o = new Order { |Count| = 3 };")))
        assertEquals(READ, kind(member("Peek(in |count|);")))
        assertEquals(READ, kind(member("var x = |count| + 1;")))
        assertEquals(READ, kind(member("if (|count| == 1) return;")))
        assertEquals(READ, kind(member("if (|count| >= 1) return;")))
        assertEquals(READ, kind(member("if (a > |count|) return;")))
        assertEquals(READ, kind(member("Func<int> f = () => |count|;")))
        assertEquals(READ, kind(member("|count|.ToString();")))
        assertEquals(READ, kind(member("|items|[0] = 1;")))
        assertEquals(READ, kind(member("var y = a ? |count| : 2;")))
        assertEquals(READ, kind(member("foreach (var x in |items|) { }")))
        assertEquals(READ, kind(member("var s = $\"{|count|} items\";")))
    }

    fun testInvocationsAndNames() {
        assertEquals(INVOCATION, kind(member("|Record|(\"a\");")))
        assertEquals(INVOCATION, kind(member("UsageLog.|Record|(\"a\");")))
        assertEquals(INVOCATION, kind(member("log?.|Record|(\"a\");")))
        assertEquals(INVOCATION, kind(member("var x = |Make|<int>();")))
        assertEquals(INVOCATION, kind(member("var s = $\"{|Format|(1)}\";")))
        assertEquals(READ, kind(member("list.ForEach(|Record|);")))
        assertEquals(NAMEOF, kind(member("var n = nameof(|Count|);")))
        assertEquals(NAMEOF, kind(member("var n = nameof(Order.|Count|);")))
        assertEquals(NAMEOF, kind(member("var n = nameof(|Order|.Count);")))
        assertEquals(COMMENT, kind("/// <see cref=\"|Record|\"/>\nclass C { }\n"))
    }

    fun testTypeUsages() {
        assertEquals(NEW, kind(member("var o = new |Order|();")))
        assertEquals(NEW, kind(member("var o = new Shop.|Order|(1);")))
        assertEquals(TYPEOF, kind(member("var t = typeof(|Order|);")))
        assertEquals(TYPE_CHECK, kind(member("if (x is |Order|) return;")))
        assertEquals(TYPE_CHECK, kind(member("if (x is not |Order|) return;")))
        assertEquals(TYPE_CHECK, kind(member("var o = x as |Order|;")))
        assertEquals(CAST, kind(member("var o = (|Order|)x;")))
        assertEquals(INVOCATION, kind(member("Save(|Make|(x));")))
        assertEquals(READ, kind(member("Save(|order|);")))
        assertEquals(TYPE_ARGUMENT, kind(member("var l = new List<|Order|>();")))
        assertEquals(TYPE_ARGUMENT, kind(member("Dictionary<string, |Order|> d = new();")))
        assertEquals(DECLARATION_TYPE, kind(member("|Order| order = Load();")))
        assertEquals(DECLARATION_TYPE, kind(member("|Order|? order = null;")))
        assertEquals(DECLARATION_TYPE, kind(member("|Order|[] orders = [];")))
        assertEquals(DECLARATION_TYPE, kind("class C\n{\n    void M(|Order| order) { }\n}\n"))
        assertEquals(DECLARATION_TYPE, kind("class C\n{\n    private |Order| _order;\n}\n"))
        assertEquals(BASE_TYPE, kind("class C : |Order| { }\n"))
        assertEquals(BASE_TYPE, kind("class C : Base, |IOrder| { }\n"))
        assertEquals(BASE_TYPE, kind("record C(int X) : |Order|(X);\n"))
        assertEquals(BASE_TYPE, kind("interface I<T> : |IOrder|<T> where T : class { }\n"))
        assertEquals(USING, kind("using Shop.|Orders|;\nclass C { }\n"))
        assertEquals(USING, kind("using static Shop.|Pricing|;\nclass C { }\n"))
        assertEquals(USING, kind("global using |Shop|;\n"))
        assertEquals(READ, kind(member("using (var s = |Open|) { }")))
        assertEquals(ATTRIBUTE, kind("[|Obsolete|]\nclass C { }\n"))
        assertEquals(ATTRIBUTE, kind("[Serializable, |UsageNote|(\"x\")]\nclass C { }\n"))
        assertEquals(ATTRIBUTE, kind("class C\n{\n    [return: |NotNull|]\n    string M() => \"\";\n}\n"))
        assertEquals(READ, kind(member("var x = a[|Index|];")))
        assertEquals(ATTRIBUTE, kind("class C\n{\n    void M([|FromBody|] Order order) { }\n}\n"))
        assertEquals(READ, kind(member("Save([|first|, second]);")))
    }

    fun testDeclarations() {
        assertEquals(DECLARATION, kind("class |Order| { }\n"))
        assertEquals(DECLARATION, kind("class C\n{\n    public int |Count| { get; set; }\n}\n"))
        assertEquals(DECLARATION, kind("class C\n{\n    public |C|() { }\n}\n"))
        assertEquals(DECLARATION, kind(member("var |total| = 0;")))
        assertEquals(DECLARATION, kind(member("int? |total| = null;")))
        assertEquals(DECLARATION, kind(member("List<int> |items| = [];")))
        assertEquals(DECLARATION, kind("class C\n{\n    void M(int |value|) { }\n}\n"))
        assertEquals(DECLARATION, kind(member("foreach (var |item| in items) { }")))
        assertEquals(DECLARATION, kind(member("int |Twice|(int x) => x * 2;")))
        assertEquals(READ, kind(member("var q = from x in xs select |x|;")))
    }

    fun testTheMemberAUsageStandsIn() {
        val text = """
            namespace Shop;
            [Note(nameof(Order.Total))]
            public class Order
            {
                public int Total;
                public Order() { Total = 0; }
                public int Read() => Total;
                public class Line { public void Add() => Total++; }
            }
        """.trimIndent()
        val structure = CSharpDeclarations.scan(text)
        fun at(marker: String, nth: Int = 0) = generateSequence(text.indexOf(marker)) { text.indexOf(marker, it + 1).takeIf { found -> found >= 0 } }.drop(nth).first()
        fun path(offset: Int) = CSharpUsages.containers(structure, offset).map { it.kind.title + " " + it.name }
        assertEquals("the namespace is left out; an attribute belongs to the type", listOf("class Order"), path(at("Order.Total")))
        assertEquals(listOf("class Order", "field Total"), path(at("Total;")))
        assertEquals(listOf("class Order", "constructor Order"), path(at("Total = 0")))
        assertEquals(listOf("class Order", "method Read"), path(at("Total;", 1)))
        assertEquals(listOf("class Order", "class Line", "method Add"), path(at("Total++")))
    }

    /** Constructs added with the classifier on the tree: both classifiers answer the same. */
    fun testMoreConstructsBothAgreeOn() {
        assertEquals(WRITE, kind(member("x?.|Count| = 1;")))
        assertEquals(INVOCATION, kind(member("var r = a?.|Run|();")))
        assertEquals(READ, kind(member("|a|?.Run();")))
        assertEquals(INVOCATION, kind(member("a.|Make|<int>();")))
        assertEquals(WRITE, kind(member("ref int r = ref |x|;")))
        assertEquals(READ, kind(member("var t = (|count|, 1);")))
        assertEquals(READ, kind(member("|Order|.Create();")))
        assertEquals(CAST, kind(member("var o = (|Order|?)x;")))
        assertEquals(NEW, kind(member("var a = new |Order|[3];")))
        assertEquals(TYPE_ARGUMENT, kind(member("var t = typeof(List<|Order|>);")))
        assertEquals(TYPE_CHECK, kind(member("if (x is |Order| o) return;")))
        assertEquals(TYPE_CHECK, kind(member("if (x is |Shop|.Order) return;")))
        assertEquals(READ, kind(member("if (x is { |Count|: 3 }) return;")))
        assertEquals(DECLARATION_TYPE, kind(member("try { } catch (|IOException| e) { }")))
        assertEquals(DECLARATION, kind(member("try { } catch (IOException |e|) { }")))
        assertEquals(DECLARATION_TYPE, kind(member("(|Order| a, int b) t = default;")))
        assertEquals(DECLARATION_TYPE, kind("class C\n{\n    public |Order| Current { get; set; }\n}\n"))
        assertEquals(DECLARATION_TYPE, kind("class C\n{\n    public |Order| Make() => null;\n}\n"))
        assertEquals(WRITE, kind("[Note(|Text| = \"a\")]\nclass C { }\n"))
        assertEquals(USING, kind("using |Alias| = Shop.Orders;\nclass C { }\n"))
        assertEquals(USING, kind("using Alias = Shop.|Orders|;\nclass C { }\n"))
        assertEquals(NAMEOF, kind("[Note(nameof(|Order|.Total))]\nclass C { }\n"))
        assertEquals(COMMENT, kind("// see |Order|\nclass C { }\n"))
        assertEquals(STRING, kind(member("var s = \"|Order|\";")))
    }

    /** Where the tree knows what the tokens can only guess: (tokens, tree). */
    fun testWhereTheTreeKnowsBetter() {
        // deconstruction writes every element of the tuple
        assertEquals(READ to WRITE, kinds(member("(|a|, b) = (1, 2);")))
        assertEquals(READ to WRITE, kinds(member("(a, (|b|, c)) = (1, (2, 3));")))
        assertEquals(READ to READ, kinds(member("var t = (|a|, b);")))
        // `x! = 1`: the tokens see `!=`
        assertEquals(READ to WRITE, kinds(member("|x|! = 1;")))
        // a member given a nested initializer is read (its getter runs), the members inside are set
        assertEquals(WRITE to READ, kinds(member("var o = new Order { |Lines| = { 1, 2 } };")))
        assertEquals(WRITE to READ, kinds(member("var o = new Order { |Customer| = { Name = n } };")))
        assertEquals(WRITE to WRITE, kinds(member("var o = new Order { Customer = { |Name| = n } };")))
        // the text of an interpolated string is a string, only its holes are code
        assertEquals(READ to STRING, kinds(member("var s = \$\"Total: |Count|\";")))
        assertEquals(INVOCATION to STRING, kinds(member("var s = \$\"Call |Format|(1)\";")))
        assertEquals(READ to READ, kinds(member("var s = \$\"Total: {|Count|}\";")))
        // `out Order o` declares `o` of the type `Order`, it writes nothing to `Order`
        assertEquals(WRITE to DECLARATION_TYPE, kinds(member("Load(out |Order| o);")))
        assertEquals(WRITE to WRITE, kinds(member("Load(out |o|);")))
        assertEquals(WRITE to DECLARATION_TYPE, kinds("class C\n{\n    bool Load(out |Order| o) { o = null; return true; }\n}\n"))
        // a type in a pattern of a switch is a type check as after `is`
        assertEquals(DECLARATION_TYPE to TYPE_CHECK, kinds(member("var y = x switch { |Order| o => 1, _ => 0 };")))
        assertEquals(READ to TYPE_CHECK, kinds(member("var y = x switch { |Order| { Total: > 0 } => 1, _ => 0 };")))
        // what is declared without a type before it: query variables, lambda parameters, members of an anonymous type, a namespace
        assertEquals(READ to DECLARATION, kinds(member("var q = from |x| in xs select x;")))
        assertEquals(WRITE to DECLARATION, kinds(member("var q = from x in xs let |y| = x select y;")))
        assertEquals(READ to DECLARATION, kinds(member("var q = xs.Select(|x| => x);")))
        assertEquals(WRITE to DECLARATION, kinds(member("var a = new { |Total| = 1 };")))
        assertEquals(READ to DECLARATION, kinds("namespace |Shop|;\nclass C { }\n"))
    }

    /** The same usages on both trees: the heuristic tree of `SYNTAX_TREE` = ROSLYN gets the heuristic kinds whatever `USAGE_KINDS` says. */


    /** Half-typed code: an answer for every offset, no exception. */
    fun testBrokenCode() {
        val texts = listOf(
            "class C { void M() { var x = new ; Run(ref ); (a, = 1; nameof(; typeof(Order; x is ; [Obs class",
            "namespace ; using ; class : { int Count { get; set } void M( { Count++ ; Make<int>(; } }",
            "/// <see cref=\"Run(\"/>\nclass C { string s = \$\"{x\"; void M() { from x in select ; } }",
            "record R(int X) : Base(X, ; interface I<T> : where T : { } enum E { A = , B }",
        )
        for (text in texts) {
            val file = io.github.dotnetsupport.lang.NativeCSharpSyntaxModel.parse(text)
            val analysis = NativeCSharpUsageKinds.Analysis(file)
            for (offset in 0..text.length) assertNotNull(text, analysis.kindOf(TextRange(offset, offset)))
        }
    }

    /**
     * A text usage of the file and the range (what a usage search that knows no PSI element gives), wrapped as Find Usages wraps every usage of a search target (the classes are internal to Kotlin, hence reflection).
     */
    private fun textUsage(file: PsiFile, range: TextRange): Usage {
        val loader = PsiUsage::class.java.classLoader
        val info = loader.loadClass("com.intellij.find.usages.impl.PsiUsage2UsageInfo").getConstructor(PsiUsage::class.java).newInstance(PsiUsage.textUsage(file, range))
        return loader.loadClass("com.intellij.find.usages.impl.Psi2UsageInfo2UsageAdapter").getConstructor(info.javaClass).newInstance(info) as Usage
    }

    /** The rules as the Usages view runs them, on a text usage. */
    fun testRulesOnTextUsages() {
        val file = myFixture.addFileToProject("FindUsagesRules/Console/Sample.cs", """
            namespace Playground;
            public class Sample
            {
                public int Counter;
                public void Write() { Counter = 5; }
                public int Read() => Counter;
            }
        """.trimIndent())
        myFixture.addFileToProject("FindUsagesRules/Console/Console.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />")
        val text = file.text
        val write = text.indexOf("Counter = 5")
        val usage = textUsage(file, TextRange(write, write + "Counter".length))

        assertNull("the platform's own rule gets no type from a text usage: why the plugin brings its own", UsageTypeGroupingRule().getParentGroupsFor(usage, UsageTarget.EMPTY_ARRAY).singleOrNull())

        val settings = UsageViewSettings().apply { isGroupByUsageType = true; isGroupByModule = true }
        val rules = CSharpUsageGroupingRuleProvider().getActiveRules(project, settings, null)
        assertEquals(2, rules.size)
        val groups = rules.flatMap { it.getParentGroupsFor(usage, UsageTarget.EMPTY_ARRAY) }.map(UsageGroup::getPresentableGroupText)
        assertEquals(listOf("Write access", "Console"), groups)
        assertEquals(0, CSharpUsageGroupingRuleProvider().getActiveRules(project, UsageViewSettings().apply { isGroupByUsageType = false; isGroupByModule = false }, null).size)

        val member: UsageGroupingRule = CSharpMemberGroupRuleProvider().getUsageGroupingRule(project)
        assertEquals("a member with its parameters, as in the Structure view", listOf("Sample", "Write()"), member.getParentGroupsFor(usage, UsageTarget.EMPTY_ARRAY).map(UsageGroup::getPresentableGroupText))
        val read = text.indexOf("Counter;", write)
        val other = textUsage(file, TextRange(read, read + "Counter".length))
        val (sampleOfWrite, _) = member.getParentGroupsFor(usage, UsageTarget.EMPTY_ARRAY)
        val (sampleOfRead, readGroup) = member.getParentGroupsFor(other, UsageTarget.EMPTY_ARRAY)
        assertEquals("one group of the type for the usages in its members", sampleOfWrite, sampleOfRead)
        assertEquals("Read()", readGroup.presentableGroupText)
    }
}
