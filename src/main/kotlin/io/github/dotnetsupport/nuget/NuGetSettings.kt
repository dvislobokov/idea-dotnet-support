package io.github.dotnetsupport.nuget

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import io.github.dotnetsupport.build.SmartRestore
import io.github.dotnetsupport.solution.SolutionService

/** Settings | Tools | .NET | NuGet. Machine-wide: how one likes packages searched and restored does not depend on the solution. */
@Service(Service.Level.APP)
@State(name = "DotNetNuGetSettings", storages = [Storage("dotnet-support.xml")])
class NuGetSettings : SimplePersistentStateComponent<NuGetSettings.Settings>(Settings()) {
    class Settings : BaseState() {
        var includePrerelease by property(false)

        /** `dotnet restore` by itself after a project file, `Directory.Packages.props` or `nuget.config` has changed. */
        var automaticRestore by property(true)
        var smartRestore by property(true)
        var noCache by property(false)
        var interactive by property(false)
    }

    var includePrerelease: Boolean
        get() = state.includePrerelease
        set(value) { state.includePrerelease = value }

    var automaticRestore: Boolean
        get() = state.automaticRestore
        set(value) { state.automaticRestore = value }

    var smartRestore: Boolean
        get() = state.smartRestore
        set(value) { state.smartRestore = value }

    var noCache: Boolean
        get() = state.noCache
        set(value) { state.noCache = value }

    var interactive: Boolean
        get() = state.interactive
        set(value) { state.interactive = value }

    /** Options of every `dotnet restore` the plugin runs. */
    fun restoreArguments(): List<String> = listOfNotNull("--no-cache".takeIf { noCache }, "--interactive".takeIf { interactive })

    companion object {
        fun getInstance(): NuGetSettings = service()
    }
}

/**
 * "Automatically restore missing packages when necessary": a change of what decides the packages (by hand, by a merge,
 * by a branch switch) is followed by a quiet `dotnet restore`, reported to the Log tab of the NuGet window.
 */
@Service(Service.Level.PROJECT)
class NuGetAutoRestore(private val project: Project) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    fun schedule() {
        // tests change project files all the time and must not start `dotnet`
        if (!NuGetSettings.getInstance().automaticRestore || ApplicationManager.getApplication().isUnitTestMode) return
        alarm.cancelAllRequests()
        alarm.addRequest({ restoreIfNeeded() }, DELAY_MS)
    }

    private fun restoreIfNeeded() {
        if (project.isDisposed) return
        val solution = SolutionService.getInstance(project).solutionFiles().firstOrNull() ?: return
        // `dotnet add package` of the plugin has restored already: the assets are newer than the project file it changed
        if (SmartRestore.isUpToDate(project, solution)) return
        NuGetService.getInstance(project).restore(listOf(solution))
    }

    override fun dispose() {}

    class Listener(private val project: Project) : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
            if (events.any { affectsPackages(it.path) }) project.service<NuGetAutoRestore>().schedule()
        }
    }

    companion object {
        private const val DELAY_MS = 2000
        private val FILE_NAMES = setOf("directory.packages.props", "directory.build.props", "directory.build.targets", "nuget.config", "packages.lock.json", "global.json")

        fun affectsPackages(path: String): Boolean {
            val name = path.substringAfterLast('/').lowercase()
            if ("/obj/" in path || "/bin/" in path) return false
            return name in FILE_NAMES || name.endsWith(".csproj") || name.endsWith(".fsproj") || name.endsWith(".vbproj")
        }
    }
}
