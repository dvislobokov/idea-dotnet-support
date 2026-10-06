package io.github.dotnetsupport.sourcelink

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * Where the source of a type or a member of a library is, as DotNetHelper reads it from the portable PDB (`helpers/dotnethelper/SourceLink.cs`,
 * method `sourceLocation`): the [document] the build machine knew (`/_/src/Lib/File.cs` with deterministic paths), the first sequence point
 * ([line], [column], 1-based), the hash of the file the PDB was built from, the Source Link JSON of the PDB ([sourceLink]) and the text of
 * the document when the PDB embeds it ([embedded]). [memberFound]: the member was matched exactly, not by its name alone.
 */
data class SourceLocation(
    val assembly: String, val assemblyName: String, val pdb: String, val document: String, val line: Int, val column: Int, val hashAlgorithm: String,
    val hash: String, val sourceLink: String?, val embedded: String?, val memberFound: Boolean,
) {
    /** `GzipCompressionProvider.cs`: the name of the tab. */
    val fileName: String get() = document.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { "Source.cs" }

    /** The URL of the document by the Source Link of the PDB; null without a Source Link or a mapping for the document. */
    val url: String? get() = sourceLink?.let { SourceLinkMap.parse(it)?.url(document) }

    companion object {
        /** Null for what is not an answer of the helper. */
        fun parse(json: JsonElement?): SourceLocation? {
            val o = json as? JsonObject ?: return null
            return SourceLocation(
                o.string("assembly") ?: return null, o.string("assemblyName").orEmpty(), o.string("pdb").orEmpty(), o.string("document") ?: return null,
                o.int("line") ?: return null, o.int("column") ?: 1, o.string("hashAlgorithm").orEmpty(), o.string("hash").orEmpty().lowercase(),
                o.string("sourceLink"), o.string("embedded"), o.get("memberFound")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
            )
        }

        private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
        private fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    }
}

/**
 * The `sourcelink` JSON of a portable PDB: `{"documents": {"C:\src\*": "https://raw.githubusercontent.com/org/repo/<sha>/ *", ...}}` (no space before
 * the star in the real thing: a Kotlin comment cannot hold it). A key
 * is a path on the build machine, with one `*` at its end at most; the part of the document's path after the prefix takes the place of `*`
 * in the URL (slashes forward). As the specification says: compared without the case, the longest prefix wins, an exact key beats a wildcard.
 */
class SourceLinkMap private constructor(private val entries: List<Entry>) {
    private class Entry(val prefix: String, val wildcard: Boolean, val url: String)

    val isEmpty: Boolean get() = entries.isEmpty()

    fun url(document: String): String? {
        val path = normalize(document)
        val lower = path.lowercase()
        // exact keys first, then the longest wildcard prefix
        entries.firstOrNull { !it.wildcard && it.prefix == lower }?.let { return it.url }
        val match = entries.filter { it.wildcard && lower.startsWith(it.prefix) }.maxByOrNull { it.prefix.length } ?: return null
        val rest = path.substring(match.prefix.length)
        return match.url.replaceFirst("*", rest)
    }

    companion object {
        /** Null for what is not Source Link JSON; a JSON without documents is an empty map. */
        fun parse(json: String): SourceLinkMap? {
            val root = runCatching { JsonParser.parseString(json) }.getOrNull() as? JsonObject ?: return null
            val documents = root.get("documents") as? JsonObject ?: return SourceLinkMap(emptyList())
            val entries = documents.entrySet().mapNotNull { (key, value) ->
                val url = value.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val star = key.indexOf('*')
                if (star >= 0 && (star != key.length - 1 || url.count { it == '*' } != 1)) return@mapNotNull null
                if (star < 0 && url.contains('*')) return@mapNotNull null
                Entry(normalize(if (star >= 0) key.substring(0, star) else key).lowercase(), star >= 0, url)
            }
            return SourceLinkMap(entries)
        }

        private fun normalize(path: String): String = path.replace('\\', '/')
    }
}

/** The hash of a document as the PDB keeps it: the file the compiler read, SHA-256 (the default of the SDK), SHA-1 of older builds, or MD5. */
/**
 * Which Source Link URLs the IDE may download. The URL is written by whoever built the package, so it is not trusted: HTTPS only, and never
 * a host that is this machine or the local network (loopback, private, link-local, unique-local, any-local) — a PDB must not make the IDE
 * probe the intranet. [resolve] is the DNS lookup, replaced in tests.
 */
object SourceLinkUrls {
    /** Larger than any real source file. */
    const val MAX_BYTES: Int = 16 * 1024 * 1024

