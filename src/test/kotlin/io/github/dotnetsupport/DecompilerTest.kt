package io.github.dotnetsupport

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.decompiler.AssemblyDecompiler
import io.github.dotnetsupport.decompiler.AssemblyTypeInfo
import io.github.dotnetsupport.decompiler.DecompileRequest
import io.github.dotnetsupport.decompiler.DecompiledBanner
import io.github.dotnetsupport.decompiler.DecompiledFile
import io.github.dotnetsupport.decompiler.DecompiledFileSystem
import io.github.dotnetsupport.decompiler.DecompiledFiles
import io.github.dotnetsupport.decompiler.DecompiledKey
import io.github.dotnetsupport.decompiler.DecompiledTabTitle
import io.github.dotnetsupport.decompiler.DecompiledType
import io.github.dotnetsupport.decompiler.DecompilerAnswers
import io.github.dotnetsupport.decompiler.DecompilerHelperSource
import io.github.dotnetsupport.decompiler.DecompilerSource
import io.github.dotnetsupport.decompiler.DependencyAssemblies
import io.github.dotnetsupport.decompiler.ImplementationAssemblies
import io.github.dotnetsupport.index.ProjectAssemblies
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lang.CSharpLanguage
import io.github.dotnetsupport.view.DependencyKey
import io.github.dotnetsupport.view.DependencyKind
import java.io.File

/**
 * The decompiler on DotNetHelper: answers of a real run on the fixture assembly of the index (`tools/index-fixture`), recorded by
 * `tools/decompiler/record.py` into `src/test/resources/decompiler` with the path made neutral; the helper itself is never started.
 * Then the file of a decompiled type (C#, read-only, its own file system, the banner and the title), the caret at a member, the cache.
 */
class DecompilerTest : BasePlatformTestCase() {
    private lateinit var cache: File
    private lateinit var work: File

    override fun setUp() {
        super.setUp()
        cache = FileUtil.createTempDirectory("decompiled", null)
        work = FileUtil.createTempDirectory("assemblies", null)
        DecompiledFiles.diskRoot = cache
        DecompiledFiles.clearMemory()
    }

    override fun tearDown() {
        try {
            FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
            AssemblyDecompiler.getInstance(project).source = DecompilerHelperSource()
            DecompiledFiles.clearMemory()
            DecompiledFiles.diskRoot = null
        } finally {
            super.tearDown()
        }
    }

    private fun json(name: String): JsonElement =
        JsonParser.parseString(DecompilerTest::class.java.getResourceAsStream("/decompiler/$name.json")!!.use { it.readBytes().toString(Charsets.UTF_8) })

    private fun saved(name: String): DecompiledType = DecompilerAnswers.parse(json(name))!!

    private fun DecompiledType.at(offset: Int?, length: Int): String = text.substring(offset!!, offset + length)

    // ------------------------------------------------------------------------------------------------ answers of the helper

    fun testTheAnswerOfTheHelper() {
        val circle = saved("circle")
        assertEquals("""C:\work\fixture\IndexFixture.dll""", circle.assembly)
        assertEquals("IndexFixture", circle.assemblyName)
        assertEquals("1.0.0.0", circle.assemblyVersion)
        assertEquals("Fixture.Circle", circle.typeName)
        assertNull(circle.warning)
        assertTrue(circle.text, circle.text.startsWith("// Decompiled with ICSharpCode.Decompiler 11.1"))
        assertTrue(circle.text.contains("// Assembly location: C:\\work\\fixture\\IndexFixture.dll\n"))
        assertTrue(circle.text.contains("public sealed class Circle : Shape\n{"))
        // the XML documentation next to the dll is above its member
        assertTrue(circle.text.contains("    /// <summary>A part of the circle.</summary>\n    public int this[int index, string? key = null] => index;"))
        assertEquals(listOf("T:Fixture.Circle", "P:Fixture.Circle.Radius", "M:Fixture.Circle.get_Radius"), circle.members.take(3).map { it.id })
        // every offset is on the line the helper names
        for (member in circle.members) assertEquals(member.id, member.line, circle.text.substring(0, member.offset).count { it == '\n' })
    }

