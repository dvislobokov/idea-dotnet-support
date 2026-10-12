package io.github.dotnetsupport.decompiler

import com.google.gson.JsonParser
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.vfs.WritingAccessProvider
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import io.github.dotnetsupport.cli.DotNetHelper
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.lang.CSharpFileType
import java.io.File
import java.security.MessageDigest
import java.util.function.Function
import javax.swing.JComponent

/**
 * A decompiled type is identified by the assembly it was asked of (as the project references it: a reference assembly, `System.Runtime`
 * for `System.String`), the metadata name of the type and `LangVersion` of the project (null: the latest C#).
 */
data class DecompiledKey(val assembly: String, val typeName: String, val languageVersion: String? = null) {
    /** `C:/.../System.Console.dll!/System.Console/Console.cs`, `...!/12.0/System.Console/Console.cs`: the path of [DecompiledFile]. */
    val path: String get() = FileUtil.toSystemIndependentName(assembly) + SEPARATOR + listOfNotNull(languageVersion, typeName, fileName(typeName)).joinToString("/")

    companion object {
        const val SEPARATOR = "!/"

        /** `Console.cs` for `System.Console`, `Inner.cs` for `Fixture.Box`1+Inner`1`: the name of the tab, as in Rider. */
        fun fileName(typeName: String): String = typeName.substringAfterLast('+').substringAfterLast('.').substringBefore('`') + ".cs"

        fun parse(path: String): DecompiledKey? {
            val at = path.lastIndexOf(SEPARATOR).takeIf { it > 0 } ?: return null
            val segments = path.substring(at + SEPARATOR.length).split('/')
            return when (segments.size) {
                2 -> DecompiledKey(path.substring(0, at), segments[0])
                3 -> DecompiledKey(path.substring(0, at), segments[1], segments[0])
                else -> null
            }.takeIf { it?.path == path }
        }
    }
}

/**
 * A decompiled type in the editor: C# (colors, folding, Structure, navigation inside), read-only, of its own file system ([DecompiledFileSystem])
 * rather than a bare LightVirtualFile, so that its URL leads back to it — the navigation history, Back after the tab is closed, and the
 * tabs reopened with the project find it again (from the cache on disk, [DecompiledFiles]).
 */
class DecompiledFile(val key: DecompiledKey, val decompiled: DecompiledType, val assemblyStamp: Long) :
    LightVirtualFile(DecompiledKey.fileName(key.typeName), CSharpFileType, decompiled.text, assemblyStamp) {
    init {
        isWritable = false
    }

    override fun getFileSystem(): VirtualFileSystem = DecompiledFileSystem.getInstance() ?: super.getFileSystem()
    override fun getPath(): String = key.path
    override fun getUrl(): String = VirtualFileManager.constructUrl(DecompiledFileSystem.PROTOCOL, path)
    override fun getPresentableUrl(): String = "${decompiled.assemblyName}: ${decompiled.typeName.replace('+', '.')}"
    override fun getParent(): VirtualFile? = null
    override fun toString(): String = "DecompiledFile(${key.path})"
}

/**
 * The decompiled files of the IDE: in memory (the last [MEMORY_SIZE], one instance per path, so that an editor and the history agree) and on
 * disk under the caches of the IDE (`dotnet-support/decompiled`), keyed by the path of the file; an entry is good while the assembly it was
 * asked of has the time it had (a rebuilt or updated assembly is decompiled again).
 */
object DecompiledFiles {
    private const val MEMORY_SIZE = 64

    /** Changed when the helper or the layout of the cache changes the text: an old entry on disk is not taken. */
    private const val FORMAT = 1

