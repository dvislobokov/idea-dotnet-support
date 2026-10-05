package io.github.dotnetsupport

import io.github.dotnetsupport.index.AssemblyLibraries
import io.github.dotnetsupport.index.ProjectAssemblies
import io.github.dotnetsupport.msbuild.MsBuildProject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * What a project is compiled against ([ProjectAssemblies.references]): packages, reference packs (installed or downloaded by
 * restore), .NET Framework reference assemblies (a package, else the folder of the machine), `HintPath`, and the projects it refers
 * to as projects. On a folder of empty files laid out as the SDK, the package folder and a solution lay them out.
 */
class ProjectReferencesTest {
    private val root: File = Files.createTempDirectory("references").toFile()
    private val packages = File(root, "packages").apply { mkdirs() }
    private val folder = packages.path.replace("\\", "\\\\")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun file(path: String) = File(root, path).apply { parentFile.mkdirs(); writeText("") }
    private fun relative(files: List<File>) = files.map { it.path.removePrefix(root.path).replace('\\', '/') }

    @Test
    fun `a restored project of several frameworks, with the one chosen in the toolbar`() {
        file("packages/newtonsoft.json/13.0.3/lib/net6.0/Newtonsoft.Json.dll")
        file("packages/microsoft.netcore.app.ref/8.0.11/ref/net8.0/System.Runtime.dll")
        file("dotnet/packs/Microsoft.NETCore.App.Ref/10.0.12/ref/net10.0/System.Runtime.dll")
        file("Lib/obj/Release/net8.0/ref/Lib.dll")
        file("App/lib/Vendor.dll")
        val assets = """
            {
              "targets": {
                "net10.0": { "Lib/1.0.0": { "type": "project", "compile": { "bin/placeholder/Lib.dll": {} } } },
                "net8.0": {
                  "Newtonsoft.Json/13.0.3": { "type": "package", "compile": { "lib/net6.0/Newtonsoft.Json.dll": {} } },
                  "Lib/1.0.0": { "type": "project", "compile": { "bin/placeholder/Lib.dll": {} } }
                }
              },
              "libraries": {
                "Newtonsoft.Json/13.0.3": { "type": "package", "path": "newtonsoft.json/13.0.3" },
                "Lib/1.0.0": { "type": "project", "path": "../Lib/Lib.csproj" }
              },
              "packageFolders": { "$folder": {} },
              "project": { "frameworks": {
                "net10.0": { "frameworkReferences": { "Microsoft.NETCore.App": { "privateAssets": "all" } } },
                "net8.0": {
                  "frameworkReferences": { "Microsoft.NETCore.App": { "privateAssets": "all" } },
                  "downloadDependencies": [ { "name": "Microsoft.NETCore.App.Ref", "version": "[8.0.11]" } ]
                }
              } }
            }
        """.trimIndent()
        val msbuild = MsBuildProject.parse("""
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup><TargetFrameworks>net10.0;net8.0</TargetFrameworks></PropertyGroup>
              <ItemGroup>
                <ProjectReference Include="..\Lib\Lib.csproj" />
                <Reference Include="Vendor"><HintPath>lib\Vendor.dll</HintPath></Reference>
              </ItemGroup>
            </Project>
        """.trimIndent())
        assertEquals(mapOf("Vendor" to "lib/Vendor.dll"), msbuild.hintPaths)

        val net8 = ProjectAssemblies.references(ProjectAssemblies.Request(assets, File(root, "App"), File(root, "dotnet"), "net8.0", msbuild))
        assertEquals("net8.0", net8.framework)
        assertEquals(listOf(
            "/packages/newtonsoft.json/13.0.3/lib/net6.0/Newtonsoft.Json.dll",
            // the SDK 10 has no pack of 8.0: restore has downloaded it
            "/packages/microsoft.netcore.app.ref/8.0.11/ref/net8.0/System.Runtime.dll",
            "/App/lib/Vendor.dll",
        ), relative(net8.assemblies))
        assertEquals("a project is its sources", listOf("/Lib/Lib.csproj"), relative(net8.projects))
        assertEquals(listOf("/Lib/obj/Release/net8.0/ref/Lib.dll"), relative(net8.projectOutputs))

        val net10 = ProjectAssemblies.references(ProjectAssemblies.Request(assets, File(root, "App"), File(root, "dotnet"), null, msbuild))
        assertEquals("nothing chosen: the first one", "net10.0", net10.framework)
        assertEquals(listOf("/dotnet/packs/Microsoft.NETCore.App.Ref/10.0.12/ref/net10.0/System.Runtime.dll", "/App/lib/Vendor.dll"), relative(net10.assemblies))
        assertTrue("Lib is not built for net10.0", net10.projectOutputs.isEmpty())
        assertEquals(listOf("/Lib/Lib.csproj"), relative(net10.projects))

        // the libraries of the IDE (External Libraries): by where the assemblies come from, with versions
        assertEquals(listOf("PACKAGE Newtonsoft.Json 13.0.3", "FRAMEWORK Microsoft.NETCore.App.Ref 8.0.11", "ASSEMBLY Vendor"), net8.libraries.map { "${it.kind} ${it.presentableName}" })
        assertEquals(listOf("FRAMEWORK Microsoft.NETCore.App.Ref 10.0.12", "ASSEMBLY Vendor"), net10.libraries.map { "${it.kind} ${it.presentableName}" })
        assertEquals("every assembly is in a library", net8.assemblies.toSet(), net8.libraries.flatMap { it.assemblies }.toSet())
        val merged = AssemblyLibraries.merge(listOf(net8, net10))
        assertEquals("frameworks, packages, assemblies; one per version", listOf("Microsoft.NETCore.App.Ref 10.0.12", "Microsoft.NETCore.App.Ref 8.0.11", "Newtonsoft.Json 13.0.3", "Vendor"),
            merged.map { it.presentableName })
        assertEquals("Vendor of both is one library with one assembly", listOf("/App/lib/Vendor.dll"), relative(merged.last().assemblies))
    }

