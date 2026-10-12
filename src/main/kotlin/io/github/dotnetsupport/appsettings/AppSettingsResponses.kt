package io.github.dotnetsupport.appsettings

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** What DotNetHelper's `appsettingsSchema` found in the code of a project (`helpers/dotnethelper/AppSettings.cs`). */
class CodeSchema(
    /** A draft-07 schema of the root of appsettings.json: the sections the code binds. */
    val schema: JsonObject,
    /** Where each key comes from: the property it is bound to, or the call that binds the section. */
    val sources: List<KeySource>,
    val sections: List<BoundSection>,
    /** Type names the helper could not find among the files (library types): their sections are open objects. */
    val unresolved: List<String>,
    val files: Int,
    val elapsedMs: Long,
) {
    /** `Shop:Retry:Count` → a property; `*` stands for an item of a list or a key of a dictionary. */
    class KeySource(val path: String, val file: String, val line: Int, val isProperty: Boolean)

    /** [how]: `Configure`, `Bind`, `BindConfiguration`, `Get`, `GetValue`, `indexer`, `GetConnectionString`, `comment`. */
    class BoundSection(val path: String, val type: String?, val how: String, val file: String, val line: Int)
}

/** The answers of `appsettingsSchema`, parsed without the helper: the tests read saved answers of a real run. */
object AppSettingsResponses {
    fun parse(json: JsonElement?): CodeSchema? {
        val o = json as? JsonObject ?: return null
        val schema = o.get("schema") as? JsonObject ?: return null
        return CodeSchema(
            schema = schema,
            sources = objects(o.get("sources")).mapNotNull { s ->
                CodeSchema.KeySource(s.string("path") ?: return@mapNotNull null, s.string("file").orEmpty(), s.int("line"), s.string("kind") == "property")
            },
            sections = objects(o.get("sections")).mapNotNull { s ->
                CodeSchema.BoundSection(s.string("path") ?: return@mapNotNull null, s.string("type"), s.string("how").orEmpty(), s.string("file").orEmpty(), s.int("line"))
            },
            unresolved = (o.get("unresolved") as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(),
            files = o.int("files"),
            elapsedMs = o.get("elapsedMs")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0,
        )
    }

    private fun objects(json: JsonElement?): List<JsonObject> = (json as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(name: String): Int = get(name)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
}
