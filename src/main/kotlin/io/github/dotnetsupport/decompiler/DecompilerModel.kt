package io.github.dotnetsupport.decompiler

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.nuget.NuGetHelper

/**
 * A type to decompile: the [assembly] (an implementation one, see [ImplementationAssemblies]), the metadata name of the type ([typeName]:
 * `System.Collections.Generic.List`1`, nested with `+`), the member the caret goes to ([memberId], an XML documentation id), the XML
 * documentation to put above the members ([xmlDoc]; by default the one next to the assembly), the folders the references are looked
 * for in ([referenceDirs], those of the project) and `LangVersion` of the project ([languageVersion]; null: the latest).
 */
data class DecompileRequest(
    val assembly: String, val typeName: String, val memberId: String? = null, val xmlDoc: String? = null, val referenceDirs: List<String> = emptyList(),
    val languageVersion: String? = null,
)

/** A member of a decompiled type: its XML documentation id and where its name is in the text ([offset] 0-based, [line] 0-based). */
data class DecompiledMember(val id: String, val offset: Int, val line: Int)

/**
 * The C# of a type, as DotNetHelper made it (`helpers/dotnethelper/Decompile.cs`): [assembly] is where the type is defined (a type forwarded
 * by a reference assembly is decompiled from its implementation), [assemblyModified] the time of that file (ms), [elapsedMs] what it took.
 */
data class DecompiledType(
    val assembly: String, val assemblyName: String, val assemblyVersion: String, val assemblyModified: Long, val typeName: String, val text: String,
    val members: List<DecompiledMember>, val warning: String? = null, val elapsedMs: Long = 0,
) {
    /** `T:Fixture.Box`1.Inner`1`: the documentation id of the type itself. */
    val typeId: String get() = typeId(typeName)

    /**
     * Where the caret goes for [memberId]: the member, else an overload of it (`M:N.T.Write(System.String)` for `M:N.T.Write(System.Int32)`
     * that the decompiler has merged or left out), else the type. Null when the text has none of them.
     */
    fun offsetOf(memberId: String?): Int? {
        if (memberId != null) {
            members.firstOrNull { it.id == memberId }?.let { return it.offset }
            val name = memberId.substringBefore('(')
            members.firstOrNull { it.id.substringBefore('(') == name }?.let { return it.offset }
        }
        return members.firstOrNull { it.id == typeId }?.offset
    }

    companion object {
        fun typeId(typeName: String): String = "T:" + typeName.replace('+', '.')
    }
}

/** A type of an assembly, for the chooser: its metadata name (`Ns.Outer+Inner`1`), `class` / `struct` / `interface` / `enum` / `delegate`. */
data class AssemblyTypeInfo(val name: String, val kind: String, val isPublic: Boolean) {
    /** `Outer.Inner<T>`-like, without the namespace: what the chooser shows first. */
    val displayName: String get() = (if (namespace.isEmpty()) name else name.removePrefix("$namespace.")).replace('+', '.').replace(ARITY, "")
    val namespace: String get() = name.substringBefore('+').substringBeforeLast('.', "")

    private companion object {
        val ARITY = Regex("`\\d+")
    }
}

/** Where the C# comes from: the .NET helper in the IDE ([DecompilerHelperSource]), a fake in tests. Blocking, not for the EDT; throws HelperException. */
interface DecompilerSource {
    fun decompile(request: DecompileRequest): DecompiledType
    fun types(assembly: String): List<AssemblyTypeInfo>
}

/**
 * The decompiler of DotNetHelper (`helpers/dotnethelper/Decompile.cs`, methods `decompile` and `assemblyTypes`): ICSharpCode.Decompiler,
 * the engine of ILSpy, in the same helper process as the NuGet client and the IL viewer. The first request builds and starts the helper.
 */
class DecompilerHelperSource(
    private val ask: (method: String, params: JsonElement, timeoutMs: Long) -> JsonElement = { method, params, timeoutMs ->
        NuGetHelper.getInstance().connection.request(method, params, timeoutMs)
    },
) : DecompilerSource {
    override fun decompile(request: DecompileRequest): DecompiledType =
        DecompilerAnswers.parse(ask("decompile", DecompilerAnswers.params(request), TIMEOUT_MS)) ?: throw HelperException("the helper gave no C# for ${request.typeName}")

    override fun types(assembly: String): List<AssemblyTypeInfo> =
        DecompilerAnswers.parseTypes(ask("assemblyTypes", JsonObject().apply { addProperty("assembly", assembly) }, TIMEOUT_MS))

    companion object {
        /** `System.Private.CoreLib` takes seconds the first time (its references are read); the next types of it are quick. */
        private const val TIMEOUT_MS = 120_000L
    }
}

/** The requests of the decompiler and its answers, without the helper: the tests read saved answers of a real run (`src/test/resources/decompiler`). */
object DecompilerAnswers {
    fun params(request: DecompileRequest): JsonObject = JsonObject().apply {
        addProperty("assembly", request.assembly)
        addProperty("typeName", request.typeName)
        request.memberId?.let { addProperty("memberId", it) }
        request.xmlDoc?.let { addProperty("xmlDoc", it) }
        if (request.referenceDirs.isNotEmpty()) add("referenceDirs", JsonArray().apply { request.referenceDirs.forEach(::add) })
        request.languageVersion?.let { addProperty("languageVersion", it) }
    }

    /** Null for what is not an answer; a member without an id or with an offset outside the text is left out. */
    fun parse(json: JsonElement?): DecompiledType? {
        val o = json as? JsonObject ?: return null
        val text = o.string("text") ?: return null
        val members = (o.get("members") as? JsonArray)?.mapNotNull { m ->
            val member = m as? JsonObject ?: return@mapNotNull null
            val offset = member.int("offset")?.takeIf { it in 0..text.length } ?: return@mapNotNull null
            DecompiledMember(member.string("id") ?: return@mapNotNull null, offset, member.int("line") ?: 0)
        }.orEmpty()
        return DecompiledType(o.string("assembly") ?: return null, o.string("assemblyName").orEmpty(), o.string("assemblyVersion").orEmpty(), o.long("assemblyModified") ?: 0,
            o.string("typeName") ?: return null, text, members, o.string("warning"), o.long("elapsedMs") ?: 0)
    }

    fun parseTypes(json: JsonElement?): List<AssemblyTypeInfo> = (json as? JsonArray)?.mapNotNull { t ->
        val o = t as? JsonObject ?: return@mapNotNull null
        AssemblyTypeInfo(o.string("name") ?: return@mapNotNull null, o.string("kind") ?: "class", o.get("isPublic")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
    }.orEmpty()

    /** The answer as the helper gives it: what the cache on disk keeps. */
    fun toJson(type: DecompiledType): JsonObject = JsonObject().apply {
        addProperty("assembly", type.assembly)
        addProperty("assemblyName", type.assemblyName)
        addProperty("assemblyVersion", type.assemblyVersion)
        addProperty("assemblyModified", type.assemblyModified)
        addProperty("typeName", type.typeName)
        addProperty("text", type.text)
        add("members", JsonArray().apply {
            type.members.forEach { m -> add(JsonObject().apply { addProperty("id", m.id); addProperty("offset", m.offset); addProperty("line", m.line) }) }
        })
        type.warning?.let { addProperty("warning", it) }
        addProperty("elapsedMs", type.elapsedMs)
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    private fun JsonObject.long(name: String): Long? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
}
