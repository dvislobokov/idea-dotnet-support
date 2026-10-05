package io.github.dotnetsupport.index

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
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
    METHOD(1), EXTENSION_METHOD(2), PROPERTY(3), FIELD(4), CONSTANT(5), ENUM_MEMBER(6), CONSTRUCTOR(7), INDEXER(8), EVENT(9), OPERATOR(10);

    val isCallable: Boolean get() = this == METHOD || this == EXTENSION_METHOD

    companion object {
        fun of(code: Int): IndexedMemberKind = entries.firstOrNull { it.code == code } ?: METHOD
    }
}

/** A generic parameter: `out T`, `where T : class, IComparable<T>, new()`. */
class IndexedTypeParameter(val name: String, private val flags: Int, val constraints: List<IndexedTypeRef>) {
    val isCovariant: Boolean get() = flags and 1 != 0
    val isContravariant: Boolean get() = flags and 2 != 0
    val isClass: Boolean get() = flags and 4 != 0
    val isStruct: Boolean get() = flags and 8 != 0
    val hasNew: Boolean get() = flags and 16 != 0 && !isStruct
    val allowsRefStruct: Boolean get() = flags and 32 != 0
    val isUnmanaged: Boolean get() = flags and 64 != 0

    override fun toString(): String = listOfNotNull("out".takeIf { isCovariant }, "in".takeIf { isContravariant }, name).joinToString(" ")
}

/**
 * A type of an indexed assembly: public, or nested protected ([isProtected]). Read from the index when a property is asked for;
 * two objects of the same row are equal.
 */
class IndexedType internal constructor(val index: AssemblyIndex, val row: Int) {
    val namespace: String = index.string(index.typeInt(row, 0))

    /** `Dictionary`2+Enumerator`: the name within the namespace as the metadata has it. */
    val path: String = index.string(index.typeInt(row, 1))
    private val packed = index.typeInt(row, 2)
    val kind: IndexedTypeKind = IndexedTypeKind.of(packed and 0xFF)
    private val flags = packed ushr 8

    /** `Dictionary.Enumerator` for a nested one: how C# names it, but for the type arguments. */
    val name: String get() = IndexedTypeRef.segments(path).joinToString(".") { it.first }

    /** `Enumerator`. */
    val simpleName: String get() = IndexedTypeRef.segments(path).last().first

    /** `System.Collections.Generic.Dictionary`2+Enumerator`: what the references of the index name it by. */
    val fullName: String get() = if (namespace.isEmpty()) path else "$namespace.$path"

    /** All its type parameters, the ones of the types around a nested type among them, as the metadata has them. */
    val arity: Int get() = index.typeInt(row, 7)
    val ownArity: Int get() = arity - (declaringType?.arity ?: 0)

    val obsolete: Boolean get() = flags and 1 != 0
    /** `[EditorBrowsable(Never)]`: not to be offered, but code that names it is right. */
    val isHidden: Boolean get() = flags and 2 != 0
    val isAbstract: Boolean get() = flags and 4 != 0
    val isSealed: Boolean get() = flags and 8 != 0
    val isStatic: Boolean get() = flags and 16 != 0
    /** A record class (a record struct leaves no trace a reference assembly keeps). */
    val isRecord: Boolean get() = flags and 32 != 0
    val isReadOnly: Boolean get() = flags and 64 != 0
    val isRefLike: Boolean get() = flags and 128 != 0
    /** Nested protected: seen by the types derived from the one around it only. */
    val isProtected: Boolean get() = flags and 256 != 0

    val declaringType: IndexedType? get() = index.typeInt(row, 3).takeIf { it >= 0 }?.let(index::type)
    /** Of a class; null for `object`, an interface, a struct (its base is `System.ValueType` and says nothing). */
    val baseType: IndexedTypeRef? get() = index.ref(index.typeInt(row, 4))
    val interfaces: List<IndexedTypeRef> get() = index.list(index.typeInt(row, 5)).map { index.ref(it)!! }
    val typeParameters: List<IndexedTypeParameter> get() = index.typeParameters(index.typeInt(row, 6), arity)
    val members: List<IndexedMember> get() = List(index.typeInt(row, 9)) { index.member(index.typeInt(row, 8) + it) }
    val nestedTypes: List<IndexedType> get() = index.list(index.typeInt(row, 10)).map(index::type)
    /** The full names of its attributes but the ones the compiler writes for itself (`[Nullable]`, `[IsReadOnly]`…): `System.ObsoleteAttribute`. */
    val attributes: List<String> get() = index.list(index.typeInt(row, 11)).map(index::string)
    val enumUnderlyingType: IndexedTypeRef? get() = index.ref(index.typeInt(row, 12))

