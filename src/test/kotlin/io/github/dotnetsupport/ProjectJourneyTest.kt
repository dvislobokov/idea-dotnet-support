package io.github.dotnetsupport

import com.intellij.credentialStore.Credentials
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.tree.TreeVisitor
import io.github.dotnetsupport.actions.MoveToSolutionFolderAction
import io.github.dotnetsupport.actions.SolutionFolderMove
import io.github.dotnetsupport.newproject.NewSolution
import io.github.dotnetsupport.newproject.NewSolutionDialog
import io.github.dotnetsupport.newproject.TemplateCategory
import io.github.dotnetsupport.newproject.TemplateOptionConditions
import io.github.dotnetsupport.newproject.TemplateOptions
import io.github.dotnetsupport.nuget.NuGetCredentialPolicy
import io.github.dotnetsupport.nuget.NuGetService
import io.github.dotnetsupport.run.LaunchBuildPlan
import io.github.dotnetsupport.solution.SlnProject
import io.github.dotnetsupport.solution.SolutionEditor
import io.github.dotnetsupport.solution.SolutionParser
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.ProjectKey
import io.github.dotnetsupport.view.SolutionFolderKey
import io.github.dotnetsupport.view.SolutionKey
import io.github.dotnetsupport.view.SolutionViewReveal
import java.io.File

/** The project, solution and NuGet findings of the live walkthrough (docs/DEV_JOURNEY.md, stages 1–3 and 5.2). */
class ProjectJourneyTest : BasePlatformTestCase() {
    private fun help(name: String) = File("src/test/resources/dotnetNew/$name.txt").readText()

    // ---- 3.1: Manage NuGet Packages of a project opened the window with an NPE

