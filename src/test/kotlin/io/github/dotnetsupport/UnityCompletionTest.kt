package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/** UNITY_PLAN.md, step 3: the event functions of a Unity class by completion, as Rider writes them. */
class UnityCompletionTest : BasePlatformTestCase() {
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
        myFixture.addFileToProject("$folder/Assets/Engine.cs", """
            namespace UnityEngine
            {
                public class Object { }
                public class Component : Object { }
                public class Behaviour : Component { }
                public class MonoBehaviour : Behaviour { }
                public class Collider : Component { }
            }
        """.trimIndent())
    }

    /** Opens [text] at [path] with the caret where `|` is. */
    private fun open(path: String, text: String) {
        val file = myFixture.addFileToProject(path, text.replace("|", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf('|'))
    }

    fun testTheMessagesOfAMonoBehaviourNotDeclaredYet() {
        unity("uc1")
        open("uc1/Assets/Player.cs", "using UnityEngine;\npublic class Player : MonoBehaviour\n{\n    void Start() { }\n    |\n}\n")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertTrue(items.toString(), "Update" in items && "OnTriggerEnter" in items && "Awake" in items)
        assertFalse("Start is declared", "Start" in items)
        assertFalse("the networking of Unity 4 is gone", "OnServerInitialized" in items)
    }

    fun testUpdIsWrittenAsTheMethodWithItsBody() {
        unity("uc2")
        open("uc2/Assets/Player.cs", "using UnityEngine;\npublic class Player : MonoBehaviour\n{\n    Upd|\n}\n")
        myFixture.completeBasic()
        myFixture.lookup?.let { lookup -> lookup.currentItem = lookup.items.first { it.lookupString == "Update" }; myFixture.finishLookup('\n') }
        assertEquals("using UnityEngine;\npublic class Player : MonoBehaviour\n{\n    private void Update()\n    {\n        \n    }\n}\n", myFixture.editor.document.text)
        assertEquals("the caret in the body", myFixture.editor.document.text.indexOf("{\n        ") + 10, myFixture.caretOffset)
    }

    fun testATypedModifierAndTheParametersOfTheMessage() {
        unity("uc3")
        open("uc3/Assets/Door.cs", "using UnityEngine;\npublic class Door : MonoBehaviour\n{\n    public OnTriggerEn|\n}\n")
        myFixture.completeBasic()
        myFixture.lookup?.let { lookup -> lookup.currentItem = lookup.items.first { it.lookupString == "OnTriggerEnter" }; myFixture.finishLookup('\n') }
        assertTrue(myFixture.editor.document.text, "    public void OnTriggerEnter(Collider other)\n    {\n" in myFixture.editor.document.text)
    }

    fun testNothingInsideAMethodOrOutsideUnity() {
        unity("uc4")
        open("uc4/Assets/Player.cs", "using UnityEngine;\npublic class Player : MonoBehaviour\n{\n    void Start()\n    {\n        Upd|\n    }\n}\n")
        myFixture.completeBasic()
        assertFalse("in a method body", myFixture.lookupElementStrings.orEmpty().contains("Update"))
        open("plain5/Player.cs", "public class MonoBehaviour { }\npublic class Player : MonoBehaviour\n{\n    Upd|\n}\n")
        myFixture.completeBasic()
        assertFalse("not a Unity project", myFixture.lookupElementStrings.orEmpty().contains("Update"))
    }
}
