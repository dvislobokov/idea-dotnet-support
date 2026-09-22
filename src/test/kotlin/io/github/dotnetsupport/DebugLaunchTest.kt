package io.github.dotnetsupport

import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import io.github.dotnetsupport.lang.CSharpBreakpointLines
import io.github.dotnetsupport.run.BuildProjectBeforeRunTaskProvider
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetConfigurationType
import io.github.dotnetsupport.run.DotNetExceptionFilter
import io.github.dotnetsupport.run.DotNetLaunchArguments
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.run.LaunchSettings
import io.github.dotnetsupport.run.MsBuildTargetPath

/** Debug of a ".NET Project" configuration: what is decided without the debug adapter and without `dotnet`. */
class DebugLaunchTest : BasePlatformTestCase() {
    private fun configuration(command: DotNetCommand = DotNetCommand.RUN) =
        DotNetRunConfiguration(project, DotNetConfigurationType.instance.factory, "debug-launch").apply { options.command = command }

    fun testProfileCarriesWhatDotnetRunApplies() {
        val profile = LaunchSettings.profiles(
            """{ "profiles": { "https": { "commandName": "Project", "commandLineArgs": "--seed \"a b\"", "applicationUrl": "https://localhost:7001;http://localhost:5000",
               "environmentVariables": { "ASPNETCORE_ENVIRONMENT": "Development", "Nested": { "no": 1 } }, }, "IIS": { "commandName": "IISExpress" } } }""",
        ).single()
        assertEquals(mapOf("ASPNETCORE_ENVIRONMENT" to "Development"), profile.environmentVariables)
        assertEquals("--seed \"a b\"", profile.commandLineArgs)
    }

    fun testLaunchArgumentsFollowDotnetRun() {
        val profile = LaunchSettings.Profile(
            "https", launchBrowser = false, launchUrl = null, applicationUrl = "https://localhost:7001;http://localhost:5000/",
            environmentVariables = mapOf("ASPNETCORE_ENVIRONMENT" to "Development", "FROM_PROFILE" to "1", "SHARED" to "profile"), commandLineArgs = "--seed \"a b\"",
        )
        val arguments = DotNetLaunchArguments.build(
            "C:/src/App/App.csproj", emptyList(), workingDirectory = " ", environment = mapOf("SHARED" to "table", "MINE" to "2"), environmentName = "Staging", profile,
        )
        assertEquals("C:/src/App/App.csproj", arguments["project"])
        assertEquals(true, arguments["build"])
        assertFalse("program" in arguments)
        assertEquals(listOf("--seed", "a b"), arguments["args"])
        assertEquals(java.io.File("C:/src/App").path, java.io.File(arguments["cwd"] as String).path)
        assertEquals("integratedTerminal", arguments["console"])
        assertEquals(true, arguments["justMyCode"])
        assertEquals(
            mapOf(
                "SHARED" to "profile", "MINE" to "2", "FROM_PROFILE" to "1", // the profile wins over the table of the configuration
                "ASPNETCORE_ENVIRONMENT" to "Staging", "DOTNET_ENVIRONMENT" to "Staging", // the chosen environment wins over the profile
                "ASPNETCORE_URLS" to "https://localhost:7001;http://localhost:5000",
            ),
            arguments["env"],
        )
    }

    fun testOwnArgumentsAndVariablesAreKept() {
        val profile = LaunchSettings.Profile("p", false, null, applicationUrl = "http://localhost:1", commandLineArgs = "--from-profile")
        val arguments = DotNetLaunchArguments.build(
            "/src/App/App.csproj", listOf("--mine"), "/work", mapOf("ASPNETCORE_URLS" to "http://localhost:9", "DOTNET_ENVIRONMENT" to "Mine"), "Staging", profile,
        )
        assertEquals(listOf("--mine"), arguments["args"])
        assertEquals("/work", arguments["cwd"])
        // a variable named in the table is left alone by the environment name, and the URLs of the profile do not replace explicit ones
        assertEquals(mapOf("ASPNETCORE_URLS" to "http://localhost:9", "DOTNET_ENVIRONMENT" to "Mine", "ASPNETCORE_ENVIRONMENT" to "Staging"), arguments["env"])
        assertEquals(emptyMap<String, String>(), DotNetLaunchArguments.build("/a/A.csproj", emptyList(), null, emptyMap(), null, null)["env"])
    }

