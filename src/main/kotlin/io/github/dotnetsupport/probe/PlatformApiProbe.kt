package io.github.dotnetsupport.probe

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.runners.ProgramRunner
import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.LicensingFacade
import java.lang.reflect.Member
import java.lang.reflect.Modifier

enum class ProbeStatus { OK, PARTIAL, MISSING, ERROR }

/** One thing looked for in the running IDE; [missing] lists the members of a class that are not there. */
data class ProbeCheck(val area: String, val kind: String, val name: String, val status: ProbeStatus, val details: String = "", val missing: List<String> = emptyList())

data class ProbeReport(val ide: Map<String, String>, val expectedSource: String, val checks: List<ProbeCheck>) {
    fun of(area: String, kind: String): List<ProbeCheck> = checks.filter { it.area == area && it.kind == kind }
}

/**
 * Tells whether the LSP and DAP modules of the platform are there in the IDE the plugin runs in, and how their API differs from
 * the one of IntelliJ IDEA 2026.1.4 (`platformProbe/expected.json`). Everything goes through reflection and names: the plugin
 * does not depend on these modules, so it loads where they are absent, and that is exactly the case to find out about.
 */
object PlatformApiProbe {
    const val EXTENSION_POINT = "extension point"
    const val REGISTRY_KEY = "registry key"
    const val SERVICE = "project service"
    const val RUNNER = "program runner"
    const val LIBRARY = "library class"
    const val CLASS = "class"
    const val PLUGIN_MODULE = "plugin module"

    /** Content modules of the plugin by the area they need: the prefix of their classes is how their extensions are told. */
    // the debugger needs no module of the platform any more: it is the plugin's own DAP client on XDebugger
    private val OWN_MODULES = mapOf("lsp" to "io.github.dotnetsupport.roslyn")

    private const val EXPECTED = "/platformProbe/expected.json"

    fun expected(): JsonObject = JsonParser.parseString(checkNotNull(javaClass.getResource(EXPECTED)) { EXPECTED }.readText()).asJsonObject

    fun run(project: Project?): ProbeReport {
        val expected = expected()
        val checks = ArrayList<ProbeCheck>()
        for ((area, element) in expected.getAsJsonObject("areas").entrySet()) {
            val spec = element.asJsonObject
            val finder = ClassFinder()
            for (point in spec.array("extensionPoints")) checks += extensionPoint(area, point.asJsonObject.get("name").asString, finder)
            OWN_MODULES[area]?.let { module ->
                checks += if (finder.ownModule != null) ProbeCheck(area, PLUGIN_MODULE, module, ProbeStatus.OK, "loaded: its extensions are registered")
                else ProbeCheck(area, PLUGIN_MODULE, module, ProbeStatus.MISSING, "not loaded: no extension of it is registered")
            }
            for (key in spec.array("registryKeys")) checks += registryKey(area, key.asString)
            for (runner in spec.array("programRunners")) checks += guarded(area, RUNNER, runner.asString) {
                if (ProgramRunner.findRunnerById(runner.asString) != null) ProbeCheck(area, RUNNER, runner.asString, ProbeStatus.OK) else null
            }
            for (service in spec.array("projectServices")) checks += guarded(area, SERVICE, service.asString) {
                val found = finder.find(service.asString) ?: return@guarded null
                when {
                    project == null -> ProbeCheck(area, SERVICE, service.asString, ProbeStatus.ERROR, "no project is open")
                    project.getService(found.type) != null -> ProbeCheck(area, SERVICE, service.asString, ProbeStatus.OK)
                    else -> ProbeCheck(area, SERVICE, service.asString, ProbeStatus.MISSING, "the class is there, the service is not registered")
                }
            }
            for (library in spec.array("libraryClasses")) checks += classCheck(area, LIBRARY, library.asString, null, finder)
            for ((name, description) in spec.getAsJsonObject("classes").entrySet()) {
                checks += classCheck(area, CLASS, name, description.asJsonObject.array("members").map { it.asString }, finder)
            }
        }
        return ProbeReport(ide(), expected.get("source").asString, checks)
    }

