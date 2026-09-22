package io.github.dotnetsupport

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.folding.LanguageFolding
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.format.DotNetFormattingService
import io.github.dotnetsupport.format.DotNetFormattingSettings
import io.github.dotnetsupport.format.FormatterChoice
import io.github.dotnetsupport.lang.CSharpFoldingBuilder
import io.github.dotnetsupport.lang.CSharpIdentifierAnnotator
import io.github.dotnetsupport.lang.CSharpLanguage
import io.github.dotnetsupport.lang.CSharpSyntaxHighlighter
import io.github.dotnetsupport.lsp.RoslynPhase
import io.github.dotnetsupport.lsp.RoslynPolicy
import io.github.dotnetsupport.lsp.RoslynServerStatus

/** Roslyn is the authority: what the plugin does by heuristics steps aside while the language server of the project is ready. */
class RoslynPolicyTest : BasePlatformTestCase() {
    private val status get() = project.getService(RoslynServerStatus::class.java)
    private val formatting get() = DotNetFormattingSettings.getInstance(project)

    override fun tearDown() {
        try {
            status.isReady = false
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
        val file = myFixture.configureByText("RoslynPolicyRename.cs", source)
        val declaration = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, io.github.dotnetsupport.lang.CSharpDeclaration::class.java).first()
        assertTrue(com.intellij.refactoring.rename.PsiElementRenameHandler.isVetoed(declaration))
    }

    /** Semantic tokens of the server (legend of 5.12, `tools/roslyn-lsp/out/probe.json`) in the palette of the plugin. */
    fun testSemanticTokenColors() {
        for (type in listOf("class", "struct", "interface", "enum", "delegate", "recordClass", "recordStruct", "typeParameter")) assertEquals(type, CSharpIdentifierAnnotator.TYPE, RoslynPolicy.textAttributesKey(type))
        for (type in listOf("method", "extensionMethod")) assertEquals(type, CSharpIdentifierAnnotator.METHOD, RoslynPolicy.textAttributesKey(type))
        for (type in listOf("property", "field", "event", "enumMember", "constant")) assertEquals(type, CSharpIdentifierAnnotator.MEMBER, RoslynPolicy.textAttributesKey(type))
        assertEquals(CSharpSyntaxHighlighter.KEYWORD, RoslynPolicy.textAttributesKey("keyword"))
        // the lexer has colored these, and locals have no color in the palette
        for (type in listOf("comment", "string", "number", "punctuation", "operator", "variable", "parameter", "xmlDocCommentText")) assertNull(type, RoslynPolicy.textAttributesKey(type))
    }
}
