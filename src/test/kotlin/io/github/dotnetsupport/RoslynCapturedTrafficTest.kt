package io.github.dotnetsupport

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.dotnetsupport.roslyn.RoslynClientCommands
import io.github.dotnetsupport.roslyn.RoslynServer
import junit.framework.TestCase
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import java.io.File

/**
 * The answers of the real server (5.12), recorded by `tools/roslyn-lsp/capture.py --fixtures`, against what the plugin assumes
 * about them. Nothing is started here. When the server is updated: record again, run, and read what has changed.
 */
class RoslynCapturedTrafficTest : TestCase() {
    private val directory = File(javaClass.getResource("/roslyn/capture-5.12/01-initialize.json")!!.toURI()).parentFile
    private val records: Map<String, JsonObject> = directory.listFiles { file -> file.extension == "json" }!!.sortedBy { it.name }
        .associate { it.nameWithoutExtension.substringAfter('-') to JsonParser.parseString(it.readText()).asJsonObject }
    private val methods = ServiceEndpoints.getSupportedMethods(RoslynServer::class.java)
    private val handler = MessageJsonHandler(methods)

    private fun result(name: String): JsonElement = records.getValue(name).get("result")

    /**
     * Every answer goes through the classes of lsp4j and back, as it does inside the IDE, and what does not survive is compared with
     * the list in `lsp4j-losses.txt`. That is how the numbers of Visual Studio in `tags` (read as nulls, sent back, refused by the
     * server) would have been seen before any user: a new line in that list is a thing to look at, not to paste.
     */
    fun testWhatLsp4jLoses() {
        val losses = sortedSetOf<String>()
        for ((name, record) in records) {
            val method = methods[record.get("method").asString] ?: continue
            val original = record.get("result")?.takeUnless { it.isJsonNull }?.let(::withoutCutMarks) ?: continue
            val title = record.get("method").asString
            // an answer lsp4j cannot read at all is an answer the IDE never gets
            val back = try {
                // a whole response with a known method: the adapters that tell the alternatives of an answer apart are bound to the method
                handler.setMethodProvider { title }
                val envelope = JsonObject().apply { addProperty("jsonrpc", "2.0"); addProperty("id", "1"); add("result", original) }
                JsonParser.parseString(handler.serialize(handler.parseMessage(envelope.toString()))).asJsonObject.get("result")
            } catch (e: RuntimeException) {
                losses += "$title !! CANNOT BE READ ($name): ${e.message}"
                continue
            }
            lost(original, back, "").mapTo(losses) { "$title $it" }
        }
        val expected = File(directory, "lsp4j-losses.txt").takeIf { it.isFile }?.readLines().orEmpty().filter { it.isNotBlank() && !it.startsWith("#") }.toSortedSet()
        assertEquals("what lsp4j drops or changes on the way, one `method path` per line:\n" + losses.joinToString("\n") + "\n", expected.joinToString("\n"), losses.joinToString("\n"))
    }

    /** `capture.py` marks a list it has cut with a last element `{"_truncated": n}`, which is not a thing of the protocol. */
    private fun withoutCutMarks(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray().also { copy -> element.filterNot { it is JsonObject && it.has("_truncated") }.forEach { copy.add(withoutCutMarks(it)) } }
        is JsonObject -> JsonObject().also { copy -> element.entrySet().forEach { (key, value) -> copy.add(key, withoutCutMarks(value)) } }
        else -> element
    }

    /** Paths (indices of arrays as `[]`) whose value is gone or different after the round trip. */
    private fun lost(original: JsonElement, back: JsonElement?, path: String): Set<String> = when {
        original.isJsonNull -> emptySet()
        original is JsonObject -> original.entrySet().flatMapTo(sortedSetOf()) { (key, value) ->
            lost(value, (back as? JsonObject)?.get(key), if (path.isEmpty()) key else "$path.$key")
        }
        original is JsonArray -> original.withIndex().flatMapTo(sortedSetOf()) { (index, value) -> lost(value, (back as? JsonArray)?.takeIf { index < it.size() }?.get(index), "$path[]") }
        back == null || back.isJsonNull -> setOf(path)
        // 1 and 1.0 are the same number
        original.asJsonPrimitive.isNumber && back.isJsonPrimitive && back.asJsonPrimitive.isNumber -> if (original.asDouble == back.asDouble) emptySet() else setOf(path)
        else -> if (original == back) emptySet() else setOf(path)
    }

