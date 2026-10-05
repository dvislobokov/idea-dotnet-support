package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.codeanalysis.BuildGeneratedFile
import io.github.dotnetsupport.codeanalysis.BuildGeneratedSources
import io.github.dotnetsupport.codeanalysis.CodeAnalysisAnswers
import io.github.dotnetsupport.codeanalysis.CodeAnalysisEvents
import io.github.dotnetsupport.codeanalysis.CodeAnalysisService
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The C# the targets of the build make in `obj/` (0.1.82): XAML of WPF, Grpc.Tools, resources. From the design-time build of the helper
 * (`codeanalysis/generate-grpc.json`, captured on a gRPC project like `debug-playground/Grpc`) or from `obj/` after the last build (a WPF
 * project of the old format, as `debug-playground/NetFramework/LegacyWpf` built by MSBuild of Visual Studio); fresh, it lets the semantic
 * errors of such a project speak.
 */
class BuildGeneratedSourcesTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSemanticEnvironment.setBuildGeneratedKnownForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testTheHelperListsWhatTheDesignTimeBuildMade() {
        val run = CodeAnalysisAnswers.generated(JsonParser.parseString(javaClass.getResource("/codeanalysis/generate-grpc.json")!!.readText()))
        assertEmpty(run.files)
        assertEquals(listOf("C:/repo/debug-playground/Grpc/obj/Debug/net9.0/Protos/Greet.cs", "C:/repo/debug-playground/Grpc/obj/Debug/net9.0/Protos/GreetGrpc.cs"), run.buildFiles)
        val inputs = listOf("C:/repo/debug-playground/Grpc/Protos/greet.proto")
        val classified = run.buildFiles.map { BuildGeneratedSources.classify(it, inputs) }
        assertEquals(listOf(BuildGeneratedSources.PROTOBUF, BuildGeneratedSources.PROTOBUF), classified.map { it.kind })
        assertTrue(classified.all { it.source == inputs.single() })
        // an answer of an older helper has no buildFiles
        assertEmpty(CodeAnalysisAnswers.generated(JsonParser.parseString("""{"project":"p","files":[]}""")).buildFiles)
    }

    fun testWhoMadeAFile() {
        val inputs = listOf("C:/s/App/MainWindow.xaml", "C:/s/App/Views/Main.xaml", "C:/s/App/Protos/hello_world.proto", "C:/s/App/Properties/Resources.resx")
        fun kind(path: String) = BuildGeneratedSources.classify(path, inputs).let { it.kind to it.source?.substringAfterLast('/') }
        assertEquals(BuildGeneratedSources.XAML to "MainWindow.xaml", kind("C:/s/App/obj/Debug/net9.0-windows/MainWindow.g.cs"))
        assertEquals(BuildGeneratedSources.XAML to "MainWindow.xaml", kind("C:/s/App/obj/Debug/MainWindow.g.i.cs"))
        assertEquals(BuildGeneratedSources.XAML to "Main.xaml", kind("C:/s/App/obj/Debug/Views/Main.g.cs"))
        assertEquals(BuildGeneratedSources.PROTOBUF to "hello_world.proto", kind("C:/s/App/obj/Debug/net9.0/Protos/HelloWorld.cs"))
        assertEquals(BuildGeneratedSources.PROTOBUF to "hello_world.proto", kind("C:/s/App/obj/Debug/net9.0/Protos/HelloWorldGrpc.cs"))
        assertEquals(BuildGeneratedSources.RESOURCES to "Resources.resx", kind("C:/s/App/obj/Debug/net9.0/Resources.Designer.cs"))
        assertEquals(BuildGeneratedSources.MSBUILD to null, kind("C:/s/App/obj/Debug/net9.0/Other.cs"))
        assertEquals("HelloWorld", BuildGeneratedSources.protocName("a/hello_world.proto"))
        assertEquals("Greet", BuildGeneratedSources.protocName("greet.proto"))
        assertEquals("V1Api2Types", BuildGeneratedSources.protocName("v1-api.2types.proto"))
    }

    /** What the last build left in obj/: of the configuration and framework of the toolbar, outputs of the inputs only, `.g.cs` over `.g.i.cs`. */
    fun testObjAfterTheLastBuild() {
        val inputs = listOf("C:/s/App/MainWindow.xaml", "C:/s/App/App.xaml")
        val obj = listOf(
            "C:/s/App/obj/Debug/MainWindow.g.cs", "C:/s/App/obj/Debug/MainWindow.g.i.cs", "C:/s/App/obj/Debug/App.g.i.cs",
            "C:/s/App/obj/Debug/.NETFramework,Version=v4.8.1.AssemblyAttributes.cs", "C:/s/App/obj/Release/MainWindow.g.cs",
        )
        assertEquals(listOf("C:/s/App/obj/Debug/App.g.i.cs", "C:/s/App/obj/Debug/MainWindow.g.cs"), BuildGeneratedSources.scan("C:/s/App", obj, inputs, "Debug", null).map { it.path })
        val sdk = listOf("C:/s/App/obj/Debug/net9.0-windows/MainWindow.g.cs", "C:/s/App/obj/Debug/net8.0-windows/MainWindow.g.cs")
        assertEquals(listOf(sdk.first()), BuildGeneratedSources.scan("C:/s/App", sdk, inputs, "Debug", "net9.0-windows").map { it.path })
    }

    fun testMissingAndStale() {
        val inputs = listOf("C:/s/App/MainWindow.xaml", "C:/s/App/Theme.xaml", "C:/s/App/greet.proto")
        val window = BuildGeneratedFile("C:/s/App/obj/Debug/MainWindow.g.cs", BuildGeneratedSources.XAML, "C:/s/App/MainWindow.xaml")
        val stamps = mutableMapOf("C:/s/App/MainWindow.xaml" to 10L, window.path to 20L)
        // a resource dictionary without x:Class makes no C#; the proto makes some and has none yet
        val needs = { path: String -> !path.endsWith("Theme.xaml") }
        val state = BuildGeneratedSources.check(listOf(window), inputs, needs) { stamps[it] ?: 0L }
        assertEquals(listOf("C:/s/App/greet.proto"), state.missing)
        assertEmpty(state.stale)
        assertFalse(state.fresh)
        val noProto = BuildGeneratedSources.check(listOf(window), inputs.dropLast(1), needs) { stamps[it] ?: 0L }
        assertTrue(noProto.fresh)
        stamps["C:/s/App/MainWindow.xaml"] = 30L
        val edited = BuildGeneratedSources.check(listOf(window), inputs.dropLast(1), needs) { stamps[it] ?: 0L }
        assertEquals(listOf(window), edited.stale)
        assertEquals("Generated by the build (XAML from MainWindow.xaml) for App: changes are lost at the next build. Out of date: MainWindow.xaml has changed since — build the project",
            BuildGeneratedSources.banner(window.kind, "MainWindow.xaml", "App", stale = true))
    }

    fun testSavingAnInputMakesTheOutputsStale() {
        for (input in listOf("C:/s/App/MainWindow.xaml", "C:/s/Grpc/Protos/greet.proto", "C:/s/App/Properties/Resources.resx")) {
            assertEquals(input, CodeAnalysisEvents.Kind.GENERATOR_INPUT, CodeAnalysisEvents.kind(input, contentChange = true, fromSave = true))
        }
        assertEquals(CodeAnalysisEvents.Kind.NONE, CodeAnalysisEvents.kind("C:/s/App/obj/Debug/MainWindow.g.cs", contentChange = true, fromSave = false))
    }

    private fun legacyWpf(folder: String, withOutput: Boolean) {
        myFixture.addFileToProject("$folder/$folder.csproj", """
            <?xml version="1.0" encoding="utf-8"?>
            <Project ToolsVersion="15.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
              <PropertyGroup><OutputType>WinExe</OutputType><TargetFrameworkVersion>v4.8.1</TargetFrameworkVersion></PropertyGroup>
              <ItemGroup>
                <Page Include="MainWindow.xaml"><Generator>MSBuild:Compile</Generator></Page>
                <Compile Include="MainWindow.xaml.cs"><DependentUpon>MainWindow.xaml</DependentUpon></Compile>
              </ItemGroup>
            </Project>
        """.trimIndent())
        myFixture.addFileToProject("$folder/MainWindow.xaml", """<Window x:Class="$folder.MainWindow" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"><TextBox x:Name="NameBox"/></Window>""")
        if (withOutput) myFixture.addFileToProject("$folder/obj/Debug/MainWindow.g.cs", """
            namespace $folder
            {
                public partial class MainWindow
                {
                    internal object NameBox;
                    public void InitializeComponent() { }
                }
            }
        """.trimIndent())
    }

    private fun errors(folder: String): List<String> {
        val file = myFixture.addFileToProject("$folder/MainWindow.xaml.cs", """
            namespace $folder;
            public partial class MainWindow
            {
                public MainWindow() { InitializeComponent(); NameBox.ToString(); Missing(); }
            }
        """.trimIndent())
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.description?.startsWith("CS") == true }.map { info: HighlightInfo ->
            document.substring(info.startOffset, info.endOffset) + " " + info.description!!.substringBefore(':')
        }
    }

    /** LegacyWpf after a build: what MarkupCompilePass1 made is in obj/Debug, so `Missing` is an error and `InitializeComponent` is not. */
    fun testLegacyWpfAfterABuildHasItsErrors() {
        legacyWpf("BuiltWpf", withOutput = true)
        val projectFile = myFixture.findFileInTempDir("BuiltWpf/BuiltWpf.csproj")
        val state = CodeAnalysisService.getInstance(project).buildGenerated(projectFile)
        assertEquals(listOf("MainWindow.g.cs"), state.files.map { it.path.substringAfterLast('/') })
        assertTrue(state.fresh)
        assertEquals(listOf("Missing CS0103"), errors("BuiltWpf"))
    }

    /** A message as protoc writes it: alias-qualified bases, one of them under `#if`, partial; the members of the generated part are complete. */
    fun testAProtobufMessageHasItsErrors() {
        myFixture.addFileToProject("Proto/obj/Debug/net9.0/Protos/Greet.cs", """
            using s = global::System;
            using scg = global::System.Collections.Generic;
            namespace Probe.Proto {
              public sealed partial class HelloRequest : s::IEquatable<HelloRequest>
              #if !GOOGLE_PROTOBUF_REFSTRUCT_COMPATIBILITY_MODE
                  , s::IDisposable
              #endif
              {
                private string name_ = "";
                public string Name { get { return name_; } set { name_ = value; } }
                public bool Equals(HelloRequest other) => true;
                public void Dispose() { }
                public scg::List<int> Values { get; } = new scg::List<int>();
              }
            }
        """.trimIndent())
        CSharpSemanticEnvironment.setBuildGeneratedKnownForTests(true)
        CSharpSemanticEnvironment.setGeneratedKnownForTests(true)
        try {
            myFixture.configureByText("ProtoUse.cs", """
                namespace Probe.Proto;
                class Use { void M() { var a = new HelloRequest().Name; var b = new HelloRequest().Nmae; } }
            """.trimIndent())
            val document = myFixture.editor.document.text
            val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.description?.startsWith("CS") == true }
                .map { document.substring(it.startOffset, it.endOffset) + " " + it.description!!.substringBefore(':') }
            assertEquals(listOf("Nmae CS1061"), errors)
        } finally {
            CSharpSemanticEnvironment.setGeneratedKnownForTests(null)
        }
    }

    /** Never built: what XAML declares is unknown, a missing name is no proof — silence, as before. */
    fun testLegacyWpfBeforeABuildStaysSilent() {
        legacyWpf("FreshWpf", withOutput = false)
        val projectFile = myFixture.findFileInTempDir("FreshWpf/FreshWpf.csproj")
        assertEquals(listOf("MainWindow.xaml"), CodeAnalysisService.getInstance(project).buildGenerated(projectFile).missing.map { it.substringAfterLast('/') })
        assertEmpty(errors("FreshWpf"))
    }
}
