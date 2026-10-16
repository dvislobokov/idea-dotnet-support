package io.github.dotnetsupport

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.solution.ActiveSolution
import io.github.dotnetsupport.solution.SolutionOpenProcessor
import io.github.dotnetsupport.solution.SolutionService

/**
 * The active solution (Open Solution, 0.1.242): one solution needs no choice, of several the chosen one is the solution of the project,
 * the first of the walk until then; a `.sln` / `.slnx` / `.slnf` opens as a project, a folder does not go through the processor.
 */
class ActiveSolutionTest : BasePlatformTestCase() {
    private fun sln(path: String): VirtualFile = myFixture.addFileToProject(path, "Microsoft Visual Studio Solution File, Format Version 12.00\n").virtualFile

    override fun tearDown() {
        try {
            ActiveSolution.getInstance(project).loadState(ActiveSolution.Choice())
            SolutionService.getInstance(project).solutionFilesChanged()
        } finally {
            super.tearDown()
        }
    }

    fun testOneSolutionIsTheActiveOneWithoutAChoice() {
        val only = sln("ActiveOne/Only.sln")
        val active = ActiveSolution.getInstance(project)
        assertEquals(only, active.pick(listOf(only)))
        assertFalse(active.needsChoice(listOf(only)))
        assertNull(active.chosen())
    }

    fun testOfSeveralTheChosenOneWinsAndTheFirstUntilThen() {
        val first = sln("ActiveTwo/A.sln")
        val second = sln("ActiveTwo/Inner/B.slnx")
        val active = ActiveSolution.getInstance(project)
        assertTrue(active.needsChoice(listOf(first, second)))
        assertEquals(first, active.pick(listOf(first, second)))
        active.choose(second)
        assertEquals("ActiveTwo/Inner/B.slnx", active.state.solution)
        assertEquals(second, active.chosen())
        assertEquals(second, active.pick(listOf(first, second)))
        assertFalse(active.needsChoice(listOf(first, second)))
        // the chosen file gone: back to the first, and a choice is due again
        assertEquals(first, active.pick(listOf(first)))
        assertTrue(active.needsChoice(listOf(first, sln("ActiveTwo/C.sln"))))
    }

    fun testTheServiceGivesTheActiveSolutionOnly() {
        val first = sln("ActiveThree/A.sln")
        val second = sln("ActiveThree/B.sln")
        val solutions = SolutionService.getInstance(project)
        solutions.solutionFilesChanged()
        assertTrue(solutions.foundSolutionFiles().containsAll(listOf(first, second)))
        assertEquals(1, solutions.solutionFiles().size)
        ActiveSolution.getInstance(project).choose(second)
        assertEquals(listOf(second), solutions.solutionFiles())
        assertEquals(listOf(second), solutions.allSolutionFiles())
    }

    fun testTheProcessorOpensSolutionFilesOnly() {
        val processor = SolutionOpenProcessor()
        assertTrue(processor.canOpenProject(sln("Open/X.sln")))
        assertTrue(processor.canOpenProject(sln("Open/X.slnx")))
        assertTrue(processor.canOpenProject(myFixture.addFileToProject("Open/X.slnf", "{}").virtualFile))
        assertFalse(processor.canOpenProject(myFixture.addFileToProject("Open/X.csproj", "<Project/>").virtualFile))
        assertFalse(processor.canOpenProject(myFixture.addFileToProject("Open/Y/readme.md", "x").virtualFile.parent))
        assertFalse(processor.lookForProjectsInDirectory())
    }
}
