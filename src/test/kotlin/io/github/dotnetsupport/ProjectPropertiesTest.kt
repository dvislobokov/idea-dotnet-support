package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.msbuild.ProjectProperties
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.ProjectKey
import io.github.dotnetsupport.view.SolutionKey

class ProjectPropertiesTest : BasePlatformTestCase() {
    private fun csproj(name: String, text: String): XmlFile = myFixture.addFileToProject("$name/$name.csproj", text) as XmlFile

    fun testReadTakesTheLastUnconditionalValue() {
        val xml = csproj(
            "Read",
            """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup>
                <TargetFrameworks>net8.0;net9.0</TargetFrameworks>
                <Nullable>enable</Nullable>
                <OutputType>Library</OutputType>
              </PropertyGroup>
              <PropertyGroup Condition="'$(Configuration)' == 'Release'">
                <Nullable>disable</Nullable>
                <TreatWarningsAsErrors>true</TreatWarningsAsErrors>
              </PropertyGroup>
              <PropertyGroup>
                <OutputType>Exe</OutputType>
                <LangVersion Condition="'$(X)' == ''">preview</LangVersion>
              </PropertyGroup>
            </Project>
            """.trimIndent(),
        )
        val values = ProjectProperties.read(xml, listOf("Nullable", "OutputType", "TreatWarningsAsErrors", "LangVersion", "RootNamespace"))
        assertEquals(mapOf("Nullable" to "enable", "OutputType" to "Exe"), values)
        assertEquals(listOf("net8.0", "net9.0"), ProjectProperties.readTargetFrameworks(xml))
    }

    fun testWriteChangesAddsAndRemovesKeepingTheRestOfTheFile() {
        val xml = csproj(
            "Write",
            """
            <Project Sdk="Microsoft.NET.Sdk">

              <PropertyGroup>
                <TargetFramework>net9.0</TargetFramework>
                <Nullable>enable</Nullable>
                <ImplicitUsings>enable</ImplicitUsings>
              </PropertyGroup>

              <ItemGroup>
                <PackageReference Include="Serilog" Version="4.0.0" />
              </ItemGroup>

            </Project>
            """.trimIndent(),
        )
        WriteCommandAction.runWriteCommandAction(project) {
            ProjectProperties.write(xml, mapOf("Nullable" to "disable", "ImplicitUsings" to "", "LangVersion" to "preview", "TreatWarningsAsErrors" to "true"))
            ProjectProperties.writeTargetFrameworks(xml, listOf("net9.0", "net10.0"))
        }
        assertEquals(
            """
            <Project Sdk="Microsoft.NET.Sdk">

              <PropertyGroup>
                <Nullable>disable</Nullable>
                <LangVersion>preview</LangVersion>
                <TreatWarningsAsErrors>true</TreatWarningsAsErrors>
                <TargetFrameworks>net9.0;net10.0</TargetFrameworks>
              </PropertyGroup>

              <ItemGroup>
                <PackageReference Include="Serilog" Version="4.0.0" />
              </ItemGroup>

            </Project>
            """.trimIndent(),
            xml.text,
        )

        // back to a single framework: TargetFrameworks goes, TargetFramework comes
        WriteCommandAction.runWriteCommandAction(project) { ProjectProperties.writeTargetFrameworks(xml, listOf("net10.0")) }
        assertEquals(listOf("net10.0"), ProjectProperties.readTargetFrameworks(xml))
        assertFalse(xml.text.contains("TargetFrameworks"))
    }

    fun testWriteCreatesAPropertyGroupWhenThereIsNone() {
        val xml = csproj("Bare", """<Project Sdk="Microsoft.NET.Sdk">${"\n"}  <ItemGroup />${"\n"}</Project>""")
        WriteCommandAction.runWriteCommandAction(project) { ProjectProperties.write(xml, mapOf("OutputType" to "Exe")) }
        assertEquals(mapOf("OutputType" to "Exe"), ProjectProperties.read(xml, listOf("OutputType")))
        // before the items, where the SDK convention puts it
        assertTrue(xml.text.indexOf("<PropertyGroup>") < xml.text.indexOf("<ItemGroup"))
    }

    fun testPropertiesActionIsForProjectNodesOnly() {
        myFixture.addFileToProject("Props/Props.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        val sln = myFixture.addFileToProject("Props.slnx", """<Solution><Project Path="Props/Props.csproj" /></Solution>""").virtualFile
        val slnProject = SolutionService.getInstance(project).solution(sln).allProjects.single()
        val action = ActionManager.getInstance().getAction("DotNet.ProjectProperties")

        fun visible(key: Any): Boolean {
            val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.SELECTED_ITEMS, arrayOf(key)).build()
            val event = AnActionEvent.createEvent(action, context, null, "ProjectViewPopup", ActionUiKind.POPUP, null)
            action.update(event)
            return event.presentation.isEnabledAndVisible
        }
        assertTrue(visible(ProjectKey(sln, slnProject)))
        assertFalse(visible(SolutionKey(sln)))
    }
}
