package io.github.dotnetsupport

import io.github.dotnetsupport.msbuild.MsBuildProject
import io.github.dotnetsupport.msbuild.PackageReference
import io.github.dotnetsupport.solution.SolutionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ParsersTest {
    @Test
    fun `sln with nested folders and solution items`() {
        val solution = SolutionParser.parseSln(
            """
            Microsoft Visual Studio Solution File, Format Version 12.00
            Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "src", "src", "{aaaaaaaa-0000-0000-0000-000000000001}"
            EndProject
            Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "Solution Items", "Solution Items", "{AAAAAAAA-0000-0000-0000-000000000002}"
            	ProjectSection(SolutionItems) = preProject
            		README.md = README.md
            		build\ci.yml = build\ci.yml
            	EndProjectSection
            EndProject
            Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "App", "src\App\App.csproj", "{BBBBBBBB-0000-0000-0000-000000000001}"
            EndProject
            Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "Tests", "tests\Tests\Tests.csproj", "{BBBBBBBB-0000-0000-0000-000000000002}"
            EndProject
            Global
            	GlobalSection(NestedProjects) = preSolution
            		{BBBBBBBB-0000-0000-0000-000000000001} = {AAAAAAAA-0000-0000-0000-000000000001}
            	EndGlobalSection
            EndGlobal
            """.trimIndent()
        )

        assertEquals(listOf("src", "Solution Items"), solution.root.folders.map { it.name })
        assertEquals(listOf("Tests"), solution.root.projects.map { it.name })
        assertEquals(listOf("tests/Tests/Tests.csproj"), solution.root.projects.map { it.path })

        val src = solution.root.folders[0]
        assertEquals(listOf("src/App/App.csproj"), src.projects.map { it.path })
        assertSame(src, solution.findFolder("AAAAAAAA-0000-0000-0000-000000000001"))
        assertEquals(listOf("README.md", "build/ci.yml"), solution.root.folders[1].files)
        assertEquals(2, solution.allProjects.size)
    }

    @Test
    fun `slnx with nested folders`() {
        val solution = SolutionParser.parseSlnx(
            """
            <Solution>
              <Folder Name="/src/">
                <Project Path="src/App/App.csproj" />
              </Folder>
              <Folder Name="/src/libs/">
                <File Path="src/libs/notes.md" />
                <Project Path="src\libs\Core\Core.fsproj" />
              </Folder>
              <Project Path="Tool/Tool.csproj" />
            </Solution>
            """.trimIndent()
        )

        assertEquals(listOf("Tool"), solution.root.projects.map { it.name })
        val src = solution.root.folders.single()
        assertEquals(listOf("App"), src.projects.map { it.name })
        val libs = src.folders.single()
        assertEquals("/src/libs/", libs.id)
        assertEquals(listOf("src/libs/Core/Core.fsproj"), libs.projects.map { it.path })
        assertEquals(listOf("src/libs/notes.md"), libs.files)
    }

    @Test
    fun `broken slnx is an empty solution`() {
        assertEquals(0, SolutionParser.parseSlnx("<Solution><Folder").allProjects.size)
    }

    @Test
    fun `sdk style project`() {
        val project = MsBuildProject.parse(
            """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup>
                <TargetFrameworks>net8.0;net9.0</TargetFrameworks>
              </PropertyGroup>
              <ItemGroup>
                <PackageReference Include="Serilog" Version="4.0.0" />
                <PackageReference Include="Dapper">
                  <Version>2.1.35</Version>
                </PackageReference>
                <PackageReference Include="Central.Package" />
                <ProjectReference Include="..\Core\Core.csproj" />
              </ItemGroup>
            </Project>
            """.trimIndent()
        )

        assertEquals(listOf("net8.0", "net9.0"), project.targetFrameworks)
        assertEquals(
            listOf(PackageReference("Serilog", "4.0.0"), PackageReference("Dapper", "2.1.35"), PackageReference("Central.Package", null)),
            project.packages,
        )
        assertEquals(listOf("../Core/Core.csproj"), project.projectReferences)
    }

    @Test
    fun `old style project with namespace`() {
        val project = MsBuildProject.parse(
            """
            <Project ToolsVersion="15.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
              <PropertyGroup>
                <TargetFrameworkVersion>v4.7.2</TargetFrameworkVersion>
              </PropertyGroup>
              <ItemGroup>
                <Reference Include="System.Xml" />
                <Reference Include="Newtonsoft.Json, Version=13.0.0.0, Culture=neutral" />
              </ItemGroup>
            </Project>
            """.trimIndent()
        )

        assertEquals(listOf("net472"), project.targetFrameworks)
        assertEquals(listOf("System.Xml", "Newtonsoft.Json"), project.assemblies)
    }

    @Test
    fun `central package versions`() {
        val props = MsBuildProject.parse("""<Project><ItemGroup><PackageVersion Include="Serilog" Version="4.1.0" /></ItemGroup></Project>""")
        assertEquals(mapOf("serilog" to "4.1.0"), props.packageVersions)
    }
}
