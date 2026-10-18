package io.github.dotnetsupport

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.frameworks.unity.UnityEditorPackage
import io.github.dotnetsupport.frameworks.unity.UnityTests
import io.github.dotnetsupport.frameworks.unity.UnityTests.Mode
import io.github.dotnetsupport.testing.LiveTestEvent
import io.github.dotnetsupport.testing.LiveTestTree
import io.github.dotnetsupport.testing.TestDiscovery
import io.github.dotnetsupport.testing.TestTarget
import java.io.File

/** UNITY_PLAN.md, step 4: tests of the Unity Test Framework run by the editor — the command, the filters, the mode, the events in the tree. */
class UnityTestsTest : BasePlatformTestCase() {
    fun testTheCommandAndTheFilters() {
        val type = TestTarget("Game.Tests.HealthRulesTests", null, TextRange.EMPTY_RANGE)
        val method = TestTarget("Game.Tests.HealthRulesTests", "Damage", TextRange.EMPTY_RANGE)
        assertEquals("""^Game\.Tests\.HealthRulesTests\.""", UnityTests.group(type))
        assertEquals("""^Game\.Tests\.HealthRulesTests\.Damage(\(|$)""", UnityTests.group(method))
        // what the filters take, as .NET's Regex would: the cases of the method, not another method or class beginning the same
        val regex = Regex(UnityTests.group(method))
        assertTrue(regex.containsMatchIn("Game.Tests.HealthRulesTests.Damage(10,5)"))
        assertTrue(regex.containsMatchIn("Game.Tests.HealthRulesTests.Damage"))
        assertFalse(regex.containsMatchIn("Game.Tests.HealthRulesTests.DamageTwice"))
        assertFalse(Regex(UnityTests.group(type)).containsMatchIn("Game.Tests.HealthRulesTestsBase.Damage"))
        // a nested fixture is named with a dot by NUnit
        assertEquals("""^Game\.Outer\.Inner\.""", UnityTests.group(TestTarget("Game.Outer+Inner", null, TextRange.EMPTY_RANGE)))
        assertEquals("run-tests run-1 EditMode\ngroup:^A\\.", UnityTests.command("run-1", Mode.EditMode, listOf("^A\\.")))
        assertEquals("run-tests run-2 PlayMode", UnityTests.command("run-2", Mode.PlayMode, emptyList()))
    }

    fun testTheModeComesFromTheAssemblyDefinition() {
        val editMode = myFixture.addFileToProject("ut/Assets/Tests/EditMode/Tests.EditMode.asmdef", """{ "name": "Tests.EditMode", "includePlatforms": ["Editor"] }""")
        val editTest = myFixture.addFileToProject("ut/Assets/Tests/EditMode/Deep/HealthTests.cs", "")
        myFixture.addFileToProject("ut/Assets/Tests/PlayMode/Tests.PlayMode.asmdef", """{ "name": "Tests.PlayMode", "includePlatforms": [] }""")
        val playTest = myFixture.addFileToProject("ut/Assets/Tests/PlayMode/MoveTests.cs", "")
        val looseEditor = myFixture.addFileToProject("ut/Assets/Editor/EditorTests.cs", "")
        val root = editMode.virtualFile.parent.parent.parent.parent
        assertEquals(Mode.EditMode, UnityTests.modeOf(editTest.virtualFile, root))
        assertEquals(Mode.PlayMode, UnityTests.modeOf(playTest.virtualFile, root))
        assertEquals(Mode.EditMode, UnityTests.modeOf(looseEditor.virtualFile, root))
    }

    fun testTheEventsOfThePackageMakeTheTree() {
        // the lines as TestRuns.cs writes them
        val start = """{"event":"testStart","id":"[Game.Tests.EditMode][Game.Tests.HealthRulesTests.Damage(10,5)]","fqn":"Game.Tests.HealthRulesTests.Damage(10,5)","displayName":"Damage(10,5)","source":"Game.Tests.EditMode","type":"Game.Tests.HealthRulesTests"}"""
        val result = """{"event":"result","id":"[Game.Tests.EditMode][Game.Tests.HealthRulesTests.Damage(10,5)]","fqn":"Game.Tests.HealthRulesTests.Damage(10,5)","displayName":"Damage(10,5)","source":"Game.Tests.EditMode","type":"Game.Tests.HealthRulesTests","resultName":"Damage(10,5)","outcome":"Failed","durationMs":"12","message":"Expected: 5\n  But was:  6","stackTrace":"at Game.Tests.HealthRulesTests.Damage (System.Int32 a, System.Int32 b) [0x00001] in C:\\Game\\Assets\\Game\\Tests\\EditMode\\HealthRulesTests.cs:14","stdout":null}"""
        val tree = LiveTestTree("C:/Game/Assets")
        val messages = listOf(start, result).mapNotNull(LiveTestEvent::parse).flatMap(tree::onEvent) + tree.finish()
        val text = messages.joinToString("\n")
        assertTrue(text, "testSuiteStarted name='Game.Tests.HealthRulesTests'" in text)
        assertTrue(text, "testStarted name='Damage(10,5)'" in text)
        assertTrue(text, "testFailed name='Damage(10,5)'" in text && "Expected: 5" in text)
        assertTrue(text, "duration='12'" in text)
        assertTrue(text, "dotnet-test://C:/Game/Assets||Game.Tests.HealthRulesTests||Damage" in text) // service messages escape the bars
        // the package writes what the tree reads
        val sources = String(UnityEditorPackage.bundled("Editor/Tests/TestRuns.cs"))
        for (name in listOf("testStart", "result", "runComplete", "fqn", "displayName", "outcome", "durationMs", "stackTrace", "stdout")) assertTrue(name, name in sources)
        assertTrue(String(UnityEditorPackage.bundled("Editor/Tests/DotNetSupport.Unity.Editor.Tests.asmdef")).contains("com.unity.test-framework"))
    }

    fun testTheEndOfARunAndTheCoroutineTests() {
        val events = File(FileUtil.createTempDirectory("unityTests", null), "events.jsonl")
        assertFalse(UnityTests.isComplete(events))
        events.writeText("""{"event":"runStart","mode":"EditMode"}""" + "\n")
        assertFalse(UnityTests.isComplete(events))
        events.appendText("""{"event":"runComplete","how":"finished"}""" + "\n")
        assertTrue(UnityTests.isComplete(events))
        // [UnityTest] is a test of the gutter too
        val text = "using NUnit.Framework;\nusing UnityEngine.TestTools;\nclass MoveTests {\n    [UnityTest]\n    public System.Collections.IEnumerator Moves() { yield return null; }\n}\n"
        assertEquals(listOf("Moves"), TestDiscovery.targets(text).mapNotNull { it.methodName })
    }
}
