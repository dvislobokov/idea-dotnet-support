package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.msbuild.CompilationOptions
import io.github.dotnetsupport.msbuild.CompilationOptionsReader
import io.github.dotnetsupport.msbuild.FrameworkDefaults
import io.github.dotnetsupport.msbuild.GlobalUsing
import io.github.dotnetsupport.msbuild.MsBuildEvaluation
import io.github.dotnetsupport.msbuild.MsBuildEvaluationResult
import java.io.File

/**
 * What the compiler of a project is given (step 10 of CSHARP_PSI_MIGRATION.md): the answers of the real MsBuildHost with the SDK 10.0.401
 * for the projects in `src/test/resources/msbuild/compilation/projects` (evaluated in `C:\src`, regenerated with
 * `tools/compilation-fixtures/capture.py`), the static reading of the same projects checked against them, and the model over a project.
 */
class CompilationOptionsTest : BasePlatformTestCase() {
    private fun text(resource: String): String = javaClass.getResourceAsStream("/msbuild/compilation/$resource")!!.readBytes().toString(Charsets.UTF_8)

    private fun answer(name: String, root: String = "C:\\\\src"): MsBuildEvaluationResult =
        MsBuildEvaluationResult.parse(JsonParser.parseString(text(name).replace("C:\\\\src", root)))

    private fun evaluated(name: String, project: String, configuration: String = "Debug"): CompilationOptions =
        CompilationOptions.fromEvaluation("C:/src/$project", configuration, answer(name))

    fun testSdkNet10() {
        val options = evaluated("net10-debug.json", "Net10/Net10.csproj")
        assertEquals(CompilationOptions.Source.EVALUATED, options.source)
        assertEquals("net10.0", options.targetFramework)
        assertEquals(
            listOf("TRACE", "FEATURE_X", "DEBUG", "NET", "NET10_0", "NETCOREAPP") + (5..10).map { "NET${it}_0_OR_GREATER" } +
                listOf("1_0", "1_1", "2_0", "2_1", "2_2", "3_0", "3_1").map { "NETCOREAPP${it}_OR_GREATER" },
            options.defineConstants,
        )
        assertEquals("14.0", options.langVersion)
        assertEquals("enable", options.nullable)
        assertTrue(options.implicitUsings)
        assertEquals("Acme.App", options.rootNamespace)
        // the implicit usings of the SDK, the project's own, without the one it removes
        assertEquals(
            listOf("System", "System.Collections.Generic", "System.IO", "System.Linq", "System.Threading", "System.Threading.Tasks").map { GlobalUsing(it) } +
                listOf(GlobalUsing("System.Text.Json"), GlobalUsing("System.Math", isStatic = true), GlobalUsing("System.Console", alias = "Con")),
            options.usings,
        )
        assertEquals(true, options.compiles("C:/src/Net10/Program.cs"))
        assertEquals(true, options.compiles("c:\\src\\shared\\linked.cs"))
        assertEquals(false, options.compiles("C:/src/Net10/Excluded/Old.cs"))
    }

    fun testMultiTargetedNet10AndNet48() {
        val outer = answer("multi-outer.json")
        assertEquals(listOf("net10.0", "net48"), outer.targetFrameworks)
        assertNull(outer.property("TargetFramework"))

        val net48 = evaluated("multi-net48.json", "Multi/Multi.csproj")
        assertEquals("net48", net48.targetFramework)
        assertEquals("7.3", net48.langVersion)
        assertEquals(
            listOf("TRACE", "DEBUG", "NETFRAMEWORK", "NET48") +
                listOf("20", "30", "35", "40", "45", "451", "452", "46", "461", "462", "47", "471", "472", "48").map { "NET${it}_OR_GREATER" },
            net48.defineConstants,
        )
        assertFalse("NET" in net48.preprocessorSymbols)

        val net10 = evaluated("multi-net10.json", "Multi/Multi.csproj")
        assertEquals("14.0", net10.langVersion)
        assertTrue(net10.preprocessorSymbols.containsAll(listOf("NET", "NET10_0", "NETCOREAPP", "NET10_0_OR_GREATER")))
        assertFalse("NETFRAMEWORK" in net10.preprocessorSymbols)
    }

    fun testNetStandard20Release() {
        val options = evaluated("netstandard-release.json", "Std/Std.csproj", "Release")
        assertEquals(
            listOf("TRACE", "RELEASE", "NETSTANDARD", "NETSTANDARD2_0") + listOf("1_0", "1_1", "1_2", "1_3", "1_4", "1_5", "1_6", "2_0").map { "NETSTANDARD${it}_OR_GREATER" },
            options.defineConstants,
        )
        assertEquals("7.3", options.langVersion)
        assertFalse(options.implicitUsings)
    }

