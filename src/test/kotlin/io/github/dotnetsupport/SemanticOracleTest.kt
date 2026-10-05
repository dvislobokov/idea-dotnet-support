package io.github.dotnetsupport

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpParseOptions
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import java.io.StringReader

/**
 * The reader of `roslyndump semantics` and the comparison of the semantic gate ([CSharpSemanticGate], CSHARP_PSI_MIGRATION.md task C0)
 * on a committed dump: `src/test/resources/semantic/sample.txt` of the sources in `semantic/sample` (made with
 * `roslyndump semantics sample --assembly Sample --out sample.txt` in that folder; no `dotnet` here).
 */
class SemanticOracleTest : BasePlatformTestCase() {
    private val dump by lazy { SemanticDump.read(StringReader(resource("semantic/sample.txt"))) }

    private fun resource(path: String): String = javaClass.classLoader.getResource(path)!!.readText()

    private fun file(path: String) = dump.files.single { it.path == path }

    private fun name(path: String, text: String, nth: Int = 0) = file(path).names.filter { it.text == text }[nth]

    fun testReadsHeaderAndRecords() {
        assertEquals("Sample", dump.header.assembly)
        assertEquals("preview", dump.header.languageVersion)
        assertEquals(listOf("App/Model.cs", "App/Orders.cs"), dump.header.sources.map { it.path })
        assertTrue(dump.header.references.contains("System.Runtime.dll"))
        assertEquals(listOf("App/Model.cs", "App/Orders.cs"), dump.files.map { it.path })

        val log = name("App/Orders.cs", "Log")
        assertFalse(log.declares)
        assertEquals(setOf("inh"), log.flags)
        assertEquals("Method.Ordinary", log.symbols.single().kind)
        assertEquals("M:App.Base.Log(System.String)", log.symbols.single().id)
        assertEquals(listOf("App/Model.cs:213"), log.symbols.single().declarations)

        val count = name("App/Orders.cs", "Count", 1)
        assertTrue(count.has("acc"))
        assertTrue(count.symbols.single().isFromAssembly)
        assertTrue(name("App/Orders.cs", "Missing").symbols.isEmpty())

        val lambda = file("App/Orders.cs").expressions.single { it.kind == "SimpleLambdaExpression" }
        assertNull(lambda.type)
        assertEquals("System.Func<int, int>", lambda.convertedType)
        val literal = file("App/Orders.cs").expressions.first { it.kind == "NumericLiteralExpression" }
        assertEquals("decimal", literal.type)
        assertEquals("decimal", literal.convertedType)
        val diagnostic = file("App/Orders.cs").diagnostics.single()
        assertEquals("CS0103", diagnostic.code)
        assertTrue(diagnostic.isError)
    }

    fun testUnescapesPlacesAndCandidates() {
        val text = "# roslyndump semantics 1\nS\toptions\t0\t13.0\tA;B\nS\tsrc\tobj/.NETCoreApp,Version=v9.0.cs\t0\nF\tA.cs\n" +
            "N\t5\tM\tref\tMethod.Ordinary|Method.Ordinary\tM:C.M(System.Int32)|M:C.M\tobj/.NETCoreApp%2CVersion=v9.0.cs:3|a.cs:1,b.cs:2\tcand=OverloadResolutionFailure\n"
        val read = SemanticDump.read(StringReader(text))
        assertEquals(listOf("A", "B"), read.header.sources.single().defines)
        val name = read.files.single().names.single()
        assertEquals("OverloadResolutionFailure", name.candidateReason)
        assertFalse(name.isBound)
        assertEquals(listOf(listOf("obj/.NETCoreApp,Version=v9.0.cs:3"), listOf("a.cs:1", "b.cs:2")), name.symbols.map { it.declarations })
        assertEquals("candidates", SemanticComparison.nameCategory(name))
        assertTrue(SemanticComparison.matches(SemanticAnswer(listOf("b.cs:2")), name.symbols))
        assertTrue(SemanticComparison.matches(SemanticAnswer(listOf("a.cs:1", "b.cs:2")), name.symbols))
        assertFalse(SemanticComparison.matches(SemanticAnswer(listOf("a.cs:1", "c.cs:2")), name.symbols))
        assertTrue(SemanticComparison.matches(SemanticAnswer(emptyList(), "M:C.M"), name.symbols))
    }

