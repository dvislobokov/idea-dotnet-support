package io.github.dotnetsupport

import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FindSymbolParameters
import io.github.dotnetsupport.csharp.lang.psi.CSharpElement
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys
import io.github.dotnetsupport.il.IlViewerLogic
import io.github.dotnetsupport.lang.CSharpBreadcrumbsProvider
import io.github.dotnetsupport.lang.CSharpBreakpointLines
import io.github.dotnetsupport.lang.CSharpDeclarationIndex
import io.github.dotnetsupport.lang.CSharpDeclarationInfo
import io.github.dotnetsupport.lang.CSharpFoldingBuilder
import io.github.dotnetsupport.lang.CSharpGotoClassContributor
import io.github.dotnetsupport.lang.CSharpGotoSymbolContributor
import io.github.dotnetsupport.lang.CSharpStructureViewFactory
import io.github.dotnetsupport.lang.CSharpSyntaxModel
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpSyntaxModel
import io.github.dotnetsupport.roslyn.RoslynBaseMembers
import io.github.dotnetsupport.testing.DotNetTestRunLineMarkerContributor
import io.github.dotnetsupport.testing.testTargetAt
import java.io.File

/**
 * Snapshots of everything [CSharpSyntaxModel] feeds, per file: the declarations, Structure view, folding, breadcrumbs, the names of the IL
 * Viewer and the member of Go to Base at each line, breakpoint lines, run-gutter markers, Go to Class / Symbol. The goldens record what the
 * heuristics do today, mistakes included (CSHARP_PSI_MIGRATION.md, step 7): the implementation on the own PSI is compared to them, and
 * every difference is reviewed, then the golden is updated, not the other way round.
 *
 * Inputs: `src/test/resources/syntaxSnapshots/playground` (a frozen copy of the sources of `debug-playground`, so that a new scenario
 * there does not touch these goldens) and `synthetic` (constructs the heuristics find hard). A golden is `<input>.txt` next to the input.
 * A missing golden is recorded and the test fails once; a different one is written to `build/syntaxSnapshots/` for a diff. To re-record,
 * delete the golden and run the test again.
 *
 * Every input runs on both trees: the heuristic one against `<input>.txt`, csharp-psi's ([NativeCSharpSyntaxModel], files parsed under
 * `native/`) against `<input>.native.txt`. The differences of the native goldens, reviewed (2026-10-04):
 * - known mistakes of the heuristics gone: `#if` / `#else` with two method headers (`IfBranches`: `_debugOnly`, `Helper`, `After` are back,
 *   `Run` ends at its `}`; breakpoint lines 16 and 24 go with it), `record Settings` after a top-level `using (…) { … }`
 *   (`TopLevelStatements`), `[assembly: …]` out of the namespace range (`AttributesOnMembers`), `int _first, _second;` and
 *   `public T Left, Right;` are two fields each (`Generics`), `record Box<T>(T Value)` has its primary constructor (`Records`), the attribute
 *   of an enum member is in its range (`NestedAndPartial`);
 * - deliberate: of a field with several declarators the breadcrumb is there on the declarators only, not on its modifiers and type
 *   (`Generics` lines 9 and 35: each declarator is an element of its own, the field is none);
 * - not the model's: the run gutter of `Tests-*` and `AttributesOnMembers` is empty because `testTargetAt` checks the leaf for the heuristic
 *   lexer's identifier type (re-record those goldens with the fix of the producers: the targets themselves agree, see
 *   `NativeCSharpSyntaxModelTest.testAttributedMethodsAgreeWithTheHeuristicsOnTheSnapshots`); breakpoint lines of auto-properties without an
 *   initializer and of `const` fields are still marked — `CSharpBreakpointLines` decides that by tokens, not by the model.
 */
class CSharpSyntaxSnapshotTest : BasePlatformTestCase() {
    private val root = File("src/test/resources/syntaxSnapshots")

    override fun tearDown() {
        try {
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } finally {
            super.tearDown()
        }
    }

    fun testPlayground() = checkGroup("playground", native = false)

    fun testSynthetic() = checkGroup("synthetic", native = false)

    fun testPlaygroundNative() = checkGroup("playground", native = true)

    fun testSyntheticNative() = checkGroup("synthetic", native = true)

