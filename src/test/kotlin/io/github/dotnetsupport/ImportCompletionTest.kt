package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.index.ImportCompletion
import io.github.dotnetsupport.index.ImportItem
import io.github.dotnetsupport.index.IndexerTool
import io.github.dotnetsupport.index.ProjectAssemblies
import io.github.dotnetsupport.lang.CSharpUsings
import java.io.File
import java.nio.file.Files

/**
 * Static members of types that are not imported, from the index of the assemblies of the project: `WriteLi` -> `Console.WriteLine`
 * with its `using`. The indexes are the fixtures of [AssemblyIndexTest]; the indexer itself is not run in tests.
 */
class ImportCompletionTest : BasePlatformTestCase() {
    private fun fixture(name: String) = AssemblyIndex.read(javaClass.getResourceAsStream("/index/$name.dnix")!!.use { it.readBytes() })
    private val indexes by lazy { listOf(fixture("System.Console"), fixture("System.Linq")) }

    private fun items(prefix: String, visible: Set<String> = emptySet(), static: Set<String> = emptySet()): List<ImportItem> =
        ImportCompletion.items(indexes, prefix, { it in visible }, static)

    fun testRowsOfAPrefix() {
        val found = items("WriteLi")
        assertEquals(listOf("Console.WriteLine"), found.map { it.qualifiedName })
        val writeLine = found.single()
        assertTrue("the overloads are one row", writeLine.overloads.size >= 17)
        assertTrue(writeLine.returnsNothing)
        assertTrue(writeLine.takesArguments)
        assertFalse(writeLine.needsTypeArguments)
        assertEquals("the one without arguments first", "()", writeLine.first.signature)
        assertTrue(writeLine.tail, Regex("""^\(\)  \+\d+ overloads$""").matches(writeLine.tail))

        assertEquals("the case of the letters does not matter", listOf("Console.WriteLine"), items("writeli").map { it.qualifiedName })
        assertTrue("too short to mean anything", items("Wr").isEmpty())
        assertTrue(items("Zzzz").isEmpty())
        assertTrue("brought by `using static` already", items("WriteLi", static = setOf("System.Console")).isEmpty())
    }

    fun testWhatIsOffered() {
        assertEquals("a property", listOf("Console.Title"), items("Titl").map { it.qualifiedName })
        assertFalse(items("Titl").single().kind.isCallable)
        assertEquals("a static method of a static class", listOf("Enumerable.Range"), items("Rang").filter { it.name == "Range" }.map { it.qualifiedName })
        assertTrue("an extension method is for the dot, not for the bare name", items("Selec").none { it.name == "Select" })
        assertTrue("an enum member is not offered by the bare name", items("DarkR").isEmpty())
        val empty = items("Empt").single { it.name == "Empty" }
        assertEquals("Enumerable.Empty", empty.qualifiedName)
        assertTrue("nothing to infer the type argument from", empty.isGeneric && empty.needsTypeArguments)
        val repeat = items("Repe").single { it.name == "Repeat" }
        assertTrue(repeat.isGeneric)
        assertFalse("inferred from the argument", repeat.needsTypeArguments)
    }

    fun testWhereABareNameIs() {
        fun bare(text: String) = ImportCompletion.isBareName(text, text.length - text.takeLastWhile { it.isLetterOrDigit() }.length)
        assertTrue(bare("        WriteLi"))
        assertTrue(bare("        var x = Rang"))
        assertTrue(bare("        return Rang"))
        assertTrue(bare("        await Dela"))
        assertTrue(bare("        Foo(Rang"))
        assertTrue(bare("        if (ready) WriteLi"))
        assertFalse("after a dot", bare("        Console.WriteLi"))
        assertFalse(bare("        Console. WriteLi"))
        assertFalse("the name of a variable", bare("        string WriteLi"))
        assertFalse(bare("        List<int> Rang"))
        assertFalse(bare("    public void WriteLi"))
        assertFalse(bare("using Syst"))
        assertFalse(bare("namespace Shop"))
        // only a type may stand here: no static member of the index (`Task<str` offered `Conversion.Str`)
        assertFalse("a type argument", bare("    public async Task<str"))
        assertFalse(bare("        var map = new Dictionary<string, Str"))
        assertFalse(bare("        Func<int, List<Str"))
        assertFalse(bare("        var t = typeof(Str"))
        assertFalse(bare("        var d = default(Str"))
        assertFalse(bare("        var s = item as Str"))
        assertFalse("a base list", bare("public class Shop : Str"))
        assertFalse(bare("public class Shop : IDisposable, Str"))
        assertFalse("a constraint", bare("    where T : Str"))
        // and where an expression goes on as before
        assertTrue("a comparison", bare("        if (count < Rang"))
        assertTrue(bare("        var x = count < Rang"))
        assertTrue("a ternary", bare("        var x = ready ? 1 : Rang"))
        assertTrue("a named argument", bare("        Foo(count: Rang"))
        assertTrue("an argument after a generic call", bare("        Make<int>(Rang"))
        assertTrue(bare("        Foo(a, Rang"))
    }

