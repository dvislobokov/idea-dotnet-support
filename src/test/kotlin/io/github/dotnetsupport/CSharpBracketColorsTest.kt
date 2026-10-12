package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpBracketColors
import io.github.dotnetsupport.lang.CSharpColorSettingsPage
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.settings.DotNetSettings

/** Matching brackets by depth ([CSharpBracketColors]): the levels, what is no bracket, generics against comparisons, mismatches, the switch. */
class CSharpBracketColorsTest : BasePlatformTestCase() {
    private var counter = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        DotNetSettings.getInstance().colorizeBrackets = true
    }

    override fun tearDown() {
        try {
            DotNetSettings.getInstance().colorizeBrackets = true
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The colored brackets of [text] in order as `bracket:level` (level 1-based), through the daemon. */
    private fun colored(text: String): String {
        myFixture.configureByText("Brackets${counter++}.cs", text)
        return myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey in CSharpBracketColors.LEVELS }
            .sortedBy { it.startOffset }
            .joinToString(" ") { it.text + ":" + (CSharpBracketColors.LEVELS.indexOf(it.forcedTextAttributesKey) + 1) }
    }

    fun testLevelsByDepthCyclingAfterThree() {
        assertEquals("{:1 (:2 ):2 {:2 (:3 [:1 {:2 (:3 ):3 }:2 ]:1 ):3 }:2 }:1", colored("class C { void M() { var x = ([{ (1) }]); } }"))
    }

    /** The line of `TYPE:brackets-nesting` of the playground: the empty `[ ]` of `new[]` and the `{ }` after them share a depth, `(i + 1)` is one deeper. */
    fun testThePlaygroundNestingLine() {
        assertEquals(
            "{:1 (:2 ):2 {:2 (:3 ):3 (:3 [:1 ]:1 {:1 (:2 ):2 }:1 ):3 (:3 ):3 }:2 }:1",
            colored("class C { object M() { return Enumerable.Range(0, 3).Select(i => new[] { (i + 1) * 2 }).ToArray(); } }"),
        )
    }

    fun testStringsCharactersAndCommentsAreNoBrackets() {
        val text = "class C { string S = \"{[(\"; char Ch = '('; /* ( [ { */ // {\n string V = @\"[\"; string R = \"\"\"\n ( \n \"\"\"; }"
        assertEquals("{:1 }:1", colored(text))
    }

    fun testTheHolesOfInterpolatedStringsAreCode() {
        // the braces of the hole are no brackets, the parentheses inside it are
        assertEquals("{:1 (:2 ):2 {:2 (:3 ):3 }:2 }:1", colored("class C { void M() { var s = \$\"{(1 + 2)}\"; } }"))
    }

    fun testGenericsButNotComparisons() {
        val text = "class C<T> { List<List<int>> F(int a, int b) { if (a < b && b > 1) return new List<List<int>>(); return null; } }"
        assertEquals("<:1 >:1 {:1 <:2 <:3 >:3 >:2 (:2 ):2 {:2 (:3 ):3 <:3 <:1 >:1 >:3 (:3 ):3 }:2 }:1", colored(text))
    }

    fun testMismatchesDoNotShiftTheRest() {
        // an extra `(` is left unclosed and uncolored, the braces around it keep their level; an extra `)` is left alone too
        assertEquals("{:1 (:2 ):2 {:2 }:2 (:2 ):2 {:2 }:2 }:1", colored("class C { void M() { ( } void N() { } }"))
        assertEquals("{:1 (:2 ):2 {:2 }:2 (:2 ):2 {:2 }:2 }:1", colored("class C { void M() { ) } void N() { } }"))
    }

    fun testInactiveBranchesAreLeftAlone() {
        assertEquals("{:1 (:2 ):2 {:2 }:2 }:1", colored("class C {\n#if NEVER\n void M() { ( }\n#endif\n void N() { } }"))
    }

    fun testTheHeuristicTreeColorsNoAngleBrackets() {
        CSharpSyntaxTrees.forceNativeTreeForTests(false)
        assertEquals("{:1 (:2 ):2 {:2 }:2 }:1", colored("class C<T> { List<int> M() { } }"))
    }

    fun testTheSettingSwitchesItOff() {
        DotNetSettings.getInstance().colorizeBrackets = false
        assertEquals("", colored("class C { void M() { } }"))
    }

    fun testTheColorPageShowsTheLevels() {
        val page = CSharpColorSettingsPage()
        val described = page.attributeDescriptors.associate { it.key to it.displayName }
        for ((i, key) in CSharpBracketColors.LEVELS.withIndex()) {
            assertEquals("Braces and operators//Matching brackets//Level ${i + 1}", described[key])
            assertTrue(page.demoText.contains("<b${i + 1}>"))
        }
    }
}
