package io.github.dotnetsupport.csharp.lang

import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.FileAttribute
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The parse options a file was last given ([CSharpPreprocessorSymbols.KEY], [CSharpLanguageLevel.KEY]), kept in an attribute of the file
 * system: the keys themselves live in memory, the stubs of the index on disk. A new session of the IDE parsed a file with the defaults until
 * the host put its keys again, and met the stubs the index made with the keys of the last session — "PSI and index do not match" (an IDE
 * error per file; 98 at the opening of a Unity project, 2026-10-18), and for a file whose keys the host now leaves unset it stayed so. With
 * the saved options the first parse of a session is the one the index has.
 */
object CSharpSavedParseOptions {
    /** What was saved: the symbols (null: none were put, the defaults hold) and the language version. */
    class Saved(val symbols: Set<String>?, val level: CSharpLanguageVersion?)

    private val ATTRIBUTE = FileAttribute("csharp.parseOptions", 1, false)

    /** What was read (or written) in this session, so the attribute is read once per file. */
    private val CACHE: Key<Saved> = Key.create("csharp.savedParseOptions")

    private val NONE = Saved(null, null)

    /** The saved options of [file]; null when nothing was ever saved for it (or it is not a file of the disk). */
    fun of(file: VirtualFile): Saved? {
        file.getUserData(CACHE)?.let { return it.takeUnless { saved -> saved === NONE } }
        if (file !is NewVirtualFile) return null
        val saved = runCatching {
            ATTRIBUTE.readFileAttribute(file)?.use { input -> read(input) }
        }.getOrNull()
        file.putUserData(CACHE, saved ?: NONE)
        return saved
    }

    /** Saves [symbols] and [level] for [file] (what the host puts under the keys). */
    fun save(file: VirtualFile, symbols: Set<String>?, level: CSharpLanguageVersion?) {
        val saved = Saved(symbols, level)
        file.putUserData(CACHE, saved)
        if (file !is NewVirtualFile) return
        runCatching { ATTRIBUTE.writeFileAttribute(file).use { output -> write(output, saved) } }
    }

    private fun read(input: DataInputStream): Saved {
        val symbols = if (input.readBoolean()) (0 until input.readInt()).mapTo(LinkedHashSet()) { input.readUTF() } else null
        val level = input.readUTF().takeIf { it.isNotEmpty() }?.let { name -> CSharpLanguageVersion.entries.firstOrNull { it.name == name } }
        return Saved(symbols, level)
    }

    private fun write(output: DataOutputStream, saved: Saved) {
        output.writeBoolean(saved.symbols != null)
        saved.symbols?.let { symbols ->
            output.writeInt(symbols.size)
            symbols.forEach(output::writeUTF)
        }
        output.writeUTF(saved.level?.name.orEmpty())
    }
}
