package io.github.dotnetsupport.index

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

enum class IndexedTypeKind(val code: Int) {
    CLASS(1), STRUCT(2), INTERFACE(3), ENUM(4), DELEGATE(5), STATIC_CLASS(6);

    companion object {
        fun of(code: Int): IndexedTypeKind = entries.firstOrNull { it.code == code } ?: CLASS
    }
}

enum class IndexedMemberKind(val code: Int) {
    METHOD(1), EXTENSION_METHOD(2), PROPERTY(3), FIELD(4), CONSTANT(5), ENUM_MEMBER(6);

    val isCallable: Boolean get() = this == METHOD || this == EXTENSION_METHOD

    companion object {
        fun of(code: Int): IndexedMemberKind = entries.firstOrNull { it.code == code } ?: METHOD
    }
}

class IndexedType(val namespace: String, /** `Dictionary.Enumerator` for a nested one. */ val name: String, val kind: IndexedTypeKind, val arity: Int, val obsolete: Boolean) {
    val qualifiedName: String get() = if (namespace.isEmpty()) name else "$namespace.$name"
    override fun toString(): String = qualifiedName + if (arity > 0) "`$arity" else ""
}

class IndexedParameter(val type: String, val name: String, private val flags: Int) {
    val isOptional: Boolean get() = flags and 1 != 0
    val isOut: Boolean get() = flags and 2 != 0
    val isRef: Boolean get() = flags and 4 != 0
    val isParams: Boolean get() = flags and 8 != 0
    val isThis: Boolean get() = flags and 16 != 0
    val isIn: Boolean get() = flags and 32 != 0

    /** `out int result`, `params object[] args`, `this IEnumerable<T> source`. */
    override fun toString(): String = listOfNotNull(
        "this".takeIf { isThis }, "params".takeIf { isParams }, "out".takeIf { isOut }, "ref".takeIf { isRef }, "in".takeIf { isIn && !isOut }, type, name,
    ).joinToString(" ")
}

class IndexedMember(
    val name: String,
    val type: IndexedType,
    val kind: IndexedMemberKind,
    /** The number of type parameters of a generic method. */
    val arity: Int,
    val obsolete: Boolean,
    /** What a method returns; the type of a property, a field, a constant. */
    val returnType: String,
    val parameters: List<IndexedParameter>,
) {
    /** `Console.WriteLine`: how a static member is written where its type is imported. */
    val qualifiedName: String get() = "${type.name}.$name"

    /** `(string format, params object[] args)`, empty for what is not called. */
    val signature: String get() = if (kind.isCallable) parameters.joinToString(", ", "(", ")") else ""

    override fun toString(): String = "$returnType $qualifiedName$signature"
}

/**
 * The index of an assembly, as `indexer/Program.cs` writes it (the format is described there, at `IndexWriter`): public types and
 * public static members, found by the beginning of a name. The file is not parsed: it is read in place, through a buffer that is
 * mapped or wrapped around its bytes, and a string is decoded when it is asked for.
 */
class AssemblyIndex private constructor(private val buffer: ByteBuffer) {
    val version: Int = buffer.getInt(4)
    val mvid: String
    val assemblyName: String

    private val stringCount = buffer.getInt(28)
    private val stringTable = buffer.getInt(32)
    val typeCount: Int = buffer.getInt(36)
    private val typeTable = buffer.getInt(40)
    val memberCount: Int = buffer.getInt(44)
    private val memberTable = buffer.getInt(48)
    private val parameterCount = buffer.getInt(52)
    private val parameterTable = buffer.getInt(56)
    val nameCount: Int = buffer.getInt(60)
    private val nameTable = buffer.getInt(64)
    private val stringData = stringTable + (stringCount + 1) * 4
    private val strings = arrayOfNulls<String>(stringCount)
    private val lowerNames = arrayOfNulls<String>(nameCount)

    init {
        val bytes = ByteArray(16).also { for (i in 0 until 16) it[i] = buffer.get(8 + i) }
        mvid = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        assemblyName = string(buffer.getInt(24))
    }

    fun type(index: Int): IndexedType {
        require(index in 0 until typeCount) { "type $index of $typeCount" }
        val at = typeTable + index * TYPE_SIZE
        val packed = buffer.getInt(at + 8)
        return IndexedType(string(buffer.getInt(at)), string(buffer.getInt(at + 4)), IndexedTypeKind.of(packed and 0xFF), packed ushr 8 and 0xFF, packed ushr 16 and 1 != 0)
    }

