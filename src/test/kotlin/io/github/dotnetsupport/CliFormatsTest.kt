package io.github.dotnetsupport

import io.github.dotnetsupport.build.MsBuildOutputParser
import io.github.dotnetsupport.newproject.DotNetTemplate
import io.github.dotnetsupport.newproject.DotNetTemplateSettings
import io.github.dotnetsupport.newproject.DotNetTemplates
import io.github.dotnetsupport.run.LaunchSettings
import io.github.dotnetsupport.solution.SolutionEditor
import io.github.dotnetsupport.solution.SolutionParser
import io.github.dotnetsupport.templates.CSharpNamespaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliFormatsTest {
    @Test
    fun `compiler error with location and project`() {
        val line = """C:\src\App\Program.cs(12,5): error CS1002: ; expected [C:\src\App\App.csproj]"""
        val message = MsBuildOutputParser.parseLine(line)!!
        assertTrue(message.isError)
        assertEquals("CS1002", message.code)
        assertEquals("; expected", message.text)
        assertEquals("""C:\src\App\Program.cs""", message.file)
        assertEquals(12 to 5, message.line to message.column)
        assertEquals("""C:\src\App\App.csproj""", message.projectFile)
        assertEquals("""C:\src\App\Program.cs""", line.substring(message.fileRange!!))
    }

    @Test
    fun `warning with relative path, node prefix and full span`() {
        val message = MsBuildOutputParser.parseLine("""  2>Models/User.cs(3,14,3,18): warning CS8618: Non-nullable property 'Name' [/home/me/App/App.csproj]""")!!
        assertFalse(message.isError)
        assertEquals("Models/User.cs", message.file)
        assertEquals(3 to 14, message.line to message.column)
        assertEquals(File("/home/me/App/Models/User.cs"), message.resolveFile())
    }

    @Test
    fun `messages without a position`() {
        val nuget = MsBuildOutputParser.parseLine("""/src/App/App.csproj : error NU1101: Unable to find package Foo. [/src/App.sln]""")!!
        assertEquals("/src/App/App.csproj", nuget.file)
        assertEquals(0, nuget.line)
        assertEquals("NU1101", nuget.code)

        val tool = MsBuildOutputParser.parseLine("MSBUILD : error MSB1009: Project file does not exist.")!!
        assertNull(tool.file)
        assertEquals("Project file does not exist.", tool.text)
    }

    @Test
    fun `ordinary output is not a diagnostic`() {
        assertNull(MsBuildOutputParser.parseLine("  App -> C:\\src\\App\\bin\\Debug\\net8.0\\App.dll"))
        assertNull(MsBuildOutputParser.parseLine("Build succeeded."))
        assertNull(MsBuildOutputParser.parseLine("    0 Warning(s)"))
        assertNull(MsBuildOutputParser.parseLine("info: Microsoft.Hosting.Lifetime[14] Now listening on: http://localhost:5000"))
    }

    @Test
    fun `localized template list`() {
        val templates = DotNetTemplates.parseList(
            """
            Эти шаблоны соответствуют входным данным: --type=project.

            Имя шаблона                 Короткое имя   Язык        Теги
            --------------------------  -------------  ----------  -----------------
            Worker Service              worker         [C#],F#     Common/Worker/Web
            Библиотека классов          classlib       [C#],F#,VB  Common/Library
            ASP.NET Core Web App        webapp,razor   [C#]        Web/MVC/Razor Pages
            Solution File               sln,solution               Solution

            """.trimIndent()
        )
        assertEquals(
            listOf(
                DotNetTemplate("Worker Service", "worker", listOf("C#", "F#"), "C#"),
                DotNetTemplate("Библиотека классов", "classlib", listOf("C#", "F#", "VB"), "C#"),
                DotNetTemplate("ASP.NET Core Web App", "webapp", listOf("C#"), "C#"),
                DotNetTemplate("Solution File", "sln", emptyList(), null),
            ),
            templates,
        )
        assertEquals(emptyList<DotNetTemplate>(), DotNetTemplates.parseList("error: something went wrong"))
    }

    @Test
    fun `sdk list and dotnet new arguments`() {
        assertEquals(
            listOf("net10.0", "net9.0"),
            DotNetTemplates.parseSdkList("9.0.301 [C:\\dotnet\\sdk]\n9.0.100 [C:\\dotnet\\sdk]\n10.0.401 [C:\\dotnet\\sdk]\n"),
        )

        val fsharp = DotNetTemplateSettings(DotNetTemplates.BUILT_IN.first { it.shortName == "classlib" }, "F#", "net9.0")
        assertEquals(
            listOf("new", "classlib", "-n", "Core", "-o", "src/Core", "-lang", "F#", "-f", "net9.0"),
            fsharp.newArguments("Core", "src/Core"),
        )
        assertEquals("fsproj", fsharp.projectExtension)
    }

    @Test
    fun `launch profiles`() {
        val json = """
            {
              // comments are allowed here
              "profiles": {
                "http": { "commandName": "Project", "applicationUrl": "http://localhost:5000" },
                "IIS Express": { "commandName": "IISExpress" },
                "https": { "commandName": "Project" },
              }
            }
        """.trimIndent()
        assertEquals(listOf("http", "https"), LaunchSettings.projectProfiles(json))
        assertEquals(emptyList<String>(), LaunchSettings.projectProfiles("{ broken"))
    }

    @Test
    fun `add nested sln folder`() {
        val parent = "A0000000-0000-0000-0000-000000000001"
        val sln = listOf(
            "Microsoft Visual Studio Solution File, Format Version 12.00",
            "Project(\"{2150E333-8FDC-42A3-9474-1A3956D46DE8}\") = \"src\", \"src\", \"{$parent}\"",
            "EndProject",
            "Global",
            "\tGlobalSection(SolutionProperties) = preSolution",
            "\t\tHideSolutionNode = FALSE",
            "\tEndGlobalSection",
            "EndGlobal",
            "",
        ).joinToString("\r\n")

        val nested = SolutionEditor.addSlnFolder(sln, "libs", parent, "A0000000-0000-0000-0000-000000000002")
        assertFalse("line separators are kept", nested.replace("\r\n", "").contains('\n'))
        assertEquals("src/libs", SolutionParser.parseSln(nested).folderPath("A0000000-0000-0000-0000-000000000002"))

        // the NestedProjects section exists now and is reused
        val deeper = SolutionEditor.addSlnFolder(nested, "core", "A0000000-0000-0000-0000-000000000002", "A0000000-0000-0000-0000-000000000003")
        assertEquals(1, Regex("GlobalSection\\(NestedProjects\\)").findAll(deeper).count())
        assertEquals("src/libs/core", SolutionParser.parseSln(deeper).folderPath("A0000000-0000-0000-0000-000000000003"))

        val topLevel = SolutionEditor.addSlnFolder(sln, "tests", null, "A0000000-0000-0000-0000-000000000004")
        assertEquals(listOf("src", "tests"), SolutionParser.parseSln(topLevel).root.folders.map { it.name })
    }

    @Test
    fun `add slnx folder`() {
        val slnx = "<Solution>\n  <Folder Name=\"/src/\">\n    <Project Path=\"src/App/App.csproj\" />\n  </Folder>\n</Solution>\n"
        val nested = SolutionParser.parseSlnx(SolutionEditor.addSlnxFolder(slnx, "libs", "/src/"))
        assertEquals(listOf("libs"), nested.root.folders.single().folders.map { it.name })
        assertEquals(1, nested.allProjects.size)

        val empty = SolutionParser.parseSlnx(SolutionEditor.addSlnxFolder("<Solution />", "a&b", null))
        assertEquals(listOf("a&b"), empty.root.folders.map { it.name })
    }

    @Test
    fun `namespace style`() {
        assertEquals(true, CSharpNamespaces.namespaceStyle("[*.cs]\ncsharp_style_namespace_declarations = file_scoped:warning\n"))
        assertEquals(false, CSharpNamespaces.namespaceStyle("csharp_style_namespace_declarations=block_scoped"))
        assertNull(CSharpNamespaces.namespaceStyle("indent_size = 4"))

        assertTrue(listOf("net48", "net472", "netstandard2.0", "netcoreapp3.1", "net5.0").all(CSharpNamespaces::isLegacyFramework))
        assertFalse(listOf("net6.0", "net8.0-windows", "net10.0").any(CSharpNamespaces::isLegacyFramework))
    }
}
