package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames
import io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration
import io.github.dotnetsupport.frameworks.unity.UnityAssetParser
import io.github.dotnetsupport.frameworks.unity.UnityAssetUsages
import io.github.dotnetsupport.frameworks.unityyaml.UnityMissingScriptInspection
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpImplicitUsage
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCodeLens
import io.github.dotnetsupport.lang.NativeCSharpSolutionRename
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import org.jetbrains.yaml.YAMLFileType

/**
 * The asset index of Unity (UNITY_PLAN.md): the components, UnityEvent calls and animation events of the scenes, read from their text;
 * what the lenses say of them, Find Usages in them, the links of a scene to its scripts, a missing script, `[FormerlySerializedAs]` on
 * the rename of a field the scenes set.
 */
class UnityAssetsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { null }
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.RENAME, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            NativeCSharpSolutionRename.answerHierarchyForTests(null)
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val player = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"
    private val door = "1f2e3d4c5b6a79880796a5b4c3d2e1f0"

    private val scene = """
        %YAML 1.1
        %TAG !u! tag:unity3d.com,2011:
        --- !u!1 &100
        GameObject:
          m_ObjectHideFlags: 0
          m_Name: Hero
        --- !u!114 &200
        MonoBehaviour:
          m_ObjectHideFlags: 0
          m_GameObject: {fileID: 100}
          m_Enabled: 1
          m_EditorHideFlags: 0
          m_Script: {fileID: 11500000, guid: $player, type: 3}
          m_Name:
          m_EditorClassIdentifier:
          speed: 7
          target: {fileID: 0}
          onLanded:
            m_PersistentCalls:
              m_Calls:
              - m_Target: {fileID: 200}
                m_TargetAssemblyTypeName: Demo.Player, Assembly-CSharp
                m_MethodName: Jump
                m_Mode: 1
        --- !u!114 &300
        MonoBehaviour:
          m_GameObject: {fileID: 100}
          m_Script: {fileID: 11500000, guid: $door, type: 3}
          m_EditorClassIdentifier:
        --- !u!114 &400 stripped
        MonoBehaviour:
          m_CorrespondingSourceObject: {fileID: 11400000, guid: 00000000000000000000000000000001, type: 3}
          m_Script: {fileID: 11500000, guid: $player, type: 3}
    """.trimIndent() + "\n"

    private val animation = """
        %YAML 1.1
        %TAG !u! tag:unity3d.com,2011:
        --- !u!74 &7400000
        AnimationClip:
          m_Name: Walk
          m_Events:
          - time: 0.5
            functionName: Footstep
            data:
    """.trimIndent() + "\n"

    private fun unity(folder: String): CSharpFile {
        myFixture.addFileToProject("$folder/ProjectSettings/ProjectVersion.txt", "m_EditorVersion: 6000.6.0f1\n")
        myFixture.addFileToProject("$folder/Assets/Engine.cs", """
            namespace UnityEngine
            {
                public class Object { }
                public class Component : Object { }
                public class MonoBehaviour : Component { }
                public class SerializeField : System.Attribute { }
            }
        """.trimIndent())
        val file = myFixture.addFileToProject("$folder/Assets/Player.cs", """
            using UnityEngine;

            namespace Demo
            {
                public class Player : MonoBehaviour
                {
                    [SerializeField] private int speed = 5;
                    public float other;
                    public void Jump() { }
                    void Footstep() { }
                    void Unused() { }
                }
            }
        """.trimIndent())
        myFixture.addFileToProject("$folder/Assets/Player.cs.meta", "fileFormatVersion: 2\nguid: $player\nMonoImporter:\n")
        myFixture.addFileToProject("$folder/Assets/Scenes/Boot.unity", scene)
        myFixture.addFileToProject("$folder/Assets/Animations/Walk.anim", animation)
        return file as CSharpFile
    }

    private fun declaration(file: CSharpFile, name: String): PsiElement = NativeCSharpCodeLens.declarations(file).first { CSharpDeclarationNames.name(it) == name }

    fun testTheParserReadsAScene() {
        val index = UnityAssetParser.index(scene)
        assertEquals(setOf("s:$player", "s:$door", "m:Jump"), index.keys)
        val component = index.getValue("s:$player").single() // the stripped one of a prefab instance is not a component of the scene
        assertEquals("Hero", component.name)
        assertEquals(scene.indexOf(player), component.offset)
        assertEquals(listOf("speed=7", "target={fileID: 0}", "onLanded="), component.fields.map { "${it.name}=${it.value}" })
        assertEquals(scene.indexOf("speed: 7"), component.fields.first().offset)
        val call = index.getValue("m:Jump").single()
        assertEquals("the script of the target component in the file", "guid:$player", call.target)
        assertEquals(scene.indexOf("Jump"), call.offset)
        assertEquals(listOf("Footstep"), UnityAssetParser.index(animation).getValue("m:Footstep").map { it.name })
        assertTrue(UnityAssetParser.index("TagManager:\n  tags: []\n").isEmpty())
    }

    fun testTheLensesSayWhatTheAssetsUse() {
        val file = unity("ua1")
        assertEquals("Used in Boot.unity", UnityAssetUsages.note(declaration(file, "Player")))
        assertEquals("Event handler in Boot.unity", UnityAssetUsages.note(declaration(file, "Jump")))
        assertEquals("Animation event in Walk.anim", UnityAssetUsages.note(declaration(file, "Footstep")))
        assertEquals("Set by Unity: 7", UnityAssetUsages.note(declaration(file, "speed")))
        assertNull("not in the scene", UnityAssetUsages.note(declaration(file, "other")))
        assertNull(UnityAssetUsages.note(declaration(file, "Unused")))
        assertEquals("Used in Boot.unity", CSharpImplicitUsage.lensText("no usages", 0, { "Unity script" }, "Used in Boot.unity"))
        assertEquals("2 usages, used in Boot.unity", CSharpImplicitUsage.lensText("2 usages", 2, { null }, "Used in Boot.unity"))
        assertEquals("Unity script", CSharpImplicitUsage.lensText("no usages", 0, { "Unity script" }, null))
    }

    fun testFindUsagesListsTheAssets() {
        val file = unity("ua2")
        fun inAssets(name: String) = ReferencesSearch.search(declaration(file, name)).findAll()
            .filter { it.element.containingFile.name != "Player.cs" }.map { "${it.element.containingFile.name}:${it.rangeInElement.substring(it.element.text)}" }
        assertEquals(listOf("Boot.unity:$player"), inAssets("Player"))
        assertEquals(listOf("Boot.unity:Jump"), inAssets("Jump"))
        assertEquals(listOf("Walk.anim:Footstep"), inAssets("Footstep"))
        assertEquals(listOf("Boot.unity:speed"), inAssets("speed"))
    }

    fun testTheScenesAreYamlWithLinksToTheScripts() {
        unity("ua3")
        val sceneFile = myFixture.findFileInTempDir("ua3/Assets/Scenes/Boot.unity")
        assertEquals(YAMLFileType.YML, sceneFile.fileType)
        val psi = PsiManager.getInstance(project).findFile(sceneFile)!!
        val script = psi.findReferenceAt(scene.indexOf(player) + 3)!!.resolve()
        assertEquals("Player", (script as CSharpBaseTypeDeclaration).identifier?.text)
        val method = psi.findReferenceAt(scene.indexOf("Jump") + 1)!!.resolve()
        assertEquals("Jump", (method as CSharpMethodDeclaration).identifier?.text)
        assertNull("Door has no script in the project", psi.findReferenceAt(scene.indexOf(door) + 3)?.resolve())
    }

    fun testAMissingScriptIsReported() {
        unity("ua4")
        // the YAML plugin's own: every `&200` "is never used" in a file Unity wrote — off there
        myFixture.enableInspections(UnityMissingScriptInspection(), org.jetbrains.yaml.inspections.YAMLUnusedAnchorInspection())
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("ua4/Assets/Scenes/Boot.unity"))
        val problems = myFixture.doHighlighting(HighlightSeverity.WARNING).map { it.description }
        assertEquals(listOf("Missing script: no script of the project has the GUID $door"), problems)
    }

    fun testTheRenameOfAFieldTheSceneSetsKeepsItsValue() {
        val file = unity("ua5")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(file.text.indexOf("speed"))
        myFixture.renameElementAtCaretUsingHandler("velocity")
        val text = myFixture.editor.document.text
        assertTrue(text, "using UnityEngine;\nusing UnityEngine.Serialization;\n" in text)
        assertTrue(text, "        [FormerlySerializedAs(\"speed\")]\n        [SerializeField] private int velocity = 5;" in text)

        // a field no asset sets is renamed alone
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("other"))
        myFixture.renameElementAtCaretUsingHandler("another")
        val after = myFixture.editor.document.text
        assertTrue(after, "        public float another;" in after)
        assertFalse(after, "FormerlySerializedAs(\"other\")" in after)
        assertTrue(PsiTreeUtil.findChildrenOfType(myFixture.file, CSharpMethodDeclaration::class.java).isNotEmpty())
    }
}
