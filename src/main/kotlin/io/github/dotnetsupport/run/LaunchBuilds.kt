package io.github.dotnetsupport.run

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * What to build before launches that start together (a compound configuration, Run N Projects, launches started one after another
 * quickly), so that every launch then runs `dotnet run --no-build`: the projects of the launches are built at once, shared dependencies once.
 *
 * Measured on the playground (Web and Worker, both reference Lib; SDK 10.0.401, warm build servers, median of three, seconds):
 * a solution filter of both builds in 1.9 (clean) / 2.1 (nothing changed) / 1.9 (Lib changed), the two projects one after another in
 * 3.6 / 3.2 / 4.2, two `dotnet build` at once (what every `dotnet run` of a compound did) in 3.3 / 1.9 / 3.1, racing for `Lib/obj`.
 * So the projects of one solution go to one build of a solution filter; the rest (a project outside the solutions, a framework chosen
 * for a multi-targeted project, which a solution build cannot take) one after another.
 */
object LaunchBuildPlan {
    /** A launch waiting for its build: the project, its `--framework` arguments, and whether it runs `dotnet watch` (which builds itself anyway). */
    data class Member(val projectPath: String, val frameworkArguments: List<String> = emptyList(), val watch: Boolean = false)

    /** One build: a solution filter of [projects] when [solutionPath] is set, else the one project. */
    data class Step(val projects: List<String>, val solutionPath: String? = null, val frameworkArguments: List<String> = emptyList()) {
        val isFilter: Boolean get() = solutionPath != null
    }

    /**
     * The builds for [members], in order. Nothing when only `dotnet watch` launches are there: each builds and rebuilds on its own.
     * [solutionOf] gives a solution that has every one of the projects, or null.
     */
    fun plan(members: List<Member>, solutionOf: (List<String>) -> String?): List<Step> {
        if (members.all { it.watch }) return emptyList()
        val steps = ArrayList<Step>()
        members.groupBy { it.frameworkArguments }.forEach { (framework, group) ->
            val paths = group.map { it.projectPath }.distinctBy(::key)
            val solution = if (framework.isEmpty() && paths.size > 1) solutionOf(paths) else null
            if (solution != null) steps += Step(paths, solution)
            else paths.forEach { steps += Step(listOf(it), frameworkArguments = framework) }
        }
        return steps
    }

    /**
     * The text of a `.slnf` in [filterDirectory] with [projects] of [solutionPath]: projects relative to the solution, as Visual Studio
     * writes them, and the solution relative to the filter (MSBuild takes an absolute path too, the reader of filters of the plugin does not).
     */
    fun filterJson(solutionPath: String, projects: List<String>, filterDirectory: String): String {
        val solutionFile = Path.of(solutionPath).toAbsolutePath()
        val directory = solutionFile.parent
        val list = JsonArray().apply { projects.forEach { add(directory.relativize(Path.of(it).toAbsolutePath()).toString().replace('/', '\\')) } }
        val filterDir = Path.of(filterDirectory).toAbsolutePath()
        val path = if (filterDir.root == solutionFile.root) filterDir.relativize(solutionFile).toString() else solutionFile.toString()
        val solution = JsonObject().apply {
            addProperty("path", path.replace('/', '\\'))
            add("projects", list)
        }
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(JsonObject().apply { add("solution", solution) })
    }

    /** `Web+Worker.slnf`: the Build tool window names the build after the file. */
    fun filterName(projects: List<String>): String =
        projects.joinToString("+") { File(it).nameWithoutExtension }.replace(Regex("""[^\w.+-]"""), "_").take(120) + ".slnf"

    fun key(path: String): String = File(path).absoluteFile.normalize().path.lowercase()
}

/** Coordinates the builds before launches: see [LaunchBuildPlan]. Builds wait for each other and for [DotNetDebugBuild.build]. */
@Service(Service.Level.PROJECT)
class LaunchBuilds(private val project: Project) {
    enum class Outcome { BUILT, NOT_BUILT, FAILED }