    fun testBuiltAssemblyReplacesTheProject() {
        fun arguments(targetPath: String?) = linkedMapOf<String, Any?>("request" to "launch", "project" to "/a/A.csproj", "build" to true, "args" to listOf("x"))
            .also { DotNetLaunchArguments.setProgram(it, targetPath) }
        // not built by the IDE: the adapter is the last resort
        assertEquals(mapOf("request" to "launch", "project" to "/a/A.csproj", "build" to true, "args" to listOf("x")), arguments(null))
        // built, the output is unknown: the adapter finds it and builds nothing
        assertEquals(mapOf("request" to "launch", "project" to "/a/A.csproj", "args" to listOf("x")), arguments(""))
        assertEquals(mapOf("request" to "launch", "args" to listOf("x"), "program" to "/a/bin/Debug/net9.0/A.dll"), arguments("/a/bin/Debug/net9.0/A.dll"))
    }

    fun testTargetPath() {
        assertEquals(
            listOf("msbuild", "/a/A.csproj", "-nologo", "-getProperty:TargetPath", "-p:Configuration=Release", "-p:TargetFramework=net9.0", "-p:Platform=x64"),
            MsBuildTargetPath.arguments("/a/A.csproj", "Release", "net9.0", listOf("-p:Platform=x64")),
        )
        assertEquals(listOf("msbuild", "/a/A.csproj", "-nologo", "-getProperty:TargetPath", "-p:Configuration=Debug"), MsBuildTargetPath.arguments("/a/A.csproj", "Debug", null))
        assertEquals("C:\\src\\App\\bin\\Debug\\net9.0\\App.dll", MsBuildTargetPath.parse("C:\\src\\App\\bin\\Debug\\net9.0\\App.dll\r\n"))
        assertEquals("/a/bin/A.dll", MsBuildTargetPath.parse("warning NETSDK1057: preview\n/a/bin/A.dll\n\n"))
        assertNull(MsBuildTargetPath.parse("")) // the outer build of a multi-targeted project
        assertNull(MsBuildTargetPath.parse("MSBUILD : error MSB1009: Project file does not exist."))
    }

    /** Lines with `// BP` are the ones a breakpoint is expected on. */
    private fun assertBreakpointLines(code: String) {
        val text = code.trimIndent()
        val expected = text.lines().withIndex().filter { it.value.trimEnd().endsWith("// BP") }.map { it.index }
        assertEquals(expected, CSharpBreakpointLines.find(text).sorted())
    }

    fun testBreakpointLinesOfAClass() = assertBreakpointLines(
        """
        using System;
        using System.Linq;

        namespace Shop;

        [Serializable]
        public class Order
        {
            private int count = 5; // BP
            private string name;
            public int Total => count * 2; // BP
            public string Name { get; set; } // BP
            // a comment
            [Obsolete]
            public void Run(int x)
            { // BP
                var y = x; // BP
        #if DEBUG
                /* a block */
                Console.WriteLine(y); // BP

        #endif
            } // BP
            public int Short() { return 1; } // BP
            public Order(int n) { // BP
                count = n; // BP
            } // BP
            public abstract void Nothing();
            public int Long => // BP
                count + // BP
                1; // BP
        }

        enum Color { Red, Green }
        """,
    )

    fun testBreakpointLinesOfTopLevelStatements() = assertBreakpointLines(
        """
        using System;

        var x = 1; // BP
        Console.WriteLine(x); // BP
        if (x > 0) // BP
        { // BP
            x++; // BP
        } // BP
        // nothing here

        class Helper
        {
            public static void Do() { } // BP
        }
        """,
    )

