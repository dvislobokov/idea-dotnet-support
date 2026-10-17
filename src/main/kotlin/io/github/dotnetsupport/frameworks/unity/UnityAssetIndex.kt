package io.github.dotnetsupport.frameworks.unity

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.IOUtil
import com.intellij.util.io.KeyDescriptor
import java.io.DataInput
import java.io.DataOutput

/**
 * What the text assets of Unity (scenes, prefabs, ScriptableObject assets, animations; serialized as "Force Text", the default) say about
 * the scripts (UNITY_PLAN.md, the asset index, as Rider reads them): a component of a script on a GameObject (`m_Script` of a
 * `MonoBehaviour` document: the GUID of the script's `.meta`) with the fields the inspector set, a UnityEvent's persistent call of a method
 * (`m_MethodName` with its `m_Target` / `m_TargetAssemblyTypeName`), an animation event (`functionName` of an `AnimationClip`).
 * A pure function of the text: the index and the tests call it alike.
 */
object UnityAssetParser {
    const val COMPONENT = 0
    const val HANDLER = 1
    const val ANIMATION_EVENT = 2

    /** A field of a component as the inspector set it: `speed: 5` (the value as written; empty for a nested value) at [offset] of the name. */
    class Field(val name: String, val value: String, val offset: Int)

    /**
     * One use of a script in an asset. [COMPONENT]: [name] is the GameObject (or the asset's own `m_Name`), [offset] the GUID of `m_Script`.
     * [HANDLER]: [name] is the method, [target] the type (`Demo.Door`) or `guid:` of the target's script, [offset] the method's name.
     * [ANIMATION_EVENT]: [name] is the method, [offset] its name.
     */
    class Usage(val kind: Int, val offset: Int, val name: String, val target: String = "", val fields: List<Field> = emptyList()) {
        override fun equals(other: Any?): Boolean = other is Usage && other.kind == kind && other.offset == offset && other.name == name && other.target == target
        override fun hashCode(): Int = offset * 31 + name.hashCode()
    }

    fun isUnityYaml(text: CharSequence): Boolean = text.startsWith("%YAML") && text.subSequence(0, minOf(text.length, 200)).contains("tag:unity3d.com")

    /** The keys of the index: `s:<guid>` for the components of a script, `m:<method>` for the handlers and animation events. */
    fun index(text: CharSequence): Map<String, List<Usage>> {
        if (!isUnityYaml(text)) return emptyMap()
        val result = HashMap<String, MutableList<Usage>>()
        val documents = documents(text)
        val byAnchor = documents.associateBy { it.anchor }
        for (document in documents) {
            when (document.type) {
                "MonoBehaviour" -> if (!document.stripped) document.script?.let { (guid, offset) ->
                    val gameObject = document.property("m_GameObject")?.let(::fileId)?.let(byAnchor::get)
                    val name = gameObject?.property("m_Name") ?: document.property("m_Name").orEmpty()
                    result.getOrPut("s:${guid.lowercase()}") { ArrayList() } += Usage(COMPONENT, offset, name, fields = document.fields)
                }
                "AnimationClip" -> for ((method, offset) in document.animationEvents) result.getOrPut("m:$method") { ArrayList() } += Usage(ANIMATION_EVENT, offset, method)
            }
            for (call in document.calls) {
                // the script of the component the call targets in this file, else the target's type as Unity 2020+ writes it
                val target = call.targetAnchor?.let(byAnchor::get)?.script?.first?.let { "guid:${it.lowercase()}" }
                    ?: call.typeName?.substringBefore(',')?.trim()?.replace('+', '.').orEmpty()
                result.getOrPut("m:${call.method}") { ArrayList() } += Usage(HANDLER, call.offset, call.method, target)
            }
        }
        return result
    }

    private class Call(val method: String, val offset: Int, val targetAnchor: String?, val typeName: String?)

    private class Document(val anchor: String, val stripped: Boolean) {
        var type = ""
        val properties = HashMap<String, String>()
        var script: Pair<String, Int>? = null
        val fields = ArrayList<Field>()
        val calls = ArrayList<Call>()
        val animationEvents = ArrayList<Pair<String, Int>>()
        fun property(name: String): String? = properties[name]
    }

    private val HEADER = Regex("""^--- !u!\d+ &(-?\d+)( stripped)?""")
    private val GUID = Regex("""guid: ([0-9a-fA-F]{32})""")
    private val FILE_ID = Regex("""fileID: (-?\d+)""")

    private fun fileId(value: String): String? = FILE_ID.find(value)?.groupValues?.get(1)?.takeIf { it != "0" }

