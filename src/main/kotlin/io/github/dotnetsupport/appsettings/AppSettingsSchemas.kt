package io.github.dotnetsupport.appsettings

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.github.dotnetsupport.msbuild.ProjectAssets
import java.io.File

/**
 * The JSON schema of `appsettings*.json` of a project, put together from three parts, the most specific first: the sections the code of
 * the project binds (DotNetHelper, [AppSettingsResponses]), the `ConfigurationSchema.json` files of its NuGet packages (Aspire, the Azure
 * SDK...) and the base sections of ASP.NET Core. Pure functions on Gson trees, so the tests need neither the helper nor the JSON plugin.
 *
 * The base is either SchemaStore's `appsettings.json` (downloaded by the IDE the way it downloads its catalog: a provider of a schema for
 * a file turns the catalog off for that file, so SchemaStore's sections have to come in through our schema) or, while it is not there
 * or the IDE may not go to the network for schemas, a small one of our own (`resources/appsettingsSchema/base.json`).
 */
object AppSettingsSchemas {
    const val SCHEMA_STORE_URL = "https://www.schemastore.org/appsettings.json"
    private const val DRAFT_07 = "http://json-schema.org/draft-07/schema#"

    /** Marks an object of the schema made of a C# class: its keys are all known, another key is a mistake or comes from elsewhere. */
    const val CLOSED_TYPE = "x-dotnet-type"

    private val NAME = Regex("""appsettings(\.[^/\\]+)?\.json""", RegexOption.IGNORE_CASE)

    /** `appsettings.json` and `appsettings.<Environment>.json`, the files the host reads. */
    fun isAppSettings(fileName: String): Boolean = NAME.matches(fileName)

