package io.github.dotnetsupport

import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFeature
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import io.github.dotnetsupport.lang.CSharpIdentifierAnnotator
import io.github.dotnetsupport.lsp.RoslynServerStatus
import io.github.dotnetsupport.roslyn.RoslynClientDescriptor
import io.github.dotnetsupport.roslyn.RoslynLsp4jClient
import io.github.dotnetsupport.roslyn.RoslynWorkspace
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Colors of identifiers between the heuristics of the plugin and the semantic tokens of the server, and the end of the server after the
 * project is closed (0.1.44, found by `tools/ui-robot/baseline.py`). The server is not started.
 */
class RoslynEditorColorsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        // the server's path (and the heuristics beside it): built-in is the default since 0.1.60
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.SEMANTIC_COLORS, CSharpFeatureSource.ROSLYN)
    }

    private val status get() = project.service<RoslynServerStatus>()

    override fun tearDown() {
        try {
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            status.isReady = false
            status.coloredByServer.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun coloredTypes(): List<String> = myFixture.also { com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart(it.file) }.doHighlighting()
        .filter { it.forcedTextAttributesKey == CSharpIdentifierAnnotator.TYPE }
        .map { myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(it.startOffset, it.endOffset)) }

    /**
     * A ready server is not enough for the heuristics to step aside: until the tokens of the file are on screen the file had no colors
     * at all for 100–300 ms (counted 0 highlights with a color right after the solution was loaded).
     */
    fun testHeuristicsStepAsideOnlyForAFileColoredByTheServer() {
        val file = myFixture.configureByText("Colors.cs", "class Order { Stream Open() => null; }").virtualFile
        status.isReady = true
        assertFalse(RoslynServerStatus.colorsIdentifiers(project, file))
        assertEquals(listOf("Order", "Stream"), coloredTypes())

        status.coloredByServer.add(file)
        assertTrue(RoslynServerStatus.colorsIdentifiers(project, file))
        assertEquals("the tokens of the server color the file now", emptyList<String>(), coloredTypes())
    }

    /**
     * `workspace/semanticTokens/refresh` handled by the platform forgets every token at once and shows none until the next answer (four
     * drops to no colors while aspnetcore loaded). The plugin takes the message over; everything else still goes to the platform.
     */
    fun testRefreshOfTokensIsTakenOverAndTheRestPassesThrough() {
        val calls = mutableListOf<String>()
        val platform = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(LspServerNotificationsHandler::class.java)) { _, method, _ ->
            calls += method.name
            if (method.returnType == java.util.concurrent.CompletableFuture::class.java) java.util.concurrent.CompletableFuture.completedFuture(null) else null
        } as LspServerNotificationsHandler
        var refreshes = 0
        val events = object : RoslynLsp4jClient.Events {
            override fun projectsLoaded() = Unit
            override fun projectsNeedRestore(projectFiles: List<String>) = Unit
            override fun semanticTokensRefresh() {
                refreshes++
            }
        }
        val client = RoslynLsp4jClient(platform, events)
        client.refreshSemanticTokens().get()
        client.logMessage(MessageParams(MessageType.Info, "hello"))
        client.refreshInlayHints().get()
        assertEquals(1, refreshes)
        assertEquals(listOf("logMessage", "refreshInlayHints"), calls)
    }

    /** The plugin's refresh makes the tokens the platform has stale (they are kept against the PSI modification count) without dropping them. */
    fun testRefreshMakesTheTokensOfThePlatformStale() {
        myFixture.configureByText("Refresh.cs", "class A { }")
        val tracker = PsiModificationTracker.getInstance(project)
        val before = tracker.modificationCount
        project.service<RoslynWorkspace>().refreshSemanticTokens()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(tracker.modificationCount > before)
    }

    /**
     * Closing a project stops its server, and the process prints its last line and ends after the project is disposed: the listeners
     * looked the workspace up then and logged `AlreadyDisposedException` (seen in idea.log after every close).
     */
    fun testTheEndOfTheServerAfterTheProjectIsClosed() {
        var closed = false
        val real = project
        val closing = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Project::class.java)) { _, method, arguments ->
            when {
                method.name == "isDisposed" -> closed
                closed && method.name.startsWith("getService") -> throw AlreadyDisposedException("the project is closed")
                else -> try {
                    method.invoke(real, *(arguments ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as Project
        val root = myFixture.addFileToProject("RoslynClosed/Program.cs", "class Program { }").virtualFile.parent
        val descriptor = RoslynClientDescriptor(closing, root, java.io.File("/tools/roslyn-language-server"))
        val listener = descriptor.processListener()
        val workspace = RoslynWorkspace(closing)
        closed = true

        listener.onTextAvailable(ProcessEvent(NopProcessHandler(), "Language server child exited cleanly.\n"), ProcessOutputTypes.STDERR)
        listener.processTerminated(ProcessEvent(NopProcessHandler(), -1))
        workspace.serverStopped(shutdownNormally = true)
        workspace.projectsLoaded()
        workspace.serverInitialized()
    }
}