    /** The members of [type] that `javap -protected` would show, spelled as in expected.json; [expected] ones that are not among them. */
    fun missingMembers(type: Class<*>, expected: List<String>): List<String> = expected - members(type)

    fun members(type: Class<*>): Set<String> {
        fun Member.shown() = !isSynthetic && (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))
        return buildSet {
            type.declaredFields.filter { it.shown() }.forEach { add(it.name) }
            type.declaredMethods.filter { it.shown() && !it.isBridge }.forEach { add("${it.name}/${it.parameterCount}") }
            type.declaredConstructors.filter { it.shown() }.forEach { add("<init>/${it.parameterCount}") }
        }
    }

    fun toJson(report: ProbeReport): String {
        val root = JsonObject()
        root.add("ide", JsonObject().apply { report.ide.forEach { (key, value) -> addProperty(key, value) } })
        root.addProperty("comparedWith", report.expectedSource)
        for (area in report.checks.map { it.area }.distinct()) {
            val json = JsonObject()
            for (kind in listOf(EXTENSION_POINT, PLUGIN_MODULE, REGISTRY_KEY, SERVICE, RUNNER, LIBRARY)) {
                val checks = report.of(area, kind).ifEmpty { continue }
                json.add(kind.replace(' ', '_') + (if (kind.endsWith("s")) "es" else "s"), JsonObject().apply { checks.forEach { addProperty(it.name, it.status.name + if (it.details.isEmpty()) "" else ": " + it.details) } })
            }
            val classes = report.of(area, CLASS)
            json.add("classes", JsonObject().apply {
                addProperty("expected", classes.size)
                addProperty("ok", classes.count { it.status == ProbeStatus.OK })
                addProperty("visibleToTheModuleOfThePlugin", classes.count { VIA_MODULE in it.details })
                addProperty("notVisibleToThePlugin", classes.count { NOT_VISIBLE in it.details })
                add("missing", JsonArray().apply { classes.filter { it.status == ProbeStatus.MISSING }.forEach { add(it.name) } })
                add("partial", JsonObject().apply {
                    classes.filter { it.status == ProbeStatus.PARTIAL }.forEach { check -> add(check.name, JsonArray().apply { check.missing.forEach { add(it) } }) }
                })
                add("errors", JsonObject().apply { classes.filter { it.status == ProbeStatus.ERROR }.forEach { addProperty(it.name, it.details) } })
            })
            root.add(area, json)
        }
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
    }

    private const val NOT_VISIBLE = "not visible to the plugin"
    private const val VIA_MODULE = "visible to the module"

    private fun ide(): Map<String, String> {
        val info = ApplicationInfo.getInstance()
        val ultimate = PluginId.getId("com.intellij.modules.ultimate")
        val facade = runCatching { LicensingFacade.getInstance() }.getOrNull()
        return linkedMapOf(
            "name" to info.fullApplicationName,
            "build" to info.build.asString(),
            "productCode" to info.build.productCode,
            "ultimateModule" to when {
                PluginManagerCore.getPlugin(ultimate) == null -> "absent"
                PluginManagerCore.isDisabled(ultimate) -> "disabled"
                else -> "present"
            },
            // no name of the licensee here: the report is meant to be pasted elsewhere
            "license" to when {
                facade == null -> "no licensing facade"
                facade.isEvaluationLicense -> "evaluation"
                facade.licensedToMessage.isNullOrBlank() -> "none"
                else -> "licensed"
            },
            "javaRuntime" to System.getProperty("java.version", ""),
            "os" to System.getProperty("os.name", ""),
        )
    }

    private fun extensionPoint(area: String, name: String, finder: ClassFinder): ProbeCheck = guarded(area, EXTENSION_POINT, name) {
        val point = ApplicationManager.getApplication().extensionArea.getExtensionPointIfRegistered<Any>(name) ?: return@guarded null
        // the class of the point lives in the module looked for: its loader sees the rest of the API even when ours does not
        runCatching { point.javaClass.getMethod("getExtensionClass").invoke(point) as? Class<*> }.getOrNull()?.classLoader?.let(finder::add)
        val module = OWN_MODULES[area]
        val extensions = runCatching { point.extensionList.onEach { if (module != null && it.javaClass.name.startsWith("$module.")) finder.ownModule = it.javaClass.classLoader }.map { it.javaClass.name + (PluginManager.getPluginByClass(it.javaClass)?.let { plugin -> " [${plugin.pluginId}]" } ?: "") } }
        ProbeCheck(area, EXTENSION_POINT, name, ProbeStatus.OK, extensions.fold({ if (it.isEmpty()) "no extensions" else it.joinToString(", ") }, { "extensions: $it" }))
    }

    private fun registryKey(area: String, key: String): ProbeCheck = guarded(area, REGISTRY_KEY, key) {
        // an unknown key throws
        runCatching { Registry.get(key).asString() }.getOrNull()?.let { ProbeCheck(area, REGISTRY_KEY, key, ProbeStatus.OK, "= $it") }
    }

    private fun classCheck(area: String, kind: String, name: String, members: List<String>?, finder: ClassFinder): ProbeCheck = guarded(area, kind, name) {
        val found = finder.find(name) ?: return@guarded null
        val details = when (found.visibility) {
            Visibility.PLUGIN -> ""
            Visibility.OWN_MODULE -> "$VIA_MODULE ${OWN_MODULES[area]} of the plugin only"
            Visibility.NONE -> "$NOT_VISIBLE: needs a dependency on the module of the platform"
        }
        val missing = members?.let { missingMembers(found.type, it) }.orEmpty()
        if (missing.isEmpty()) ProbeCheck(area, kind, name, ProbeStatus.OK, details)
        else ProbeCheck(area, kind, name, ProbeStatus.PARTIAL, listOf("${missing.size} of ${members?.size} members are missing", details).filter { it.isNotEmpty() }.joinToString("; "), missing)
    }

    /** `null` of [check] means "not there"; anything thrown (a linkage error of a half-present module included) is a finding too. */
    private fun guarded(area: String, kind: String, name: String, check: () -> ProbeCheck?): ProbeCheck = try {
        check() ?: ProbeCheck(area, kind, name, ProbeStatus.MISSING)
    } catch (e: Throwable) {
        if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
        ProbeCheck(area, kind, name, ProbeStatus.ERROR, "${e.javaClass.simpleName}: ${e.message}")
    }

    private fun JsonObject.array(name: String): JsonArray = getAsJsonArray(name) ?: JsonArray()

    private enum class Visibility { PLUGIN, OWN_MODULE, NONE }

    private class Found(val type: Class<*>, val visibility: Visibility)

    /** Looks with the loader of the plugin first, then with the ones of the modules found so far and of every loaded plugin. */
    private class ClassFinder {
        private val own: ClassLoader = PlatformApiProbe::class.java.classLoader
        private val others = LinkedHashSet<ClassLoader>()

        /** The loader of the content module of the plugin made for this area, once an extension of it is met. */
        var ownModule: ClassLoader? = null
        private val plugins: List<ClassLoader> by lazy { PluginManagerCore.loadedPlugins.mapNotNull { it.pluginClassLoader }.distinct() }

        fun add(loader: ClassLoader) {
            others += loader
        }

        fun find(name: String): Found? {
            load(name, own)?.let { return Found(it, Visibility.PLUGIN) }
            ownModule?.let { module -> load(name, module)?.let { return Found(it, Visibility.OWN_MODULE) } }
            return (others.asSequence() + plugins.asSequence()).firstNotNullOfOrNull { load(name, it) }?.let { Found(it, Visibility.NONE) }
        }

        private fun load(name: String, loader: ClassLoader): Class<*>? = try {
            Class.forName(name, false, loader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
}
