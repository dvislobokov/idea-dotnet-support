package io.github.dotnetsupport.build

import com.intellij.build.BuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.BuildEvents
import com.intellij.build.events.EventResult
import com.intellij.build.events.MessageEvent
import com.intellij.execution.process.ProcessOutputType

/**
 * The events of the Build tool window, through the builders of [BuildEvents]: the `*EventImpl` constructors are deprecated in 2026.1
 * (`@Internal`, the one of `StartBuildEventImpl` for removal). One place to touch when the builders move again.
 */
object BuildViewEvents {
    private val events: BuildEvents get() = BuildEvents.getInstance()

    fun started(descriptor: BuildDescriptor, message: String): BuildEvent = events.startBuild(message, descriptor).build()

    fun output(buildId: Any, text: String, stdOut: Boolean): BuildEvent =
        events.output(text).withParentId(buildId).withOutputType(if (stdOut) ProcessOutputType.STDOUT else ProcessOutputType.STDERR).build()

    /** A diagnostic of MSBuild: [text] is the row, [detail] the whole line of the output, [position] makes the row navigable. */
    fun message(buildId: Any, kind: MessageEvent.Kind, group: String, text: String, detail: String, position: FilePosition?): BuildEvent =
        // 2026.1 has no `withFilePosition` on the message builder yet, the file message is a builder of its own
        if (position != null) events.fileMessage(text, kind, position).withParentId(buildId).withGroup(group).withDescription(detail).build()
        else events.message(text, kind).withParentId(buildId).withGroup(group).withDescription(detail).build()

    fun finished(buildId: Any, message: String, result: EventResult): BuildEvent = events.finishBuild(buildId, message, result).withTime(System.currentTimeMillis()).build()
}