    fun testCategories() {
        fun category(text: String, nth: Int = 0) = SemanticComparison.nameCategory(name("App/Orders.cs", text, nth))
        assertEquals(SemanticComparison.DECLARATIONS, category("sum"))
        assertEquals("locals", category("sum", 1))
        assertEquals("parameters", category("x", 1))
        assertEquals("labels", category("done"))
        assertEquals("members of own type", category("items", 1))
        assertEquals("inherited members", category("Log"))
        assertEquals("types: solution", category("Base"))
        assertEquals("types: assemblies", category("List"))
        assertEquals("member access: assemblies", category("Count", 1))
        assertEquals("member access: solution", category("Price"))
        assertEquals("namespaces", category("System"))
        assertEquals("var / keywords", category("var"))
        assertNull(category("Missing"))
        assertEquals("operators", SemanticComparison.typeCategory("AddAssignmentExpression"))
        assertEquals("literals", SemanticComparison.typeCategory("InterpolatedStringExpression"))
    }

    /** Roslyn's own answers score 100 %, no answers 0 %, answers for what Roslyn does not bind are counted apart. */
    fun testComparisonScoresAnswers() {
        val perfect = SemanticComparison()
        val none = SemanticComparison()
        for (f in dump.files) {
            perfect.add(f, object : SemanticAnswers {
                override fun symbolAt(offset: Int) = f.names.single { it.offset == offset }.symbols.firstOrNull()?.let { SemanticAnswer(it.sourceDeclarations, it.id) }
                override fun typeOf(start: Int, end: Int) = f.expressions.first { it.start == start && it.end == end }.type
                override fun diagnostics() = f.diagnostics
            })
            none.add(f, object : SemanticAnswers {
                override fun symbolAt(offset: Int): SemanticAnswer? = null
                override fun typeOf(start: Int, end: Int): String? = null
            })
        }
        val scored = perfect.names.values.sumOf { it.total }
        assertEquals(53, scored)
        assertEquals(scored, perfect.names.values.sumOf { it.correct })
        assertEquals(perfect.types.values.sumOf { it.total }, perfect.types.values.sumOf { it.correct })
        assertEquals(1, perfect.unboundNames)
        assertEquals(0, perfect.answeredUnbound)
        assertEquals(1, perfect.matchedErrors)
        assertEquals(0, none.names.values.sumOf { it.correct })
        assertEquals(scored, none.names.values.sumOf { it.unresolved })
        assertTrue(perfect.report("sample"), perfect.report("sample").contains("100.0"))
        assertEquals(perfect.names.getValue("locals").correct, perfect.metrics()["names.locals"])
    }

    /** The baseline of the gate on the sample: syntax binds locals, parameters, labels, own members, types of the solution and declarations. */
    fun testSyntacticBaseline() {
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val base = myFixture.tempDirFixture.findOrCreateDir("semanticSample")
        try {
            val files = HashMap<String, VirtualFile>()
            for (source in dump.header.sources) {
                val file = myFixture.tempDirFixture.createFile("semanticSample/${source.path}", resource("semantic/sample/${source.path}").replace("\r\n", "\n"))
                CSharpParseOptions.put(file, source.defines.toSet(), source.languageVersion)
                files[source.path] = file
            }
            val comparison = SemanticComparison()
            for (record in dump.files) comparison.add(record, SemanticModelAnswers(SyntacticSemanticModel, PsiManager.getInstance(project).findFile(files.getValue(record.path))!!, base))
            val report = comparison.report("sample") + comparison.examples()
            fun tally(category: String) = comparison.names.getValue(category)
            // inherited members came with the one resolver of 0.1.53 (base classes of the solution from the stubs)
            for (category in listOf("locals", "parameters", "labels", "members of own type", "inherited members", "types: solution", SemanticComparison.DECLARATIONS)) {
                assertEquals("$category\n$report", tally(category).total, tally(category).correct)
            }
            assertEquals(report, 0, comparison.names.values.sumOf { it.wrong })
            val literals = comparison.types.getValue("literals")
            assertEquals(report, literals.total, literals.correct)
            assertEquals(report, 0, comparison.types.values.sumOf { it.wrong })
        } finally {
            WriteAction.run<Throwable> { base.delete(this) }
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        }
    }

    fun testNumericLiteralTypes() {
        val cases = mapOf("1" to "int", "4294967295" to "uint", "4294967296" to "long", "10L" to "long", "1u" to "uint", "1UL" to "ulong", "0xFFFF_FFFF" to "uint",
            "1.5" to "double", "1f" to "float", "2m" to "decimal", "1e3" to "double", "0b101" to "int", "9223372036854775808" to "ulong")
        for ((text, type) in cases) assertEquals(text, type, SyntacticSemanticModel.numericType(text))
    }
}
