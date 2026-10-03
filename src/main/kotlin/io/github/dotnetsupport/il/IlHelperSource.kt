package io.github.dotnetsupport.il

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.nuget.NuGetHelper

/**
 * The IL by DotNetHelper (`helpers/dotnethelper/Il.cs`, method `il`): ICSharpCode.Decompiler disassembles, the portable PDB picks the bodies
 * of the line. The same helper process as the NuGet client ([NuGetHelper.connection]), one per IDE. [ask] sends a request and waits for its
 * result; tests give a fake. Blocking: the first request builds and starts the helper.
 */
class IlHelperSource(
    private val ask: (method: String, params: JsonElement, timeoutMs: Long) -> JsonElement = { method, params, timeoutMs ->
        NuGetHelper.getInstance().connection.request(method, params, timeoutMs)
    },
) : IlSource {
    override fun il(request: IlRequest): IlAnswer =
        IlAnswers.parse(ask("il", IlAnswers.params(request), TIMEOUT_MS)) ?: throw HelperException("the helper gave no IL for ${request.file}:${request.line}")

    companion object {
        /** A big assembly is read and its PDB indexed on the first request; later ones take milliseconds. */
        private const val TIMEOUT_MS = 60_000L
    }
}

/** The `il` request of DotNetHelper and its answer, without the helper: the tests read saved answers of a real run (`src/test/resources/il`). */
object IlAnswers {
    fun params(request: IlRequest): JsonObject = JsonObject().apply {
        addProperty("assembly", request.assembly)
        addProperty("file", request.file)
        addProperty("line", request.line)
        request.typeName?.let { addProperty("typeName", it) }
        request.memberName?.let { addProperty("memberName", it) }
    }

    /** Null for what is not an answer; a body without a name or a text is left out, an unknown kind is a method. */
    fun parse(json: JsonElement?): IlAnswer? {
        val o = json as? JsonObject ?: return null
        val bodies = objects(o.get("bodies")).mapNotNull { b ->
            IlBody(b.string("name") ?: return@mapNotNull null, kind(b.string("kind")), b.string("text") ?: return@mapNotNull null, b.bool("atCaret"),
                objects(b.get("mapping")).mapNotNull(::mapping))
        }
        return IlAnswer(o.string("assembly") ?: return null, o.long("assemblyModified") ?: 0, o.string("pdb"), bodies, o.string("warning"))
    }

    fun kind(text: String?): IlBody.Kind = when (text) {
        "stateMachine" -> IlBody.Kind.STATE_MACHINE
        "lambda" -> IlBody.Kind.LAMBDA
        "localFunction" -> IlBody.Kind.LOCAL_FUNCTION
        "type" -> IlBody.Kind.TYPE
        "field" -> IlBody.Kind.FIELD
        else -> IlBody.Kind.METHOD
    }

    private fun mapping(m: JsonObject): IlLineMapping? =
        IlLineMapping(m.int("textLine") ?: return null, m.int("offset") ?: return null, m.int("startLine") ?: return null, m.int("startColumn") ?: 0,
            m.int("endLine") ?: return null, m.int("endColumn") ?: 0)

    private fun objects(json: JsonElement?): List<JsonObject> = (json as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.bool(name: String): Boolean = get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    private fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    private fun JsonObject.long(name: String): Long? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
}
