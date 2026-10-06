package io.github.dotnetsupport

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.CommonProcessors
import com.intellij.util.indexing.FindSymbolParameters
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyGotoClassContributor
import io.github.dotnetsupport.index.AssemblyGotoContributor
import io.github.dotnetsupport.index.AssemblyGotoSymbolContributor
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.index.AssemblyMemberItem
import io.github.dotnetsupport.index.AssemblyMetadataFile
import io.github.dotnetsupport.index.AssemblyMetadataText
import io.github.dotnetsupport.index.AssemblyNavigation
import io.github.dotnetsupport.index.AssemblyTypeItem
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpGotoDeclarationHandler
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynOptions
import java.io.File

/**
 * Go to Class / Go to Symbol over the referenced assemblies and their metadata view (CSHARP_PSI_MIGRATION.md, B4), on the fixtures of
 * src/test/resources/index: the text of the view (a golden file), that it is C# the native parser takes without an error, the contributors
 * with and without the non-project items, the deduplication of one assembly of two projects, navigation to the line of a member and Go to
 * Declaration of the native tree into the view, and how fast the names of ~600 indexes are.
 */
class AssemblyGotoLibraryTest : BasePlatformTestCase() {
    private lateinit var app: VirtualFile
    private lateinit var lib: VirtualFile

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        app = myFixture.addFileToProject("GotoLibApp/GotoLibApp.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />").virtualFile
        lib = myFixture.addFileToProject("GotoLibLib/GotoLibLib.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />").virtualFile
        val service = AssemblyIndexService.getInstance(project)
        // the same assembly in two projects is one index (the service opens a file once); System.Collections a second time, as another file
        // of the same assembly and version: one item all the same
        service.set(app, listOf(FIXTURE, CONSOLE, COLLECTIONS))
        service.set(lib, listOf(FIXTURE, fixture("System.Collections")))
        service.setAssemblyFile(CONSOLE, File("C:/dotnet/packs/Microsoft.NETCore.App.Ref/10.0.0/ref/net10.0/System.Console.dll"))
    }

    override fun tearDown() {
        try {
            FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
            AssemblyIndexService.getInstance(project).clearIndexes()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            RoslynLanguageServerSettings.getInstance().state.options = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun type(index: AssemblyIndex, name: String) = index.findType(name) ?: error("no type $name")

    fun testTheMetadataTextOfTheFixtureTypes() {
        val names = listOf("Fixture.Shape", "Fixture.Circle", "Fixture.Box`1", "Fixture.IShape`2", "Fixture.Money", "Fixture.Cursor", "Fixture.Color", "Fixture.Handler",
            "Fixture.ShapeExtensions", "Fixture.Old", "Fixture.Outer", "Fixture.Point")
        val text = names.joinToString("\n// ----------------------------------------\n\n") { AssemblyMetadataText.render(type(FIXTURE, it), "C:/fixture/IndexFixture.dll").text }
        val golden = File("src/test/resources/metadata/IndexFixture.txt")
        if (System.getProperty("metadata.update") == "true" || !golden.isFile) {
            golden.parentFile.mkdirs()
            golden.writeText(text)
        }
        assertEquals(golden.readText().replace("\r\n", "\n"), text)
    }

    fun testTheMetadataViewIsCSharpWithoutSyntaxErrors() {
        // with METADATA_INDEXES every type of the playground's references too
        val more = System.getenv("METADATA_INDEXES")?.let(::File)?.listFiles { it -> it.name.endsWith(".dnix") }.orEmpty().map { AssemblyIndex.open(it.toPath()) }
        for (index in listOf(FIXTURE, CONSOLE, COLLECTIONS, LINQ) + more) {
            for (type in index.allTypes.filter { it.declaringType == null }) {
                val file = AssemblyNavigation.file(type)
                val psi = PsiManager.getInstance(project).findFile(file)!!
                val errors = PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java)
                assertTrue("$type: ${errors.map { it.errorDescription + " at " + file.rendered.text.substring(it.textRange.startOffset).lineSequence().first() }}", errors.isEmpty())
                assertFalse(file.isWritable)
            }
        }
    }

    fun testTheFileIsFoundAgainByItsUrl() {
        val file = AssemblyNavigation.file(type(COLLECTIONS, "System.Collections.Generic.List`1+Enumerator"))
        assertEquals("List.cs", file.name)
        assertEquals("System.Collections.Generic.List`1", file.type.fullName)
        assertTrue(file.url, file.url.startsWith("dotnet-metadata://v${AssemblyIndex.FORMAT_VERSION}/${COLLECTIONS.mvid}/System.Collections.Generic.List`1/List.cs"))
        assertSame(file, VirtualFileManager.getInstance().findFileByUrl(file.url))
        assertNull(VirtualFileManager.getInstance().findFileByUrl("dotnet-metadata://v${AssemblyIndex.FORMAT_VERSION}/0000/System.Nothing/Nothing.cs"))
        val console = AssemblyNavigation.file(type(CONSOLE, "System.Console")).rendered.text
        assertTrue(console.lines().take(5).toString(), console.contains("// Assembly location: " + File("C:/dotnet/packs/Microsoft.NETCore.App.Ref/10.0.0/ref/net10.0/System.Console.dll").path))
        assertTrue(console.contains("/// <summary>Writes the specified string value, followed by the current line terminator, to the standard output stream.</summary>"))
    }

    private fun names(contributor: AssemblyGotoContributor, scope: GlobalSearchScope): Set<String> =
        CommonProcessors.CollectProcessor<String>().also { contributor.processNames(it, scope, null) }.results.toSet()

    private fun items(contributor: AssemblyGotoContributor, name: String, everywhere: Boolean): List<Any> =
        CommonProcessors.CollectProcessor<Any>().also { contributor.processElementsWithName(name, it, FindSymbolParameters.wrap(name, project, everywhere)) }.results.toList()

    fun testGoToClassOffersTheTypesOfTheLibrariesWithNonProjectItemsOnly() {
        val contributor = AssemblyGotoClassContributor()
        assertEmpty(names(contributor, ProjectScope.getProjectScope(project)))
        val names = names(contributor, ProjectScope.getAllScope(project))
        assertTrue(names.containsAll(listOf("List", "Box", "Console", "Enumerator", "Shape")))
        assertFalse("members are for Go to Symbol", "WriteLine" in names)
        assertEmpty(items(contributor, "List", everywhere = false))

        val list = items(contributor, "List", everywhere = true).single() as AssemblyTypeItem
        assertEquals("List<T>", list.presentableText)
        assertEquals("(System.Collections.Generic, System.Collections 10.0)", list.locationString)
        assertEquals("System.Collections.Generic.List", contributor.getQualifiedName(list))
        assertEquals("one Box of the two projects", 1, items(contributor, "Box", everywhere = true).size)
        val nested = items(contributor, "Enumerator", everywhere = true).map { (it as AssemblyTypeItem).presentableText }
        assertTrue(nested.toString(), "List<T>.Enumerator" in nested && "Dictionary<TKey, TValue>.Enumerator" in nested)
    }

    fun testGoToSymbolOffersTheMembersAndNavigatesToTheirLine() {
        val contributor = AssemblyGotoSymbolContributor()
        assertTrue(names(contributor, ProjectScope.getAllScope(project)).containsAll(listOf("WriteLine", "Describe", "Console")))
        assertEmpty(names(contributor, ProjectScope.getProjectScope(project)))
        val writeLines = items(contributor, "WriteLine", everywhere = true).map { it as AssemblyMemberItem }
        assertEquals("every overload once", type(CONSOLE, "System.Console").members.count { it.name == "WriteLine" }, writeLines.size)
        assertTrue(writeLines.map { it.presentableText }.toString(), writeLines.any { it.presentableText == "WriteLine(string)" })
        assertEquals("(Console, System, System.Console 10.0)", writeLines.first().locationString)

        val describe = items(contributor, "Describe", everywhere = true).map { it as AssemblyMemberItem }.single { it.member.type.name == "Shape" }
        describe.navigate(true)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertTrue(editor.virtualFile is AssemblyMetadataFile)
        val line = editor.document.getLineNumber(editor.caretModel.offset)
        val text = editor.document.getText(com.intellij.openapi.util.TextRange(editor.document.getLineStartOffset(line), editor.document.getLineEndOffset(line)))
        assertEquals("public virtual string Describe(string? prefix = null, int digits = 2);", text.trim())
        assertTrue("at the name", editor.document.text.startsWith("Describe(", editor.caretModel.offset))
        assertFalse(editor.document.isWritable)

        // the view is highlighted as any C# file: the annotators of the plugin take a file of no project
        myFixture.openFileInEditor(editor.virtualFile!!)
        myFixture.doHighlighting()
    }

    /** `symbol_search.dotnet_search_reference_assemblies` of the page of the server, off: the libraries are not searched. */
    fun testGoToClassAndSymbolObeyTheSearchReferenceAssembliesOption() {
        val option = RoslynOptions.option("symbol_search.dotnet_search_reference_assemblies")
        RoslynLanguageServerSettings.getInstance().setValue(option, "false")
        assertEmpty(names(AssemblyGotoClassContributor(), ProjectScope.getAllScope(project)))
        assertEmpty(items(AssemblyGotoClassContributor(), "List", everywhere = true))
        assertEmpty(names(AssemblyGotoSymbolContributor(), ProjectScope.getAllScope(project)))
        assertEmpty(items(AssemblyGotoSymbolContributor(), "WriteLine", everywhere = true))
        RoslynLanguageServerSettings.getInstance().setValue(option, "true")
        assertTrue("List" in names(AssemblyGotoClassContributor(), ProjectScope.getAllScope(project)))
    }

    /** `navigation.dotnet_navigate_to_decompiled_sources` off: the metadata view, and the decompiler is never asked. */
    fun testGoToDeclarationStaysOnTheMetadataViewWithDecompiledSourcesOff() {
        CSharpSemanticEnvironment.setAssembliesForTests { AssemblyIndexSet(listOf(FIXTURE)) }
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.NAVIGATION, CSharpFeatureSource.NATIVE)
        RoslynLanguageServerSettings.getInstance().setValue(RoslynOptions.option("navigation.dotnet_navigate_to_decompiled_sources"), "false")
        val decompiler = io.github.dotnetsupport.decompiler.AssemblyDecompiler.getInstance(project)
        val helper = decompiler.source
        val dll = java.io.File(com.intellij.openapi.util.io.FileUtil.createTempDirectory("gotoMetadata", null), "IndexFixture.dll").apply { writeText("not really an assembly") }
        val answer = io.github.dotnetsupport.decompiler.DecompilerAnswers.parse(com.google.gson.JsonParser.parseString(javaClass.classLoader.getResource("decompiler/circle.json")!!.readText()))!!
        val requests = java.util.concurrent.atomic.AtomicInteger()
        AssemblyIndexService.getInstance(project).setAssemblyFile(FIXTURE, dll)
        decompiler.source = object : io.github.dotnetsupport.decompiler.DecompilerSource {
            override fun decompile(request: io.github.dotnetsupport.decompiler.DecompileRequest) = answer.also { requests.incrementAndGet() }
            override fun types(assembly: String) = emptyList<io.github.dotnetsupport.decompiler.AssemblyTypeInfo>()
        }
        try {
            val file = myFixture.addFileToProject("GotoMetadata/Program.cs", "class Program { Fixture.Circle shape; }")
            val at = file.text.indexOf("Circle")
            repeat(2) {
                val target = AssemblyNavigation.declarationTargets(file.findElementAt(at)!!)!!.single()
                assertTrue("the metadata view", target.containingFile.virtualFile is AssemblyMetadataFile)
            }
            Thread.sleep(200)
            assertEquals("the decompiler is not asked", 0, requests.get())
        } finally {
            decompiler.source = helper
        }
    }

    fun testGoToDeclarationOfTheNativeTreeOpensTheMetadataView() {
        CSharpSemanticEnvironment.setAssembliesForTests { AssemblyIndexSet(listOf(FIXTURE, CONSOLE, COLLECTIONS, LINQ)) }
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.NAVIGATION, CSharpFeatureSource.NATIVE)
        val file = myFixture.addFileToProject("GotoLibApp/Program.cs", """
            using System.Collections.Generic;

            class Program
            {
                static void Main()
                {
                    System.Console.WriteLine("x");
                    var list = new List<int>();
                }
            }
        """.trimIndent())
        val text = file.text
        val writeLine = AssemblyNavigation.declarationTargets(file.findElementAt(text.indexOf("WriteLine"))!!)!!
        assertTrue(writeLine.all { it.containingFile.virtualFile is AssemblyMetadataFile })
        assertTrue(writeLine.map { it.text }.toString(), writeLine.all { it.text.contains("WriteLine(") })
        val list = AssemblyNavigation.declarationTargets(file.findElementAt(text.indexOf("List<int>"))!!)!!.single()
        assertEquals("List.cs", list.containingFile.name)
        assertTrue(list.textOffset.let { list.containingFile.text.startsWith("List<T>", it) })
        // the handler of the platform: the same targets
        val handled = CSharpGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(text.indexOf("List<int>")), text.indexOf("List<int>"), null)
        assertEquals(list, handled?.singleOrNull())
        assertNull("a declaration of the solution is not the view's", AssemblyNavigation.declarationTargets(file.findElementAt(text.indexOf("Main"))!!))
    }

    /** Ctrl+Click inside decompiled code goes on: its names resolve against a project compiled against its dll, else the largest one. */
    fun testADecompiledTypeResolvesAgainstAProjectThatRefersToItsAssembly() {
        val console = "C:/dotnet/packs/Microsoft.NETCore.App.Ref/10.0.0/ref/net10.0/System.Console.dll"
        fun decompiled(assembly: String) = io.github.dotnetsupport.decompiler.DecompiledFile(
            io.github.dotnetsupport.decompiler.DecompiledKey(assembly, "System.Console"),
            io.github.dotnetsupport.decompiler.DecompiledType(assembly, "System.Console", "10.0.0.0", 0, "System.Console", "public static class Console { }", emptyList()), 0)
        val own = AssemblyNavigation.assembliesOf(project, decompiled(console))!!
        assertTrue("the project of the dll", own.indexes.any { it === CONSOLE })
        val other = AssemblyNavigation.assembliesOf(project, decompiled("C:/elsewhere/System.Private.CoreLib.dll"))!!
        assertTrue("an implementation assembly no project refers to: the largest project", other.indexes.any { it === CONSOLE })
        assertNull("a file of the solution is no decompiled one", AssemblyNavigation.assembliesOf(project, app))
    }

    /** With the decompiler of DotNetHelper: the first Go to Declaration is the metadata view and decompiles behind it; the next one is the code. */
    fun testGoToDeclarationGoesToTheDecompiledCodeOnceItIsThere() {
        CSharpSemanticEnvironment.setAssembliesForTests { AssemblyIndexSet(listOf(FIXTURE)) }
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.NAVIGATION, CSharpFeatureSource.NATIVE)
        val decompiler = io.github.dotnetsupport.decompiler.AssemblyDecompiler.getInstance(project)
        val helper = decompiler.source
        val dll = java.io.File(com.intellij.openapi.util.io.FileUtil.createTempDirectory("gotoDecompiled", null), "IndexFixture.dll").apply { writeText("not really an assembly") }
        val answer = io.github.dotnetsupport.decompiler.DecompilerAnswers.parse(com.google.gson.JsonParser.parseString(javaClass.classLoader.getResource("decompiler/circle.json")!!.readText()))!!
        val requests = java.util.concurrent.atomic.AtomicInteger()
        AssemblyIndexService.getInstance(project).setAssemblyFile(FIXTURE, dll)
        decompiler.source = object : io.github.dotnetsupport.decompiler.DecompilerSource {
            override fun decompile(request: io.github.dotnetsupport.decompiler.DecompileRequest) = answer.also { requests.incrementAndGet() }
            override fun types(assembly: String) = emptyList<io.github.dotnetsupport.decompiler.AssemblyTypeInfo>()
        }
        try {
            val file = myFixture.addFileToProject("GotoDecompiled/Program.cs", "class Program { Fixture.Circle shape; }")
            val at = file.text.indexOf("Circle")
            val first = AssemblyNavigation.declarationTargets(file.findElementAt(at)!!)!!.single()
            assertTrue("the metadata view at once", first.containingFile.virtualFile is AssemblyMetadataFile)
            val deadline = System.currentTimeMillis() + 10_000
            while (decompiler.cachedFile(dll.path, "Fixture.Circle") == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals("decompiled behind it, once", 1, requests.get())
            val next = AssemblyNavigation.declarationTargets(file.findElementAt(at)!!)!!.single()
            assertTrue(next.containingFile.virtualFile is io.github.dotnetsupport.decompiler.DecompiledFile)
            assertTrue(next.containingFile.text.startsWith("Circle", next.textOffset))
            AssemblyNavigation.declarationTargets(file.findElementAt(at)!!)
            assertEquals("not again", 1, requests.get())
        } finally {
            decompiler.source = helper
        }
    }

    /**
     * ~600 indexes: the fixtures 150 times over as separate files (the debug-playground has 582 assemblies, 17 316 types, 177 886 members), or
     * the indexes of a folder named by `METADATA_INDEXES` (the indexer's `--out` of the playground's references: 582 indexes, 41 027 names in 110 ms the first time, 7 ms again; List, WriteLine, Where, Shape, ToString: 2 531 items in 28 ms).
     */
    fun testTheNamesOfSixHundredIndexesAreFast() {
        val folder = (System.getProperty("metadata.indexes") ?: System.getenv("METADATA_INDEXES"))?.let(::File)?.takeIf { it.isDirectory }
        val indexes = folder?.listFiles { it -> it.name.endsWith(".dnix") }?.map { AssemblyIndex.open(it.toPath()) }
            ?: List(150) { listOf("IndexFixture", "System.Console", "System.Linq", "System.Collections") }.flatten().map(::fixture)
        val service = AssemblyIndexService.getInstance(project)
        service.clearIndexes()
        service.set(app, indexes)
        val symbols = AssemblyGotoSymbolContributor()
        val all = ProjectScope.getAllScope(project)
        var started = System.nanoTime()
        val count = names(symbols, all).size
        val first = (System.nanoTime() - started) / 1_000_000
        started = System.nanoTime()
        names(symbols, all)
        val again = (System.nanoTime() - started) / 1_000_000
        started = System.nanoTime()
        val found = listOf("List", "WriteLine", "Where", "Shape", "ToString").sumOf { items(symbols, it, everywhere = true).size }
        val lookups = (System.nanoTime() - started) / 1_000_000
        println("Go to Symbol over ${indexes.size} indexes: $count names, first ${first} ms, again ${again} ms; 5 names -> $found items in ${lookups} ms")
        assertTrue("names first: $first ms", first < 5_000)
        assertTrue("names again: $again ms", again < 500)
        assertTrue("items: $lookups ms", lookups < 2_000)
    }

    private companion object {
        fun bytes(name: String): ByteArray = AssemblyGotoLibraryTest::class.java.getResourceAsStream("/index/$name")!!.use { it.readBytes() }
        fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix"), AssemblyDocs.read(bytes("$name.dnxd")))
        val FIXTURE by lazy { fixture("IndexFixture") }
        val CONSOLE by lazy { fixture("System.Console") }
        val COLLECTIONS by lazy { fixture("System.Collections") }
        val LINQ by lazy { fixture("System.Linq") }
    }
}