    fun testDirectoryBuildPropsSetsLangVersionAndDefineConstants() {
        val options = evaluated("props-release.json", "Props/WithProps/WithProps.csproj", "Release")
        assertEquals("11.0", options.langVersion) // net8.0 alone would get 12.0
        assertEquals(listOf("FROM_PROPS", "SHIPPING", "TRACE", "RELEASE", "NET", "NET8_0", "NETCOREAPP"), options.defineConstants.take(7))
    }

    fun testLegacyProject() {
        val debug = evaluated("legacy-debug.json", "Legacy/Legacy.csproj")
        assertEquals(listOf("DEBUG", "TRACE", "LEGACY_DEBUG"), debug.defineConstants)
        assertEquals("7.3", debug.langVersion)
        assertEquals("net472", debug.targetFramework)
        assertEquals("Legacy.App", debug.rootNamespace)
        assertEquals(true, debug.compiles("C:/src/Legacy/Properties/AssemblyInfo.cs"))
        assertEquals(false, debug.compiles("C:/src/Legacy/Stray.cs"))

        val release = evaluated("legacy-release.json", "Legacy/Legacy.csproj", "Release")
        assertEquals(listOf("TRACE"), release.defineConstants)
        assertEquals("9.0", release.langVersion)
    }

    /** The static reading gives what MSBuild gives, for every fixture: it is what a project has until MsBuildHost answers. */
    fun testStaticReadingMatchesMsBuild() {
        fun check(answer: String, project: String, configuration: String, framework: String? = null, projectText: String = text("projects/$project"), props: String? = null) {
            val expected = evaluated(answer, project, configuration)
            val actual = CompilationOptionsReader.read(CompilationOptionsReader.Input("C:/src/$project", projectText, props, configuration, selectedFramework = framework))
            assertEquals(CompilationOptions.Source.STATIC, actual.source)
            assertEquals(answer, expected.copy(source = actual.source, compileFiles = null), actual.copy(compileFiles = null))
            if (actual.compileFiles != null) assertEquals(answer, expected.compileFiles, actual.compileFiles)
        }
        check("net10-debug.json", "Net10/Net10.csproj", "Debug")
        check("multi-net10.json", "Multi/Multi.csproj", "Debug")
        check("multi-net48.json", "Multi/Multi.csproj", "Debug", framework = "net48")
        check("netstandard-release.json", "Std/Std.csproj", "Release")
        check("props-release.json", "Props/WithProps/WithProps.csproj", "Release", props = text("projects/Props/Directory.Build.props"))
        check("legacy-debug.json", "Legacy/Legacy.csproj", "Debug")
        check("legacy-release.json", "Legacy/Legacy.csproj", "Release")
        // the playground's own MultiTarget (net9.0;net10.0), net9.0 chosen in the toolbar
        val playground = File("debug-playground/MultiTarget/MultiTarget.csproj").readText()
        check("playground-multitarget-net9.json", "debug-playground/MultiTarget/MultiTarget.csproj", "Debug", "net9.0", playground)
    }

    fun testWhatTheStaticReadingCannotTell() {
        val project = """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup>
                <TargetFramework>net8.0</TargetFramework>
                <DefineConstants>$(DefineConstants);ALWAYS</DefineConstants>
                <DefineConstants Condition="Exists('x.txt')">$(DefineConstants);EXISTS</DefineConstants>
                <DefineConstants Condition="'$(TargetFramework)' == 'net8.0' and '$(Configuration)' != 'Release'">$(DefineConstants);NET8_DEBUG</DefineConstants>
                <DefineConstants Condition="$(TargetFramework.StartsWith('net8'))">$(DefineConstants);FUNCTION</DefineConstants>
                <DisableImplicitFrameworkDefines>true</DisableImplicitFrameworkDefines>
              </PropertyGroup>
              <Choose>
                <When Condition="'$(Configuration)' == 'Debug'"><PropertyGroup><LangVersion>preview</LangVersion></PropertyGroup></When>
                <Otherwise><PropertyGroup><LangVersion>latest</LangVersion></PropertyGroup></Otherwise>
              </Choose>
            </Project>
        """.trimIndent()
        val debug = CompilationOptionsReader.read(CompilationOptionsReader.Input("C:/src/A/A.csproj", project))
        // Exists and property functions are not evaluated: taken for false
        assertEquals(listOf("TRACE", "ALWAYS", "NET8_DEBUG", "DEBUG"), debug.defineConstants)
        assertEquals("preview", debug.langVersion)
        assertEquals("A", debug.rootNamespace)
        assertNull(debug.compileFiles) // the default globs of the SDK
        assertEquals("latest", CompilationOptionsReader.read(CompilationOptionsReader.Input("C:/src/A/A.csproj", project, configuration = "Release")).langVersion)
        // a global property is not overridden by the project
        val global = CompilationOptionsReader.read(CompilationOptionsReader.Input("C:/src/A/A.csproj", project, globalProperties = mapOf("DefineConstants" to "FROM_CLI")))
        assertEquals(listOf("FROM_CLI"), global.defineConstants)
    }

