package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticChecks
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import java.io.File

/**
 * The diagnostics of the plugin on real Jellyfin sources with the real assembly indexes (docs/compare/jellyfin-broken-2026-10-13.md):
 * a harness, not a check. Runs only with `JELLYFIN_HARNESS=<the clone>` and `LATENCY_HARNESS=<dir of real .dnix indexes>`; the
 * projects of `JELLYFIN_PROJECTS` (comma-separated folders) go into the light project, the breakages of `JELLYFIN_BREAK` (a file of
 * `path|old text|new text` lines) are applied to the copies, and every diagnostic of the broken files goes to `build/jellyfin-harness.txt`.
 */
class JellyfinDiagnosticsHarnessTest : BasePlatformTestCase() {
    private val root = System.getenv("JELLYFIN_HARNESS")?.let(::File)?.takeIf { it.isDirectory }
    private val indexes = System.getenv("LATENCY_HARNESS")?.let(::File)?.takeIf { it.isDirectory }

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val set = assemblies()
        CSharpSemanticEnvironment.setAssembliesForTests { set }
    }

    override fun tearDown() {
        try {
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun assemblies(): AssemblyIndexSet? {
        val dir = indexes ?: return null
        val byName = LinkedHashMap<String, AssemblyIndex>()
        // the cache holds every framework the sandbox ever indexed: of each assembly the newest version (the net10.0 ref pack of Jellyfin)
        fun version(index: AssemblyIndex) = index.assemblyVersion.split('.').map { it.toIntOrNull() ?: 0 }.fold(0L) { acc, part -> acc * 100000 + part }
        for (file in dir.listFiles { f -> f.name.endsWith(".dnix") }.orEmpty()) {
            val index = runCatching { AssemblyIndex.read(file.readBytes()) }.getOrNull() ?: continue
            val old = byName[index.assemblyName]
            if (old == null || version(index) > version(old)) byName[index.assemblyName] = index
        }
        return AssemblyIndexSet(byName.values.toList())
    }

    fun testDiagnostics() {
        val root = root ?: return
        if (indexes == null) return
        val breaks = System.getenv("JELLYFIN_BREAK")?.let(::File)?.readLines().orEmpty().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('|') }
        // `JELLYFIN_REPORT`: more files to report (a clone broken by `git apply` of a breakages diff)
        val broken = breaks.map { it[0] }.toSet() + System.getenv("JELLYFIN_REPORT")?.split(',').orEmpty().filter { it.isNotBlank() }
        val projects = (System.getenv("JELLYFIN_PROJECTS") ?: "Emby.Naming,MediaBrowser.Model,MediaBrowser.Common,Jellyfin.Data,src/Jellyfin.Extensions").split(',')
        val files = HashMap<String, CSharpFile>()
        for (project in projects) {
            File(root, project).walkTopDown().filter { it.isFile && it.extension == "cs" && "${File.separator}obj${File.separator}" !in it.path && "${File.separator}bin${File.separator}" !in it.path }.forEach { f ->
                val path = f.relativeTo(root).invariantSeparatorsPath
                var text = f.readText().replace("\r\n", "\n")
                for (b in breaks.filter { it[0] == path }) {
                    check(b[1] in text) { "no '${b[1]}' in $path" }
                    text = text.replace(b[1], b[2])
                }
                files[path] = myFixture.addFileToProject(path, text) as CSharpFile
            }
        }
        val report = StringBuilder()
        // `JELLYFIN_REPORT=*`: every file, only errors (false positives on a clone that builds)
        val everything = System.getenv("JELLYFIN_REPORT") == "*"
        for (path in if (everything) files.keys.sorted() else broken.sorted()) {
            val file = files[path] ?: continue
            val text = file.text
            report.append("== $path\n")
            for (p in CSharpSemanticChecks(CSharpSemanticSession(project).resolver(file)).run().filter { !everything || !it.warning }.sortedBy { it.range.startOffset }) {
                val line = text.substring(0, p.range.startOffset).count { it == '\n' } + 1
                report.append(if (everything) "$path:" else "").append("${p.code} $line [${text.substring(p.range.startOffset, p.range.endOffset).take(40)}] ${p.message.take(160)}\n")
            }
        }
        println(report)
        File("build/jellyfin-harness.txt").writeText(report.toString())
    }
}