    fun testTheCaretGoesToTheNameOfTheMember() {
        val circle = saved("circle")
        assertEquals("Radius {", circle.at(circle.offsetOf("P:Fixture.Circle.Radius"), 8))
        assertEquals("Circle(double", circle.at(circle.offsetOf("M:Fixture.Circle.#ctor(System.Double)"), 13))
        assertEquals("this[int", circle.at(circle.offsetOf("P:Fixture.Circle.Item(System.Int32,System.String)"), 8))
        // an overload the text has not: the member of that name; nothing asked or an unknown member: the type
        assertEquals("Describe(", circle.at(circle.offsetOf("M:Fixture.Circle.Describe(System.Int64)"), 9))
        assertEquals("Circle : Shape", circle.at(circle.offsetOf(null), 14))
        assertEquals("Circle : Shape", circle.at(circle.offsetOf("M:Fixture.Circle.Nope"), 14))

        val shape = saved("shape")
        assertEquals("Kind = ", shape.at(shape.offsetOf("F:Fixture.Shape.Kind"), 7))
        assertEquals("Changed;", shape.at(shape.offsetOf("E:Fixture.Shape.Changed"), 8))
        assertEquals("operator +", shape.at(shape.offsetOf("M:Fixture.Shape.op_Addition(Fixture.Shape,Fixture.Shape)"), 10))
    }

    fun testANestedTypeIsShownInTheTypeThatHoldsIt() {
        val inner = saved("inner")
        assertEquals("Fixture.Box`1+Inner`1", inner.typeName)
        assertEquals("T:Fixture.Box`1.Inner`1", inner.typeId)
        assertTrue(inner.text.contains("public class Box<T>"))
        assertEquals("Inner<U> where U : struct", inner.at(inner.offsetOf(null), 25))
    }

    fun testTheTypesOfAnAssembly() {
        val types = DecompilerAnswers.parseTypes(json("types"))
        val inner = types.single { it.name == "Fixture.Box`1+Inner`1" }
        assertEquals("Box.Inner", inner.displayName)
        assertEquals("Fixture", inner.namespace)
        assertEquals("enum", types.single { it.name == "Fixture.Color" }.kind)
        assertEquals(listOf("Fixture.NotSeen", "Fixture.Outer+Internal", "Fixture.Outer+Private"), types.filter { !it.isPublic }.map { it.name })
    }

    fun testTheRequest() {
        val params = DecompilerAnswers.params(DecompileRequest("C:\\a\\X.dll", "N.T`1", "M:N.T`1.M", "C:\\ref\\X.xml", listOf("C:\\ref"), "12.0"))
        assertEquals("""{"assembly":"C:\\a\\X.dll","typeName":"N.T`1","memberId":"M:N.T`1.M","xmlDoc":"C:\\ref\\X.xml","referenceDirs":["C:\\ref"],"languageVersion":"12.0"}""",
            params.toString())
        assertEquals("""{"assembly":"X.dll","typeName":"T"}""", DecompilerAnswers.params(DecompileRequest("X.dll", "T")).toString())
        // what the cache keeps reads back the same
        val circle = saved("circle")
        assertEquals(circle, DecompilerAnswers.parse(DecompilerAnswers.toJson(circle)))
        assertNull(DecompilerAnswers.parse(JsonParser.parseString("""{"error":"x"}""")))
    }

    // ------------------------------------------------------------------------------------------------ the implementation of a reference assembly

    fun testAReferenceAssemblyOfTheSdkIsDecompiledFromTheSharedFramework() {
        val ref = file("dotnet/packs/Microsoft.NETCore.App.Ref/10.0.12/ref/net10.0/System.Console.dll")
        val xml = file("dotnet/packs/Microsoft.NETCore.App.Ref/10.0.12/ref/net10.0/System.Console.xml")
        val shared = file("dotnet/shared/Microsoft.NETCore.App/10.0.12/System.Console.dll")
        assertEquals(ImplementationAssemblies.Target(shared, xml), ImplementationAssemblies.forReference(ref, null, null))
        // another patch of the runtime than of the pack: the newest of the same major.minor
        val older = file("dotnet/packs/Microsoft.NETCore.App.Ref/9.0.5/ref/net9.0/System.Console.dll")
        file("dotnet/shared/Microsoft.NETCore.App/9.0.3/System.Console.dll")
        val newest = file("dotnet/shared/Microsoft.NETCore.App/9.0.11/System.Console.dll")
        assertEquals(ImplementationAssemblies.Target(newest, null), ImplementationAssemblies.forReference(older, null, null))
        // a package with ref/ and lib/; an assembly that is the implementation already stays
        val packageRef = file("nuget/some.lib/1.0.0/ref/net8.0/Some.Lib.dll")
        val packageLib = file("nuget/some.lib/1.0.0/lib/net8.0/Some.Lib.dll")
        assertEquals(packageLib, ImplementationAssemblies.forReference(packageRef, null, null).assembly)
        assertEquals(ImplementationAssemblies.Target(packageLib, null), ImplementationAssemblies.forReference(packageLib, null, null))
        // .NET Framework: the Framework folder of Windows
        val netFx = file("ra/.NETFramework/v4.7.2/System.Xml.dll")
        val windows = file("windows/Microsoft.NET/Framework64/v4.0.30319/System.Xml.dll")
        assertEquals(windows, ImplementationAssemblies.forReference(netFx, null, File(work, "windows")).assembly)
    }

