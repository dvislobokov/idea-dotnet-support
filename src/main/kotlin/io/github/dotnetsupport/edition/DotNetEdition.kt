package io.github.dotnetsupport.edition

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.PluginLog
import org.jetbrains.annotations.TestOnly
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The edition the plugin works in (the user's decision 2026-10-17): in GIGA IDE the paid features — the allocation profiler, the monitor's
 * charts beyond CPU, memory and GC — work in its Pro mode only; in every other IDE everything works as before. The mode is GIGA IDE's own,
 * asked by reflection through the class loader of its plugin `com.gigaide.pro` (`PluginVerificationService.getWorkMode()`): the plugin
 * depends on nothing of GIGA IDE and loads the same everywhere. Asked anew every time (Pro is activated while the IDE runs); only the method
 * found is kept. A development build made with `-PeditionChecks=false` carries the resource `edition/unlocked` and is Pro everywhere.
 */
enum class EditionMode {
    /** GIGA IDE in Pro mode, for a person or a company. */
    PRO,

    /** GIGA IDE on its trial: the paid features work, as GIGA IDE lets its own commercial plugins work. */
    TRIAL,

    /** GIGA IDE without Pro: the paid features are off. */
    FREE,

    /** Not GIGA IDE (IntelliJ IDEA, GoLand...), or GIGA IDE with its Pro plugin off: everything works. */
    OTHER_IDE,
}

object DotNetEdition {
    private const val PRO_PLUGIN = "com.gigaide.pro"
    private const val SERVICE = "com.gigaide.pro.api.PluginVerificationService"
    private const val LOG_CATEGORY = "edition"

    /** The action of GIGA IDE that activates Pro (Help | Activate PRO mode); null outside GIGA IDE. */
    const val ACTIVATION_ACTION = "com.gigaide.pro.OpenActivationDialog"

    /** What a feature that is off says, in its tooltip or in place of its panel. */
    const val PRO_ONLY = "Available in GIGA IDE Pro"

    @Volatile private var forTests: EditionMode? = null
    private val methods = ConcurrentHashMap<ClassLoader, Method>()
    private val logged = ConcurrentHashMap.newKeySet<String>()

    /** A development build (`-PeditionChecks=false`): the checks are off, the plugin works as in Pro. */
    val isUnlockedBuild: Boolean by lazy { DotNetEdition::class.java.getResource("/edition/unlocked") != null }

    fun mode(): EditionMode {
        forTests?.let { return it }
        if (isUnlockedBuild) return EditionMode.PRO
        val plugin = PluginManagerCore.getPlugin(PluginId.getId(PRO_PLUGIN))
        if (plugin == null || !plugin.isEnabled) return EditionMode.OTHER_IDE
        return try {
            val loader = plugin.pluginClassLoader ?: return EditionMode.OTHER_IDE
            val method = methods.getOrPut(loader) { loader.loadClass(SERVICE).getMethod("getWorkMode") }
            val service = ApplicationManager.getApplication().getService(method.declaringClass)
            modeOf((method.invoke(service) as? Enum<*>)?.name)
        } catch (e: Exception) {
            // GIGA IDE changed its API: the paid features are off rather than given away, and the log says why, once
            logOnce("the mode of GIGA IDE could not be read (${e.javaClass.simpleName}: ${e.message}), working as without Pro")
            EditionMode.FREE
        }
    }

    /** GIGA IDE's `WorkMode` by its name: PRO_PERSON / PRO_COMPANY, TRIAL, COMMUNITY; one this plugin does not know is not Pro. */
    fun modeOf(workMode: String?): EditionMode = when (workMode) {
        "PRO_PERSON", "PRO_COMPANY" -> EditionMode.PRO
        "TRIAL" -> EditionMode.TRIAL
        "COMMUNITY" -> EditionMode.FREE
        null -> EditionMode.FREE.also { logOnce("GIGA IDE gave no work mode, working as without Pro") }
        else -> EditionMode.FREE.also { logOnce("unknown work mode of GIGA IDE '$workMode', working as without Pro") }
    }

    /** GIGA IDE with Pro (or its trial). */
    fun isPro(): Boolean = mode().let { it == EditionMode.PRO || it == EditionMode.TRIAL }

    /** GIGA IDE without Pro: the paid features are off. Everywhere else false — outside GIGA IDE nothing is off. */
    fun isFree(): Boolean = mode() == EditionMode.FREE

    /** The action that activates Pro, when the IDE has one. */
    fun activationAction(): AnAction? = ActionManager.getInstance().getAction(ACTIVATION_ACTION)

    @TestOnly
    fun setModeForTests(mode: EditionMode?) {
        forTests = mode
    }

    private fun logOnce(text: String) {
        if (logged.add(text)) PluginLog.warn(LOG_CATEGORY, text)
    }

    internal fun announceUnlocked(project: Project) {
        if (!isUnlockedBuild || !announced.compareAndSet(false, true)) return
        PluginLog.warn(LOG_CATEGORY, "edition checks are off in this build (-PeditionChecks=false): a development build, not for the users")
        DotNetCli.notifyInfo(project, "Edition checks are off", "This is a development build of C# Project Support: the features of GIGA IDE Pro work without Pro.")
    }

    private val announced = AtomicBoolean(false)
}

/** A development build says so at the start, once a session: a build like that must not reach the users unnoticed. */
class DotNetEditionStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!ApplicationManager.getApplication().isUnitTestMode) DotNetEdition.announceUnlocked(project)
    }
}
