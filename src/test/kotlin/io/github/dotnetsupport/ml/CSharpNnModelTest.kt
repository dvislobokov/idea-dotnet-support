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

    @Test fun aFluentChainGoesOnBelowTheLineWithTheOpenBracket() {
        assumeTrue("no ml-models/csharp/${CSharpMlModels.NN_MODEL}", dir != null)
        val nn = checkNotNull(CSharpMlModels.loadNn(dir)) { "no network in $dir" }
        try {
            // the file shows the style (the ShopApi Program.cs of 2026-10-08): the model continues the chain line by line
            val head = "using OpenTelemetry.Metrics;\n\nvar builder = WebApplication.CreateBuilder(args);\n\nbuilder.Services.AddOpenTelemetry()\n    .WithTracing(tracing => tracing\n        .AddSource(ShopTelemetry.ServiceName)\n        .AddAspNetCoreInstrumentation()\n        .AddOtlpExporter())\n    .WithMetrics(metrics => metrics\n        .AddMeter(ShopMetrics.MeterName)\n        .AddAspNetCoreInstrumentation()\n        .AddPrometheusExporter());\n\nbuilder.Services.AddOpenTelemetry()\n    "
            val tail = "\n\nvar app = builder.Build();\napp.Run();\n"
            nn.model.newSession(4096).use { s ->
                val c = CSharpNnInline.context(head + tail, head.length, "Program.cs")
                val first = nn.completion.complete(c.path, c.before, c.after, s)
                val text = CSharpNnInline.continueOpenBrackets(CSharpNnInline.lineBefore(c.before, 0), first.textString, 8) { accepted ->
                    val n = nn.completion.complete(c.path, c.before + accepted, c.after, s)
                    n.textString.takeIf { n.text.isNotEmpty() && !n.repeated && !n.healMiss && n.confProd >= 0.25 }
                }
                println("CSharpNnModelTest: fluent chain -> '${text.replace("\n", "⏎")}' (first line confProd ${first.confProd})")
                assertTrue("the first line opens a bracket: '${first.textString}'", CSharpNnInline.openBrackets(ByteArray(0), first.text) > 0)
                assertTrue("goes on below: '$text'", text.contains('\n'))
                assertTrue("the brackets close: '$text'", CSharpNnInline.openBrackets(ByteArray(0), text.toByteArray()) <= 0)
            }
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
