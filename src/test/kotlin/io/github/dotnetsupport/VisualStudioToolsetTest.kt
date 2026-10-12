package io.github.dotnetsupport

import com.intellij.execution.CommandLineUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.build.DotNetBuildOptions
import io.github.dotnetsupport.build.VisualStudioInstance
import io.github.dotnetsupport.build.VisualStudioToolset
import java.io.File
import java.nio.file.Files

/**
 * Projects of the old format are built by `MSBuild.exe` of Visual Studio: what `vswhere` says (`src/test/resources/msbuild`, Build Tools 2022
 * of a real machine, its description localized), the arguments of `dotnet build` turned into the ones of `MSBuild.exe`, and which builds what.
 */
class VisualStudioToolsetTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            VisualStudioToolset.setInstancesForTests(null)
            DotNetBuildOptions.getInstance(project).loadState(DotNetBuildOptions.Settings())
        } finally {
            super.tearDown()
        }
    }

    fun testVswhere() {
        val json = javaClass.getResourceAsStream("/msbuild/vswhere-buildtools-2022.json")!!.readBytes().toString(Charsets.UTF_8)
        val instance = VisualStudioToolset.parse(json).single()
        assertEquals(VisualStudioInstance("Visual Studio Build Tools 2022", "17.14.37710.0", "C:\\Program Files (x86)\\Microsoft Visual Studio\\2022\\BuildTools"), instance)
        assertEquals("Visual Studio Build Tools 2022 (17.14)", instance.title)

        // newest first, a preview after every release
        val several = VisualStudioToolset.parse(
            """[{"installationPath":"C:\\VS\\Old","installationVersion":"16.11.5","displayName":"Visual Studio Community 2019"},
               {"installationPath":"C:\\VS\\Preview","installationVersion":"18.1.0","displayName":"Visual Studio Enterprise 2026","isPrerelease":true},
               {"installationPath":"C:\\VS\\New","installationVersion":"17.9.2","displayName":"Visual Studio Professional 2022"},
               {"displayName":"no path"}]""")
        assertEquals(listOf("C:\\VS\\New", "C:\\VS\\Old", "C:\\VS\\Preview"), several.map { it.path })
        assertEquals("Visual Studio Enterprise 2026 (18.1) Preview", several.last().title)
        assertEquals(emptyList<VisualStudioInstance>(), VisualStudioToolset.parse("vswhere: error"))
    }

    fun testTheArgumentsOfMsBuildExe() {
        assertEquals(listOf("-t:Build", "-restore", "-p:RestorePackagesConfig=true", "-m", "-v:m", "App.sln", "-nologo", "-clp:NoSummary", "-p:Configuration=Release"),
            VisualStudioToolset.translate(listOf("build", "App.sln", "-nologo", "-clp:NoSummary", "-c", "Release")))
        assertEquals(listOf("-t:Rebuild", "App.csproj", "-p:Configuration=Debug", "-p:TargetFramework=net48", "-m:4", "-v:detailed", "-p:Platform=x64"),
            VisualStudioToolset.translate(listOf("build", "--no-incremental", "App.csproj", "-c", "Debug", "--framework", "net48", "--no-restore", "-m:4", "--verbosity", "detailed", "--property:Platform=x64")))
        // a clean restores nothing
        assertEquals(listOf("-t:Clean", "-m", "-v:m", "App.csproj", "-nologo"), VisualStudioToolset.translate(listOf("clean", "App.csproj", "-nologo")))
        // a custom target is MSBuild syntax already
        assertEquals(listOf("App.csproj", "-t:TransformAll"), VisualStudioToolset.translate(listOf("msbuild", "App.csproj", "-t:TransformAll")))
        // the rest stays with the SDK
        assertNull(VisualStudioToolset.translate(listOf("publish", "App.csproj")))
        assertNull(VisualStudioToolset.translate(listOf("restore", "App.sln")))
        assertNull(VisualStudioToolset.translate(emptyList()))
    }

    fun testSolutionDir() {
        if (!SystemInfo.isWindows) return
        val argument = VisualStudioToolset.solutionDirArgument(File("C:\\Program Files\\Shop\\"))
        assertEquals("-p:SolutionDir=C:\\Program Files\\Shop\\", argument)
        // a backslash before the closing quote would escape it: the command line doubles it, MSBuild gets the path as it is
        assertEquals("\"-p:SolutionDir=C:\\Program Files\\Shop\\\\\"", CommandLineUtil.toCommandLine("MSBuild.exe", listOf(argument)).last())
    }

    fun testWhichMsBuildBuilds() {
        val newest = File("C:/VS/New/MSBuild.exe")
        val existing = Files.createTempFile("MSBuild", ".exe").toFile().apply { deleteOnExit() }
        assertEquals(newest, VisualStudioToolset.choose(VisualStudioToolset.AUTO, listOf(newest), needsVisualStudio = true))
        assertNull(VisualStudioToolset.choose(VisualStudioToolset.AUTO, listOf(newest), needsVisualStudio = false))
        assertNull("Visual Studio is not there", VisualStudioToolset.choose(VisualStudioToolset.AUTO, emptyList(), needsVisualStudio = true))
        assertNull("the SDK always", VisualStudioToolset.choose(VisualStudioToolset.DOTNET, listOf(newest), needsVisualStudio = true))
        // a chosen MSBuild builds everything, SDK projects too
        assertEquals(existing, VisualStudioToolset.choose(existing.path, listOf(newest), needsVisualStudio = false))
        assertEquals("a chosen MSBuild that is gone counts as Auto", newest, VisualStudioToolset.choose("C:/gone/MSBuild.exe", listOf(newest), needsVisualStudio = true))
    }

    fun testLegacyProjectsAndSolutionsNeedVisualStudio() {
        val legacy = myFixture.addFileToProject("VsLegacy/Legacy/Legacy.csproj", """
            <Project ToolsVersion="15.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
              <Import Project="${'$'}(MSBuildToolsPath)\Microsoft.CSharp.targets" />
            </Project>""".trimIndent()).virtualFile
        val modern = myFixture.addFileToProject("VsLegacy/Modern/Modern.csproj", """<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><TargetFramework>net48</TargetFramework></PropertyGroup></Project>""").virtualFile
        val mixed = myFixture.addFileToProject("VsLegacy/Mixed.slnx", """<Solution><Project Path="Legacy/Legacy.csproj" /><Project Path="Modern/Modern.csproj" /></Solution>""").virtualFile
        val sdkOnly = myFixture.addFileToProject("VsLegacy/Modern.slnx", """<Solution><Project Path="Modern/Modern.csproj" /></Solution>""").virtualFile

        assertTrue(VisualStudioToolset.needsVisualStudio(project, legacy))
        assertFalse("net48 of the SDK style: the SDK builds it", VisualStudioToolset.needsVisualStudio(project, modern))
        assertTrue(VisualStudioToolset.needsVisualStudio(project, mixed))
        assertFalse(VisualStudioToolset.needsVisualStudio(project, sdkOnly))
    }

    fun testTheBuildOfALegacyProjectGoesToMsBuildExe() {
        if (!SystemInfo.isWindows) return
        val installation = Files.createTempDirectory("VsBuildTools").toFile()
        val msBuild = File(installation, "MSBuild/Current/Bin/amd64/MSBuild.exe").apply { parentFile.mkdirs(); writeText("") }
        File(installation, "MSBuild/Microsoft/VisualStudio/v17.0").mkdirs()
        try {
            val instance = VisualStudioInstance("Visual Studio Build Tools 2022", "17.14.37710.0", installation.path)
            VisualStudioToolset.setInstancesForTests(listOf(instance))
            assertEquals(File(installation, "MSBuild\\Microsoft\\VisualStudio\\v17.0").path, VisualStudioToolset.vsToolsPath())

            val legacy = myFixture.addFileToProject("VsBuild/Legacy.csproj", """<Project ToolsVersion="15.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003" />""").virtualFile
            val command = VisualStudioToolset.buildCommandLine(project, legacy, legacy.parent.path, listOf("build", legacy.path, "-c", "Debug"))
            assertEquals(msBuild.path, command.exePath)
            assertEquals(listOf("-t:Build", "-restore", "-p:RestorePackagesConfig=true", "-m", "-v:m", legacy.path, "-p:Configuration=Debug"), command.parametersList.list)
            // a publish is not a thing of MSBuild.exe here
            assertFalse(VisualStudioToolset.buildCommandLine(project, legacy, legacy.parent.path, listOf("publish", legacy.path)).exePath.endsWith("MSBuild.exe"))

            DotNetBuildOptions.getInstance(project).state.msBuild = VisualStudioToolset.DOTNET
            assertFalse(VisualStudioToolset.buildCommandLine(project, legacy, legacy.parent.path, listOf("build", legacy.path)).exePath.endsWith("MSBuild.exe"))
        } finally {
            installation.deleteRecursively()
        }
    }
}