    /** `T:System.Collections.Generic.Dictionary`2.Enumerator`. */
    val docId: String get() = "T:" + fullName.replace('+', '.')

    val qualifiedName: String get() = if (namespace.isEmpty()) name else "$namespace.$name"

    override fun toString(): String = qualifiedName + if (arity > 0) "`$arity" else ""
    override fun equals(other: Any?): Boolean = other is IndexedType && other.index === index && other.row == row
    override fun hashCode(): Int = System.identityHashCode(index) * 31 + row
}

class IndexedParameter(
    /** As C# writes it, without `?` of nullable reference types: `int`, `List<T>`, `T[]`. */
    val type: String,
    val name: String,
    private val flags: Int,
    /** The type itself; one by reference is not wrapped, its [isRef] / [isOut] / [isIn] say so. */
    val typeRef: IndexedTypeRef = IndexedTypeRef.Named(type, false),
    /** `null`, `42`, `"text"`, `'c'`, `default`: as C# writes it. */
    val defaultValue: String? = null,
) {
    val isOptional: Boolean get() = flags and 1 != 0
    val isOut: Boolean get() = flags and 2 != 0
    val isRef: Boolean get() = flags and 4 != 0
    val isParams: Boolean get() = flags and 8 != 0
    val isThis: Boolean get() = flags and 16 != 0
    val isIn: Boolean get() = flags and 32 != 0
    val hasDefault: Boolean get() = flags and 64 != 0
    val isByReference: Boolean get() = isRef || isOut || isIn

    /** `out int result`, `params object[] args`, `this IEnumerable<T> source`. */
    override fun toString(): String = listOfNotNull(
        "this".takeIf { isThis }, "params".takeIf { isParams }, "out".takeIf { isOut }, "ref".takeIf { isRef }, "in".takeIf { isIn && !isOut }, type, name,
    ).joinToString(" ")
}

/** A member other assemblies see: public, or protected ([isProtected]); instance and static. */
class IndexedMember internal constructor(private val index: AssemblyIndex, val row: Int, val type: IndexedType) {
    val name: String = index.string(index.memberInt(row, 0))
    private val packed = index.memberInt(row, 2)
    val kind: IndexedMemberKind = IndexedMemberKind.of(packed and 0xFF)
    private val flags = packed ushr 8

    /** The number of type parameters of a generic method. */
    val arity: Int get() = index.memberInt(row, 7)
    val typeParameters: List<IndexedTypeParameter> get() = index.typeParameters(index.memberInt(row, 6), arity)

    val obsolete: Boolean get() = flags and 1 != 0
    val isHidden: Boolean get() = flags and 2 != 0
    val isStatic: Boolean get() = flags and 4 != 0
    val isProtected: Boolean get() = flags and 8 != 0
    val isAbstract: Boolean get() = flags and 16 != 0
    val isVirtual: Boolean get() = flags and 32 != 0
    val isOverride: Boolean get() = flags and 64 != 0
    val isSealed: Boolean get() = flags and 128 != 0
    /** A readonly field; a readonly member of a struct. */
    val isReadOnly: Boolean get() = flags and 256 != 0
    /** Of a property: the accessors other code may call. */
    val hasGetter: Boolean get() = flags and 512 != 0
    val hasSetter: Boolean get() = flags and 1024 != 0
    val isInitOnly: Boolean get() = flags and 2048 != 0
    val isRequired: Boolean get() = flags and 4096 != 0

    /** What a method returns (`void` too); the type of a property, an indexer, a field, a constant, an event. */
    val typeRef: IndexedTypeRef get() = index.ref(index.memberInt(row, 3))!!

    /** [typeRef] as C# writes it, without `?` of nullable reference types. */
    val returnType: String get() = display(typeRef)

    val parameters: List<IndexedParameter>
        get() {
            val first = index.memberInt(row, 4)
            return List(index.memberInt(row, 5)) { i ->
                val at = first + i
                val ref = index.ref(index.parameterInt(at, 0))!!
                IndexedParameter(display(ref), index.string(index.parameterInt(at, 1)), index.parameterInt(at, 2), ref, index.parameterInt(at, 3).takeIf { it >= 0 }?.let(index::string))
            }
        }