    class Request(val name: String, val member: LaunchBuildPlan.Member) {
        internal val outcome = CompletableFuture<Outcome>()
    }

    private val pending = ConcurrentLinkedQueue<Request>()

    /**
     * Blocking, for the thread of a step before a launch. The first launch to take the lock waits a moment for the launches started
     * together with it (a compound starts them one after another on the EDT), then builds for every launch that has come.
     */
    fun buildBefore(request: Request): Outcome {
        pending += request
        while (!request.outcome.isDone) {
            if (project.isDisposed) return Outcome.FAILED
            DotNetDebugBuild.exclusivelyOrNull(100) { if (!request.outcome.isDone) runBatch() }
        }
        return request.outcome.get()
    }

    private fun runBatch() {
        Thread.sleep(SETTLE_MILLIS)
        val batch = generateSequence { pending.poll() }.toList()
        if (batch.isEmpty()) return
        try {
            val steps = LaunchBuildPlan.plan(batch.map { it.member }, ::solutionOf)
            val names = batch.joinToString { it.name }
            PluginLog.info(DotNetDebugBuild.LOG_CATEGORY, "build before the launch of $names: ${steps.joinToString { step -> step.solutionPath?.let { "filter of ${File(it).name} with ${step.projects.size} projects" } ?: File(step.projects.single()).name }.ifEmpty { "nothing, dotnet watch builds itself" }}")
            if (steps.isEmpty()) return batch.forEach { it.outcome.complete(Outcome.NOT_BUILT) }
            val failed = steps.firstOrNull { !build(it) }
            if (failed != null) {
                val what = failed.solutionPath?.let { failed.projects.joinToString { p -> File(p).nameWithoutExtension } } ?: File(failed.projects.single()).nameWithoutExtension
                DotNetCli.notifyError(project, "Build Failed", "The build of $what before the launch has failed, so ${if (batch.size == 1) "'$names' was" else "none of $names was"} started. See the Build tool window.")
            }
            batch.forEach { it.outcome.complete(if (failed == null) Outcome.BUILT else Outcome.FAILED) }
        } catch (e: Throwable) {
            batch.forEach { it.outcome.complete(Outcome.FAILED) }
            if (e !is InterruptedException) PluginLog.warn(DotNetDebugBuild.LOG_CATEGORY, "the build before the launch has failed", e)
        }
    }

    private fun build(step: LaunchBuildPlan.Step): Boolean {
        val target = if (step.isFilter) filterFile(step) else LocalFileSystem.getInstance().refreshAndFindFileByPath(step.projects.single())
        return target != null && DotNetDebugBuild.buildNow(project, target)
    }

    private fun filterFile(step: LaunchBuildPlan.Step): VirtualFile? {
        val file = PathManager.getSystemDir().resolve("dotnet-support/launch-builds/${LaunchBuildPlan.filterName(step.projects)}").toFile()
        file.parentFile.mkdirs()
        file.writeText(LaunchBuildPlan.filterJson(step.solutionPath!!, step.projects, file.parent))
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.also { it.refresh(false, false) }
    }

    /** A solution of the opened directory that has every one of [projects]. */
    private fun solutionOf(projects: List<String>): String? {
        val solutions = SolutionService.getInstance(project)
        val wanted = projects.map(LaunchBuildPlan::key).toSet()
        return solutions.solutionFiles().firstOrNull { file ->
            val paths = solutions.solution(file).allProjects.mapNotNull { it.resolveFile(file)?.path?.let(LaunchBuildPlan::key) }.toSet()
            paths.containsAll(wanted)
        }?.path
    }

    companion object {
        /** How long the first launch waits for the others started with it; a compound starts its launches within a few milliseconds. */
        const val SETTLE_MILLIS = 300L

        fun getInstance(project: Project): LaunchBuilds = project.service()
    }
}
