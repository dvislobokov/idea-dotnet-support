package io.github.dotnetsupport.coverage

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import java.io.File

class LineCoverage(val hits: Int, val branchesCovered: Int, val branchesTotal: Int) {
    val isCovered: Boolean get() = hits > 0
    /** Executed, but not every branch of the condition on the line was taken. */
    val isPartial: Boolean get() = isCovered && branchesCovered < branchesTotal

    fun merge(other: LineCoverage): LineCoverage =
        LineCoverage(hits + other.hits, maxOf(branchesCovered, other.branchesCovered), maxOf(branchesTotal, other.branchesTotal))
}

/** [lines] are keyed by the 1-based line number. */
class FileCoverage(val path: String, val lines: Map<Int, LineCoverage>) {
    val coveredLines: Int get() = lines.values.count { it.isCovered }
    val totalLines: Int get() = lines.size
    val coveredBranches: Int get() = lines.values.sumOf { it.branchesCovered }
    val totalBranches: Int get() = lines.values.sumOf { it.branchesTotal }
}

class CoverageReport(val files: List<FileCoverage>) {
    val coveredLines: Int get() = files.sumOf { it.coveredLines }
    val totalLines: Int get() = files.sumOf { it.totalLines }

    /** Several reports of one run (a test project per report) cover the same sources: hits are summed. */
    fun merge(other: CoverageReport): CoverageReport {
        val merged = LinkedHashMap<String, MutableMap<Int, LineCoverage>>()
        for (file in files + other.files) {
            val lines = merged.getOrPut(file.path) { LinkedHashMap() }
            for ((number, line) in file.lines) lines.merge(number, line, LineCoverage::merge)
        }
        return CoverageReport(merged.map { (path, lines) -> FileCoverage(path, lines) })
    }

    companion object {
        val EMPTY = CoverageReport(emptyList())
    }
}

/** Cobertura XML, the format of coverlet (`dotnet test --collect:"XPlat Code Coverage"`). */
object CoberturaParser {
    private val CONDITION_COVERAGE = Regex("""\((\d+)/(\d+)\)""")

    /** [fileExists] resolves a relative `filename` against the `<source>` roots; injectable for tests. */
    fun parse(text: CharSequence, fileExists: (String) -> Boolean = { File(it).isFile }): CoverageReport {
        val root = try {
            JDOMUtil.load(text.toString().removePrefix("\uFEFF"))
        } catch (_: Exception) {
            return CoverageReport.EMPTY
        }
        val sources = root.getChild("sources")?.getChildren("source").orEmpty().map { it.textTrim }.filter { it.isNotEmpty() }

        val files = LinkedHashMap<String, MutableMap<Int, LineCoverage>>()
        for (classElement in root.getChild("packages")?.getChildren("package").orEmpty().flatMap { it.getChild("classes")?.getChildren("class").orEmpty() }) {
            val path = resolve(classElement.getAttributeValue("filename") ?: continue, sources, fileExists)
            val lines = files.getOrPut(path) { LinkedHashMap() }
            // the <lines> of the class already include the ones of its <methods>
            for (line in classElement.getChild("lines")?.getChildren("line").orEmpty()) {
                val number = line.getAttributeValue("number")?.toIntOrNull() ?: continue
                lines.merge(number, lineCoverage(line), LineCoverage::merge)
            }
        }
        return CoverageReport(files.map { (path, lines) -> FileCoverage(path, lines) })
    }

    private fun lineCoverage(line: Element): LineCoverage {
        val branches = line.getAttributeValue("condition-coverage")?.let(CONDITION_COVERAGE::find)
        return LineCoverage(
            hits = line.getAttributeValue("hits")?.toIntOrNull() ?: 0,
            branchesCovered = branches?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            branchesTotal = branches?.groupValues?.get(2)?.toIntOrNull() ?: 0,
        )
    }

    /** coverlet writes `<source>C:\</source>` and file names relative to it. */
    private fun resolve(fileName: String, sources: List<String>, fileExists: (String) -> Boolean): String {
        val normalized = fileName.replace('\\', '/')
        val candidates = listOf(normalized) + sources.map { it.replace('\\', '/').trimEnd('/') + "/" + normalized.trimStart('/') }
        return candidates.firstOrNull(fileExists) ?: candidates.last()
    }
}
