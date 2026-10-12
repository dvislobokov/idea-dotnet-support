package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.lang.CSharpFeature
import io.github.dotnetsupport.csharp.lang.CSharpLanguageLevel
import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion
import io.github.dotnetsupport.csharp.lang.CSharpParserDefinition
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.oracle.DumpFile
import io.github.dotnetsupport.csharp.lang.oracle.RoslynDump
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Every version-dependent path of the parser at its boundary (docs/csharp-psi/GRAMMAR.md, "Language version"): the snippets of
 * `testData/parser/langversion` are named `<name>.v<version>.<expr|stmt|member|cs>`, `<version>` being the first
 * version with the feature (`2`, `7`, `7_2`, `9`, `11`, `13`, `14`, `preview`). Each is diffed against
 * `roslyndump --langversion` at that version and at the one before it, with our parser at the same version
 * ([SliceGate]: valid and invalid snippets must match; a valid one must have no error, an invalid one at least one).
 * Each snippet must also really sit on a boundary: Roslyn's tree or diagnostics differ between the two versions.
 */
class LanguageVersionDiffTest : CSharpParsingTestCase("parser/langversion") {

    private val root = Paths.get(CSharpTestUtil.testDataPath("parser/langversion"))

    private class Snippet(val path: String, val mode: SliceParseHarness.Mode, val at: CSharpLanguageVersion, val below: CSharpLanguageVersion)

    private fun snippets(): List<Snippet> = Files.list(root).use { files ->
        files.map { it.fileName.toString() }.sorted().toList().map { name ->
            val m = Regex("^.+\\.v([0-9_a-z]+)\\.(expr|stmt|member|cs)$").find(name) ?: error("bad snippet name $name")
            val at = CSharpLanguageVersion.parse(m.groupValues[1].replace('_', '.')) ?: error("bad version in $name")
            val ordered = CSharpLanguageVersion.effectiveVersions.sortedBy { it.value }
            val below = ordered[ordered.indexOf(at) - 1]
            val mode = SliceParseHarness.Mode.entries.single { it.dumpMode == (if (m.groupValues[2] == "cs") "tree" else m.groupValues[2]) }
            Snippet(name, mode, at, below)
        }
    }

    fun testSnippetsAtTheirBoundaries() {
        val snippets = snippets()
        assertTrue(snippets.size >= 15)
        val gate = SliceGate("langversion")
        val dumps = HashMap<Pair<String, CSharpLanguageVersion>, DumpFile>()
        val ourErrors = ArrayList<String>()
        for (mode in SliceParseHarness.Mode.entries) {
            val byVersion = LinkedHashMap<CSharpLanguageVersion, MutableList<String>>()
            for (s in snippets.filter { it.mode == mode }) {
                byVersion.getOrPut(s.below) { ArrayList() } += s.path
                byVersion.getOrPut(s.at) { ArrayList() } += s.path
            }
            for ((version, paths) in byVersion) {
                val run = RoslynDump.run(mode.dumpMode, root, langVersion = version.displayString, includes = paths)
                for (dump in run.files) {
                    dumps[dump.path to version] = dump
                    gate.compare(root, dump, mode, version)
                    // Invalid at this version: our parser must report an error too (feature diagnostics are counted).
                    if (dump.diagnostics.isNotEmpty()) {
                        val result = SliceParseHarness.parse(Files.readString(root.resolve(dump.path)), mode, version)
                        if (result.errorCount == 0 && result.errorElements == 0) ourErrors += "${dump.path} @${version.displayString}"
                    }
                }
            }
        }
        val report = gate.writeReport(CSharpTestUtil.buildDir("slice-gate"))
        println(gate.summary())
        assertTrue("exceptions:\n" + gate.exceptions.joinToString("\n"), gate.exceptions.isEmpty())
        assertEquals("valid snippets with mismatches (see $report)", 0L, gate.stats.validMismatchedFiles)
        assertEquals("invalid snippets with mismatches (see $report)", 0L, gate.stats.invalidMismatchedFiles)
        assertEquals("snippets Roslyn rejects but our parser accepts", emptyList<String>(), ourErrors)
        val notOnBoundary = snippets.filter { s ->
            val below = dumps[s.path to s.below] ?: error("no dump of ${s.path} at ${s.below}")
            val at = dumps[s.path to s.at] ?: error("no dump of ${s.path} at ${s.at}")
            below.roots == at.roots && below.diagnostics.size == at.diagnostics.size
        }
        assertEquals("snippets whose Roslyn parse does not change at their boundary", emptyList<String>(), notOnBoundary.map { it.path })
    }

