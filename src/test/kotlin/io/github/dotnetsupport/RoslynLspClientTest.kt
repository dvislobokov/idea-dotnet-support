package io.github.dotnetsupport

import com.intellij.openapi.project.guessProjectDir
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lsp.RoslynLanguageServer
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynLogLevel
import io.github.dotnetsupport.lsp.RoslynWorkspaceTarget
import io.github.dotnetsupport.roslyn.ProjectOpenParams
import io.github.dotnetsupport.roslyn.RoslynClientDescriptor
import io.github.dotnetsupport.roslyn.RoslynLsp4jClient
import io.github.dotnetsupport.roslyn.RoslynLspIntegrationProvider
import io.github.dotnetsupport.roslyn.RoslynServer
import io.github.dotnetsupport.roslyn.RoslynWorkspace
import io.github.dotnetsupport.roslyn.SolutionOpenParams
import io.github.dotnetsupport.roslyn.isCSharpSource
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The client of roslyn-language-server: what is specific to Roslyn, on the wire and in the choice of what to load. The server is not started. */
class RoslynLspClientTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            RoslynLspIntegrationProvider.startInTests = false
            RoslynLanguageServerSettings.getInstance().loadState(RoslynLanguageServerSettings.Settings())
        } finally {
            super.tearDown()
        }
    }

    fun testWorkspaceTarget() {
        fun target(solutions: List<String>, chosen: String? = null, projects: List<String> = emptyList()) = RoslynLanguageServer.workspaceTarget(solutions, chosen, projects)
        // a solution wins over loose projects, and the only one needs no question
        assertEquals(RoslynWorkspaceTarget.Solution("/w/src/Shop.sln"), target(listOf("/w/src/Shop.sln"), projects = listOf("/w/A/A.csproj")))
        assertEquals(RoslynWorkspaceTarget.Solution("/w/src/Shop.sln"), target(listOf("/w/src/Shop.sln"), chosen = "/w/Gone.sln"))
        // several: the user is asked, until there is a choice that still exists
        assertEquals(RoslynWorkspaceTarget.Choice(listOf("/w/a/All.slnx", "/w/Shop.sln")), target(listOf("/w/Shop.sln", "/w/a/All.slnx")))
        assertEquals(RoslynWorkspaceTarget.Solution("/w/a/All.slnx"), target(listOf("/w/Shop.sln", "/w/a/All.slnx"), chosen = "/w/a/All.slnx"))
        assertEquals(RoslynWorkspaceTarget.Choice(listOf("/w/a/All.slnx", "/w/Shop.sln")), target(listOf("/w/Shop.sln", "/w/a/All.slnx"), chosen = "/w/Gone.sln"))
        assertEquals(RoslynWorkspaceTarget.Projects(listOf("/w/A/A.csproj", "/w/b/B.csproj")), target(emptyList(), projects = listOf("/w/b/B.csproj", "/w/A/A.csproj")))
        assertNull(target(emptyList()))
    }

    /** A solution is opened by the plugin, so the server must not be told to look for one as well. */
    fun testNoAutoLoadNextToASolution() {
        val state = RoslynLanguageServerSettings.Settings()
        assertTrue("--autoLoadProjects" in RoslynLanguageServer.arguments(state, "/logs", null, solutionFound = false))
        assertFalse("--autoLoadProjects" in RoslynLanguageServer.arguments(state, "/logs", null, solutionFound = true))
    }

    /** Solutions are looked for in the whole opened folder, build output aside; the choice is kept relative to the folder. */
    fun testSolutionsOfTheFolder() {
        myFixture.addFileToProject("RoslynScan/Shop.sln", "")
        myFixture.addFileToProject("RoslynScan/tools/deep/Tools.slnx", "<Solution/>")
        myFixture.addFileToProject("RoslynScan/bin/Debug/Copy.sln", "")
        myFixture.addFileToProject("RoslynScan/App/App.csproj", "<Project/>")
        val workspace = project.getService(RoslynWorkspace::class.java)
        val found = workspace.scan()
        val solutions = found.solutions.filter { "/RoslynScan/" in it }.map { it.substringAfter("/RoslynScan/") }.sorted()
        assertEquals(listOf("Shop.sln", "tools/deep/Tools.slnx"), solutions)
        assertTrue(found.projects.any { it.endsWith("/RoslynScan/App/App.csproj") })
        assertEquals(found.solutions, workspace.knownSolutions)
        try {
            assertTrue(workspace.workspaceTarget(found) is RoslynWorkspaceTarget.Choice)
            val root = project.guessProjectDir()!!.path
            workspace.state.solution = found.solutions.first { it.endsWith("Tools.slnx") }.removePrefix("$root/")
            assertEquals(RoslynWorkspaceTarget.Solution(found.solutions.first { it.endsWith("Tools.slnx") }), workspace.workspaceTarget(found))
        } finally {
            workspace.state.solution = null
        }
    }

    /** The folder of the workspace as `--autoLoadProjects` can read it (seen live: `/c:/...` "does not exist, skipping"). */
    fun testWorkspaceFolderUri() {
        assertEquals("file:///c:/Users/me/My%20Shop", RoslynLanguageServer.plainDriveUri("file:///c%3A/Users/me/My%20Shop"))
        assertEquals("file:///home/me/a%3Ab", RoslynLanguageServer.plainDriveUri("file:///home/me/a%3Ab"))
        assertEquals("file:///C:/w", RoslynLanguageServer.plainDriveUri("file:///C:/w"))
    }

    fun testWhatNeedsARestart() {
        val state = RoslynLanguageServerSettings.Settings()
        val before = RoslynLanguageServer.commandLineKey(state)
        state.options = mutableMapOf("completion.dotnet_provide_regex_completions" to "false")
        state.additionalOptions = "a = b"
        assertEquals("options travel by workspace/configuration", before, RoslynLanguageServer.commandLineKey(state))
        state.logLevel = RoslynLogLevel.Trace
        assertFalse(before == RoslynLanguageServer.commandLineKey(state))
        assertFalse(RoslynLanguageServer.commandLineKey(RoslynLanguageServerSettings.Settings().apply { enabled = false }) == before)
    }

    /** lsp4j finds the methods of Roslyn next to the standard ones; a lost annotation would make the server talk to nobody. */
    fun testProtocolMethodsAreRegistered() {
        val server = ServiceEndpoints.getSupportedMethods(RoslynServer::class.java)
        assertTrue(server.keys.containsAll(listOf("solution/open", "project/open", "initialize", "textDocument/completion")))
        assertTrue(server.getValue("solution/open").isNotification)
        val client = ServiceEndpoints.getSupportedMethods(RoslynLsp4jClient::class.java)
        assertTrue(client.getValue("workspace/projectInitializationComplete").isNotification)
        assertFalse(client.getValue("workspace/_roslyn_projectNeedsRestore").isNotification)
        assertTrue("workspace/configuration" in client.keys)
    }

    /** The JSON the server gets is the one `tools/roslyn-lsp/bench.py` has checked against the real server. */
    fun testMessagesToTheServer() {
        val sent = ByteArrayOutputStream()
        val launcher = Launcher.createLauncher(Any(), RoslynServer::class.java, PipedInputStream(PipedOutputStream()), sent)
        launcher.remoteProxy.openSolution(SolutionOpenParams("file:///c:/w/Shop.sln"))
        launcher.remoteProxy.openProjects(ProjectOpenParams(listOf("file:///c:/w/A/A.csproj")))
        val text = sent.toString(Charsets.UTF_8)
        assertTrue(text, """"method":"solution/open","params":{"solution":"file:///c:/w/Shop.sln"}""" in text)
        assertTrue(text, """"method":"project/open","params":{"projects":["file:///c:/w/A/A.csproj"]}""" in text)
    }

    /** What the server sends, as recorded from server 5.12: the end of loading has no params at all. */
    fun testMessagesFromTheServer() {
        val loaded = CountDownLatch(1)
        var restore: List<String>? = null
        val events = object : RoslynLsp4jClient.Events {
            override fun projectsLoaded() = loaded.countDown()
            override fun projectsNeedRestore(projectFiles: List<String>) { restore = projectFiles }
        }
        val handler = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(com.intellij.platform.lsp.api.LspServerNotificationsHandler::class.java)) { _, method, _ ->
            error("unexpected ${method.name}")
        } as com.intellij.platform.lsp.api.LspServerNotificationsHandler
        val toClient = PipedOutputStream()
        val answers = ByteArrayOutputStream()
        val launcher = Launcher.createLauncher(RoslynLsp4jClient(handler, events), RoslynServer::class.java, PipedInputStream(toClient), answers)
        val listening = launcher.startListening()
        try {
            fun send(json: String) = toClient.write("Content-Length: ${json.toByteArray().size}\r\n\r\n$json".toByteArray()).also { toClient.flush() }
            send("""{"jsonrpc":"2.0","id":7,"method":"workspace/_roslyn_projectNeedsRestore","params":{"projectFilePaths":["C:\\w\\A\\A.csproj"]}}""")
            send("""{"jsonrpc":"2.0","method":"workspace/projectInitializationComplete"}""")
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("C:\\w\\A\\A.csproj"), restore)
            assertTrue(answers.toString(Charsets.UTF_8), """"id":7,"result":null""" in answers.toString(Charsets.UTF_8))
        } finally {
            toClient.close()
            listening.cancel(true)
        }
    }

    fun testSupportedFiles() {
        assertTrue(isCSharpSource(myFixture.addFileToProject("RoslynLsp/Program.cs", "class Program { }").virtualFile))
        assertTrue(isCSharpSource(myFixture.addFileToProject("RoslynLsp/Upper.CS", "").virtualFile))
        assertFalse(isCSharpSource(myFixture.addFileToProject("RoslynLsp/RoslynLsp.csproj", "<Project/>").virtualFile))
        assertFalse(isCSharpSource(myFixture.addFileToProject("RoslynLsp/Page.cshtml", "").virtualFile))
    }

    /** The module with the client is loaded and its provider is the one the platform asks when a file is opened. */
    fun testProviderIsRegistered() {
        val providers = com.intellij.openapi.extensions.ExtensionPointName.create<LspIntegrationProvider>("com.intellij.platform.lsp.integrationProvider").extensionList
        assertEquals(1, providers.count { it is RoslynLspIntegrationProvider })
        assertTrue(LspClientManager.getInstance(project).getClients(RoslynLspIntegrationProvider::class.java).isEmpty())
    }

    /** What the platform gets from the descriptor: the command line of the settings, the language id, the protocol of Roslyn, the answers to the server. */
    fun testDescriptor() {
        val file = myFixture.addFileToProject("RoslynLspDescriptor/Program.cs", "class Program { }").virtualFile
        val descriptor = RoslynClientDescriptor(project, file.parent, java.io.File("/tools/roslyn-language-server"))
        val commandLine = descriptor.createCommandLine()
        assertEquals(java.io.File("/tools/roslyn-language-server").path, commandLine.exePath)
        assertEquals(listOf("--stdio", "--logLevel", "Information"), commandLine.parametersList.list.take(3))
        assertTrue(commandLine.parametersList.list.containsAll(listOf("--autoLoadProjects", "--clientProcessId", ProcessHandle.current().pid().toString())))
        assertEquals("csharp", descriptor.getLanguageId(file))
        assertTrue(descriptor.isSupportedFile(file))
        assertEquals(RoslynServer::class.java, descriptor.lsp4jServerClass)
        assertEquals("en", descriptor.createInitializeParams().locale)
        assertEquals(true, descriptor.getWorkspaceConfiguration(org.eclipse.lsp4j.ConfigurationItem().apply { section = "csharp|code_lens.dotnet_enable_references_code_lens" }))
        assertEquals(4, descriptor.getWorkspaceConfiguration(org.eclipse.lsp4j.ConfigurationItem().apply { section = "csharp|code_style.formatting.indentation_and_spacing.indent_size" }))
        assertNull(descriptor.getWorkspaceConfiguration(org.eclipse.lsp4j.ConfigurationItem().apply { section = "razor.format.attribute_indent_style" }))
        assertFalse("no diagnostics of a workspace that is still loading", descriptor.lspCustomization.diagnosticsCustomizer.let {
            (it as com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport).shouldAskServerForDiagnostics(file)
        })
    }

    /**
     * Roslyn tags every diagnostic with numbers of Visual Studio, lsp4j reads them as nulls, and a null sent back in
     * `textDocument/codeAction` makes the server refuse the request: seen live as no quick fixes at all.
     */
    fun testUnknownDiagnosticTagsAreNotSentBack() {
        val json = """{"range":{"start":{"line":1,"character":2},"end":{"line":1,"character":5}},"code":"IDE0059","message":"m","tags":[2147483642,2147483645,1]}"""
        val diagnostic = org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler(emptyMap()).gson.fromJson(json, org.eclipse.lsp4j.Diagnostic::class.java)
        assertEquals(listOf(null, null, org.eclipse.lsp4j.DiagnosticTag.Unnecessary), diagnostic.tags)
        io.github.dotnetsupport.roslyn.RoslynServerWrapper.dropUnknownTags(diagnostic)
        assertEquals(listOf(org.eclipse.lsp4j.DiagnosticTag.Unnecessary), diagnostic.tags)

        val onlyUnknown = org.eclipse.lsp4j.Diagnostic().apply { tags = listOf(null, null) }
        io.github.dotnetsupport.roslyn.RoslynServerWrapper.dropUnknownTags(onlyUnknown)
        assertNull(onlyUnknown.tags)
    }

    /**
     * `public const string`: without the contributor that restarts a completion begun on an empty prefix, the popup came for the first
     * word only (seen live, `tools/ui-robot/scripts/type_text.js` shows it letter by letter). It must be there for C#, and before the others.
     */
    fun testCompletionRestartIsRegisteredForCSharp() {
        val contributors = com.intellij.codeInsight.completion.CompletionContributor.forLanguage(io.github.dotnetsupport.lang.CSharpLanguage)
        val restart = contributors.indexOfFirst { it is io.github.dotnetsupport.roslyn.RoslynCompletionRestart }
        assertTrue(restart >= 0)
        val lsp = contributors.indexOfFirst { it.javaClass.name.endsWith("LspCompletionContributor") }
        assertTrue("before the contributor of the LSP client ($restart, $lsp)", lsp < 0 || restart < lsp)
    }

    /** A test that opens a C# file must not start the server that happens to be installed on the machine. */
    fun testNothingStartsInTests() {
        val started = mutableListOf<Any>()
        val file = myFixture.addFileToProject("RoslynLspQuiet/Program.cs", "class Program { }").virtualFile
        RoslynLspIntegrationProvider().fileOpened(project, file, object : LspIntegrationProvider.LspClientStarter {
            override fun ensureClientStarted(descriptor: com.intellij.platform.lsp.api.LspClientDescriptor) { started += descriptor }
        })
        assertEmpty(started)
    }

    /** Switched off on the settings page: a C# file starts nothing, and the listener of the page reaches the project without a server. */
    fun testDisabledStartsNothing() {
        RoslynLanguageServerSettings.getInstance().state.enabled = false
        RoslynLspIntegrationProvider.startInTests = true
        val started = mutableListOf<Any>()
        val file = myFixture.addFileToProject("RoslynLspOff/Program.cs", "class Program { }").virtualFile
        RoslynLspIntegrationProvider().fileOpened(project, file, object : LspIntegrationProvider.LspClientStarter {
            override fun ensureClientStarted(descriptor: com.intellij.platform.lsp.api.LspClientDescriptor) { started += descriptor }
        })
        assertEmpty(started)
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.syncPublisher(RoslynLanguageServerSettings.CHANGED).settingsChanged(true)
        assertFalse(project.getService(RoslynWorkspace::class.java).isLoaded)
    }
}
