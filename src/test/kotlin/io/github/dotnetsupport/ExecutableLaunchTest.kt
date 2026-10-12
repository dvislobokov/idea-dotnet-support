package io.github.dotnetsupport

import com.intellij.execution.ExecutionException
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetConfigurationType
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.run.ExecutableLaunch
import io.github.dotnetsupport.run.PortableExecutable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.nio.file.Files
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Run of a project of the old format starts the program its build has made; `dotnet run` stays for everything else. */
class ExecutableLaunchTest : BasePlatformTestCase() {
    private fun configuration(projectPath: String, command: DotNetCommand = DotNetCommand.RUN) =
        DotNetRunConfiguration(project, DotNetConfigurationType.instance.factory, "executable-launch").apply {
            options.projectPath = projectPath
            options.command = command
        }

    fun testWhatIsStartedDirectly() {
        // the configuration keeps a path on disk: real files, not the ones of the light fixture
        val directory = Files.createTempDirectory("ExeLaunch").toFile()
        Disposer.register(testRootDisposable) { directory.deleteRecursively() }
        val legacy = File(directory, "Legacy/Legacy.csproj").apply {
            parentFile.mkdirs()
            writeText("""
                <Project ToolsVersion="15.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
                  <PropertyGroup><OutputType>Exe</OutputType><TargetFrameworkVersion>v4.8</TargetFrameworkVersion></PropertyGroup>
                </Project>""".trimIndent())
        }.let { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it)!! }
        val modern = File(directory, "Modern/Modern.csproj").apply {
            parentFile.mkdirs()
            writeText("""<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net48</TargetFramework></PropertyGroup></Project>""")
        }.let { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it)!! }

        assertTrue(ExecutableLaunch.applies(project, configuration(legacy.path).options))
        assertFalse("net48 of the SDK style: dotnet run runs it", ExecutableLaunch.applies(project, configuration(modern.path).options))
        assertFalse("tests are dotnet test", ExecutableLaunch.applies(project, configuration(legacy.path, DotNetCommand.TEST).options))
        assertFalse(ExecutableLaunch.applies(project, configuration("C:/nowhere/Gone.csproj").options))
    }

    fun testTheCommandLine() {
        val configuration = configuration("C:/src/Legacy/Legacy.csproj").apply {
            options.programArguments = "first \"two words\""
            options.environment = mutableMapOf("FROM_CONFIGURATION" to "yes")
        }
        val program = File("C:/src/Legacy/bin/Debug/Legacy.exe")
        val command = configuration.executableCommandLine(program.path)
        assertEquals(program.path, command.exePath)
        assertEquals(listOf("first", "two words"), command.parametersList.list)
        // as in Visual Studio: the output folder, where app.config is
        assertEquals(program.parentFile, command.workDirectory)
        assertEquals("yes", command.environment["FROM_CONFIGURATION"])
        assertEquals(ExecutableLaunch.consoleCharset(), command.charset)

        configuration.options.workingDirectory = "C:/elsewhere"
        assertEquals(File("C:/elsewhere"), configuration.executableCommandLine(program.path).workDirectory)

        assertTrue(failure { configuration.executableCommandLine(null) }.contains("Build .NET Project"))
        assertTrue(failure { configuration.executableCommandLine("C:/src/Legacy/bin/Debug/Legacy.dll") }.contains("library"))
    }

    private fun failure(block: () -> Unit): String = try {
        block()
        fail("no ExecutionException")
        ""
    } catch (e: ExecutionException) {
        e.message.orEmpty()
    }

    /** The start of a PE32 file: `MZ`, the offset of the PE header at 0x3C, `PE\0\0`, the optional header with `Subsystem` at 68. */
    private fun header(subsystem: Int): ByteArray = ByteArray(512).also {
        it[0] = 'M'.code.toByte(); it[1] = 'Z'.code.toByte()
        it[0x3C] = 0x80.toByte()
        it[0x80] = 'P'.code.toByte(); it[0x81] = 'E'.code.toByte()
        it[0x80 + 24] = 0x0B; it[0x80 + 25] = 0x01
        it[0x80 + 24 + 68] = subsystem.toByte()
    }

    fun testWindowProgramsStopHard() {
        assertEquals(2, PortableExecutable.subsystem(header(2)))
        assertEquals(3, PortableExecutable.subsystem(header(3)))
        assertNull(PortableExecutable.subsystem(ByteArray(64)))
        assertNull(PortableExecutable.subsystem(header(2).copyOf(0x90)))

        val directory = Files.createTempDirectory("PeSubsystem").toFile()
        Disposer.register(testRootDisposable) { directory.deleteRecursively() }
        assertTrue(PortableExecutable.isWindowsGui(File(directory, "Wpf.exe").apply { writeBytes(header(2)) }))
        assertFalse(PortableExecutable.isWindowsGui(File(directory, "Console.exe").apply { writeBytes(header(3)) }))
        assertFalse(PortableExecutable.isWindowsGui(File(directory, "missing.exe")))
    }

    fun testTheConsoleCodePage() {
        assertEquals(Charset.forName("IBM866"), ExecutableLaunch.charsetOf(866))
        assertEquals(Charset.forName("IBM437"), ExecutableLaunch.charsetOf(437))
        assertEquals(StandardCharsets.UTF_8, ExecutableLaunch.charsetOf(65001))
        assertNull(ExecutableLaunch.charsetOf(12345))
        // the OEM code page of this machine is known on Windows; elsewhere there is no .NET Framework console
        if (SystemInfo.isWindows) assertTrue(ExecutableLaunch.consoleCharset().name(), ExecutableLaunch.consoleCharset().name().let { it.startsWith("IBM") || it.startsWith("x-IBM") || it == "UTF-8" || it.startsWith("windows") })
    }
}
