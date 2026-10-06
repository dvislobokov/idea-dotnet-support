package io.github.dotnetsupport

import com.intellij.codeInsight.inline.completion.testInlineCompletion
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/** The gray text of [io.github.dotnetsupport.lang.NativeCSharpTypingGhostProvider] through the platform: shown, and Tab writes it as shown. */
class TypingGhostInlineTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { TypingGhostTest.ASSEMBLIES }
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val types = "public class User { public string Name { get; set; } = \"\"; }\n"

    fun testTheSemicolonGoesPastTheParenthesis() {
        myFixture.testInlineCompletion {
            init(CSharpFileType, "${types}public static class E\n{\n    public static void M()\n    {\n        var user = new User(<caret>)\n        var other = 1;\n    }\n}\n")
            callInlineCompletion()
            delay()
            assertInlineElements {
                skip(")")
                gray(";")
            }
            insert()
            assertFileContent("${types}public static class E\n{\n    public static void M()\n    {\n        var user = new User();<caret>\n        var other = 1;\n    }\n}\n")
        }
    }

    fun testNewByTheNameOfTheVariable() {
        myFixture.testInlineCompletion {
            init(CSharpFileType, "${types}public static class E\n{\n    public static void M()\n    {\n        var user = new <caret>\n    }\n}\n")
            callInlineCompletion()
            delay()
            assertInlineRender("User();")
            insert()
            assertFileContent("${types}public static class E\n{\n    public static void M()\n    {\n        var user = new User();<caret>\n    }\n}\n")
        }
    }

    fun testTheMembersOfAnEmptyInitializer() {
        myFixture.testInlineCompletion {
            init(CSharpFileType, "${types}public static class E\n{\n    public static void M(string name)\n    {\n        var user = new User()\n        {\n            <caret>\n        };\n    }\n}\n")
            callInlineCompletion()
            delay()
            assertInlineRender("Name = name")
        }
    }

    // ---- the open completion list (0.1.102): the gray text follows the selected row

    private val listTypes = "public class User { public string Name { get; set; } = \"\"; }\npublic class UserDto { public string Name { get; set; } = \"\"; }\n" +
        "public class Customer { public Customer(string name) { } }\n"

    private fun method(body: String, parameters: String = "") = "${listTypes}public static class E\n{\n    public static void M($parameters)\n    {\n        $body\n    }\n}\n"

    fun testTheGrayTextFollowsTheSelectedRowOfTheList() {
        myFixture.testInlineCompletion {
            init(CSharpFileType, method("var user = new <caret>"))
            createLookup()
            delay()
            assertInlineRender("User();")
            pickLookupElement("UserDto")
            delay()
            assertInlineRender("UserDto();")
            pickLookupElement("Customer")
            delay()
            assertInlineHidden()
            pickLookupElement("UserDto")
            delay()
            // Tab takes the gray text and closes the list
            insertWithTab()
            assertFileContent(method("var user = new UserDto();<caret>"))
            assertNoLookup()
        }
    }

    fun testTheGrayTextAddsToWhatIsTyped() {
        myFixture.testInlineCompletion {
            init(CSharpFileType, method("Console.WriteLine(new Us<caret>)"))
            createLookup()
            pickLookupElement("UserDto")
            delay()
            assertInlineRender("erDto()")
        }
    }

    fun testTheValueAfterTheSelectedMember() {
        myFixture.testInlineCompletion {
            // `member.` + `Admin`: `Admin = isAdmin;`, not a name as after a type (`Admin admin`)
            init(CSharpFileType, "public class Member { public bool Admin { get; set; } public bool Active { get; set; } }\n" +
                "public static class E\n{\n    public static void M(bool isAdmin)\n    {\n        var member = new Member();\n        member.A<caret>\n    }\n}\n")
            createLookup()
            pickLookupElement("Admin")
            delay()
            assertInlineRender("dmin = isAdmin;")
        }
    }

    fun testTheNameAfterTheSelectedType() {
        myFixture.testInlineCompletion {
            init(CSharpFileType, "${listTypes}public static class E\n{\n    public static void M(Use<caret>)\n    {\n    }\n}\n")
            createLookup()
            pickLookupElement("UserDto")
            delay()
            assertInlineRender("rDto userDto")
        }
    }

    fun testTheNameAfterAType() {
        myFixture.testInlineCompletion {
            init(CSharpFileType, "${types}public static class E\n{\n    public static void M(User <caret>)\n    {\n    }\n}\n")
            callInlineCompletion()
            delay()
            assertInlineRender("user")
            insert()
            assertFileContent("${types}public static class E\n{\n    public static void M(User user<caret>)\n    {\n    }\n}\n")
        }
    }

    // ---- the override being typed (0.1.126): the best one in gray, Tab writes what the row of the list writes

    private fun lens(line: String) = "class LensCircle\n{\n    public virtual string Describe() => \"\";\n}\nclass LensRing : LensCircle\n{\n    $line\n}\n"

    fun testTheBestOverrideAfterTheStartOfItsReturnType() {
        myFixture.testInlineCompletion {
            init(CSharpFileType, lens("public override str<caret>"))
            callInlineCompletion()
            delay()
            assertInlineRender("ing Describe()\n    {\n        return base.Describe();\n    }")
            insert()
            assertFileContent(lens("public override string Describe()\n    {\n        return base.Describe();<caret>\n    }"))
        }
    }

    fun testTheOverrideRowOfTheListInGray() {
        myFixture.testInlineCompletion {
            init(CSharpFileType, lens("public ov<caret>"))
            createLookup()
            pickLookupElement("override Describe")
            delay()
            assertInlineRender("erride string Describe()\n    {\n        return base.Describe();\n    }")
            insertWithTab()
            assertFileContent(lens("public override string Describe()\n    {\n        return base.Describe();<caret>\n    }"))
            assertNoLookup()
        }
    }
}
