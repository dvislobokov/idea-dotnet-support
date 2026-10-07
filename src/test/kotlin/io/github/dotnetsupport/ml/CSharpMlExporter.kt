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
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.lang.NativeCSharpMlInfo
import io.github.dotnetsupport.lang.NativeCSharpTypeNames
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
    class Options(
        val perFile: Int = 10, val maxFiles: Int = 120, val cacheLambda: Double = 0.3, val seed: Long = 7L, val maxCandidates: Int = 100,
        /** At most this many `.cs` files are copied into the content root (0 = all): the sampled sources first, then a random subset
         *  of the rest. Indexing the whole repository dominated the export time on the server (~10 min for a big repository). */
        val maxCopy: Int = 0,
        /** `dotnet restore` the projects of the copy before the export and index the restored assemblies (the members of library types). */
        val restore: Boolean = false,
        /** Seconds the restore of one repository may take in all; what is not restored by then is exported without its packages. */
        val restoreTimeoutSec: Int = 300,
        /** An overlay with the MSBuild files of the repositories (`<projects>/<repo>/...`, `tools/ml-dataset/fetch-projects.sh`): the corpus has only sources. */
        val projects: File? = null,
        /** The stub-index snapshot of the solution's types per file ([NativeCSharpTypeNames.snapshot]) instead of a scan per position. */
        val typeSnapshot: Boolean = true,
    )

    class Stats {
        var files = 0; var positions = 0; var lists = 0; var noAnswer = 0; var empty = 0; var single = 0; var candidates = 0L; var millis = 0L
        /** Positions right after `.`, those of them with a list at all, those with the answer in it. */
        var afterDot = 0; var afterDotListed = 0; var afterDotFound = 0
        /** The restore and the index of assemblies: projects found / restored (assets written), assemblies indexed, time. */
        var projects = 0; var restored = 0; var assemblies = 0; var restoreMillis = 0L; var indexMillis = 0L; var copyMillis = 0L
        /** The indexing of the content root by the IDE and the type-name snapshots of the files. */
        var indexingMillis = 0L; var snapshotMillis = 0L
        fun add(o: Stats) {
            files += o.files; positions += o.positions; lists += o.lists; noAnswer += o.noAnswer; empty += o.empty; single += o.single
            candidates += o.candidates; millis += o.millis
            afterDot += o.afterDot; afterDotListed += o.afterDotListed; afterDotFound += o.afterDotFound
            projects += o.projects; restored += o.restored; assemblies += o.assemblies; restoreMillis += o.restoreMillis; indexMillis += o.indexMillis; copyMillis += o.copyMillis
            indexingMillis += o.indexingMillis; snapshotMillis += o.snapshotMillis
        }

        /** recall = lists with the answer / positions where the plugin offered a list at all. */
        val recall: Double get() = lists.toDouble() / (positions - empty - single).coerceAtLeast(1)

        fun summary(): String = "files=$files positions=$positions lists=$lists answer-missing=$noAnswer no-list=$empty single-insert=$single " +
            "recall=%.3f candidates/list=%.1f %.0f ms/position".format(Locale.ROOT, recall, candidates.toDouble() / lists.coerceAtLeast(1), millis.toDouble() / positions.coerceAtLeast(1)) +
            " after-dot=$afterDot listed=$afterDotListed found=$afterDotFound projects=$projects restored=$restored assemblies=$assemblies" +
            " copy=${copyMillis / 1000}s restore=${restoreMillis / 1000}s index=${indexMillis / 1000}s indexing=${indexingMillis / 1000}s snapshot=${snapshotMillis / 1000}s"
    }

    /** The type names of the repository being exported ([NativeCSharpTypeNames.snapshot]), read once per repository. */
    private var solutionSnapshot: NativeCSharpTypeNames.Snapshot? = null

    /** Copies the repository's C# and project files into a temporary content root (the corpus stays untouched) and exports every selected file. */
    fun exportRepository(repoDir: File, writer: ExampleShards.Writer, stats: Stats) {
        val tmp = FileUtil.createTempDirectory("csml-", repoDir.name, true)
        val sources = ArrayList<File>()
        val all = ArrayList<Pair<File, String>>()     // (file, relative path) of every copyable file
        repoDir.walkTopDown().onEnter { d -> !d.name.startsWith(".") && d.name.lowercase() !in SKIPPED_DIRS }.forEach { f ->
            if (f.isFile && f.extension.lowercase() in COPIED_EXTENSIONS) all.add(f to f.relativeTo(repoDir).path)
        }
        // which .cs files are sampled (the first maxFiles sources by path) and which are copied at all (maxCopy)
        val csFiles = all.filter { it.first.extension.lowercase() == "cs" }
        val sampled = csFiles.filter { isSource(it.second, it.first) }.sortedBy { it.first.path }.let { if (options.maxFiles > 0) it.take(options.maxFiles) else it }
        val copied: Set<String> = if (options.maxCopy <= 0 || csFiles.size <= options.maxCopy) csFiles.map { it.second }.toSet() else {
            val rest = csFiles.map { it.second }.toMutableSet().also { it.removeAll(sampled.map { s -> s.second }.toSet()) }
            sampled.map { it.second }.toSet() + rest.shuffled(Random(options.seed)).take((options.maxCopy - sampled.size).coerceAtLeast(0))
        }
        val copyStarted = System.currentTimeMillis()
        for ((f, rel) in all) {
            if (f.extension.lowercase() == "cs" && rel !in copied) continue
            val dst = File(tmp, rel); dst.parentFile.mkdirs(); f.copyTo(dst)
            if (f.extension.lowercase() == "cs" && isSource(rel, f)) sources.add(dst)
        }
        // the MSBuild files of the repository, fetched apart from the corpus (which has only the sources): copied over, project files win
        options.projects?.let { File(it, repoDir.name) }?.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.forEach { f ->
            val dst = File(tmp, f.relativeTo(File(options.projects, repoDir.name)).path); dst.parentFile.mkdirs(); f.copyTo(dst, overwrite = true)
        }
        stats.copyMillis += System.currentTimeMillis() - copyStarted
        if (options.restore) restore(tmp, stats)
        VfsRootAccess.allowRootAccess(disposable, tmp.path)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp) ?: error("no VFS root for $tmp")
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        try {
            val indexingStarted = System.currentTimeMillis()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            stats.indexingMillis += System.currentTimeMillis() - indexingStarted
            if (options.restore) indexAssemblies(tmp, root, stats)
            val files = sources.sortedBy { it.path }.let { if (options.maxFiles > 0) it.take(options.maxFiles) else it }
            for (file in files) {
                val text = file.readText().replace("\r\n", "\n")
                if (isGenerated(text)) continue
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: continue
                exportFile(vf, text, writer, stats)
            }
        } finally {
            NativeCSharpTypeNames.setSnapshotForTests(null)
            solutionSnapshot = null
            PsiTestUtil.removeContentEntry(module, root)
            if (options.restore) AssemblyIndexService.getInstance(project).clearIndexes()
            FileUtil.delete(tmp)
        }
    }

    /**
     * `dotnet restore` of the copy: the solutions at the top (two levels down at most), else every project (capped); a repository without
     * project files gets one synthetic SDK project for the whole tree, so its files are at least compiled against the .NET runtime.
     * One restore budget per repository; a failure leaves the project unrestored and the export goes on.
     */
    private fun restore(tmp: File, stats: Stats) {
        val started = System.currentTimeMillis()
        var projects = tmp.walkTopDown().filter { it.isFile && it.extension.equals("csproj", ignoreCase = true) }.toList()
        if (projects.isEmpty()) {
            val synthetic = File(tmp, SYNTHETIC_PROJECT)
            synthetic.writeText(SYNTHETIC_PROJECT_TEXT)
            projects = listOf(synthetic)
        }
        stats.projects += projects.size
        val solutions = tmp.walkTopDown().maxDepth(2).filter { it.isFile && it.extension.lowercase() in SOLUTION_EXTENSIONS }.toList()
        val targets = solutions.ifEmpty { projects.take(MAX_RESTORED_PROJECTS) }
        val deadline = started + options.restoreTimeoutSec * 1000L
        for (target in targets) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) { println("ml: restore budget spent at ${target.name}"); break }
            runDotnet(target.parentFile, left, "restore", target.name, "--ignore-failed-sources", "-nologo", "-v", "q", "-p:TreatWarningsAsErrors=false")
        }
        stats.restored += projects.count { File(it.parentFile, "obj/project.assets.json").isFile }
        stats.restoreMillis += System.currentTimeMillis() - started
    }

    private fun runDotnet(directory: File, timeoutMillis: Long, vararg arguments: String) {
        val process = ProcessBuilder(listOf("dotnet") + arguments).directory(directory).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["DOTNET_CLI_TELEMETRY_OPTOUT"] = "1"; environment()["DOTNET_NOLOGO"] = "1"; environment()["DOTNET_SKIP_FIRST_TIME_EXPERIENCE"] = "1"; environment()["MSBUILDDISABLENODEREUSE"] = "1" }
            .start()
        if (!process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly().waitFor()
            println("ml: dotnet ${arguments.joinToString(" ")} in $directory: timed out after ${timeoutMillis / 1000} s")
        } else if (process.exitValue() != 0) println("ml: dotnet ${arguments.joinToString(" ")} in $directory: exit ${process.exitValue()}")
    }

    /** The assembly index of every project of the copy (what the IDE does in the background after a restore), synchronously. */
    private fun indexAssemblies(tmp: File, root: VirtualFile, stats: Stats) {
        val started = System.currentTimeMillis()
        // the assemblies are outside the content root: the reference packs of the SDK and the packages of the NuGet cache (the test VFS guards both)
        val dotnetRoot = io.github.dotnetsupport.cli.DotNetCli.findExecutable()?.let { runCatching { File(it).canonicalFile.parentFile }.getOrNull() }
        val nuget = System.getenv("NUGET_PACKAGES")?.let(::File) ?: File(System.getProperty("user.home"), ".nuget/packages")
        VfsRootAccess.allowRootAccess(disposable, *listOfNotNull(dotnetRoot?.path, nuget.path).toTypedArray())
        val service = AssemblyIndexService.getInstance(project)
        val projectFiles = tmp.walkTopDown().filter { it.isFile && it.extension.equals("csproj", ignoreCase = true) }
            .mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }.toList()
        service.refreshForTests(projectFiles)
        // the libraries are published to the IDE in a later event: let it through, then let the names of the dlls be indexed
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        stats.assemblies += projectFiles.maxOfOrNull { service.indexes(it).size } ?: 0
        stats.indexMillis += System.currentTimeMillis() - started
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
        if (options.typeSnapshot) {
            // the stub index of the solution read once per repository (a second on a big one); for the next file only the names declared in
            // the file exported before (its stubs were rebuilt by the edits) and in the next one are read again
            val snapshotStarted = System.currentTimeMillis()
            val snapshot = solutionSnapshot?.refresh(project, vf, emptyList()) ?: NativeCSharpTypeNames.snapshot(project, vf)
            solutionSnapshot = snapshot
            NativeCSharpTypeNames.setSnapshotForTests(snapshot)
            stats.snapshotMillis += System.currentTimeMillis() - snapshotStarted
        }
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
            val afterDot = CSharpMlFeatures.isAfterDot(tokens, i)
            if (afterDot) stats.afterDot++
            try {
                setText(document, modified)
                fixture.editor.caretModel.moveToOffset(caret)
                val items = fixture.completeBasic()
                if (items == null) { stats.single++; continue }     // one candidate was inserted directly: no list to learn from
                var candidates = items.map(NativeCSharpMlInfo::candidateOf).distinctBy { it.lookupString }
                if (candidates.isEmpty()) { stats.empty++; continue }
                if (afterDot) stats.afterDotListed++
                val chosenAll = candidates.indexOfFirst { it.lookupString == t.text }
                if (chosenAll >= 0 && afterDot) stats.afterDotFound++
                if (chosenAll < 0 || candidates.size < 2) { stats.noAnswer++; continue }
                if (candidates.size > options.maxCandidates) {
                    val answer = candidates[chosenAll]
                    candidates = (candidates.filterIndexed { idx, _ -> idx != chosenAll }.shuffled(rnd).take(options.maxCandidates - 1) + answer).shuffled(rnd)
                }
                candidates = CSharpMlFeatures.ordered(candidates)
                val chosen = candidates.indexOfFirst { it.lookupString == t.text }
                val language = CSharpMlFeatures.languageBlock(caret, afterDot, candidates)
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
        NativeCSharpTypeNames.setSnapshotForTests(null)
    }

    private fun setText(document: Document, text: String) {
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    companion object {
        private val SKIPPED_DIRS = setOf("bin", "obj", "node_modules", "packages")
        private val SOLUTION_EXTENSIONS = setOf("sln", "slnx")
        private const val MAX_RESTORED_PROJECTS = 40
        const val SYNTHETIC_PROJECT = "__ml_export.csproj"
        /** All the sources of the tree in one project for the newest runtime: no packages, but `string`, `List<T>` and the rest of the BCL resolve. */
        const val SYNTHETIC_PROJECT_TEXT = "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <PropertyGroup>\n    <TargetFramework>net10.0</TargetFramework>\n" +
            "    <LangVersion>latest</LangVersion>\n    <Nullable>enable</Nullable>\n    <EnableDefaultCompileItems>true</EnableDefaultCompileItems>\n  </PropertyGroup>\n</Project>\n"
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
