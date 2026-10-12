package io.github.dotnetsupport.run

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.dotnetsupport.build.MsBuildOutputParser

/** Makes `Program.cs(12,5): error CS1002: ...` lines of `dotnet run` / `dotnet test` output clickable. */
class MsBuildConsoleFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        if (": error" !in line && ": warning" !in line) return null
        val message = MsBuildOutputParser.parseLine(line.trimEnd('\n', '\r')) ?: return null
        val range = message.fileRange ?: return null
        val file = message.resolveFile()?.let { LocalFileSystem.getInstance().findFileByIoFile(it) } ?: return null

        val lineStart = entireLength - line.length
        val link = OpenFileHyperlinkInfo(project, file, (message.line - 1).coerceAtLeast(0), (message.column - 1).coerceAtLeast(0))
        return Filter.Result(lineStart + range.first, lineStart + range.last + 1, link)
    }
}
