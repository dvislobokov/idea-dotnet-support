package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.roslyn.HierarchyItem
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import java.io.File

/** The items of the server (captured traffic of server 5.12) as the rows of the Hierarchy tool window. */
class RoslynHierarchyTest : BasePlatformTestCase() {
    private val gson = MessageJsonHandler(emptyMap()).gson
    private val capture = File("src/test/resources/roslyn/capture-5.12")

    private fun result(number: String): com.google.gson.JsonElement =
        JsonParser.parseString(capture.listFiles()!!.first { it.name.startsWith("$number-") }.readText()).asJsonObject["result"]

    fun testTypeHierarchyItemsFromTheCapture() {
        val base = result("42").asJsonArray.map { gson.fromJson(it, TypeHierarchyItem::class.java) }.map(HierarchyItem::of).single()
        assertEquals("CaptureShapeBase", base.name)
        assertEquals("Playground", base.container)
        assertFalse(base.isInterface)
        assertEquals(AllIcons.Nodes.Class, base.icon)

        val supertypes = result("43").asJsonArray.map { gson.fromJson(it, TypeHierarchyItem::class.java) }.map(HierarchyItem::of)
        assertEquals(listOf("ICaptureShape"), supertypes.map { it.name })
        assertTrue(supertypes.single().isInterface)
        assertEquals(AllIcons.Nodes.Interface, supertypes.single().icon)

        val subtypes = result("44").asJsonArray.map { gson.fromJson(it, TypeHierarchyItem::class.java) }.map(HierarchyItem::of)
        assertEquals(listOf("CaptureSquare", "CaptureCircle"), subtypes.map { it.name })
        // the item goes back to the server as it came, with its `data` (the symbol key)
        val back = base.toTypeItem()
        assertEquals(base.name, back.name)
        assertNotNull(back.data)
        assertTrue(base.sameAs(HierarchyItem.of(back)))
        assertFalse(base.sameAs(subtypes.first()))
    }

    fun testCallHierarchyItemsFromTheCapture() {
        val callers = result("40").asJsonArray.map { gson.fromJson(it, CallHierarchyIncomingCall::class.java) }.map { HierarchyItem.of(it.from) }
        assertEquals(listOf("LspCapture.Use()"), callers.map { it.name })
        assertEquals("Playground.LspCapture", callers.single().container)
        assertEquals(AllIcons.Nodes.Method, callers.single().icon)
        assertEquals(0, result("41").asJsonArray.size())
    }
}