    fun testUsingDirectives() {
        fun inserted(text: String, namespace: String): String? =
            CSharpUsings.insertion(text, namespace)?.let { text.substring(0, it.offset) + it.text + text.substring(it.offset) }

        assertEquals("using System;\n\nclass A { }\n", inserted("class A { }\n", "System"))
        assertEquals("// header\nusing System;\n\nnamespace Shop;\n", inserted("// header\nnamespace Shop;\n", "System"))
        assertEquals("using System;\nusing System.IO;\nusing Shop;\n\nclass A { }\n", inserted("using System;\nusing Shop;\n\nclass A { }\n", "System.IO"))
        assertEquals("System goes first", "using System;\nusing Shop;\n", inserted("using Shop;\n", "System"))
        assertEquals("by the alphabet", "using Alpha;\nusing Beta;\nusing Gamma;\n", inserted("using Alpha;\nusing Gamma;\n", "Beta"))
        assertEquals("after the last", "using System;\nusing Zeta;\n", inserted("using System;\n", "Zeta"))
        assertEquals("static and aliases stay where they are", "using System;\nusing System.IO;\nusing static System.Math;\nusing F = System.IO.File;\n",
            inserted("using System;\nusing static System.Math;\nusing F = System.IO.File;\n", "System.IO"))
        assertNull("it is there", CSharpUsings.insertion("using System;\n", "System"))

        assertEquals(setOf("System", "Shop.Models"), CSharpUsings.imported("using System;\nusing   Shop.Models ;\nusing static System.Math;\nusing F = System.IO.File;\n"))
        assertEquals(setOf("System.Math"), CSharpUsings.importedStatically("using System;\nusing static System.Math;\n"))
        assertTrue(CSharpUsings.isVisible("System", "using System;\n"))
        assertTrue("the namespace of the file itself", CSharpUsings.isVisible("Shop", "namespace Shop.Models;\n"))
        assertTrue(CSharpUsings.isVisible("Shop.Models", "namespace Shop.Models\n{\n}\n"))
        assertFalse(CSharpUsings.isVisible("Shop.Models.Inner", "namespace Shop.Models;\n"))
        assertFalse(CSharpUsings.isVisible("System.IO", "using System;\n"))
        assertTrue("implicit", CSharpUsings.isVisible("System.IO", "class A { }", CSharpUsings.implicit("Microsoft.NET.Sdk", true)))
        assertTrue(CSharpUsings.implicit("Microsoft.NET.Sdk", false).isEmpty())
        assertTrue("Microsoft.AspNetCore.Builder" in CSharpUsings.implicit("Microsoft.NET.Sdk.Web", true))
        assertFalse("Microsoft.AspNetCore.Builder" in CSharpUsings.implicit("Microsoft.NET.Sdk", true))
        assertEquals(setOf("System", "System.Linq", "Shop"), CSharpUsings.global("// <auto-generated/>\nglobal using global::System;\nglobal using global::System.Linq;\nglobal using Shop;\n"))
    }

