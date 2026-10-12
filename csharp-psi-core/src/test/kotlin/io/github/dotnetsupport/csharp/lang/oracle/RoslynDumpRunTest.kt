package io.github.dotnetsupport.csharp.lang.oracle

import io.github.dotnetsupport.csharp.CSharpTestUtil
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * `tools/csharp-psi/roslyndump` end to end: built when stale, run over a temporary file and a directory, output parsed. The canned
 * dump `testData/oracle/snippets.tree` must stay what the tool prints: when the format changes on purpose, regenerate
 * it (`roslyndump tree testData/oracle/snippets --out testData/oracle/snippets.tree`). Needs `dotnet` on `PATH`.
 */
class RoslynDumpRunTest : TestCase() {

    fun testDirectoryMatchesCannedDump() {
        val run = RoslynDump.run("tree", Path.of(CSharpTestUtil.testDataPath("oracle/snippets")))
        for (fact in listOf("files=2", "nodes=60", "errors=2")) assertTrue(run.summary, run.summary.split(' ').contains(fact))
        assertEquals(RoslynDump.read(RoslynDumpFormatTest.cannedDump()), run.files)
    }

    /** `--fields` adds a field to every child and changes nothing else; separated lists carry their field on separators. */
    fun testFieldsAnnotateEveryChild() {
        val dir = Path.of(CSharpTestUtil.testDataPath("oracle/snippets"))
        val plain = RoslynDump.run("tree", dir).files
        val annotated = RoslynDump.run("tree", dir, fields = true).files
        fun strip(n: DumpNode): DumpNode = n.copy(field = null, children = n.children.map(::strip))
        assertEquals(plain.map { it.root }, annotated.map { strip(it.root) })
        fun check(n: DumpNode) = n.children.forEach { assertNotNull("$it in ${n.kind}", it.field); }
        fun walk(n: DumpNode) { check(n); n.children.forEach(::walk) }
        annotated.forEach { assertNull(it.root.field); walk(it.root) }

        val call = RoslynDump.run("expr", Files.writeString(Files.createTempFile("fields", ".expr"), "F(a, b)"), fields = true).files.single().root
        assertEquals(listOf("Expression", "ArgumentList"), call.children.map { it.field })
        assertEquals(
            listOf("OpenParenToken", "Arguments", "Arguments", "Arguments", "CloseParenToken"),
            call.children[1].children.map { it.field },
        )
    }

    fun testTemporaryFileWithCrLf() {
        val dir = Files.createTempDirectory("roslyndump-run")
        try {
            // CRLF is normalised to LF before parsing: offsets are those of the IntelliJ document.
            val source = Files.writeString(dir.resolve("T.cs"), "class T\r\n{\r\n    var f;\r\n}\r\n")
            val tree = RoslynDump.run("tree", source).files.single()
            assertEquals("T.cs", tree.path)
            assertEquals(DumpNode("CompilationUnit", 0, 23, tree.root.children), tree.root)
            assertEquals("ClassDeclaration", tree.root.children[0].kind)

            val tokens = RoslynDump.run("tokens", source).files.single()
            assertEquals(
                listOf("ClassKeyword", "IdentifierToken", "OpenBraceToken", "IdentifierToken", "IdentifierToken", "SemicolonToken", "CloseBraceToken", "EndOfFileToken"),
                tokens.roots.map { it.kind },
            )
            assertEquals("VarKeyword", tokens.roots[3].contextualKind)
            assertTrue(tokens.trivia.any { it.kind == "EndOfLineTrivia" && it.end - it.start == 1 })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** A process past its timeout is killed and the run fails with a clear message (Roslyn can hang: docs/csharp-psi/GRAMMAR.md). */
    fun testTimeoutKillsProcess() {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val sleeper = if (windows) listOf("ping", "-n", "60", "127.0.0.1") else listOf("sleep", "60")
        val started = System.nanoTime()
        val e = try {
            RoslynDump.runProcess(sleeper, Path.of(System.getProperty("java.io.tmpdir")), timeoutSeconds = 2)
            null
        } catch (e: IllegalStateException) {
            e
        }
        val seconds = (System.nanoTime() - started) / 1_000_000_000
        assertNotNull("no timeout", e)
        assertTrue(e!!.message, e.message!!.startsWith("Timed out after 2 s, killed"))
        assertTrue("took $seconds s", seconds < 30)
    }
}
