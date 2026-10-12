package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/** The icons of files and tree nodes: one set for both themes, drawn by tools/icons/generate.py. */
class IconsTest : BasePlatformTestCase() {
    // the sources: in the tests the resources of the plugin come packed in a jar
    private val directory = File("src/main/resources/icons").absoluteFile

    /** The tool windows have their own pairs (and the ones of the new UI in icons/expui). */
    private val drawn = listOf(
        "csharp", "csharpType", "fsharp", "vb", "project", "projectFSharp", "projectVb", "solution", "dependencies", "propertiesFolder",
        "settingsJson", "msbuild", "nuget", "assembly", "config", "razor", "xaml", "resx",
    )

    fun testEveryIconHasItsDarkPair() {
        for (name in drawn) {
            val light = File(directory, "$name.svg")
            val dark = File(directory, "${name}_dark.svg")
            assertTrue("$name.svg", light.isFile)
            assertTrue("${name}_dark.svg", dark.isFile)
            for (file in listOf(light, dark)) assertTrue(file.name, file.readText().contains("""width="16" height="16" viewBox="0 0 16 16""""))
            assertFalse("$name: the dark one has colors of its own", light.readText() == dark.readText())
        }
        // nothing is left without a pair: an icon added by hand would be bright on the dark theme
        val single = directory.listFiles { file -> file.extension == "svg" && !file.name.endsWith("_dark.svg") }!!
            .map { it.nameWithoutExtension }.filter { !File(directory, "${it}_dark.svg").isFile }
        assertEquals(emptyList<String>(), single)
    }

    fun testIconsOfTheHolderAreThere() {
        val icons = DotNetIcons::class.java.declaredFields.filter { javax.swing.Icon::class.java.isAssignableFrom(it.type) }
        assertTrue(icons.size >= drawn.size)
        for (field in icons) {
            val icon = field.get(null) as javax.swing.Icon
            assertEquals(field.name, 16, icon.iconWidth)
            assertEquals(field.name, 16, icon.iconHeight)
        }
    }

    fun testPropertiesFolderOfAProject() {
        myFixture.addFileToProject("icons/App/App.csproj", """<Project Sdk="Microsoft.NET.Sdk"/>""")
        val properties = myFixture.addFileToProject("icons/App/Properties/launchSettings.json", "{}").virtualFile.parent
        val nested = myFixture.addFileToProject("icons/App/Models/Properties/Size.cs", "class Size { }").virtualFile.parent
        val basic = myFixture.addFileToProject("icons/Basic/Basic.vbproj", """<Project Sdk="Microsoft.NET.Sdk"/>""").virtualFile.parent
        val myProject = myFixture.addFileToProject("icons/Basic/My Project/Application.myapp", "").virtualFile.parent
        val models = nested.parent

        assertTrue(DotNetDirectoryIconProvider.isPropertiesFolder(properties))
        assertTrue(DotNetDirectoryIconProvider.isPropertiesFolder(myProject))
        assertFalse("not right in the project", DotNetDirectoryIconProvider.isPropertiesFolder(nested))
        assertFalse(DotNetDirectoryIconProvider.isPropertiesFolder(models))
        assertFalse(DotNetDirectoryIconProvider.isPropertiesFolder(basic))

        val provider = DotNetDirectoryIconProvider()
        assertSame(DotNetIcons.PropertiesFolder, provider.getIcon(psiManager.findDirectory(properties)!!, 0))
        assertNull(provider.getIcon(psiManager.findDirectory(models)!!, 0))
        assertNull("a file", provider.getIcon(myFixture.findFileInTempDir("icons/App/App.csproj").let { psiManager.findFile(it)!! }, 0))
    }
}
