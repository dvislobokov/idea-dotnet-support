package io.github.dotnetsupport

import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.GroupByTypeComparator
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.DotNetInstallation
import io.github.dotnetsupport.msbuild.ProjectAssets
import io.github.dotnetsupport.msbuild.TargetFrameworks
import io.github.dotnetsupport.view.DependenciesKey
import io.github.dotnetsupport.view.DependenciesNode
import io.github.dotnetsupport.view.SolutionKey
import io.github.dotnetsupport.view.SolutionNode
import io.github.dotnetsupport.view.SolutionViewPane
import java.io.File

class DependenciesTreeTest : BasePlatformTestCase() {
    private val assetsJson = """
        {
          "version": 3,
          "targets": {
            "net9.0": {
              "Serilog.Sinks.Console/6.0.0": { "type": "package", "dependencies": { "Serilog": "4.0.0" }, "compile": { "lib/net8.0/Serilog.Sinks.Console.dll": {} } },
              "Serilog/4.0.0": { "type": "package", "compile": { "lib/net8.0/Serilog.dll": {} } },
              "Meziantou.Analyzer/2.0.1": { "type": "package" },
              "Core/1.0.0": { "type": "project", "framework": ".NETCoreApp,Version=v9.0" }
            },
            "net9.0/win-x64": { "Serilog/4.0.0": { "type": "package" } },
            "netstandard2.0": {
              "Serilog/4.0.0": { "type": "package", "dependencies": { "System.Memory": "4.5.5" } },
              "System.Memory/4.5.5": { "type": "package", "dependencies": { "Serilog": "4.0.0" } }
            }
          },
          "libraries": {
            "Serilog.Sinks.Console/6.0.0": { "type": "package", "path": "serilog.sinks.console/6.0.0", "files": ["lib/net8.0/Serilog.Sinks.Console.dll"] },
            "Serilog/4.0.0": { "type": "package", "path": "serilog/4.0.0", "files": ["lib/net8.0/Serilog.dll"] },
            "Meziantou.Analyzer/2.0.1": { "type": "package", "files": ["analyzers/dotnet/cs/Meziantou.Analyzer.dll", "analyzers/dotnet/cs/ru/Meziantou.Analyzer.resources.dll", "build/x.props"] },
            "System.Memory/4.5.5": { "type": "package" },
            "Core/1.0.0": { "type": "project", "path": "../Core/Core.csproj" }
          },
          "packageFolders": { "C:\\Users\\me\\.nuget\\packages\\": {} },
          "project": {
            "frameworks": {
              "net9.0": {
                "dependencies": {
                  "Serilog.Sinks.Console": { "target": "Package", "version": "[6.0.0, )" },
                  "Meziantou.Analyzer": { "target": "Package", "version": "[2.0.1, )" }
                },
                "frameworkReferences": { "Microsoft.NETCore.App": { "privateAssets": "all" }, "Microsoft.AspNetCore.App": {} }
              },
              "netstandard2.0": { "dependencies": { "Serilog": { "target": "Package", "version": "[4.0.0, )" } } }
            }
          }
        }
    """.trimIndent()

    fun testAssetsModel() {
        val assets = ProjectAssets.parse("\uFEFF$assetsJson")
        assertEquals(listOf("net9.0", "netstandard2.0"), assets.targets.map { it.framework })

        val net9 = assets.targets[0]
        assertEquals(listOf("Serilog.Sinks.Console", "Meziantou.Analyzer"), net9.directPackages)
        assertEquals(listOf("Serilog"), net9.findPackage("serilog.sinks.console")!!.dependencies)
        assertEquals("4.0.0", net9.findPackage("Serilog")!!.version)
        assertEquals(listOf("Meziantou.Analyzer.dll"), net9.findPackage("Meziantou.Analyzer")!!.analyzers)
        assertEquals(listOf("Core" to "../Core/Core.csproj"), net9.projects.map { it.name to it.path })
        assertEquals(listOf("Microsoft.AspNetCore.App", "Microsoft.NETCore.App"), net9.frameworkReferences)

        assertSame(ProjectAssets.EMPTY, ProjectAssets.parse("{ not json"))
    }

    fun testFrameworkNames() {
        val names = listOf("net9.0", "net8.0-windows", "netstandard2.0", "netcoreapp3.1", "net48", "net472", ".NETCoreApp,Version=v8.0", ".NETFramework,Version=v4.8")
        assertEquals(
            listOf(".NET 9.0", ".NET 8.0 (windows)", ".NET Standard 2.0", ".NET Core 3.1", ".NET Framework 4.8", ".NET Framework 4.7.2", ".NET 8.0", ".NET Framework 4.8"),
            names.map(TargetFrameworks::displayName),
        )
        assertTrue(TargetFrameworks.sameFramework("net8.0", ".NETCoreApp,Version=v8.0"))
        assertEquals("9.0", TargetFrameworks.version("net9.0"))
    }