    private fun complete(before: String, choose: String, csproj: String = """<Project Sdk="Microsoft.NET.Sdk"/>"""): String {
        val name = "imp" + (counter++)
        val projectFile = myFixture.addFileToProject("$name/App/App.csproj", csproj).virtualFile
        AssemblyIndexService.getInstance(project).set(projectFile, indexes)
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("$name/App/Program.cs", before).virtualFile)
        val elements = myFixture.completeBasic()
        if (elements != null) {
            val element = elements.firstOrNull { it.lookupString == choose && it.`object` is ImportItem } ?: error("no $choose among ${elements.map { it.lookupString }}")
            myFixture.lookup.currentItem = element
            myFixture.finishLookup(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR)
        }
        val text = myFixture.editor.document.text
        val caret = myFixture.editor.caretModel.offset
        return text.substring(0, caret) + "|" + text.substring(caret)
    }

    fun testChosenMethodIsWrittenWhole() {
        assertEquals("the type, the parentheses, the semicolon of a statement and the using",
            "using System;\n\nclass A\n{\n    void M()\n    {\n        Console.WriteLine(|);\n    }\n}\n",
            complete("class A\n{\n    void M()\n    {\n        WriteLi<caret>\n    }\n}\n", "WriteLine"))
    }

    fun testTheCaseOfTheLettersDoesNotMatter() {
        val expected = "using System;\n\nclass A\n{\n    void M()\n    {\n        Console.WriteLine(|);\n    }\n}\n"
        assertEquals("all small", expected, complete("class A\n{\n    void M()\n    {\n        writeli<caret>\n    }\n}\n", "WriteLine"))
        assertEquals("all capital", expected, complete("class A\n{\n    void M()\n    {\n        WRITELI<caret>\n    }\n}\n", "WriteLine"))
    }

    fun testNoUsingWhereTheNamespaceIsSeen() {
        assertEquals("imported by the file",
            "using System;\n\nclass A\n{\n    void M()\n    {\n        Console.WriteLine(|);\n    }\n}\n",
            complete("using System;\n\nclass A\n{\n    void M()\n    {\n        WriteLi<caret>\n    }\n}\n", "WriteLine"))
        assertEquals("imported by the SDK",
            "class A\n{\n    void M()\n    {\n        Console.WriteLine(|);\n    }\n}\n",
            complete("class A\n{\n    void M()\n    {\n        WriteLi<caret>\n    }\n}\n", "WriteLine",
                """<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><ImplicitUsings>enable</ImplicitUsings></PropertyGroup></Project>"""))
    }

    fun testValuesAndGenerics() {
        assertEquals("a value: the statement ends after the call",
            "using System.Linq;\n\nclass A\n{\n    void M()\n    {\n        var numbers = Enumerable.Range(|);\n    }\n}\n",
            complete("class A\n{\n    void M()\n    {\n        var numbers = Rang<caret>\n    }\n}\n", "Range"))
        assertEquals("the type argument is written first",
            "using System.Linq;\n\nclass A\n{\n    void M()\n    {\n        var none = Enumerable.Empty<|>();\n    }\n}\n",
            complete("class A\n{\n    void M()\n    {\n        var none = Empt<caret>\n    }\n}\n", "Empty"))
        assertEquals("a property has no parentheses",
            "using System;\n\nclass A\n{\n    void M()\n    {\n        var title = Console.Title|\n    }\n}\n",
            complete("class A\n{\n    void M()\n    {\n        var title = Titl<caret>\n    }\n}\n", "Title"))
        assertEquals("an argument: something follows, no semicolon",
            "using System;\n\nclass A\n{\n    void M()\n    {\n        Foo(Console.ReadLine()|);\n    }\n}\n",
            complete("class A\n{\n    void M()\n    {\n        Foo(ReadLin<caret>);\n    }\n}\n", "ReadLine"))
    }

    fun testTheIndexerIsCarriedAsItsSource() {
        val sources = IndexerTool.Sources.read()
        assertNotNull("indexer/Program.cs and the project file are packed by the build", sources)
        assertEquals(setOf("Program.cs", "AssemblyIndexer.csproj"), sources!!.files.keys)
        assertTrue(sources.files.getValue("AssemblyIndexer.csproj").contains("\$(IndexerFramework)"))
        assertTrue("the format of the writer is the one the reader reads", sources.files.getValue("Program.cs").contains("FormatVersion = ${AssemblyIndex.FORMAT_VERSION};"))
        assertEquals(12, sources.hash.length)
        assertEquals("the line ends of a checkout do not make another source", IndexerTool.Sources.hash(listOf("a\nb\n")), IndexerTool.Sources.hash(listOf("a\r\nb\r\n")))
        assertFalse(IndexerTool.Sources.hash(listOf("a")) == IndexerTool.Sources.hash(listOf("b")))

        assertEquals("net10.0", IndexerTool.framework("10.0.401"))
        assertEquals("net9.0", IndexerTool.framework("9.0.301\n"))
        assertEquals("net8.0", IndexerTool.framework("8.0.100-preview.1"))
        assertNull("too old to build the indexer", IndexerTool.framework("7.0.410"))
        assertNull(IndexerTool.framework(""))

        val output = """
            {"path":"C:/packs/System.Console.dll","mvid":"0610","index":"C:/cache/0610.dnix","types":8,"members":257,"bytes":12568,"ms":5}
            {"path":"C:/packs/System.Linq.dll","mvid":"bb44","index":"C:/cache/bb44.dnix","skipped":true,"ms":1}
            {"path":"C:/packs/native.dll","error":"BadImageFormatException: no metadata","ms":1}
            {"summary":true,"format":1,"indexed":1,"skipped":1,"failed":1}
        """.trimIndent()
        assertEquals(mapOf(File("C:/packs/System.Console.dll") to File("C:/cache/0610.dnix"), File("C:/packs/System.Linq.dll") to File("C:/cache/bb44.dnix")), IndexerTool.parse(output))
    }

    fun testAssembliesOfAProject() {
        val root = Files.createTempDirectory("assemblies").toFile()
        try {
            val packages = File(root, "packages").apply { mkdirs() }
            fun file(path: String) = File(root, path).apply { parentFile.mkdirs(); writeText("") }
            file("packages/newtonsoft.json/13.0.3/lib/net6.0/Newtonsoft.Json.dll")
            file("packages/xunit.assert/2.9.0/lib/net6.0/xunit.assert.dll")
            file("dotnet/packs/Microsoft.NETCore.App.Ref/9.0.6/ref/net9.0/System.Console.dll")
            file("dotnet/packs/Microsoft.NETCore.App.Ref/9.0.11/ref/net9.0/System.Console.dll")
            file("dotnet/packs/Microsoft.NETCore.App.Ref/9.0.11/ref/net9.0/System.Runtime.dll")
            file("dotnet/packs/Microsoft.NETCore.App.Ref/9.0.11/ref/net9.0/System.Runtime.xml")
            file("dotnet/packs/Microsoft.NETCore.App.Ref/10.0.12/ref/net10.0/System.Console.dll")
            file("Lib/obj/Debug/net9.0/ref/Lib.dll")
            file("Lib/bin/Debug/net9.0/Lib.dll")
            val folder = packages.path.replace("\\", "\\\\")
            val assets = """
                {
                  "targets": {
                    "net9.0": {
                      "Newtonsoft.Json/13.0.3": { "type": "package", "compile": { "lib/net6.0/Newtonsoft.Json.dll": {} } },
                      "xunit.assert/2.9.0": { "type": "package", "compile": { "lib/net6.0/xunit.assert.dll": {} } },
                      "Microsoft.NET.Test.Sdk/17.12.0": { "type": "package", "compile": { "lib/netcoreapp3.1/_._": {} } },
                      "Private.Package/1.0.0": { "type": "package", "runtime": { "lib/net6.0/Private.dll": {} } },
                      "Lib/1.0.0": { "type": "project", "compile": { "bin/placeholder/Lib.dll": {} } }
                    },
                    "net9.0/win-x64": { }
                  },
                  "libraries": {
                    "Newtonsoft.Json/13.0.3": { "type": "package", "path": "newtonsoft.json/13.0.3" },
                    "xunit.assert/2.9.0": { "type": "package", "path": "xunit.assert/2.9.0" },
                    "Lib/1.0.0": { "type": "project", "path": "../Lib/Lib.csproj" }
                  },
                  "packageFolders": { "$folder": {} },
                  "project": { "frameworks": { "net9.0": { "frameworkReferences": { "Microsoft.NETCore.App": { "privateAssets": "all" } } } } }
                }
            """.trimIndent()
            val found = ProjectAssemblies.of(ProjectAssemblies.Request(assets, File(root, "App"), File(root, "dotnet")))
            val names = found.map { it.path.removePrefix(root.path).replace('\\', '/') }
            assertEquals(listOf(
                "/packages/newtonsoft.json/13.0.3/lib/net6.0/Newtonsoft.Json.dll",
                "/packages/xunit.assert/2.9.0/lib/net6.0/xunit.assert.dll",
                "/dotnet/packs/Microsoft.NETCore.App.Ref/9.0.11/ref/net9.0/System.Console.dll",
                "/dotnet/packs/Microsoft.NETCore.App.Ref/9.0.11/ref/net9.0/System.Runtime.dll",
                // the projects it refers to last: their built assemblies stand for their sources
                "/Lib/obj/Debug/net9.0/ref/Lib.dll",
            ), names)
            assertTrue("a framework the project does not have: its first one", ProjectAssemblies.of(ProjectAssemblies.Request(assets, File(root, "App"), File(root, "dotnet"), "net7.0")).isNotEmpty())
            assertTrue("no dotnet: the packages still", ProjectAssemblies.of(ProjectAssemblies.Request(assets, File(root, "App"), null)).size == 3)
            assertTrue(ProjectAssemblies.of(ProjectAssemblies.Request("not json", root, null)).isEmpty())
            assertTrue(ProjectAssemblies.referencePack(File(root, "dotnet"), "Microsoft.AspNetCore.App", "net9.0").isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        var counter = 0
    }
}