    @Test
    fun `an SDK project for dotnet Framework`() {
        for (name in listOf("mscorlib", "System", "System.Core", "System.Xml", "System.Web")) {
            file("packages/microsoft.netframework.referenceassemblies.net472/1.0.3/build/.NETFramework/v4.7.2/$name.dll")
        }
        file("machine/v4.8/mscorlib.dll")
        file("machine/v4.8/System.dll")
        file("machine/v4.8/Facades/System.Runtime.dll")
        fun assets(framework: String, withPackage: Boolean) = """
            {
              "targets": { "$framework": {
                ${if (withPackage) "\"Microsoft.NETFramework.ReferenceAssemblies.net472/1.0.3\": { \"type\": \"package\" }" else ""}
              } },
              "libraries": { "Microsoft.NETFramework.ReferenceAssemblies.net472/1.0.3": { "type": "package", "path": "microsoft.netframework.referenceassemblies.net472/1.0.3" } },
              "packageFolders": { "$folder": {} },
              "project": { "frameworks": { "$framework": { } } }
            }
        """.trimIndent()
        val msbuild = MsBuildProject.parse("""<Project Sdk="Microsoft.NET.Sdk"><ItemGroup><Reference Include="System.Web" /><Reference Include="System.Runtime" /></ItemGroup></Project>""")

        val fromPackage = ProjectAssemblies.references(ProjectAssemblies.Request(assets("net472", true), File(root, "App"), null, null, msbuild, File(root, "machine")))
        assertEquals("mscorlib, what the SDK adds and what is there, then what the project names", listOf("mscorlib", "System", "System.Xml", "System.Core", "System.Web"),
            fromPackage.assemblies.map { it.nameWithoutExtension })
        assertTrue(fromPackage.assemblies.all { "referenceassemblies.net472" in it.path })
        assertEquals(listOf(".NETFramework 4.7.2"), fromPackage.libraries.map { it.presentableName })

        val fromMachine = ProjectAssemblies.references(ProjectAssemblies.Request(assets("net48", false), File(root, "App"), null, null, msbuild, File(root, "machine")))
        assertEquals("a facade too", listOf("/machine/v4.8/mscorlib.dll", "/machine/v4.8/System.dll", "/machine/v4.8/Facades/System.Runtime.dll"), relative(fromMachine.assemblies))
        val nowhere = ProjectAssemblies.references(ProjectAssemblies.Request(assets("net48", false), File(root, "App"), null, null, msbuild, null))
        assertTrue("no reference assemblies on the machine: nothing", nowhere.assemblies.isEmpty())
    }

