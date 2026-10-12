package io.github.dotnetsupport

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import io.github.dotnetsupport.lang.CSharpColors
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpOpeningColors
import io.github.dotnetsupport.lang.CSharpSyntaxHighlighter
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpSemanticColors
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The colors painted as a C# editor opens (0.1.84, [CSharpOpeningColors]): they are what the annotators paint, the remembered ones are for
 * the same text only, and the layer gives way to the daemon.
 */
class CSharpOpeningColorsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.SEMANTIC_COLORS, CSharpFeatureSource.NATIVE)
        CSharpOpeningColors.forgetAll()
    }

    override fun tearDown() {
        try {
            CSharpOpeningColors.forgetAll()
            settings.state.features = mutableMapOf()
            settings.state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val code = """
        using System;
        using System.Collections.Generic;

        namespace Opening;

        public record Point(int X, int Y);

        public static class Shapes
        {
            private const int Limit = 10;
            public static int Count { get; set; }

            public static string Describe(List<Point> points, string title)
            {
                var total = 0;
                foreach (var point in points) total += point.X;
        #if NEVER_DEFINED
                Console.WriteLine("inactive");
        #endif
                int Local(int value) => value * Limit;
                return string.Format("{0}: {1,5:N2}", title, Local(total));
            }
        }
    """.trimIndent()

    private fun show(colors: List<Pair<TextRange, TextAttributesKey>>, text: CharSequence): List<String> =
        colors.map { (range, key) -> "${range.startOffset}-${range.endOffset} ${range.subSequence(text)}:${key.externalName}" }.sorted()

    /** The colors of the annotators the daemon has put on [file]'s editor: its INFORMATION highlights of the palette. */
    private fun daemonColors(): List<Pair<TextRange, TextAttributesKey>> = myFixture.doHighlighting(HighlightSeverity.INFORMATION).filter { it.severity == HighlightSeverity.INFORMATION }
        .mapNotNull { info -> keyOf(info)?.takeIf(CSharpOpeningColors::isColorKey)?.let { TextRange(info.startOffset, info.endOffset) to it } }

    private fun keyOf(info: HighlightInfo): TextAttributesKey? = info.forcedTextAttributesKey ?: info.type.attributesKey

    private fun computed(): List<Pair<TextRange, TextAttributesKey>> = ReadAction.compute<List<Pair<TextRange, TextAttributesKey>>, RuntimeException> {
        CSharpOpeningColors.compute(myFixture.file as CSharpFile)
    }

    fun testTheComputedColorsAreWhatTheAnnotatorsPaint() {
        myFixture.configureByText("OpeningNative.cs", code)
        val text = myFixture.editor.document.immutableCharSequence
        val daemon = show(daemonColors(), text)
        val computed = show(computed(), text)
        assertEquals(daemon, computed)
        // each source of the annotators is there: identifiers (native), inactive text, format items
        assertTrue(computed.toString(), computed.any { it.endsWith(" Describe:${CSharpColors.STATIC_METHOD_DECLARATION.externalName}") })
        assertTrue(computed.toString(), computed.any { it.endsWith(":${CSharpColors.INACTIVE_BRANCH.externalName}") })
        assertTrue(computed.toString(), computed.any { it.endsWith(" {1,5:N2}:${CSharpSyntaxHighlighter.FORMAT_ITEM.externalName}") })
        // the semantic part is cached on the file: the pass of the daemon after the opening colors does not compute it again
        val file = myFixture.file as CSharpFile
        assertSame(ReadAction.compute<Any, RuntimeException> { NativeCSharpSemanticColors.colors(file) }, ReadAction.compute<Any, RuntimeException> { NativeCSharpSemanticColors.colors(file) })
    }

    fun testTheHeuristicColorsWhenTheNativeOnesDoNotServe() {
        settings.state.enabled = true
        settings.setSource(CSharpFeature.SEMANTIC_COLORS, CSharpFeatureSource.ROSLYN)
        myFixture.configureByText("OpeningHeuristic.cs", code)
        val text = myFixture.editor.document.immutableCharSequence
        val computed = show(computed(), text)
        assertEquals(show(daemonColors(), text), computed)
        assertTrue(computed.toString(), computed.any { it.endsWith(" Describe:${CSharpColors.METHOD.externalName}") })
    }

    fun testRememberedColorsAreForTheSameTextOnly() {
        val colors = listOf(TextRange(0, 5) to CSharpColors.TYPE, TextRange(6, 9) to CSharpColors.METHOD)
        CSharpOpeningColors.remember("/x/A.cs", "class A { }", colors)
        assertEquals(colors, CSharpOpeningColors.remembered("/x/A.cs", "class A { }"))
        assertNull(CSharpOpeningColors.remembered("/x/A.cs", "class B { }"))
        assertNull(CSharpOpeningColors.remembered("/x/A.cs", "class A { } "))
        assertNull(CSharpOpeningColors.remembered("/x/B.cs", "class A { }"))
    }

    fun testRememberedColorsArePaintedAsTheEditorIsCreated() {
        CSharpOpeningColors.enableInTests(testRootDisposable)
        val file = myFixture.addFileToProject("OpeningRemembered.cs", code).virtualFile
        val remembered = listOf(TextRange(code.indexOf("Shapes"), code.indexOf("Shapes") + 6) to CSharpColors.STATIC_CLASS)
        CSharpOpeningColors.remember(file.path, code, remembered)
        myFixture.openFileInEditor(file)
        // no wait: they are there before the editor is painted
        assertEquals(remembered, CSharpOpeningColors.layer(myFixture.editor).map { it.textRange to it.textAttributesKey!! })
        giveWayToTheDaemon()
    }

    fun testComputedColorsArePaintedWhenNothingIsRemembered() {
        CSharpOpeningColors.enableInTests(testRootDisposable)
        val file = myFixture.addFileToProject("OpeningComputed.cs", code).virtualFile
        CSharpOpeningColors.remember(file.path, "$code\n// another text", listOf(TextRange(0, 5) to CSharpColors.TYPE))
        myFixture.openFileInEditor(file)
        assertEmpty(CSharpOpeningColors.layer(myFixture.editor))
        // computed in the background, painted on the EDT
        val deadline = System.currentTimeMillis() + 20_000
        while (CSharpOpeningColors.layer(myFixture.editor).isEmpty() && System.currentTimeMillis() < deadline) {
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
        val text = myFixture.editor.document.immutableCharSequence
        assertEquals(show(computed(), text), show(CSharpOpeningColors.layer(myFixture.editor).map { it.textRange to it.textAttributesKey!! }, text))
        giveWayToTheDaemon()
    }

    private fun layerShown(): List<String> =
        show(CSharpOpeningColors.layer(myFixture.editor).map { it.textRange to it.textAttributesKey!! }, myFixture.editor.document.immutableCharSequence)

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
    }

    /** A file opened while the IDE indexes gets the plain colors of the tokens at once, and those of the stubs as soon as the indexes are ready. */
    fun testTheColorsOfDumbModeGiveWayToTheSemanticOnesWhenTheIndexesAreReady() {
        CSharpOpeningColors.enableInTests(testRootDisposable)
        val file = myFixture.addFileToProject("OpeningDumb.cs", code).virtualFile
        val token = com.intellij.testFramework.DumbModeTestUtils.startEternalDumbModeTask(project)
        try {
            myFixture.openFileInEditor(file)
            waitFor { CSharpOpeningColors.layer(myFixture.editor).isNotEmpty() }
            assertTrue(layerShown().toString(), layerShown().any { it.endsWith(" Describe:${CSharpColors.METHOD.externalName}") })
        } finally {
            com.intellij.testFramework.DumbModeTestUtils.endEternalDumbModeTaskAndWaitForSmartMode(project, token)
        }
        waitFor { layerShown().any { it.endsWith(" Describe:${CSharpColors.STATIC_METHOD_DECLARATION.externalName}") } }
        assertEquals(show(computed(), myFixture.editor.document.immutableCharSequence), layerShown())
        giveWayToTheDaemon()
    }

    /** The daemon's colors come, it finishes: the layer is gone, and the daemon's colors are what it showed. */
    private fun giveWayToTheDaemon() {
        val editor = myFixture.editor
        val daemon = daemonColors()
        assertNotEmpty(daemon)
        val textEditor = FileEditorManagerEx.getInstanceEx(project).getEditors(myFixture.file.virtualFile).filterIsInstance<TextEditor>().first { it.editor == editor }
        CSharpOpeningColors.Daemon().daemonFinished(listOf(textEditor))
        assertEmpty(CSharpOpeningColors.layer(editor))
        assertTrue(editor.markupModel.allHighlighters.none { CSharpOpeningColors.isColorKey(it.textAttributesKey) })
    }

    fun testTheColorsOfTheClosedEditorAreRememberedForItsText() {
        CSharpOpeningColors.enableInTests(testRootDisposable)
        val file = myFixture.addFileToProject("OpeningClosed.cs", code).virtualFile
        myFixture.openFileInEditor(file)
        val daemon = daemonColors()
        val textEditor = FileEditorManagerEx.getInstanceEx(project).getEditors(file).filterIsInstance<TextEditor>().first()
        CSharpOpeningColors.Daemon().daemonFinished(listOf(textEditor))
        FileEditorManagerEx.getInstanceEx(project).closeFile(file)
        val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)!!.immutableCharSequence
        assertEquals(show(daemon, text), show(CSharpOpeningColors.remembered(file.path, text)!!, text))
    }
}
