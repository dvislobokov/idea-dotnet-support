package io.github.dotnetsupport.ml

import io.github.completionml.core.nn.native.NativeLib
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The real network of `ml-models/csharp` through the loader of [CSharpMlModels]; skipped when the repository has no models. */
class CSharpNnModelTest {
    private val dir = listOf(File("ml-models/csharp"), File("../ml-models/csharp")).firstOrNull { File(it, CSharpMlModels.NN_MODEL).isFile }

    @Test fun completesACSharpLine() {
        assumeTrue("no ml-models/csharp/${CSharpMlModels.NN_MODEL}", dir != null)
        val nn = checkNotNull(CSharpMlModels.loadNn(dir)) { "no network in $dir" }
        try {
            val before = "using System;\n\nnamespace Demo\n{\n    public class Program\n    {\n        public static void Main()\n        {\n            Console.Wri"
            val c = CSharpNnInline.context(before + "\n        }\n    }\n}\n", before.length, "Program.cs")
            val started = System.currentTimeMillis()
            val r = nn.model.newSession(2048).use { nn.completion.complete(c.path, c.before, c.after, it) }
            println("CSharpNnModelTest: '${r.textString}' confProd ${r.confProd} show ${r.show} in ${System.currentTimeMillis() - started} ms; kernels: ${NativeLib.status}")
            assertTrue("empty completion", r.text.isNotEmpty())
            assertTrue("healed from the word start: '${r.textString}'", r.textString.startsWith("teLine("))
            assertTrue(NativeLib.status.isNotBlank())
        } finally { nn.model.close() }
    }

    @Test fun prefillThenCompleteReusesTheSession() {
        assumeTrue("no ml-models/csharp/${CSharpMlModels.NN_MODEL}", dir != null)
        val nn = checkNotNull(CSharpMlModels.loadNn(dir)) { "no network in $dir" }
        try {
            val head = "using System.Collections.Generic;\n\nnamespace Demo\n{\n    public class Order\n    {\n        private readonly List<string> items = new();\n\n        public int Count()\n        {\n            return "
            nn.model.newSession(2048).use { s ->
                // the prefill of the file as opened (the caret at the start of the statement), then the completion after a few keystrokes
                val c0 = CSharpNnInline.context(head + "\n        }\n    }\n}\n", head.length, "Order.cs")
                s.prefill(nn.completion.buildPrompt(c0.path, c0.before, nn.completion.healedBoundary(c0.before, c0.after), c0.after))
                val c1 = CSharpNnInline.context(head + "it\n        }\n    }\n}\n", head.length + 2, "Order.cs")
                val started = System.currentTimeMillis()
                val r = nn.completion.complete(c1.path, c1.before, c1.after, s)
                println("CSharpNnModelTest: 'return it' -> '${r.textString}' confProd ${r.confProd} in ${System.currentTimeMillis() - started} ms after a prefill")
                assertTrue("continues the word: '${r.textString}'", r.textString.startsWith("ems."))
            }
        } finally { nn.model.close() }
    }
}