    /** `LanguageVersionFacts.TryParse` and `MapSpecifiedToEffectiveVersion` at `roslynCommit`. */
    fun testParseAndEffectiveVersion() {
        assertEquals(CSharpLanguageVersion.CSharp7_3, CSharpLanguageVersion.parse("7.3"))
        assertEquals(CSharpLanguageVersion.CSharp1, CSharpLanguageVersion.parse("ISO-1"))
        assertEquals(CSharpLanguageVersion.CSharp7, CSharpLanguageVersion.parse("7"))
        assertEquals(CSharpLanguageVersion.CSharp14, CSharpLanguageVersion.parse("14.0"))
        assertEquals(CSharpLanguageVersion.LatestMajor, CSharpLanguageVersion.parse("LatestMajor"))
        assertEquals(CSharpLanguageVersion.Default, CSharpLanguageVersion.parse(null))
        assertNull(CSharpLanguageVersion.parse("7.4"))
        assertNull(CSharpLanguageVersion.parse("15"))
        for (v in listOf(CSharpLanguageVersion.Default, CSharpLanguageVersion.Latest, CSharpLanguageVersion.LatestMajor)) {
            assertEquals(CSharpLanguageVersion.CSharp14, v.effective())
        }
        assertEquals(CSharpLanguageVersion.Preview, CSharpLanguageVersion.Preview.effective())
        assertEquals(18, CSharpLanguageVersion.effectiveVersions.size)
        assertTrue(CSharpLanguageVersion.Default.isFeatureEnabled(CSharpFeature.FieldKeyword))
        assertFalse(CSharpLanguageVersion.Default.isFeatureEnabled(CSharpFeature.Unions))
        assertTrue(CSharpLanguageVersion.Preview.isFeatureEnabled(CSharpFeature.Unions))
        assertFalse(CSharpLanguageVersion.CSharp7_3.isFeatureEnabled(CSharpFeature.Records))
        assertTrue(CSharpLanguageVersion.CSharp7_2.isFeatureEnabled(CSharpFeature.LeadingDigitSeparator))
        assertFalse(CSharpLanguageVersion.CSharp7_1.isFeatureEnabled(CSharpFeature.LeadingDigitSeparator))
    }

    /** The version comes from the file's key ([CSharpLanguageLevel.forFile]) through the file element type; the default is C# 14. */
    fun testFileKeyDrivesTheParser() {
        val text = "class C { int P { get { return field; } } }"
        fun fieldExpressions(version: CSharpLanguageVersion?): Int {
            val file = createPsiFile("a", text)
            file.putUserData(CSharpLanguageLevel.KEY, version) // replaces the Preview key of createFile; null: the IDE default
            return countKind(file.node, SyntaxKind.FieldExpression.toString())
        }
        assertEquals(0, fieldExpressions(CSharpLanguageVersion.CSharp13))
        assertEquals(1, fieldExpressions(CSharpLanguageVersion.CSharp14))
        assertEquals(1, fieldExpressions(null))
        assertEquals(CSharpLanguageVersion.Default, CSharpLanguageLevel.IDE_DEFAULT)
    }

    /** A copy made for a reparse (`PsiFile.getOriginalFile`) finds the original's version. */
    fun testCopiesFollowTheOriginal() {
        val file = createPsiFile("b", "class C { }")
        file.putUserData(CSharpLanguageLevel.KEY, CSharpLanguageVersion.CSharp7_3)
        val copy = file.copy() as com.intellij.psi.PsiFile
        assertEquals(CSharpLanguageVersion.CSharp7_3, CSharpLanguageLevel.forFile(copy))
    }

    /** Preview-only modifiers are not modifiers at the IDE default (C# 14): `closed c;` is a field of type `closed`. */
    fun testIdeDefaultIsNotPreview() {
        val file = createPsiFile("c", "class C { closed c; }")
        file.putUserData(CSharpLanguageLevel.KEY, null)
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
        assertEquals(1, countKind(file.node, SyntaxKind.FieldDeclaration.toString()))
        assertEquals(CSharpParserDefinition.FILE, file.node.elementType)
    }

    private fun countKind(node: com.intellij.lang.ASTNode, kind: String): Int {
        var n = if (node.elementType.toString() == kind) 1 else 0
        var child = node.firstChildNode
        while (child != null) {
            n += countKind(child, kind)
            child = child.treeNext
        }
        return n
    }
}
