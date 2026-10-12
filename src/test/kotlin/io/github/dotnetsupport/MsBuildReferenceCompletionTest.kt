package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.msbuild.MsBuildReferences
import io.github.dotnetsupport.msbuild.MsBuildReferences.Kind

class MsBuildReferenceCompletionTest : BasePlatformTestCase() {
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testContextOfAReference() {
        assertEquals(MsBuildReferences.Context(Kind.PROPERTY, "Mod"), MsBuildReferences.contextAt("'$(Mod"))
        assertEquals(MsBuildReferences.Context(Kind.PROPERTY, ""), MsBuildReferences.contextAt("a;$("))
        assertEquals(MsBuildReferences.Context(Kind.ITEM, "Comp"), MsBuildReferences.contextAt("@(Comp"))
        assertEquals(MsBuildReferences.Context(Kind.METADATA, "Fi"), MsBuildReferences.contextAt("%(Fi"))
        assertEquals(MsBuildReferences.Context(Kind.METADATA, "Fi", "Compile"), MsBuildReferences.contextAt("%(Compile.Fi"))
        // closed, a property function, plain text
        assertNull(MsBuildReferences.contextAt("$(Mod)"))
        assertNull(MsBuildReferences.contextAt("$([System.IO.Path]::Comb"))
        assertNull(MsBuildReferences.contextAt("net8.0"))
    }

    fun testDeclaredNames() {
        val d = MsBuildReferences.declared(listOf("""<Project><PropertyGroup><Mine>1</Mine></PropertyGroup><ItemGroup><Thing Include="a" Tag="x"><Extra>1</Extra></Thing></ItemGroup></Project>"""))
        assertEquals(setOf("Mine"), d.properties)
        assertEquals(setOf("Thing"), d.items)
        assertEquals(setOf("Tag", "Extra"), d.metadata["thing"])
    }

    private fun complete(text: String): List<String> {
        myFixture.configureByText("App.csproj", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testPropertiesOfTheFileAndWellKnownOnes() {
        val names = complete("""<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><MyOwn>1</MyOwn><Out>${'$'}(MSBuildProj<caret></Out></PropertyGroup></Project>""")
        assertTrue(names.toString(), "MSBuildProjectDirectory" in names && "MSBuildProjectName" in names)
        val own = complete("""<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><MyOwn>1</MyOwn><Out>${'$'}(myo<caret></Out></PropertyGroup></Project>""")
        assertEquals(listOf("MyOwn"), own)
        // inside an attribute, and the parenthesis is closed
        val inCondition = complete("""<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup Condition="'${'$'}(Configurat<caret>' == 'Debug'"/></Project>""")
        assertTrue(inCondition.toString(), "Configuration" in inCondition)
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == "Configuration" }
        myFixture.finishLookup(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR)
        assertTrue(myFixture.editor.document.text, "'\$(Configuration)'" in myFixture.editor.document.text)
    }

    fun testItemTypesAndMetadata() {
        val items = complete("""<Project Sdk="Microsoft.NET.Sdk"><ItemGroup><Mine Include="a"/></ItemGroup><Target Name="T"><Message Text="@(Mi<caret>"/></Target></Project>""")
        assertEquals("Mine", items.first())
        val compile = complete("""<Project Sdk="Microsoft.NET.Sdk"><ItemGroup><Compile Include="a.cs" Link="%(Filen<caret>"/></ItemGroup></Project>""")
        assertTrue(compile.toString(), "Filename" in compile)
        val qualified = complete("""<Project Sdk="Microsoft.NET.Sdk"><ItemGroup><Mine Include="a" Tag="x"/></ItemGroup><Target Name="T"><Message Text="%(Mine.Ta<caret>"/></Target></Project>""")
        assertEquals("Tag", qualified.first())
    }

    fun testImportedFilesDeclareProperties() {
        myFixture.addFileToProject("Common.props", """<Project><PropertyGroup><FromImport>1</FromImport></PropertyGroup></Project>""")
        myFixture.addFileToProject("Directory.Build.props", """<Project><PropertyGroup><FromDirectory>1</FromDirectory></PropertyGroup></Project>""")
        val names = complete("""<Project Sdk="Microsoft.NET.Sdk"><Import Project="Common.props"/><PropertyGroup><A>${'$'}(From<caret></A></PropertyGroup></Project>""")
        assertEquals(setOf("FromImport", "FromDirectory"), names.toSet())
    }

    fun testImportPaths() {
        myFixture.addFileToProject("build/Shared.props", "<Project/>")
        myFixture.addFileToProject("build/readme.txt", "x")
        myFixture.addFileToProject("Lib/Lib.csproj", "<Project/>")
        val root = complete("""<Project><Import Project="<caret>"/></Project>""")
        assertTrue(root.toString(), "build\\" in root && "Lib\\" in root && "..\\" in root)
        val inside = complete("""<Project><Import Project="build\<caret>"/></Project>""")
        assertEquals(listOf("Shared.props"), inside)
        // after the placeholder of the file's own directory
        val own = complete("""<Project><Import Project="${'$'}(MSBuildThisFileDirectory)build/<caret>"/></Project>""")
        assertEquals(listOf("Shared.props"), own)
    }

    fun testProjectReferencePathsAreProjectsOnly() {
        myFixture.addFileToProject("Lib/Lib.csproj", "<Project/>")
        myFixture.addFileToProject("Lib/Shared.props", "<Project/>")
        val names = complete("""<Project><ItemGroup><ProjectReference Include="Lib\<caret>"/></ItemGroup></Project>""")
        assertEquals(listOf("Lib.csproj"), names)
        // other attributes and items have no paths
        assertTrue(complete("""<Project><ItemGroup><Compile Include="<caret>"/></ItemGroup></Project>""").isEmpty())
    }
}
