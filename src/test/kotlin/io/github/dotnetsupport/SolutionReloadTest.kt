package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.actions.ReloadProjectAction
import io.github.dotnetsupport.actions.SolutionReload
import io.github.dotnetsupport.actions.SolutionReloadListener
import io.github.dotnetsupport.solution.SolutionService

/** Reload Solution / Reload Project: the disk is read again, what was parsed is forgotten, the server is told. */
class SolutionReloadTest : BasePlatformTestCase() {
    fun testActionsAreWhereRiderHasThem() {
        val actions = ActionManager.getInstance()
        for (group in listOf("DotNet.MainMenu", "DotNet.SolutionViewPopup")) {
            val children = (actions.getAction(group) as DefaultActionGroup).childActionsOrStubs.map { actions.getId(it) }
            assertTrue("$group: Reload Solution", "DotNet.ReloadSolution" in children)
            assertTrue("$group: Reload Project", "DotNet.ReloadProject" in children)
        }
        val toolbar = (actions.getAction("ProjectViewToolbar") as DefaultActionGroup).childActionsOrStubs.map { actions.getId(it) }
        assertTrue("the button next to the eye", "DotNet.ReloadSolution" in toolbar)
        assertEquals("Reload Solution", actions.getAction("DotNet.ReloadSolution").templatePresentation.text)
    }

    fun testWhatWasParsedIsForgotten() {
        val app = myFixture.addFileToProject("reload/App/App.csproj", """<Project Sdk="Microsoft.NET.Sdk"/>""").virtualFile
        val lib = myFixture.addFileToProject("reload/Lib/Lib.csproj", """<Project Sdk="Microsoft.NET.Sdk"/>""").virtualFile
        myFixture.addFileToProject("reload/App/obj/project.assets.json", "{}")
        val solutions = SolutionService.getInstance(project)
        solutions.reload()
        solutions.msBuildProject(app)
        solutions.msBuildProject(lib)
        solutions.assets(app)
        assertEquals(3, solutions.cachedFiles)

        solutions.reload(app)
        assertEquals("the other project stays", 1, solutions.cachedFiles)
        solutions.msBuildProject(app)
        solutions.reload()
        assertEquals(0, solutions.cachedFiles)
    }

    fun testTheServerIsTold() {
        val app = myFixture.addFileToProject("told/App/App.csproj", """<Project Sdk="Microsoft.NET.Sdk"/>""").virtualFile
        val told = ArrayList<VirtualFile?>()
        project.messageBus.connect(testRootDisposable).subscribe(SolutionReloadListener.TOPIC, object : SolutionReloadListener {
            override fun reloaded(projectFile: VirtualFile?) {
                told += projectFile
            }
        })
        var done = 0
        SolutionReload.reload(project) { done++ }
        SolutionReload.reload(project, app) { done++ }
        assertEquals(listOf(null, app), told)
        assertEquals(2, done)
    }

    fun testFilesThatCameFromOutsideAreSeen() {
        val app = myFixture.addFileToProject("outside/App/App.csproj", """<Project Sdk="Microsoft.NET.Sdk"/>""").virtualFile
        // written past the IDE, as a terminal or a generator does
        val directory = java.io.File(app.parent.path, "Editor").apply { mkdirs() }
        val onDisk = app.fileSystem.protocol == "file"
        if (onDisk) java.io.File(directory, "Outside.cs").writeText("class Outside { }")
        SolutionReload.reload(project, app)
        if (onDisk) assertNotNull(app.parent.findFileByRelativePath("Editor/Outside.cs"))
    }

    fun testTheProjectOfTheAction() {
        val app = myFixture.addFileToProject("action/App/App.csproj", """<Project Sdk="Microsoft.NET.Sdk"/>""").virtualFile
        val source = myFixture.addFileToProject("action/App/Models/Order.cs", "class Order { }").virtualFile
        val loose = myFixture.addFileToProject("action/readme.md", "").virtualFile
        fun projectOf(file: VirtualFile) = ReloadProjectAction.projectFile(
            TestActionEvent.createTestEvent(SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, file).build()))
        assertEquals("a file of the project", app, projectOf(source))
        assertEquals("the project file itself", app, projectOf(app))
        assertNull(projectOf(loose))
    }
}
