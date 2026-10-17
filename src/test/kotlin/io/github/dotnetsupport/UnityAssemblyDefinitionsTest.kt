package io.github.dotnetsupport

import com.intellij.json.JsonFileType
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import io.github.dotnetsupport.frameworks.unityjson.AssemblyDefinitionReferenceInspection
import io.github.dotnetsupport.frameworks.unityjson.AssemblyDefinitions

/** `.asmdef` / `.asmref` of Unity: JSON with the schema of the plugin, the names of `"references"` completed, navigable and checked. */
class UnityAssemblyDefinitionsTest : BasePlatformTestCase() {
    private fun definitions(folder: String) {
        myFixture.addFileToProject("$folder/Assets/Core/Game.Core.asmdef", "{\n  \"name\": \"Game.Core\",\n  \"rootNamespace\": \"Game.Core\"\n}\n")
        myFixture.addFileToProject("$folder/Assets/Core/Game.Core.asmdef.meta", "fileFormatVersion: 2\nguid: 0f1e2d3c4b5a69788796a5b4c3d2e1f0\nAssemblyDefinitionImporter:\n")
        myFixture.addFileToProject("$folder/Assets/Net/Game.Net.asmdef", "{ \"name\": \"Game.Net\" }\n")
    }

    fun testTheFilesAreJsonWithTheSchema() {
        definitions("ad1")
        val file = myFixture.configureByText("Game.Boot.asmdef", "{ \"name\": \"Game.Boot\" }")
        assertEquals(JsonFileType.INSTANCE, file.fileType)
        assertEquals(JsonFileType.INSTANCE, myFixture.configureByText("Game.Boot.asmref", "{ \"reference\": \"Game.Core\" }").fileType)
        val schema = JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file.virtualFile)
        assertTrue(schema.toString(), schema.any { it.path.endsWith("unityjson/asmdef.schema.json") })
    }

    fun testTheReferencesAreCompletedAndResolved() {
        definitions("ad2")
        myFixture.configureByText("Game.Boot.asmdef", "{\n  \"name\": \"Game.Boot\",\n  \"references\": [ \"<caret>\" ]\n}\n")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(items.toString(), items.containsAll(listOf("Game.Core", "Game.Net")))

        myFixture.configureByText("Game.Boot.asmdef", "{\n  \"name\": \"Game.Boot\",\n  \"references\": [ \"Game.Co<caret>re\", \"GUID:0f1e2d3c4b5a69788796a5b4c3d2e1f0\" ]\n}\n")
        assertEquals("Game.Core.asmdef", (myFixture.getReferenceAtCaretPositionWithAssertion().resolve() as PsiFile).name)
        val guid = myFixture.file.findReferenceAt(myFixture.file.text.indexOf("GUID:") + 2)!!
        assertEquals("by the guid of the .meta", "Game.Core.asmdef", (guid.resolve() as PsiFile).name)

        myFixture.configureByText("Game.Boot.asmref", "{ \"reference\": \"Game.N<caret>et\" }")
        assertEquals("Game.Net.asmdef", (myFixture.getReferenceAtCaretPositionWithAssertion().resolve() as PsiFile).name)
    }

    fun testAnUnknownReferenceIsAWarning() {
        definitions("ad3")
        myFixture.enableInspections(AssemblyDefinitionReferenceInspection())
        myFixture.configureByText(
            "Game.Boot.asmdef",
            "{\n  \"name\": \"Game.Boot\",\n  \"references\": [ \"Game.Core\", <warning descr=\"No assembly definition 'Game.Missing' in the project\">\"Game.Missing\"</warning>, " +
                "<warning descr=\"No assembly definition 'GUID:00000000000000000000000000000000' in the project\">\"GUID:00000000000000000000000000000000\"</warning> ],\n" +
                "  \"defineConstraints\": [ \"UNITY_EDITOR\" ]\n}\n",
        )
        myFixture.checkHighlighting(true, false, false)
        // a JSON file of another kind is not touched
        myFixture.configureByText("package.json", "{ \"references\": [ \"Game.Missing\" ] }")
        myFixture.checkHighlighting(true, false, false)
        assertFalse(AssemblyDefinitions.isAssemblyFile(myFixture.file.virtualFile))
    }
}
