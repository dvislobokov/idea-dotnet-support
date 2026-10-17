package io.github.dotnetsupport.frameworks.unity

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.Project

/**
 * The names a Unity project defines in `ProjectSettings` (UNITY_PLAN.md, "плюшки"): tags and layers (`TagManager.asset`), the scenes of the
 * build (`EditorBuildSettings.asset`), the axes and buttons of the old input manager (`InputManager.asset`) — what the strings of
 * `CompareTag("…")`, `LayerMask.NameToLayer("…")`, `SceneManager.LoadScene("…")` and `Input.GetAxis("…")` name. Read as text (the assets
 * are YAML in text serialization, the default since Unity 2019), cached until the file changes.
 */
class UnityProjectSettings(val tags: List<String>, val layers: List<String>, val scenes: List<Scene>, val axes: List<String>) {
    class Scene(val name: String, val path: String, val enabled: Boolean)

    companion object {
        /** The tags every project has, before the ones of `TagManager.asset`. */
        val BUILT_IN_TAGS = listOf("Untagged", "Respawn", "Finish", "EditorOnly", "MainCamera", "Player", "GameController")

        /** By the path of `ProjectSettings`: the settings and the stamps of the three files they were read from (not a CachedValue on a VirtualFile: that leaks). */
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<Long>, UnityProjectSettings>>()

        @Suppress("UNUSED_PARAMETER")
        fun of(project: Project, root: VirtualFile): UnityProjectSettings {
            val settings = root.findChild("ProjectSettings") ?: return UnityProjectSettings(BUILT_IN_TAGS, emptyList(), emptyList(), emptyList())
            val stamps = FILES.map { name -> settings.findChild(name)?.modificationStamp ?: -1L }
            cache[settings.path]?.takeIf { it.first == stamps }?.let { return it.second }
            return read(settings).also { cache[settings.path] = stamps to it }
        }

        private val FILES = listOf("TagManager.asset", "EditorBuildSettings.asset", "InputManager.asset")

        private fun read(settings: VirtualFile): UnityProjectSettings {
            fun text(name: String): String = settings.findChild(name)?.let { runCatching { VfsUtilCore.loadText(it) }.getOrNull() }.orEmpty()
            val tagManager = text("TagManager.asset")
            return UnityProjectSettings(
                BUILT_IN_TAGS + listItems(tagManager, "tags").filter { it !in BUILT_IN_TAGS },
                listItems(tagManager, "layers").filter { it.isNotBlank() },
                scenes(text("EditorBuildSettings.asset")),
                Regex("""^\s*m_Name:\s*(.+?)\s*$""", RegexOption.MULTILINE).findAll(text("InputManager.asset")).map { it.groupValues[1] }.distinct().toList(),
            )
        }

        /** The items of a YAML list `key:` (`  - Enemy` lines up to the next key); `key: []` is empty. */
        fun listItems(text: String, key: String): List<String> {
            val lines = text.lines()
            val start = lines.indexOfFirst { it.trim() == "$key:" }
            if (start < 0) return emptyList()
            val indent = lines[start].indexOfFirst { !it.isWhitespace() }
            val items = ArrayList<String>()
            for (line in lines.drop(start + 1)) {
                val trimmed = line.trimStart()
                val at = line.length - trimmed.length
                if (trimmed.startsWith("- ") || trimmed == "-") {
                    if (at < indent) break
                    items += trimmed.removePrefix("-").trim()
                } else if (trimmed.isNotEmpty()) break
            }
            return items
        }

        /** `- enabled: 1` / `path: Assets/Scenes/Boot.unity` of `m_Scenes`: the scene's name is its file name. */
        fun scenes(text: String): List<Scene> {
            val found = ArrayList<Scene>()
            var enabled = true
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("- enabled:")) enabled = trimmed.substringAfter(':').trim() == "1"
                if (trimmed.startsWith("path:") && trimmed.endsWith(".unity")) {
                    val path = trimmed.substringAfter(':').trim()
                    found += Scene(path.substringAfterLast('/').removeSuffix(".unity"), path, enabled)
                }
            }
            return found
        }
    }
}

/** Which names a string argument stands for, by the call it is in. */
enum class UnityStringKind(val noun: String) {
    TAG("tag"), LAYER("layer"), SCENE("scene"), AXIS("input axis or button");

    companion object {
        /**
         * The kind of the string literal whose text before the caret ends [line] (the text of the line up to it, the opening quote last):
         * `CompareTag("`, `.tag == "`, `GameObject.FindWithTag("`, `LayerMask.NameToLayer("`, `LayerMask.GetMask("A", "`,
         * `SceneManager.LoadScene("`, `Input.GetAxis("`.
         */
        fun at(line: CharSequence): UnityStringKind? = PATTERNS.firstOrNull { (regex, _) -> regex.containsMatchIn(line) }?.second

        private val PATTERNS = listOf(
            Regex("""(\bCompareTag|\bFindWithTag|\bFindGameObjectWithTag|\bFindGameObjectsWithTag)\(\s*"[^"]*$""") to TAG,
            Regex("""\btag\s*[!=]=\s*"[^"]*$""") to TAG,
            Regex("""\btag\s*=\s*"[^"]*$""") to TAG,
            Regex("""\bLayerMask\.NameToLayer\(\s*"[^"]*$""") to LAYER,
            Regex("""\bLayerMask\.GetMask\((\s*"[^"]*"\s*,)*\s*"[^"]*$""") to LAYER,
            Regex("""\bSceneManager\.(LoadScene|LoadSceneAsync|GetSceneByName|UnloadSceneAsync)\(\s*"[^"]*$""") to SCENE,
            Regex("""\bInput\.(GetAxis|GetAxisRaw|GetButton|GetButtonDown|GetButtonUp)\(\s*"[^"]*$""") to AXIS,
        )
    }

    fun names(settings: UnityProjectSettings): List<String> = when (this) {
        TAG -> settings.tags
        LAYER -> settings.layers
        SCENE -> settings.scenes.map { it.name }
        AXIS -> settings.axes
    }
}
