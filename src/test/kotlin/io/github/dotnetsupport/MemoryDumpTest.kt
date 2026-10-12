package io.github.dotnetsupport

import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.monitor.MemoryDumps
import io.github.dotnetsupport.monitor.Sos
import io.github.dotnetsupport.monitor.SosAnswers
import junit.framework.TestCase
import java.io.File
import java.time.LocalTime

/** Memory Dump of the .NET Monitor: the output of `dotnet-dump analyze` 10.0 as probed on the `leak` scenario of `debug-playground`. */
class MemoryDumpTest : TestCase() {
    /** With its input redirected, analyze ends every answer with a marker instead of a prompt; the marker may come split between reads. */
    fun testAnswersAreSplitByMarkers() {
        val answers = SosAnswers()
        val banner = answers.feed("Loading core dump: C:\\x.dmp ...\nReady to process analysis commands.\n<END_COMMAND_OUT")
        assertTrue(banner.isEmpty())
        val first = answers.feed("PUT>\n> dumpobj zzzz\nInvalid parameter zzzz\n<END_COMMAND_ERROR>\n> clrthreads\nThreadCount:      3\n<END_COMMAND_OUTPUT>")
        assertEquals(3, first.size)
        assertEquals(listOf(false, true, false), first.map { it.failed })
        assertEquals("Invalid parameter zzzz", first[1].text)
        assertEquals("the echo of the command is dropped", "ThreadCount:      3", first[2].text)
    }

    fun testHeapStatistics() {
        val types = Sos.heapStat("""
            > dumpheap -stat -type Playground
            Statistics:
                      MT Count TotalSize Class Name
            7ffe13f5e810     1        56 Playground.Scenarios+<Run>d__1
            7ffe13f6da28     1        80 System.Collections.Generic.Dictionary<System.Int32, Playground.CachedOrder>
            7ffe13f5f908     7       224 Playground.PriceWatcher
            7ffe13f5f600 1,400    56,000 Playground.CachedOrder
            7ffe13f19280 1,404 1,467,925 System.Byte[]
            Total 1,418 objects, 143,720 bytes
        """.trimIndent())
        assertEquals(listOf("System.Byte[]", "Playground.CachedOrder", "Playground.PriceWatcher"), types.take(3).map { it.name })
        val orders = types.single { it.name == "Playground.CachedOrder" }
        assertEquals("7ffe13f5f600", orders.methodTable)
        assertEquals(1400L, orders.count)
        assertEquals(56_000L, orders.totalSize)
        assertEquals("generic arguments with spaces stay in the name",
            "System.Collections.Generic.Dictionary<System.Int32, Playground.CachedOrder>", types.single { it.methodTable == "7ffe13f6da28" }.name)
        assertEquals(1418L to 143_720L, Sos.total("Total 1,418 objects, 143,720 bytes"))
    }

    fun testObjectsOfAType() {
        val objects = Sos.objects("""
                     Address               MT           Size
                022809c48e28     7ffe13f5f908             32
                022809c80908     7ffe13f5f908             32

            Statistics:
                      MT Count TotalSize Class Name
            7ffe13f5f908     7       224 Playground.PriceWatcher
        """.trimIndent())
        assertEquals(listOf("022809c48e28", "022809c80908"), objects.map { it.address })
        assertEquals(32L, objects[0].size)
    }

    fun testRetentionPaths() {
        val paths = Sos.gcRoots("""
            Caching GC roots, this may take a while.
            Subsequent runs of this command will be faster.
            HandleTable:
                00000228058a13e8 (strong handle)
                      -> 022807400028     System.Object[]
                      -> 022809dace08     System.EventHandler<System.Decimal> (static variable: System.Collections.Generic.Dictionary<T1, T2>.Orders)
                      -> 022809c48e28     Playground.PriceWatcher

            Thread 6a30:
                0000004E98B7E250 00007FFE13E72E47 Playground.Scenarios.Leak() [C:\src\Scenarios.cs @ 250]
                    rbp-48: 0000004e98b7e1e8
                        ->  022809c48e28     Playground.PriceWatcher

            Found 2 unique roots.
        """.trimIndent())
        assertEquals(2, paths.size)
        assertEquals("HandleTable: 00000228058a13e8 (strong handle)", paths[0].root)
        assertEquals(listOf("System.Object[]", "System.EventHandler<System.Decimal>", "Playground.PriceWatcher"), paths[0].steps.map { it.type })
        assertEquals("static variable: System.Collections.Generic.Dictionary<T1, T2>.Orders", paths[0].steps[1].note)
        assertNull(paths[0].steps[2].note)
        assertEquals("the frame without its stack and instruction pointers",
            "Thread 6a30: Playground.Scenarios.Leak() [C:\\src\\Scenarios.cs @ 250] rbp-48: 0000004e98b7e1e8", paths[1].root)
        assertEquals("022809c48e28", paths[1].steps.single().address)
        assertTrue(Sos.gcRoots("Found 0 unique roots.").isEmpty())
    }

    fun testObjectDetails() {
        val details = Sos.dumpObj("""
            Name:        Playground.CachedOrder
            MethodTable: 00007ffe13f5f600
            Size:        40(0x28) bytes
            File:        C:\src\bin\Playground.Console.dll
            Fields:
                          MT    Field   Offset                 Type   VT     Attr            Value Name
            0000000000000000 04000011       18         System.Int32  Yes instance                0 <Id>k__BackingField
            0000000000000000 04000012        8        System.String   No instance 0000022809c0bcc0 <Name>k__BackingField
            00007ffe13f6f600 04000017       10 ...Private.CoreLib]]   No instance 0000000000000000 _history
        """.trimIndent())!!
        assertEquals("Playground.CachedOrder", details.type)
        assertEquals(40L, details.size)
        assertEquals(listOf("<Id>k__BackingField", "<Name>k__BackingField", "_history"), details.fields.map { it.name })
        assertNull("a value type is no reference", details.fields[0].reference)
        assertEquals("0000022809c0bcc0", details.fields[1].reference)
        assertNull("null", details.fields[2].reference)
        assertEquals("text", Sos.dumpObj("Name:        System.String\nSize:        30(0x1e) bytes\nString:      text\n")!!.stringValue)
        assertNull(Sos.dumpObj("Invalid parameter zzzz"))
    }

    fun testCollectCommand() {
        val command = MemoryDumps.collectCommand(File("dotnet-dump"), 42, File("out.dmp"))
        assertEquals(listOf("collect", "--process-id", "42", "--output", "out.dmp", "--type", "Heap"), command.parametersList.list)
        assertEquals("Playground_Console-42-093005.dmp", MemoryDumps.dumpFile("Playground.Console (42)", 42, LocalTime.of(9, 30, 5)).name)
        assertEquals(listOf("tool", "update", "--global", "dotnet-dump"), DotNetTool.DUMP.installCommand())
    }
}