    /** The documents of the file with the properties of their top level and what [index] needs from their nested values. */
    private fun documents(text: CharSequence): List<Document> {
        val documents = ArrayList<Document>()
        var current: Document? = null
        var fieldsStarted = false
        var pendingTarget: String? = null
        var pendingType: String? = null
        var lineStart = 0
        while (lineStart < text.length) {
            var lineEnd = lineStart
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            val line = text.subSequence(lineStart, lineEnd).toString().trimEnd('\r')
            if (line.startsWith("---")) {
                val match = HEADER.find(line)
                current = match?.let { Document(it.groupValues[1], it.groupValues[2].isNotEmpty()) }?.also(documents::add)
                fieldsStarted = false
                pendingTarget = null
                pendingType = null
            } else if (current != null) {
                val indent = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
                if (indent == 0 && line.endsWith(":")) current.type = line.dropLast(1)
                else if (indent == 2) {
                    val colon = line.indexOf(':', 2)
                    if (colon > 2) {
                        val key = line.substring(2, colon)
                        val value = line.substring(colon + 1).trim()
                        current.properties.putIfAbsent(key, value)
                        if (key == "m_Script") GUID.find(value)?.let { current.script = it.groupValues[1] to lineStart + line.indexOf(it.groupValues[1]) }
                        if (fieldsStarted && current.type == "MonoBehaviour" && !key.startsWith("m_")) current.fields += Field(key, value, lineStart + 2)
                        if (key == "m_EditorClassIdentifier") fieldsStarted = true
                    }
                } else if (indent > 2) {
                    val body = line.substring(indent).removePrefix("- ")
                    val bodyStart = lineStart + line.length - body.length
                    when {
                        body.startsWith("m_Target: ") -> { pendingTarget = fileId(body); pendingType = null }
                        body.startsWith("m_TargetAssemblyTypeName: ") -> pendingType = body.substringAfter(": ").trim()
                        body.startsWith("m_MethodName: ") -> {
                            val method = body.substringAfter(": ").trim()
                            if (method.isNotEmpty()) current.calls += Call(method, bodyStart + "m_MethodName: ".length, pendingTarget, pendingType)
                        }
                        body.startsWith("functionName: ") && current.type == "AnimationClip" -> {
                            val method = body.substringAfter(": ").trim()
                            if (method.isNotEmpty()) current.animationEvents += method to bodyStart + "functionName: ".length
                        }
                    }
                }
            }
            lineStart = lineEnd + 1
        }
        return documents
    }

    /** The GUID of a `.meta` file (`guid: 0f1e…`). */
    fun metaGuid(text: CharSequence): String? = Regex("""(?m)^guid: ([0-9a-fA-F]{32})""").find(text)?.groupValues?.get(1)
}

/** The text of an indexed file as the PSI sees it: UTF-8, no BOM, `\n` line ends (the offsets of the index are offsets of the PSI). */
internal fun FileContent.unityText(limit: Int = Int.MAX_VALUE): String {
    val bytes = content
    val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
    return String(bytes, start, minOf(bytes.size - start, limit), Charsets.UTF_8).replace("\r\n", "\n")
}

/** The extensions of the text assets that refer to scripts. */
internal val UNITY_ASSET_EXTENSIONS = setOf("unity", "prefab", "asset", "anim", "controller", "overridecontroller", "playable", "signal", "preset")

/** `s:<script guid>` → the components of the script, `m:<method>` → the persistent calls and animation events of the method. */
class UnityAssetUsageIndex : FileBasedIndexExtension<String, List<UnityAssetParser.Usage>>() {
    override fun getName(): ID<String, List<UnityAssetParser.Usage>> = NAME
    override fun getIndexer(): DataIndexer<String, List<UnityAssetParser.Usage>, FileContent> = DataIndexer { content -> UnityAssetParser.index(content.unityText()) }
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getValueExternalizer(): DataExternalizer<List<UnityAssetParser.Usage>> = UsagesExternalizer
    override fun getVersion(): Int = 1
    override fun getInputFilter(): FileBasedIndex.InputFilter = FileBasedIndex.InputFilter { file -> file.extension?.lowercase() in UNITY_ASSET_EXTENSIONS }
    override fun dependsOnFileContent(): Boolean = true
    // the scenes of a game are megabytes: the limit of the IDE's code insight (2.5 MB) would leave them out
    override fun getFileTypesWithSizeLimitNotApplicable(): Collection<com.intellij.openapi.fileTypes.FileType> = SIZE_UNLIMITED

    companion object {
        val NAME: ID<String, List<UnityAssetParser.Usage>> = ID.create("dotnet.unity.assetUsages")
        internal val SIZE_UNLIMITED: List<com.intellij.openapi.fileTypes.FileType>
            get() = listOfNotNull(com.intellij.openapi.fileTypes.FileTypeManager.getInstance().findFileTypeByName("YAML"), com.intellij.openapi.fileTypes.UnknownFileType.INSTANCE, com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE)
    }

    private object UsagesExternalizer : DataExternalizer<List<UnityAssetParser.Usage>> {
        override fun save(out: DataOutput, value: List<UnityAssetParser.Usage>) {
            out.writeInt(value.size)
            for (usage in value) {
                out.writeByte(usage.kind)
                out.writeInt(usage.offset)
                IOUtil.writeUTF(out, usage.name)
                IOUtil.writeUTF(out, usage.target)
                out.writeInt(usage.fields.size)
                for (field in usage.fields) {
                    IOUtil.writeUTF(out, field.name)
                    IOUtil.writeUTF(out, field.value.take(200))
                    out.writeInt(field.offset)
                }
            }
        }

