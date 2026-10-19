package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.ProjectAssemblies
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.msbuild.MsBuildProject
import java.io.File
import java.nio.file.Files

/**
 * What a Unity project looks like to the plugin (UNITY_PLAN.md): every generated project in one folder with explicit `Compile` items, and
 * `HintPath` references by absolute path.
 */
class ProjectsInOneFolderTest : BasePlatformTestCase() {
    fun testTheProjectOfAFileAmongSeveralInOneFolderIsTheOneThatListsIt() {
        val boot = myFixture.addFileToProject("unity1/Game.Boot.csproj", "<Project ToolsVersion=\"4.0\"><ItemGroup><Compile Include=\"Assets\\Game\\Boot\\DebugHotkeys.cs\" /></ItemGroup></Project>")
        val net = myFixture.addFileToProject("unity1/Game.Net.csproj", "<Project ToolsVersion=\"4.0\"><ItemGroup><Compile Include=\"Assets\\Game\\Net\\CombatServer.cs\" /></ItemGroup></Project>")
        val hotkeys = myFixture.addFileToProject("unity1/Assets/Game/Boot/DebugHotkeys.cs", "class DebugHotkeys {}")
        val combat = myFixture.addFileToProject("unity1/Assets/Game/Net/CombatServer.cs", "class CombatServer {}")
        val loose = myFixture.addFileToProject("unity1/Assets/Loose.cs", "class Loose {}")
        assertEquals(boot.virtualFile, DotNetProjects.findOwningProject(hotkeys.virtualFile))
        assertEquals(net.virtualFile, DotNetProjects.findOwningProject(combat.virtualFile))
        // listed by none: the first project by name, whatever order the folder has (a script made in Assets/ before Unity lists it)
        assertEquals(boot.virtualFile, DotNetProjects.findOwningProject(loose.virtualFile))
        // listed by none in a folder Unity writes itself: the sources of its packages are in no project (they used to get the first one,
        // and a project Unity added gave thousands of them other `#if` symbols — 101 IDE errors, 2026-10-18)
        myFixture.addFileToProject("unity1/ProjectSettings/ProjectVersion.txt", "m_EditorVersion: 6000.6.0f1\n")
        val packaged = myFixture.addFileToProject("unity1/Library/PackageCache/com.unity.inputsystem@1/Runtime/InputSystem.cs", "class InputSystem {}")
        assertNull(DotNetProjects.findOwningProject(packaged.virtualFile))
        // a directory has no single project among several
        assertNotNull(DotNetProjects.findOwningProject(hotkeys.virtualFile.parent))
    }

    fun testAProjectInTheHomeFolderOwnsNothingBelowIt() {
        // `dotnet new console` once run in ~ left ~/<user>.csproj: the plugin's files under ~/Library/Caches got it as their project, and
        // MSBuild's `**/*` over the home read every app's data on macOS ("data of other apps" prompts, helpers that never ended; 2026-10-19)
        val home = Files.createTempDirectory("home").toFile().canonicalFile
        try {
            File(home, "user.csproj").writeText("<Project Sdk=\"Microsoft.NET.Sdk\" />")
            File(home, "Library/Caches/IDE/dotnet-support/generated/Gen.cs").also { it.parentFile.mkdirs(); it.writeText("class Gen {}") }
            File(home, "work/App/App.csproj").also { it.parentFile.mkdirs(); it.writeText("<Project Sdk=\"Microsoft.NET.Sdk\" />") }
            File(home, "work/App/Program.cs").writeText("class Program {}")
            val fs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            val root = fs.refreshAndFindFileByIoFile(home)!!
            com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true, root)
            DotNetProjects.setHomeForTests(home.path)
            assertNull(DotNetProjects.findOwningProject(root.findFileByRelativePath("Library/Caches/IDE/dotnet-support/generated/Gen.cs")!!))
            assertEquals(root.findFileByRelativePath("work/App/App.csproj"), DotNetProjects.findOwningProject(root.findFileByRelativePath("work/App/Program.cs")!!))
            // opened by its own file, it is a project as any other
            assertEquals(root.findChild("user.csproj"), DotNetProjects.findOwningProject(root.findChild("user.csproj")!!))
        } finally {
            DotNetProjects.setHomeForTests(null)
            home.deleteRecursively()
        }
    }

    fun testAnAbsoluteHintPathIsTakenAsItIs() {
        val editor = Files.createTempDirectory("unity-editor").toFile()
        try {
            val dll = File(editor, "UnityEngine.CoreModule.dll").also { it.writeBytes(ByteArray(16)) }
            val projectDirectory = Files.createTempDirectory("unity-project").toFile()
            val relativeDll = File(projectDirectory, "Library/ScriptAssemblies/Assembly-CSharp.dll").also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(16)) }
            val text = """
                <Project ToolsVersion="4.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
                  <PropertyGroup><TargetFrameworkVersion>v4.7.1</TargetFrameworkVersion></PropertyGroup>
                  <ItemGroup>
                    <Reference Include="UnityEngine.CoreModule"><HintPath>${dll.path}</HintPath></Reference>
                    <Reference Include="Assembly-CSharp"><HintPath>Library\ScriptAssemblies\Assembly-CSharp.dll</HintPath></Reference>
                    <Reference Include="Missing"><HintPath>${File(editor, "Missing.dll").path}</HintPath></Reference>
                  </ItemGroup>
                </Project>
            """.trimIndent()
            val msbuild = MsBuildProject.parse(text)
            assertTrue(msbuild.isLegacy)
            val references = ProjectAssemblies.references(ProjectAssemblies.Request(null, projectDirectory, null, "net471", msbuild, null))
            assertEquals(listOf(dll.canonicalFile, relativeDll.canonicalFile), references.assemblies.map { it.canonicalFile })
            projectDirectory.deleteRecursively()
        } finally {
            editor.deleteRecursively()
        }
    }
}
