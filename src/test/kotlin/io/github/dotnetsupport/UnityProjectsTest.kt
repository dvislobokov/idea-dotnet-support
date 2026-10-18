package io.github.dotnetsupport

import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.frameworks.unity.UnityItemTemplates
import io.github.dotnetsupport.frameworks.unity.UnityProjects
import io.github.dotnetsupport.frameworks.unity.UnityViewSettings
import io.github.dotnetsupport.solution.SolutionFinder
import io.github.dotnetsupport.templates.ItemCreator
import io.github.dotnetsupport.view.SolutionKey
import io.github.dotnetsupport.view.SolutionNode
import io.github.dotnetsupport.view.SolutionTreeStructure
import io.github.dotnetsupport.view.SolutionViewSettings

/** The Unity module (UNITY_PLAN.md): a Unity project is recognized, its caches and `.meta` files stay out of the Solution view, New → Unity renders its scripts. */
class UnityProjectsTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            SolutionViewSettings.setShowAllFiles(project, false)
            UnityViewSettings.setShowMetaFiles(project, false)
        } finally {
            super.tearDown()
        }
    }

    private fun unityProject(): com.intellij.openapi.vfs.VirtualFile {
        myFixture.addFileToProject("u/ProjectSettings/ProjectVersion.txt", "m_EditorVersion: 6000.6.0f1\nm_EditorVersionWithRevision: 6000.6.0f1 (f7f8ed4d1e24)\n")
        myFixture.addFileToProject("u/Assets/Game/Player.cs", "using UnityEngine;\npublic class Player : MonoBehaviour {}")
        myFixture.addFileToProject("u/Assets/Game/Player.cs.meta", "fileFormatVersion: 2\nguid: 1")
        myFixture.addFileToProject("u/Assets/Game.meta", "fileFormatVersion: 2\nguid: 2")
        myFixture.addFileToProject("u/Library/ScriptAssemblies/Assembly-CSharp.dll", "")
        myFixture.addFileToProject("u/Library/PackageCache/com.unity.nuget.mono-cecil@1/cecil.sln", "")
        myFixture.addFileToProject("u/Temp/UnityLockfile", "")
        myFixture.addFileToProject(
            "u/Assembly-CSharp.csproj",
            "<Project ToolsVersion=\"4.0\"><PropertyGroup><LangVersion>9.0</LangVersion><RootNamespace></RootNamespace></PropertyGroup>" +
                "<ItemGroup><Compile Include=\"Assets\\Game\\Player.cs\" /></ItemGroup></Project>",
        )
        return myFixture.addFileToProject("u/u.slnx", """<Solution><Project Path="Assembly-CSharp.csproj" /></Solution>""").virtualFile
    }

    fun testAUnityProjectIsRecognizedByItsFolders() {
        val sln = unityProject()
        val root = sln.parent
        assertTrue(UnityProjects.isUnityRoot(root))
        assertFalse(UnityProjects.isUnityRoot(root.findChild("Assets")!!))
        assertEquals(root, UnityProjects.rootOf(root.findFileByRelativePath("Assets/Game/Player.cs")!!))
        assertNull(UnityProjects.rootOf(root.parent))
        assertEquals("6000.6.0f1", UnityProjects.editorVersion(root))
        assertTrue(UnityProjects.isGeneratedDirectory(root.findChild("Library")!!))
        assertTrue(UnityProjects.isGeneratedDirectory(root.findChild("Temp")!!))
        assertFalse(UnityProjects.isGeneratedDirectory(root.findChild("Assets")!!))
        // the solutions inside the package cache are nobody's: the finder skips the generated folders; a package's own in Assets is not
        // the project's either (FishNet's Mono.Cecil.sln): only the root's solution, no chooser
        myFixture.addFileToProject("u/Assets/FishNet/CodeGenerating/cecil-0.11.4/Mono.Cecil.sln", "")
        assertEquals(listOf("u.slnx"), SolutionFinder.find(root).solutions.map { it.name })
        // NuGet does nothing here: Unity generates the projects and restores no packages (the window says why, the menu is off)
        assertEquals(UnityProjects.NUGET_UNAVAILABLE, io.github.dotnetsupport.solution.SolutionLayout.nuGetUnavailable(root))
        assertNull(io.github.dotnetsupport.solution.SolutionLayout.nuGetUnavailable(root.findChild("Assets")!!))
    }

    fun testTheSolutionViewHidesTheCachesAndTheMetaFiles() {
        val sln = unityProject()
        SolutionViewSettings.setShowAllFiles(project, true)
        val structure = SolutionTreeStructure(project)
        val solutionNode = SolutionNode(project, SolutionKey(sln), structure)
        solutionNode.update()
        assertEquals("Unity 6000.6.0f1 · 1 project", solutionNode.presentation.locationString)
        // the project sits in the root of the Unity project: everything of the folder is under its node, the caches excepted
        val projectNode = child(structure, solutionNode, "Assembly-CSharp")
        val top = names(structure, projectNode)
        assertTrue(top.toString(), "Assets" in top && "ProjectSettings" in top && "Library" !in top && "Temp" !in top)
        val game = child(structure, child(structure, projectNode, "Assets"), "Game")
        assertEquals(listOf("Player.cs"), names(structure, game))
        UnityViewSettings.setShowMetaFiles(project, true)
        assertEquals(listOf("Player.cs", "Player.cs.meta"), names(structure, game))
    }

    /** The namespace of a folder counts from the assembly definition above it (Unity, Rider), not from the project file in the root. */
    fun testTheNamespaceOfAFolderCountsFromTheAssemblyDefinition() {
        val sln = unityProject()
        val root = sln.parent
        myFixture.addFileToProject("u/Assets/Game/Boot/Game.Boot.asmdef", """{ "name": "Game.Boot", "rootNamespace": "Game.Boot" }""")
        myFixture.addFileToProject("u/Assets/Game/Boot/Ui/Menu.cs", "")
        myFixture.addFileToProject("u/Assets/Scripts/Enemies/Enemy.cs", "")
        val boot = myFixture.addFileToProject("u/Game.Boot.csproj", "<Project ToolsVersion=\"4.0\"><PropertyGroup><RootNamespace>Game.Boot</RootNamespace></PropertyGroup></Project>").virtualFile
        val main = root.findChild("Assembly-CSharp.csproj")!!
        assertEquals("Game.Boot", io.github.dotnetsupport.msbuild.DotNetProjects.namespaceFor(root.findFileByRelativePath("Assets/Game/Boot")!!, boot, "Game.Boot"))
        assertEquals("Game.Boot.Ui", io.github.dotnetsupport.msbuild.DotNetProjects.namespaceFor(root.findFileByRelativePath("Assets/Game/Boot/Ui")!!, boot, "Game.Boot"))
        // Assembly-CSharp: no root namespace, the folders from Assets, `Scripts` none of them
        assertEquals("Enemies", io.github.dotnetsupport.msbuild.DotNetProjects.namespaceFor(root.findFileByRelativePath("Assets/Scripts/Enemies")!!, main, ""))
        assertEquals("", io.github.dotnetsupport.msbuild.DotNetProjects.namespaceFor(root.findChild("Assets")!!, main, ""))
        // an assembly definition with no project of its own yet (the editor has not regenerated): its rootNamespace counts
        myFixture.addFileToProject("u/Assets/Gameplay/Gameplay.asmdef", """{ "name": "Demo.Gameplay", "rootNamespace": "Demo.Gameplay" }""")
        myFixture.addFileToProject("u/Assets/Gameplay/Ai/Brain.cs", "")
        assertEquals("Demo.Gameplay.Ai", io.github.dotnetsupport.msbuild.DotNetProjects.namespaceFor(root.findFileByRelativePath("Assets/Gameplay/Ai")!!, main, ""))
    }

    fun testNewUnityScriptsRenderWithTheirUsings() {
        val sln = unityProject()
        val directory = sln.parent.findFileByRelativePath("Assets/Game")!!
        val script = ItemCreator.create(project, directory, UnityItemTemplates.ALL.first { it.id == "unityMonoBehaviour" }, "Enemy").single()
        val text = String(script.contentsToByteArray())
        assertEquals("Enemy.cs", script.name)
        assertTrue(text, text.startsWith("using UnityEngine;"))
        // the namespace of the folder from Assets (no assembly definition, no root namespace), a block: LangVersion 9 has no file-scoped one
        assertTrue(text, "namespace Game\n{" in text)
        assertTrue(text, "public class Enemy : MonoBehaviour" in text && "void Update()" in text)
        val editor = ItemCreator.create(project, directory, UnityItemTemplates.ALL.first { it.id == "unityEditor" }, "Enemy").single()
        val editorText = String(editor.contentsToByteArray())
        assertEquals("EnemyEditor.cs", editor.name)
        assertTrue(editorText, "[CustomEditor(typeof(Enemy))]" in editorText && "public class EnemyEditor : Editor" in editorText)
        val asmdef = ItemCreator.create(project, directory, UnityItemTemplates.ALL.first { it.id == "unityAsmdef" }, "Game.Core").single()
        assertEquals("Game.Core.asmdef", asmdef.name)
        assertTrue("\"name\": \"Game.Core\"" in String(asmdef.contentsToByteArray()))
    }

    private fun names(structure: SolutionTreeStructure, node: Any): List<String> = structure.getChildElements(node).map { describe(it) }.sorted()

    private fun child(structure: SolutionTreeStructure, node: Any, name: String): AbstractTreeNode<*> =
        structure.getChildElements(node).map { it as AbstractTreeNode<*> }.first { describe(it) == name }

    private fun describe(node: Any): String {
        val treeNode = node as AbstractTreeNode<*>
        treeNode.update()
        return treeNode.presentation.presentableText ?: treeNode.name.orEmpty()
    }
}