    @Test
    fun `a project of the old format has no assets file`() {
        for (name in listOf("mscorlib", "System", "System.Xml", "System.Data")) file("machine/v4.7.2/$name.dll")
        file("packages/Newtonsoft.Json.13.0.1/lib/net45/Newtonsoft.Json.dll")
        file("Lib/bin/Debug/Lib.dll")
        val msbuild = MsBuildProject.parse("""
            <?xml version="1.0" encoding="utf-8"?>
            <Project ToolsVersion="15.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
              <PropertyGroup><TargetFrameworkVersion>v4.7.2</TargetFrameworkVersion></PropertyGroup>
              <ItemGroup>
                <Reference Include="System" />
                <Reference Include="System.Xml" />
                <Reference Include="Newtonsoft.Json, Version=13.0.0.0, Culture=neutral, PublicKeyToken=30ad4fe6b2a6aeed">
                  <HintPath>..\packages\Newtonsoft.Json.13.0.1\lib\net45\Newtonsoft.Json.dll</HintPath>
                </Reference>
                <Reference Include="Missing"><HintPath>..\packages\Missing\Missing.dll</HintPath></Reference>
              </ItemGroup>
              <ItemGroup><ProjectReference Include="..\Lib\Lib.csproj"><Project>{guid}</Project></ProjectReference></ItemGroup>
            </Project>
        """.trimIndent())
        assertTrue(msbuild.isLegacy)
        val found = ProjectAssemblies.references(ProjectAssemblies.Request(null, File(root, "App"), null, null, msbuild, File(root, "machine")))
        assertEquals("net472", found.framework)
        assertEquals(listOf(
            "/machine/v4.7.2/mscorlib.dll", "/machine/v4.7.2/System.dll", "/machine/v4.7.2/System.Xml.dll",
            "/packages/Newtonsoft.Json.13.0.1/lib/net45/Newtonsoft.Json.dll",
        ), relative(found.assemblies))
        assertEquals(listOf("/Lib/Lib.csproj"), relative(found.projects))
        assertEquals("built into bin/<configuration>, no folder of the framework", listOf("/Lib/bin/Debug/Lib.dll"), relative(found.projectOutputs))
        assertEquals("a package of packages.config by its folder", listOf("FRAMEWORK .NETFramework 4.7.2", "PACKAGE Newtonsoft.Json 13.0.1"),
            found.libraries.map { "${it.kind} ${it.presentableName}" })
        assertEquals("an SDK project not restored and naming nothing: nothing", ProjectAssemblies.References.NONE.assemblies,
            ProjectAssemblies.references(ProjectAssemblies.Request(null, File(root, "App"), null, null, MsBuildProject.parse("<Project Sdk=\"Microsoft.NET.Sdk\" />"))).assemblies)
    }

    @Test
    fun `versions of dotnet Framework`() {
        assertEquals("v4.7.2", ProjectAssemblies.netFrameworkVersion("net472"))
        assertEquals("v4.8", ProjectAssemblies.netFrameworkVersion("net48"))
        assertEquals("v4.8.1", ProjectAssemblies.netFrameworkVersion("net481"))
        assertNull(ProjectAssemblies.netFrameworkVersion("net8.0"))
        assertNull(ProjectAssemblies.netFrameworkVersion("netstandard2.0"))
    }
}
