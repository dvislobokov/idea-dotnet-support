package io.github.dotnetsupport

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.psi.SyntaxTraverser
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.rename.RenameHandlerRegistry
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpRename
import io.github.dotnetsupport.lang.NativeCSharpRenameHandler
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * RENAME on the native tree (CSHARP_PSI_MIGRATION.md, step 9, task A5): Shift+F6 of locals, parameters, local functions, labels, range
 * variables and type parameters by the one resolver, conflicts, `@` before keywords; members and types across the solution (C4b).
 */
class CSharpRenameNativeTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.RENAME, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            settings.state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** [before] with `<caret>` renamed to [newName] by the handler Shift+F6 finds; the text after. */
    private fun rename(before: String, newName: String): String {
        myFixture.configureByText("Rename${files++}.cs", before.trimIndent())
        val handler = RenameHandlerRegistry.getInstance().getRenameHandler((myFixture.editor as com.intellij.openapi.editor.ex.EditorEx).dataContext)
        assertTrue("the native handler answers: $handler", handler is NativeCSharpRenameHandler)
        myFixture.renameElementAtCaretUsingHandler(newName)
        return myFixture.editor.document.text
    }

    private fun check(before: String, newName: String, after: String) = assertEquals(after.trimIndent(), rename(before, newName))

    private fun conflicts(before: String, newName: String): List<String> = try {
        rename(before, newName)
        fail("conflicts expected")
        emptyList()
    } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
        e.messages.toList()
    }

    private fun refusal(before: String, newName: String = "renamed"): String = try {
        rename(before, newName)
        fail("a hint expected")
        ""
    } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
        e.message!!
    }

    // ---- every kind of symbol

    fun testLocalWithUseBeforeAndAfterAndInStrings() = check(
        """
        class R { void M() { var <caret>total = 1; total++; System.Console.Write(${'$'}"{total} total" + nameof(total)); } }
        """,
        "sum",
        """
        class R { void M() { var sum = 1; sum++; System.Console.Write(${'$'}"{sum} total" + nameof(sum)); } }
        """,
    )

    fun testOtherMethodsKeepTheirLocals() = check(
        "class R { void A() { int x<caret> = 1; x = x + 1; } void B() { int x = 2; B(); } int x; }",
        "y",
        "class R { void A() { int y = 1; y = y + 1; } void B() { int x = 2; B(); } int x; }",
    )

    fun testPatternOutVarDeconstructionForeachCatch() = check(
        """
        class R
        {
            void M(object o, int[] xs)
            {
                if (o is string te<caret>xt && text.Length > 0) System.Console.Write(text);
                if (int.TryParse("1", out var n)) n++;
                var (a, b) = (1, 2);
                foreach (var x in xs) System.Console.Write(x);
                try { } catch (System.Exception e) { System.Console.Write(e); }
            }
        }
        """,
        "s",
        """
        class R
        {
            void M(object o, int[] xs)
            {
                if (o is string s && s.Length > 0) System.Console.Write(s);
                if (int.TryParse("1", out var n)) n++;
                var (a, b) = (1, 2);
                foreach (var x in xs) System.Console.Write(x);
                try { } catch (System.Exception e) { System.Console.Write(e); }
            }
        }
        """,
    )

    fun testParameterWithDocCommentAndNamedArgumentOfTheSameType() = check(
        """
        class R
        {
            /// <summary>Uses <paramref name="count"/>.</summary>
            /// <param name="count">How many.</param>
            int Twice(int cou<caret>nt) => count * 2;
            int Call() => Twice(count: 3) + this.Twice(count: 1);
        }
        """,
        "times",
        """
        class R
        {
            /// <summary>Uses <paramref name="times"/>.</summary>
            /// <param name="times">How many.</param>
            int Twice(int times) => times * 2;
            int Call() => Twice(times: 3) + this.Twice(times: 1);
        }
        """,
    )

    fun testParameterUsedAsNamedArgumentElsewhereIsTheServers() {
        myFixture.addFileToProject("RenameOther.cs", "class Caller { void C(R r) { r.Twice(count: 1); } }")
        val hint = refusal("class R { public int Twice(int cou<caret>nt) => count * 2; }")
        assertTrue(hint, hint.contains("named argument"))
    }

    fun testLambdaAndAnonymousMethodParameters() = check(
        """
        using System;
        class R
        {
            int x;
            void M()
            {
                Func<int, int> f = <caret>x => x + 1;
                Func<int, int, int> g = (x, y) => x + y;
                Func<int, int> h = delegate (int x) { return x; };
            }
        }
        """,
        "v",
        """
        using System;
        class R
        {
            int x;
            void M()
            {
                Func<int, int> f = v => v + 1;
                Func<int, int, int> g = (x, y) => x + y;
                Func<int, int> h = delegate (int x) { return x; };
            }
        }
        """,
    )

    fun testLocalFunctionUsedBeforeItsDeclarationAndItsNamedParameter() {
        check(
            "class R { int M() { return Twi<caret>ce(n: 2) + Twice(3); int Twice(int n) => n * 2; } }",
            "Double",
            "class R { int M() { return Double(n: 2) + Double(3); int Double(int n) => n * 2; } }",
        )
        check(
            "class R { int M() { return Twice(n: 2); int Twice(int <caret>n) => n * 2; } void Other(int n) => Other(n: 1); }",
            "k",
            "class R { int M() { return Twice(k: 2); int Twice(int k) => k * 2; } void Other(int n) => Other(n: 1); }",
        )
    }

    fun testPrimaryConstructorParameter() = check(
        """
        /// <param name="seed">The start.</param>
        class R(int se<caret>ed)
        {
            int Next() => seed + 1;
            class Nested { int seed; int Get() => seed; }
        }
        """,
        "start",
        """
        /// <param name="start">The start.</param>
        class R(int start)
        {
            int Next() => start + 1;
            class Nested { int seed; int Get() => seed; }
        }
        """,
    )

    fun testRecordPositionalParameterIsAProperty() {
        val hint = refusal("record Point(int <caret>X, int Y) { int Sum => X + Y; }")
        assertTrue(hint, hint.contains("not a local symbol"))
    }

    fun testLabelsAndQueries() {
        check(
            "class R { void M() { again: if (true) goto ag<caret>ain; } void N() { again: goto again; } }",
            "retry",
            "class R { void M() { retry: if (true) goto retry; } void N() { again: goto again; } }",
        )
        check(
            "using System.Linq; class R { object Q(int[] xs) => from <caret>o in xs let d = o * 2 group o by d into g select g.Key; }",
            "item",
            "using System.Linq; class R { object Q(int[] xs) => from item in xs let d = item * 2 group item by d into g select g.Key; }",
        )
    }

    fun testTypeParameters() {
        check(
            """
            class R
            {
                /// <typeparam name="T">The item.</typeparam>
                T Pick<<caret>T>(System.Collections.Generic.List<T> items) where T : class => items[0];
            }
            """,
            "TItem",
            """
            class R
            {
                /// <typeparam name="TItem">The item.</typeparam>
                TItem Pick<TItem>(System.Collections.Generic.List<TItem> items) where TItem : class => items[0];
            }
            """,
        )
        val hint = refusal("partial class Box<<caret>T> { T Value; }")
        assertTrue(hint, hint.contains("partial"))
    }

    // ---- the new name

    fun testKeywordGetsTheAtSign() = check(
        "class R { void M() { var <caret>kind = 1; kind++; } }",
        "class",
        "class R { void M() { var @class = 1; @class++; } }",
    )

    fun testInvalidNameIsRefused() {
        val hint = refusal("class R { void M() { var <caret>kind = 1; } }", "2nd")
        assertTrue(hint, hint.contains("not a valid C# identifier"))
    }

    // ---- conflicts

    fun testCapturingAFieldIsAConflict() {
        val found = conflicts("class R { int count; void M() { var <caret>total = 1; count = total; } }", "count")
        assertTrue(found.toString(), found.single().contains("would refer to the renamed local variable"))
    }

    fun testALambdaParameterWouldCaptureAUse() {
        val found = conflicts("using System; class R { void M() { var <caret>a = 1; Func<int, int> f = b => a + b; } }", "b")
        assertTrue(found.toString(), found.any { it.contains("would refer to another declaration") })
        assertTrue(found.toString(), found.any { it.contains("already declared") })
    }

    fun testTwoLocalsOfOneScopeConflict() {
        val found = conflicts("class R { void M() { var a = 1; var <caret>b = 2; System.Console.Write(a + b); } }", "a")
        assertTrue(found.toString(), found.any { it.contains("A local variable named 'a' is already declared") })
    }

    fun testSiblingBlocksDoNotConflict() = check(
        "class R { void M() { { var a = 1; a++; } { var <caret>b = 2; b++; } } }",
        "a",
        "class R { void M() { { var a = 1; a++; } { var a = 2; a++; } } }",
    )

    fun testAMemberWouldHideThePrimaryConstructorParameter() {
        val found = conflicts("class R(int <caret>seed) { int Total; int Next() => seed; }", "Total")
        assertTrue(found.toString(), found.any { it.contains("would hide the primary constructor parameter") })
    }

    // ---- what is not local

    fun testMembersAndTypesAreRenamedAcrossTheSolution() {
        // the rename of the solution (C4b, CSharpSolutionRenameTest has the rest)
        check("class R { int <caret>Total; int Get() => Total; }", "Sum", "class R { int Sum; int Get() => Sum; }")
        check("class <caret>R { R() { } static R Make() => new R(); }", "Q", "class Q { Q() { } static Q Make() => new Q(); }")
        assertFalse("NATIVE: one handler, the native one", NativeCSharpRename.serverRenames(myFixture.file))
    }

    fun testRoslynLeavesShiftF6ToTheServer() {
        settings.state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
        settings.setSource(CSharpFeature.RENAME, CSharpFeatureSource.ROSLYN)
        myFixture.configureByText("RenameRoslyn.cs", "class R { void M() { var <caret>x = 1; } }")
        val handler = RenameHandlerRegistry.getInstance().getRenameHandler((myFixture.editor as com.intellij.openapi.editor.ex.EditorEx).dataContext)
        assertFalse("not the native one: $handler", handler is NativeCSharpRenameHandler)
        assertTrue(NativeCSharpRename.serverRenames(myFixture.file))
    }

    fun testBrokenCodeDoesNotThrow() {
        val file = myFixture.configureByText("RenameBroken.cs", "class Broken { void M(int a { var x = ; if (x > a { foo(x, ; } int Y => x. ; class { from q in ") as CSharpFile
        for (leaf in SyntaxTraverser.psiTraverser(file).filter { it.firstChild == null }) {
            val decision = NativeCSharpRename.decide(file, leaf.textRange.startOffset)
            if (decision is NativeCSharpRename.Native) NativeCSharpRename.conflicts(decision.target, "renamed")
        }
    }

    // ---- inplace and undo

    fun testUndoRestoresEverythingInOneStep() {
        val before = "class R { void M() { var <caret>total = 1; total++; } }"
        rename(before, "sum")
        undo()
        assertEquals(before.replace("<caret>", ""), myFixture.editor.document.text)
    }

    fun testInplaceRenameFollowsTheTemplate() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.configureByText("RenameInplace.cs", "class R { void M() { var <caret>total = 1; total++; System.Console.Write(total); } }")
        val original = myFixture.editor.document.text
        NativeCSharpRenameHandler().invoke(project, myFixture.editor, myFixture.file, (myFixture.editor as com.intellij.openapi.editor.ex.EditorEx).dataContext)
        val state = TemplateManagerImpl.getTemplateState(myFixture.editor)
        assertNotNull("the inplace template runs", state)
        val range = state!!.currentVariableRange!!
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.replaceString(range.startOffset, range.endOffset, "class") }
        state.gotoEnd(false)
        // the rename itself runs after the template, in a write-safe context
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("class R { void M() { var @class = 1; @class++; System.Console.Write(@class); } }", myFixture.editor.document.text)
        undo()
        assertEquals(original, myFixture.editor.document.text)
    }

    private fun undo() {
        IdeDocumentHistory.getInstance(project)
        UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
    }

    /** The EXPECT of `debug-playground/Console/Editor/Rename.cs` (`TYPE:rename-*`): renamed at each marker, the file says what changes. */
    fun testThePlaygroundScenario() {
        val text = java.io.File("debug-playground/Console/Editor/Rename.cs").readText().replace("\r\n", "\n")
        fun at(anchor: String, newName: String, vararg expected: String) {
            // the first one in code: the comments of the markers quote the code
            val offset = Regex(Regex.escape(anchor)).findAll(text).map { it.range.first }
                .firstOrNull { text.substring(text.lastIndexOf('\n', it) + 1, it).trimStart().let { line -> !line.startsWith("//") } } ?: -1
            assertTrue(anchor, offset >= 0)
            val after = rename(text.substring(0, offset) + "<caret>" + text.substring(offset), newName)
            for (line in expected) assertTrue("$anchor → $newName: $line\n$after", after.contains(line))
        }
        at("subtotal = 0", "sum", "var sum = 0m;", "sum += price;", "return sum * (1 - discount);")
        at("price in prices", "cost", "foreach (var cost in prices) subtotal += cost;")
        at("discount)", "rate", "decimal rate)", "* (1 - rate)", "<param name=\"rate\">", "Limits(decimal discount) => Limit * discount;")
        at("Scale(2)", "Times", "Times(2) + Times(factor: 3)", "int Times(int factor)")
        at("factor * 10", "k", "Scale(k: 3)", "int Scale(int k) => k * 10;")
        at("again;", "retry", "retry:", "goto retry;")
        at("line =>", "text", "text => text.Trim()")
        at("o in numbers", "item", "from item in numbers where item > 1 group item by item % 2 into g select g.Key")
        at("TValue>(", "TItem", "TItem First<TItem>(List<TItem> values)", "<typeparam name=\"TItem\">")
        at("kind =", "class", "var @class = values.Count;", "return @class > 0")
        at("owner}", "customer", "<param name=\"customer\">", "RenameScenarios(string customer)", "{customer}")
        val conflict = try {
            at("subtotal = 0", "prices")
            emptyList()
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            e.messages.toList()
        }
        assertTrue(conflict.toString(), conflict.isNotEmpty())
        // a member: the rename across the solution (C4b)
        at("Limit * discount", "Cap", "public decimal Cap => 100m;", "=> Cap * discount;", "{Cap}")
    }
}
