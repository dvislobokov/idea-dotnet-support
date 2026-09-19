package io.github.dotnetsupport

import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.ide.IdeView
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.fileTemplates.FileTemplateUtil
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.IconUtil
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lang.CSharpLexer
import io.github.dotnetsupport.msbuild.DotNetXmlFileType
import io.github.dotnetsupport.msbuild.MsBuildFileType
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.run.DotNetRunConfigurationGenerator
import io.github.dotnetsupport.run.LaunchSettings
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.solution.SolutionFileType
import io.github.dotnetsupport.solution.SolutionXmlFileType
import io.github.dotnetsupport.view.SolutionKey
import io.github.dotnetsupport.view.SolutionNode
import io.github.dotnetsupport.view.resolveFile

class PluginTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            // the light project is shared between tests
            val runManager = RunManager.getInstance(project)
            runManager.allSettings.filter { it.configuration is DotNetRunConfiguration }.forEach(runManager::removeConfiguration)
            PropertiesComponent.getInstance(project).unsetValue("dotnet.generated.run.configurations")
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

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
        // the project node is what gets rebuilt when something is created right in the project directory
        val appDirectory = sln.parent.findFileByRelativePath("src/App")!!
        assertTrue(app.canRepresent(appDirectory))
        assertTrue(app.canRepresent(psiManager.findDirectory(appDirectory)))
        assertFalse(app.canRepresent(appDirectory.parent))

        myFixture.addFileToProject("src/App/Models/keep.txt", "")
        myFixture.tempDirFixture.findOrCreateDir("src/App/Empty") // a folder that has just been created
        assertEquals(listOf("Dependencies", "Empty", "Models", "Program.cs"), app.children.map { it.describe() }.sorted())
        val groups = app.children.first().children.toList()
        assertEquals(listOf("Packages", "Projects"), groups.map { it.describe() })
        assertEquals(listOf("Serilog (4.1.0)"), groups[0].children.map { it.describe() })
        assertEquals(listOf("Core"), groups[1].children.map { it.describe() })
        assertTrue(groups[1].children.single().canNavigate())
    }

    fun testNewTypeTemplates() {
        myFixture.addFileToProject(
            "Modern/Modern.csproj",
            """<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><TargetFramework>net8.0</TargetFramework><RootNamespace>Acme.App</RootNamespace></PropertyGroup></Project>""",
        )
        myFixture.addFileToProject("Legacy App/Legacy App.csproj", """<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><TargetFramework>net48</TargetFramework></PropertyGroup></Project>""")
        val modern = myFixture.addFileToProject("Modern/Models/Dto/keep.txt", "").containingDirectory
        val legacy = myFixture.addFileToProject("Legacy App/keep.txt", "").containingDirectory
        val templates = FileTemplateManager.getInstance(project)

        val user = FileTemplateUtil.createFromTemplate(templates.getInternalTemplate("CSharp Class"), "User", null, modern)
        assertEquals("User.cs", (user as PsiFile).name)
        assertEquals("namespace Acme.App.Models.Dto;\n\npublic class User\n{\n}\n", user.text)

        val point = FileTemplateUtil.createFromTemplate(templates.getInternalTemplate("CSharp Record"), "Point", null, modern)
        assertEquals("namespace Acme.App.Models.Dto;\n\npublic record Point();\n", point.text)

        val service = FileTemplateUtil.createFromTemplate(templates.getInternalTemplate("CSharp Interface"), "IService", null, legacy)
        assertEquals("namespace Legacy_App\n{\n    public interface IService\n    {\n    }\n}\n", service.text)
    }

    fun testNewCSharpFileAction() {
        myFixture.addFileToProject("App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        val inProject = myFixture.addFileToProject("App/Models/keep.txt", "").containingDirectory!!
        val outside = myFixture.addFileToProject("docs/keep.txt", "").containingDirectory!!

        val actionManager = ActionManager.getInstance()
        val action = actionManager.getAction("DotNet.NewCSharpFile")
        val newGroup = actionManager.getAction("NewGroup") as DefaultActionGroup
        assertTrue(newGroup.getChildActionsOrStubs().any { actionManager.getId(it) == "DotNet.NewCSharpFile" })

        fun isVisibleIn(directory: PsiDirectory): Boolean {
            val view = object : IdeView {
                override fun getDirectories(): Array<PsiDirectory> = arrayOf(directory)
                override fun getOrChooseDirectory(): PsiDirectory = directory
            }
            val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(LangDataKeys.IDE_VIEW, view).build()
            val event = AnActionEvent.createEvent(action, context, null, "ProjectViewPopup", ActionUiKind.POPUP, null)
            action.update(event)
            return event.presentation.isEnabledAndVisible
        }
        assertTrue(isVisibleIn(inProject))
        assertFalse(isVisibleIn(outside))
    }

    fun testGeneratedRunConfigurations() {
        myFixture.addFileToProject("Web/Web.csproj", """<Project Sdk="Microsoft.NET.Sdk.Web"/>""")
        myFixture.addFileToProject(
            "Web/Properties/launchSettings.json",
            """{ "profiles": { "http": { "commandName": "Project" }, "https": { "commandName": "Project" }, "IIS": { "commandName": "IISExpress" } } }""",
        )
        myFixture.addFileToProject("Tool/Tool.csproj", """<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType></PropertyGroup></Project>""")
        myFixture.addFileToProject("Lib/Lib.csproj", """<Project Sdk="Microsoft.NET.Sdk"/>""")
        myFixture.addFileToProject(
            "Tests/Tests.csproj",
            """<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType></PropertyGroup><ItemGroup><PackageReference Include="xunit.v3" Version="1.0.0"/></ItemGroup></Project>""",
        )
        val sln = myFixture.addFileToProject(
            "All.slnx",
            """<Solution><Project Path="Web/Web.csproj"/><Project Path="Tool/Tool.csproj"/><Project Path="Lib/Lib.csproj"/><Project Path="Tests/Tests.csproj"/></Solution>""",
        ).virtualFile

        // solutionFiles() looks at the project directory, which is not where the light fixture puts files
        val solutions = SolutionService.getInstance(project)
        val generator = DotNetRunConfigurationGenerator.getInstance(project)
        val targets = solutions.solution(sln).allProjects
            .map { it.name to it.resolveFile(sln)!! }
            .filter { (_, file) -> solutions.msBuildProject(file).let { it.isRunnable && !it.isTestProject } }
            .flatMap { (name, file) ->
                LaunchSettings.projectProfiles(file).ifEmpty { listOf(null) }
                    .map { DotNetRunConfigurationGenerator.Target(if (it == null) name else "$name: $it", file.path, it) }
            }
        assertEquals(listOf("Web: http", "Web: https", "Tool"), targets.map { it.name })

        val runManager = RunManager.getInstance(project)
        fun names() = runManager.allSettings.filter { it.configuration is DotNetRunConfiguration }.map { it.name }.sorted()
        generator.register(targets)
        assertEquals(listOf("Tool", "Web: http", "Web: https"), names())
        assertEquals("Web: http", runManager.selectedConfiguration?.name)

        // a deleted configuration is not generated again, and nothing is duplicated
        runManager.removeConfiguration(runManager.allSettings.first { it.name == "Tool" })
        generator.register(targets)
        assertEquals(listOf("Web: http", "Web: https"), names())
    }

    fun testRunConfiguration() {
        myFixture.addFileToProject("Web/Web.csproj", """<Project Sdk="Microsoft.NET.Sdk.Web"/>""")
        myFixture.addFileToProject("Lib/Lib.csproj", """<Project Sdk="Microsoft.NET.Sdk"/>""")
        myFixture.addFileToProject(
            "Tests/Tests.csproj",
            """<Project Sdk="Microsoft.NET.Sdk"><ItemGroup><PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.0.0"/></ItemGroup></Project>""",
        )
        val program = myFixture.addFileToProject("Web/Program.cs", "class Program\n{\n    public static async Task Main(string[] args) { }\n    void Main2() { Main(null); }\n}")
        val library = myFixture.addFileToProject("Lib/Class1.cs", "class Class1 {}")
        val test = myFixture.addFileToProject("Tests/UnitTest1.cs", "class UnitTest1 {}")

        fun fromContext(file: PsiFile) = ConfigurationContext(file).configuration?.configuration as? DotNetRunConfiguration

        val web = fromContext(program)!!
        assertEquals("Web", web.name)
        assertEquals(DotNetCommand.RUN, web.options.command)
        assertEquals(DotNetCommand.TEST, fromContext(test)!!.options.command)
        assertNull(fromContext(library))

        if (DotNetCli.findExecutable() != null) {
            web.options.launchProfile = "http"
            web.options.programArguments = "--port 5000 \"two words\""
            val path = web.options.projectPath!!
            assertEquals(
                listOf("run", "--project", path, "--launch-profile", "http", "--", "--port", "5000", "two words"),
                web.buildCommandLine().parametersList.list,
            )
        }

        // ▶ only at the declaration of the entry point, not at calls
        myFixture.configureFromExistingVirtualFile(program.virtualFile)
        assertEquals(1, myFixture.findAllGutters().size)
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
