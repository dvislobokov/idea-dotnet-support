package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.lang.parser.SliceParseHarness.Mode

/**
 * Depth and cost of the declaration parser: deep but realistic nesting parses without errors, absurd nesting does not
 * throw (the depth guard or [SyntaxParser.parseWithStackGuard] stops it), and the replays of
 * `ParseMemberDeclarationOrStatement` and the two passes of `ParseNamespaceBody` stay bounded (docs/csharp-psi/GRAMMAR.md,
 * "Declarations").
 */
class DeclarationNestingTest : CSharpParsingTestCase("parser/decl") {

    private fun parseClean(text: String) {
        val r = SliceParseHarness.parse(text, Mode.File)
        assertFalse("stack overflow in: ${text.take(60)}...", r.stackOverflow)
        assertEquals("errors in: ${text.take(60)}...", 0, r.errorCount)
        assertEquals("error elements in: ${text.take(60)}...", 0, r.errorElements)
    }

    fun testDeepTypesAndNamespacesParseWithoutErrors() {
        parseClean("class C { ".repeat(150) + "}".repeat(150))
        parseClean("namespace N { ".repeat(150) + "}".repeat(150))
        parseClean("void F() { " + "void G() { ".repeat(100) + "}".repeat(100) + " }")
    }

    fun testAbsurdNestingDoesNotThrow() {
        for (text in listOf(
            "class C { ".repeat(5000),
            "namespace N { ".repeat(5000),
            "[A] ".repeat(3000) + "class C { }",
            "public ".repeat(5000),
        )) {
            SliceParseHarness.parse(text, Mode.File)
        }
    }

    /** Each nested namespace has a member to move into a type: every level parses its body twice. */
    fun testMisplacedMembersInNestedNamespacesAreBounded() {
        val depth = 10
        val text = "namespace N { class C { } void M() { } ".repeat(depth) + "}".repeat(depth)
        val t0 = System.nanoTime()
        SliceParseHarness.parse(text, Mode.File)
        val millis = (System.nanoTime() - t0) / 1_000_000
        println("  misplaced members, $depth nested namespaces: $millis ms")
        assertTrue("took $millis ms", millis < 5000)
    }

    /** Top-level members are tried as statements and members; a long run of them stays linear. */
    fun testManyTopLevelMembersAreLinear() {
        val unit = "async Task M() { await F(x => { int L() => 1; return L(); }); }\nint P { get; }\nx = y;\n"
        val small = timeMillis(unit.repeat(200))
        val large = timeMillis(unit.repeat(2000))
        println("  top-level members: 200 units $small ms, 2000 units $large ms")
        assertTrue("2000 units took $large ms (200 took $small ms)", large < 20 * small + 2000)
    }

    private fun timeMillis(text: String): Long {
        SliceParseHarness.parse(text, Mode.File)
        val t0 = System.nanoTime()
        SliceParseHarness.parse(text, Mode.File)
        return (System.nanoTime() - t0) / 1_000_000
    }
}
