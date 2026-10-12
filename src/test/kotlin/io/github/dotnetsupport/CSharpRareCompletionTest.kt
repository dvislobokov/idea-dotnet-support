package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpCompletionExclusions
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpRare
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.msbuild.PackageCompletionService
import io.github.dotnetsupport.nuget.NuGetClient
import io.github.dotnetsupport.settings.DotNetSettings

/** 0.1.95: the rare places of COMPLETION_GAPS 3.14, "Exclude from completion" (3.11), a format that starts with a digit. */
class CSharpRareCompletionTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true
    private var counter = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            DotNetSettings.getInstance().completionExclusions = emptyList()
            PackageCompletionService.getInstance(project).useForTests(null, null)
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun strings(text: String): List<String> {
        myFixture.configureByText("Rare${counter++}.cs", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    // ---- 3.14

    fun testPlacesByTheTextOfTheLine() {
        assertEquals(CSharpRare.Kind.INTERNALS_VISIBLE_TO, CSharpRare.placeOf("[assembly: InternalsVisibleTo(\"Sh")!!.kind)
        assertEquals("Sh", CSharpRare.placeOf("[assembly: System.Runtime.CompilerServices.InternalsVisibleTo(\"Sh")!!.prefix)
        assertNull("the string is closed", CSharpRare.placeOf("[assembly: InternalsVisibleTo(\"Shop\""))
        assertEquals(CSharpRare.Kind.EXTERN_ALIAS, CSharpRare.placeOf("extern alias ")!!.kind)
        assertEquals(CSharpRare.Kind.CALLING_CONVENTION, CSharpRare.placeOf("    delegate* unmanaged[")!!.kind)
        assertEquals("Std", CSharpRare.placeOf("delegate* unmanaged[Cdecl, Std")!!.prefix)
        assertNull("managed has none", CSharpRare.placeOf("delegate* managed["))
        assertEquals(CSharpRare.Kind.PACKAGE_ID, CSharpRare.placeOf("#:package Ser")!!.kind)
        val version = CSharpRare.placeOf("#:package Serilog@4.")!!
        assertEquals(CSharpRare.Kind.PACKAGE_VERSION, version.kind)
        assertEquals("Serilog", version.argument)
        assertEquals(CSharpRare.Kind.FILE_DIRECTIVE, CSharpRare.placeOf("#:")!!.kind)
        assertNull(CSharpRare.placeOf("var x = 1;"))
    }

    fun testExternAliasesOfTheReferences() {
        val xml = """<Project><ItemGroup><ProjectReference Include="..\A\A.csproj" Aliases="Lib1,Lib2"/><Reference Include="B"><Aliases>Legacy</Aliases></Reference>
            <PackageReference Include="C" Aliases="global"/><Compile Include="x.cs" Aliases="No"/></ItemGroup></Project>"""
        assertEquals(listOf("Lib1", "Lib2", "Legacy"), CSharpRare.externAliases(xml))
        assertEquals(emptyList<String>(), CSharpRare.externAliases("not xml"))
    }

    fun testCallingConventions() {
        val names = strings("unsafe class A { delegate* unmanaged[<caret>]<int, void> f; }")
        assertEquals(listOf("Cdecl", "Stdcall", "Thiscall", "Fastcall", "SuppressGCTransition"), names)
        assertEquals(listOf("Stdcall"), strings("unsafe class A { delegate* unmanaged[Cdecl, Std<caret>]<int, void> f; }"))
    }

    fun testFileBasedAppPackages() {
        val feed = "https://feed.test/index.json"
        val client = NuGetClient { url, _ ->
            when {
                url == feed -> """{"resources":[{"@id":"https://feed.test/query","@type":"SearchQueryService"},{"@id":"https://feed.test/flat/","@type":"PackageBaseAddress/3.0.0"}]}"""
                url.startsWith("https://feed.test/query?q=seri") -> """{"data":[{"id":"Serilog","version":"4.2.0","description":"","totalDownloads":1,"verified":true,"versions":[]},
                    {"id":"Serilog.Sinks.Console","version":"6.0.0","description":"","totalDownloads":1,"verified":true,"versions":[]}]}"""
                url == "https://feed.test/flat/serilog/index.json" -> """{"versions":["4.0.0","4.2.0","4.3.0-dev-1"]}"""
                else -> """{"data":[]}"""
            }
        }
        PackageCompletionService.getInstance(project).useForTests(client, listOf(feed))
        assertEquals(listOf("Serilog", "Serilog.Sinks.Console"), strings("#:package seri<caret>\nSystem.Console.WriteLine();\n"))
        assertEquals(listOf("4.2.0", "4.0.0"), strings("#:package Serilog@<caret>\nSystem.Console.WriteLine();\n"))
        assertEquals(listOf("package", "sdk", "property", "project"), strings("#:<caret>\nSystem.Console.WriteLine();\n"))
    }

    // ---- 3.11

    fun testPatterns() {
        assertEquals(listOf("System.Data.*", "Foo"), CSharpCompletionExclusions.parse("System.Data.*\n\n# comment\n  Foo  \n"))
        val p = listOf("System.Data.*")
        assertTrue(CSharpCompletionExclusions.matches(p, "System.Data.DataTable"))
        assertTrue(CSharpCompletionExclusions.matches(p, "system.data.common.DbConnection"))
        assertFalse(CSharpCompletionExclusions.matches(p, "System.Database"))
        assertTrue("a namespace covers the ones inside", CSharpCompletionExclusions.matches(listOf("System.Text"), "System.Text.Json.JsonSerializer"))
        assertTrue(CSharpCompletionExclusions.matches(listOf("System.Text.StringBuilder"), "System.Text.StringBuilder"))
        assertFalse(CSharpCompletionExclusions.matches(listOf("System.Text.StringBuilder"), "System.Text.StringBuilderX"))
        assertTrue(CSharpCompletionExclusions.matches(listOf("*.Internal.*"), "Foo.Internal.Bar"))
    }

    private fun natives(text: String): List<String> {
        myFixture.configureByText("Excl${counter++}.cs", text)
        myFixture.completeBasic()
        return myFixture.lookupElements?.filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.orEmpty().map(LookupElement::getLookupString)
    }

    fun testExcludedTypesAreNotOffered() {
        val code = "using System;\nclass Sample\n{\n    void Run()\n    {\n        StringBu<caret>\n    }\n}\n"
        assertTrue(natives(code).contains("StringBuilder"))
        DotNetSettings.getInstance().completionExclusions = listOf("System.Text.*")
        assertFalse("not imported and excluded", natives(code).contains("StringBuilder"))
        DotNetSettings.getInstance().completionExclusions = listOf("System")
        val console = "using System;\nclass Sample\n{\n    void Run()\n    {\n        Conso<caret>\n    }\n}\n"
        assertFalse("imported and excluded", natives(console).contains("Console"))
        DotNetSettings.getInstance().completionExclusions = emptyList()
        assertTrue(natives(console).contains("Console"))
    }

    // ---- the guard against numbers lets the format of an interpolation through

    fun testAFormatThatStartsWithADigit() {
        val text = "using System;\nclass Order { public decimal Total { get; set; } }\nclass Sample\n{\n    void Run(Order order)\n    {\n        var s = \$\"{order.Total:0<caret>}\";\n    }\n}\n"
        val items = strings(text)
        assertTrue(items.toString(), "0000" in items && "0.##" in items)
        // and a number outside a format is still left alone
        assertEquals(emptyList<String>(), strings("class Sample\n{\n    int x = 1<caret>;\n}\n"))
    }

    companion object {
        private fun bytes(name: String): ByteArray? = CSharpRareCompletionTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