    /** The commands the server leaves to its client, exactly as they come. */
    fun testClientCommands() {
        val lens = result("codeLens_resolve").asJsonObject.getAsJsonObject("command")
        assertEquals(RoslynClientCommands.PEEK_REFERENCES, lens.get("command").asString)
        val (uri, position) = RoslynClientCommands.peekReferences(lens.getAsJsonArray("arguments").toList())!!
        assertTrue(uri, uri.startsWith("file:///") && uri.endsWith("/Console/Scenarios.cs"))
        assertTrue(position.line > 0)

        val actions = result("textDocument_codeAction_at_warning").asJsonArray.map { it.asJsonObject }.filter { it.has("command") }
        fun arguments(command: String) = actions.first { it.getAsJsonObject("command").get("command").asString == command }.getAsJsonObject("command").getAsJsonArray("arguments").toList()

        val nested = RoslynClientCommands.nestedActions(arguments(RoslynClientCommands.NESTED_CODE_ACTION))
        assertTrue(nested.size >= 2)
        assertTrue("a variant is resolved by its data", nested.all { it.title.isNotBlank() && it.data != null && it.edit == null })

        val fixAll = RoslynClientCommands.fixAll(arguments(RoslynClientCommands.FIX_ALL))!!
        assertTrue(fixAll.scopes.containsAll(listOf("Document", "Project", "Solution")))
        assertEquals("Containing Member", RoslynClientCommands.scopeTitle("ContainingMember"))
        assertNull(RoslynClientCommands.fixAll(emptyList()))
        assertNull(RoslynClientCommands.peekReferences(emptyList()))
        assertEmpty(RoslynClientCommands.nestedActions(emptyList()))
    }

    private fun assertEmpty(list: List<*>) = assertTrue(list.toString(), list.isEmpty())

    /** Facts the client is built on; each has bitten once. */
    fun testFactsTheClientReliesOn() {
        // resolved variants and "Fix All" come back as ordinary code actions with an edit
        for (name in listOf("codeAction_resolve_plain_action", "codeAction_resolve_nested_action", "codeAction_resolveFixAll_document")) {
            assertTrue(name, result(name).asJsonObject.getAsJsonObject("edit").has("documentChanges"))
        }
        // the server refuses the whole request when a diagnostic comes back with a null tag...
        val refused = records.getValue("textDocument_codeAction_with_null_tags_what_lsp4j_sends_back").getAsJsonObject("error").get("message").asString
        assertTrue(refused, "DiagnosticTag" in refused)
        // ...and every diagnostic carries tags lsp4j reads as null
        val tags = result("textDocument_diagnostic").asJsonObject.getAsJsonArray("items").flatMap { it.asJsonObject.getAsJsonArray("tags")?.map { tag -> tag.asLong }.orEmpty() }
        assertTrue(tags.toString(), tags.any { it > 2 })
        // completion: `data` of an item lives in itemDefaults (the platform merges it), and resolve without it takes the server down
        val completion = result("textDocument_completion_member").asJsonObject
        assertTrue(completion.getAsJsonObject("itemDefaults").has("data"))
        assertFalse(completion.getAsJsonArray("items").first().asJsonObject.has("data"))
        assertTrue("shut down" in records.getValue("textDocument_hover_after_the_resolve_without_data").getAsJsonObject("error").get("message").asString)
        // a definition inside the framework is a real file on disk, decompiled by the server
        assertTrue(result("definition_target_uri").asJsonObject.get("uri").asString.contains("MetadataAsSource"))
    }
}