    /** No main code but the heuristic implementation itself reaches past the facade: the parser swap replaces only that implementation. */
    fun testConsumersGoThroughTheFacade() {
        val implementation = setOf("CSharpDeclarations.kt", "CSharpPsi.kt", "CSharpParserDefinition.kt", "HeuristicCSharpParserDefinition.kt", "HeuristicCSharpSyntaxModel.kt", "CSharpSyntaxModel.kt")
        val forbidden = Regex("""\b(CSharpDeclarations|CSharpDeclaration|CSharpStructure|CSharpTreeBuilder|CSharpElementTypes)\b""")
        val offenders = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in implementation }.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) ->
                val code = line.trim()
                !code.startsWith("*") && !code.startsWith("/**") && !code.startsWith("//") && forbidden.containsMatchIn(code)
            }.map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }.toList()
        assertEquals("consumers of the declarations use CSharpSyntaxModel", emptyList<String>(), offenders)
    }

    private fun checkGroup(group: String, native: Boolean) {
        CSharpSyntaxTrees.forceNativeTreeForTests(native)
        val inputs = File(root, group).listFiles { file -> file.extension == "cs" }.orEmpty().sortedBy { it.name }
        assertTrue("inputs of $group", inputs.isNotEmpty())
        val problems = ArrayList<String>()
        for (input in inputs) {
            val text = input.readText().replace("\r\n", "\n").replace('\r', '\n')
            // the native files under paths of their own: a file keeps the tree it was parsed with
            val actual = snapshot((if (native) "native/" else "") + "$group/${input.name}", text)
            val golden = File(input.parentFile, input.nameWithoutExtension + (if (native) ".native.txt" else ".txt"))
            if (!golden.exists()) {
                golden.writeText(actual)
                problems += "${golden.path}: recorded, review it and run again"
            } else if (golden.readText().replace("\r\n", "\n") != actual) {
                val copy = File("build/syntaxSnapshots/$group/${golden.name}").apply { parentFile.mkdirs() }
                copy.writeText(actual)
                problems += "${golden.path} differs: see ${copy.path}"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun snapshot(path: String, text: String): String {
        val file = myFixture.addFileToProject("syntaxSnapshots/$path", text)
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val out = StringBuilder()
        fun section(title: String, lines: List<String>) {
            out.append("## ").append(title).append('\n')
            lines.forEach { out.append(it).append('\n') }
            out.append('\n')
        }
        fun at(offset: Int): String = document.getLineNumber(offset).let { line -> "${line + 1}:${offset - document.getLineStartOffset(line) + 1}" }
        fun range(range: TextRange): String = at(range.startOffset) + "-" + at(range.endOffset)

        val structure = CSharpSyntaxModel.current.declarations(file)
        fun declaration(info: CSharpDeclarationInfo, indent: String): List<String> {
            val modifiers = info.modifiers.takeIf { it.isNotEmpty() }?.joinToString(" ", " [", "]").orEmpty()
            val body = info.body?.let { " body " + range(it) }.orEmpty()
            return listOf("$indent${info.kind.title} ${info.presentation}$modifiers  name ${range(info.nameRange)}  range ${range(info.range)}$body") +
                info.children.flatMap { declaration(it, "$indent  ") }
        }
        section("declarations", listOfNotNull(structure.usings?.let { "usings ${range(it)}" }) + structure.declarations.flatMap { declaration(it, "") })
        section("structure view", structureView(file))
        section("folding", foldRegions(file, document).map { (range, placeholder) -> "${range(range)}  $placeholder  | ${firstLine(range.substring(text))}" })

        // per line, at its first character that is not a space; a line is listed when the answer changes
        val lineStarts = (0 until document.lineCount).map { document.getLineStartOffset(it) + document.getText(TextRange(document.getLineStartOffset(it), document.getLineEndOffset(it))).indexOfFirst { c -> !c.isWhitespace() } }
            .withIndex().filter { it.value >= document.getLineStartOffset(it.index) }
        fun byLine(answer: (Int) -> String): List<String> {
            var last: String? = null
            return lineStarts.mapNotNull { (line, offset) -> answer(offset).takeIf { it != last }?.also { last = it }?.let { "${line + 1}: $it" } }
        }
        val crumbs = CSharpBreadcrumbsProvider()
        section("breadcrumbs", byLine { offset ->
            generateSequence(file.findElementAt(offset)) { it.parent }.filter(crumbs::acceptElement).map(crumbs::getElementInfo).toList().asReversed().joinToString(" > ")
        })
        section("IL Viewer: type / member at the line", byLine { offset -> IlViewerLogic.names(text, offset).let { (type, member) -> "${type ?: "-"} / ${member ?: "-"}" } })
        section("Go to Base: member at the line", byLine { offset ->
            RoslynBaseMembers.memberAt(structure, offset)?.let { "${it.type.name}.${it.member.name}${it.member.parameters.orEmpty()}" + if (it.inBody) " (in body)" else "" } ?: "-"
        })
        section("breakpoint lines", listOf(lineRuns(CSharpBreakpointLines.find(text).map { it + 1 }.sorted())))
        val gutter = DotNetTestRunLineMarkerContributor()
        section("run gutter", SyntaxTraverser.psiTraverser(file).filter { it.firstChild == null }.toList().mapNotNull { leaf ->
            gutter.getInfo(leaf)?.let { testTargetAt(leaf)!! }?.let { "${at(leaf.textRange.startOffset)} ${it.displayName}  ${it.filter}" }
        })
        section("Go to Class / Symbol", gotoItems(file, ::at))
        return out.toString()
    }

    private fun structureView(file: PsiFile): List<String> {
        val builder = CSharpStructureViewFactory().getStructureViewBuilder(file) as TreeBasedStructureViewBuilder
        val model = builder.createStructureViewModel(null)
        try {
            fun outline(element: TreeElement, indent: String): List<String> = element.children.flatMap { listOf(indent + it.presentation.presentableText) + outline(it, "$indent  ") }
            return outline(model.root, "")
        } finally {
            Disposer.dispose(model)
        }
    }

    private fun foldRegions(file: PsiFile, document: Document): List<Pair<TextRange, String>> =
        CSharpFoldingBuilder().buildFoldRegions(file, document, false).map { it.range to (it.placeholderText ?: "") + if (it.isCollapsedByDefault == true) " (collapsed)" else "" }

    /**
     * Per key of the index: Go to Class for the names of types (`T:`), Go to Symbol for the names of members (`M:`), the items of this file.
     * The keys are those of [CSharpDeclarationIndex] on the heuristic tree, of the stub indexes of csharp-psi on the native one (step 8).
     */
    private fun gotoItems(file: PsiFile, at: (Int) -> String): List<String> {
        val keys = if (CSharpSyntaxTrees.nativeTree()) stubKeys(file) else FileBasedIndex.getInstance().getFileData(CSharpDeclarationIndex.NAME, file.virtualFile, project).keys.sorted()
        return keys.map { key ->
            val isType = key.startsWith(CSharpDeclarationIndex.TYPE_PREFIX)
            val contributor = if (isType) CSharpGotoClassContributor() else CSharpGotoSymbolContributor()
            val items = ArrayList<NavigationItem>()
            contributor.processElementsWithName(key.substring(CSharpDeclarationIndex.TYPE_PREFIX.length), { items += it; true }, FindSymbolParameters.simple(project, false))
            val here = items.filter { (it as? PsiElement)?.containingFile == file }
                .map { item -> "${at((item as PsiElement).textOffset)} ${item.presentation?.presentableText} (${item.presentation?.locationString})" }
            "$key -> ${here.joinToString("; ")}"
        }
    }

    /** The names [file] has in the type and member stub indexes, as the keys of [CSharpDeclarationIndex]. */
    private fun stubKeys(file: PsiFile): List<String> {
        val scope = GlobalSearchScope.fileScope(file)
        return listOf(CSharpStubIndexKeys.TYPE_NAMES to CSharpDeclarationIndex.TYPE_PREFIX, CSharpStubIndexKeys.MEMBER_NAMES to CSharpDeclarationIndex.MEMBER_PREFIX).flatMap { (key, prefix) ->
            StubIndex.getInstance().getAllKeys(key, project).filter { StubIndex.getElements(key, it, project, scope, CSharpElement::class.java).isNotEmpty() }.map { prefix + it }
        }.sorted()
    }

    private fun firstLine(text: String): String = text.lineSequence().first().trim()

    /** `3-5, 8, 10-12`. */
    private fun lineRuns(lines: List<Int>): String {
        val runs = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            var j = i
            while (j + 1 < lines.size && lines[j + 1] == lines[j] + 1) j++
            runs += if (j == i) "${lines[i]}" else "${lines[i]}-${lines[j]}"
            i = j + 1
        }
        return runs.joinToString(", ")
    }
}
