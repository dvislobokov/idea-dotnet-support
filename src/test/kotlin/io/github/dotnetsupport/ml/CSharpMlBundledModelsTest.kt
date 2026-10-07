package io.github.dotnetsupport.ml

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The ML build (`-PmlEnabled=true`): the models are in the resources and load through the service — the ranker pair with the schema of
 * [CSharpMlFeatures], the network from a copy of the resource in the system directory. Nothing to check in a plain build.
 */
class CSharpMlBundledModelsTest : BasePlatformTestCase() {
    fun testBundledModelsLoad() {
        if (!CSharpMlModels.isBundled) { println("CSharpMlBundledModelsTest: plain build, nothing bundled"); return }
        val models = CSharpMlModels.getInstance()
        if (CSharpMlModels.isRankerBundled) {
            var loaded = models.get("")
            val deadline = System.currentTimeMillis() + 60_000
            while (loaded == null && System.currentTimeMillis() < deadline) { Thread.sleep(100); loaded = models.get("") }
            assertNotNull("the bundled ranker pair loads: ${models.status("")}", loaded)
            assertEquals(CSharpMlFeatures.schema.names, loaded!!.ranker.schema.names)
        }
        if (CSharpMlModels.isNnBundled) {
            val nn = checkNotNull(CSharpMlModels.loadNn(null)) { "no bundled network" }
            try {
                val before = "using System;\n\nclass Program\n{\n    static void Main()\n    {\n        Console.Wri"
                val c = CSharpNnInline.context(before + "\n    }\n}\n", before.length, "Program.cs")
                val r = nn.model.newSession(2048).use { nn.completion.complete(c.path, c.before, c.after, it) }
                assertTrue("the bundled network completes: '${r.textString}'", r.text.isNotEmpty())
            } finally { nn.model.close() }
        }
    }
}
