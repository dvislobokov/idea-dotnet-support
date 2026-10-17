package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.frameworks.unity.UnityCompareTagInspection
import io.github.dotnetsupport.frameworks.unity.UnityEventFunctionSignatureInspection
import io.github.dotnetsupport.frameworks.unity.UnityExpensiveCallInFrameInspection
import io.github.dotnetsupport.frameworks.unity.UnityGetComponentTypeInspection
import io.github.dotnetsupport.frameworks.unity.UnityObjectNullPropagationInspection
import io.github.dotnetsupport.frameworks.unity.UnityRedundantSerializeFieldInspection
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/** The inspections of Unity (UNITY_PLAN.md, "плюшки"): what each reports in a Unity project, what its quick-fix writes, nothing outside Unity. */
class UnityInspectionsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { null }
        myFixture.enableInspections(
            UnityObjectNullPropagationInspection(), UnityCompareTagInspection(), UnityRedundantSerializeFieldInspection(),
            UnityEventFunctionSignatureInspection(), UnityExpensiveCallInFrameInspection(), UnityGetComponentTypeInspection(),
        )
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
                public class Object { public string name; }
                public class Component : Object { public string tag; public GameObject gameObject; public bool CompareTag(string tag) => true; public T GetComponent<T>() => default; public T AddComponent<T>() => default; }
                public class GameObject : Object { public string tag; public static GameObject Find(string name) => null; }
                public class Behaviour : Component { }
                public class MonoBehaviour : Behaviour { }
                public class Rigidbody : Component { public void AddForce(float f) { } }
                public class Collider : Component { }
                public class Collision { }
                public sealed class SerializeField : System.Attribute { }
            }
            public class Settings { }
        """.trimIndent())
    }

    private fun open(path: String, text: String) {
        val file = myFixture.addFileToProject(path, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
    }

    /** `line: text` of every warning and error of the open file from the Unity inspections. */
    private fun problems(): List<String> {
        val document = myFixture.editor.document
        return myFixture.doHighlighting().filter { it.description != null && (it.inspectionToolId?.startsWith("Unity") == true) }
            .sortedBy { it.startOffset }.map { "${document.getLineNumber(it.startOffset) + 1}: ${it.description}" }
    }

    fun testWhatEachInspectionReports() {
        unity("ui1")
        open("ui1/Assets/Player.cs", """
            using UnityEngine;
            public class Player : MonoBehaviour
            {
                [SerializeField] private static int count;
                [SerializeField] private float speed;
                public Rigidbody target;
                void update() { }
                void OnTriggerEnter(Collision other) { }
                void OnCollisionEnter() { }
                void Update()
                {
                    var body = GetComponent<Rigidbody>();
                    target?.AddForce(1);
                    var t = target ?? body;
                    if (gameObject.tag == "Enemy") { }
                    var p = GetComponent<Player>();
                    var s = GetComponent<Settings>();
                    System.Action later = () => GetComponent<Rigidbody>();
                }
            }
        """.trimIndent())
        val found = problems()
        fun has(line: Int, part: String) = assertTrue("$line: $part in $found", found.any { it.startsWith("$line: ") && part in it })
        has(4, "Redundant 'SerializeField': Unity does not serialize a static field")
        has(7, "'update' is not an event function: Unity calls 'Update'")
        has(8, "Incorrect signature of the event function 'OnTriggerEnter': Unity calls it with (Collider other)")
        has(12, "Expensive 'GetComponent' in 'Update'")
        has(13, "'?.' on a type derived from 'UnityEngine.Object'")
        has(14, "'??' on a type derived from 'UnityEngine.Object'")
        has(15, "use 'CompareTag'")
        has(17, "'Settings' is not a 'Component' or an interface")
        assertFalse("[SerializeField] private: serialized, fine: $found", found.any { it.startsWith("5: ") })
        assertFalse("OnCollisionEnter() without its parameter is called: $found", found.any { it.startsWith("9: ") })
        assertFalse("GetComponent<Player>: a component: $found", found.any { it.startsWith("16: ") && "is not" in it })
        assertFalse("a lambda is not run every frame by itself: $found", found.any { it.startsWith("18: ") })
    }

    fun testTheQuickFixes() {
        unity("ui2")
        open("ui2/Assets/Door.cs", """
            using UnityEngine;
            public class Door : MonoBehaviour
            {
                [SerializeField] private readonly int locks;
                void OnTriggerEnter(Collision other)
                {
                    if (other.ToString() != null && gameObject.tag != "Player") { }
                }
                void Update()
                {
                    GetComponent<Rigidbody>().AddForce(1);
                }
            }
        """.trimIndent())
        myFixture.doHighlighting()
        for (fix in listOf("Remove the attribute", "Change the signature to (Collider other)", "Use CompareTag", "Introduce a field and initialize it in 'Awake'")) {
            myFixture.launchAction(myFixture.getAllQuickFixes().first { it.text == fix })
            myFixture.doHighlighting()
        }
        assertEquals("""
            using UnityEngine;
            public class Door : MonoBehaviour
            {
                private readonly int locks;
                void OnTriggerEnter(Collider other)
                {
                    if (other.ToString() != null && !gameObject.CompareTag("Player")) { }
                }
                private Rigidbody _rigidbody;

                private void Awake()
                {
                    _rigidbody = GetComponent<Rigidbody>();
                }

                void Update()
                {
                    _rigidbody.AddForce(1);
                }
            }
        """.trimIndent(), myFixture.editor.document.text)
    }

    fun testNothingOutsideAUnityProject() {
        open("plainInspections/Player.cs", """
            public class Object { }
            public class Player : Object
            {
                public Object target;
                void update() { var t = target ?? new Object(); }
            }
        """.trimIndent())
        assertEquals(emptyList<String>(), problems())
    }
}
