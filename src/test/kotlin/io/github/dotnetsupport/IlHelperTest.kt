package io.github.dotnetsupport

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.il.IlAnswer
import io.github.dotnetsupport.il.IlAnswers
import io.github.dotnetsupport.il.IlBody.Kind
import io.github.dotnetsupport.il.IlHelperSource
import io.github.dotnetsupport.il.IlRequest
import junit.framework.TestCase

/**
 * The IL viewer on DotNetHelper: answers of a real run on `debug-playground/Console` (Debug, Release, without a PDB, with an embedded one, plus a
 * file of overloads and an abstract class), saved in `src/test/resources/il` with the paths made neutral. The helper itself is never started.
 */
class IlHelperTest : TestCase() {
    private fun json(name: String): JsonElement =
        JsonParser.parseString(IlHelperTest::class.java.getResourceAsStream("/il/$name.json")!!.use { it.readBytes().toString(Charsets.UTF_8) })

    private fun saved(name: String): IlAnswer = IlAnswers.parse(json(name))!!

    private fun IlAnswer.textAt(body: Int, line: Int): String = bodies[body].text.lines()[line].trim()

    fun testAsyncMethodComesWithItsStateMachineAtTheCaret() {
        val answer = saved("async-debug")
        assertEquals("""C:\work\Playground\Console\bin\Debug\net9.0\Playground.Console.dll""", answer.assembly)
        assertEquals("""C:\work\Playground\Console\bin\Debug\net9.0\Playground.Console.pdb""", answer.pdb)
        assertTrue(answer.assemblyModified > 1_700_000_000_000)
        assertNull(answer.warning)
        assertEquals(listOf(Kind.METHOD, Kind.STATE_MACHINE), answer.bodies.map { it.kind })
        assertEquals(listOf("Scenarios.Async()", "Scenarios.<Async>d__14.MoveNext()"), answer.bodies.map { it.name })
        assertEquals(listOf(false, true), answer.bodies.map { it.atCaret })
        // the kickoff method has no sequence points: it only starts the state machine
        assertTrue(answer.bodies[0].mapping.isEmpty())
        assertTrue(answer.bodies[0].text, answer.bodies[0].text.startsWith(".method private hidebysig static"))
        assertTrue(answer.bodies[0].text.contains("AsyncStateMachineAttribute"))

        val moveNext = answer.bodies[1]
        assertEquals(listOf(185, 186, 187, 188, 189, 190), moveNext.mapping.map { it.startLine })
        val await = moveNext.mapping.single { it.startLine == 187 }
        assertEquals(0x1a, await.offset)
        assertEquals(9, await.startColumn)
        assertEquals(39, await.endColumn)
        assertEquals("IL_001a: ldc.i4.s 20", answer.textAt(1, await.textLine))
        // every mapped line is the label of its offset, in the order of the text
        for (m in moveNext.mapping) assertTrue(answer.textAt(1, m.textLine), answer.textAt(1, m.textLine).startsWith("IL_%04x:".format(m.offset)))
        assertEquals(moveNext.mapping.map { it.textLine }.sorted(), moveNext.mapping.map { it.textLine })
    }

    fun testLambdaInsideItsMethod() {
        val answer = saved("lambda-release")
        assertEquals(listOf(Kind.METHOD, Kind.LAMBDA), answer.bodies.map { it.kind })
        assertEquals("Scenarios.<>c__DisplayClass11_0.<Closures>b__0(int)", answer.bodies[1].name)
        assertTrue(answer.bodies[1].atCaret)
        assertFalse(answer.bodies[0].atCaret)
        val lambda = answer.bodies[1].mapping.single()
        assertEquals(listOf(0, 149, 35, 149, 47), listOf(lambda.offset, lambda.startLine, lambda.startColumn, lambda.endLine, lambda.endColumn))
        assertEquals("IL_0000: ldarg.1", answer.textAt(1, lambda.textLine))
        // Release: no `nop` for the brace, the first line of code is the first sequence point
        assertEquals(148, answer.bodies[0].mapping.first().startLine)
        assertTrue(answer.bodies[0].text.contains("// end of method Scenarios::Closures"))
    }