    fun testTheNuGetWindowOpensOnARequestedProject() {
        myFixture.addFileToProject("nugetjourney/App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        myFixture.addFileToProject("nugetjourney/Journey.slnx", """<Solution><Project Path="App/App.csproj" /></Solution>""")
        val solutions = SolutionService.getInstance(project)
        solutions.solutionFilesChanged()
        val service = NuGetService.getInstance(project)
        val app = service.projects().first { it.first == "App" }.second
        service.requestedProject = app
        val toolWindow = com.intellij.toolWindow.ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        val listeners = service.requestListeners.size
        try {
            io.github.dotnetsupport.nuget.NuGetToolWindowFactory().createToolWindowContent(project, toolWindow)
            assertEquals("Packages", toolWindow.contentManager.contents.first().displayName)
            // the action asks the window again once it is shown
            service.requestListeners.toList().forEach { it() }
            // a project that is in no solution any more: no endless reloading of the list
            service.requestedProject = myFixture.addFileToProject("nugetjourney/Loose/Loose.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>").virtualFile
            service.requestListeners.toList().forEach { it() }
        } finally {
            service.requestedProject = null
            Disposer.dispose(toolWindow.disposable)
        }
        assertEquals("the window forgets its listeners with the tool window", listeners, service.requestListeners.size)
    }

    // ---- 3.2: the password storage was asked for nuget.org on every request

    fun testTheCredentialStoreIsAskedOnlyForSourcesThatNeedIt() {
        val asked = ArrayList<String>()
        val known = HashSet<String>()
        val stored = mapOf("https://feed.example/v3/index.json" to Credentials("me", "secret"))
        val policy = NuGetCredentialPolicy(lookup = { asked += it; stored[it] }, known = { known })

        repeat(3) { assertNull(policy.get("https://api.nuget.org/v3/index.json")) }
        assertNull(policy.get("https://other.example/v3/index.json"))
        assertEquals("nuget.org and a source without credentials are never looked up", emptyList<String>(), asked)

        // a 401: asked once, the empty answer cached
        policy.unauthorized("https://other.example/v3/index.json")
        assertNull(policy.get("https://other.example/v3/index.json"))
        assertNull(policy.get("https://other.example/v3/index.json"))
        assertEquals(listOf("https://other.example/v3/index.json"), asked)

        // credentials the plugin stored: asked once, then cached
        known += NuGetCredentialPolicy.key("https://feed.example/v3/index.json")
        assertEquals("me", policy.get("https://feed.example/v3/index.json")?.userName)
        assertEquals("me", policy.get("https://feed.example/v3/index.json/")?.userName)
        assertEquals(2, asked.size)

        // nuget.org stays anonymous even after a 401 of a proxy
        policy.unauthorized("https://api.nuget.org/v3/index.json")
        assertNull(policy.get("https://api.nuget.org/v3/index.json"))
        assertEquals(2, asked.size)
        assertTrue(NuGetCredentialPolicy.isAnonymous("https://www.nuget.org/api/v2/"))
        assertFalse(NuGetCredentialPolicy.isAnonymous("https://pkgs.dev.azure.com/org/_packaging/feed/nuget/v3/index.json"))
    }

    // ---- 5.2: the text of a failed build before a launch

    fun testTheFailedBuildSaysTheLaunchWasNotStarted() {
        assertEquals("The build of Journey.App before the launch has failed, so 'Journey.App' was not started. See the Build tool window.",
            LaunchBuildPlan.failureMessage("Journey.App", listOf("Journey.App")))
        assertEquals("The build of Api, Worker before the launch has failed, so none of Api, Worker was started. See the Build tool window.",
            LaunchBuildPlan.failureMessage("Api, Worker", listOf("Api", "Worker")))
    }

    // ---- 2.2: Move to Solution Folder and drag and drop

    fun testAProjectMovesIntoAFolderOfAnSlnx() {
        val text = """
            <Solution>
              <Folder Name="/src/" />
              <Folder Name="/tests/">
                <Project Path="Tests/Tests.csproj" />
              </Folder>
              <Project Path="App/App.csproj" />
              <Project Path="Domain\Domain.csproj">
                <BuildType Project="Release" />
              </Project>
            </Solution>
        """.trimIndent()
        val app = SlnProject("App", "App/App.csproj", "App/App.csproj")
        val intoSrc = SolutionEditor.moveProject(text, "slnx", app, "/src/")
        val parsed = SolutionParser.parse(intoSrc, "slnx")
        assertEquals(listOf("App"), parsed.findFolder("/src/")!!.projects.map { it.name })
        assertEquals(listOf("Domain"), parsed.root.projects.map { it.name })
        assertTrue(intoSrc, intoSrc.contains("  <Folder Name=\"/src/\">\n    <Project Path=\"App/App.csproj\" />\n  </Folder>"))

        // into a folder that has projects, with what the element holds; back to the root
        val domain = SlnProject("Domain", "Domain/Domain.csproj", "Domain/Domain.csproj")
        val intoTests = SolutionParser.parse(SolutionEditor.moveProject(intoSrc, "slnx", domain, "/tests/"), "slnx")
        assertEquals(listOf("Tests", "Domain"), intoTests.findFolder("/tests/")!!.projects.map { it.name })
        assertTrue(SolutionEditor.moveProject(intoSrc, "slnx", domain, "/tests/").contains("<BuildType Project=\"Release\" />"))
        val back = SolutionEditor.moveProject(intoSrc, "slnx", app, null)
        assertEquals(setOf("App", "Domain"), SolutionParser.parse(back, "slnx").root.projects.map { it.name }.toSet())
        assertEquals("an unknown project leaves the file as it is", text, SolutionEditor.moveProject(text, "slnx", SlnProject("X", "X/X.csproj", "X/X.csproj"), "/src/"))
    }

    fun testAProjectMovesIntoAFolderOfAnSln() {
        val folder = "11111111-1111-1111-1111-111111111111"
        val project = "22222222-2222-2222-2222-222222222222"
        val text = listOf(
            "Microsoft Visual Studio Solution File, Format Version 12.00",
            "Project(\"{2150E333-8FDC-42A3-9474-1A3956D46DE8}\") = \"src\", \"src\", \"{$folder}\"",
            "EndProject",
            "Project(\"{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}\") = \"App\", \"App\\App.csproj\", \"{$project}\"",
            "EndProject",
            "Global",
            "EndGlobal",
            "",
        ).joinToString("\r\n")
        val app = SolutionParser.parse(text, "sln").root.projects.single()
        val moved = SolutionEditor.moveProject(text, "sln", app, folder)
        assertEquals(listOf("App"), SolutionParser.parse(moved, "sln").findFolder(folder)!!.projects.map { it.name })
        assertTrue("the line ends of the file are kept", moved.contains("\tGlobalSection(NestedProjects) = preSolution\r\n\t\t{$project} = {$folder}\r\n\tEndGlobalSection"))
        val back = SolutionEditor.moveProject(moved, "sln", app, null)
        assertEquals(listOf("App"), SolutionParser.parse(back, "sln").root.projects.map { it.name })
        assertFalse("an emptied section is dropped", back.contains("NestedProjects"))
    }

    fun testMoveTargetsAndDrops() {
        val sln = myFixture.addFileToProject("movejourney/Move.slnx", """
            <Solution>
              <Folder Name="/src/" />
              <Folder Name="/src/libs/" />
              <Project Path="App/App.csproj" />
            </Solution>
        """.trimIndent()).virtualFile
        val other = myFixture.addFileToProject("movejourney/Other.slnx", "<Solution />").virtualFile
        val solution = SolutionService.getInstance(project).solution(sln)
        val app = solution.root.projects.single()
        assertEquals(listOf("src", "src/libs"), SolutionFolderMove.targets(solution, listOf(app)).map { it.title })

        val key = ProjectKey(sln, app)
        assertEquals(listOf(key) to "/src/libs/", SolutionFolderMove.dropOf(listOf(key), SolutionFolderKey(sln, "/src/libs/")))
        assertEquals(listOf(key) to null, SolutionFolderMove.dropOf(listOf(key), SolutionKey(sln)))
        assertNull("a project goes nowhere but a folder or the solution", SolutionFolderMove.dropOf(listOf(key), key))
        assertNull("not into another solution", SolutionFolderMove.dropOf(listOf(key), SolutionKey(other)))
        assertNull("files are left to the platform", SolutionFolderMove.dropOf(listOf(sln), SolutionKey(sln)))

        // the action is in the popup of the Solution view and shows for projects only
        val popup = ActionManager.getInstance().getAction("DotNet.SolutionViewPopup") as DefaultActionGroup
        assertTrue(popup.childActionsOrStubs.any { ActionManager.getInstance().getId(it) == "DotNet.MoveToSolutionFolder" })
        fun visible(item: Any): Boolean {
            val action = MoveToSolutionFolderAction()
            val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.SELECTED_ITEMS, arrayOf(item)).build()
            val event = AnActionEvent.createEvent(action, context, null, "ProjectViewPopup", ActionUiKind.POPUP, null)
            action.update(event)
            return event.presentation.isEnabledAndVisible
        }
        assertTrue(visible(key))
        assertFalse(visible(SolutionKey(sln)))

        SolutionFolderMove.move(project, sln, listOf(app), "/src/libs/")
        assertEquals(listOf("App"), SolutionService.getInstance(project).solution(sln).findFolder("/src/libs/")!!.projects.map { it.name })
        assertEquals("the project is no longer offered the folder it is in", listOf(SolutionFolderMove.ROOT_TITLE, "src"),
            SolutionFolderMove.targets(SolutionService.getInstance(project).solution(sln), listOf(app)).map { it.title })
    }

    // ---- 2.3: the new project is selected and expanded

    fun testTheWayToANewProjectInTheSolutionView() {
        myFixture.addFileToProject("revealjourney/App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        val created = myFixture.addFileToProject("revealjourney/Lib/Lib.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>").virtualFile
        val sln = myFixture.addFileToProject("revealjourney/Reveal.slnx", """
            <Solution>
              <Folder Name="/src/"><Project Path="Lib/Lib.csproj" /></Folder>
              <Folder Name="/tests/" />
              <Project Path="App/App.csproj" />
            </Solution>
        """.trimIndent()).virtualFile
        val other = myFixture.addFileToProject("revealjourney/Other.slnx", "<Solution />").virtualFile
        val solution = SolutionService.getInstance(project).solution(sln)
        fun visit(value: Any?) = SolutionViewReveal.visit(project, value, created)
        assertEquals(TreeVisitor.Action.CONTINUE, visit(project))
        assertEquals(TreeVisitor.Action.CONTINUE, visit(SolutionKey(sln)))
        assertEquals(TreeVisitor.Action.SKIP_CHILDREN, visit(SolutionKey(other)))
        assertEquals(TreeVisitor.Action.CONTINUE, visit(SolutionFolderKey(sln, "/src/")))
        assertEquals(TreeVisitor.Action.SKIP_CHILDREN, visit(SolutionFolderKey(sln, "/tests/")))
        assertEquals(TreeVisitor.Action.INTERRUPT, visit(ProjectKey(sln, solution.findFolder("/src/")!!.projects.single())))
        assertEquals(TreeVisitor.Action.SKIP_CHILDREN, visit(ProjectKey(sln, solution.root.projects.single())))
    }

    // ---- 2.1: Add | New Project is the window of New Solution, options follow each other

    fun testAddNewProjectIsTheNewSolutionWindow() {
        val directory = kotlin.io.path.createTempDirectory("addProject").toFile()
        val dialog = NewSolutionDialog(project, loadFromCli = false, addTo = NewSolutionDialog.AddProjectTarget(directory.path, "Journey.slnx / src"))
        try {
            assertEquals("no Empty Solution when adding a project", listOf("Project Type", "Console", "Class Library", "Web", "Services", "Unit Test"), dialog.leftTitles())
            assertFalse(dialog.isSolutionNameShown())
            assertTrue(dialog.isProjectNameShown())
            dialog.selectKind(TemplateCategory.LIBRARY)
            assertEquals("The project will be created in " + File(directory, "ClassLibrary1").path, dialog.createdInText())
            val request = dialog.addProjectRequest()!!
            assertEquals("ClassLibrary1", request.name)
            assertEquals(File(directory, "ClassLibrary1"), request.directory)
            assertEquals("classlib", request.template.template.shortName)
            assertNull("the window of New Solution has no request of a solution here", dialog.validationMessage())

            dialog.setNames("Journey.Domain", "Journey.Domain")
            assertEquals("Journey.Domain", dialog.addProjectRequest()!!.name)
            val taken = File(directory, "Journey.Domain")
            taken.mkdirs()
            File(taken, "x.txt").writeText("x")
            assertTrue(dialog.validationMessage().orEmpty(), dialog.validationMessage().orEmpty().contains("not empty"))
        } finally {
            Disposer.dispose(dialog.disposable)
        }
        assertNull(NewSolution.validateProject("Api", directory.path, true) { false })
        assertEquals("Specify the project name", NewSolution.validateProject("", directory.path, true) { false }?.message)
    }

    fun testWebApiAuthenticationFieldsFollowTheAuthentication() {
        val options = TemplateOptions.parse(help("sdk10/webapi"))
        fun shown(auth: String) = TemplateOptionConditions.shown(options, mapOf("--auth" to auth)).map { it.name }.toSet()
        val azure = setOf("--aad-b2c-instance", "--susi-policy-id", "--aad-instance", "--client-id", "--domain", "--default-scope", "--tenant-id",
            "--org-read-access", "--called-api-url", "--calls-graph", "--called-api-scopes")
        val none = shown("None")
        assertTrue("Auth = None hides the Azure AD fields: $none", none.intersect(azure).isEmpty())
        assertTrue(none.containsAll(listOf("--auth", "--no-https", "--no-openapi", "--use-controllers", "--use-program-main", "--exclude-launch-settings")))
        val singleOrg = shown("SingleOrg")
        assertTrue(singleOrg.containsAll(listOf("--aad-instance", "--client-id", "--domain", "--tenant-id", "--org-read-access", "--calls-graph")))
        assertFalse("B2C only", "--susi-policy-id" in singleOrg)
        assertFalse("--no-https applies when SingleOrg is not used", "--no-https" in singleOrg)
        assertTrue(shown("IndividualB2C").containsAll(listOf("--aad-b2c-instance", "--susi-policy-id", "--use-local-db")))
        // the default of the template counts when nothing is chosen
        assertEquals(none, TemplateOptionConditions.shown(options, emptyMap()).map { it.name }.toSet())
    }

    fun testEnabledIfExpressions() {
        val options = TemplateOptions.parse(help("mstest"))
        val withCondition = options.filter { it.enabledIf != null }
        assertTrue(withCondition.isNotEmpty())
        // `UseMSTestSdk` names no option (it is `--sdk`): not understood, so the option stays shown
        assertEquals(options.map { it.name }, TemplateOptionConditions.shown(options, emptyMap()).map { it.name })

        val runner = options.first { it.name == "--test-runner" }
        val dependent = io.github.dotnetsupport.newproject.TemplateOption("--x", emptyList(), "", io.github.dotnetsupport.newproject.TemplateOption.Kind.BOOL,
            emptyList(), "false", false, "(TestRunner == Microsoft.Testing.Platform) && !Aot")
        val aot = io.github.dotnetsupport.newproject.TemplateOption("--aot", emptyList(), "", io.github.dotnetsupport.newproject.TemplateOption.Kind.BOOL,
            emptyList(), "false", false, null)
        val all = listOf(runner, dependent, aot)
        val condition = TemplateOptionConditions.condition(dependent, all)!!
        assertTrue(condition(mapOf("--test-runner" to "Microsoft.Testing.Platform")))
        assertFalse(condition(mapOf("--test-runner" to "VSTest")))
        assertFalse(condition(mapOf("--test-runner" to "Microsoft.Testing.Platform", "--aot" to "true")))
    }
}