    private val memory = object : LinkedHashMap<String, DecompiledFile>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DecompiledFile>): Boolean = size > MEMORY_SIZE
    }

    @Volatile var diskRoot: File? = null

    private fun disk(): File = diskRoot ?: File(DotNetHelper.root(), "decompiled")

    /** The time of the assembly as the cache compares it: a rebuilt or updated assembly is another one. */
    fun stamp(assembly: String): Long = File(assembly).let { it.lastModified() * 31 + it.length() }

    fun find(key: DecompiledKey): DecompiledFile? = find(key.path)

    /** By the path of the file: memory, else disk; null for an assembly changed since (or gone) and for what was never decompiled. */
    fun find(path: String): DecompiledFile? {
        val key = DecompiledKey.parse(path) ?: return null
        val stamp = stamp(key.assembly)
        synchronized(memory) {
            memory[path]?.let { if (it.assemblyStamp == stamp) return it else memory.remove(path) }
        }
        val file = cacheFile(key)
        if (!file.isFile) return null
        val restored = runCatching {
            val json = JsonParser.parseString(file.readText()).asJsonObject
            if (json.get("format")?.asInt != FORMAT || json.get("stamp")?.asLong != stamp) return null
            DecompilerAnswers.parse(json.get("answer"))
        }.onFailure { PluginLog.warn(AssemblyDecompiler.LOG_CATEGORY, "The decompiled $path in the cache cannot be read: ${it.message}") }.getOrNull() ?: return null
        return remember(key, restored, stamp)
    }

    /** [decompiled] for [key], kept in memory and on disk; the file already made for the path when it is the same. */
    fun put(key: DecompiledKey, decompiled: DecompiledType): DecompiledFile {
        val stamp = stamp(key.assembly)
        val file = remember(key, decompiled, stamp)
        runCatching {
            val json = com.google.gson.JsonObject().apply {
                addProperty("format", FORMAT); addProperty("path", key.path); addProperty("stamp", stamp); add("answer", DecompilerAnswers.toJson(decompiled))
            }
            cacheFile(key).apply { parentFile.mkdirs() }.writeText(json.toString())
        }.onFailure { PluginLog.warn(AssemblyDecompiler.LOG_CATEGORY, "The decompiled ${key.path} cannot be cached on disk: ${it.message}") }
        return file
    }

    private fun remember(key: DecompiledKey, decompiled: DecompiledType, stamp: Long): DecompiledFile = synchronized(memory) {
        memory[key.path]?.takeIf { it.assemblyStamp == stamp && it.decompiled.text == decompiled.text } ?: DecompiledFile(key, decompiled, stamp).also { memory[key.path] = it }
    }

    fun clearMemory() = synchronized(memory) { memory.clear() }

    private fun cacheFile(key: DecompiledKey): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.path.lowercase().toByteArray(Charsets.UTF_8)).take(10).joinToString("") { "%02x".format(it) }
        return File(disk(), "$digest-${DecompiledKey.fileName(key.typeName).removeSuffix(".cs")}.json")
    }
}

/** `dotnet-decompiled://C:/.../System.Console.dll!/System.Console/Console.cs`: the files of [DecompiledFiles] by their URL. */
class DecompiledFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
    override fun getProtocol(): String = PROTOCOL
    override fun findFileByPath(path: String): VirtualFile? = DecompiledFiles.find(path)
    override fun refreshAndFindFileByPath(path: String): VirtualFile? = findFileByPath(path)
    override fun refresh(asynchronous: Boolean) = Unit
    override fun isReadOnly(): Boolean = true

    companion object {
        const val PROTOCOL = "dotnet-decompiled"

        fun getInstance(): DecompiledFileSystem? = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as? DecompiledFileSystem
    }
}

/** Decompiled code is not the code of the project: typing into it asks for nothing and changes nothing. */
class DecompiledWritingAccess(@Suppress("unused") private val project: Project) : WritingAccessProvider() {
    override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> = files.filterIsInstance<DecompiledFile>()
    override fun isPotentiallyWritable(file: VirtualFile): Boolean = file !is DecompiledFile
}

/** `Console.cs [System.Console]`: the tab of a decompiled type says which assembly it is from. */
class DecompiledTabTitle : EditorTabTitleProvider {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? = (file as? DecompiledFile)?.let { "${it.name} [${it.decompiled.assemblyName}]" }
}

/** As Rider says above a decompiled type: where it comes from, that it cannot be edited, and the way to the assembly. */
class DecompiledBanner : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val decompiled = (file as? DecompiledFile)?.decompiled ?: return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text = bannerText(decompiled)
                File(decompiled.assembly).takeIf { it.isFile }?.let { assembly ->
                    createActionLabel("Show Assembly in ${RevealFileAction.getFileManagerName()}") { RevealFileAction.openFile(assembly) }
                }
            }
        }
    }

    companion object {
        fun bannerText(decompiled: DecompiledType): String = buildString {
            append("Decompiled from ${decompiled.assemblyName}")
            if (decompiled.assemblyVersion.isNotEmpty()) append(" ${decompiled.assemblyVersion}")
            append(". Read-only")
        }
    }
}
