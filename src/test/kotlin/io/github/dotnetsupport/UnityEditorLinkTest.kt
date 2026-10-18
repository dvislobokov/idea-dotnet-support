package io.github.dotnetsupport

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.frameworks.unity.UnityEditorLink
import io.github.dotnetsupport.frameworks.unity.UnityEditorLink.Status
import io.github.dotnetsupport.frameworks.unity.UnityEditorPackage
import io.github.dotnetsupport.frameworks.unity.UnityEditorStatusWidget
import java.io.File

/**
 * UNITY_PLAN.md, "Интеграция с редактором": the package of the link (installed, updated, hidden from git) and the link itself on the files
 * the editor writes — no Unity here; the live check is the probe project with the editor open (the plan says how).
 */
class UnityEditorLinkTest : BasePlatformTestCase() {
    private fun project(): File {
        val root = FileUtil.createTempDirectory("unityLink", null)
        File(root, "Assets").mkdirs()
        File(root, "ProjectSettings/ProjectVersion.txt").apply { parentFile.mkdirs(); writeText("m_EditorVersion: 6000.6.0f1\n") }
        return root
    }

    fun testThePackageTheIdeWritesIsTheOneTheEditorReports() {
        val sources = String(UnityEditorPackage.bundled("Editor/IdeConnection.cs"))
        assertEquals("the version of package.json is the one the editor writes into its state", UnityEditorPackage.version,
            Regex("""PackageVersion = "([^"]+)"""").find(sources)?.groupValues?.get(1))
        for (path in UnityEditorPackage.FILES) assertTrue(path, UnityEditorPackage.bundled(path).isNotEmpty())
    }

    fun testInstallUpdateAndTheExcludeOfGit() {
        val repository = FileUtil.createTempDirectory("unityRepo", null)
        File(repository, ".git/info").mkdirs()
        val root = File(repository, "Game").apply { File(this, "Assets").mkdirs() }
        assertEquals(UnityEditorPackage.Status.MISSING, UnityEditorPackage.status(root))
        UnityEditorPackage.install(root)
        assertEquals(UnityEditorPackage.Status.CURRENT, UnityEditorPackage.status(root))
        UnityEditorPackage.install(root)
        val exclude = File(repository, ".git/info/exclude").readText()
        assertEquals("one line, from the root of the repository: $exclude", 1, exclude.lines().count { it == "/Game/Packages/${UnityEditorPackage.NAME}/" })
        // the .meta files the editor made stay on an update; a changed file is written again
        val folder = UnityEditorPackage.folder(root)
        File(folder, "package.json.meta").writeText("guid: 1")
        File(folder, "Editor/IdeConnection.cs").writeText("// old")
        assertEquals(UnityEditorPackage.Status.OUTDATED, UnityEditorPackage.status(root))
        UnityEditorPackage.install(root)
        assertEquals(UnityEditorPackage.Status.CURRENT, UnityEditorPackage.status(root))
        assertEquals("guid: 1", File(folder, "package.json.meta").readText())
    }

    fun testTheStatusOfTheLink() {
        val root = project()
        val me = ProcessHandle.current().pid()
        val alive: (Long) -> Boolean = { it == me }
        assertEquals("no editor instance", Status.NO_EDITOR, UnityEditorLink.link(root, 0, alive).status)
        File(root, "Library/EditorInstance.json").apply { parentFile.mkdirs(); writeText("""{ "process_id" : 4242, "version" : "6000.6.0f1" }""") }
        assertEquals("the file a crashed editor left", Status.NO_EDITOR, UnityEditorLink.link(root, 0, alive).status)
        File(root, "Library/EditorInstance.json").writeText("""{ "process_id" : $me, "version" : "6000.6.0f1" }""")
        assertEquals(Status.NO_PACKAGE, UnityEditorLink.link(root, 0, alive).status)
        UnityEditorPackage.install(root)
        assertEquals("installed, no state yet: the editor loads it at its next focus", Status.LOADING, UnityEditorLink.link(root, 0, alive).status)
        val state = File(UnityEditorLink.folder(root), "state.json").apply { parentFile.mkdirs() }
        fun write(version: String, playing: Boolean, heartbeat: Long) = state.writeText(
            """{"package":"$version","unity":"6000.6.0f1","processId":$me,"playing":$playing,"paused":false,"compiling":false,"updating":false,"compileErrors":0,"compiledAt":0,"heartbeat":$heartbeat}""")
        write("0.9.0", false, 10_000)
        assertEquals("the state of an older package the editor still runs", Status.LOADING, UnityEditorLink.link(root, 11_000, alive).status)
        write(UnityEditorPackage.version, true, 10_000)
        val link = UnityEditorLink.link(root, 11_000, alive)
        assertEquals(Status.CONNECTED, link.status)
        assertTrue(link.state!!.playing)
        assertEquals("playing", UnityEditorStatusWidget.short(link))
        assertEquals("no heartbeat for a while", Status.BUSY, UnityEditorLink.link(root, 10_000 + UnityEditorLink.HEARTBEAT_TIMEOUT_MS + 1, alive).status)
        File(UnityEditorPackage.folder(root), "Editor/IdeConnection.cs").writeText("// edited")
        assertEquals(Status.OLD_PACKAGE, UnityEditorLink.link(root, 11_000, alive).status)
    }

    fun testCommandsRunInTheOrderTheyWereSent() {
        val root = project()
        for ((i, command) in listOf("refresh", "play", "pause").withIndex()) UnityEditorLink.send(root, command, i.toLong())
        val files = File(UnityEditorLink.folder(root), "commands").listFiles()!!.sortedBy { it.name }
        assertEquals("only finished files, the editor takes *.txt", listOf("txt", "txt", "txt"), files.map { it.extension })
        assertEquals(listOf("refresh", "play", "pause"), files.map { it.readText() })
    }

    fun testWhatTheEditorRefreshesOnASave() {
        assertTrue(UnityEditorLink.refreshes("Assets/Scripts/Player.cs"))
        assertTrue(UnityEditorLink.refreshes("Packages/com.company.tools/Runtime/Tool.cs"))
        assertFalse("the editor writes those", UnityEditorLink.refreshes("Assets/Scripts/Player.cs.meta"))
        assertFalse(UnityEditorLink.refreshes("Library/ScriptAssemblies/x.dll"))
        assertFalse(UnityEditorLink.refreshes("Game.Core.csproj"))
    }
}