    private fun file(relative: String): File = File(work, relative).apply { parentFile.mkdirs(); writeText("x") }

    fun testTheAssembliesOfADependencyNode() {
        val projectFile = myFixture.addFileToProject("Deps/Deps.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />").virtualFile
        val json = File(work, "pkg/Newtonsoft.Json.dll")
        val runtime = File(work, "pack/System.Runtime.dll")
        val stj = File(work, "pack/System.Text.Json.dll")
        val vendor = File(work, "lib/Vendor.dll")
        val references = ProjectAssemblies.References("net10.0", listOf(json, runtime, stj, vendor), emptyList(), emptyList(), listOf(
            ProjectAssemblies.Library("Newtonsoft.Json", "13.0.3", ProjectAssemblies.LibraryKind.PACKAGE, listOf(json)),
            ProjectAssemblies.Library("Microsoft.NETCore.App.Ref", "10.0.12", ProjectAssemblies.LibraryKind.FRAMEWORK, listOf(runtime, stj)),
            ProjectAssemblies.Library("Vendor", null, ProjectAssemblies.LibraryKind.ASSEMBLY, listOf(vendor)),
        ))
        fun select(kind: DependencyKind, name: String, vararg parents: String) =
            DependencyAssemblies.select(references, DependencyKey(projectFile, kind, name, "net10.0", parents.toList()))
        assertEquals(listOf(json), select(DependencyKind.PACKAGES, "newtonsoft.json"))
        assertEquals(listOf(stj), select(DependencyKind.FRAMEWORKS, "System.Text.Json", "Microsoft.NETCore.App"))
        assertEquals(listOf(vendor), select(DependencyKind.ASSEMBLIES, "Vendor, Version=1.0.0.0"))
        assertTrue(DependencyAssemblies.isDecompilable(DependencyKey(projectFile, DependencyKind.FRAMEWORKS, "System.Text.Json", "net10.0", listOf("Microsoft.NETCore.App"))))
        assertFalse(DependencyAssemblies.isDecompilable(DependencyKey(projectFile, DependencyKind.FRAMEWORKS, "Microsoft.NETCore.App", "net10.0")))
        assertFalse(DependencyAssemblies.isDecompilable(DependencyKey(projectFile, DependencyKind.PROJECTS, "../Lib/Lib.csproj")))
    }

    // ------------------------------------------------------------------------------------------------ the file in the editor

    private class FakeSource(val answer: DecompiledType) : DecompilerSource {
        val requests = ArrayList<DecompileRequest>()
        override fun decompile(request: DecompileRequest): DecompiledType = answer.also { requests += request }
        override fun types(assembly: String): List<AssemblyTypeInfo> = emptyList()
    }

    private fun fixtureAssembly(): File = File(work, "IndexFixture.dll").apply { writeText("not really an assembly") }

    fun testADecompiledTypeIsAReadOnlyCSharpFileOfItsOwnFileSystem() {
        val source = FakeSource(saved("circle"))
        AssemblyDecompiler.getInstance(project).source = source
        val assembly = fixtureAssembly()
        val file = AssemblyDecompiler.getInstance(project).decompiledFile(assembly.path, "Fixture.Circle")

        assertEquals("Circle.cs", file.name)
        assertEquals(CSharpFileType, file.fileType)
        assertFalse(file.isWritable)
        assertEquals(FileUtil.toSystemIndependentName(assembly.path) + "!/Fixture.Circle/Circle.cs", file.path)
        assertEquals("dotnet-decompiled://" + file.path, file.url)
        assertSame(file, VirtualFileManager.getInstance().findFileByUrl(file.url))
        assertSame(DecompiledFileSystem.getInstance(), file.fileSystem)
        assertFalse(FileDocumentManager.getInstance().getDocument(file)!!.isWritable)
        assertEquals(CSharpLanguage, PsiManager.getInstance(project).findFile(file)!!.language)
        assertEquals("Circle.cs [IndexFixture]", DecompiledTabTitle().getEditorTabTitle(project, file))
        assertEquals("Decompiled from IndexFixture 1.0.0.0. Read-only", DecompiledBanner.bannerText(file.decompiled))
        assertNotNull(DecompiledBanner().collectNotificationData(project, file))
        assertNull(DecompiledBanner().collectNotificationData(project, myFixture.addFileToProject("Plain.cs", "class Plain {}").virtualFile))

        // the helper is given the assembly, the type and the folder of the assembly to resolve references in
        val request = source.requests.single()
        assertEquals(assembly.path, request.assembly)
        assertEquals("Fixture.Circle", request.typeName)
        assertEquals(listOf(work.path), request.referenceDirs)
    }