    fun testLegacyProjectWithoutConfigurationGroupsAndWildcards() {
        val project = """
            <Project ToolsVersion="4.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
              <PropertyGroup><TargetFrameworkVersion>v4.0</TargetFrameworkVersion></PropertyGroup>
              <ItemGroup><Compile Include="Src\**\*.cs" /><Compile Include="..\Shared\Common.cs" /></ItemGroup>
            </Project>
        """.trimIndent()
        val options = CompilationOptionsReader.read(CompilationOptionsReader.Input("C:/src/Old/Old.csproj", project))
        assertEquals(emptyList<String>(), options.defineConstants) // nothing implicit without the SDK
        assertEquals("7.3", options.langVersion)
        assertEquals("net40", options.targetFramework)
        assertEquals(true, options.compiles("C:/src/Old/Src/Deep/A.cs"))
        assertEquals(true, options.compiles("C:/src/Shared/Common.cs"))
        assertEquals(false, options.compiles("C:/src/Old/Other.cs"))
    }

    /** `_MaxSupportedLangVersion` of `Microsoft.CSharp.Core.targets` (SDK 10.0.401), the default `LangVersion` per framework. */
    fun testDefaultLangVersions() {
        val table = mapOf(
            "net48" to "7.3", "net472" to "7.3", "net20" to "7.3", "netstandard2.0" to "7.3", "netstandard1.6" to "7.3", "netstandard2.1" to "8.0",
            "netcoreapp2.1" to "7.3", "netcoreapp3.0" to "8.0", "netcoreapp3.1" to "8.0", "net5.0" to "9.0", "net6.0" to "10.0", "net7.0" to "11.0",
            "net8.0" to "12.0", "net9.0" to "13.0", "net10.0" to "14.0", "net11.0" to "14.0", "net8.0-windows" to "12.0",
        )
        for ((tfm, version) in table) assertEquals(tfm, version, FrameworkDefaults.defaultLangVersion(FrameworkDefaults.parse(tfm)))
        assertNull(FrameworkDefaults.defaultLangVersion(null))
        assertNull(FrameworkDefaults.parse("net4.0"))
        assertNull(FrameworkDefaults.parse("uap10.0"))
    }

    fun testImplicitDefinesOfOtherFrameworks() {
        assertEquals(
            listOf("NETCOREAPP", "NETCOREAPP3_1") + listOf("1_0", "1_1", "2_0", "2_1", "2_2", "3_0", "3_1").map { "NETCOREAPP${it}_OR_GREATER" },
            FrameworkDefaults.implicitDefines(FrameworkDefaults.parse("netcoreapp3.1")),
        )
        assertEquals(listOf("NET", "NET8_0", "NETCOREAPP", "WINDOWS", "WINDOWS10_0_19041_0"), FrameworkDefaults.implicitDefines(FrameworkDefaults.parse("net8.0-windows10.0.19041.0")).take(5))
        assertTrue("NET11_0_OR_GREATER" in FrameworkDefaults.implicitDefines(FrameworkDefaults.parse("net11.0")))
        assertEquals(listOf("NETSTANDARD", "NETSTANDARD2_1"), FrameworkDefaults.implicitDefines(FrameworkDefaults.parse("netstandard2.1")).take(2))
    }

    fun testSymbolsAsTheCompilerReadsThem() {
        assertEquals(listOf("A", "B", "C", "Ü_1"), CompilationOptions.symbols(" A; B ,C;;1X;A;$(Foo);Ü_1;a-b"))
        assertEquals(emptyList<String>(), CompilationOptions.symbols(null))
    }

