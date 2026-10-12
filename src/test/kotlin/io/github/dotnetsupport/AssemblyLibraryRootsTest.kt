package io.github.dotnetsupport

import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.ExternalLibrariesNode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.impl.cache.impl.id.IdIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.index.AssemblyLibrary
import io.github.dotnetsupport.index.AssemblyLibraryRootsProvider
import io.github.dotnetsupport.index.ProjectAssemblies
import io.github.dotnetsupport.index.ProjectAssemblies.Library
import io.github.dotnetsupport.index.ProjectAssemblies.LibraryKind
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The referenced assemblies as libraries of the IDE ([AssemblyLibraryRootsProvider]): what the provider gives for the references
 * of the projects, External Libraries, the scopes, the change on another restore, and that the content of a dll is not indexed.
 */
class AssemblyLibraryRootsTest : BasePlatformTestCase() {
    private lateinit var root: File

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("libraryRoots").toFile()
        VfsRootAccess.allowRootAccess(testRootDisposable, root.path)
    }

    override fun tearDown() {
        try {
            // the light project is shared: no libraries are left to the next test
            publish(emptyMap())
            root.deleteRecursively()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun dll(path: String): File = File(root, path).apply { parentFile.mkdirs(); writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 0, 0, 0x50, 0x45, 0, 0)) }

    private fun publish(found: Map<VirtualFile, ProjectAssemblies.References>) {
        val service = AssemblyIndexService.getInstance(project)
        service.setReferences(found)
        ApplicationManager.getApplication().executeOnPooledThread { service.publishLibraries() }.get(30, TimeUnit.SECONDS)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun references(vararg libraries: Library) =
        ProjectAssemblies.References("net10.0", libraries.flatMap { it.assemblies }, emptyList(), emptyList(), libraries.toList())

    private fun virtual(file: File): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!

    private fun provided(): List<AssemblyLibrary> =
        AdditionalLibraryRootsProvider.EP_NAME.findExtensionOrFail(AssemblyLibraryRootsProvider::class.java).getAdditionalProjectLibraries(project).map { it as AssemblyLibrary }

    fun testTheReferencesAreLibrariesOutsideTheProjectScope() {
        val json = dll("packages/newtonsoft.json/13.0.3/lib/net6.0/Newtonsoft.Json.dll")
        File(json.parentFile, "Newtonsoft.Json.xml").writeText("<doc><members/></doc>")
        val runtime = dll("dotnet/packs/Microsoft.NETCore.App.Ref/10.0.12/ref/net10.0/System.Runtime.dll")
        val collections = dll("dotnet/packs/Microsoft.NETCore.App.Ref/10.0.12/ref/net10.0/System.Collections.dll")
        val app = myFixture.addFileToProject("App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />").virtualFile
        val lib = myFixture.addFileToProject("Lib/Lib.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />").virtualFile
        val pack = Library("Microsoft.NETCore.App.Ref", "10.0.12", LibraryKind.FRAMEWORK, listOf(runtime, collections))
        val newtonsoft = Library("Newtonsoft.Json", "13.0.3", LibraryKind.PACKAGE, listOf(json))
        publish(mapOf(app to references(pack, newtonsoft), lib to references(pack)))

        val libraries = provided()
        assertEquals("one per package, the projects' together", listOf("Microsoft.NETCore.App.Ref 10.0.12", "Newtonsoft.Json 13.0.3"), libraries.map { it.presentableText })
        assertEquals(DotNetIcons.NuGet, libraries[1].getIcon(false))
        assertEquals(DotNetIcons.Assembly, libraries[0].getIcon(false))
        assertEquals("the dlls are the roots", listOf("System.Runtime.dll", "System.Collections.dll"), libraries[0].roots.map { it.name })

        val fileIndex = ProjectFileIndex.getInstance(project)
        val dll = virtual(json)
        assertTrue(fileIndex.isInLibrary(dll))
        assertTrue(fileIndex.isInLibraryClasses(dll))
        assertTrue(GlobalSearchScope.allScope(project).contains(dll))
        assertFalse(GlobalSearchScope.projectScope(project).contains(dll))
        val xml = virtual(File(json.parentFile, "Newtonsoft.Json.xml"))
        assertFalse("the XML docs next to a dll are not a part of it: not indexed", fileIndex.isInLibrary(xml))

        // nothing reads the content of a dll: it is binary, the id index (the one every text gets) has nothing of it
        assertTrue(dll.fileType.isBinary)
        assertTrue(FileBasedIndex.getInstance().getFileData(IdIndex.NAME, dll, project).isEmpty())

        val node = ExternalLibrariesNode(project, ViewSettings.DEFAULT)
        val shown = node.children.filter { it.value is AssemblyLibrary }
        assertEquals("in Project view → External Libraries", listOf("Microsoft.NETCore.App.Ref 10.0.12", "Newtonsoft.Json 13.0.3"), shown.map { (it.value as AssemblyLibrary).presentableText })
        assertEquals(listOf("Newtonsoft.Json.dll"), shown[1].children.map { (it.value as? com.intellij.psi.PsiFileSystemItem)?.name })
    }

    fun testAnotherRestoreChangesTheLibraries() {
        val old = dll("packages/serilog/3.1.1/lib/net8.0/Serilog.dll")
        val new = dll("packages/serilog/4.0.0/lib/net8.0/Serilog.dll")
        val app = myFixture.addFileToProject("Restored/Restored.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />").virtualFile
        publish(mapOf(app to references(Library("Serilog", "3.1.1", LibraryKind.PACKAGE, listOf(old)))))
        assertEquals(listOf("Serilog 3.1.1"), provided().map { it.presentableText })
        val fileIndex = ProjectFileIndex.getInstance(project)
        assertTrue(fileIndex.isInLibrary(virtual(old)))

        publish(mapOf(app to references(Library("Serilog", "4.0.0", LibraryKind.PACKAGE, listOf(new)))))
        assertEquals(listOf("Serilog 4.0.0"), provided().map { it.presentableText })
        assertFalse("the old version is no library any more", fileIndex.isInLibrary(virtual(old)))
        assertTrue(fileIndex.isInLibrary(virtual(new)))

        publish(emptyMap())
        assertTrue(provided().isEmpty())
        assertFalse(fileIndex.isInLibrary(virtual(new)))
    }
}
