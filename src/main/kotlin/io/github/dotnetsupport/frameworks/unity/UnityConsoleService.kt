package io.github.dotnetsupport.frameworks.unity

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dotnetsupport.frameworks.unity.UnityConsole.Entry
import io.github.dotnetsupport.frameworks.unity.UnityConsole.Event
import io.github.dotnetsupport.frameworks.unity.UnityConsole.Severity
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The messages of the Unity editor's console in the IDE (UNITY_PLAN.md, step 2): the file the package writes, read twice a second while the
 * project is a Unity one; the window "Unity" shows [entries]. Kept while the IDE runs, as the console keeps them while the editor does:
 * another editor (another process in the first line of the file) clears them, a compilation clears the messages of the last one, Play mode
 * clears the rest when [clearOnPlay] says so.
 */
@Service(Service.Level.PROJECT)
class UnityConsoleService(private val project: Project) : Disposable {
    private val entries = ArrayList<Entry>()
    private var reader: UnityConsole.Reader? = null
    private var processId = 0L
    private var poll: ScheduledFuture<*>? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val notifyPending = AtomicBoolean()

    var clearOnPlay: Boolean
        get() = PropertiesComponent.getInstance(project).getBoolean(CLEAR_ON_PLAY, true)
        set(value) = PropertiesComponent.getInstance(project).setValue(CLEAR_ON_PLAY, value, true)

    fun start(root: File) {
        synchronized(this) {
            if (poll != null) return
            reader = UnityConsole.Reader(UnityConsole.file(root))
            poll = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(::read, 0, 500, TimeUnit.MILLISECONDS)
        }
    }

    private fun read() {
        if (project.isDisposed) return
        val events = synchronized(this) { reader }?.read().orEmpty()
        if (events.isNotEmpty() && apply(events)) changed()
    }

    /** Takes [events] in; true when what the window shows has changed. */
    fun apply(events: List<Event>): Boolean = synchronized(this) {
        var changed = false
        for (event in events) {
            when (event) {
                is Event.Start -> {
                    // the file begun again by the same editor (it grew too big) keeps the messages; another editor's does not
                    if (event.processId != processId && processId != 0L && entries.isNotEmpty()) {
                        entries.clear()
                        changed = true
                    }
                    processId = event.processId
                }
                Event.CompileStarted -> changed = entries.removeIf { it.compiler } || changed
                Event.PlayStarted -> if (clearOnPlay) changed = entries.removeIf { !it.compiler } || changed
                is Event.Message -> {
                    entries += event.entry
                    changed = true
                }
            }
        }
        if (entries.size > MAX_ENTRIES) entries.subList(0, entries.size - MAX_ENTRIES + MAX_ENTRIES / 10).clear()
        changed
    }

    fun entries(): List<Entry> = synchronized(this) { ArrayList(entries) }

    fun count(severity: Severity): Int = synchronized(this) { entries.count { it.severity == severity } }

    fun clear() {
        synchronized(this) { entries.removeIf { !it.compiler } }
        changed()
    }

    /** [listener] is called on the EDT after the messages changed, until [parent] is disposed. */
    fun subscribe(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    private fun changed() {
        if (!notifyPending.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            notifyPending.set(false)
            listeners.forEach { it() }
        }, project.disposed)
    }

    override fun dispose() {
        poll?.cancel(false)
    }

    companion object {
        private const val CLEAR_ON_PLAY = "dotnet.unity.log.clearOnPlay"

        /** A game that writes every frame fills this in minutes: the oldest tenth goes then. */
        const val MAX_ENTRIES = 20_000

        fun getInstance(project: Project): UnityConsoleService = project.service()
    }
}