    /** Why [url] is not followed, or null when it may be downloaded. */
    fun refusal(url: String, resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }): String? {
        val uri = try { URI(url) } catch (_: URISyntaxException) { return "not a URL" }
        if (!uri.scheme.equals("https", ignoreCase = true)) return "not an HTTPS URL"
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return "no host"
        val addresses = try { resolve(host) } catch (_: UnknownHostException) { return "the host is unknown" }
        return if (addresses.isEmpty() || addresses.any(::isLocal)) "the host is this machine or the local network" else null
    }

    fun isLocal(address: InetAddress): Boolean = address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
        address.isAnyLocalAddress || address.isMulticastAddress || (address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC)
}

object DocumentChecksums {
    private val ALGORITHMS = mapOf("SHA256" to "SHA-256", "SHA1" to "SHA-1", "MD5" to "MD5")

    fun isKnown(algorithm: String): Boolean = algorithm.uppercase() in ALGORITHMS

    /** True when [bytes] hash to [hash] (hex) with [algorithm]; false for an algorithm the JVM does not name (a GUID of the PDB) or a different hash. */
    fun matches(bytes: ByteArray, algorithm: String, hash: String): Boolean {
        val name = ALGORITHMS[algorithm.uppercase()] ?: return false
        val digest = MessageDigest.getInstance(name).digest(bytes)
        return digest.joinToString("") { "%02x".format(it) } == hash.lowercase()
    }
}

/**
 * The caret in a source file found by a sequence point: the first point of a method is its first statement or the brace of its body, and the
 * declaration stands a few lines above. [name] is what the declaration is called in C#: the simple name of the type or the member, `this[`
 * for an indexer, `operator` for an operator, the type's name for a constructor. The offset of the name on the nearest line above (or on)
 * [line] that has it as a whole word (for a type, [isType]: after `class`, `struct`, `interface`, `enum`, `record` or `delegate`, however far
 * above: its first method may be far below its name), else the start of [line]; null when the file has fewer lines.
 */
object DeclarationFinder {
    private const val LINES_ABOVE = 60

    fun offset(text: String, line: Int, column: Int, name: String?, isType: Boolean = false): Int? {
        val starts = lineStarts(text)
        val index = line - 1
        if (index < 0 || index >= starts.size) return null
        val pattern = name?.takeIf { it.isNotEmpty() }?.let { pattern(it, isType) }
        if (pattern != null) {
            for (at in index downTo (if (isType) 0 else maxOf(0, index - LINES_ABOVE))) {
                val end = if (at + 1 < starts.size) starts[at + 1] - 1 else text.length
                val lineText = text.substring(starts[at], end)
                // not inside a comment line: `/// <see cref="Name"/>` above the declaration names it too
                if (lineText.trimStart().startsWith("//")) continue
                pattern.find(lineText)?.let { return starts[at] + (it.groups[1]?.range?.first ?: it.range.first) }
            }
        }
        val length = (if (index + 1 < starts.size) starts[index + 1] - 1 else text.length) - starts[index]
        return starts[index] + maxOf(0, column - 1).coerceAtMost(maxOf(0, length))
    }

    private fun pattern(name: String, isType: Boolean): Regex {
        // a whole word; `this[` ends with a bracket and `operator` is followed by a symbol, so a boundary is asked only beside a letter
        val before = if (name.first().isLetterOrDigit() || name.first() == '_') "(?<![\\w@])" else ""
        val after = if (name.last().isLetterOrDigit() || name.last() == '_') "(?![\\w])" else ""
        val word = "$before(${Regex.escape(name)})$after"
        return if (isType) Regex("\\b(?:class|struct|interface|enum|delegate|record(?:\\s+(?:class|struct))?)\\s+$word") else Regex(word)
    }

    /** What to look for in the source for a member of a documentation id: `Describe` for `M:Ns.T.Describe(System.String)`, `this[` for an indexer, `operator` for an operator, [typeName] for a constructor. */
    fun memberName(memberId: String, typeName: String): String? {
        val plain = memberId.substringBefore('(').substringAfter(':')
        val name = plain.substringAfterLast('.').substringBefore('`')
        return when {
            name == "#ctor" || name == "#cctor" -> typeName
            name == "Item" && memberId.startsWith("P:") && memberId.contains('(') -> "this["
            name.startsWith("op_") -> "operator"
            name.isEmpty() -> null
            else -> name
        }
    }

    private fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>().apply { add(0) }
        for (i in text.indices) if (text[i] == '\n') starts.add(i + 1)
        return starts.toIntArray()
    }
}