    val attributes: List<String> get() = index.list(index.memberInt(row, 8)).map(index::string)

    /** The value of a constant or an enum member as C# writes it: `42`, `"text"`, `1.5F`. */
    val constantValue: String? get() = index.memberInt(row, 9).takeIf { it >= 0 }?.let(index::string)

    val thisParameter: IndexedParameter? get() = if (kind == IndexedMemberKind.EXTENSION_METHOD) parameters.firstOrNull() else null

    /** A type of a signature of this member as C# writes it, with the names of the type parameters of the type and of the method. */
    fun display(ref: IndexedTypeRef, nullable: Boolean = false): String =
        ref.display(type.typeParameters.map { it.name }, if (arity > 0) typeParameters.map { it.name } else emptyList(), nullable)

    /** `Console.WriteLine`: how a static member is written where its type is imported. */
    val qualifiedName: String get() = "${type.name}.$name"

    /** `(string format, params object[] args)`, empty for what is not called. */
    val signature: String get() = if (kind.isCallable) parameters.joinToString(", ", "(", ")") else ""

    /**
     * The documentation ID, as the XML documentation of the assembly names the member:
     * `M:System.Linq.Enumerable.Select``2(System.Collections.Generic.IEnumerable{``0},System.Func{``0,``1})`.
     */
    val docId: String
        get() {
            val prefix = when (kind) {
                IndexedMemberKind.PROPERTY, IndexedMemberKind.INDEXER -> "P:"
                IndexedMemberKind.FIELD, IndexedMemberKind.CONSTANT, IndexedMemberKind.ENUM_MEMBER -> "F:"
                IndexedMemberKind.EVENT -> "E:"
                else -> "M:"
            }
            val builder = StringBuilder(prefix).append(type.fullName.replace('+', '.')).append('.').append(name.replace('.', '#'))
            if (arity > 0) builder.append("``").append(arity)
            val parameters = parameters
            if (parameters.isNotEmpty()) builder.append(parameters.joinToString(",", "(", ")") { it.typeRef.docId() + if (it.isByReference) "@" else "" })
            if (name == "op_Implicit" || name == "op_Explicit") builder.append('~').append(typeRef.docId())
            return builder.toString()
        }

    override fun toString(): String = "$returnType $qualifiedName$signature"
    override fun equals(other: Any?): Boolean = other is IndexedMember && other.index === index && other.row == row
    override fun hashCode(): Int = System.identityHashCode(index) * 31 + row
}

/**
 * The index of an assembly, as `indexer/Program.cs` writes it (the format is described there, at `IndexWriter`): its types and
 * members other assemblies see, with their signatures; static members and types are found by the beginning of a name
 * ([types], [members]), a type by its name ([findType]) and the types of a namespace ([typesIn]) by a binary search, extension
 * methods by what they extend ([extensions]). The file is not parsed: it is read in place, through a buffer that is mapped or
 * wrapped around its bytes, and a string or a type reference is decoded when it is asked for. The documentation is in a file of
 * its own ([docs]).
 */
class AssemblyIndex private constructor(private val buffer: ByteBuffer, docsFile: Path?, docs: AssemblyDocs?) {
    val version: Int = buffer.getInt(4)
    val mvid: String
    val assemblyName: String
    val assemblyVersion: String

    private val stringCount = buffer.getInt(32)
    private val stringTable = buffer.getInt(36)
    val typeCount: Int = buffer.getInt(40)
    private val typeTable = buffer.getInt(44)
    val memberCount: Int = buffer.getInt(48)
    private val memberTable = buffer.getInt(52)
    private val parameterCount = buffer.getInt(56)
    private val parameterTable = buffer.getInt(60)
    private val genericCount = buffer.getInt(64)
    private val genericTable = buffer.getInt(68)
    private val intCount = buffer.getInt(72)
    private val intTable = buffer.getInt(76)
    val nameCount: Int = buffer.getInt(80)
    private val nameTable = buffer.getInt(84)
    val extensionCount: Int = buffer.getInt(88)
    private val extensionTable = buffer.getInt(92)
    private val stringData = stringTable + (stringCount + 1) * 4
    private val strings = arrayOfNulls<String>(stringCount)
    private val refs = arrayOfNulls<IndexedTypeRef>(stringCount)
    private val lowerNames = arrayOfNulls<String>(nameCount)

