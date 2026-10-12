package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpCompleteStatement
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFeatures
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpGhostText
import io.github.dotnetsupport.lang.CSharpSelection
import io.github.dotnetsupport.lang.HeuristicCSharpFile
import io.github.dotnetsupport.lang.NativeCSharpCompleteStatement
import io.github.dotnetsupport.lang.NativeCSharpGhostText
import io.github.dotnetsupport.lang.NativeCSharpSelection
import io.github.dotnetsupport.lang.NativeCSharpSyntaxModel
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * `CSharpFeature.EDITING` (CSHARP_PSI_MIGRATION.md, step 9): Extend Selection, Complete Statement and the gray `;` by the tokens (ROSLYN,
 * today's path) and by the tree (NATIVE). Where both answer the same the cases say so once; where the tree answers better the pair
 * (tokens, tree) is written out.
 */
class CSharpEditingTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            settings.state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun use(source: CSharpFeatureSource) = settings.setSource(CSharpFeature.EDITING, source)

    // ---- Extend Selection

    private val order = """
        class Order
        {
            /// <summary>Saves.</summary>
            [Obsolete]
            public void Save(int count, string name)
            {
                var total = Compute(count, name.Trim()) + 1; // tail
                if (total > 0)
                {
                    Log(${'$'}"total {total:N2} of {name}", "plain text here");
                }
            }
        }
    """.trimIndent()

    /** What Ctrl+W selects, pressed [times] times with the caret at [anchor] of [text]. */
    private fun selections(source: CSharpFeatureSource, anchor: String, times: Int, text: String = order): List<String> {
        use(source)
        myFixture.configureByText("Select${files++}.cs", text)
        myFixture.editor.caretModel.moveToOffset(text.indexOf(anchor))
        return (1..times).map {
            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_SELECT_WORD_AT_CARET)
            myFixture.editor.selectionModel.selectedText.orEmpty()
        }.distinct()
    }

    fun testExtendSelectionGoesThroughTheNodes() {
        settings.state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
        assertEquals(
            listOf(
                "name", "name.Trim", "name.Trim()", "count, name.Trim()", "(count, name.Trim())", "Compute(count, name.Trim())", "Compute(count, name.Trim()) + 1",
                "= Compute(count, name.Trim()) + 1", "total = Compute(count, name.Trim()) + 1", "var total = Compute(count, name.Trim()) + 1",
                "var total = Compute(count, name.Trim()) + 1;",
            ),
            selections(CSharpFeatureSource.NATIVE, "name.Trim", 11),
        )
        // the tokens: the word, then brackets only, straight to the contents of the block with its line breaks
        assertEquals(listOf("name", "count, name.Trim()", "(count, name.Trim())"), selections(CSharpFeatureSource.ROSLYN, "name.Trim", 3))
        val block = selections(CSharpFeatureSource.NATIVE, "name.Trim", 16).drop(11)
        assertEquals("the statements of the block, comments included, without the line breaks around them",
            order.substring(order.indexOf("var total"), order.indexOf("}\n    }") + 1), block[0])
        assertTrue(block[1].startsWith("{") && block[1].endsWith("}"))
        assertTrue("the member: attributes, then with its doc comment", block[2].startsWith("[Obsolete]") && block[2].endsWith("}"))
        assertTrue(block[3].startsWith("/// <summary>"))
    }

    fun testExtendSelectionInStrings() {
        settings.state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
        assertEquals(
            listOf("plain", "plain text here", "\"plain text here\""),
            selections(CSharpFeatureSource.NATIVE, "ain text", 3),
        )
        assertEquals("the tokens: the literal with its quotes at once", listOf("plain", "\"plain text here\""), selections(CSharpFeatureSource.ROSLYN, "ain text", 2))
        assertEquals(
            listOf("total", "total ", "total {total:N2} of {name}", "\$\"total {total:N2} of {name}\""),
            selections(CSharpFeatureSource.NATIVE, "otal {total", 4),
        )
        assertEquals(listOf("total", "total ", "\$\"total {total:N2} of {name}\""), selections(CSharpFeatureSource.ROSLYN, "otal {total", 3))
        // a hole: the expression, the hole with its braces
        assertEquals(listOf("name", "{name}"), selections(CSharpFeatureSource.NATIVE, "name}\"", 2))
    }

    fun testExtendSelectionOfAConditionAndAStatement() {
        assertEquals(listOf(">", "total > 0", "(total > 0)"), selections(CSharpFeatureSource.ROSLYN, "> 0", 3))
        val native = selections(CSharpFeatureSource.NATIVE, "> 0", 4)
        assertEquals(listOf(">", "total > 0", "(total > 0)"), native.take(3))
        assertTrue("the whole if statement", native[3].startsWith("if (total > 0)") && native[3].endsWith("}"))
    }

    /** Where both answer, the ranges of the tokens (brackets, strings, comments) are among those of the tree, trimmed of the line breaks. */
    fun testTheTreeKeepsTheRangesOfTheTokens() {
        val file = NativeCSharpSyntaxModel.parse(order)
        var compared = 0
        for (offset in order.indices) {
            val native = NativeCSharpSelection.ranges(file, offset).toSet()
            for (range in CSharpSelection.ranges(order, offset)) {
                val text = range.substring(order)
                val start = range.startOffset + (text.length - text.trimStart().length)
                val end = range.endOffset - (text.length - text.trimEnd().length)
                if (start >= end || offset < start || offset > end) continue // a range away from the caret is never taken
                compared++
                assertTrue("offset $offset: ${order.substring(start, end)} [$start,$end] in $native", native.any { it.startOffset == start && it.endOffset == end })
            }
        }
        assertTrue(compared > 200)
    }

    // ---- Complete Statement

    private fun complete(source: CSharpFeatureSource, before: String): String {
        use(source)
        myFixture.configureByText("Complete${files++}.cs", before)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_COMPLETE_STATEMENT)
        val text = myFixture.editor.document.text
        val caret = myFixture.editor.caretModel.offset
        return text.substring(0, caret) + "<caret>" + text.substring(caret)
    }

    private fun method(body: String) = "class A\n{\n    void M()\n    {\n        $body\n    }\n}\n"

    /** The same result by the tokens and by the tree. */
    private fun agreed(before: String): String {
        val tokens = complete(CSharpFeatureSource.ROSLYN, before)
        assertEquals("the tokens and the tree disagree on $before", tokens, complete(CSharpFeatureSource.NATIVE, before))
        return tokens
    }

    /** The cases of `CSharpEditorAssistTest.testCompleteStatementAddsSemicolonOnlyWhereSafe`, in the editor, where both agree. */
    fun testCompleteStatementAgreesOnTheSafeCases() {
        for (statement in listOf("x = 1", "items[i] = value", "count += 1", "var y = Make(a, b)", "obj.Method()", "return result", "throw ex", "break")) {
            assertEquals(method("$statement;\n        <caret>").replace("\n        <caret>\n", "\n        <caret>\n"), agreed(method("$statement<caret>")))
        }
        // already terminated; a trailing comment stays where it is
        assertEquals(method("x = 1;\n        <caret>"), agreed(method("x = 1;<caret>")))
        assertEquals(method("x = 1; // c\n        <caret>"), agreed(method("x = 1<caret> // c")))
        // a label; something missing that is not a closer: the tree gives up, the tokens answer
        agreed("class A\n{\n    void M(int x)\n    {\n        switch (x)\n        {\n            case 1:<caret>\n        }\n    }\n}\n")
        agreed(method("Make(a,<caret>"))
        agreed(method("x = <caret>"))
        // a `}` gone missing: the tree has given the braces to the wrong owners
        agreed(method("var l = new List<int> { 1, 2<caret>"))
        // an unterminated string: a `)` after it would be inside it
        agreed(method("Log(\"abc<caret>"))
    }

    /** (tokens, tree): where the tree knows what the statement lacks. */
    fun testCompleteStatementWhereTheTreeKnowsBetter() {
        settings.state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
        fun pair(before: String) = complete(CSharpFeatureSource.ROSLYN, before) to complete(CSharpFeatureSource.NATIVE, before)

        assertEquals(method("Foo(a, b\n            <caret>") to method("Foo(a, b);\n        <caret>"), pair(method("Foo(a, b<caret>")))
        assertEquals(method("return Foo(Bar(1\n            <caret>") to method("return Foo(Bar(1));\n        <caret>"), pair(method("return Foo(Bar(1<caret>")))
        // a statement over two lines: completed at its end, not split in the middle
        assertEquals(
            method("Make(a,\n            <caret>\n             b)") to method("Make(a,\n             b);\n        <caret>"),
            pair(method("Make(a,<caret>\n             b)")),
        )
        // headers get a block, in the brace style around them
        assertEquals(method("if (x)\n            <caret>") to method("if (x)\n        {\n            <caret>\n        }"), pair(method("if (x)<caret>")))
        assertEquals(method("if (x\n            <caret>") to method("if (x)\n        {\n            <caret>\n        }"), pair(method("if (x<caret>")))
        assertEquals(method("while (Ready(\n            <caret>") to method("while (Ready())\n        {\n            <caret>\n        }"), pair(method("while (Ready(<caret>")))
        assertEquals(method("foreach (var a in b)\n        {\n            <caret>\n        }"), pair(method("foreach (var a in b)<caret>")).second)
        assertEquals("class A {\n    void M() {\n        if (x) {\n            <caret>\n        }\n    }\n}\n", pair("class A {\n    void M() {\n        if (x)<caret>\n    }\n}\n").second)
        // the statement of a header on the same line
        assertEquals(method("if (x) Foo();\n        <caret>"), pair(method("if (x) Foo(<caret>")).second)
        // an existing block takes the caret
        assertEquals(method("if (x)\n        {\n            <caret>\n            Foo();\n        }"), pair(method("if (x)<caret>\n        {\n            Foo();\n        }")).second)
        // declarations
        fun member(text: String) = "class A\n{\n    $text\n}\n"
        assertEquals(member("public int X\n        <caret>") to member("public int X;\n    <caret>"), pair(member("public int X<caret>")))
        assertEquals(member("void N()\n    {\n        <caret>\n    }"), pair(member("void N()<caret>")).second)
        assertEquals(member("abstract void Q();\n    <caret>"), pair(member("abstract void Q()<caret>")).second)
        assertEquals("interface I\n{\n    void N();\n    <caret>\n}\n", pair("interface I\n{\n    void N()<caret>\n}\n").second)
        assertEquals(member("class B\n    {\n        <caret>\n    }"), pair(member("class B<caret>")).second)
        assertEquals(member("public int P => 1;\n    <caret>"), pair(member("public int P => 1<caret>")).second)
    }

    /** The pure decision of the tokens against the tree, on the lines of `testCompleteStatementAddsSemicolonOnlyWhereSafe`. */
    fun testThePureRulesSideBySide() {
        fun tree(statement: String): String? {
            val text = method(statement)
            val caret = text.indexOf(statement) + statement.length
            val plan = NativeCSharpCompleteStatement.plan(text, caret) ?: return null
            return plan.applyTo(text).substring(text.indexOf(statement)).substringBefore("\n")
        }
        for (statement in listOf("x = 1", "items[i] = value", "count += 1", "var y = Make(a, b)", "obj.Method()", "return result", "throw ex", "break")) {
            assertTrue(CSharpCompleteStatement.needsSemicolon(statement))
            assertEquals("$statement;", tree(statement))
        }
        assertEquals("already terminated: nothing to add", "x = 1;", tree("x = 1;"))
        assertNull("the tree gives up on an argument still to come", tree("Make(a,"))
        // (tokens, tree): a bare comparison is a statement that lacks its `;` to the parser, as to Roslyn
        assertFalse(CSharpCompleteStatement.needsSemicolon("x == y"))
        assertEquals("x == y;", tree("x == y"))
    }

    // ---- The gray `;`

    private fun ghost(text: String, native: Boolean): String? {
        val offset = text.indexOf('|')
        val clean = text.removeRange(offset, offset + 1)
        val context = if (!native) CSharpGhostText.Context() else object : CSharpGhostText.Context() {
            override fun tree(text: CharSequence): CSharpFile = NativeCSharpSyntaxModel.parse(text)
        }
        return CSharpGhostText.semicolon(clean, offset, context)
    }

    private fun inMethod(body: String) = "class Order\n{\n    void M()\n    {\n        $body\n    }\n}\n"

    fun testTheGraySemicolonOnBothPaths() {
        // the cases of GhostTextTest, the same on both paths
        for ((case, expected) in listOf("total = 1|" to ";", "Save()|" to ";", "if (ready)|" to null, "total = 1;|" to null, "total = 1| + 2" to null, "Save(\"a\")|" to ";", "items[0] = 1|" to ";")) {
            assertEquals(case, expected, ghost(inMethod(case), native = false))
            assertEquals(case, expected, ghost(inMethod(case), native = true))
        }
        // (tokens, tree): a statement over lines ends here
        assertEquals(null to ";", ghost(inMethod("Make(a,\n             b)|"), false) to ghost(inMethod("Make(a,\n             b)|"), true))
        assertEquals(null to ";", ghost(inMethod("var adults = people\n            .Where(p => p.Age > 18)|"), false) to ghost(inMethod("var adults = people\n            .Where(p => p.Age > 18)|"), true))
        // (tokens, tree): the line looks done, the statement is not: the call of the line above lacks its `)` first
        val open = inMethod("Foo(a,\n            Bar()|")
        assertEquals(";" to null, ghost(open, false) to ghost(open, true))
    }

    // ---- The scenarios of debug-playground/Console/Editor: the text typed where the marker says, in the file as it is

    private fun playground(name: String) = java.io.File("debug-playground/Console/Editor/$name").readText().replace("\r\n", "\n")

    /** [typed] on the empty line under the comment of [marker], Complete Statement by [source]: the text from the typed line on. */
    private fun scenario(marker: String, typed: String, source: CSharpFeatureSource): String {
        val text = playground("CompleteStatement.cs")
        val comment = text.indexOf("// TYPE:$marker ")
        val blank = Regex("""\n([ \t]*)\n""").find(text, comment)!!
        val indent = text.substring(text.lastIndexOf('\n', comment) + 1, comment)
        val before = text.substring(0, blank.range.first + 1) + indent + typed + "<caret>" + text.substring(blank.range.last)
        val after = complete(source, before)
        return after.substring(blank.range.first + 1).substringBefore("\n    }\n").trimEnd()
    }

    fun testThePlaygroundScenariosOfCompleteStatement() {
        settings.state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
        val n = CSharpFeatureSource.NATIVE
        val t = CSharpFeatureSource.ROSLYN
        val i = "        "
        assertEquals("${i}Make(a, b);\n$i<caret>", scenario("complete-call", "Make(a, b", n))
        assertEquals("${i}Make(a, b\n$i    <caret>", scenario("complete-call", "Make(a, b", t))
        assertEquals("${i}_count = Make(Make(1, 2), b);\n$i<caret>", scenario("complete-nested", "_count = Make(Make(1, 2), b", n))
        assertEquals("${i}Make(a, \n$i    <caret>", scenario("complete-unfinished", "Make(a, ", n))
        assertEquals("${i}if (ready)\n$i{\n$i    <caret>\n$i}", scenario("complete-if", "if (ready", n))
        assertEquals("${i}foreach (var item in items)\n$i{\n$i    <caret>\n$i}", scenario("complete-foreach", "foreach (var item in items)", n))
        assertEquals("${i}while (Ready())\n$i{\n$i    <caret>\n$i}", scenario("complete-while", "while (Ready(", n))
        assertEquals("${i}if (ready) Make(1, 2);\n$i<caret>", scenario("complete-if-same-line", "if (ready) Make(1, 2", n))
        // (the text is cut before the first `    }`: the one of the new body)
        assertEquals("    public void Run()\n    {\n        <caret>", scenario("complete-method", "public void Run()", n))
        assertEquals("    public int Total;\n    <caret>", scenario("complete-method", "public int Total", n).take(33))
        // the statement over two lines: the caret at the end of `Make(a,`
        val text = playground("CompleteStatement.cs")
        val at = text.indexOf("Make(a,\n") + "Make(a,".length
        val after = complete(n, text.substring(0, at) + "<caret>" + text.substring(at))
        val expected = "Make(a,\n             b);\n$i<caret>\n"
        assertEquals(expected, after.substring(after.indexOf("Make(a,\n")).take(expected.length))
    }

    fun testThePlaygroundScenariosOfExtendSelection() {
        val text = playground("ExtendSelection.cs")
        val native = selections(CSharpFeatureSource.NATIVE, "name.Trim()) + 1", 16, text)
        assertEquals(listOf("name", "name.Trim", "name.Trim()", "count, name.Trim()", "(count, name.Trim())"), native.take(5))
        assertEquals("var total = Compute(count, name.Trim()) + 1;", native[10])
        assertTrue("the statement with the comment above it", native[11].startsWith("// TYPE:extend-selection-call") && native[11].endsWith("+ 1;"))
        assertTrue("the contents of the body", native[12].startsWith("// TYPE:extend-selection-call") && native[12].endsWith("return total;"))
        assertTrue(native[13].startsWith("{") && native[14].startsWith("[Obsolete") && native[15].startsWith("/// <summary>Saves"))
        assertEquals(listOf("plain", "plain text here", "\"plain text here\""), selections(CSharpFeatureSource.NATIVE, "lain text here\");", 3, text))
        assertEquals(listOf(">", "total > 1", "(total > 1)"), selections(CSharpFeatureSource.NATIVE, "> 1)\n", 4, text).take(3))
    }

    // ---- Broken code, the switch

    fun testBrokenCodeOnEveryOffset() {
        val texts = listOf(
            "class A\n{\n    void M(\n    {\n        if (x\n        Foo(a, \"b\n        var l = new List<int> { 1,\n    }\n    public int P { get; set;\n",
            "namespace N\nclass { void ( { } } } )) ;; \$\"{ {x\n /* open",
            "using System;\nclass B : { int[] a = [1, 2; void M() => Foo(; record R(int A\n",
        )
        for (text in texts) {
            val file = NativeCSharpSyntaxModel.parse(text)
            for (offset in 0..text.length) {
                NativeCSharpSelection.ranges(file, offset)
                NativeCSharpGhostText.needsSemicolon(file, offset)
                val plan = NativeCSharpCompleteStatement.plan(file, offset, "    ") ?: continue
                val after = plan.applyTo(text)
                assertTrue("offset $offset", plan.caretAfter() in 0..after.length)
            }
        }
    }

    fun testTheSwitch() {
        settings.state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
        assertTrue(CSharpFeatures.hasNative(CSharpFeature.EDITING))
        assertFalse("no index needed", CSharpFeature.EDITING.needsIndexes)
        assertEquals("the tree by default since the robot (0.1.48)", CSharpFeatureSource.NATIVE, CSharpFeature.EDITING.defaultSource)
        assertTrue(CSharpFeatures.native(CSharpFeature.EDITING, project))
        assertFalse("ROSLYN: the tokens", "name.Trim()" in selections(CSharpFeatureSource.ROSLYN, "name.Trim", 4))
        assertTrue("NATIVE: the tree", "name.Trim()" in selections(CSharpFeatureSource.NATIVE, "name.Trim", 4))
        assertTrue(CSharpFeatures.native(CSharpFeature.EDITING, project))
        // a file of the heuristic tree has nothing to give: the tokens answer even with NATIVE
        settings.setSource(CSharpFeature.SYNTAX_TREE, CSharpFeatureSource.ROSLYN)
        assertFalse("name.Trim()" in selections(CSharpFeatureSource.NATIVE, "name.Trim", 4))
        assertTrue(myFixture.file is HeuristicCSharpFile)
        settings.setSource(CSharpFeature.SYNTAX_TREE, CSharpFeatureSource.NATIVE)
        // no server: what is built in works
        settings.state.enabled = false
        use(CSharpFeatureSource.ROSLYN)
        assertTrue(CSharpFeatures.native(CSharpFeature.EDITING, project))
    }
}
