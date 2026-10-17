package io.github.dotnetsupport

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.frameworks.unity.UnityProjectSettings
import io.github.dotnetsupport.frameworks.unity.UnityStringKind
import io.github.dotnetsupport.frameworks.unity.UnityUnknownNameInspection
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/** UNITY_PLAN.md, "плюшки": the names of ProjectSettings in strings, the .meta that follows its asset, the live templates, the components first in GetComponent<. */
class UnityExtrasTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { null }
    }

    override fun tearDown() {
        try {
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun unity(folder: String) {
        myFixture.addFileToProject("$folder/ProjectSettings/ProjectVersion.txt", "m_EditorVersion: 6000.6.0f1\n")
        myFixture.addFileToProject("$folder/ProjectSettings/TagManager.asset", "%YAML 1.1\n--- !u!78 &1\nTagManager:\n  serializedVersion: 2\n  tags:\n  - Enemy\n  - Pickup\n  layers:\n  - Default\n  - TransparentFX\n  - \n  - Water\n  - Ground\n  m_SortingLayers:\n  - name: Default\n")
        myFixture.addFileToProject("$folder/ProjectSettings/EditorBuildSettings.asset", "EditorBuildSettings:\n  m_Scenes:\n  - enabled: 1\n    path: Assets/Scenes/Boot.unity\n    guid: 1\n  - enabled: 0\n    path: Assets/Scenes/Office01.unity\n    guid: 2\n")
        myFixture.addFileToProject("$folder/ProjectSettings/InputManager.asset", "InputManager:\n  m_Axes:\n  - serializedVersion: 3\n    m_Name: Horizontal\n  - serializedVersion: 3\n    m_Name: Jump\n")
        myFixture.addFileToProject("$folder/Assets/Engine.cs", """
            namespace UnityEngine
            {
                public class Object { }
                public class Component : Object { public bool CompareTag(string tag) => true; public T GetComponent<T>() => default; }
                public class MonoBehaviour : Component { }
                public static class LayerMask { public static int NameToLayer(string name) => 0; }
                public static class Input { public static float GetAxis(string name) => 0; }
            }
        """.trimIndent())
    }

    private fun open(path: String, text: String) {
        val file = myFixture.addFileToProject(path, text.replace("|", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        if ('|' in text) myFixture.editor.caretModel.moveToOffset(text.indexOf('|'))
    }

    fun testTheProjectSettingsAreRead() {
        unity("ux1")
        val root = myFixture.findFileInTempDir("ux1")!!
        val settings = UnityProjectSettings.of(project, root)
        assertEquals(UnityProjectSettings.BUILT_IN_TAGS + listOf("Enemy", "Pickup"), settings.tags)
        assertEquals(listOf("Default", "TransparentFX", "Water", "Ground"), settings.layers)
        assertEquals(listOf("Boot", "Office01"), settings.scenes.map { it.name })
        assertEquals(listOf("Horizontal", "Jump"), settings.axes)
        assertEquals(emptyList<String>(), UnityProjectSettings.listItems("TagManager:\n  tags: []\n  layers:\n  - Default\n", "tags"))
        assertEquals(UnityStringKind.TAG, UnityStringKind.at("        if (other.CompareTag(\"En"))
        assertEquals(UnityStringKind.TAG, UnityStringKind.at("if (gameObject.tag == \""))
        assertEquals(UnityStringKind.LAYER, UnityStringKind.at("var mask = LayerMask.GetMask(\"Default\", \"Wa"))
        assertEquals(UnityStringKind.SCENE, UnityStringKind.at("SceneManager.LoadScene(\""))
        assertEquals(UnityStringKind.AXIS, UnityStringKind.at("Input.GetAxisRaw(\"Hor"))
        assertNull(UnityStringKind.at("Debug.Log(\""))
    }

    fun testTheNamesAreCompletedAndTheUnknownOnesReported() {
        unity("ux2")
        open("ux2/Assets/Player.cs", "using UnityEngine;\npublic class Player : MonoBehaviour\n{\n    void OnTriggerEnter(Component other)\n    {\n        if (other.CompareTag(\"|\")) { }\n    }\n}\n")
        myFixture.completeBasic()
        val tags = myFixture.lookupElementStrings.orEmpty()
        assertTrue(tags.toString(), "Enemy" in tags && "Player" in tags && "Untagged" in tags)
        assertFalse("only tags in a tag: $tags", "Water" in tags || "Horizontal" in tags)

        myFixture.enableInspections(UnityUnknownNameInspection())
        open("ux2/Assets/Checks.cs", "using UnityEngine;\npublic class Checks : MonoBehaviour\n{\n    void Start()\n    {\n        CompareTag(\"Enmy\");\n        CompareTag(\"Enemy\");\n        LayerMask.NameToLayer(\"Water\");\n        LayerMask.NameToLayer(\"Lava\");\n        Input.GetAxis(\"Jump\");\n    }\n}\n")
        val found = myFixture.doHighlighting().filter { it.inspectionToolId == "UnityUnknownName" }.map { it.description }
        assertEquals(listOf(
            "The tag 'Enmy' is not defined in Tags and Layers (ProjectSettings/TagManager.asset)",
            "The layer 'Lava' is not defined in Tags and Layers (ProjectSettings/TagManager.asset)",
        ), found)
    }

    fun testTheMetaFollowsItsAssetOnRename() {
        unity("ux3")
        val script = myFixture.addFileToProject("ux3/Assets/Scripts/Enemy.cs", "public class Enemy { }")
        myFixture.addFileToProject("ux3/Assets/Scripts/Enemy.cs.meta", "fileFormatVersion: 2\nguid: abc\n")
        RenameProcessor(project, script, "Boss.cs", false, false).run()
        val folder = myFixture.findFileInTempDir("ux3/Assets/Scripts")!!
        assertNotNull("the script renamed", folder.findChild("Boss.cs"))
        assertNotNull("its .meta with it", folder.findChild("Boss.cs.meta"))
        assertNull(folder.findChild("Enemy.cs.meta"))
        assertEquals("fileFormatVersion: 2\nguid: abc\n", String(folder.findChild("Boss.cs.meta")!!.contentsToByteArray()))
    }

    fun testTheTemplatesAndTheComponentsInGetComponent() {
        unity("ux4")
        myFixture.addFileToProject("ux4/Assets/Health.cs", "using UnityEngine;\npublic class Health : MonoBehaviour { }\n")
        myFixture.addFileToProject("ux4/Assets/Armor.cs", "public class Armor : Health { }\n")
        open("ux4/Assets/Player.cs", "using UnityEngine;\npublic class Player : MonoBehaviour\n{\n    void Start()\n    {\n        var h = GetComponent<|>();\n    }\n}\n")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertTrue(items.toString(), "Health" in items && "Armor" in items && "Rigidbody" in items)
        assertTrue("the components first: $items", items.indexOf("Health") < 3)

        open("ux4/Assets/Mover.cs", "using UnityEngine;\npublic class Mover : MonoBehaviour\n{\n    sfield|\n}\n")
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.type('\t')
        assertTrue(myFixture.editor.document.text, "[SerializeField] private float speed;" in myFixture.editor.document.text)
    }
}
