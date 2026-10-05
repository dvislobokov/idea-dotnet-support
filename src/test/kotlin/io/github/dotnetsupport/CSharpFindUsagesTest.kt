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
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.roslyn.RoslynServer
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import java.io.File

/**
 * Find Usages with grouping, as in Rider: the kind of a usage by its tokens, the type and member it stands in, and both on the answers of
 * the real server (`tools/roslyn-lsp/capture_references.py`, fixtures in `roslyn/capture-5.12-references`), checked against what the
 * server says about the same usages to a Visual Studio client and in `documentHighlight`.
 */
class CSharpFindUsagesTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
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
    fun testTheSwitchPicksTheClassifier() {
        val settings = RoslynLanguageServerSettings.getInstance()
        val file = myFixture.addFileToProject("FindUsagesSwitch/Program.cs", "class P\n{\n    void M()\n    {\n        (a, b) = (1, 2);\n    }\n}\n")
        val range = file.text.indexOf("a,").let { TextRange(it, it + 1) }
        assertNotNull("the native tree by default", (file as io.github.dotnetsupport.lang.CSharpFile).compilationUnit)
        assertEquals("NATIVE by default (0.1.46): the tree", WRITE, CSharpUsages.analysis(file).kindOf(range))
        settings.setSource(CSharpFeature.USAGE_KINDS, CSharpFeatureSource.ROSLYN)
        try {
            assertEquals("ROSLYN: the tokens, at once", READ, CSharpUsages.analysis(file).kindOf(range))
            settings.state.enabled = false
            try {
                assertEquals("no server: the built-in one", WRITE, CSharpUsages.analysis(file).kindOf(range))
            } finally {
                settings.state.enabled = true
            }
        } finally {
            settings.setSource(CSharpFeature.USAGE_KINDS, CSharpFeatureSource.NATIVE)
        }
        assertEquals(WRITE, CSharpUsages.analysis(file).kindOf(range))
    }

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
     * What the Usages view gets from the LSP client of the platform for one location of `textDocument/references`: a text usage of the
     * file and the range, wrapped as Find Usages wraps every usage of a search target (the classes are internal to Kotlin, hence reflection).
     */
    private fun lspUsage(file: PsiFile, range: TextRange): Usage {
        val loader = PsiUsage::class.java.classLoader
        val info = loader.loadClass("com.intellij.find.usages.impl.PsiUsage2UsageInfo").getConstructor(PsiUsage::class.java).newInstance(PsiUsage.textUsage(file, range))
        return loader.loadClass("com.intellij.find.usages.impl.Psi2UsageInfo2UsageAdapter").getConstructor(info.javaClass).newInstance(info) as Usage
    }

    /** The rules as the Usages view runs them, on the very kind of usage the LSP client of the platform makes. */
    fun testRulesOnTheUsagesOfTheLspClient() {
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
        val usage = lspUsage(file, TextRange(write, write + "Counter".length))

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
        val other = lspUsage(file, TextRange(read, read + "Counter".length))
        val (sampleOfWrite, _) = member.getParentGroupsFor(usage, UsageTarget.EMPTY_ARRAY)
        val (sampleOfRead, readGroup) = member.getParentGroupsFor(other, UsageTarget.EMPTY_ARRAY)
        assertEquals("one group of the type for the usages in its members", sampleOfWrite, sampleOfRead)
        assertEquals("Read()", readGroup.presentableGroupText)
    }

    // ---- the answers of the real server ----

    private val directory = File(javaClass.getResource("/roslyn/capture-5.12-references/03-textDocument_references_field.json")!!.toURI()).parentFile
    private val sources = mapOf("FindUsages.cs" to File(directory, "FindUsages.cs.txt").readText(), "UsageLog.cs" to File(directory, "UsageLog.cs.txt").readText())
    private val handler = MessageJsonHandler(ServiceEndpoints.getSupportedMethods(RoslynServer::class.java))

    private fun record(name: String): JsonObject = JsonParser.parseString(File(directory, "$name.json").readText()).asJsonObject

    /** The answer read by lsp4j, as the LSP client of the IDE reads it. */
    @Suppress("UNCHECKED_CAST")
    private fun locations(name: String): List<Location> {
        handler.setMethodProvider { "textDocument/references" }
        val envelope = JsonObject().apply { addProperty("jsonrpc", "2.0"); addProperty("id", "1"); add("result", record(name).get("result")) }
        return (handler.parseMessage(envelope.toString()) as org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage).result as List<Location>
    }

    private fun offset(text: String, line: Int, character: Int): Int = text.split('\n').take(line).sumOf { it.length + 1 } + character

    /** A location of the server's answer and its kind; the tokens and the tree must agree on every one. */
    private fun classified(location: Location): Pair<String, CSharpUsageKind> {
        val name = location.uri.substringAfterLast('/')
        val text = sources.getValue(name)
        val start = offset(text, location.range.start.line, location.range.start.character)
        val end = offset(text, location.range.end.line, location.range.end.character)
        val line = "$name:${location.range.start.line + 1} " + text.split('\n')[location.range.start.line].trim()
        val heuristic = CSharpUsageKinds.classify(text, TextRange(start, end))
        assertEquals("the tree and the tokens disagree on $line", heuristic, NativeCSharpUsageKinds.classify(text, TextRange(start, end)))
        return line to heuristic
    }

    fun testWhatTheServerAnswers() {
        val field = locations("03-textDocument_references_field")
        assertTrue("bare locations: uri and range, nothing about the kind", record("03-textDocument_references_field").getAsJsonArray("result").all { (it as JsonObject).keySet() == setOf("uri", "range") })
        assertEquals("includeDeclaration adds the declaration and nothing else", field.size - 1, locations("04-textDocument_references_field_without_declaration").size)
        assertEquals("the declaration comes in no special place and with no mark", setOf(DECLARATION), field.map(::classified).filter { "public int Counter" in it.first }.map { it.second }.toSet())
        // a type: its constructor is a declaration too; an attribute with a primary constructor: the same range twice
        assertEquals(2, locations("07-textDocument_references_type").size - locations("08-textDocument_references_type_without_declaration").size)
        val attribute = locations("09-textDocument_references_attribute")
        val header = sources.getValue("FindUsages.cs").split('\n').indexOfFirst { "class UsageNoteAttribute" in it }
        assertEquals(2, attribute.count { it.range.start.line == header })
        assertEquals(ATTRIBUTE, classified(locations("10-textDocument_references_attribute_without_declaration").single()).second)
    }

    fun testTheKindsOfTheFieldAgreeWithWhatTheServerTellsVisualStudio() {
        // what Roslyn gives a client that says it is Visual Studio: VSInternalReferenceKind 3 Read, 4 Write, 5 Reference (ref / in / out), 6 Name (nameof)
        val visualStudio = record("vs-03-textDocument_references_field_visual_studio_client").getAsJsonArray("result").map { it as JsonObject }
        val server = visualStudio.associate { item ->
            val range = item.getAsJsonObject("_vs_location").getAsJsonObject("range").getAsJsonObject("start")
            val kinds = item.getAsJsonArray("_vs_kind").map { it.asInt }.toSet()
            val kind = when {
                kinds.isEmpty() -> DECLARATION
                6 in kinds -> NAMEOF
                4 in kinds -> WRITE
                else -> READ
            }
            (range.get("line").asInt to range.get("character").asInt) to kind
        }
        val ours = locations("03-textDocument_references_field").associate { (it.range.start.line to it.range.start.character) to classified(it).second }
        assertEquals(server, ours)

        // and documentHighlight of plain LSP: 1 Text (the declaration), 2 Read, 3 Write; nameof is a read there
        val highlights = record("11-textDocument_documentHighlight_field").getAsJsonArray("result").map { it as JsonObject }.associate { item ->
            val start = item.getAsJsonObject("range").getAsJsonObject("start")
            (start.get("line").asInt to start.get("character").asInt) to when (item.get("kind").asInt) { 1 -> DECLARATION; 3 -> WRITE; else -> READ }
        }
        assertEquals(highlights, ours.mapValues { (_, kind) -> if (kind == NAMEOF) READ else kind })
    }

    fun testTheContainingMembersAgreeWithTheServer() {
        val visualStudio = record("vs-03-textDocument_references_field_visual_studio_client").getAsJsonArray("result").map { it as JsonObject }
        val text = sources.getValue("FindUsages.cs")
        val structure = CSharpDeclarations.scan(text)
        for (item in visualStudio) {
            val start = item.getAsJsonObject("_vs_location").getAsJsonObject("range").getAsJsonObject("start")
            val path = CSharpUsages.containers(structure, offset(text, start.get("line").asInt, start.get("character").asInt))
            assertEquals(item.get("_vs_containingType").asString, path.first().name)
            // the server names a constructor `.ctor`; on the declaration of the field itself it names no member, and the plugin puts it under the field
            val member = item.get("_vs_containingMember")?.asString?.replace(".ctor", "UsageSample") ?: "Counter"
            assertEquals(member, path.last().name)
        }
    }

    fun testTheUsagesOfTheScenarioByKind() {
        fun kinds(name: String) = locations(name).map(::classified).groupingBy { it.second }.eachCount()
        assertEquals("the `<see cref>` in a doc comment is a reference to the server", mapOf(DECLARATION to 1, INVOCATION to 5, COMMENT to 1), kinds("05-textDocument_references_method"))
        assertEquals(mapOf(DECLARATION to 2, NEW to 1, BASE_TYPE to 1, NAMEOF to 1, DECLARATION_TYPE to 1, TYPEOF to 1, TYPE_CHECK to 1, TYPE_ARGUMENT to 1), kinds("07-textDocument_references_type"))
        assertEquals(mapOf(DECLARATION to 2, ATTRIBUTE to 1), kinds("09-textDocument_references_attribute"))
    }

    fun testTheAnswerToVisualStudioIsNotForThisClient() {
        val answer = runCatching { locations("vs-03-textDocument_references_field_visual_studio_client") }
        assertTrue("lsp4j cannot read the items Roslyn gives Visual Studio as locations: ${answer.getOrNull()?.firstOrNull()}",
            answer.isFailure || answer.getOrThrow().all { it.uri == null })
    }
}
