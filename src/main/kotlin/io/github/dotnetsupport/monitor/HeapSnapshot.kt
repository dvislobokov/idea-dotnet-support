package io.github.dotnetsupport.monitor

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class HeapType(val name: String, val module: String, val count: Long, val bytes: Long)

/** A type in two snapshots; a type that exists in only one of them has zeros on the other side. */
class HeapTypeDifference(val type: HeapType, val countDelta: Long, val bytesDelta: Long)

class HeapSnapshot(val processTitle: String, val takenAt: LocalTime, val totalBytes: Long, val objects: Long, val types: List<HeapType>) {
    /** `10:15:00`: LocalTime.toString() drops zero seconds. */
    val time: String get() = takenAt.format(TIME)
    val label: String get() = time + "  " + ChartFormats.bytes(totalBytes.toDouble())

    override fun toString(): String = label

    /** Every type of this snapshot against [baseline], plus the types that have disappeared since. */
    fun compareWith(baseline: HeapSnapshot): List<HeapTypeDifference> {
        val before = baseline.types.associateBy { it.name to it.module }
        val now = types.map { type ->
            val old = before[type.name to type.module]
            HeapTypeDifference(type, type.count - (old?.count ?: 0), type.bytes - (old?.bytes ?: 0))
        }
        val present = types.mapTo(HashSet()) { it.name to it.module }
        val gone = baseline.types.filter { (it.name to it.module) !in present }
            .map { HeapTypeDifference(HeapType(it.name, it.module, 0, 0), -it.count, -it.bytes) }
        return now + gone
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val COLUMNS = Regex("""\s{2,}""")
        private val SIZE_CLASS = Regex("""\s*\(Bytes > \d+[KMG]?\)$""")

        /**
         * Output of `dotnet-gcdump report` (heapstat):
         * ```
         *      39,114,093  GC Heap bytes
         *          13,633  GC Heap objects
         *
         *    Object Bytes     Count  Type
         *         200,024       190  System.Byte[] (Bytes > 100K)  [System.Private.CoreLib.dll]
         *              22     2,094  System.String  [System.Private.CoreLib.dll]
         * ```
         * "Object Bytes" is the size of one object, and large objects of a type are listed separately by size class:
         * the rows of a type are added up, bytes times count. The thousands separator follows the culture of the machine.
         */
        fun parse(output: String, processTitle: String, takenAt: LocalTime = LocalTime.now()): HeapSnapshot? {
            var totalBytes = -1L
            var objects = -1L
            val types = LinkedHashMap<Pair<String, String>, LongArray>()
            for (line in output.lineSequence()) {
                val cells = line.trim().split(COLUMNS)
                val first = number(cells[0]) ?: continue
                if (cells.size == 2 && cells[1].startsWith("GC Heap")) {
                    if (cells[1].contains("bytes", ignoreCase = true)) totalBytes = first
                    if (cells[1].contains("objects", ignoreCase = true)) objects = first
                    continue
                }
                if (cells.size < 3) continue
                val count = number(cells[1]) ?: continue
                val name = cells[2].replace(SIZE_CLASS, "")
                val module = cells.getOrNull(3).orEmpty().removeSurrounding("[", "]")
                val sums = types.getOrPut(name to module) { LongArray(2) }
                sums[0] += count
                sums[1] += first * count
            }
            if (types.isEmpty()) return null
            val list = types.map { (key, sums) -> HeapType(key.first, key.second, sums[0], sums[1]) }.sortedByDescending { it.bytes }
            return HeapSnapshot(processTitle, takenAt, if (totalBytes >= 0) totalBytes else list.sumOf { it.bytes }, if (objects >= 0) objects else list.sumOf { it.count }, list)
        }

        /** `39,114,093`, `39 114 093`, `39.114.093`: only the digits matter. */
        private fun number(cell: String): Long? {
            if (cell.isEmpty() || !cell[0].isDigit() || cell.any { it.isLetter() }) return null
            return cell.filter { it.isDigit() }.toLongOrNull()
        }
    }
}

/** The snapshots of this IDE session, per process: a second snapshot is worth more than the first, it shows what grows. */
@Service(Service.Level.PROJECT)
class HeapSnapshots {
    private val byProcess = HashMap<Long, MutableList<HeapSnapshot>>()

    @Synchronized fun add(pid: Long, snapshot: HeapSnapshot) {
        val list = byProcess.getOrPut(pid) { ArrayList() }
        list += snapshot
        if (list.size > KEPT) list.removeAt(0)
    }

    /** The oldest first. */
    @Synchronized fun of(pid: Long): List<HeapSnapshot> = byProcess[pid].orEmpty().toList()

    companion object {
        private const val KEPT = 8

        fun getInstance(project: Project): HeapSnapshots = project.service()
    }
}
