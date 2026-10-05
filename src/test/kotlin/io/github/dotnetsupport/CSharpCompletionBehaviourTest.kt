package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupFocusDegree
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.lang.CSharpCompletionAutoPopup
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpLookupDocumentationTargetProvider
import io.github.dotnetsupport.lang.CSharpSuggestionMode
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.NativeCSharpDocumentationTarget
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.suggest.SuggestionStats

/**
 * How the C# list behaves (0.1.91, COMPLETION_GAPS 2.8–2.14, 3.4, 3.10): statistics of choices among items of one priority, commit
 * characters, suggestion mode where a name is written, the list opening by itself, Quick Doc of an item, type arguments, `nameof(` /
 * `typeof(`, middle matching. The keywords of Roslyn's recommenders: [CSharpKeywordRecommendationsTest].
 */
class CSharpCompletionBehaviourTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        settings.setSource(CSharpFeature.DOCUMENTATION, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            runCatching { myFixture.lookup?.hideLookup(true) }
            settings.state.features = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun lookup(text: String): List<LookupElement> {
        myFixture.configureByText("Behaviour${files++}.cs", text)
        myFixture.completeBasic()
        return myFixture.lookupElements?.toList().orEmpty()
    }

    private fun native(text: String): List<String> = lookup(text).filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.map { it.lookupString }

    /** The list as it opens by itself at `<caret>` (after [typed] is typed there). */
    private fun autoPopup(text: String, typed: String = ""): LookupImpl? {
        myFixture.configureByText("Auto${files++}.cs", text)
        if (typed.isNotEmpty()) myFixture.type(typed)
        CodeCompletionHandlerBase(CompletionType.BASIC, false, true, true).invokeCompletion(project, myFixture.editor, 0)
        UIUtil.dispatchAllInvocationEvents()
        return myFixture.lookup as LookupImpl?
    }

    private fun assertOrder(list: List<String>, vararg names: String) {
        val positions = names.map { name -> list.indexOf(name).also { assertTrue("$name in $list", it >= 0) } }
        assertEquals("the order of ${names.toList()} in $list", positions.sorted(), positions)
    }

    // ---- 2.8 statistics

    fun testChosenBeforeGoesUpAmongItsKindOnly() {
        val stats = SuggestionStats.getInstance()
        val saved = stats.state
        try {
            stats.loadState(SuggestionStats.Data())
            val text = "class Stats { int alpha; int zeta; void Beta() { } void Omega() { } void M() { int local = 0; <caret> } }"
            val before = native(text)
            assertOrder(before, "local", "alpha", "zeta")
            assertOrder(before, "Beta", "Omega")
            myFixture.lookup?.hideLookup(true)
            repeat(6) { stats.completionAccepted(0, emptySet(), "zeta") }
            repeat(3) { stats.completionAccepted(0, emptySet(), "Omega") }
            repeat(9) { stats.completionAccepted(0, emptySet(), "while") }
            val after = native(text)
            // within a kind the used one goes first; the kinds keep Rider's order: locals, fields, methods, types, keywords
            assertOrder(after, "local", "zeta", "alpha", "Omega", "Beta", "Stats", "while")
            assertOrder(after, "Stats", "if")
        } finally {
            stats.loadState(saved)
        }
    }

    // ---- 2.9 commit characters

    fun testCommitCharactersChooseTheItemAndAreTyped() {
        for ((char, expected) in listOf(';' to "var x = counter;", '.' to "var x = counter.", ',' to "Use(counter,", ')' to "Use(counter)", '[' to "var x = counter[", ' ' to "var x = counter ", '=' to "counter=")) {
            val code = when (char) {
                ',', ')' -> "class C { int counter; void Use(int a, int b) { } void M() { Use(cou<caret> } }"
                '=' -> "class C { int counter; void M() { cou<caret> } }"
                else -> "class C { int counter; void M() { var x = cou<caret> } }"
            }
            val lookup = autoPopup(code)
            assertNotNull("a list for `$char`", lookup)
            assertEquals("cou selects counter", "counter", lookup!!.currentItem?.lookupString)
            myFixture.type(char)
            assertTrue("`$char`: ${myFixture.editor.document.text}", myFixture.editor.document.text.contains(expected))
            myFixture.lookup?.hideLookup(true)
        }
    }

    fun testNoCommitWithNothingTypedOrByAMiddleMatch() {
        // after `.` nothing is typed: a second `.` is just typed
        val lookup = autoPopup("class C { int counter; void M() { var x = <caret> } }")
        if (lookup != null) {
            myFixture.type(';')
            assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("var x = ;"))
        }
        myFixture.lookup?.hideLookup(true)
        // `unt` finds `counter` in the middle only: `;` does not choose it
        val middle = autoPopup("class C { int counter; void M() { var x = unt<caret> } }")
        assertNotNull(middle)
        assertEquals("counter", middle!!.currentItem?.lookupString)
        myFixture.type(';')
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("var x = unt;"))
    }

    fun testATemplateOrAWholeWordIsNotCommittedByACharacter() {
        // robot 0.1.91: `foreach (` expanded the live template `foreach`, `if ` the template `if`
        for (word in listOf("foreach", "if")) {
            val lookup = autoPopup("class C { int[] items; void M() { $word<caret> } }")
            if (lookup != null) myFixture.type('(')
            else myFixture.type("(")
            assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("$word("))
            assertFalse(myFixture.editor.document.text, myFixture.editor.document.text.contains("collection"))
            myFixture.lookup?.hideLookup(true)
        }
    }

    // ---- 2.12 suggestion mode

    fun testNamePlaces() {
        fun at(code: String): Boolean {
            val dummy = code.replace("<caret>", "IntellijIdeaRulezzz ")
            val file = myFixture.configureByText("Names${files++}.cs", dummy) as CSharpFile
            val leaf = file.findElementAt(code.indexOf("<caret>"))!!
            return CSharpSuggestionMode.isNamePlace(leaf)
        }
        val names = listOf(
            "class A { void M() { foreach (var <caret> in new int[0]) { } } }",
            "class A { void M(string s) { int.TryParse(s, out var <caret>); } }",
            "class A { void M() { var <caret> } }",
            "class Order { } class A { void M() { Order <caret> } }",
            "class A { void M(object o) { if (o is string <caret>) { } } }",
            "class A { void M(int <caret>) { } }",
            "class A { int <caret> }",
            "class A { void M() { try { } catch (System.Exception <caret>) { } } }",
            "class A { void M(int[] xs) { var q = from <caret> in xs select 1; } }",
            "class A { void M(System.Func<int, int> f) { M(x => x); M(<caret> => 1); } }",
            "class A { void Run(System.Func<int, bool> f) { } void M() { Run(<caret> } }",
            "class A { void Run(int a, System.Action<int> f) { } void M() { Run(1, <caret> } }",
            "class <caret> { }",
        )
        for (code in names) assertTrue("a name: $code", at(code))
        val values = listOf(
            "class A { void M() { <caret> } }",
            "class A { int count; void M() { var x = <caret> } }",
            "class A { void Use(int a) { } void M() { Use(<caret> } }",
            "class A { void M() { return <caret> } }",
            "class A { <caret> }",
            "class A { public override <caret> }",
            "class A { void M() { yield <caret> } }",
            "class A { void M(A a) { a.<caret> } }",
        )
        for (code in values) assertFalse("a value: $code", at(code))
    }

    fun testListAtANameIsOnlyASuggestion() {
        val lookup = autoPopup("class Order { } class A { void M() { Order or<caret> } }")
        assertNotNull("names after the type", lookup)
        assertEquals(LookupFocusDegree.UNFOCUSED, lookup!!.lookupFocusDegree)
        // space and the commit characters are typed, not the name `order`
        myFixture.type(' ')
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("Order or "))
        myFixture.lookup?.hideLookup(true)
        // in an expression the list is focused as usual
        val value = autoPopup("class A { int order; void M() { var x = or<caret> } }")
        assertNotNull(value)
        assertNotSame(LookupFocusDegree.UNFOCUSED, value!!.lookupFocusDegree)
    }

    fun testLambdaParameterIsASuggestion() {
        val lookup = autoPopup("class A { int item; void Run(System.Func<int, bool> f) { } void M() { Run(it<caret> } }")
        assertNotNull(lookup)
        assertEquals(LookupFocusDegree.UNFOCUSED, lookup!!.lookupFocusDegree)
        myFixture.type(' ')
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("Run(it "))
    }

    // ---- 2.10 the list opens by itself

    fun testWhereTheListOpensByItself() {
        fun opens(line: String): Boolean = CSharpCompletionAutoPopup.triggers(line, line.length)
        for (line in listOf("#", "    #", "var l = new List<", "    [", "    [Obsolete][", "Run(", "Run(1,", "var o = new ", "case ", "if (s == ", "if (s != ", "x is ", "using ", "public override ")) {
            assertTrue("opens after `$line`", opens(line))
        }
        for (line in listOf("x #", "a < ", "if (", "x = ", "var s = \"a (", "// Run(", "foo.new ", "x  ", "{", "a[", "return ")) {
            assertFalse("no list after `$line`", opens(line))
        }
    }

    fun testTypesOpenByThemselvesAfterAngleBracket() {
        val lookup = autoPopup("class Order { } class A { void M() { var l = new System.Collections.Generic.List<caret> } }", "<")
        assertNotNull("a list after `<`", lookup)
        assertTrue(lookup!!.items.map { it.lookupString }.containsAll(listOf("Order", "int", "string")))
    }

    fun testLambdaPlaceOpensByItselfWithTheNames() {
        val lookup = autoPopup("class A { int item; void Run(System.Func<int, bool> f) { } void M() { Run<caret> } }", "(")
        assertNotNull("a list where a lambda may go", lookup)
        assertEquals(LookupFocusDegree.UNFOCUSED, lookup!!.lookupFocusDegree)
        assertTrue(lookup.items.map { it.lookupString }.contains("item"))
    }

    fun testValuesOpenByThemselvesAfterComparison() {
        val lookup = autoPopup("enum Status { Open } class A { Status status; void M() { if (status == <caret>) { } } }")
        assertNotNull("a list after `== `", lookup)
        assertTrue(lookup!!.items.map { it.lookupString }.contains("status"))
    }

    // ---- 2.11 Quick Doc of an item

    fun testQuickDocOfAnItem() {
        myFixture.configureByText("Doc${files++}.cs", """
            class Basket
            {
                /// <summary>The number of items.</summary>
                public int Count;

                /// <summary>Adds an item.</summary>
                public void Add(int item) { }
            }
            /// <summary>A shop order.</summary>
            class Order<T> { }
            class A { void M(Basket basket) { basket.<caret> } }
        """.trimIndent())
        val file = myFixture.file
        val offset = myFixture.caretOffset
        val provider = CSharpLookupDocumentationTargetProvider()
        fun doc(element: LookupElement, at: Int = offset): String? =
            (provider.documentationTarget(file, element, at) as? NativeCSharpDocumentationTarget)?.doc?.html
        assertTrue(doc(LookupElementBuilder.create("Count")).orEmpty(), doc(LookupElementBuilder.create("Count")).orEmpty().contains("The number of items."))
        assertTrue(doc(LookupElementBuilder.create("Add")).orEmpty().contains("Adds an item."))
        assertNull("a keyword", doc(LookupElementBuilder.create("while").bold()))
        // a generic type of the solution, as the native list shows it
        myFixture.configureByText("Doc${files++}.cs", "/// <summary>A shop order.</summary>\nclass Order<T> { }\nclass B { void M() { var o = new Ord<caret> } }")
        val generic = provider.documentationTarget(myFixture.file, LookupElementBuilder.create("Order").withPresentableText("Order<>"), myFixture.caretOffset)
        val html = (generic as? NativeCSharpDocumentationTarget)?.doc?.html.orEmpty()
        assertTrue(html, html.contains("A shop order."))
    }

    // ---- 2.13 type arguments

    fun testTypeArguments() {
        for (code in listOf(
            "using System.Collections.Generic; class Order { } class A { void M() { var l = new List<<caret> } }",
            "using System.Collections.Generic; class Order { } class A { void M() { var d = new Dictionary<string, <caret> } }",
            "using System.Collections.Generic; class Order { } class A { IEnumerable<<caret> M() => null; }",
        )) {
            val list = native(code)
            assertTrue("$code: $list", list.containsAll(listOf("Order", "int", "string", "object")))
            assertFalse("$code: $list", list.contains("if"))
            myFixture.lookup?.hideLookup(true)
        }
    }

    // ---- 3.4 nameof / typeof

    fun testNameofAndTypeof() {
        val nameof = native("class Order { } class A { int _count; void M(int item) { var n = nameof(<caret> } }")
        assertOrder(nameof, "item", "_count", "M", "Order")
        for (keyword in listOf("int", "out", "ref", "new", "this", "if")) assertFalse(keyword, keyword in nameof)
        myFixture.lookup?.hideLookup(true)
        val types = native("class Order { } class A { int _count; void M(int item) { var t = typeof(<caret> } }")
        assertTrue(types.containsAll(listOf("Order", "int", "string")))
        for (name in listOf("item", "_count", "M", "dynamic", "new", "this")) assertFalse(name, name in types)
    }

    // ---- 3.10 middle matching

    fun testMiddleMatchingUnderStartMatches() {
        val list = lookup("class A { int writer; void WriteLine() { } void M() { int prewrite = 0; wri<caret> } }").map { it.lookupString }
        assertOrder(list, "writer", "WriteLine", "prewrite")
        myFixture.lookup?.hideLookup(true)
        assertTrue(lookup("class A { void WriteLine() { } void M() { rite<caret> } }").map { it.lookupString }.contains("WriteLine"))
    }
}
