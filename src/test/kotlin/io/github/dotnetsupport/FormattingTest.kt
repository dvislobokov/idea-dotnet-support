package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.format.CSharpierCli
import io.github.dotnetsupport.format.CSharpierLocator
import io.github.dotnetsupport.format.CSharpierOutput
import io.github.dotnetsupport.format.CSharpierServer
import io.github.dotnetsupport.format.DotNetFormatRunner
import io.github.dotnetsupport.format.DotNetFormattingService
import io.github.dotnetsupport.format.DotNetFormattingSettings
import io.github.dotnetsupport.format.FormatResult
import io.github.dotnetsupport.format.FormatTargetAction
import io.github.dotnetsupport.format.FormatterChoice
import io.github.dotnetsupport.settings.DotNetSettings
import io.github.dotnetsupport.settings.DotNetSettingsConfigurable
import java.io.File

/** Command lines, outputs and answers are the real ones of CSharpier 1.3.0 and 0.30.6. */
class FormattingTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            DotNetFormattingSettings.getInstance(project).formatter = FormatterChoice.AUTO
            DotNetSettings.getInstance().setToolPath(DotNetTool.CSHARPIER, "")
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testCommandLinesOfBothGenerations() {
        // real directories: a working directory that does not exist is left out of the command line
        val repo = FileUtil.createTempDirectory("repo", null, true)
        val file = File(repo, "src/App/Orders.cs").apply { parentFile.mkdirs() }
        val modern = CSharpierCli(listOf("csharpier"), "1.3.0", null)
        assertFalse(modern.isLegacy)
        assertEquals(listOf("format", "--stdin-path", file.path), modern.formatStdin(file).parametersList.list)
        assertEquals(file.parentFile, modern.formatStdin(file).workDirectory)
        assertEquals(listOf("server"), modern.server(null).parametersList.list)
        assertEquals(listOf("check", file.parent), modern.check(file.parentFile).parametersList.list)
        assertEquals(listOf("format", file.parent), modern.format(file.parentFile).parametersList.list)

        // 0.x has no subcommands and no --stdin-path: the configuration is found from the working directory
        val legacy = CSharpierCli(listOf("dotnet-csharpier"), "0.30.6", null)
        assertTrue(legacy.isLegacy)
        assertEquals(emptyList<String>(), legacy.formatStdin(file).parametersList.list)
        assertEquals(file.parentFile, legacy.formatStdin(file).workDirectory)
        assertEquals(listOf("--server"), legacy.server(null).parametersList.list)
        assertEquals(listOf("--check", file.parent), legacy.check(file.parentFile).parametersList.list)
        assertEquals(listOf(file.parent), legacy.format(file.parentFile).parametersList.list)

        // a tool of the repository runs through `dotnet`, from where its manifest is visible
        val manifestDirectory = repo
        val local = CSharpierCli(listOf("dotnet", "csharpier"), "1.2.5", manifestDirectory)
        assertEquals("dotnet", local.server(manifestDirectory).exePath)
        assertEquals(listOf("csharpier", "server"), local.server(manifestDirectory).parametersList.list)
        assertEquals(manifestDirectory, local.server(manifestDirectory).workDirectory)
        assertEquals("CSharpier 1.2.5 (tool manifest of the repository)", local.description)
        assertEquals("CSharpier 1.3.0 (global tool)", modern.description)
        // a file that is not on disk (in-memory, remote): no working directory instead of a failure to start
        assertNull(modern.formatStdin(File(repo, "gone/A.cs")).workDirectory)
    }

    fun testManifestAndMarkers() {
        val manifest = """{ "version": 1, "isRoot": true, "tools": { "dotnet-ef": { "version": "9.0.0", "commands": ["dotnet-ef"] },
            "CSharpier": { "version": "1.2.5", "commands": ["csharpier"], "rollForward": false } } }"""
        assertEquals("1.2.5", CSharpierLocator.parseManifest(manifest))
        assertNull(CSharpierLocator.parseManifest("""{ "tools": { "dotnet-ef": { "version": "9.0.0" } } }"""))
        assertNull(CSharpierLocator.parseManifest("{ broken"))

        val root = FileUtil.createTempDirectory("repo", null, true)
        val nested = File(root, "src/App").apply { mkdirs() }
        assertNull(CSharpierLocator.manifestEntry(nested))
        assertFalse(CSharpierLocator.isUsedBy(nested))

        // SDK 10 puts the manifest next to the sources, older SDKs into .config
        File(root, "dotnet-tools.json").writeText(manifest)
        assertEquals(root to "1.2.5", CSharpierLocator.manifestEntry(nested))
        assertTrue(CSharpierLocator.isUsedBy(nested))
        File(root, "dotnet-tools.json").delete()
        File(root, ".config").mkdirs()
        File(root, ".config/dotnet-tools.json").writeText(manifest.replace("1.2.5", "0.30.6"))
        assertEquals(root to "0.30.6", CSharpierLocator.manifestEntry(nested))
        File(root, ".config/dotnet-tools.json").delete()

        // a configuration file of CSharpier, or its MSBuild package in the project
        assertFalse(CSharpierLocator.isUsedBy(nested))
        File(root, ".csharpierrc.yaml").writeText("printWidth: 100")
        assertTrue(CSharpierLocator.isUsedBy(nested))
        File(root, ".csharpierrc.yaml").delete()
        assertTrue(CSharpierLocator.isUsedBy(nested, "<Project><ItemGroup><PackageReference Include=\"CSharpier.MsBuild\" Version=\"1.0.0\" /></ItemGroup></Project>"))
        // .editorconfig alone says nothing: CSharpier reads it, but so does everything else
        File(root, ".editorconfig").writeText("root = true")
        assertFalse(CSharpierLocator.isUsedBy(nested))

        assertEquals("1.3.0", CSharpierLocator.parseVersion("1.3.0\r\n"))
        assertEquals("0.30.6", CSharpierLocator.parseVersion("0.30.6+2bb1a1c0e5\n"))
        assertNull(CSharpierLocator.parseVersion("Could not execute because the specified command or file was not found."))
    }

    fun testAutoChoosesByRepository() {
        val settings = DotNetFormattingSettings.getInstance(project)
        val plain = FileUtil.createTempDirectory("plain", null, true)
        val withCSharpier = FileUtil.createTempDirectory("csh", null, true).also { File(it, ".csharpierrc.json").writeText("{}") }
        assertEquals(FormatterChoice.AUTO, settings.formatter)
        assertEquals(FormatterChoice.DOTNET_FORMAT, settings.resolve(plain))
        assertEquals(FormatterChoice.CSHARPIER, settings.resolve(withCSharpier))
        // an explicit choice is not second-guessed
        settings.formatter = FormatterChoice.DOTNET_FORMAT
        assertEquals(FormatterChoice.DOTNET_FORMAT, settings.resolve(withCSharpier))
        settings.formatter = FormatterChoice.NONE
        assertEquals(FormatterChoice.NONE, settings.resolve(withCSharpier))

        // Reformat Code is ours for C# files unless formatting is off
        val service = DotNetFormattingService()
        val code = myFixture.addFileToProject("Shop/A.cs", "class A { }")
        val other = myFixture.addFileToProject("Shop/a.json", "{}")
        assertFalse(service.canFormat(code))
        settings.formatter = FormatterChoice.AUTO
        assertTrue(service.canFormat(code))
        assertFalse(service.canFormat(other))

        assertTrue(DotNetSettingsConfigurable.describeFormatter(FormatterChoice.AUTO, plain).startsWith("For this project: dotnet format whitespace"))
        assertEquals("Reformat Code leaves C# files alone.", DotNetSettingsConfigurable.describeFormatter(FormatterChoice.NONE, plain))
    }

    fun testAnswersOfCSharpier() {
        val formatted = CSharpierOutput.parseServerResponse("""{"formattedFile":"class A { }\n","status":"Formatted","errorMessage":null}""")
        assertEquals("class A { }\n", (formatted as FormatResult.Formatted).text)
        val failed = CSharpierOutput.parseServerResponse("""{"formattedFile":null,"status":"Failed","errorMessage":"File had compilation errors and could not be formatted"}""")
        assertEquals("File had compilation errors and could not be formatted", (failed as FormatResult.Failed).message)
        assertSame(FormatResult.Unchanged, CSharpierOutput.parseServerResponse("""{"formattedFile":null,"status":"UnsupportedFile","errorMessage":null}"""))
        assertTrue(CSharpierOutput.parseServerResponse("<html>502</html>") is FormatResult.Failed)

        // a one-shot run
        assertEquals("class A { }\n", (CSharpierOutput.parseProcessOutput(0, "class A { }\n", "") as FormatResult.Formatted).text)
        // nothing on stdout and exit 0: an ignored or unsupported file
        assertSame(FormatResult.Unchanged, CSharpierOutput.parseProcessOutput(0, "", ""))
        val syntax = CSharpierOutput.parseProcessOutput(1, "",
            "Error C:\\src\\Shop\\Orders.cs - Was not formatted due to syntax errors.\r\n  (1,19): error CS1026: Требуется \")\"\r\n  (1,22): error CS1513: Требуется \"}\"\r\n") as FormatResult.Failed
        assertEquals("Was not formatted due to syntax errors: (1,19): error CS1026: Требуется \")\"; (1,22): error CS1513: Требуется \"}\"", syntax.message)
        assertEquals(1 to 19, CSharpierOutput.firstErrorPosition(syntax.message))
        assertNull(CSharpierOutput.firstErrorPosition("CSharpier failed."))
        // the tool is in the manifest but `dotnet tool restore` was not run
        val restore = CSharpierOutput.parseProcessOutput(1, "", "Run \"dotnet tool restore\" to make the \"csharpier\" command available.\r\n") as FormatResult.Failed
        assertEquals(CSharpierOutput.RESTORE_NEEDED, restore.message)
    }

    fun testEditorConfigChainForDotNetFormat() {
        val root = FileUtil.createTempDirectory("repo", null, true)
        val file = File(root, "src/App/Models/Order.cs").apply { parentFile.mkdirs(); writeText("class Order { }") }
        // no .editorconfig anywhere: only the directory of the file
        assertEquals(listOf(file.parentFile), DotNetFormatRunner.editorConfigChain(file))

        File(root, "src/.editorconfig").writeText("[*.cs]\nindent_size = 2\n")
        assertEquals(listOf("src", "App", "Models"), DotNetFormatRunner.editorConfigChain(file).map { it.name })

        // root = true stops the search: what is above it does not apply
        File(root, "src/App/.editorconfig").writeText("root = true\n[*.cs]\nindent_size = 3\n")
        assertEquals(listOf("App", "Models"), DotNetFormatRunner.editorConfigChain(file).map { it.name })
    }

    fun testBatchCommands() {
        val settings = DotNetFormattingSettings.getInstance(project)
        val repo = FileUtil.createTempDirectory("repo", null, true)
        val solution = File(repo, "Shop.sln").apply { writeText("") }

        settings.formatter = FormatterChoice.DOTNET_FORMAT
        assertEquals(listOf("format", solution.path), FormatTargetAction.commandLine(project, solution, verify = false)!!.parametersList.list)
        assertEquals(listOf("format", solution.path, "--verify-no-changes"), FormatTargetAction.commandLine(project, solution, verify = true)!!.parametersList.list)

        // the tool of the repository: `dotnet csharpier check <dir>`
        File(repo, "dotnet-tools.json").writeText("""{ "tools": { "csharpier": { "version": "1.2.5", "commands": ["csharpier"] } } }""")
        settings.formatter = FormatterChoice.AUTO
        if (io.github.dotnetsupport.cli.DotNetCli.findExecutable() != null) {
            assertEquals(listOf("csharpier", "check", repo.path), FormatTargetAction.commandLine(project, solution, verify = true)!!.parametersList.list)
            assertEquals(listOf("csharpier", "format", repo.path), FormatTargetAction.commandLine(project, solution, verify = false)!!.parametersList.list)
        }
        settings.formatter = FormatterChoice.NONE
        assertNull(FormatTargetAction.commandLine(project, solution, verify = false))

        assertNotNull(ActionManager.getInstance().getAction("DotNet.FormatSelected"))
        assertNotNull(ActionManager.getInstance().getAction("DotNet.VerifyFormatting"))
        assertEquals("csharpier", DotNetTool.CSHARPIER.command)
    }

    /** The real tools, when the environment says where they are: CSHARPIER_1X / CSHARPIER_0X point at the executables. */
    fun testRealCSharpier() {
        val ugly = "namespace Demo{class  A{public   void M( int x ){if(x>1){System.Console.WriteLine( \"привет €\" );}}}}\n"
        val expected = "namespace Demo\n{\n    class A\n    {\n        public void M(int x)\n        {\n            if (x > 1)\n            {\n                System.Console.WriteLine(\"привет €\");\n            }\n        }\n    }\n}\n"
        val directory = FileUtil.createTempDirectory("real", null, true)
        val file = File(directory, "A.cs")
        for (variable in listOf("CSHARPIER_1X", "CSHARPIER_0X")) {
            val executable = System.getenv(variable)?.let(::File)?.takeIf { it.isFile } ?: continue
            DotNetSettings.getInstance().setToolPath(DotNetTool.CSHARPIER, executable.path)
            val cli = CSharpierLocator.find(directory)
            println("REAL $variable -> ${cli.description}, legacy=${cli.isLegacy}")
            val server = CSharpierServer.getInstance(project)
            try {
                assertEquals(expected, (server.formatOnce(cli, file, ugly) as FormatResult.Formatted).text)
                val started = System.currentTimeMillis()
                assertEquals(expected, (server.format(cli, file, ugly) as FormatResult.Formatted).text)
                val first = System.currentTimeMillis() - started
                val again = System.currentTimeMillis()
                assertEquals(expected, (server.format(cli, file, ugly) as FormatResult.Formatted).text)
                println("REAL $variable server: first ${first} ms, second ${System.currentTimeMillis() - again} ms")
                // CRLF in, CRLF out
                assertEquals(expected.replace("\n", "\r\n"), (server.format(cli, file, ugly.replace("\n", "\r\n")) as FormatResult.Formatted).text)
                // the whole way: Reformat Code of the platform -> our service -> the tool -> the document
                DotNetFormattingSettings.getInstance(project).formatter = FormatterChoice.CSHARPIER
                val psi = myFixture.configureByText("Reformat$variable.cs", ugly)
                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                    com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project).reformat(psi)
                }
                // the tool runs on a background thread and the text comes back through the event queue
                val deadline = System.currentTimeMillis() + 15_000
                while (myFixture.editor.document.text != expected && System.currentTimeMillis() < deadline) {
                    com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                    Thread.sleep(50)
                }
                println("REAL $variable Reformat Code -> " + (myFixture.editor.document.text == expected))
                assertEquals(expected, myFixture.editor.document.text)

                val broken = server.formatOnce(cli, file, "class A { void M( { }\n")
                println("REAL $variable broken: " + (broken as FormatResult.Failed).message)
            } finally {
                server.stop()
            }
        }
        if (System.getenv("DOTNET_FORMAT_PROBE") == "1") {
            File(directory, ".editorconfig").writeText("root = true\n[*.cs]\nindent_style = space\nindent_size = 2\n")
            val started = System.currentTimeMillis()
            val result = DotNetFormatRunner.format(file, "class  A\n{\n        void  M( ) { }\n}\n")
            println("REAL dotnet format: ${System.currentTimeMillis() - started} ms -> " + ((result as? FormatResult.Formatted)?.text?.replace("\n", "\\n") ?: result))
            assertEquals("class A\n{\n  void M() { }\n}\n", (result as FormatResult.Formatted).text.replace("\r\n", "\n"))
            assertFalse("the scratch copy must not leak into the real directory", File(directory, "A.cs").exists())
        }
    }
}