    fun testInstallationLayout() {
        val root = FileUtil.createTempDirectory("dotnet", null, true)
        for (path in listOf(
            "sdk/9.0.301/Sdks/Microsoft.NET.Sdk/Sdk/Sdk.props",
            "sdk/10.0.100-rc.1/Sdks/Microsoft.NET.Sdk/Sdk/Sdk.props",
            "sdk/10.0.100/Sdks/Microsoft.NET.Sdk/Sdk/Sdk.props",
            "sdk/10.0.100/Sdks/Microsoft.NET.Sdk/Sdk/Sdk.targets",
            "packs/Microsoft.NETCore.App.Ref/9.0.2/ref/net9.0/System.Text.Json.dll",
            "packs/Microsoft.NETCore.App.Ref/9.0.11/ref/net9.0/System.Runtime.dll",
            "packs/Microsoft.NETCore.App.Ref/9.0.11/ref/net9.0/System.Linq.dll",
            "packs/Microsoft.NETCore.App.Ref/9.0.11/ref/net9.0/System.Linq.xml",
            "packs/Microsoft.NETCore.App.Ref/10.0.0/ref/net10.0/System.Future.dll",
        )) File(root, path).apply { parentFile.mkdirs(); writeText("") }

        // the newest SDK, a release over its preview; the newest patch of the requested framework version
        assertEquals(
            listOf("sdk/10.0.100/Sdks/Microsoft.NET.Sdk/Sdk/Sdk.props", "sdk/10.0.100/Sdks/Microsoft.NET.Sdk/Sdk/Sdk.targets"),
            DotNetInstallation.sdkImports("Microsoft.NET.Sdk", root).map { it.relativeTo(root).invariantSeparatorsPath },
        )
        assertEquals(listOf("System.Linq", "System.Runtime"), DotNetInstallation.frameworkAssemblies("Microsoft.NETCore.App", "9.0", root))
        assertEquals(emptyList<String>(), DotNetInstallation.frameworkAssemblies("Microsoft.WindowsDesktop.App", "9.0", root))
    }

    fun testRestoredProjectTree() {
        val projectFile = myFixture.addFileToProject(
            "App/App.csproj",
            """
            <Project Sdk="Microsoft.NET.Sdk.Web">
              <PropertyGroup><TargetFrameworks>net9.0;netstandard2.0</TargetFrameworks></PropertyGroup>
              <Import Project="..\build\Common.props" />
              <ItemGroup>
                <PackageReference Include="Serilog.Sinks.Console" Version="6.*" />
                <ProjectReference Include="..\Core\Core.csproj" />
              </ItemGroup>
            </Project>
            """.trimIndent(),
        ).virtualFile
        myFixture.addFileToProject("App/obj/project.assets.json", assetsJson)
        myFixture.addFileToProject("Core/Core.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        myFixture.addFileToProject("build/Common.props", "<Project/>")
        myFixture.addFileToProject("Directory.Build.targets", "<Project/>")

        val dependencies = DependenciesNode(project, DependenciesKey(projectFile), ViewSettings.DEFAULT)
        assertEquals(listOf("Imports", ".NET 9.0", ".NET Standard 2.0"), dependencies.childNames())

        // the files of the installed SDK depend on the machine: only the ones of the project are checked
        val imports = dependencies.child("Imports").childNames().filterNot { it.startsWith("Sdk.") }
        assertEquals(listOf("Common.props", "Directory.Build.targets"), imports)

        val net9 = dependencies.child(".NET 9.0")
        assertEquals(listOf("Packages", "Projects", "Analyzers", "Frameworks"), net9.childNames())

        // resolved versions instead of the floating "6.*", and the packages each one brings in
        val packages = net9.child("Packages")
        assertEquals(listOf("Meziantou.Analyzer (2.0.1)", "Serilog.Sinks.Console (6.0.0)"), packages.childNames())
        assertEquals(listOf("Serilog (4.0.0)"), packages.child("Serilog.Sinks.Console (6.0.0)").childNames())

        assertEquals(listOf("Core"), net9.child("Projects").childNames())
        assertTrue(net9.child("Projects").children.single().canNavigate())

        val analyzers = net9.child("Analyzers")
        assertEquals(listOf("Meziantou.Analyzer (2.0.1)"), analyzers.childNames())
        assertEquals(listOf("Meziantou.Analyzer"), analyzers.children.single().childNames())
        assertEquals(listOf("Microsoft.AspNetCore.App", "Microsoft.NETCore.App"), net9.child("Frameworks").childNames())

        // per framework: another set of packages, and a dependency cycle does not expand forever
        val standard = dependencies.child(".NET Standard 2.0")
        assertEquals(listOf("Packages", "Projects"), standard.childNames())
        val serilog = standard.child("Packages").children.single()
        assertEquals("Serilog (4.0.0)", serilog.describe())
        val memory = serilog.children.single()
        assertEquals("System.Memory (4.5.5)", memory.describe())
        assertEquals(emptyList<String>(), memory.childNames())
    }

    fun testDependenciesIsTheFirstChildOfProject() {
        myFixture.addFileToProject("Shop/Shop.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net9.0</TargetFramework></PropertyGroup></Project>")
        myFixture.addFileToProject("Shop/Api/Orders.cs", "")
        myFixture.addFileToProject("Shop/Alpha.cs", "")
        val sln = myFixture.addFileToProject("Shop.slnx", "<Solution><Project Path=\"Shop/Shop.csproj\" /></Solution>").virtualFile

        val projectNode = SolutionNode(project, SolutionKey(sln), ViewSettings.DEFAULT).children.single()
        // the comparator of the Project tool window, with its default "Folders Always on Top"
        val sorted = projectNode.children.sortedWith(GroupByTypeComparator(project, SolutionViewPane.ID))
        assertEquals(listOf("Dependencies", "Api", "Alpha.cs"), sorted.map { it.describe().ifEmpty { it.name.orEmpty() } })
    }

    private fun AbstractTreeNode<*>.describe(): String {
        update()
        val text = presentation.presentableText.orEmpty()
        return presentation.locationString?.let { "$text ($it)" } ?: text
    }

    private fun AbstractTreeNode<*>.childNames(): List<String> = children.map { it.describe() }
    private fun AbstractTreeNode<*>.child(name: String): AbstractTreeNode<*> = children.first { it.describe() == name }
}
