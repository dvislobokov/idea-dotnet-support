package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.debugger.CSharpLineBreakpointType
import io.github.dotnetsupport.frameworks.razor.RazorBreakpointLines
import io.github.dotnetsupport.frameworks.razor.RazorFile
import io.github.dotnetsupport.frameworks.razor.RazorLanguage

/** RAZOR_PLAN.md, R8: the lines of a `.razor` file a line breakpoint can be put on — the C# the compiler maps there, not the markup or the directives. */
class RazorBreakpointsTest : BasePlatformTestCase() {
    private val counter = """
        @page "/counter"
        @using System.Text
        <h1>Counter</h1>
        <p>Current count: @count</p>
        @if (count > 3)
        {
            <p>Many</p>
        }

        @code {
            private int count;
            private int step = 2;

            private void Increment()
            {
                count += step;
            }
        }
    """.trimIndent()

    fun testTheLinesOfTheCSharp() {
        val file = myFixture.configureByText("Counter.razor", counter)
        val razor = file.viewProvider.getPsi(RazorLanguage) as RazorFile
        val lines = RazorBreakpointLines.find(razor)
        val text = counter.lines()
        val allowed = lines.sorted().map { text[it].trim() }
        assertTrue(allowed.toString(), "<p>Current count: @count</p>" in allowed)
        assertTrue(allowed.toString(), "@if (count > 3)" in allowed)
        assertTrue("a statement of a method: $allowed", "count += step;" in allowed)
        assertTrue("a field with an initializer: $allowed", "private int step = 2;" in allowed)
        for (line in listOf("@page \"/counter\"", "@using System.Text", "<h1>Counter</h1>", "<p>Many</p>", "private int count;", "@code {")) {
            assertFalse("no code runs on `$line`: $allowed", line in allowed)
        }
        // the breakpoint type asks the framework's source for its files
        val type = CSharpLineBreakpointType()
        assertTrue(type.canPutAt(file.virtualFile, text.indexOf("    count += step;").let { if (it >= 0) it else text.indexOfFirst { l -> l.trim() == "count += step;" } }, project))
        assertFalse(type.canPutAt(file.virtualFile, 0, project))
    }
}
