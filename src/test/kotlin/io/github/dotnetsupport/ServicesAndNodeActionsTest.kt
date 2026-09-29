package io.github.dotnetsupport

import com.intellij.execution.RunManager
import com.intellij.execution.dashboard.RunDashboardCustomizer
import com.intellij.execution.dashboard.RunDashboardDefaultTypesProvider
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.run.DotNetConfigurationType
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.run.DotNetRunDashboardCustomizer
import io.github.dotnetsupport.run.DotNetRunDashboardTypes
import io.github.dotnetsupport.run.ListeningAddressRecorder
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.ProjectKey
import io.github.dotnetsupport.view.ProjectNodeDeleteProvider
import io.github.dotnetsupport.view.ProjectNodeRenameHandler
import io.github.dotnetsupport.view.SolutionKey

/** The Services tool window knows the .NET configurations; F2 and Delete on a project node. */
class ServicesAndNodeActionsTest : BasePlatformTestCase() {
    fun testDotNetConfigurationsAreDefaultServices() {
        val providers = ExtensionPointName.create<RunDashboardDefaultTypesProvider>("com.intellij.runDashboardDefaultTypesProvider").extensionList
        assertTrue(providers.any { it is DotNetRunDashboardTypes })
        assertEquals(DotNetConfigurationType().id, DotNetRunDashboardTypes.TYPE_ID)
        assertTrue(providers.flatMap { it.getDefaultTypeIds(project) }.contains(DotNetConfigurationType().id))

        val settings = RunManager.getInstance(project).createConfiguration("Svc", DotNetConfigurationType::class.java)
        assertTrue(settings.configuration is DotNetRunConfiguration)
        assertTrue(RunDashboardCustomizer.CUSTOMIZER_EP_NAME.extensionList.filterIsInstance<DotNetRunDashboardCustomizer>().single().isApplicable(settings, null))
    }

    fun testListeningAddressIsKeptOnTheProcess() {
        val handler = NopProcessHandler()
        ListeningAddressRecorder.attach(handler)
        handler.startNotify()
        handler.notifyTextAvailable("info: Microsoft.Hosting.Lifetime[14]\n", ProcessOutputTypes.STDOUT)
        assertNull(handler.getUserData(ListeningAddressRecorder.KEY))
        handler.notifyTextAvailable("      Now listening on: http://0.0.0.0:5000\n", ProcessOutputTypes.STDOUT)
        handler.notifyTextAvailable("      Now listening on: https://localhost:7001\n", ProcessOutputTypes.STDOUT)
        // the first address, made openable
        assertEquals("http://localhost:5000", handler.getUserData(ListeningAddressRecorder.KEY))
    }

    fun testRenameAndDeleteOnProjectNodes() {
        myFixture.addFileToProject("na/App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        val sln = myFixture.addFileToProject("na/Na.slnx", """<Solution><Project Path="App/App.csproj" /></Solution>""").virtualFile
        val slnf = myFixture.addFileToProject("na/Na.slnf", """{ "solution": { "path": "Na.slnx", "projects": [ "App/App.csproj" ] } }""").virtualFile
        val app = SolutionService.getInstance(project).solution(sln).allProjects.single()
        fun context(selected: Any) = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.SELECTED_ITEMS, arrayOf(selected)).build()

        val rename = RenameHandler.EP_NAME.extensionList.filterIsInstance<ProjectNodeRenameHandler>().single()
        assertTrue(rename.isAvailableOnDataContext(context(ProjectKey(sln, app))))
        assertFalse("a solution is not renamed this way", rename.isAvailableOnDataContext(context(SolutionKey(sln))))
        assertFalse("a filter cannot be edited by dotnet sln", rename.isAvailableOnDataContext(context(ProjectKey(slnf, app))))

        val delete = ProjectNodeDeleteProvider()
        assertTrue(delete.canDeleteElement(context(ProjectKey(sln, app))))
        assertFalse(delete.canDeleteElement(context(ProjectKey(slnf, app))))
        assertFalse(delete.canDeleteElement(context(SolutionKey(sln))))
    }
}
