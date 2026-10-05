package io.github.dotnetsupport.csharp.lang.oracle

import com.intellij.testFramework.ParsingTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import java.nio.file.Files

/**
 * [PsiToDump] on the PSI of [ToyLanguage] (no C# parser needed), diffed with [TreeDiff] against `roslyndump tree` of
 * the same text: the whole oracle pipeline end to end. Needs `dotnet` on `PATH`.
 */
class PsiToDumpTest : ParsingTestCase("", "toy", ToyParserDefinition()) {

    override fun getTestDataPath(): String = CSharpTestUtil.testDataPath()

    fun testValidCodeMatchesRoslyn() {
        val text = "/* c */ a + b * (c + 1);\nx;\n"
        val file = createPsiFile("a", text)
        // The toy binds the comment and the line breaks into CompilationUnit: the mapping must trim them.
        val unit = file.node.firstChildNode
        assertEquals("CompilationUnit", unit.elementType.toString())
        assertEquals(0, unit.startOffset)

        val mapper = PsiToDump()
        val ours = mapper.map(file.node)
        assertEquals("CompilationUnit", ours.single().kind)
        assertEquals(8 to text.length, ours.single().let { it.start to it.end })
        assertEquals(0, mapper.errorElements)

        val diff = TreeDiff()
        val roslyn = roslynTree(text)
        assertEquals(emptyList<DumpDiagnostic>(), roslyn.diagnostics)
        assertEquals(emptyList<Mismatch>(), diff.diff(roslyn, ours))
        assertEquals(1, diff.zeroWidthTokens)
        assertEquals(roslyn.root.count() - 1, ours.single().count())
    }

    fun testMissingOperandMatchesRoslyn() {
        // Roslyn: AddExpression(IdentifierName x, +, IdentifierName(missing IdentifierToken)), positioned at `;`.
        val text = "x + ;\n"
        val file = createPsiFile("a", text)
        val mapper = PsiToDump()
        val ours = mapper.map(file.node)
        assertEquals(1, mapper.errorElements)
        val diff = TreeDiff()
        val roslyn = roslynTree(text)
        assertEquals(listOf("CS1525"), roslyn.diagnostics.map { it.id })
        assertEquals(ours.single().format(), emptyList<Mismatch>(), diff.diff(roslyn, ours))
        assertEquals(1, diff.missingTokens)
    }

    fun testErrorElementsAreUnwrapped() {
        val file = createPsiFile("a", "@a;")
        val mapper = PsiToDump()
        val statement = mapper.map(file.node).single().children.single().children.single()
        assertEquals("ExpressionStatement", statement.kind)
        assertEquals(
            listOf(
                DumpNode("BadToken", 0, 1, isToken = true),
                DumpNode("IdentifierName", 1, 2, listOf(DumpNode("IdentifierToken", 1, 2, isToken = true))),
                DumpNode("SemicolonToken", 2, 3, isToken = true),
            ),
            statement.children,
        )
        assertEquals(1, mapper.errorElements)
    }

    fun testCustomNodePredicate() {
        val file = createPsiFile("a", "a;")
        // Everything that is not a statement is unwrapped: tokens stay, IdentifierName disappears.
        val mapper = PsiToDump(isNode = { it.toString().endsWith("Statement") || it.toString() == "CompilationUnit" })
        val statement = mapper.map(file.node).single().children.single().children.single()
        assertEquals(listOf("IdentifierToken", "SemicolonToken"), statement.children.map { it.kind })
    }

    private fun roslynTree(text: String): DumpFile {
        val dir = Files.createTempDirectory("psitodump")
        try {
            val source = Files.writeString(dir.resolve("a.cs"), text)
            return RoslynDump.run("tree", source).files.single()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
