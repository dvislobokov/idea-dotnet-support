package io.github.dotnetsupport.csharp.lang.oracle

import io.github.dotnetsupport.csharp.CSharpTestUtil
import junit.framework.TestCase
import java.nio.file.Path

/**
 * Parsing of the `roslyndump` line format on a canned dump: `testData/oracle/snippets.tree`, written by
 * `roslyndump tree testData/oracle/snippets --out testData/oracle/snippets.tree`.
 */
class RoslynDumpFormatTest : TestCase() {

    private val files: List<DumpFile> by lazy { RoslynDump.read(cannedDump()) }

    fun testFilesAndRoots() {
        assertEquals(listOf("Directives.cs", "Recovery.cs"), files.map { it.path })
        val directives = files[0].root
        assertEquals(DumpNode("CompilationUnit", 0, 136, directives.children), directives)
        assertEquals(listOf("ClassDeclaration", "EndOfFileToken"), directives.children.map { it.kind })
        // Node counts agree with the tool's summary (`nodes=` counts nodes, trivia structure included): 35 and 25.
        fun nodes(f: DumpFile) = countNodes(f.root) + f.trivia.sumOf { t -> t.structure?.let { countNodes(it) } ?: 0 }
        assertEquals(35, nodes(files[0]))
        assertEquals(25, nodes(files[1]))
    }

    fun testTokenFields() {
        val tokens = flatten(files[0].root).filter { it.isToken }
        val variable = tokens.first { it.start == 46 }
        assertEquals(DumpNode("IdentifierToken", 46, 49, isToken = true, contextualKind = "VarKeyword"), variable)
        val missing = flatten(files[1].root).filter { it.missing }
        assertEquals(
            listOf(DumpNode("IdentifierToken", 61, 61, isToken = true, missing = true), DumpNode("CloseParenToken", 61, 61, isToken = true, missing = true)),
            missing,
        )
        assertTrue(tokens.none { it.missing })
    }

    fun testTriviaSection() {
        val trivia = files[0].trivia
        assertEquals(listOf("IfDirectiveTrivia", "DisabledTextTrivia", "EndIfDirectiveTrivia"), trivia.map { it.kind })
        val ifDirective = trivia[0].structure!!
        assertEquals("IfDirectiveTrivia", ifDirective.kind)
        assertEquals(listOf("HashToken", "IfKeyword", "IdentifierName", "EndOfDirectiveToken"), ifDirective.children.map { it.kind })
        assertEquals("IdentifierToken", ifDirective.children[2].children.single().kind)
        assertNull(trivia[1].structure)
        assertEquals(DumpTrivia("DisabledTextTrivia", 110, 127), trivia[1])
        assertTrue(files[1].trivia.isEmpty())
    }

    fun testDiagnostics() {
        assertTrue(files[0].diagnostics.isEmpty())
        assertEquals(
            listOf(
                DumpDiagnostic("CS1525", 61, 62, "Invalid expression term ';'"),
                DumpDiagnostic("CS1026", 61, 62, ") expected"),
            ),
            files[1].diagnostics,
        )
    }

    fun testFormatRoundTrip() {
        for (file in files) {
            val text = "file ${file.path}\n" + file.root.format()
            assertEquals(file.root, RoslynDump.parse(text).single().root)
        }
    }

    fun testTokensModeIsFlat() {
        val dump = "file a.cs\nV WhitespaceTrivia 0 1\nT IdentifierToken 1 4 ck=VarKeyword\nV SingleLineCommentTrivia 5 9\nT EndOfFileToken 10 10\n"
        val file = RoslynDump.parse(dump).single()
        assertEquals(listOf("IdentifierToken", "EndOfFileToken"), file.roots.map { it.kind })
        assertEquals("VarKeyword", file.roots[0].contextualKind)
        assertEquals(listOf("WhitespaceTrivia", "SingleLineCommentTrivia"), file.trivia.map { it.kind })
    }

    /** `--fields`: ` f=<Field>` after the other optional parts of node and token records; kept by [DumpNode.format]. */
    fun testFieldAnnotations() {
        val dump = "file a.cs\nN 0 CompilationUnit 0 6\nN 1 ClassDeclaration 0 5 f=Members\nT 2 IdentifierToken 0 3 missing ck=VarKeyword f=Identifier\nT 1 EndOfFileToken 6 6 f=EndOfFileToken\n"
        val root = RoslynDump.parse(dump).single().root
        assertNull(root.field)
        assertEquals("Members", root.children[0].field)
        val token = root.children[0].children.single()
        assertEquals(DumpNode("IdentifierToken", 0, 3, isToken = true, missing = true, contextualKind = "VarKeyword", field = "Identifier"), token)
        assertEquals(dump.substringAfter('\n'), root.format())
    }

    fun testMalformedRecordFails() {
        val error = runCatching { RoslynDump.parse("file a.cs\nN 0 CompilationUnit 0 1\nN 2 Block 0 1\n") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("line 3"))
    }

    companion object {
        fun cannedDump(): Path = Path.of(CSharpTestUtil.testDataPath("oracle/snippets.tree"))

        fun flatten(n: DumpNode): List<DumpNode> = listOf(n) + n.children.flatMap { flatten(it) }

        fun countNodes(n: DumpNode): Int = flatten(n).count { !it.isToken }
    }
}
