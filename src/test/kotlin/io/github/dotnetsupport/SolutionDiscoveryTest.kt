package io.github.dotnetsupport

import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.actions.AddExistingProjectAction
import io.github.dotnetsupport.actions.AddNewProjectToSolutionAction
import io.github.dotnetsupport.actions.AddProjectReferenceAction
import io.github.dotnetsupport.actions.NewSolutionFolderAction
import io.github.dotnetsupport.actions.RemoveProjectFromSolutionAction
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.build.BuildSelected
import io.github.dotnetsupport.solution.SolutionFilter
import io.github.dotnetsupport.solution.SolutionFinder
import io.github.dotnetsupport.solution.SolutionParser
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.ProjectKey
import io.github.dotnetsupport.view.SolutionKey
import io.github.dotnetsupport.view.SolutionNode

/** Solutions anywhere under the opened folder, and solution filters (`.slnf`). */
class SolutionDiscoveryTest : BasePlatformTestCase() {
    fun testSolutionsAreFoundBelowTheRootAndNotInBuildOutput() {
        val root = myFixture.tempDirFixture.findOrCreateDir("repo")
        for (path in listOf(
            "repo/Zeta.sln", "repo/Alpha.slnx", "repo/Alpha.slnf",
            "repo/samples/Sample.sln", "repo/src/deep/Inner.slnx", "repo/src/deep/Inner.slnf",
            "repo/bin/Built.sln", "repo/obj/Cached.sln", "repo/node_modules/pkg/Pkg.sln", "repo/.git/Hidden.sln", "repo/packages/x/X.sln",
            "repo/src/App/App.csproj", "repo/obj/Gen.csproj",
        )) myFixture.addFileToProject(path, "")

        val found = SolutionFinder.find(root, includeProjects = true)
        // the root first, then the deeper ones; by name within a depth
        assertEquals(listOf("repo/Alpha.slnx", "repo/Zeta.sln", "repo/samples/Sample.sln", "repo/src/deep/Inner.slnx"), found.solutions.map { relative(root, it) })
        assertEquals(listOf("repo/Alpha.slnf", "repo/src/deep/Inner.slnf"), found.filters.map { relative(root, it) })
        assertEquals(listOf("repo/src/App/App.csproj"), found.projects.map { relative(root, it) })
        assertEquals(emptyList<String>(), SolutionFinder.find(root).projects)
    }

    fun testSolutionFilterParsing() {
        val filter = SolutionFilter.parse(
            """
            {
              "solution": {
                "path": "..\\All.sln",
                "projects": [
                  "src\\App\\App.csproj",
                  "src/Core/Core.csproj",
                ]
              }
            }
            """.trimIndent(),
        )!!
        assertEquals("../All.sln", filter.solutionPath)
        assertEquals(listOf("src/App/App.csproj", "src/Core/Core.csproj"), filter.projects)

        assertNull(SolutionFilter.parse("{ }"))
        assertNull(SolutionFilter.parse("not json"))
        assertNull(SolutionFilter.parse("""{ "solution": { "projects": [] } }"""))
    }

    fun testFilterKeepsListedProjectsAndTheirFoldersOnly() {
        val solution = SolutionParser.parseSlnx(
            """
            <Solution>
              <Folder Name="/src/"><Project Path="src/App/App.csproj" /><Project Path="src/Core/Core.csproj" /></Folder>
              <Folder Name="/tests/"><Project Path="tests/Tests/Tests.csproj" /></Folder>
              <Folder Name="/docs/"><File Path="README.md" /></Folder>
              <Project Path="Tool/Tool.csproj" />
            </Solution>
            """.trimIndent(),
        )
        val filtered = SolutionFilter("All.slnx", listOf("SRC/App/App.csproj")).apply(solution)

        assertTrue(filtered.filtered)
        assertEquals(4, filtered.totalProjects)
        assertEquals(listOf("App"), filtered.allProjects.map { it.name })
        // tests/ is left without projects and goes; docs/ keeps its solution items
        assertEquals(listOf("src", "docs"), filtered.root.folders.map { it.name })
        assertEquals(emptyList<String>(), filtered.root.projects.map { it.name })
        assertEquals(solution.configurations, filtered.configurations)
    }

