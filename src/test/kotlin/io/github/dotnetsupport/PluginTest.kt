package io.github.dotnetsupport

import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.psi.TokenType
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.IconUtil
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lang.CSharpLexer
import io.github.dotnetsupport.msbuild.DotNetXmlFileType
import io.github.dotnetsupport.msbuild.MsBuildFileType
import io.github.dotnetsupport.solution.SolutionFileType
import io.github.dotnetsupport.solution.SolutionXmlFileType
import io.github.dotnetsupport.view.SolutionKey
import io.github.dotnetsupport.view.SolutionNode

class PluginTest : BasePlatformTestCase() {
    fun testFileTypes() {
        assertSame(CSharpFileType, myFixture.configureByText("A.cs", "class A {}").fileType)
        assertSame(SolutionFileType, myFixture.configureByText("A.sln", "").fileType)

        for ((name, type) in listOf("A.csproj" to MsBuildFileType, "A.slnx" to SolutionXmlFileType, "App.config" to DotNetXmlFileType)) {
            val file = myFixture.configureByText(name, "<Project/>")
            assertSame(name, type, file.virtualFile.fileType)
            assertTrue(name, file is XmlFile)
        }
    }

    fun testFileIcons() {
        assertSame(DotNetIcons.ProjectFSharp, DotNetIcons.forFile("Core.fsproj"))
        assertSame(DotNetIcons.SettingsJson, DotNetIcons.forFile("appsettings.Development.json"))
        assertSame(DotNetIcons.NuGet, DotNetIcons.forFile("Directory.Packages.props"))
        assertSame(DotNetIcons.MsBuild, DotNetIcons.forFile("Directory.Build.props"))
        assertNull(DotNetIcons.forFile("package.json"))

        // The provider is registered and wins over the file type icon.
        val razor = myFixture.addFileToProject("Pages/Index.razor", "").virtualFile
        assertSame(DotNetIcons.Razor, IconUtil.getIcon(razor, 0, project))
    }

    fun testLexer() {
        assertEquals(
            listOf(
                "PREPROCESSOR:#if DEBUG",
                "DOC_COMMENT:/// <summary>doc</summary>",
                "KEYWORD:var", "IDENTIFIER:s", "OPERATOR:=", "STRING:\$\"a {x + \"}\"} b\"", "SEMICOLON:;", "LINE_COMMENT:// tail",
                "IDENTIFIER:p", "OPERATOR:=", "STRING:@\"c:\\\"\"x\"", "SEMICOLON:;",
                "IDENTIFIER:r", "OPERATOR:=", "STRING:\"\"\"raw \"q\" text\"\"\"", "SEMICOLON:;",
                "IDENTIFIER:c", "OPERATOR:=", "CHAR:'\\''", "SEMICOLON:;",
                "IDENTIFIER:n", "OPERATOR:=", "NUMBER:1.5e+3f", "OPERATOR:+", "NUMBER:0xFF", "OPERATOR:+", "NUMBER:1", "DOT:.", "IDENTIFIER:ToString", "LPAREN:(", "RPAREN:)", "SEMICOLON:;",
                "IDENTIFIER:@class", "BLOCK_COMMENT:/* open",
            ),
            tokens(
                """
                #if DEBUG
                /// <summary>doc</summary>
                var s = $"a {x + "}"} b"; // tail
                p = @"c:\""x";
                r = ""${'"'}raw "q" text""${'"'};
                c = '\'';
                n = 1.5e+3f + 0xFF + 1.ToString();
                @class /* open
                """.trimIndent()
            ),
        )
    }

    fun testUnterminatedStringStopsAtLineEnd() {
        assertEquals(listOf("STRING:\"abc", "KEYWORD:class"), tokens("\"abc\nclass"))
    }

    fun testSolutionTree() {
        myFixture.addFileToProject(
            "src/App/App.csproj",
            """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup>
              <ItemGroup>
                <PackageReference Include="Serilog" />
                <ProjectReference Include="..\Core\Core.csproj" />
              </ItemGroup>
            </Project>
            """.trimIndent(),
        )
        myFixture.addFileToProject("src/App/Program.cs", "class Program {}")
        myFixture.addFileToProject("src/App/bin/Debug/App.txt", "")
        myFixture.addFileToProject("src/App/obj/project.assets.json", "{}")
        myFixture.addFileToProject("src/Core/Core.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        myFixture.addFileToProject("Directory.Packages.props", """<Project><ItemGroup><PackageVersion Include="Serilog" Version="4.1.0"/></ItemGroup></Project>""")
        myFixture.addFileToProject("README.md", "")
        val sln = myFixture.addFileToProject(
            "Demo.sln",
            """
            Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "src", "src", "{A0000000-0000-0000-0000-000000000001}"
            EndProject
            Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "Items", "Items", "{A0000000-0000-0000-0000-000000000002}"
            	ProjectSection(SolutionItems) = preProject
            		README.md = README.md
            	EndProjectSection
            EndProject
            Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "App", "src\App\App.csproj", "{B0000000-0000-0000-0000-000000000001}"
            EndProject
            Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "Core", "src\Core\Core.csproj", "{B0000000-0000-0000-0000-000000000002}"
            EndProject
            Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "Gone", "src\Gone\Gone.csproj", "{B0000000-0000-0000-0000-000000000003}"
            EndProject
            Global
            	GlobalSection(NestedProjects) = preSolution
            		{B0000000-0000-0000-0000-000000000001} = {A0000000-0000-0000-0000-000000000001}
            		{B0000000-0000-0000-0000-000000000002} = {A0000000-0000-0000-0000-000000000001}
            	EndGlobalSection
            EndGlobal
            """.trimIndent(),
        ).virtualFile

        val solution = SolutionNode(project, SolutionKey(sln), ViewSettings.DEFAULT)
        assertEquals("Demo (3 projects)", solution.describe())
        assertEquals(listOf("src", "Items", "Gone (not found: src/Gone/Gone.csproj)"), solution.children.map { it.describe() })

        val (src, items) = solution.children.toList()
        assertEquals(listOf("README.md"), items.children.map { it.describe() })
        assertEquals(listOf("App (net8.0)", "Core"), src.children.map { it.describe() })

        val app = src.children.first()
        assertEquals(listOf("Dependencies", "Program.cs"), app.children.map { it.describe() })
        val groups = app.children.first().children.toList()
        assertEquals(listOf("Packages", "Projects"), groups.map { it.describe() })
        assertEquals(listOf("Serilog (4.1.0)"), groups[0].children.map { it.describe() })
        assertEquals(listOf("Core"), groups[1].children.map { it.describe() })
        assertTrue(groups[1].children.single().canNavigate())
    }

    private fun AbstractTreeNode<*>.describe(): String {
        update()
        val text = presentation.presentableText ?: name
        return presentation.locationString?.let { "$text ($it)" } ?: text.orEmpty()
    }

    private fun tokens(text: String): List<String> {
        val lexer = CSharpLexer()
        lexer.start(text)
        return buildList {
            while (lexer.tokenType != null) {
                if (lexer.tokenType != TokenType.WHITE_SPACE) add("${lexer.tokenType.toString().removePrefix("CSharp:")}:${lexer.tokenText}")
                lexer.advance()
            }
        }
    }
}