    fun testTheCaretIsAtTheMember() {
        AssemblyDecompiler.getInstance(project).source = FakeSource(saved("circle"))
        val decompiler = AssemblyDecompiler.getInstance(project)
        val assembly = fixtureAssembly()
        val file = decompiler.decompiledFile(assembly.path, "Fixture.Circle")
        decompiler.navigate(file, "M:Fixture.Circle.Describe(System.String,System.Int32)")
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(file, FileDocumentManager.getInstance().getFile(editor.document))
        assertEquals("Describe(", editor.document.text.substring(editor.caretModel.offset, editor.caretModel.offset + 9))
        // opened again for the type: from the cache at once, the same tab, the caret on the name of the type
        decompiler.open(assembly.path, "Fixture.Circle")
        assertEquals(1, FileEditorManager.getInstance(project).openFiles.size)
        assertEquals("Circle : Shape", editor.document.text.substring(editor.caretModel.offset, editor.caretModel.offset + 14))
    }

    fun testTheCacheIsByAssemblyItsTimeAndTheType() {
        val source = FakeSource(saved("circle"))
        val decompiler = AssemblyDecompiler.getInstance(project)
        decompiler.source = source
        val assembly = fixtureAssembly()
        val first = decompiler.decompiledFile(assembly.path, "Fixture.Circle")
        assertSame(first, decompiler.decompiledFile(assembly.path, "Fixture.Circle"))
        assertSame(first, decompiler.cachedFile(assembly.path, "Fixture.Circle"))
        assertEquals(1, source.requests.size)
        assertNull(decompiler.cachedFile(assembly.path, "Fixture.Shape"))

        // a new session: from the disk, without the helper; the URL of a tab reopened with the project finds it
        DecompiledFiles.clearMemory()
        val restored = VirtualFileManager.getInstance().findFileByUrl(first.url) as DecompiledFile
        assertEquals(first.decompiled, restored.decompiled)
        assertEquals(1, source.requests.size)

        // the assembly is rebuilt: decompiled again, and the old URL leads nowhere until then
        assertTrue(assembly.setLastModified(assembly.lastModified() + 10_000))
        assertNull(VirtualFileManager.getInstance().findFileByUrl(first.url))
        decompiler.decompiledFile(assembly.path, "Fixture.Circle")
        assertEquals(2, source.requests.size)
    }

    fun testAFailureOfTheHelperIsAnError() {
        AssemblyDecompiler.getInstance(project).source = object : DecompilerSource {
            override fun decompile(request: DecompileRequest): DecompiledType = throw HelperException("IndexFixture.dll has no type Fixture.Nope")
            override fun types(assembly: String): List<AssemblyTypeInfo> = emptyList()
        }
        assertThrows(HelperException::class.java, "IndexFixture.dll has no type Fixture.Nope") {
            AssemblyDecompiler.getInstance(project).decompiledFile(fixtureAssembly().path, "Fixture.Nope")
        }
        assertThrows(HelperException::class.java) { AssemblyDecompiler.getInstance(project).decompiledFile(File(work, "missing.dll").path, "T") }
    }

    fun testTheKeyAndThePath() {
        val key = DecompiledKey("C:/x/System.Console.dll", "System.Console")
        assertEquals("C:/x/System.Console.dll!/System.Console/Console.cs", key.path)
        assertEquals(key, DecompiledKey.parse(key.path))
        val versioned = DecompiledKey("/usr/share/dotnet/X.dll", "N.Box`1+Inner`1", "12.0")
        assertEquals("/usr/share/dotnet/X.dll!/12.0/N.Box`1+Inner`1/Inner.cs", versioned.path)
        assertEquals(versioned, DecompiledKey.parse(versioned.path))
        assertNull(DecompiledKey.parse("C:/x/System.Console.dll"))
        assertNull(DecompiledKey.parse("C:/x/System.Console.dll!/System.Console/Other.cs"))
    }

    fun testTheActionIsInTheContextMenuOfTheSolutionView() {
        val popup = ActionManager.getInstance().getAction("DotNet.SolutionViewPopup") as DefaultActionGroup
        assertTrue(popup.getChildActionsOrStubs().any { ActionManager.getInstance().getId(it) == "DotNet.DecompileDependency" })
    }
}