    fun testLocalFunction() {
        val answer = saved("local-function-debug")
        assertEquals(listOf(Kind.METHOD, Kind.LOCAL_FUNCTION), answer.bodies.map { it.kind })
        assertTrue(answer.bodies[1].name, answer.bodies[1].name.contains("<Closures>g__Local|"))
        assertTrue(answer.bodies[1].atCaret)
        assertEquals(150, answer.bodies[1].mapping.single().startLine)
        // the names of the locals come from the PDB
        assertTrue(answer.bodies[0].text.contains("int32 item"))
    }

    fun testOverloadsByNameWhenThePdbDoesNotKnowTheFile() {
        val answer = saved("overloads-by-name")
        assertEquals(listOf("IlShape.Add(int, int)", "IlShape.Add(double, double)", "IlShape.Add(string, string)"), answer.bodies.map { it.name })
        assertTrue(answer.bodies.all { it.kind == Kind.METHOD && it.mapping.isEmpty() })
        assertEquals(listOf(true, false, false), answer.bodies.map { it.atCaret })
        assertTrue(answer.warning!!, answer.warning!!.contains("has no Nope.cs") && answer.warning!!.contains("all 3 methods named `Add`"))
    }

    fun testTypeHeaderGivesTheWholeType() {
        val answer = saved("type-header")
        val type = answer.bodies.single()
        assertEquals(Kind.TYPE, type.kind)
        assertEquals("IlShape", type.name)
        assertTrue(type.atCaret)
        assertTrue(type.mapping.isEmpty())
        assertTrue(type.text.startsWith(".class public auto ansi abstract beforefieldinit Playground.IlShape"))
        assertTrue(type.text.contains(".field private int32 _count") && type.text.contains("// end of class Playground.IlShape"))
        assertTrue(answer.warning!!.contains("no code at line 3"))
    }

    fun testWithoutPdbTheStateMachineIsFoundByItsAttribute() {
        val answer = saved("async-without-pdb")
        assertNull(answer.pdb)
        assertEquals(listOf(Kind.METHOD, Kind.STATE_MACHINE), answer.bodies.map { it.kind })
        assertEquals(listOf(true, false), answer.bodies.map { it.atCaret })
        assertTrue(answer.bodies.all { it.mapping.isEmpty() })
        assertTrue(answer.warning!!, answer.warning!!.startsWith("No PDB for Playground.Console.dll"))
    }

    fun testAbstractMemberWithAnEmbeddedPdb() {
        val answer = saved("abstract-embedded-pdb")
        assertEquals("embedded", answer.pdb)
        val area = answer.bodies.single()
        assertEquals("IlShape.Area()", area.name)
        assertEquals(".method public hidebysig newslot abstract virtual", area.text.lines().first())
        // no trailing spaces of the disassembler
        assertTrue(area.text.lines().none { it.endsWith(" ") })
    }

    fun testUnknownKindIsAMethodAndIncompleteBodiesAreLeftOut() {
        val answer = IlAnswers.parse(JsonParser.parseString(
            """{"assembly":"a.dll","assemblyModified":5,"pdb":null,"bodies":[{"name":"X.Y()","kind":"future","text":"t","atCaret":true,
               "mapping":[{"textLine":0,"offset":1,"startLine":2,"startColumn":3,"endLine":4,"endColumn":5},{"offset":7}]},{"kind":"method","text":"no name"}]}"""))!!
        assertEquals(Kind.METHOD, answer.bodies.single().kind)
        assertEquals(1, answer.bodies.single().mapping.size)
        assertNull(answer.pdb)
        assertNull(IlAnswers.parse(JsonNull.INSTANCE))
        assertNull(IlAnswers.parse(JsonParser.parseString("""{"bodies":[]}""")))
    }

    fun testTheSourceSendsTheRequestAndParsesTheAnswer() {
        var sent: JsonObject? = null
        val source = IlHelperSource { method, params, _ ->
            assertEquals("il", method)
            sent = params.asJsonObject
            json("async-debug")
        }
        val answer = source.il(IlRequest("""C:\work\a.dll""", "C:/work/Scenarios.cs", 187, "Playground.Scenarios", null))
        assertEquals(2, answer.bodies.size)
        assertEquals("""{"assembly":"C:\\work\\a.dll","file":"C:/work/Scenarios.cs","line":187,"typeName":"Playground.Scenarios"}""", sent.toString())

        val empty = IlHelperSource { _, _, _ -> JsonNull.INSTANCE }
        assertTrue(runCatching { empty.il(IlRequest("a.dll", "a.cs", 1)) }.exceptionOrNull() is HelperException)
    }
}
