package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointType
import io.github.dotnetsupport.debugger.CSharpBreakpointLambdas
import io.github.dotnetsupport.debugger.CSharpLineBreakpointType
import io.github.dotnetsupport.debugger.DotNetLineBreakpointHandler
import io.github.dotnetsupport.debugger.DotNetLineBreakpointProperties
import io.github.dotnetsupport.lang.CSharpSyntaxTrees

/** Breakpoints in lambdas, as in Rider: a line with lambdas offers the line or one of them; a lambda's breakpoint goes with the column of its body. */
class DebugLambdaBreakpointsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
    }

    override fun tearDown() {
        try {
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val source = """
        using System;
        using System.Linq;
        class Program
        {
            static void Main()
            {
                var evens = new[] { 1, 2, 3, 4 }.Where(n => n % 2 == 0).Select(n => n * 10).ToArray();
                Action log = () =>
                {
                    Console.WriteLine(evens.Length);
                };
                var label = evens.Length switch { 0 => "none", _ => "some" };
            }
        }
    """.trimIndent()

    private fun type(): CSharpLineBreakpointType = XBreakpointType.EXTENSION_POINT_NAME.findExtension(CSharpLineBreakpointType::class.java)!!

    fun testALineWithLambdasOffersTheLineAndEachLambda() {
        val file = myFixture.configureByText("Lambdas.cs", source).virtualFile
        val line = source.lines().indexOfFirst { "Where(" in it }
        val variants = type().computeVariants(project, XDebuggerUtil.getInstance().createPosition(file, line)!!)
        assertEquals(listOf("Line", "λ n => n % 2 == 0", "λ n => n * 10"), variants.map { it.text })
        val text = myFixture.editor.document.text
        assertEquals("the body the breakpoint stops at", listOf("n % 2 == 0", "n * 10"), variants.drop(1).map { it.highlightRange!!.substring(text) })
        assertEquals(listOf(-1, 0, 1), variants.map { (it.createProperties() as DotNetLineBreakpointProperties).lambdaOrdinal })
    }

    fun testLinesWithoutALambdaBodyOnThemHaveNoVariants() {
        val file = myFixture.configureByText("Lambdas2.cs", source).virtualFile
        val lines = source.lines()
        fun variants(marker: String) = type().computeVariants(project, XDebuggerUtil.getInstance().createPosition(file, lines.indexOfFirst { marker in it })!!)
        assertEmpty("a plain line", variants("Console.WriteLine"))
        assertEmpty("a lambda whose block starts on the next line: the line itself", variants("Action log"))
        assertEmpty("the arms of a switch expression are no lambdas", variants("switch"))
    }

    fun testTheColumnGoesToTheAdapterOneBased() {
        val text = myFixture.configureByText("Lambdas3.cs", source).text
        val line = source.lines().indexOfFirst { "Where(" in it }
        val lineStart = text.lines().take(line).sumOf { it.length + 1 }
        val lambdas = CSharpBreakpointLambdas.on(myFixture.file, lineStart, lineStart + text.lines()[line].length)
        val column = lambdas[1].body.startOffset - lineStart
        assertEquals(text.lines()[line].indexOf("n * 10"), column)
        assertEquals(mapOf("line" to line + 1, "column" to column + 1), DotNetLineBreakpointHandler.breakpointJson(line, null, null, null, column))
        assertEquals("a line's breakpoint has no column", mapOf("line" to line + 1), DotNetLineBreakpointHandler.breakpointJson(line, null, null, null))
    }
}
