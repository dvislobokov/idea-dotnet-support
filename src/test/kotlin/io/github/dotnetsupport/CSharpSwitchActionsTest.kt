package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * The context actions on `switch` (0.1.162, `NativeCSharpSwitchActions.kt`): the missing sections and arms of an enum, `default`, section
 * braces, statement ↔ expression, statement → `if` chain; and where each is not offered.
 */
class CSharpSwitchActionsTest : BasePlatformTestCase() {
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
    }

    override fun tearDown() {
        try {
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** [members] as the members of a class after the usings and an enum; after [title] at `<caret>`, the members back. */
    private fun apply(members: String, title: String): String {
        myFixture.configureByText("Switch${files++}.cs", wrap(members))
        myFixture.launchAction(myFixture.findSingleIntention(title))
        return unwrap(myFixture.editor.document.text)
    }

    private fun available(members: String, title: String): Boolean {
        myFixture.configureByText("Switch${files++}.cs", wrap(members))
        return myFixture.filterAvailableIntentions(title).any { it.text == title }
    }

    private fun wrap(members: String): String = HEAD + members.trimIndent().prependIndent("    ") + "\n}\n"

    private fun unwrap(text: String): String = text.removePrefix(HEAD).removeSuffix("\n}\n").lines().joinToString("\n") { it.removePrefix("    ") }

    // ---- Add 'switch' sections / Add 'default' section

    fun testAddSwitchSectionsWritesTheMissingMembersAndDefault() {
        assertEquals(
            """
            void M(OperationType operationType)
            {
                switch (operationType)
                {
                    case OperationType.Insert:
                        break;
                    case OperationType.Update:
                        break;
                    case OperationType.Delete:
                        break;
                    default:
                        break;
                }
            }
            """.trimIndent(),
            apply("""
                void M(OperationType operationType)
                {
                    sw<caret>itch (operationType)
                    {
                        case OperationType.Insert:
                            break;
                    }
                }
            """, "Add 'switch' sections"),
        )
    }

    fun testAddSwitchSectionsFillsAnEmptySwitchAndKeepsTheQualifierOfTheLabels() {
        val text = apply("""
            void M(OperationType operationType)
            {
                <caret>switch (operationType) { }
            }
        """, "Add 'switch' sections")
        assertTrue(text, text.contains("switch (operationType) {\n        case OperationType.Insert:\n            break;\n        case OperationType.Update:"))
        assertTrue(text, text.endsWith("default:\n            break;\n    }\n}"))
        val short = apply("""
            void M(OperationType operationType)
            {
                <caret>switch (operationType)
                {
                    case Demo.OperationType.Delete:
                        return;
                    default:
                        return;
                }
            }
        """, "Add 'switch' sections")
        assertTrue(short, short.contains("case Demo.OperationType.Insert:\n            break;\n        case Demo.OperationType.Update:\n            break;\n    }"))
    }

    fun testAddSwitchSectionsIsNotOffered() {
        // all members and default there; not an enum; the caret in the body
        val full = "void M(OperationType t)\n{\n    <caret>switch (t)\n    {\n        case OperationType.Insert: break;\n        case OperationType.Update: break;\n        case OperationType.Delete: break;\n        default: break;\n    }\n}"
        assertFalse(available(full, "Add 'switch' sections"))
        assertFalse(available("void M(int n)\n{\n    <caret>switch (n)\n    {\n        case 1: break;\n    }\n}", "Add 'switch' sections"))
        assertFalse(available("void M(OperationType t)\n{\n    switch (t)\n    {\n        case OperationType.Insert: <caret>break;\n    }\n}", "Add 'switch' sections"))
    }

    fun testAddDefaultSection() {
        assertEquals(
            "void M(int n)\n{\n    switch (n)\n    {\n        case 1:\n            break;\n        default:\n            break;\n    }\n}",
            apply("void M(int n)\n{\n    <caret>switch (n)\n    {\n        case 1:\n            break;\n    }\n}", "Add 'default' section"),
        )
        assertFalse(available("void M(int n)\n{\n    <caret>switch (n)\n    {\n        default:\n            break;\n    }\n}", "Add 'default' section"))
    }

    // ---- Add missing arms

    fun testAddMissingArms() {
        assertEquals(
            "string M(OperationType t)\n{\n    return t switch\n    {\n        OperationType.Insert => \"i\",\n        OperationType.Update => throw new NotImplementedException(),\n" +
                "        OperationType.Delete => throw new NotImplementedException(),\n        _ => throw new ArgumentOutOfRangeException()\n    };\n}",
            apply("string M(OperationType t)\n{\n    return t swi<caret>tch\n    {\n        OperationType.Insert => \"i\"\n    };\n}", "Add missing arms"),
        )
        // an unconditional `_` takes the other members already: throwing arms would change what they do (Jellyfin № 18, as Rider)
        assertFalse(available("string M(OperationType t)\n{\n    return t <caret>switch\n    {\n        OperationType.Insert => \"i\",\n        _ => \"?\",\n    };\n}", "Add missing arms"))
        assertFalse(available("string M(OperationType t)\n{\n    return t <caret>switch\n    {\n        OperationType.Insert => \"i\",\n        OperationType.Update => \"u\",\n        OperationType.Delete => \"d\",\n        _ => \"?\"\n    };\n}", "Add missing arms"))
        assertFalse(available("string M(int n)\n{\n    return n <caret>switch\n    {\n        1 => \"i\",\n    };\n}", "Add missing arms"))
    }

    // ---- Add section braces

    fun testAddSectionBraces() {
        assertEquals(
            "string M(int n)\n{\n    switch (n)\n    {\n        case 1:\n        {\n            return \"one\";\n        }\n        case 2:\n        {\n            M(n);\n            return \"two\";\n        }\n        default:\n        {\n            return \"?\";\n        }\n    }\n}",
            apply("string M(int n)\n{\n    <caret>switch (n)\n    {\n        case 1:\n            return \"one\";\n        case 2:\n        {\n            M(n);\n            return \"two\";\n        }\n        default: return \"?\";\n    }\n}", "Add section braces"),
        )
        assertFalse(available("string M(int n)\n{\n    <caret>switch (n)\n    {\n        case 1:\n        {\n            return \"one\";\n        }\n    }\n}", "Add section braces"))
    }

    // ---- To 'switch' expression / To 'switch' statement

    fun testSwitchStatementBecomesSwitchExpression() {
        assertEquals(
            "string M(OperationType t)\n{\n    return t switch\n    {\n        OperationType.Insert => \"i\",\n        OperationType.Update => \"u\",\n        _ => \"?\"\n    };\n}",
            apply("string M(OperationType t)\n{\n    <caret>switch (t)\n    {\n        case OperationType.Insert:\n            return \"i\";\n        case OperationType.Update:\n        {\n            return \"u\";\n        }\n        default:\n            return \"?\";\n    }\n}", "To 'switch' expression"),
        )
        // assignments to one variable, a throw, no default: the discard arm throws
        assertEquals(
            "void M(OperationType t)\n{\n    string s;\n    s = t switch\n    {\n        OperationType.Insert => \"i\",\n        OperationType.Update => throw new InvalidOperationException(),\n        _ => throw new ArgumentOutOfRangeException(nameof(t), t, null)\n    };\n}",
            apply("void M(OperationType t)\n{\n    string s;\n    <caret>switch (t)\n    {\n        case OperationType.Insert:\n            s = \"i\";\n            break;\n        case OperationType.Update:\n            throw new InvalidOperationException();\n    }\n}", "To 'switch' expression"),
        )
    }

    fun testToSwitchExpressionIsNotOffered() {
        // two labels of a section, a section of two statements, assignments to different variables
        assertFalse(available("string M(int n)\n{\n    <caret>switch (n)\n    {\n        case 1:\n        case 2:\n            return \"a\";\n        default:\n            return \"b\";\n    }\n}", "To 'switch' expression"))
        assertFalse(available("string M(int n)\n{\n    <caret>switch (n)\n    {\n        case 1:\n            M(n);\n            return \"a\";\n        default:\n            return \"b\";\n    }\n}", "To 'switch' expression"))
        assertFalse(available("void M(int n)\n{\n    int a, b;\n    <caret>switch (n)\n    {\n        case 1:\n            a = 1;\n            break;\n        default:\n            b = 2;\n            break;\n    }\n}", "To 'switch' expression"))
    }

    fun testSwitchExpressionBecomesSwitchStatement() {
        assertEquals(
            "string M(OperationType t)\n{\n    switch (t)\n    {\n        case OperationType.Insert:\n            return \"i\";\n        case OperationType.Update when t != OperationType.Delete:\n            return \"u\";\n        default:\n            throw new ArgumentOutOfRangeException();\n    }\n}",
            apply("string M(OperationType t)\n{\n    return t <caret>switch\n    {\n        OperationType.Insert => \"i\",\n        OperationType.Update when t != OperationType.Delete => \"u\",\n        _ => throw new ArgumentOutOfRangeException()\n    };\n}", "To 'switch' statement"),
        )
        assertEquals(
            "void M(int n)\n{\n    string s;\n    switch (n)\n    {\n        case 1:\n            s = \"a\";\n            break;\n        case > 1:\n            s = \"b\";\n            break;\n        default:\n            s = \"?\";\n            break;\n    }\n}",
            apply("void M(int n)\n{\n    string s;\n    s = n <caret>switch { 1 => \"a\", > 1 => \"b\", _ => \"?\" };\n}", "To 'switch' statement"),
        )
        // the value of an argument, not of a return or an assignment
        assertFalse(available("void M(int n)\n{\n    M(n <caret>switch { 1 => 2, _ => 3 });\n}", "To 'switch' statement"))
    }

    // ---- To 'if' statements

    fun testSwitchBecomesIfStatements() {
        assertEquals(
            "string M(OperationType t, int n)\n{\n    if (t == OperationType.Insert || t == OperationType.Update)\n    {\n        n++;\n        return \"iu\";\n    }\n    else if (t == OperationType.Delete && (n > 0))\n    {\n        return \"d\";\n    }\n    else\n    {\n        return \"?\";\n    }\n}",
            apply("string M(OperationType t, int n)\n{\n    <caret>switch (t)\n    {\n        case OperationType.Insert:\n        case OperationType.Update:\n            n++;\n            return \"iu\";\n        default:\n            return \"?\";\n        case OperationType.Delete when n > 0:\n        {\n            return \"d\";\n        }\n    }\n}", "To 'if' statements"),
        )
        // `break;` dropped, no default, a compound subject in parentheses
        assertEquals(
            "void M(int n)\n{\n    if ((n + 1) == 1)\n    {\n        M(n);\n    }\n    else if ((n + 1) == 2)\n    {\n    }\n}",
            apply("void M(int n)\n{\n    <caret>switch (n + 1)\n    {\n        case 1:\n            M(n);\n            break;\n        case 2:\n            break;\n    }\n}", "To 'if' statements"),
        )
    }

    fun testToIfStatementsIsNotOffered() {
        assertFalse(available("void M(int n)\n{\n    <caret>switch (n)\n    {\n        case 1:\n            goto case 2;\n        case 2:\n            break;\n    }\n}", "To 'if' statements"))
        assertFalse(available("void M(int n)\n{\n    <caret>switch (n)\n    {\n        default:\n            break;\n    }\n}", "To 'if' statements"))
        assertFalse(available("void M(int n)\n{\n    switch (n)\n    {\n        case 1:\n            <caret>break;\n    }\n}", "To 'if' statements"))
    }

    // ---- a switch expression: To 'if' statements, To ?: expression (0.1.173)

    fun testSwitchExpressionBecomesIfStatements() {
        assertEquals(
            "string M(OperationType t)\n{\n    if (t == OperationType.Insert)\n    {\n        return \"i\";\n    }\n    else if (t is > OperationType.Insert && (t != OperationType.Delete))\n    {\n        return \"u\";\n    }\n    else\n    {\n        throw new InvalidOperationException();\n    }\n}",
            apply("string M(OperationType t)\n{\n    return t <caret>switch\n    {\n        OperationType.Insert => \"i\",\n        > OperationType.Insert when t != OperationType.Delete => \"u\",\n        _ => throw new InvalidOperationException()\n    };\n}", "To 'if' statements"),
        )
        // an assignment without a discard arm: the last `else` throws as the switch expression would
        assertEquals(
            "void M(int n)\n{\n    string s;\n    if (n == 1)\n    {\n        s = \"a\";\n    }\n    else\n    {\n        throw new ArgumentOutOfRangeException(nameof(n), n, null);\n    }\n}",
            apply("void M(int n)\n{\n    string s;\n    s = n <caret>switch { 1 => \"a\" };\n}", "To 'if' statements"),
        )
        assertFalse(available("void M(int n)\n{\n    M(n <caret>switch { 1 => 2, _ => 3 });\n}", "To 'if' statements"))
    }

    fun testSwitchExpressionBecomesConditional() {
        assertEquals(
            "string M(OperationType t) => t == OperationType.Insert ? \"i\" : \"other\";",
            apply("string M(OperationType t) => t <caret>switch { OperationType.Insert => \"i\", _ => \"other\" };", "To ?: expression"),
        )
        assertEquals(
            "string M(object o) => o is string s && (s.Length > 0) ? s : \"\";",
            apply("string M(object o) => o <caret>switch { string s when s.Length > 0 => s, _ => \"\" };", "To ?: expression"),
        )
        // three arms, or no discard arm last: not a `?:`
        assertFalse(available("int M(int n) => n <caret>switch { 1 => 1, 2 => 2, _ => 0 };", "To ?: expression"))
        assertFalse(available("int M(int n) => n <caret>switch { 1 => 1, > 1 => 2 };", "To ?: expression"))
    }

    // ---- Add condition component (0.1.173)

    fun testAddConditionComponent() {
        myFixture.configureByText("Switch${files++}.cs", wrap("void M(OperationType t, int n)\n{\n    <caret>switch (t)\n    {\n        case OperationType.Insert:\n            break;\n        case OperationType.Update when n > 0:\n            break;\n        default:\n            break;\n    }\n}"))
        myFixture.launchAction(myFixture.findSingleIntention("Add condition component"))
        assertEquals(
            "void M(OperationType t, int n)\n{\n    switch (t, )\n    {\n        case (OperationType.Insert, _):\n            break;\n        case (OperationType.Update, _) when n > 0:\n            break;\n        default:\n            break;\n    }\n}",
            unwrap(myFixture.editor.document.text),
        )
        // the caret on the new component
        val text = myFixture.editor.document.text
        assertEquals(text.indexOf("switch (t, ") + "switch (t, ".length, myFixture.editor.caretModel.offset)
        // a tuple gets one more component, its positional patterns `, _`
        assertEquals(
            "int M(int a, int b) => (a, b, ) switch { (1, 2, _) => 1, _ => 0 };",
            apply("int M(int a, int b) => (a, b) <caret>switch { (1, 2) => 1, _ => 0 };", "Add condition component"),
        )
        assertEquals(
            "int M(int a) => (a, ) switch { 1 => 1, _ => 0 };".replace("1 => 1", "(1, _) => 1"),
            apply("int M(int a) => a <caret>switch { 1 => 1, _ => 0 };", "Add condition component"),
        )
    }

    // ---- Generate type patterns (0.1.173)

    fun testGenerateTypePatterns() {
        val types = "class TpShape { }\nclass TpCircle : TpShape { }\nclass TpSquare : TpShape { }\nclass TpCube : TpSquare { }\nsealed class TpSealed { }\n"
        assertEquals(
            types + "void M(TpShape shape)\n{\n    switch (shape)\n    {\n        case TpCircle c:\n            break;\n        case TpSquare square:\n            break;\n        default:\n            break;\n    }\n}",
            apply(types + "void M(TpShape shape)\n{\n    <caret>switch (shape)\n    {\n        case TpCircle c:\n            break;\n        default:\n            break;\n    }\n}", "Generate type patterns"),
        )
        assertEquals(
            types + "int M(TpShape shape) => shape switch\n{\n    TpCircle circle => throw new NotImplementedException(),\n    TpSquare square => throw new NotImplementedException(),\n    _ => 0\n};",
            apply(types + "int M(TpShape shape) => shape <caret>switch\n{\n    _ => 0\n};", "Generate type patterns"),
        )
        // a sealed type, a value type, every subtype matched already
        assertFalse(available(types + "void M(TpSealed s)\n{\n    <caret>switch (s) { default: break; }\n}", "Generate type patterns"))
        assertFalse(available(types + "void M(int n)\n{\n    <caret>switch (n) { default: break; }\n}", "Generate type patterns"))
        assertFalse(available(types + "void M(TpShape s)\n{\n    <caret>switch (s) { case TpCircle c: break; case TpSquare q: break; }\n}", "Generate type patterns"))
    }

    private companion object {
        const val HEAD ="using System;\n\nnamespace Demo;\n\nenum OperationType { Insert, Update, Delete }\n\nclass Sample\n{\n"

        private fun bytes(name: String): ByteArray? = CSharpSwitchActionsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