    fun testTheModelOfAProject() {
        val root = "CompModel"
        val files = mapOf(
            "Net10/Net10.csproj" to text("projects/Net10/Net10.csproj"), "Net10/Program.cs" to "", "Net10/Excluded/Old.cs" to "", "Shared/Linked.cs" to "",
            "Multi/Multi.csproj" to text("projects/Multi/Multi.csproj"), "Multi/Lib.cs" to "",
            "Legacy/Legacy.csproj" to text("projects/Legacy/Legacy.csproj"), "Legacy/Program.cs" to "", "Legacy/Stray.cs" to "",
            "Loose/Loose.cs" to "",
        )
        val vfs = files.mapValues { (path, content) -> myFixture.addFileToProject("$root/$path", content).virtualFile }
        val base = vfs.getValue("Net10/Net10.csproj").parent.parent.path
        val model = CompilationModel.getInstance(project)
        val settings = DotNetBuildSettings.getInstance(project)
        val asked = ArrayList<String>()
        var changes = 0
        project.messageBus.connect(testRootDisposable).subscribe(CompilationModel.CHANGED, CompilationModel.Listener { changes++ })
        try {
            // no helper: read from the project files
            assertEquals(CompilationOptions.Source.STATIC, model.optionsFor(vfs.getValue("Net10/Program.cs"))!!.source)
            assertTrue("NET10_0" in model.symbolsFor(vfs.getValue("Net10/Program.cs"))!!)
            assertEquals("7.3", model.languageVersionFor(vfs.getValue("Legacy/Program.cs")))
            assertNull(model.symbolsFor(vfs.getValue("Loose/Loose.cs")))
            assertNull(model.projectOf(vfs.getValue("Shared/Linked.cs"))) // only the evaluation knows who links it

            model.setEvaluatorForTests { path, globals ->
                val name = path.substringAfterLast('/').substringBefore('.')
                val tfm = globals["TargetFramework"]
                asked += "$name ${globals["Configuration"]} ${tfm.orEmpty()}".trim()
                val fixture = when (name) {
                    "Net10" -> "net10-debug.json"
                    "Multi" -> if (tfm == null) "multi-outer.json" else "multi-$tfm.json".replace("net10.0", "net10")
                    else -> "legacy-${globals["Configuration"]!!.lowercase()}.json"
                }
                answer(fixture, base)
            }
            val program = model.optionsFor(vfs.getValue("Net10/Program.cs"))!!
            assertEquals(CompilationOptions.Source.EVALUATED, program.source)
            assertTrue("FEATURE_X" in program.preprocessorSymbols)
            assertEquals(vfs.getValue("Net10/Net10.csproj"), model.projectOf(vfs.getValue("Shared/Linked.cs")))
            assertEquals("14.0", model.languageVersionFor(vfs.getValue("Shared/Linked.cs")))

            // a multi-targeted project: its first framework, then the one of the toolbar
            assertTrue("NET10_0" in model.symbolsFor(vfs.getValue("Multi/Lib.cs"))!!)
            assertEquals(listOf("Net10 Debug", "Multi Debug", "Multi Debug net10.0"), asked)
            val before = changes
            settings.framework = "net48"
            assertTrue(changes > before)
            assertTrue("NET48" in model.symbolsFor(vfs.getValue("Multi/Lib.cs"))!!)
            assertEquals("7.3", model.languageVersionFor(vfs.getValue("Multi/Lib.cs")))

            // the old format, Release: what its Release group says
            settings.configuration = "Release"
            assertEquals(setOf("TRACE"), model.symbolsFor(vfs.getValue("Legacy/Program.cs")))
            assertEquals("9.0", model.languageVersionFor(vfs.getValue("Legacy/Program.cs")))
            // a file the project does not list is still of its directory's project
            assertEquals(vfs.getValue("Legacy/Legacy.csproj"), model.projectOf(vfs.getValue("Legacy/Stray.cs")))
            settings.configuration = "Debug"

            // a new file under the project: evaluated again when next asked
            asked.clear()
            model.optionsFor(vfs.getValue("Net10/Program.cs"))
            assertEquals(emptyList<String>(), asked)
            MsBuildEvaluation.getInstance(project).changed(listOf("$base/Net10/New.cs"))
            model.optionsFor(vfs.getValue("Net10/Program.cs"))
            assertEquals(listOf("Net10 Debug"), asked)
        } finally {
            model.setEvaluatorForTests(null)
            settings.framework = null
            settings.configuration = DotNetBuildSettings.DEFAULT_CONFIGURATION
        }
    }
}