    /** The XML documentation of the assembly, when there was one next to it; read the first time it is asked for. */
    val docs: AssemblyDocs? by lazy { docs ?: docsFile?.takeIf { Files.isRegularFile(it) }?.let { runCatching { AssemblyDocs.open(it) }.getOrNull() } }

    init {
        val bytes = ByteArray(16).also { for (i in 0 until 16) it[i] = buffer.get(8 + i) }
        mvid = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        assemblyName = string(buffer.getInt(24))
        assemblyVersion = string(buffer.getInt(28))
    }

    fun type(index: Int): IndexedType {
        require(index in 0 until typeCount) { "type $index of $typeCount" }
        return IndexedType(this, index)
    }

    fun member(index: Int): IndexedMember {
        require(index in 0 until memberCount) { "member $index of $memberCount" }
        return IndexedMember(this, index, type(memberInt(index, 1)))
    }

    /** Every type, sorted by the namespace, then by the name. */
    val allTypes: List<IndexedType> get() = List(typeCount) { type(it) }

    /** Types whose name begins with [prefix], whatever the case of the letters; a nested type by its own name. Not the protected ones. */
    fun types(prefix: String, limit: Int = DEFAULT_LIMIT): List<IndexedType> = targets(prefix, limit) { it >= 0 }.map { type(it) }

    /** Static members (extension methods, constants and enum members among them) whose name begins with [prefix]; not the protected ones. */
    fun members(prefix: String, limit: Int = DEFAULT_LIMIT): List<IndexedMember> = targets(prefix, limit) { it < 0 }.map { member(it and MEMBER_MASK) }

    /** `System.Collections.Generic.Dictionary`2+Enumerator`: by the metadata name, the namespace up to the last dot before a `+`. */
    fun findType(fullName: String): IndexedType? {
        val top = fullName.substringBefore('+')
        val dot = top.lastIndexOf('.')
        return if (dot < 0) findType("", fullName) else findType(fullName.substring(0, dot), fullName.substring(dot + 1))
    }

    fun findType(namespace: String, path: String): IndexedType? {
        var low = 0
        var high = typeCount
        while (low < high) {
            val middle = (low + high) ushr 1
            val order = compareType(middle, namespace, path)
            when {
                order < 0 -> low = middle + 1
                order > 0 -> high = middle
                else -> return type(middle)
            }
        }
        return null
    }

    /** The types of [namespace] (not of the namespaces in it); the nested ones too when [nested]. */
    fun typesIn(namespace: String, nested: Boolean = false): List<IndexedType> {
        var at = firstOfNamespace(namespace)
        val found = ArrayList<IndexedType>()
        while (at < typeCount && string(typeInt(at, 0)) == namespace) {
            if (nested || typeInt(at, 3) < 0) found += type(at)
            at++
        }
        return found
    }

    /** The namespaces that have a type. */
    val namespaces: Set<String> by lazy { (0 until typeCount).mapTo(LinkedHashSet()) { string(typeInt(it, 0)) } }

