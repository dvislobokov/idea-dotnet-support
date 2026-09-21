package io.github.dotnetsupport

import io.github.dotnetsupport.lang.CSharpIndentRules
import io.github.dotnetsupport.lang.IndentSizes
import junit.framework.TestCase

class CSharpIndentRulesTest : TestCase() {
    private val rules = CSharpIndentRules.DEFAULT

    /** The line marked with `>` of [code]: the text without the mark, and where that line starts. */
    private fun locate(code: List<String>): Pair<String, Int> {
        val at = code.indexOfFirst { it.startsWith(">") }
        check(at >= 0) { "no line is marked with > in $code" }
        val lines = code.mapIndexed { i, line -> if (i == at) line.removePrefix(">") else line }
        return lines.joinToString("\n") to lines.take(at).sumOf { it.length + 1 }
    }

    /** Every example of the rules file gives the indent it promises, by the rule it stands under. */
    fun testEveryExampleOfTheRules() {
        val failures = ArrayList<String>()
        var count = 0
        for (rule in rules.rules) for (example in rule.examples) {
            count++
            val (text, lineStart) = locate(example.code)
            val result = rules.indentOf(text, lineStart)
            val problem = when {
                result == null -> "no rule matched"
                result.second.id != rule.id -> "decided by ${result.second.id}, indent ${result.first}"
                result.first != example.expect -> "indent ${result.first}, expected ${example.expect}"
                else -> null
            }
            if (problem != null) failures += "${rule.id}: $problem\n      " + example.code.joinToString("\n      ")
        }
        assertTrue("$count examples", count > 60)
        assertTrue("${failures.size} of $count examples fail:\n  " + failures.joinToString("\n  "), failures.isEmpty())
    }

    fun testRulesAreWellFormed() {
        assertEquals("ids are unique", rules.rules.size, rules.rules.map { it.id }.toSet().size)
        val anchors = setOf("column0", "keep", "previousLine", "statementStart", "scopeOpen", "matchingOpen", "caseLabel", "keyword")
        val states = setOf("statementStart", "inCaseSection", "afterControlHeader", "afterAttribute", "previousLineInScope")
        val scopes = setOf("none", "block", "switch", "list", "paren", "bracket")
        for (rule in rules.rules) {
            assertTrue(rule.id, rule.anchor in anchors && rule.add in setOf("none", "indent", "continuation", "star"))
            assertTrue(rule.id, states.containsAll(rule.states) && scopes.containsAll(rule.scopes.orEmpty()))
            assertTrue("${rule.id} is not described", rule.about.length > 20)
            assertTrue("${rule.id}: a keyword anchor needs keywords", rule.anchor != "keyword" || rule.keywords.isNotEmpty())
            // a rule that depends on an option is shown by its own examples only with that option, so it may have none
            assertTrue("${rule.id} has no examples", rule.examples.isNotEmpty() || rule.optionName != null)
        }
        // the last rule takes whatever is left: every line of code gets an answer
        assertTrue(rules.rules.last().let { it.first == null && it.previous == null && it.scopes == null && it.states.isEmpty() && it.optionName == null })
    }

    fun testOptionsOfEditorConfig() {
        fun indent(code: List<String>, options: Map<String, String>): Pair<Int?, String> = locate(code).let { (text, start) -> rules.indentOf(text, start, options = options)!!.let { it.first to it.second.id } }
        val label = listOf("    switch (x)", "    {", ">case 1:")
        assertEquals(8 to "switch.label", indent(label, emptyMap()))
        assertEquals(4 to "switch.label.flat", indent(label, mapOf("csharp_indent_switch_labels" to "false")))
        val content = listOf("    switch (x)", "    {", "        case 1:", ">Work();")
        assertEquals(8 to "switch.section.flat", indent(content, mapOf("csharp_indent_case_contents" to "false")))
        val brace = listOf("    void M()", "    {", "        Work();", ">}")
        assertEquals(8 to "close.brace.indented", indent(brace, mapOf("csharp_indent_braces" to "true")))
    }

    fun testSizesAndTabs() {
        val (text, start) = locate(listOf("class A", "{", "\tvoid M()", "\t{", "\t\tvar q = xs", ">.ToList();"))
        assertEquals(10, rules.indentOf(text, start, IndentSizes(indent = 4, continuation = 2, tab = 4, useTabs = true))!!.first)
        assertEquals("\t\t  ", IndentSizes(tab = 4, useTabs = true).text(10))
        assertEquals("      ", IndentSizes(indent = 2).text(6))
        val (body, bodyStart) = locate(listOf("class A", "{", ">int x;"))
        assertEquals(2, rules.indentOf(body, bodyStart, IndentSizes(indent = 2, continuation = 4))!!.first)
    }

    /** A real method typed line by line: what Enter gives at the start of every line is what the file has. */
    fun testAWholeFileLineByLine() {
        val source = """
            using System;

            namespace Shop.Orders
            {
                public class OrderService : IOrderService
                {
                    private readonly Dictionary<string, int> _cache = new()
                    {
                        ["a"] = 1,
                        ["b"] = 2,
                    };

                    [Obsolete]
                    public decimal Total(
                        IEnumerable<OrderLine> lines,
                        decimal discount)
                    {
                        var total = lines
                            .Where(line => line.Quantity > 0)
                            .Sum(line => line.Price * line.Quantity);
                        if (discount > 0)
                        {
                            total -= total * discount;
                        }
                        else if (discount < 0)
                            throw new ArgumentException(
                                "negative",
                                nameof(discount));
                        else
                            total += 0;

                        foreach (var line in lines)
                            Log(line);

                        switch (total)
                        {
                            case 0:
                                return 0;
                            case 1:
                            case 2:
                                {
                                    return 1;
                                }
                            default:
                                break;
                        }

                        return Run(() =>
                        {
                            return total;
                        });
                    }

                    public string Name => _name
                        ?? "orders";
                }
            }
        """.trimIndent()
        val lines = source.lines()
        val wrong = ArrayList<String>()
        var offset = 0
        for ((number, line) in lines.withIndex()) {
            if (line.isNotBlank()) {
                val expected = line.length - line.trimStart().length
                // the file as it is while the line is typed: everything above, and the line itself without its indent
                val text = lines.take(number).joinToString("") { it + "\n" } + line.trimStart()
                val actual = rules.indentOf(text, text.length - line.trimStart().length)
                if (actual?.first != expected) wrong += "line ${number + 1} `${line.trim()}`: ${actual?.first} by ${actual?.second?.id}, expected $expected"
            }
            offset += line.length + 1
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** Code that is being typed: never an exception. */
    fun testUnfinishedCode() {
        for (text in listOf("", "}", "))) ]]", "class A { void M( {", "/* open", "var s = @\"open", "case 1:", "else", "#if")) {
            for (start in 0..text.length) rules.indentOf(text + "\nx", start)
            rules.indentOf(text + "\n", text.length + 1)
        }
    }
}
