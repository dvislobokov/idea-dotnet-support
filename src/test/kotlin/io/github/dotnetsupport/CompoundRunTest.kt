package io.github.dotnetsupport

import com.intellij.execution.RunManager
import com.intellij.execution.compound.CompoundRunConfiguration
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.run.BuiltBeforeLaunch
import io.github.dotnetsupport.run.CompoundConfigurations
import io.github.dotnetsupport.run.DotNetConfigurationType
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.run.LaunchBuildPlan
import io.github.dotnetsupport.run.LaunchBuildPlan.Member
import io.github.dotnetsupport.run.LaunchBuildPlan.Step
import io.github.dotnetsupport.run.LaunchWaitCondition
import io.github.dotnetsupport.run.LaunchWaits
import io.github.dotnetsupport.run.SaveAsCompoundAction
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.ProjectKey
import org.jdom.Element

/** Launches started together: one build for all of them, "Wait for", compound configurations of .NET Project ones. */
class CompoundRunTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            // the light project is shared between test classes: no configuration of this one may stay
            val runManager = RunManager.getInstance(project)
            runManager.allSettings.filter { it.name.startsWith("Cmp") || it.folderName?.startsWith("Cmp") == true || it.configuration is CompoundRunConfiguration }
                .forEach { runManager.removeConfiguration(it) }
        } finally {
            super.tearDown()
        }
    }

    fun testTheProjectsOfOneSolutionAreBuiltByOneSolutionFilter() {
        val solution = "C:/src/Shop/Shop.sln"
        val plan = LaunchBuildPlan.plan(listOf(Member("C:/src/Shop/Web/Web.csproj"), Member("C:/src/Shop/Worker/Worker.csproj"), Member("C:/src/Shop/web/WEB.csproj"))) { solution }
        assertEquals("one build; the same project twice is built once", listOf(Step(listOf("C:/src/Shop/Web/Web.csproj", "C:/src/Shop/Worker/Worker.csproj"), solution)), plan)
        assertTrue(plan.single().isFilter)
    }

    fun testWhatASolutionBuildCannotTakeIsBuiltProjectByProject() {
        val outside = LaunchBuildPlan.plan(listOf(Member("C:/a/A.csproj"), Member("C:/b/B.csproj"))) { null }
        assertEquals("no solution has both: one after another", listOf(Step(listOf("C:/a/A.csproj")), Step(listOf("C:/b/B.csproj"))), outside)

        val framework = listOf("--framework", "net8.0")
        val mixed = LaunchBuildPlan.plan(listOf(Member("C:/s/A/A.csproj"), Member("C:/s/Multi/Multi.csproj", framework), Member("C:/s/B/B.csproj"))) { "C:/s/S.sln" }
        assertEquals(listOf(Step(listOf("C:/s/A/A.csproj", "C:/s/B/B.csproj"), "C:/s/S.sln"), Step(listOf("C:/s/Multi/Multi.csproj"), frameworkArguments = framework)), mixed)

        assertEquals("a single launch builds its project", listOf(Step(listOf("C:/s/A/A.csproj"))), LaunchBuildPlan.plan(listOf(Member("C:/s/A/A.csproj"))) { "C:/s/S.sln" })
    }

    fun testDotnetWatchAloneBuildsItself() {
        assertEmpty(LaunchBuildPlan.plan(listOf(Member("C:/s/A/A.csproj", watch = true))) { "C:/s/S.sln" })
        assertEmpty(LaunchBuildPlan.plan(listOf(Member("C:/s/A/A.csproj", watch = true), Member("C:/s/B/B.csproj", watch = true))) { "C:/s/S.sln" })
        assertEquals("with dotnet run its project is in the build: the shared ones are compiled once", 2,
            LaunchBuildPlan.plan(listOf(Member("C:/s/A/A.csproj", watch = true), Member("C:/s/B/B.csproj"))) { "C:/s/S.sln" }.single().projects.size)
    }

    fun testSolutionFilterOfTheLaunchedProjects() {
        val json = LaunchBuildPlan.filterJson("C:/src/Shop/Shop.sln", listOf("C:/src/Shop/Web/Web.csproj", "C:/src/Shop/Worker/Worker.csproj"), "C:/system/launch-builds")
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject.getAsJsonObject("solution")
        assertEquals("relative to the filter: the reader of filters of the plugin takes no absolute path", "..\\..\\src\\Shop\\Shop.sln", root.get("path").asString)
        assertEquals(listOf("Web\\Web.csproj", "Worker\\Worker.csproj"), root.getAsJsonArray("projects").map { it.asString })
        assertEquals("Web+Worker.slnf", LaunchBuildPlan.filterName(listOf("C:/src/Shop/Web/Web.csproj", "C:/src/Shop/Worker/Worker.csproj")))
        assertEquals("My_App+B.slnf", LaunchBuildPlan.filterName(listOf("C:/x/My App.csproj", "C:/y/B.fsproj")))
    }

    fun testWhereTheConfigurationWaitedForIsChecked() {
        val urls = listOf("https://localhost:7187", "http://localhost:5187")
        assertEquals("http first: https needs the certificate", "http://localhost:5187", LaunchWaits.address(urls, null))
        assertEquals("what it has printed wins", "http://localhost:6000", LaunchWaits.address(urls, "http://localhost:6000"))
        assertEquals("https://localhost:7187", LaunchWaits.address(listOf("https://localhost:7187"), null))
        assertNull(LaunchWaits.address(emptyList(), null))

        assertEquals("http://localhost:5187/health", LaunchWaits.healthUrl("/health", "http://localhost:5187/"))
        assertEquals("http://localhost:5187/health", LaunchWaits.healthUrl("health", "http://*:5187"))
        assertEquals("the address itself", "http://localhost:5187", LaunchWaits.healthUrl(" ", "http://localhost:5187"))
        assertEquals("http://other:9000/ready", LaunchWaits.healthUrl("http://other:9000/ready", null))
        assertNull("a path without an address", LaunchWaits.healthUrl("/health", null))

        assertEquals("localhost" to 5187, LaunchWaits.endpoint("http://localhost:5187/orders"))
        assertEquals("localhost" to 5000, LaunchWaits.endpoint("http://*:5000"))
        assertEquals("localhost" to 5001, LaunchWaits.endpoint("https://[::]:5001"))
        assertEquals("localhost" to 80, LaunchWaits.endpoint("http://0.0.0.0"))
        assertEquals("api.local" to 443, LaunchWaits.endpoint("https://api.local/health"))
        assertNull(LaunchWaits.endpoint("localhost:5000"))
    }

    fun testConfigurationsThatWaitForEachOtherAreFound() {
        val waits = mapOf("Worker" to "Web", "Web" to "Db", "Db" to null, "A" to "B", "B" to "C", "C" to "A", "Self" to "Self")
        assertNull(LaunchWaits.cycle("Worker") { waits[it] })
        assertEquals(listOf("A", "B", "C", "A"), LaunchWaits.cycle("A") { waits[it] })
        assertEquals(listOf("Self", "Self"), LaunchWaits.cycle("Self") { waits[it] })
    }

    fun testTheStatusOfAWaitSaysWhatIsAwaited() {
        assertEquals("Waiting for 'Web' to listen on http://localhost:5187", LaunchWaits.waitingText("Web", LaunchWaitCondition.LISTENING, "http://localhost:5187"))
        assertEquals("Waiting for 'Web' to start", LaunchWaits.waitingText("Web", LaunchWaitCondition.STARTED, "http://localhost:5187"))
        assertEquals("'Web' does not answer on http://localhost:5187/health (it is not running) after 60 s, so 'Worker' was not started.",
            LaunchWaits.timeoutText("Worker", "Web", LaunchWaitCondition.HEALTHY, "http://localhost:5187/health", 60, running = false))
        assertEquals("'Web' has not started after 5 s, so 'Worker' was not started.", LaunchWaits.timeoutText("Worker", "Web", LaunchWaitCondition.STARTED, null, 5, running = false))
    }

    fun testWaitForIsSavedWithTheConfiguration() {
        val settings = RunManager.getInstance(project).createConfiguration("CmpWorker", DotNetConfigurationType::class.java)
        val configuration = settings.configuration as DotNetRunConfiguration
        assertNull(configuration.options.waitFor)
        assertEquals(LaunchWaitCondition.LISTENING, configuration.options.waitCondition)
        assertEquals(LaunchWaits.DEFAULT_TIMEOUT_SECONDS, configuration.options.waitTimeoutSeconds)
        configuration.options.apply {
            waitFor = "CmpWeb"
            waitCondition = LaunchWaitCondition.HEALTHY
            waitHealthUrl = "/health"
            waitTimeoutSeconds = 15
        }
        val element = Element("configuration")
        configuration.writeExternal(element)
        val copy = RunManager.getInstance(project).createConfiguration("CmpWorker", DotNetConfigurationType::class.java).configuration as DotNetRunConfiguration
        copy.readExternal(element)
        assertEquals("CmpWeb", copy.options.waitFor)
        assertEquals(LaunchWaitCondition.HEALTHY, copy.options.waitCondition)
        assertEquals("/health", copy.options.waitHealthUrl)
        assertEquals(15, copy.options.waitTimeoutSeconds)
    }

    fun testAConfigurationCannotWaitForItselfOrInACircle() {
        val runManager = RunManager.getInstance(project)
        // the check looks at the disk, where the files of the light project are not
        val projectFile = java.io.File.createTempFile("CmpApp", ".csproj").apply { deleteOnExit() }.path
        fun configuration(name: String, waitsFor: String?) = runManager.createConfiguration(name, DotNetConfigurationType::class.java).also {
            (it.configuration as DotNetRunConfiguration).options.apply {
                projectPath = projectFile
                waitFor = waitsFor
            }
            runManager.addConfiguration(it)
        }.configuration as DotNetRunConfiguration
        fun problem(configuration: DotNetRunConfiguration): RuntimeConfigurationException? =
            try { configuration.checkConfiguration(); null } catch (e: RuntimeConfigurationException) { e }

        val self = configuration("CmpSelf", "CmpSelf")
        val first = configuration("CmpFirst", "CmpSecond")
        configuration("CmpSecond", "CmpFirst")
        val missing = configuration("CmpMissing", "CmpNoSuchThing")
        // without dotnet on PATH (a build agent) the check stops before "Wait for"
        if (io.github.dotnetsupport.cli.DotNetCli.findExecutable() == null) return
        assertEquals("The configuration waits for itself", problem(self)?.localizedMessage)
        assertEquals("The configurations wait for each other: CmpFirst → CmpSecond → CmpFirst", problem(first)?.localizedMessage)
        assertTrue("a warning only: the configuration may be added later", problem(missing).let { it != null && it !is RuntimeConfigurationError })
    }

    fun testCompoundOfSeveralProjectsIsNamedAsInRider() {
        assertEquals("Web + Worker", CompoundConfigurations.name(listOf("Web", "Worker"), emptySet()))
        assertEquals("Web + Worker (2)", CompoundConfigurations.name(listOf("Web", "Worker"), setOf("Web + Worker")))
        assertEquals(CompoundConfigurations.MULTIPLE_PROJECTS, CompoundConfigurations.name(listOf("A", "B", "C", "D"), emptySet()))
    }

    fun testSaveAsCompoundMakesTheConfigurationsPermanentAndGroupsThem() {
        val runManager = RunManagerImpl.getInstanceImpl(project)
        fun dotNet(name: String, path: String) = runManager.createConfiguration(name, DotNetConfigurationType::class.java).also {
            (it.configuration as DotNetRunConfiguration).options.projectPath = path
        }
        val web = dotNet("CmpWeb", "C:/src/CmpWeb/CmpWeb.csproj").also { runManager.setTemporaryConfiguration(it) }
        val worker = dotNet("CmpWorker", "C:/src/CmpWorker/CmpWorker.csproj") // not registered yet, as Run N Projects makes it

        val compound = CompoundConfigurations.save(project, listOf(web, worker))
        assertEquals("CmpWeb + CmpWorker", compound.name)
        assertFalse(compound.isTemporary)
        assertFalse("a temporary member is kept", web.isTemporary)
        assertTrue("a new member is added", runManager.allSettings.any { it === worker })
        assertEquals(setOf(web.configuration, worker.configuration), (compound.configuration as CompoundRunConfiguration).getConfigurationsWithTargets(runManager).keys)
        assertEquals("the Services tool window groups them by folder", listOf("CmpWeb + CmpWorker", "CmpWeb + CmpWorker"), listOf(web.folderName, worker.folderName))
        assertSame(compound, runManager.selectedConfiguration)

        val debug = com.intellij.execution.executors.DefaultDebugExecutor.EXECUTOR_ID
        assertTrue("the platform has no runner to debug a compound", io.github.dotnetsupport.run.DotNetCompoundDebugRunner().canRun(debug, compound.configuration))
        assertSame(io.github.dotnetsupport.run.DotNetCompoundDebugRunner::class.java, com.intellij.execution.runners.ProgramRunner.getRunner(debug, compound.configuration)?.javaClass)

        assertSame("saved once", compound, CompoundConfigurations.save(project, listOf(worker, web)))
        assertSame(compound, CompoundConfigurations.find(project, listOf(web, worker)))
        assertNull(CompoundConfigurations.find(project, listOf(web)))
    }

    fun testSaveAsCompoundIsOfferedForSeveralSelectedProjects() {
        myFixture.addFileToProject("cmp/Api/Api.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"><PropertyGroup><OutputType>Exe</OutputType></PropertyGroup></Project>")
        myFixture.addFileToProject("cmp/Jobs/Jobs.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><OutputType>Exe</OutputType></PropertyGroup></Project>")
        val sln = myFixture.addFileToProject("cmp/Cmp.slnx", """<Solution><Project Path="Api/Api.csproj" /><Project Path="Jobs/Jobs.csproj" /></Solution>""").virtualFile
        val (api, jobs) = SolutionService.getInstance(project).solution(sln).allProjects.sortedBy { it.name }
        fun event(vararg selected: Any) = AnActionEvent.createEvent(SaveAsCompoundAction(), SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
            .add(PlatformCoreDataKeys.SELECTED_ITEMS, arrayOf(*selected)).build(), null, ActionPlaces.PROJECT_VIEW_POPUP, com.intellij.openapi.actionSystem.ActionUiKind.POPUP, null)

        val two = event(ProjectKey(sln, api), ProjectKey(sln, jobs))
        SaveAsCompoundAction().update(two)
        assertTrue(two.presentation.isEnabledAndVisible)
        val one = event(ProjectKey(sln, api))
        SaveAsCompoundAction().update(one)
        assertFalse("one project is not a compound", one.presentation.isVisible)
    }

    fun testWhatWasBuiltIsHandedToEachLaunchOfACompound() {
        // the launches of a compound share the execution id
        BuiltBeforeLaunch.put(77, "", "Web")
        BuiltBeforeLaunch.put(77, "C:/out/Worker.dll", "Worker")
        assertEquals("C:/out/Worker.dll", BuiltBeforeLaunch.take(77, "Worker"))
        assertEquals("", BuiltBeforeLaunch.take(77, "Web"))
        assertNull(BuiltBeforeLaunch.take(77, "Web"))
    }
}
