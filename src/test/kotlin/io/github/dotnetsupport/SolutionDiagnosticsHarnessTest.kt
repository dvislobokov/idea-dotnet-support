package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.index.IndexerTool
import io.github.dotnetsupport.index.ProjectAssemblies
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticChecks
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.msbuild.MsBuildProject
import java.io.File

/**
 * The errors of the plugin on a real restored solution, every project against its own assemblies as the IDE resolves them
 * ([ProjectAssemblies.references] of its `project.assets.json`, the real indexer, [ProjectAssemblies.withoutConflicts]): a harness, not a
 * check (the false CS0246 / CS1061 of a live solution of libraries, 0.1.184). Runs only with `SOLUTION_HARNESS=<the folder of the .sln>`,
 * restored; the sources of all projects are seen by all, as in the Jellyfin harness. Errors of every file go to `build/solution-harness.txt`.
 */
class SolutionDiagnosticsHarnessTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private val root = System.getenv("SOLUTION_HARNESS")?.let(::File)?.takeIf { it.isDirectory }

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testDiagnostics() {
        val root = root ?: return
        val dotnetRoot = DotNetCli.findExecutable()?.let { File(it).canonicalFile.parentFile }
        val projects = root.walkTopDown().filter { it.isFile && it.extension == "csproj" && File(it.parentFile, "obj/project.assets.json").isFile }.toList()
        val references = projects.associateWith { csproj ->
            ProjectAssemblies.references(ProjectAssemblies.Request(File(csproj.parentFile, "obj/project.assets.json").readText(), csproj.parentFile, dotnetRoot,
                msbuild = MsBuildProject.parse(csproj.readText())))
        }
        val all = references.values.flatMap { it.forIndex }.distinct()
        val indexed = IndexerTool.getInstance().index(all, File("build/solution-harness-index").absoluteFile)
        val sets = references.mapKeys { it.key.parentFile.name }.mapValues { (name, found) ->
            val frameworks = found.libraries.filter { it.kind == ProjectAssemblies.LibraryKind.FRAMEWORK }.flatMapTo(HashSet()) { it.assemblies }
            val opened = found.forIndex.mapNotNull { dll -> indexed[dll]?.let { runCatching { AssemblyIndex.open(it.toPath()) }.getOrNull() }?.let { dll to it } }
            val kept = ProjectAssemblies.withoutConflicts(opened, { it.second.assemblyName.ifEmpty { it.first.path } }, { it.second.assemblyVersion }, { it.first in frameworks })
            AssemblyIndexSet(kept.map { it.second }, name)
        }
        val summary = StringBuilder()
        for ((csproj, found) in references) summary.append("# ${csproj.parentFile.name}: ${found.framework}, ${found.libraries.joinToString { "${it.kind} ${it.presentableName}" }}\n")
        val unmatched = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        CSharpSemanticEnvironment.setAssembliesForTests { file ->
            val path = file.viewProvider.virtualFile.path
            sets[path.removePrefix("/src/").substringBefore('/')] ?: null.also { unmatched += path }
        }

        val files = HashMap<String, CSharpFile>()
        for (csproj in projects) {
            csproj.parentFile.walkTopDown().onEnter { it.name != "bin" && (it.name != "obj" || it.parentFile == null) }
                .filter { it.isFile && it.extension == "cs" && "${File.separator}obj${File.separator}" !in it.path }.forEach { f ->
                    val path = f.relativeTo(root).invariantSeparatorsPath
                    files[path] = myFixture.addFileToProject(path, f.readText().replace("\r\n", "\n")) as CSharpFile
                }
        }
        // a control: an error the compiler surely reports, in a file of the first project, says the checks are not silent
        val control = projects.first().parentFile.name + "/HarnessControl.cs"
        files[control] = myFixture.addFileToProject(control, "class HarnessControl { void M() { var x = new System.Collections.Generic.List<int>(); x.Nope(); undefinedName(); } }") as CSharpFile
        val report = StringBuilder(summary)
        for (path in files.keys.sorted()) {
            val file = files[path]!!
            val text = file.text
            for (p in CSharpSemanticChecks(CSharpSemanticSession(project).resolver(file)).run().filter { !it.warning }.sortedBy { it.range.startOffset }) {
                val line = text.substring(0, p.range.startOffset).count { it == '\n' } + 1
                report.append("$path:$line ${p.code} [${text.substring(p.range.startOffset, p.range.endOffset).take(40)}] ${p.message.take(160)}\n")
            }
        }
        val probe = files[control]!!; report.append("# control: set ${CSharpSemanticEnvironment.assemblies(probe).indexes.size}, complete ${CSharpSemanticEnvironment.referencesComplete(probe)}, all ${CSharpSemanticChecks(CSharpSemanticSession(project).resolver(probe)).run().map { it.code }}\n")
        report.append("# files: ${files.size}, without a set of assemblies: ${unmatched.size} ${unmatched.take(3)}\n")
        println(report)
        File("build/solution-harness.txt").writeText(report.toString())
    }
}