    fun testFilterFileIsASolutionInTheView() {
        myFixture.addFileToProject("shop/src/App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        myFixture.addFileToProject("shop/src/Core/Core.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        myFixture.addFileToProject("shop/Shop.slnx", """<Solution><Project Path="src/App/App.csproj" /><Project Path="src/Core/Core.csproj" /></Solution>""")
        val filterFile = myFixture.addFileToProject("shop/filters/AppOnly.slnf", """{ "solution": { "path": "../Shop.slnx", "projects": [ "src/App/App.csproj" ] } }""").virtualFile
        val brokenFilter = myFixture.addFileToProject("shop/filters/Gone.slnf", """{ "solution": { "path": "../Missing.sln", "projects": [ "src/App/App.csproj" ] } }""").virtualFile

        val solutions = SolutionService.getInstance(project)
        assertEquals(listOf("App"), solutions.solution(filterFile).allProjects.map { it.name })
        assertEquals(0, solutions.solution(brokenFilter).allProjects.size)

        val node = SolutionNode(project, SolutionKey(filterFile), ViewSettings.DEFAULT)
        assertEquals("AppOnly (1 of 2 projects)", node.describe())
        assertEquals(listOf("App"), node.children.map { it.describe().substringBefore(" (") })
    }

    fun testSolutionEditingActionsHideUnderAFilterButProjectOnesStay() {
        myFixture.addFileToProject("f/src/App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        val sln = myFixture.addFileToProject("f/F.slnx", """<Solution><Project Path="src/App/App.csproj" /></Solution>""").virtualFile
        val slnf = myFixture.addFileToProject("f/F.slnf", """{ "solution": { "path": "F.slnx", "projects": [ "src/App/App.csproj" ] } }""").virtualFile
        val solutions = SolutionService.getInstance(project)
        val app = solutions.solution(slnf).allProjects.single()

        fun visible(action: AnAction, key: Any): Boolean {
            val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.SELECTED_ITEMS, arrayOf(key)).build()
            val event = AnActionEvent.createEvent(action, context, null, "ProjectViewPopup", ActionUiKind.POPUP, null)
            action.update(event)
            return event.presentation.isEnabledAndVisible
        }

        // on the real solution everything is there
        assertTrue(visible(AddExistingProjectAction(), SolutionKey(sln)))
        assertTrue(visible(NewSolutionFolderAction(), SolutionKey(sln)))
        assertTrue(visible(AddNewProjectToSolutionAction(), SolutionKey(sln)))
        assertTrue(visible(RemoveProjectFromSolutionAction(), ProjectKey(sln, app)))
        // a filter cannot be edited by `dotnet sln`
        assertFalse(visible(AddExistingProjectAction(), SolutionKey(slnf)))
        assertFalse(visible(NewSolutionFolderAction(), SolutionKey(slnf)))
        assertFalse(visible(AddNewProjectToSolutionAction(), SolutionKey(slnf)))
        assertFalse(visible(RemoveProjectFromSolutionAction(), ProjectKey(slnf, app)))
        // but the project under it is a project, and the filter builds
        assertTrue(visible(AddProjectReferenceAction(), ProjectKey(slnf, app)))
        assertTrue(visible(BuildSelected(), SolutionKey(slnf)))
        assertTrue(SolutionContext(slnf).isFilter)
        assertFalse(SolutionContext(sln).isFilter)
    }

    private fun relative(root: com.intellij.openapi.vfs.VirtualFile, file: com.intellij.openapi.vfs.VirtualFile): String =
        "repo/" + com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, root, '/')

    private fun AbstractTreeNode<*>.describe(): String {
        update()
        val text = presentation.presentableText.orEmpty()
        return presentation.locationString?.let { "$text ($it)" } ?: text
    }
}
