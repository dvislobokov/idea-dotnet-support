package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import java.io.File

/**
 * The files of debug-playground/Broken/Errors, one per compiler error: each line marked `// ERROR CSxxxx` has that error from the plugin's
 * own pass, every other line none. The same marks are checked against `dotnet build` and the live IDE by tools/diag/check_errors.py; here
 * on the assemblies of src/test/resources/index, with the implicit usings of an SDK project.
 */
class BrokenErrorFilesTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** `CS0128.cs` → its marks against the plugin's errors; empty when they agree. */
    private fun mismatches(file: File): List<String> {
        val text = file.readText().removePrefix("﻿").replace("\r\n", "\n")
        val expected = text.lines().flatMapIndexed { i, line ->
            MARK.find(line)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))?.map { (i + 1) to it }.orEmpty()
        }.toSet()
        myFixture.configureByText(file.name, text)
        val document = myFixture.editor.document
        val infos = myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
        val got = infos.associate { (document.getLineNumber(it.startOffset) + 1 to it.description.substringBefore(':')) to it.description }
        return (expected - got.keys).map { "missing line ${it.first} ${it.second}" } + (got.keys - expected).map { "extra line ${it.first} ${got[it]}" }
    }

    fun testEveryFileAgreesWithItsMarks() {
        myFixture.addFileToProject("GlobalUsings.g.cs", IMPLICIT_USINGS)
        val files = DIRECTORY.listFiles { f -> f.name.endsWith(".cs") }.orEmpty().sortedBy { it.name }
        assertTrue("no files in $DIRECTORY", files.isNotEmpty())
        val report = files.mapNotNull { file -> mismatches(file).takeIf { it.isNotEmpty() }?.let { "${file.name}:\n  " + it.joinToString("\n  ") } }
        assertTrue(report.joinToString("\n"), report.isEmpty())
    }

    companion object {
        private val MARK = Regex("//\\s*ERROR\\s+((?:CS\\d{4}\\s*)+)")
        val DIRECTORY = File("debug-playground/Broken/Errors").absoluteFile

        /**
         * What `<ImplicitUsings>enable</ImplicitUsings>` of Microsoft.NET.Sdk adds, but `System.Net.Http`: its assembly is not among the
         * fixtures, and a namespace that does not resolve keeps the checks silent.
         */
        private val IMPLICIT_USINGS = listOf("System", "System.Collections.Generic", "System.IO", "System.Linq", "System.Threading", "System.Threading.Tasks")
            .joinToString("\n") { "global using global::$it;" }
    }
}
