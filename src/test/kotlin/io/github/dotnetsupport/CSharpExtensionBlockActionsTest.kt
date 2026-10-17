package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.CSharpLanguageLevel
import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpSyntaxTrees

/**
 * Extension methods into a C# 14 `extension` block (0.1.173, `NativeCSharpExtensionBlockActions.kt`): every method of the receiver
 * ("Convert to extension block (N methods)") or the one at the caret ("Convert this method to extension block"); not before C# 14, not for
 * a generic method or a class that cannot hold extension blocks.
 */
class CSharpExtensionBlockActionsTest : BasePlatformTestCase() {
    private var files = 0

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

    private fun configure(text: String, version: CSharpLanguageVersion? = null) {
        myFixture.configureByText("Ext${files++}.cs", text)
        if (version != null) myFixture.file.putUserData(CSharpLanguageLevel.KEY, version)
    }

    private fun titles(text: String, version: CSharpLanguageVersion? = null): List<String> {
        configure(text, version)
        return myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Convert") }
    }

    private val strings = """
        namespace Demo;

        public static class Strings
        {
            /// <summary>Words.</summary>
            public static int Wo<caret>rds(this string text) => text.Split(' ').Length;

            public static bool IsBlank(this string text)
            {
                return text.Trim().Length == 0;
            }

            public static int Twice(this int value) => value * 2;

            public static string Left(this string text, int count) => text.Substring(0, count);
        }
    """.trimIndent() + "\n"

    fun testEveryMethodOfTheReceiver() {
        assertEquals(listOf("Convert this method to extension block", "Convert to extension block (3 methods)"), titles(strings).sorted())
        myFixture.launchAction(myFixture.findSingleIntention("Convert to extension block (3 methods)"))
        assertEquals(
            """
            namespace Demo;

            public static class Strings
            {
                extension(string text)
                {
                    /// <summary>Words.</summary>
                    public int Words() => text.Split(' ').Length;

                    public bool IsBlank()
                    {
                        return text.Trim().Length == 0;
                    }

                    public string Left(int count) => text.Substring(0, count);
                }

                public static int Twice(this int value) => value * 2;
            }
            """.trimIndent() + "\n",
            myFixture.editor.document.text,
        )
    }

    fun testOneMethod() {
        configure(strings.replace("Wo<caret>rds", "Words").replace("Twi", "Tw<caret>i"))
        assertEquals(listOf("Convert this method to extension block"), myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Convert") })
        myFixture.launchAction(myFixture.findSingleIntention("Convert this method to extension block"))
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("    extension(int value)\n    {\n        public int Twice() => value * 2;\n    }\n"))
        assertFalse(text, text.contains("this int value"))
    }

    fun testNotOffered() {
        // before C# 14
        assertEquals(emptyList<String>(), titles(strings, CSharpLanguageVersion.CSharp13))
        // a generic method; a class that is not static
        assertEquals(emptyList<String>(), titles("public static class G\n{\n    public static T Fi<caret>rst<T>(this T[] items) => items[0];\n}\n"))
        assertEquals(emptyList<String>(), titles("public class C\n{\n    public static int Wo<caret>rds(string text) => 0;\n}\n"))
    }
}