    private val base: JsonObject by lazy {
        val text = AppSettingsSchemas::class.java.getResourceAsStream("/appsettingsSchema/base.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
        (text?.let { JsonParser.parseString(it) as? JsonObject } ?: JsonObject()).apply { remove("about") }
    }

    /** A `ConfigurationSchema.json` of a package. */
    class PackageSchema(val id: String, val schema: JsonObject)

    /** The `ConfigurationSchema.json` files at the root of the packages restored for the project, one per package. */
    fun packageSchemaFiles(assets: ProjectAssets): List<Pair<String, File>> {
        val result = LinkedHashMap<String, File>()
        for (target in assets.targets) for (pkg in target.packages) {
            if (pkg.name.lowercase() in result) continue
            val file = assets.packageFolders.asSequence().map { File(it, "${pkg.name.lowercase()}/${pkg.version.lowercase()}/ConfigurationSchema.json") }.firstOrNull { it.isFile }
            if (file != null) result[pkg.name.lowercase()] = file
        }
        return result.map { (id, file) -> id to file }
    }

    fun parsePackageSchema(id: String, text: String): PackageSchema? = parseSchema(text)?.let { PackageSchema(id, it) }

    /**
     * The whole schema: [code] (the `schema` of the answer of the helper), then [packages], then the base — [schemaStore] (SchemaStore's
     * appsettings schema, as the IDE has downloaded it) when there is one, else ours. Each is put in as it is, not by `$ref`: the JSON
     * support of 2026.1 does not follow a remote `$ref` inside a schema (registry `json.schema.object.v2.enable.nested.remote.schema.resolve`,
     * off). Definitions of a part are renamed `<part>.<name>` so two parts cannot clash.
     */
    fun merge(code: JsonObject?, packages: List<PackageSchema>, schemaStore: JsonObject?): JsonObject {
        val root = JsonObject().apply { addProperty("\$schema", DRAFT_07); addProperty("type", "object") }
        val definitions = JsonObject()
        code?.let { mergeInto(root, it.deepCopy().apply { remove("\$schema") }) }
        fun part(name: String, schema: JsonObject) {
            val renamed = renameDefinitions(schema.deepCopy(), "$name.")
            (renamed.remove("definitions") as? JsonObject)?.entrySet()?.forEach { (definition, value) -> definitions.add(definition, value) }
            for (key in listOf("\$schema", "\$id", "id", "title", "description", "about")) renamed.remove(key)
            mergeInto(root, renamed)
        }
        for (pkg in packages) part(pkg.id, pkg.schema)
        if (schemaStore != null) part("schemastore", schemaStore) else part("base", base)
        if (definitions.size() > 0) root.add("definitions", definitions)
        if (!root.has("properties")) root.add("properties", JsonObject())
        return root
    }

    fun parseSchema(text: String): JsonObject? = runCatching { JsonParser.parseString(text.removePrefix("﻿")) }.getOrNull() as? JsonObject

    /** `#/definitions/x` → `#/definitions/<prefix>x`, in the definitions and in every `$ref`. */
    fun renameDefinitions(schema: JsonObject, prefix: String): JsonObject {
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> for ((key, value) in element.entrySet().toList()) {
                    if (key == "\$ref" && value is JsonPrimitive && value.isString && value.asString.startsWith("#/definitions/")) {
                        element.addProperty(key, "#/definitions/$prefix" + value.asString.removePrefix("#/definitions/"))
                    } else walk(value)
                }
                is JsonArray -> element.forEach(::walk)
                else -> {}
            }
        }
        walk(schema)
        (schema.get("definitions") as? JsonObject)?.let { old ->
            schema.add("definitions", JsonObject().apply { old.entrySet().forEach { (name, value) -> add(prefix + name, value) } })
        }
        return schema
    }

    /**
     * [from] into [into]: properties are joined key by key, what [into] lacks is taken over. Two sources describing one object make it
     * open: neither knows all of its keys, so `additionalProperties: false` and [CLOSED_TYPE] go. A `$ref` next to properties would be
     * ignored by draft-07, so it goes to `allOf`.
     */
    fun mergeInto(into: JsonObject, from: JsonObject) {
        val bothDescribe = into.has("properties") || into.has("additionalProperties") || into.has(CLOSED_TYPE)
        val closedBefore = into.get(CLOSED_TYPE)
        for ((key, value) in from.entrySet()) {
            when {
                key == "properties" && value is JsonObject -> {
                    val target = into.getAsJsonObject("properties") ?: JsonObject().also { into.add("properties", it) }
                    for ((name, property) in value.entrySet()) {
                        val existing = target.get(name) as? JsonObject
                        if (existing != null && property is JsonObject) mergeInto(existing, property) else if (existing == null) target.add(name, property.deepCopy())
                    }
                }
                key == "\$ref" && !into.has("\$ref") && bothDescribe -> {
                    val allOf = into.get("allOf") as? JsonArray ?: JsonArray().also { into.add("allOf", it) }
                    allOf.add(JsonObject().apply { add("\$ref", value) })
                }
                key == "allOf" && value is JsonArray && into.get("allOf") is JsonArray -> into.getAsJsonArray("allOf").addAll(value.deepCopy())
                !into.has(key) -> into.add(key, value.deepCopy())
            }
        }
        if (bothDescribe && from.entrySet().isNotEmpty()) {
            if (closedBefore != from.get(CLOSED_TYPE)) into.remove(CLOSED_TYPE)
            if (into.get("additionalProperties")?.let { it is JsonPrimitive && it.isBoolean && !it.asBoolean } == true) into.remove("additionalProperties")
        }
    }

    /**
     * The C# type that does not bind [key] at [path] of the file (keys of objects, `null` for an item of an array), or null when the key is
     * fine or nothing is known: only objects made of a class of the code are closed. Configuration keys ignore case, as the binder does.
     */
    fun unknownKey(schema: JsonObject, path: List<String?>, key: String): String? {
        var current: JsonObject = schema
        for (segment in path) current = child(current, segment) ?: return null
        val type = current.get(CLOSED_TYPE)?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val properties = current.getAsJsonObject("properties") ?: return null
        return if (properties.keySet().any { it.equals(key, ignoreCase = true) }) null else type
    }

    private fun child(schema: JsonObject, segment: String?): JsonObject? {
        if (segment == null) return schema.get("items") as? JsonObject
        schema.getAsJsonObject("properties")?.entrySet()?.firstOrNull { it.key.equals(segment, ignoreCase = true) }?.let { return it.value as? JsonObject }
        return schema.get("additionalProperties") as? JsonObject
    }
}
