package io.github.dotnetsupport

import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.build.BuildViewEvents
import java.io.File

/** The events of the Build tool window made through the builders of the platform carry what the `*EventImpl` constructors used to. */
class BuildViewEventsTest : BasePlatformTestCase() {
    fun testTheEventsCarryTheirFields() {
        val buildId = Any()
        val descriptor = DefaultBuildDescriptor(buildId, "Build App", "C:/work", 1L)
        val start = BuildViewEvents.started(descriptor, "running...") as StartBuildEvent
        assertEquals("running...", start.message)
        assertSame(buildId, start.id)
        assertSame(descriptor, start.buildDescriptor)

        val output = BuildViewEvents.output(buildId, "> dotnet build\n", true) as OutputBuildEvent
        assertEquals("> dotnet build\n", output.message)
        assertSame(buildId, output.parentId)
        assertTrue(output.isStdOut)
        assertFalse((BuildViewEvents.output(buildId, "oops", false) as OutputBuildEvent).isStdOut)

        val position = FilePosition(File("C:/work/App/Program.cs"), 4, 2)
        val file = BuildViewEvents.message(buildId, MessageEvent.Kind.ERROR, "MSBuild", "CS0103: The name 'x' does not exist", "Program.cs(5,3): error CS0103", position) as FileMessageEvent
        assertEquals(MessageEvent.Kind.ERROR, file.kind)
        assertEquals("MSBuild", file.group)
        assertEquals("CS0103: The name 'x' does not exist", file.message)
        assertEquals("Program.cs(5,3): error CS0103", file.description)
        assertEquals(position, file.filePosition)
        assertSame(buildId, file.parentId)

        val plain = BuildViewEvents.message(buildId, MessageEvent.Kind.WARNING, "MSBuild", "NU1603: dependency", "warning NU1603", null)
        assertTrue(plain is MessageEvent && plain !is FileMessageEvent)
        assertEquals(MessageEvent.Kind.WARNING, (plain as MessageEvent).kind)

        val finish = BuildViewEvents.finished(buildId, "failed with 1 error", FailureResultImpl()) as FinishBuildEvent
        assertEquals("failed with 1 error", finish.message)
        assertSame(buildId, finish.id)
        assertTrue(finish.result is FailureResultImpl)
        assertTrue(finish.eventTime > 0)
    }
}
