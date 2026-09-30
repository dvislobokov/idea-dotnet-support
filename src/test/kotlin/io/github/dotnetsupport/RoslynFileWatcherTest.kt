package io.github.dotnetsupport

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.roslyn.RoslynWatchedFiles
import org.eclipse.lsp4j.FileChangeType

/** The VFS events that become `workspace/didChangeWatchedFiles` for the server. */
class RoslynFileWatcherTest : BasePlatformTestCase() {
    fun testWhatIsWatched() {
        val root = "C:/work/Shop"
        assertTrue(RoslynWatchedFiles.isWatched("C:/work/Shop/App/Order.cs", root))
        assertTrue("the drive letter in either case", RoslynWatchedFiles.isWatched("c:/work/Shop/App/Order.cs", root))
        assertTrue(RoslynWatchedFiles.isWatched("C:/work/Shop/App/App.csproj", root))
        assertTrue(RoslynWatchedFiles.isWatched("C:/work/Shop/Directory.Build.props", root))
        assertTrue(RoslynWatchedFiles.isWatched("C:/work/Shop/Directory.Packages.props", root))
        assertTrue(RoslynWatchedFiles.isWatched("C:/work/Shop/.editorconfig", root))
        assertTrue(RoslynWatchedFiles.isWatched("C:/work/Shop/Shop.slnx", root))
        assertTrue(RoslynWatchedFiles.isWatched("C:/work/Shop/Web/Pages/Index.cshtml", root))
        assertFalse("the build output", RoslynWatchedFiles.isWatched("C:/work/Shop/App/obj/Debug/net9.0/App.AssemblyInfo.cs", root))
        assertFalse(RoslynWatchedFiles.isWatched("C:/work/Shop/App/bin/Debug/App.dll", root))
        assertFalse(RoslynWatchedFiles.isWatched("C:/work/Shop/.git/index", root))
        assertFalse("not a source", RoslynWatchedFiles.isWatched("C:/work/Shop/README.md", root))
        assertFalse(RoslynWatchedFiles.isWatched("C:/work/Shop/App/Order.cs.bak", root))
        assertFalse("outside the opened folder", RoslynWatchedFiles.isWatched("C:/work/Other/Order.cs", root))
        assertFalse("a sibling folder with the same prefix", RoslynWatchedFiles.isWatched("C:/work/Shop2/Order.cs", root))
    }

    fun testTheEventsBecomeChanges() {
        val dir = myFixture.tempDirFixture.findOrCreateDir("watch")
        val root = dir.path
        val order = myFixture.addFileToProject("watch/Order.cs", "class Order {}").virtualFile
        val readme = myFixture.addFileToProject("watch/README.md", "").virtualFile
        val generated = myFixture.addFileToProject("watch/obj/Generated.cs", "").virtualFile
        val events = listOf(
            VFileCreateEvent(null, dir, "New.cs", false, null, null, null),
            VFileContentChangeEvent(null, order, 1, 2),
            VFileDeleteEvent(null, readme),
            VFileDeleteEvent(null, generated),
            VFilePropertyChangeEvent(null, order, VirtualFile.PROP_NAME, "Order.cs", "Renamed.cs"),
            VFileMoveEvent(null, order, dir.parent),
            VFilePropertyChangeEvent(null, order, VirtualFile.PROP_WRITABLE, false, true),
        )
        // the move goes out of the watched folder: only its deletion counts, and it is the one the rename already gave
        val changes = RoslynWatchedFiles.changes(events, root).map { "${it.type} ${it.path.removePrefix(root)}" }
        assertEquals(listOf("Created /New.cs", "Changed /Order.cs", "Deleted /Order.cs", "Created /Renamed.cs"), changes)
        assertEquals("a batch has no repeats", 4, RoslynWatchedFiles.changes(events + events, root).size)
    }
}