    /**
     * Extension methods by what their `this` parameter is: a type by its metadata name (`System.Collections.Generic.IEnumerable`1`,
     * not its instance), `[]` for an array, `!` for a type parameter of the method (`this T value`, whatever its constraints).
     */
    fun extensions(key: String): List<IndexedMember> {
        var low = 0
        var high = extensionCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (string(buffer.getInt(extensionTable + middle * EXTENSION_SIZE)) < key) low = middle + 1 else high = middle
        }
        val found = ArrayList<IndexedMember>()
        while (low < extensionCount && string(buffer.getInt(extensionTable + low * EXTENSION_SIZE)) == key) {
            found += member(buffer.getInt(extensionTable + low * EXTENSION_SIZE + 4))
            low++
        }
        return found
    }

    /** The documentation of a member or a type by its ID ([IndexedMember.docId], [IndexedType.docId]). */
    fun doc(docId: String): IndexedDoc? = docs?.doc(docId)

    private fun firstOfNamespace(namespace: String): Int {
        var low = 0
        var high = typeCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (string(typeInt(middle, 0)) < namespace) low = middle + 1 else high = middle
        }
        return low
    }

    private fun compareType(row: Int, namespace: String, path: String): Int {
        val byNamespace = string(typeInt(row, 0)).compareTo(namespace)
        return if (byNamespace != 0) byNamespace else string(typeInt(row, 1)).compareTo(path)
    }

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

    internal fun typeInt(row: Int, column: Int): Int = buffer.getInt(typeTable + row * TYPE_SIZE + column * 4)
    internal fun memberInt(row: Int, column: Int): Int = buffer.getInt(memberTable + row * MEMBER_SIZE + column * 4)
    internal fun parameterInt(row: Int, column: Int): Int {
        require(row in 0 until parameterCount) { "parameter $row of $parameterCount" }
        return buffer.getInt(parameterTable + row * PARAMETER_SIZE + column * 4)
    }

    internal fun typeParameters(first: Int, count: Int): List<IndexedTypeParameter> {
        require(first >= 0 && count >= 0 && first + count <= genericCount) { "generics $first+$count of $genericCount" }
        return List(count) { i ->
            val at = genericTable + (first + i) * GENERIC_SIZE
            IndexedTypeParameter(string(buffer.getInt(at)), buffer.getInt(at + 4), list(buffer.getInt(at + 8)).map { ref(it)!! })
        }
    }

    /** A list of the table of ints: its count, then its items. */
    internal fun list(at: Int): List<Int> {
        require(at in 0 until intCount) { "list $at of $intCount" }
        val count = buffer.getInt(intTable + at * 4)
        require(count >= 0 && at + count < intCount) { "list $at+$count of $intCount" }
        return List(count) { buffer.getInt(intTable + (at + 1 + it) * 4) }
    }

    internal fun ref(id: Int): IndexedTypeRef? {
        if (id < 0) return null
        require(id < stringCount) { "string $id of $stringCount" }
        return refs[id] ?: IndexedTypeRef.parse(string(id)).also { refs[id] = it }
    }

    internal fun string(id: Int): String {
        require(id in 0 until stringCount) { "string $id of $stringCount" }
        strings[id]?.let { return it }
        val from = buffer.getInt(stringTable + id * 4)
        val to = buffer.getInt(stringTable + (id + 1) * 4)
        val bytes = ByteArray(to - from)
        for (i in bytes.indices) bytes[i] = buffer.get(stringData + from + i)
        return String(bytes, Charsets.UTF_8).also { strings[id] = it }
    }

    companion object {
        const val FORMAT_VERSION = 2
        const val EXTENSION = "dnix"
        const val DEFAULT_LIMIT = 200
        private const val MAGIC = 0x58494E44 // "DNIX", little-endian
        private const val HEADER_SIZE = 96
        private const val TYPE_SIZE = 52
        private const val MEMBER_SIZE = 40
        private const val PARAMETER_SIZE = 16
        private const val GENERIC_SIZE = 12
        private const val NAME_SIZE = 8
        private const val EXTENSION_SIZE = 8
        private const val MEMBER_MASK = 0x7FFFFFFF

        /** Mapped: the pages are read when they are touched, and shared by every project that has the assembly. The documentation is `<mvid>.dnxd` next to it. */
        fun open(file: Path): AssemblyIndex = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            of(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()), file.resolveSibling(file.fileName.toString().substringBeforeLast('.') + "." + AssemblyDocs.EXTENSION), null)
        }

        fun read(bytes: ByteArray, docs: AssemblyDocs? = null): AssemblyIndex = of(ByteBuffer.wrap(bytes), null, docs)

        private fun of(buffer: ByteBuffer, docsFile: Path?, docs: AssemblyDocs?): AssemblyIndex {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.limit() >= HEADER_SIZE && buffer.getInt(0) == MAGIC) { "Not an index of an assembly" }
            val version = buffer.getInt(4)
            require(version == FORMAT_VERSION) { "The index is of format $version, the plugin reads $FORMAT_VERSION" }
            // a file cut short by a full disk or a killed process: every table has to be inside
            val limit = buffer.limit().toLong()
            for ((at, size) in listOf(32 to 4, 40 to TYPE_SIZE, 48 to MEMBER_SIZE, 56 to PARAMETER_SIZE, 64 to GENERIC_SIZE, 72 to 4, 80 to NAME_SIZE, 88 to EXTENSION_SIZE)) {
                val rows = buffer.getInt(at).toLong()
                val from = buffer.getInt(at + 4).toLong()
                require(rows >= 0 && from >= HEADER_SIZE && from + rows * size <= limit) { "The index is cut short" }
            }
            return AssemblyIndex(buffer, docsFile, docs)
        }
    }
}
