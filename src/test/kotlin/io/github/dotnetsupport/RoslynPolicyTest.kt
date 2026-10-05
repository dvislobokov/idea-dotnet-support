package io.github.dotnetsupport

import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFeature
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.folding.LanguageFolding
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.format.DotNetFormattingService
import io.github.dotnetsupport.format.DotNetFormattingSettings
import io.github.dotnetsupport.format.FormatterChoice
import io.github.dotnetsupport.lang.CSharpFoldingBuilder
import io.github.dotnetsupport.lang.CSharpColors
import io.github.dotnetsupport.lang.CSharpIdentifierAnnotator
import io.github.dotnetsupport.lang.CSharpLanguage
import io.github.dotnetsupport.lang.CSharpSyntaxHighlighter
import io.github.dotnetsupport.lsp.RoslynPhase
import io.github.dotnetsupport.lsp.RoslynPolicy
import io.github.dotnetsupport.lsp.RoslynServerStatus

/** Roslyn is the authority: what the plugin does by heuristics steps aside while the language server of the project is ready. */
class RoslynPolicyTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        // the server's path (and the heuristics beside it): built-in is the default since 0.1.60
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.SEMANTIC_COLORS, CSharpFeatureSource.ROSLYN)
    }

    private val status get() = project.getService(RoslynServerStatus::class.java)
    private val formatting get() = DotNetFormattingSettings.getInstance(project)

    override fun tearDown() {
        try {
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            status.isReady = false
            status.coloredByServer.clear()
            formatting.formatter = FormatterChoice.AUTO
        } finally {
            super.tearDown()
        }
    }

    private val source = """
        namespace Shop;

        public class Order
        {
            public int Total() { return 1; }
        }
    """.trimIndent()

    fun testIdentifierColorsStepAside() {
        myFixture.configureByText("RoslynPolicyColors.cs", source)
        fun typeColors(): List<HighlightInfo> = myFixture.doHighlighting().filter { it.forcedTextAttributesKey == CSharpIdentifierAnnotator.TYPE }
        assertFalse(typeColors().isEmpty())
        status.isReady = true
        myFixture.type(' ') // a new pass
        // ready is not enough (0.1.44): the heuristics keep the colors until the server's tokens of the file are on screen
        assertFalse(typeColors().isEmpty())
        status.coloredByServer.add(myFixture.file.virtualFile)
        // the plugin restarts the daemon on the file when the server's tokens are shown
        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart(myFixture.file)
        assertEmpty(typeColors())
    }

    fun testFoldingStepsAside() {
        val file = myFixture.configureByText("RoslynPolicyFolding.cs", source)
        val builder = LanguageFolding.INSTANCE.allForLanguage(CSharpLanguage).filterIsInstance<CSharpFoldingBuilder>().single()
        assertTrue(builder.buildFoldRegions(file, myFixture.editor.document, false).isNotEmpty())
        status.isReady = true
        assertEmpty(builder.buildFoldRegions(file, myFixture.editor.document, false))
    }

    /** The server does the work of `dotnet format whitespace`; CSharpier is the choice of the team and "None" is nobody. */
    fun testWhoFormats() {
        assertTrue(RoslynPolicy.formatsByServer(FormatterChoice.DOTNET_FORMAT, serverReady = true))
        assertFalse(RoslynPolicy.formatsByServer(FormatterChoice.DOTNET_FORMAT, serverReady = false))
        assertFalse(RoslynPolicy.formatsByServer(FormatterChoice.CSHARPIER, serverReady = true))
        assertFalse(RoslynPolicy.formatsByServer(FormatterChoice.NONE, serverReady = true))
        assertFalse("Built-in: the plugin's formatter", RoslynPolicy.formatsByServer(FormatterChoice.BUILT_IN, serverReady = true))
        assertTrue("Built-in, heuristic tree: the server stands in", RoslynPolicy.formatsByServer(FormatterChoice.BUILT_IN, serverReady = true, nativeTree = false))

        val file = myFixture.configureByText("RoslynPolicyFormat.cs", source)
        val service = DotNetFormattingService()
        formatting.formatter = FormatterChoice.DOTNET_FORMAT
        assertTrue(service.canFormat(file))
        status.isReady = true
        assertFalse("left to the language server", service.canFormat(file))
        formatting.formatter = FormatterChoice.CSHARPIER
        assertTrue(service.canFormat(file))
        formatting.formatter = FormatterChoice.NONE
        assertFalse(service.canFormat(file))
    }

    fun testStatusInTheWidget() {
        assertEquals(": starting...", RoslynPolicy.statusText(RoslynPhase.STARTING, null))
        assertEquals(": select a solution to load", RoslynPolicy.statusText(RoslynPhase.CHOOSING_SOLUTION, null))
        assertEquals(": loading Shop.sln...", RoslynPolicy.statusText(RoslynPhase.LOADING, "Shop.sln"))
        assertEquals(": loading projects...", RoslynPolicy.statusText(RoslynPhase.LOADING, null))
        assertEquals(": Shop.sln", RoslynPolicy.statusText(RoslynPhase.READY, "Shop.sln"))
        assertEquals("", RoslynPolicy.statusText(RoslynPhase.READY, null))
    }

    /** The server is a .NET 10 program: a crash right after the start is explained by `dotnet --list-runtimes`. */
    fun testServerRuntime() {
        val runtimes = """
            Microsoft.AspNetCore.App 9.0.4 [C:\Program Files\dotnet\shared\Microsoft.AspNetCore.App]
            Microsoft.NETCore.App 8.0.15 [C:\Program Files\dotnet\shared\Microsoft.NETCore.App]
            Microsoft.NETCore.App 9.0.4 [C:\Program Files\dotnet\shared\Microsoft.NETCore.App]
        """.trimIndent()
        assertFalse(RoslynPolicy.hasRuntime(runtimes, 10))
        assertTrue(RoslynPolicy.hasRuntime(runtimes, 9))
        assertTrue(RoslynPolicy.hasRuntime(listOf(runtimes, "Microsoft.NETCore.App 10.0.0-rc.1.25451.107 [/usr/share/dotnet]").joinToString("\n"), 10))
        // the desktop and ASP.NET runtimes of the version are not what the server runs on
        assertFalse(RoslynPolicy.hasRuntime(listOf("Microsoft.AspNetCore.App 10.0.1 [/dotnet]", "Microsoft.WindowsDesktop.App 10.0.1 [/dotnet]").joinToString("\n"), 10))
        assertFalse(RoslynPolicy.hasRuntime("", 10))
    }

    /** Shift+F6 on a declaration must reach the language server: the rename of the platform would end in "needs a language server". */
    fun testRenameOfDeclarationsIsLeftToTheServer() {
        // on both trees: a declaration of either is vetoed (the native one is the default since 0.1.45)
        try {
            for (native in listOf(false, true)) {
                io.github.dotnetsupport.lang.CSharpSyntaxTrees.forceNativeTreeForTests(native)
                val file = myFixture.configureByText("RoslynPolicyRename${if (native) "Native" else "Heuristic"}.cs", source)
                val model = io.github.dotnetsupport.lang.CSharpSyntaxModel.current
                val declarations = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, com.intellij.psi.NavigatablePsiElement::class.java).filter { model.declarationOf(it) != null }
                assertFalse("declarations of the ${if (native) "native" else "heuristic"} tree", declarations.isEmpty())
                for (declaration in declarations) assertTrue(declaration.toString(), com.intellij.refactoring.rename.PsiElementRenameHandler.isVetoed(declaration))
            }
        } finally {
            io.github.dotnetsupport.lang.CSharpSyntaxTrees.forceNativeTreeForTests(null)
        }
    }

    /** Semantic tokens of the server (legend of 5.12, `tools/roslyn-lsp/out/probe.json`) in the palette of the plugin, the same as the native colors. */
    fun testSemanticTokenColors() {
        val expected = mapOf(
            "class" to CSharpColors.CLASS, "recordClass" to CSharpColors.RECORD, "struct" to CSharpColors.STRUCT, "recordStruct" to CSharpColors.RECORD_STRUCT,
            "interface" to CSharpColors.INTERFACE, "enum" to CSharpColors.ENUM, "delegate" to CSharpColors.DELEGATE, "typeParameter" to CSharpColors.TYPE_PARAMETER,
            "namespace" to CSharpColors.NAMESPACE, "method" to CSharpColors.METHOD_CALL, "extensionMethod" to CSharpColors.EXTENSION_METHOD_CALL,
            "property" to CSharpColors.PROPERTY, "field" to CSharpColors.FIELD, "event" to CSharpColors.EVENT, "enumMember" to CSharpColors.CONSTANT,
            "constant" to CSharpColors.CONSTANT, "variable" to CSharpColors.LOCAL_VARIABLE, "parameter" to CSharpColors.PARAMETER, "label" to CSharpColors.LABEL,
        )
        for ((type, key) in expected) assertEquals(type, key, RoslynPolicy.textAttributesKey(type))
        // modifiers: `static`, and a local written after its declaration
        assertEquals(CSharpColors.STATIC_CLASS, RoslynPolicy.textAttributesKey("class", listOf("static")))
        assertEquals(CSharpColors.STATIC_METHOD_CALL, RoslynPolicy.textAttributesKey("method", listOf("static")))
        assertEquals(CSharpColors.STATIC_FIELD, RoslynPolicy.textAttributesKey("field", listOf("static")))
        assertEquals(CSharpColors.STATIC_PROPERTY, RoslynPolicy.textAttributesKey("property", listOf("static")))
        assertEquals(CSharpColors.MUTABLE_LOCAL_VARIABLE, RoslynPolicy.textAttributesKey("variable", listOf("ReassignedVariable")))
        assertEquals(CSharpSyntaxHighlighter.KEYWORD, RoslynPolicy.textAttributesKey("keyword"))
        // the lexer has colored these
        for (type in listOf("comment", "string", "number", "punctuation", "operator", "xmlDocCommentText")) assertNull(type, RoslynPolicy.textAttributesKey(type))
    }
}