    fun member(index: Int): IndexedMember {
        require(index in 0 until memberCount) { "member $index of $memberCount" }
        val at = memberTable + index * MEMBER_SIZE
        val packed = buffer.getInt(at + 8)
        val first = buffer.getInt(at + 16)
        val count = buffer.getInt(at + 20)
        require(first >= 0 && count >= 0 && first + count <= parameterCount) { "parameters $first+$count of $parameterCount" }
        val parameters = List(count) { i ->
            val parameter = parameterTable + (first + i) * PARAMETER_SIZE
            IndexedParameter(string(buffer.getInt(parameter)), string(buffer.getInt(parameter + 4)), buffer.getInt(parameter + 8))
        }
        return IndexedMember(string(buffer.getInt(at)), type(buffer.getInt(at + 4)), IndexedMemberKind.of(packed and 0xFF), packed ushr 8 and 0xFF, packed ushr 16 and 1 != 0,
            string(buffer.getInt(at + 12)), parameters)
    }

    /** Types whose name begins with [prefix], whatever the case of the letters; a nested type by its own name. */
    fun types(prefix: String, limit: Int = DEFAULT_LIMIT): List<IndexedType> = targets(prefix, limit) { it >= 0 }.map { type(it) }

    /** Static members, extension methods, constants and enum members whose name begins with [prefix]. */
    fun members(prefix: String, limit: Int = DEFAULT_LIMIT): List<IndexedMember> = targets(prefix, limit) { it < 0 }.map { member(it and MEMBER_MASK) }

    private fun targets(prefix: String, limit: Int, wanted: (Int) -> Boolean): List<Int> {
        val lower = prefix.lowercase(Locale.ROOT)
        val found = ArrayList<Int>()
        var at = firstNotBelow(lower)
        while (at < nameCount && found.size < limit && lowerName(at).startsWith(lower)) {
            val target = buffer.getInt(nameTable + at * NAME_SIZE + 4)
            if (wanted(target)) found += target
            at++
        }
        return found
    }

    /** The first row of the table of names that is not below [lower]: the names are sorted by their lower case. */
    private fun firstNotBelow(lower: String): Int {
        var low = 0
        var high = nameCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (lowerName(middle) < lower) low = middle + 1 else high = middle
        }
        return low
    }

    private fun lowerName(row: Int): String =
        lowerNames[row] ?: string(buffer.getInt(nameTable + row * NAME_SIZE)).lowercase(Locale.ROOT).also { lowerNames[row] = it }

    private fun string(id: Int): String {
        require(id in 0 until stringCount) { "string $id of $stringCount" }
        strings[id]?.let { return it }
        val from = buffer.getInt(stringTable + id * 4)
        val to = buffer.getInt(stringTable + (id + 1) * 4)
        val bytes = ByteArray(to - from)
        for (i in bytes.indices) bytes[i] = buffer.get(stringData + from + i)
        return String(bytes, Charsets.UTF_8).also { strings[id] = it }
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val EXTENSION = "dnix"
        const val DEFAULT_LIMIT = 200
        private const val MAGIC = 0x58494E44 // "DNIX", little-endian
        private const val HEADER_SIZE = 68
        private const val TYPE_SIZE = 12
        private const val MEMBER_SIZE = 24
        private const val PARAMETER_SIZE = 12
        private const val NAME_SIZE = 8
        private const val MEMBER_MASK = 0x7FFFFFFF

        /** Mapped: the pages are read when they are touched, and shared by every project that has the assembly. */
        fun open(file: Path): AssemblyIndex = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            of(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
        }

        fun read(bytes: ByteArray): AssemblyIndex = of(ByteBuffer.wrap(bytes))

        private fun of(buffer: ByteBuffer): AssemblyIndex {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.limit() >= HEADER_SIZE && buffer.getInt(0) == MAGIC) { "Not an index of an assembly" }
            val version = buffer.getInt(4)
            require(version == FORMAT_VERSION) { "The index is of format $version, the plugin reads $FORMAT_VERSION" }
            // a file cut short by a full disk or a killed process: every table has to be inside
            val limit = buffer.limit().toLong()
            for ((count, offset, size) in listOf(
                Triple(28, 32, 4), Triple(36, 40, TYPE_SIZE), Triple(44, 48, MEMBER_SIZE), Triple(52, 56, PARAMETER_SIZE), Triple(60, 64, NAME_SIZE),
            )) {
                val rows = buffer.getInt(count).toLong()
                val from = buffer.getInt(offset).toLong()
                require(rows >= 0 && from >= HEADER_SIZE && from + rows * size <= limit) { "The index is cut short" }
            }
            return AssemblyIndex(buffer)
        }
    }
}
