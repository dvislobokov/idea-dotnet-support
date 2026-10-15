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
 * With `SOLUTION_HARNESS_GENERATED=true` the projects also see what their source generators made (`generated/` of a build with
 * `-p:EmitCompilerGeneratedFiles=true`, byte for byte what CodeAnalysisHelper makes): the gate of RAZOR_PLAN.md, R0, on `.corpus/razor`.
 * `obj/` may be in the project or in `artifacts/obj/<project>` (UseArtifactsOutput, eShop); projects may lie at any depth (nopCommerce).
 * The C# the build wrote into `obj/` for the compiler is always seen, as the IDE sees it (CompilationModel, BuildGeneratedSources): the
 * global usings of ImplicitUsings, what Grpc.Tools made.
 */
class SolutionDiagnosticsHarnessTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private val root = System.getenv("SOLUTION_HARNESS")?.let(::File)?.takeIf { it.isDirectory }?.canonicalFile
    private val withGenerated = System.getenv("SOLUTION_HARNESS_GENERATED").toBoolean()

    /** The intermediate folder of [csproj] with its `project.assets.json`: `obj/` beside it, or `artifacts/obj/<name>/` above it. */
    private fun intermediate(csproj: File): File? {
        File(csproj.parentFile, "obj").takeIf { File(it, "project.assets.json").isFile }?.let { return it }
        return generateSequence(csproj.parentFile) { it.parentFile }.take(6)
            .map { File(it, "artifacts/obj/${csproj.nameWithoutExtension}") }.firstOrNull { File(it, "project.assets.json").isFile }
    }

    /** Where the build of [framework] put its files for the compiler: `<obj>/<Configuration>/<tfm>`, or `<obj>/debug[_tfm]` with artifacts. */
    private fun compileFolderOf(obj: File, framework: String?): File? {
        val found = obj.walkTopDown().maxDepth(2).filter { it.isDirectory && it.listFiles { f -> f.name.endsWith(".AssemblyInfo.cs") }.orEmpty().isNotEmpty() }.toList()
        return found.firstOrNull { framework != null && it.name.endsWith(framework) } ?: found.singleOrNull()
    }

    /** What the targets of the build wrote there for the compiler, as CodeAnalysisHelper's BuildFiles keeps them (no assembly info). */
    private fun buildSources(folder: File): List<File> = folder.walkTopDown().onEnter { it == folder || it.name != "generated" && it.name != "refint" && it.name != "ref" }
        .filter { it.isFile && it.extension == "cs" && !it.name.endsWith(".AssemblyInfo.cs") && !it.name.endsWith(".AssemblyAttributes.cs") && !it.name.endsWith(".RazorAssemblyInfo.cs") }
        .toList()

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
        val projects = root.walkTopDown().onEnter { it.name != "bin" && it.name != "obj" && it.name != "artifacts" && it.name != "node_modules" }
            .filter { it.isFile && it.extension == "csproj" && intermediate(it) != null }.toList()
        val references = projects.associateWith { csproj ->
            ProjectAssemblies.references(ProjectAssemblies.Request(File(intermediate(csproj)!!, "project.assets.json").readText(), csproj.parentFile, dotnetRoot,
                msbuild = MsBuildProject.parse(csproj.readText())))
        }
        // a file belongs to the project of the deepest folder of a project above it
        val folders = projects.associate { it.parentFile.relativeTo(root).invariantSeparatorsPath + "/" to it.parentFile.name }
        fun projectOf(path: String): String? = folders.keys.filter { path.startsWith(it) }.maxByOrNull { it.length }?.let(folders::get)
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
            projectOf(path.removePrefix("/src/"))?.let(sets::get) ?: null.also { unmatched += path }
        }

        val files = HashMap<String, CSharpFile>()
        val generatedPaths = HashSet<String>()
        val tooLarge = ArrayList<String>()
        // a file over the size the IDE parses is plain text to it (a docs project of MudBlazor has such): counted, left out
        fun add(path: String, text: String): Boolean {
            val psi = myFixture.addFileToProject(path, text) as? CSharpFile ?: return false.also { tooLarge += path }
            files[path] = psi
            return true
        }
        for (csproj in projects) {
            csproj.parentFile.walkTopDown().onEnter { it.name != "bin" && (it.name != "obj" || it.parentFile == null) }
                .filter { it.isFile && it.extension == "cs" && "${File.separator}obj${File.separator}" !in it.path }.forEach { f ->
                    val path = f.relativeTo(root).invariantSeparatorsPath
                    add(path, f.readText().replace("\r\n", "\n"))
                }
            // under the folder of the project, so they take the project's assemblies; kept apart from its sources in the report
            val compile = compileFolderOf(intermediate(csproj)!!, references[csproj]?.framework) ?: continue
            val generated = File(compile, "generated").takeIf { withGenerated && it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "cs" }?.toList().orEmpty()
            for (f in buildSources(compile) + generated) {
                val path = csproj.parentFile.relativeTo(root).invariantSeparatorsPath + "/~obj/" + f.relativeTo(compile).invariantSeparatorsPath
                if (add(path, f.readText().replace("\r\n", "\n"))) generatedPaths += path
            }
        }
        // a control: an error the compiler surely reports, in a file of the first project, says the checks are not silent
        val control = projects.first().parentFile.relativeTo(root).invariantSeparatorsPath + "/HarnessControl.cs"
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
        report.append("# files: ${files.size} (from obj ${generatedPaths.size}), too large for the IDE: ${tooLarge.size} ${tooLarge.take(3)}, without a set of assemblies: ${unmatched.size} ${unmatched.take(3)}\n")
        println(report)
        File("build/solution-harness.txt").writeText(report.toString())
    }
}
