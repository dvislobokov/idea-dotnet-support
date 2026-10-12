package io.github.dotnetsupport.index

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.Inflater

/**
 * The XML documentation of an indexed assembly by the documentation ID, as `DocWriter` of `indexer/Program.cs` writes it (the
 * format is described there): blocks of entries compressed one by one, found by a binary search over the first ID of every block.
 * A few inflated blocks are kept: the documentation of a type and of its members is mostly in one.
 */
class AssemblyDocs private constructor(private val buffer: ByteBuffer) {
    val count: Int = buffer.getInt(24)
    private val blockCount = buffer.getInt(28)
    private val blockTable = buffer.getInt(32)
    private val firstIds = arrayOfNulls<ByteArray>(blockCount)
    private val inflated = object : LinkedHashMap<Int, ByteArray>(CACHED_BLOCKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean = size > CACHED_BLOCKS
    }

    /** The inner XML of the `member` element of [docId], its white space made single spaces; null when the file has none. */
    fun text(docId: String): String? {
        val id = docId.toByteArray(Charsets.UTF_8)
        // the last block whose first ID is not above the one asked for
        var low = 0
        var high = blockCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (compare(firstId(middle), id) <= 0) low = middle + 1 else high = middle
        }
        val block = low - 1
        if (block < 0) return null
        val bytes = block(block)
        var at = 0
        while (at < bytes.size) {
            val separator = indexOf(bytes, 1, at)
            val end = indexOf(bytes, 0, separator + 1)
            if (separator < 0 || end < 0) return null
            val order = compare(bytes, at, separator, id)
            if (order == 0) return String(bytes, separator + 1, end - separator - 1, Charsets.UTF_8)
            if (order > 0) return null
            at = end + 1
        }
        return null
    }

    fun doc(docId: String): IndexedDoc? = text(docId)?.let(::IndexedDoc)

    private fun firstId(block: Int): ByteArray = firstIds[block] ?: run {
        val row = blockTable + block * BLOCK_SIZE
        ByteArray(buffer.getInt(row + 4)).also { bytes -> for (i in bytes.indices) bytes[i] = buffer.get(buffer.getInt(row) + i) }
    }.also { firstIds[block] = it }

    private fun block(block: Int): ByteArray = synchronized(inflated) {
        inflated[block] ?: run {
            val row = blockTable + block * BLOCK_SIZE
            val compressed = ByteArray(buffer.getInt(row + 12))
            for (i in compressed.indices) compressed[i] = buffer.get(buffer.getInt(row + 8) + i)
            val raw = ByteArray(buffer.getInt(row + 16))
            val inflater = Inflater(true)
            try {
                inflater.setInput(compressed)
                var done = 0
                while (done < raw.size && !inflater.finished()) {
                    val read = inflater.inflate(raw, done, raw.size - done)
                    if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    done += read
                }
            } finally {
                inflater.end()
            }
            raw.also { inflated[block] = it }
        }
    }

    companion object {
        const val EXTENSION = "dnxd"
        private const val MAGIC = 0x44584E44 // "DNXD", little-endian
        private const val HEADER_SIZE = 36
        private const val BLOCK_SIZE = 20
        private const val CACHED_BLOCKS = 8

        fun open(file: Path): AssemblyDocs = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            of(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
        }

        fun read(bytes: ByteArray): AssemblyDocs = of(ByteBuffer.wrap(bytes))

        private fun of(buffer: ByteBuffer): AssemblyDocs {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.limit() >= HEADER_SIZE && buffer.getInt(0) == MAGIC) { "Not a documentation of an assembly" }
            val version = buffer.getInt(4)
            require(version == AssemblyIndex.FORMAT_VERSION) { "The documentation is of format $version, the plugin reads ${AssemblyIndex.FORMAT_VERSION}" }
            val blocks = buffer.getInt(28).toLong()
            val table = buffer.getInt(32).toLong()
            val limit = buffer.limit().toLong()
            require(blocks >= 0 && table >= HEADER_SIZE && table + blocks * BLOCK_SIZE <= limit) { "The documentation is cut short" }
            for (block in 0 until blocks.toInt()) {
                val row = (table + block * BLOCK_SIZE).toInt()
                require(buffer.getInt(row).toLong() + buffer.getInt(row + 4) <= limit && buffer.getInt(row + 8).toLong() + buffer.getInt(row + 12) <= limit) {
                    "The documentation is cut short"
                }
            }
            return AssemblyDocs(buffer)
        }

        private fun indexOf(bytes: ByteArray, value: Int, from: Int): Int {
            for (i in from until bytes.size) if (bytes[i].toInt() == value) return i
            return -1
        }

        /** UTF-8 bytes compared as unsigned: the order of the IDs in the file, which is the ordinal order of their characters. */
        private fun compare(a: ByteArray, b: ByteArray): Int = compare(a, 0, a.size, b)

        private fun compare(a: ByteArray, from: Int, to: Int, b: ByteArray): Int {
            val length = to - from
            for (i in 0 until minOf(length, b.size)) {
                val order = (a[from + i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (order != 0) return order
            }
            return length - b.size
        }
    }
}

/**
 * The documentation of a member as the XML of its assembly has it: the parts are inner XML (`<see cref="T:System.String" />`,
 * `<paramref name="x" />` and the like stay in them, to be rendered by who shows them).
 */
class IndexedDoc(val xml: String) {
    val summary: String? get() = element("summary")
    val returns: String? get() = element("returns")
    val value: String? get() = element("value")
    val remarks: String? get() = element("remarks")

    fun parameter(name: String): String? = named("param", name)
    fun typeParameter(name: String): String? = named("typeparam", name)

    /** `cref` of every `<exception>` with its text. */
    val exceptions: List<Pair<String, String>>
        get() = Regex("""<exception cref="([^"]*)"\s*>(.*?)</exception>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()

    /** `<inheritdoc />`: the text is the one of what the member overrides or implements. */
    val inherits: Boolean get() = xml.contains("<inheritdoc")

    private fun element(name: String): String? =
        Regex("""<$name>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    private fun named(element: String, name: String): String? =
        Regex("""<$element name="${Regex.escape(name)}"\s*>(.*?)</$element>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    override fun toString(): String = xml
}
