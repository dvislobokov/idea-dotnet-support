package io.github.dotnetsupport.ml

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import io.github.completionml.core.rank.ExampleShards
import io.github.completionml.core.rank.FeatureExtractor
import io.github.completionml.core.rank.FileState
import io.github.completionml.core.rank.TrainingExample
import io.github.completionml.core.spi.TokenKind
import io.github.completionml.core.vocab.Vocabulary
import io.github.dotnetsupport.lang.NativeCSharpMlInfo
import java.io.File
import java.util.Locale
import java.util.Random

/**
 * The per-repository / per-file logic of the offline dataset export ([CSharpMlDatasetExport]; ML_RANKER_EXPORT_TASK.md, ADAPTER.md §3),
 * apart from the test fixture so a unit test drives it on a fixture repository. A repository is copied into a content root of the light
 * project; in every selected file the sampled identifiers are cut to a 0–2 character prefix, the plugin's real completion runs at the caret,
 * and the list with its features goes into the shard — when the identifier of the source is in it (recall is counted, not fixed).
 */
class CSharpMlExporter(
    private val fixture: CodeInsightTestFixture,
    private val project: Project,
    private val module: Module,
    private val disposable: Disposable,
    private val vocab: Vocabulary,
    private val extractor: FeatureExtractor,
    private val options: Options,
) {
    /** @param perFile sampled positions per file; @param maxFiles per repository (0 = all); @param cacheLambda λ of the file cache LM (0 = off). */
    class Options(val perFile: Int = 10, val maxFiles: Int = 120, val cacheLambda: Double = 0.3, val seed: Long = 7L, val maxCandidates: Int = 100)

    class Stats {
        var files = 0; var positions = 0; var lists = 0; var noAnswer = 0; var empty = 0; var single = 0; var candidates = 0L; var millis = 0L
        fun add(o: Stats) {
            files += o.files; positions += o.positions; lists += o.lists; noAnswer += o.noAnswer; empty += o.empty; single += o.single
            candidates += o.candidates; millis += o.millis
        }

        /** recall = lists with the answer / positions where the plugin offered a list at all. */
        val recall: Double get() = lists.toDouble() / (positions - empty - single).coerceAtLeast(1)

        fun summary(): String = "files=$files positions=$positions lists=$lists answer-missing=$noAnswer no-list=$empty single-insert=$single " +
            "recall=%.3f candidates/list=%.1f %.0f ms/position".format(Locale.ROOT, recall, candidates.toDouble() / lists.coerceAtLeast(1), millis.toDouble() / positions.coerceAtLeast(1))
    }

    /** Copies the repository's C# and project files into a temporary content root (the corpus stays untouched) and exports every selected file. */
    fun exportRepository(repoDir: File, writer: ExampleShards.Writer, stats: Stats) {
        val tmp = FileUtil.createTempDirectory("csml-", repoDir.name, true)
        val sources = ArrayList<File>()
        repoDir.walkTopDown().onEnter { d -> !d.name.startsWith(".") && d.name.lowercase() !in SKIPPED_DIRS }.forEach { f ->
            if (!f.isFile) return@forEach
            val ext = f.extension.lowercase()
            if (ext !in COPIED_EXTENSIONS) return@forEach
            val rel = f.relativeTo(repoDir).path
            val dst = File(tmp, rel); dst.parentFile.mkdirs(); f.copyTo(dst)
            if (ext == "cs" && isSource(rel, f)) sources.add(dst)
        }
        VfsRootAccess.allowRootAccess(disposable, tmp.path)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp) ?: error("no VFS root for $tmp")
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val files = sources.sortedBy { it.path }.let { if (options.maxFiles > 0) it.take(options.maxFiles) else it }
            for (file in files) {
                val text = file.readText().replace("\r\n", "\n")
                if (isGenerated(text)) continue
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: continue
                exportFile(vf, text, writer, stats)
            }
        } finally {
            PsiTestUtil.removeContentEntry(module, root)
            FileUtil.delete(tmp)
        }
    }

    /** One file: the sampled identifier positions, each completed by the plugin and written when the answer is in the list. */
    fun exportFile(vf: VirtualFile, text: String, writer: ExampleShards.Writer, stats: Stats) {
        val tokens = CSharpMlLanguage.tokenizer.tokens(text)
        val rnd = Random(options.seed xor tokens.size.toLong())
        val identifiers = (1 until tokens.size).filter { tokens[it].kind == TokenKind.IDENT }
        val positions = (if (identifiers.size <= options.perFile) identifiers else identifiers.shuffled(rnd).take(options.perFile)).sorted()
        if (positions.isEmpty()) return
        stats.files++
        fixture.configureFromExistingVirtualFile(vf)
        val document = fixture.editor.document
        val state = FileState(vocab, withCache = options.cacheLambda > 0)
        var fed = 0
        for (i in positions) {
            while (fed < i) state.add(tokens[fed++])
            val t = tokens[i]
            val prefixLen = when (rnd.nextInt(10)) { in 0..4 -> 0; in 5..7 -> 1; else -> 2 }.coerceAtMost(t.text.length)
            val prefix = t.text.substring(0, prefixLen)
            val caret = t.offset + prefixLen
            val modified = text.substring(0, caret) + text.substring(t.offset + t.text.length)
            val started = System.currentTimeMillis()
            stats.positions++
            try {
                setText(document, modified)
                fixture.editor.caretModel.moveToOffset(caret)
                val items = fixture.completeBasic()
                if (items == null) { stats.single++; continue }     // one candidate was inserted directly: no list to learn from
                var candidates = items.map(NativeCSharpMlInfo::candidateOf).distinctBy { it.lookupString }
                if (candidates.isEmpty()) { stats.empty++; continue }
                val chosenAll = candidates.indexOfFirst { it.lookupString == t.text }
                if (chosenAll < 0 || candidates.size < 2) { stats.noAnswer++; continue }
                if (candidates.size > options.maxCandidates) {
                    val answer = candidates[chosenAll]
                    candidates = (candidates.filterIndexed { idx, _ -> idx != chosenAll }.shuffled(rnd).take(options.maxCandidates - 1) + answer).shuffled(rnd)
                }
                val chosen = candidates.indexOfFirst { it.lookupString == t.text }
                val language = CSharpMlFeatures.languageBlock(caret, CSharpMlFeatures.isAfterDot(tokens, i), candidates)
                val names = Array(candidates.size) { candidates[it].lookupString }
                val base = extractor.features(state, prefix, names, language)
                writer.add(TrainingExample(CSharpMlFeatures.contextKind(tokens, i), base, chosen, names))
                stats.lists++
                stats.candidates += candidates.size
            } finally {
                LookupManager.getInstance(project).hideActiveLookup()
                setText(document, text)
                stats.millis += System.currentTimeMillis() - started
            }
        }
    }

    private fun setText(document: Document, text: String) {
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    companion object {
        private val SKIPPED_DIRS = setOf("bin", "obj", "node_modules", "packages")
        private val COPIED_EXTENSIONS = setOf("cs", "csproj", "props", "targets", "sln", "slnx")
        private val GENERATED_SUFFIXES = listOf(".g.cs", ".g.i.cs", ".designer.cs", ".generated.cs", "assemblyinfo.cs")
        private val TEST_SUFFIXES = listOf("tests.cs", "test.cs")

        /** A source worth sampling: not a test, not generated by its name, 200 B – 400 KB (as the e17 selection). */
        fun isSource(relativePath: String, file: File): Boolean {
            val path = relativePath.replace('\\', '/').lowercase()
            val name = path.substringAfterLast('/')
            if (GENERATED_SUFFIXES.any { name.endsWith(it) } || TEST_SUFFIXES.any { name.endsWith(it) }) return false
            if (path.split('/').dropLast(1).any { it == "test" || it == "tests" || it.endsWith(".tests") || it.endsWith(".test") || it.startsWith("test") }) return false
            return file.length() in 200..400_000
        }

        /** Generated by its header: the `<auto-generated>` mark in the first 2 KB. */
        fun isGenerated(text: String): Boolean = text.take(2048).contains("<auto-generated", ignoreCase = true)
    }
}
