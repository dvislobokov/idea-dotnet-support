package io.github.dotnetsupport

import com.google.gson.JsonParser
import io.github.dotnetsupport.roslyn.RoslynResponseMemo
import junit.framework.TestCase
import org.eclipse.lsp4j.services.TextDocumentService
import java.io.File

/** The answers the plugin gives again while the workspace has not changed: generations, and copies that lose nothing of a real answer. */
class RoslynResponseMemoTest : TestCase() {
    private val directory = File(javaClass.getResource("/roslyn/capture-5.12/01-initialize.json")!!.toURI()).parentFile

    fun testGenerations() {
        val memo = RoslynResponseMemo()
        val asked = memo.current
        memo.put("textDocument/hover a", asked, "{}")
        assertEquals("{}", memo.get("textDocument/hover a"))

        memo.invalidate()
        assertNull("a change forgets every answer", memo.get("textDocument/hover a"))

        val askedBefore = memo.current
        memo.invalidate()
        memo.put("textDocument/hover b", askedBefore, "{}")
        assertNull("an answer that comes after a change is of the old workspace: not kept", memo.get("textDocument/hover b"))
    }

    fun testOnlyTheLatestAnswersAreKept() {
        val memo = RoslynResponseMemo()
        for (i in 0 until 600) memo.put("k$i", memo.current, "$i")
        assertEquals(512, memo.size)
        assertNull(memo.get("k0"))
        assertEquals("599", memo.get("k599"))
    }

    fun testWhatIsCachedAndWhatForgets() {
        val methods = TextDocumentService::class.java.methods.map { it.name }.toSet()
        assertTrue("every cacheable name is a method of lsp4j", methods.containsAll(RoslynResponseMemo.CACHEABLE))
        for (never in listOf("completion", "diagnostic", "semanticTokensFull", "rename", "formatting", "onTypeFormatting")) {
            assertFalse(never, never in RoslynResponseMemo.CACHEABLE)
        }
        assertTrue(RoslynResponseMemo.CHANGES.containsAll(listOf("didChange", "didClose", "didSave", "didChangeWatchedFiles")))
        assertFalse("opening a file changes nothing the server knows", "didOpen" in RoslynResponseMemo.CHANGES)
    }

    /**
     * Real answers of the server through the copy the memo keeps: what the platform gets from the memo is what it would get from lsp4j,
     * `Either` and enums included. (lsp4j itself drops the fields it does not know, such as `glyph` of Roslyn: lsp4j-losses.txt.)
     */
    fun testCopiesOfRealAnswersLoseNothing() {
        val cases = mapOf(
            "09-textDocument_hover.json" to "hover",
            "18-textDocument_documentHighlight.json" to "documentHighlight",
            "19-textDocument_documentSymbol.json" to "documentSymbol",
            "21-textDocument_foldingRange.json" to "foldingRange",
            "25-textDocument_inlayHint.json" to "inlayHint",
            "26-textDocument_codeLens.json" to "codeLens",
            "27-codeLens_resolve.json" to "resolveCodeLens",
            "29-textDocument_codeAction_at_warning.json" to "codeAction",
            "31-codeAction_resolve_plain_action.json" to "resolveCodeAction",
            "11-textDocument_signatureHelp.json" to "signatureHelp",
            "12-textDocument_definition.json" to "definition",
            "17-textDocument_references.json" to "references",
        )
        for ((file, methodName) in cases) {
            val record = JsonParser.parseString(File(directory, file).readText()).asJsonObject
            val method = TextDocumentService::class.java.methods.first { it.name == methodName }
            val adapter = RoslynResponseMemo.adapter(method)!!
            val answer = adapter.fromJson(record.get("result").toString())
            val kept = adapter.toJson(answer)
            val copy = adapter.fromJson(kept)
            assertEquals(file, JsonParser.parseString(kept), JsonParser.parseString(adapter.toJson(copy)))
            assertNotSame(file, answer, copy)
        }
    }

}
