package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The context actions on `switch` (0.1.162, `NativeCSharpSwitchActions.kt`): the missing sections and arms of an enum, `default`, section
 * braces, statement ↔ expression, statement → `if` chain; and where each is not offered.
 */
class CSharpSwitchActionsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.CONTEXT_ACTIONS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            settings.state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
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
        // the arms go before the `_` arm, which stays last
        assertEquals(
            "string M(OperationType t)\n{\n    return t switch\n    {\n        OperationType.Insert => \"i\",\n        OperationType.Update => throw new NotImplementedException(),\n" +
                "        OperationType.Delete => throw new NotImplementedException(),\n        _ => \"?\",\n    };\n}",
            apply("string M(OperationType t)\n{\n    return t <caret>switch\n    {\n        OperationType.Insert => \"i\",\n        _ => \"?\",\n    };\n}", "Add missing arms"),
        )
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

    private companion object {
        const val HEAD = "using System;\n\nnamespace Demo;\n\nenum OperationType { Insert, Update, Delete }\n\nclass Sample\n{\n"

        private fun bytes(name: String): ByteArray? = CSharpSwitchActionsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