    fun testDebugStartsNothingByItself() {
        val configuration = configuration()
        val runner = ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, configuration)!!
        val environment = ExecutionEnvironmentBuilder(project, DefaultDebugExecutor.getDebugExecutorInstance()).runProfile(configuration).runner(runner).build()
        // under a debugger the program is started by the adapter: the state of the configuration starts nothing
        assertNull(configuration.getState(DefaultDebugExecutor.getDebugExecutorInstance(), environment).execute(DefaultDebugExecutor.getDebugExecutorInstance(), runner))
    }

    /** The debugger of the plugin is its own and is there in every IDE: IntelliJ IDEA Community and its forks have no DAP module. */
    fun testOnlyDebugOfRunGoesToTheDebugRunner() {
        val runner = "DotNetDebugRunner"
        assertEquals(runner, ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, configuration())?.runnerId)
        assertFalse(runner == ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration())?.runnerId)
        assertFalse(runner == ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, configuration(DotNetCommand.WATCH))?.runnerId)
        assertFalse(runner == ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, configuration(DotNetCommand.TEST))?.runnerId)
        assertNull("no runner of the platform DAP client", ProgramRunner.findRunnerById("DebugAdapterRunner")?.takeIf { it.canRun(DefaultDebugExecutor.EXECUTOR_ID, configuration()) })
    }

    fun testBuildBeforeLaunch() {
        val provider = BeforeRunTaskProvider.getProvider(project, BuildProjectBeforeRunTaskProvider.ID)
        assertNotNull(provider)
        assertEquals("Build .NET Project", provider!!.name)
        assertTrue(provider.createTask(configuration())!!.isEnabled)
        // only a debugger needs the project built beforehand, `dotnet run` and the others build it themselves
        assertTrue(BuildProjectBeforeRunTaskProvider.isNeeded(DefaultDebugExecutor.EXECUTOR_ID, DotNetCommand.RUN))
        assertFalse(BuildProjectBeforeRunTaskProvider.isNeeded(DefaultRunExecutor.EXECUTOR_ID, DotNetCommand.RUN))
        assertFalse(BuildProjectBeforeRunTaskProvider.isNeeded(DefaultDebugExecutor.EXECUTOR_ID, DotNetCommand.TEST))
    }

    fun testBreakpointTypes() {
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        assertTrue("io.github.dotnetsupport.debugger.CSharpLineBreakpointType" in pluginXml)
        // the ids are the ones of the breakpoints users have saved
        val type = XBreakpointType.EXTENSION_POINT_NAME.extensionList.first { it.id == "dotnet-line" } as XLineBreakpointType<*>
        assertTrue(type is io.github.dotnetsupport.debugger.CSharpLineBreakpointType)
        val file = myFixture.addFileToProject("debugLaunch/Program.cs", "using System;\n\nConsole.WriteLine(1);\n// done\n").virtualFile
        assertFalse(type.canPutAt(file, 0, project))
        assertTrue(type.canPutAt(file, 2, project))
        assertFalse(type.canPutAt(file, 3, project))
        assertFalse(type.canPutAt(myFixture.addFileToProject("debugLaunch/notes.txt", "Console.WriteLine(1);").virtualFile, 0, project))
    }

    fun testDebuggerLogs() {
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        for (id in listOf("DotNet.Debugger.ShowLogs", "DotNet.Debugger.TraceProtocol", "dotnet.debugger.adapter.log", "dotnet.debugger.protocol.trace")) assertTrue(id, "\"$id\"" in pluginXml)
        val logs = io.github.dotnetsupport.debugger.DotNetDebuggerLogs
        assertEquals("adapter-20260921-140509.log", logs.adapterLogName(java.time.LocalDateTime.of(2026, 9, 21, 14, 5, 9)))
        val names = listOf("adapter-20260921-100000.log", "protocol", "adapter-20260921-120000.log", "adapter-20260920-235959.log", "notes.txt")
        assertEquals(listOf("adapter-20260921-100000.log", "adapter-20260920-235959.log"), logs.outdated(names, keep = 1))
        assertEquals(emptyList<String>(), logs.outdated(names, keep = 3))
        assertEquals(listOf("protocol-20260920-1.log"), logs.outdated(listOf("protocol-20260921-1.log", "protocol-20260920-1.log"), keep = 1, prefix = "protocol-"))
        val command = io.github.dotnetsupport.debugger.DebugAdapterProcess.commandLine(java.io.File("/tools/dotnet-debugger"), java.io.File("/logs/a.log"))
        assertEquals(listOf("--log=" + java.io.File("/logs/a.log").path), command.parametersList.list)
        assertEquals(emptyList<String>(), io.github.dotnetsupport.debugger.DebugAdapterProcess.commandLine(java.io.File("/tools/dotnet-debugger"), null).parametersList.list)
    }

    fun testDebuggerSettingsReachTheAdapter() {
        val defaults = DotNetLaunchArguments.build("/a/A.csproj", emptyList(), null, emptyMap(), null, null)
        // the profile is applied by the plugin, by the rules of `dotnet run`: the adapter must not apply the default one again
        assertEquals("", defaults["launchSettingsProfile"])
        assertFalse("configuration" in defaults)
        assertEquals("Release", DotNetLaunchArguments.build("/a/A.csproj", emptyList(), null, emptyMap(), null, null, configuration = "Release")["configuration"])

        val settings = io.github.dotnetsupport.settings.DotNetSettings.getInstance()
        val before = settings.debugExternalSource to settings.debugAllowImplicitEvaluation
        try {
            settings.debugExternalSource = false
            settings.debugAllowImplicitEvaluation = true
            configuration().debugLaunchArguments().let {
                assertEquals(true, it["justMyCode"])
                assertEquals(true, it["allowImplicitFuncEval"])
                assertEquals(io.github.dotnetsupport.build.DotNetBuildSettings.getInstance(project).configuration, it["configuration"])
            }
            settings.debugExternalSource = true
            settings.debugAllowImplicitEvaluation = false
            configuration().debugLaunchArguments().let {
                assertEquals(false, it["justMyCode"])
                assertEquals(false, it["allowImplicitFuncEval"])
            }
        } finally {
            settings.debugExternalSource = before.first
            settings.debugAllowImplicitEvaluation = before.second
        }
    }

    /** `|` marks the mouse; the expected expression, or null when a hover must show nothing. */
    private fun assertHover(expected: String?, code: String) {
        val offset = code.indexOf('|')
        val text = code.removeRange(offset, offset + 1)
        assertEquals(code, expected, io.github.dotnetsupport.lang.CSharpHoverExpression.rangeAt(text, offset)?.substring(text))
    }

    fun testExpressionUnderTheMouse() {
        assertHover("counter", "coun|ter += 10;")
        assertHover("counter", "|counter += 10;")
        assertHover("person", "var x = per|son.Friend.Name;")
        assertHover("person.Friend", "var x = person.Fri|end.Name;")
        assertHover("person.Friend.Name", "var x = person.Friend.Na|me;")
        assertHover("this.count", "return this.cou|nt * 2;")
        assertHover("this", "return th|is.count;")
        assertHover("a?.b", "var y = a?.|b ?? 1;")
        assertHover("a . b", "var y = a . |b;")
        // nothing that runs the code of the program, nothing that is not code, nothing out of its context
        assertHover(null, "var n = Mea|sure(text);")
        assertHover(null, "var n = text.Sub|string(1);")
        assertHover("text", "var n = te|xt.Substring(1);")
        assertHover(null, "var s = \"per|son\";")
        assertHover(null, "// per|son")
        assertHover(null, "ret|urn person;")
        assertHover(null, "var n = Make().Na|me;")
        assertHover(null, "var n = items[0].Na|me;")
        assertHover(null, "x = 4|2;")
        assertHover(null, "x = a |+ b;")
        assertHover(null, "|")
        assertHover(null, "x = 1; |")
    }

    fun testExceptionBreakpointSettings() {
        val breakpoints = io.github.dotnetsupport.run.DotNetExceptionBreakpoints
        assertEquals(listOf("all", "user-unhandled", "unhandled"), DotNetExceptionFilter.entries.map { it.id }) // the ids of the filters of the adapter
        assertNull(breakpoints.typeCondition(null))
        assertNull(breakpoints.typeCondition("  ,; "))
        assertEquals("ShopException, System.IO.*, !System.OperationCanceledException", breakpoints.typeCondition("ShopException;  System.IO.*  !System.OperationCanceledException, ShopException"))
        assertEquals("Any exception (user-unhandled, unhandled)", breakpoints.displayText("", listOf(DotNetExceptionFilter.UNHANDLED, DotNetExceptionFilter.USER_UNHANDLED)))
        assertEquals("ShopException (thrown)", breakpoints.displayText(" ShopException ", listOf(DotNetExceptionFilter.THROWN)))
        assertEquals("Any exception (never)", breakpoints.displayText(null, emptyList()))
    }

    fun testExceptionBreakpointType() {
        val type = XBreakpointType.EXTENSION_POINT_NAME.extensionList.first { it.id == "dotnet-exception" } as io.github.dotnetsupport.debugger.DotNetExceptionBreakpointType
        assertTrue(type.isAddBreakpointButtonVisible)

        // every thrown exception is too noisy to be the default
        val manager = com.intellij.xdebugger.XDebuggerManager.getInstance(project).breakpointManager
        val default = manager.getDefaultBreakpoints(type).single()
        assertTrue(default.isEnabled)
        assertEquals(setOf(DotNetExceptionFilter.USER_UNHANDLED, DotNetExceptionFilter.UNHANDLED), default.properties!!.filters)
        assertEquals("Any exception (user-unhandled, unhandled)", type.getDisplayText(default))

        val added = com.intellij.openapi.application.WriteAction.computeAndWait<io.github.dotnetsupport.debugger.DotNetExceptionBreakpoint, RuntimeException> { type.addBreakpoint(project, null) }
        try {
            assertEquals(setOf(DotNetExceptionFilter.THROWN), added.properties!!.filters)
            added.properties!!.types = "Playground.Lib.ShopException"
            assertEquals("Playground.Lib.ShopException (thrown)", type.getDisplayText(added))

            // what is saved with the breakpoint comes back
            val restored = io.github.dotnetsupport.debugger.DotNetExceptionBreakpointProperties()
            restored.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(added.properties!!.state), io.github.dotnetsupport.debugger.DotNetExceptionBreakpointProperties.State::class.java))
            assertEquals("Playground.Lib.ShopException", restored.types)
            assertEquals(setOf(DotNetExceptionFilter.THROWN), restored.filters)
        } finally {
            com.intellij.openapi.application.WriteAction.runAndWait<RuntimeException> { manager.removeBreakpoint(added) }
        }
    }

    fun testTestHostProcessId() {
        val host = io.github.dotnetsupport.run.TestHostDebug
        assertEquals(12345L, host.processId("Host debugging is enabled. Please attach debugger to testhost process to continue.\nProcess Id: 12345, Name: testhost\n"))
        assertEquals(778L, host.processId("Process Id: 778, Name: dotnet"))
        // a localized CLI: the words differ, the shape does not
        assertEquals(4242L, host.processId("Идентификатор процесса: 4242, имя: testhost"))
        assertNull(host.processId("Passed!  - Failed: 0, Passed: 12, Skipped: 0, Total: 12, Duration: 35 ms"))
        assertNull(host.processId("  Determining projects to restore..."))
        assertNull(host.processId(""))
        // the test host greets its debugger with Debugger.Break(): that stop is skipped, a pause asked by the user is not
        assertTrue(host.isInitialBreak("pause", "Debugger.Break"))
        assertFalse(host.isInitialBreak("pause", "Paused"))
        assertFalse(host.isInitialBreak("breakpoint", null))
    }

    fun testAttachArguments() {
        assertEquals(mapOf("processId" to 4242L, "justMyCode" to true, "allowImplicitFuncEval" to true), DotNetLaunchArguments.attach(4242))
        assertEquals(false, DotNetLaunchArguments.attach(1, justMyCode = false)["justMyCode"])
    }

    fun testWhichProcessesAreOfferedTheDebugger() {
        val processes = io.github.dotnetsupport.run.DotNetProcesses
        val nothing = { _: String -> false }
        assertTrue(processes.isDotNet("dotnet.exe", "\"C:\\Program Files\\dotnet\\dotnet.exe\" exec App.dll", nothing))
        assertTrue(processes.isDotNet("/usr/share/dotnet/dotnet", "dotnet App.dll", nothing))
        assertTrue(processes.isDotNet("testhost.exe", "", nothing))
        // an apphost: App.exe with App.runtimeconfig.json (or App.dll and App.deps.json) next to it
        assertTrue(processes.isDotNet("Playground.Web.exe", "", { it == "Playground.Web.runtimeconfig.json" }))
        assertTrue(processes.isDotNet("App", "", { it == "App.dll" || it == "App.deps.json" }))
        assertFalse(processes.isDotNet("App.exe", "", { it == "App.dll" }))
        assertFalse(processes.isDotNet("chrome.exe", "chrome.exe --type=renderer", nothing))
        // the debug adapter is a .NET process too, and debugging it while it debugs is not what anybody wants
        assertFalse(processes.isDotNet("dotnet-debugger.exe", "", { true }))
    }

    fun testDebugOfTestsNeedsSomebodyToAttach() {
        val tests = configuration(DotNetCommand.TEST)
        assertNotNull("the debugger of the plugin is always there to attach", io.github.dotnetsupport.run.DotNetProcessAttacher.find())
        assertEquals("DotNetTestRunner", ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, tests)?.runnerId)
        assertEquals("DotNetTestRunner", ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, tests)?.runnerId)

        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        assertTrue("<processAttacher " in pluginXml && "<xdebugger.attachDebuggerProvider " in pluginXml)
        // an attach session goes to the debug runner of the plugin with the `attach` request
        val profile = io.github.dotnetsupport.debugger.DotNetAttachProfile(4242L, "testhost")
        assertEquals("DotNetDebugRunner", ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, profile)?.runnerId)
    }

    fun testLineBreakpointKeepsItsHitCountAndLogMessage() {
        val type = XBreakpointType.EXTENSION_POINT_NAME.extensionList.first { it.id == "dotnet-line" }
        // both ways a breakpoint gets its properties: made in the editor, read from the workspace file
        val file = myFixture.addFileToProject("debugLaunch/Extras.cs", "class C { void M() { } }").virtualFile
        val created = (type as XLineBreakpointType<*>).createBreakpointProperties(file, 0)
        val loaded = type.createProperties()
        assertNotNull("a saved hit count would be dropped on load", loaded)
        assertEquals(created!!.javaClass, loaded!!.javaClass)

        val properties = io.github.dotnetsupport.debugger.DotNetLineBreakpointProperties().apply { hitCondition = ">= 3"; logMessage = "total = {total}" }
        val restored = io.github.dotnetsupport.debugger.DotNetLineBreakpointProperties()
        restored.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(properties.state), io.github.dotnetsupport.debugger.DotNetLineBreakpointProperties.State::class.java))
        assertEquals(">= 3" to "total = {total}", restored.hitCondition to restored.logMessage)
    }

    /** `|` is the caret; expected: qualifier (null at a name that stands alone) and the typed prefix, or null where nothing is completed. */
    private fun assertCompletion(expected: Pair<String?, String>?, code: String) {
        val offset = code.indexOf('|')
        val context = io.github.dotnetsupport.lang.CSharpDebugCompletion.contextAt(code.removeRange(offset, offset + 1), offset)
        assertEquals(code, expected, context?.let { it.qualifier to it.prefix })
    }

    fun testWhatIsCompletedInADebuggerExpression() {
        assertCompletion(null to "", "|")
        assertCompletion(null to "per", "per|")
        assertCompletion(null to "co", "x + co|")
        assertCompletion(null to "", "x + |")
        assertCompletion("person" to "", "person.|")
        assertCompletion("person" to "Fr", "person.Fr|")
        assertCompletion("person.Friend" to "Na", "person.Friend.Na|")
        assertCompletion("person" to "", "person?.|")
        assertCompletion("this" to "cou", "this.cou|")
        assertCompletion("person" to "Na", "total + person.Na|")
        // members of a call or of an element would need the call to run while typing
        assertCompletion(null, "Make().|")
        assertCompletion(null, "items[0].Na|")
        assertCompletion(null, "\"text.|")
        assertCompletion(null, "\"per|\"")
        assertCompletion(null, "12|")
        assertCompletion(null, "1.|")

        val completion = io.github.dotnetsupport.lang.CSharpDebugCompletion
        for (name in listOf("Name", "_notes", "count2", "@class", "Имя")) assertTrue(name, completion.isName(name))
        for (other in listOf("[0]", "Raw View", "Static members", "", "2x", "a.b")) assertFalse(other, completion.isName(other))
    }

    fun testExpressionEditorsAreCSharpFragments() {
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        assertTrue("io.github.dotnetsupport.debugger.DotNetExpressionCompletionContributor" in pluginXml)
        val provider: com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider = io.github.dotnetsupport.debugger.DotNetEditorsProvider()
        assertEquals("C#", provider.fileType.name)
        val document = provider.createDocument(project, com.intellij.xdebugger.impl.breakpoints.XExpressionImpl.fromText("person.Age"), null, com.intellij.xdebugger.evaluation.EvaluationMode.EXPRESSION)
        assertEquals("person.Age", document.text)
        val file = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(document)!!
        assertEquals("C#", file.language.id)
        assertEquals(true, file.getUserData(io.github.dotnetsupport.debugger.DotNetEditorsProvider.EXPRESSION))
    }

    fun testLinesThatShowTheValueOfAVariable() {
        val code = """
            class Shop
            {
                int total = 1;
                void Other() { var total = 5; }
                void Run(int x)
                {
                    var total = x * 2;
                    var text = "total";
                    // total in a comment
                    order.total = 3;
                    Console.WriteLine(total + x);
                    total++;
                }
            }
        """.trimIndent()
        val inline = io.github.dotnetsupport.lang.CSharpInlineValues
        // stopped at `Console.WriteLine`: the lines of this method up to it; not the field, not the other method, not what is below
        assertEquals(listOf(6, 10), inline.lines(code, "total", 10))
        assertEquals(listOf(4, 6, 10), inline.lines(code, "x", 10))
        assertEquals(listOf(6), inline.lines(code, "total", 6))
        assertEquals(emptyList<Int>(), inline.lines(code, "missing", 10))
        assertEquals(emptyList<Int>(), inline.lines(code, "this", 10))
        assertEquals(emptyList<Int>(), inline.lines(code, "total", 99))

        // top-level statements have no member around them
        val program = listOf("var count = 1;", "count += 2;", "Console.WriteLine(count);", "count = 0;", "").joinToString("\n")
        assertEquals(listOf(0, 1, 2), inline.lines(program, "count", 2))
    }
}
