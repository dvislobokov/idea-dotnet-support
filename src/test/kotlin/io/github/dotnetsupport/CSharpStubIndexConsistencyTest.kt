package io.github.dotnetsupport

import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubTreeLoader
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.psi.CSharpElement
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys
import io.github.dotnetsupport.lang.CSharpSyntaxTrees

/** The stubs the index builds from a file's text are the ones its PSI builds: else "PSI and index do not match" (the sandbox, 2026-10-19). */
class CSharpStubIndexConsistencyTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
    }

    override fun tearDown() {
        try {
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } finally {
            super.tearDown()
        }
    }

    fun testAnObjectCreationWithItsArgumentsOnTheNextLineIsNoDeclaration() {
        // the webapi template with its `record WeatherForecast(...)` deleted: in the sandbox the index still had WeatherForecast as a type
        // after the IDE was killed (its stub index kept the key of the old text); the parse itself gives the same stubs to both
        val file = myFixture.addFileToProject("StubConsistency/Program.cs", "\n\n" + """
            var builder = WebApplication.CreateBuilder(args);
            var app = builder.Build();
            var summaries = new[] { "Freezing", "Bracing" };

            app.MapGet("/weatherforecast", () =>
            {
                var forecast =  Enumerable.Range(1, 5).Select(index =>
                    new WeatherForecast
                    (
                        DateOnly.FromDateTime(DateTime.Now.AddDays(index)),
                        Random.Shared.Next(-20, 55),
                        summaries[Random.Shared.Next(summaries.Length)]
                    ))
                    .ToArray();
                return forecast;
            })
            .WithName("GetWeatherForecast");

            app.Run();
        """.trimIndent() + "\n")
        val fromIndex = StubTreeLoader.getInstance().readFromVFile(project, file.virtualFile)?.root as? com.intellij.psi.stubs.StubElement<*>
        val fromPsi = (file as PsiFileImpl).calcStubTree().root
        assertEquals(dump(fromPsi), dump(fromIndex))
        val found = ArrayList<CSharpElement>()
        StubIndex.getInstance().processElements(CSharpStubIndexKeys.TYPE_NAMES, "WeatherForecast", project, GlobalSearchScope.projectScope(project), CSharpElement::class.java) { found += it; true }
        assertEmpty(found)
    }

    private fun dump(stub: com.intellij.psi.stubs.StubElement<*>?): String {
        if (stub == null) return "null"
        val out = StringBuilder()
        fun visit(element: com.intellij.psi.stubs.StubElement<*>, depth: Int) {
            out.append("  ".repeat(depth)).append(element.elementType).append(' ').append(element).append('\n')
            for (child in element.childrenStubs) visit(child, depth + 1)
        }
        visit(stub, 0)
        return out.toString()
    }
}
