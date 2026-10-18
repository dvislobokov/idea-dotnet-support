package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.frameworks.unity.UnityEditorLink
import io.github.dotnetsupport.frameworks.unity.UnityEditorPackage
import io.github.dotnetsupport.frameworks.unity.UnityExternalEditor
import io.github.dotnetsupport.frameworks.unity.UnityExternalEditor.Open
import java.io.File

/** UNITY_PLAN.md, step 3: the IDE as Unity's external script editor — what the IDE writes for the package and the files it is asked to open. */
class UnityExternalEditorTest : BasePlatformTestCase() {
    fun testIdeJsonHasWhatThePackageReads() {
        val root = FileUtil.createTempDirectory("unityIde", null)
        val launcher = """C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\bin\idea64.exe"""
        UnityExternalEditor.writeIde(root, UnityExternalEditor.ideJson(4242, launcher, "IntelliJ IDEA 2026.1.4", 1_760_000_000_000))
        val json = JsonParser.parseString(UnityExternalEditor.ideFile(root).readText()).asJsonObject
        assertEquals(launcher, json.get("path").asString)
        assertEquals(4242, json.get("pid").asInt)
        assertFalse("no temporary file left", File(UnityEditorLink.folder(root), "ide.json.tmp").exists())
        // the names ExternalEditor.cs looks for
        val sources = String(UnityEditorPackage.bundled("Editor/ExternalEditor.cs"))
        for (name in listOf("path", "name", "pid", "heartbeat")) assertTrue(name, "\"$name\"" in sources)
        assertTrue("the package carries the editor", "Editor/ExternalEditor.cs" in UnityEditorPackage.FILES)
    }

    fun testTheCommandsOfTheEditorAreTakenInTheirOrderOnce() {
        val root = FileUtil.createTempDirectory("unityIdeCommands", null)
        val folder = File(UnityEditorLink.folder(root), "ide-commands").apply { mkdirs() }
        File(folder, "0000000000002-000001.json").writeText("""{"command":"open","file":"C:/Game/Assets/B.cs","line":-1,"column":-1}""")
        File(folder, "0000000000001-000000.json").writeText("""{"command":"open","file":"C:/Game/Assets/A.cs","line":12,"column":5}""")
        File(folder, "0000000000003-000002.tmp").writeText("""{"command":"open","file":"half written"}""")
        assertEquals(listOf(Open("C:/Game/Assets/A.cs", 12, 5), Open("C:/Game/Assets/B.cs", -1, -1)), UnityExternalEditor.takeCommands(root))
        assertEquals(emptyList<Open>(), UnityExternalEditor.takeCommands(root))
        assertTrue("a file not yet renamed waits", File(folder, "0000000000003-000002.tmp").exists())
        // Assets | Open C# Project: no file, the project only
        assertEquals(Open("", -1, -1), UnityExternalEditor.parse("""{"command":"open","file":"","line":-1,"column":-1}"""))
        assertNull(UnityExternalEditor.parse("""{"command":"unknown"}"""))
    }

    fun testTheStateSaysWhetherUnityOpensScriptsHere() {
        val state = """{"package":"1.2.0","unity":"6000.6.0f1","processId":1,"playing":false,"paused":false,"compiling":false,"updating":false,""" +
            """"compileErrors":0,"compiledAt":0,"ideIsEditor":true,"heartbeat":5}"""
        assertEquals(true, UnityEditorLink.stateOf(JsonParser.parseString(state).asJsonObject)?.ideIsEditor)
        // a package before 1.2.0 does not say
        assertNull(UnityEditorLink.stateOf(JsonParser.parseString(state.replace(""""ideIsEditor":true,""", "")).asJsonObject)?.ideIsEditor)
        val sources = String(UnityEditorPackage.bundled("Editor/IdeConnection.cs"))
        assertTrue("\\\"ideIsEditor\\\"" in sources && "\"use-as-editor\"" in sources)
    }
}