        override fun read(input: DataInput): List<UnityAssetParser.Usage> = List(input.readInt()) {
            val kind = input.readByte().toInt()
            val offset = input.readInt()
            val name = IOUtil.readUTF(input)
            val target = IOUtil.readUTF(input)
            val fields = List(input.readInt()) { UnityAssetParser.Field(IOUtil.readUTF(input), IOUtil.readUTF(input), input.readInt()) }
            UnityAssetParser.Usage(kind, offset, name, target, fields)
        }
    }
}

/** The GUID of a `.meta` file → that file: which asset or script an asset's `guid:` names. */
class UnityMetaGuidIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME
    override fun getIndexer(): DataIndexer<String, Void?, FileContent> = DataIndexer { content ->
        UnityAssetParser.metaGuid(content.unityText(limit = 1024))?.let { mapOf(it.lowercase() to null) } ?: emptyMap()
    }
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getVersion(): Int = 1
    override fun getInputFilter(): FileBasedIndex.InputFilter = FileBasedIndex.InputFilter { file -> file.name.endsWith(".meta", ignoreCase = true) && !file.isDirectory }
    override fun dependsOnFileContent(): Boolean = true
    override fun getFileTypesWithSizeLimitNotApplicable(): Collection<com.intellij.openapi.fileTypes.FileType> = UnityAssetUsageIndex.SIZE_UNLIMITED

    companion object {
        val NAME: ID<String, Void> = ID.create("dotnet.unity.metaGuids")
    }
}

/** What the asset index answers: the GUID of a script, the asset of a GUID, the uses of a script or a method in the assets. */
object UnityAssets {
    /** A use found in [file]. */
    class Located(val file: VirtualFile, val usage: UnityAssetParser.Usage)

    /** The GUID of the `.meta` beside [file] (lowercase); null when there is none. */
    fun guidOf(file: VirtualFile): String? {
        val meta = file.parent?.findChild(file.name + ".meta") ?: return null
        // the lenses ask for every declaration on every pass: read the .meta again only when it changed
        val cached = guids[meta.path]
        if (cached != null && cached.first == meta.modificationStamp) return cached.second
        val guid = runCatching { UnityAssetParser.metaGuid(VfsUtilCore.loadText(meta)) }.getOrNull()?.lowercase()
        guids[meta.path] = meta.modificationStamp to guid
        return guid
    }

    private val guids = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String?>>()

    /** The asset (or script) whose `.meta` has [guid]; null when the project has none (or the IDE is indexing). */
    fun fileOf(project: Project, guid: String): VirtualFile? {
        if (DumbService.isDumb(project)) return null
        val metas = FileBasedIndex.getInstance().getContainingFiles(UnityMetaGuidIndex.NAME, guid.lowercase(), GlobalSearchScope.allScope(project))
        return metas.firstNotNullOfOrNull { meta -> meta.parent?.findChild(meta.name.removeSuffix(".meta").removeSuffix(".META")) }
    }

    /** Whether some `.meta` of the project has [guid]. */
    fun isKnownGuid(project: Project, guid: String): Boolean =
        FileBasedIndex.getInstance().getContainingFiles(UnityMetaGuidIndex.NAME, guid.lowercase(), GlobalSearchScope.allScope(project)).isNotEmpty()

    /** The uses under [key] (`s:<guid>`, `m:<method>`) in the assets of [scope], in a stable order (path, offset). */
    fun usages(project: Project, key: String, scope: GlobalSearchScope = GlobalSearchScope.allScope(project)): List<Located> {
        if (DumbService.isDumb(project)) return emptyList()
        val result = ArrayList<Located>()
        FileBasedIndex.getInstance().processValues(UnityAssetUsageIndex.NAME, key, null, { file, values ->
            values.mapTo(result) { Located(file, it) }
            true
        }, scope)
        return result.sortedWith(compareBy({ it.file.path }, { it.usage.offset }))
    }

    /** The components of the script with [guid] in the scenes, prefabs and assets. */
    fun components(project: Project, guid: String, scope: GlobalSearchScope = GlobalSearchScope.allScope(project)): List<Located> =
        usages(project, "s:${guid.lowercase()}", scope)

    /**
     * The persistent calls of [method] that target the class [typeName] (full name) or its script [guid], and — when [component] —
     * the animation events of that name (an animation calls the method on every component of its GameObject).
     */
    fun handlers(project: Project, method: String, typeName: String, guid: String?, component: Boolean, scope: GlobalSearchScope = GlobalSearchScope.allScope(project)): List<Located> =
        usages(project, "m:$method", scope).filter { located ->
            val usage = located.usage
            when (usage.kind) {
                UnityAssetParser.HANDLER -> usage.target == typeName || (guid != null && usage.target.equals("guid:$guid", ignoreCase = true))
                UnityAssetParser.ANIMATION_EVENT -> component
                else -> false
            }
        }

    /** How a list of uses reads in a lens: "Boot.unity" for one file, "3 assets" for several. */
    fun where(uses: List<Located>): String {
        val files = uses.map { it.file }.distinct()
        return if (files.size == 1) files.single().name else "${files.size} assets"
    }
}
