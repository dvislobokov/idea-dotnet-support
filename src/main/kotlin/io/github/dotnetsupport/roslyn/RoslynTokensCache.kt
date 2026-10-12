package io.github.dotnetsupport.roslyn

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import org.eclipse.lsp4j.SemanticTokensLegend
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

private val LOG = logger<RoslynTokensCache>()

/**
 * Semantic tokens of the server by the content of a file, on disk and for every project of the machine: a file opens colored by Roslyn
 * while its solution is still loading (3 s on the playground, tens of seconds on a large solution), not by the heuristics of the
 * plugin. Used only until the server has loaded the solution; then its own answer replaces the cached one.
 *
 * The key is the text of the file and the legend of the server: `tools/roslyn-lsp/capture.py` has checked that the same text gives the
 * same tokens. What the text alone does not decide (whether a name of another file is a type) may be stale, which is acceptable for
 * a few seconds and is the reason the cache is never used after the load.
 */
@Service(Service.Level.APP)
class RoslynTokensCache {
    val store = RoslynTokenStore(File(PathManager.getSystemPath(), "dotnet-support/lsp-cache/semantic-tokens"))

    /** In the background: a write must not hold the answer of the server on its way to the editor. */
    fun put(key: String, data: List<Int>) {
        ApplicationManager.getApplication().executeOnPooledThread { store.put(key, data) }
    }
}

/** The files of [RoslynTokensCache]: one per key, the least recently used removed beyond [limitBytes]. A plain class for the tests. */
class RoslynTokenStore(private val directory: File, private val limitBytes: Long = LIMIT_BYTES) {
    fun get(key: String): List<Int>? {
        val file = File(directory, key)
        if (!file.isFile) return null
        return runCatching { decode(file.readBytes()) }.onFailure { LOG.info("Unreadable cached tokens $file", it) }.getOrNull()
            ?.also { file.setLastModified(System.currentTimeMillis()) }
    }

    fun contains(key: String): Boolean = File(directory, key).isFile

    fun put(key: String, data: List<Int>) {
        runCatching {
            directory.mkdirs()
            // a reader never sees half a file: written aside, then renamed
            val temporary = File(directory, "$key.tmp")
            temporary.writeBytes(encode(data))
            val target = File(directory, key)
            if (!temporary.renameTo(target)) {
                target.delete()
                temporary.renameTo(target) || temporary.delete()
            }
            evict(directory, limitBytes)
        }.onFailure { LOG.info("Cannot cache tokens in $directory", it) }
    }

    companion object {
        /** A format of the file other than this one is not read: the version goes into the key. */
        private const val FORMAT = 1
        const val LIMIT_BYTES = 64L * 1024 * 1024

        /** The legend of the server decides what the numbers of a token mean, so it is a part of the key. */
        fun legendKey(legend: SemanticTokensLegend?): String =
            listOf(legend?.tokenTypes.orEmpty().joinToString(","), legend?.tokenModifiers.orEmpty().joinToString(",")).joinToString("|")

        fun key(legendKey: String, text: CharSequence): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("$FORMAT\n$legendKey\n".toByteArray())
            digest.update(text.toString().toByteArray())
            return digest.digest().joinToString("") { "%02x".format(it) }.take(40)
        }

        /** The count, then the numbers: 4 bytes each, a file of 4 000 lines is ~700 KB against ~370 KB of JSON on the wire. */
        fun encode(data: List<Int>): ByteArray {
            val bytes = java.io.ByteArrayOutputStream(4 + data.size * 4)
            DataOutputStream(bytes).use { out ->
                out.writeInt(data.size)
                data.forEach(out::writeInt)
            }
            return bytes.toByteArray()
        }

        fun decode(bytes: ByteArray): List<Int> = DataInputStream(bytes.inputStream()).use { input ->
            val size = input.readInt()
            require(size >= 0 && size * 4L == bytes.size - 4L) { "a file of $size numbers is ${bytes.size} bytes" }
            List(size) { input.readInt() }
        }

        /** The least recently used files go first, until what is left fits into [limit]. */
        fun evict(directory: File, limit: Long) {
            val files = directory.listFiles { file -> file.isFile && !file.name.endsWith(".tmp") }?.sortedByDescending { it.lastModified() } ?: return
            var total = 0L
            for (file in files) {
                total += file.length()
                if (total > limit) file.delete()
            }
        }
    }
}
