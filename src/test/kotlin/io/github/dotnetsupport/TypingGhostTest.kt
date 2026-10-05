package io.github.dotnetsupport

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpGhostText
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.CSharpVariableNames
import io.github.dotnetsupport.lang.NativeCSharpSyntaxModel
import io.github.dotnetsupport.lang.NativeCSharpTypingGhost
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The gray text of 0.1.101 ([NativeCSharpTypingGhost]): `new User();` by the variable's name, `);` after `new User(`, the members of an
 * empty initializer with their values, the value of `Name = `, the name after a type; the file's name after `class ` in the list and one
 * `class` row instead of two.
 */
class TypingGhostTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun code(body: String, parameters: String = "", types: String = DEFAULT_TYPES): String = """
using System;
using System.Collections.Generic;

namespace Shop;

$types

public static class Endpoints
{
    public static void Map($parameters)
    {
$body
    }
}
"""

    private fun ghost(text: String): NativeCSharpTypingGhost.Suggestion? {
        myFixture.configureByText("Ghost${counter++}.cs", text)
        return NativeCSharpTypingGhost.suggestion(myFixture.file as CSharpFile, myFixture.editor.document.charsSequence, myFixture.caretOffset)
    }

    private fun gray(text: String): String? = ghost(text)?.text

    // ---- 1. `var user = new |`

    fun testNewOfTheTypeNamedAsTheVariable() {
        assertEquals("User();", gray(code("        var user = new <caret>")))
        assertEquals("er();", gray(code("        var user = new Us<caret>")))
        assertEquals("List<User>();", gray(code("        var users = new <caret>")))
        assertNull("no type of that name", gray(code("        var order = new <caret>")))
        assertNull("its constructor wants arguments", gray(code("        var customer = new <caret>")))
        assertNull("required members: the initializer comes first", gray(code("        var account = new <caret>")))
        assertNull("text after the caret", gray(code("        var user = new <caret> User();")))
    }

    fun testTheListPutsTheTypeOfTheVariableFirst() {
        myFixture.configureByText("GhostList${counter++}.cs", code("        var user = new <caret>"))
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertEquals(items.toString(), "User", items.firstOrNull())
    }

    // ---- 2. `new User(|)` and `new User()|`

    fun testSemicolonPastTheClosingParenthesis() {
        val ghost = ghost(code("        var user = new User(<caret>)\n        var other = 1;"))
        assertEquals(";", ghost?.text)
        assertEquals(")", ghost?.skip)
        assertNull("the constructor wants arguments", gray(code("        var customer = new Customer(<caret>)")))
        assertNull("a `;` is there", gray(code("        var user = new User(<caret>);")))
        assertNull("an argument of a call", gray(code("        Console.WriteLine(new User(<caret>))")))
    }

    fun testSemicolonAfterACreationAndAfterAnInitializer() {
        fun semicolon(text: String): String? {
            val offset = text.indexOf("<caret>")
            val clean = text.replace("<caret>", "")
            val context = object : CSharpGhostText.Context() {
                override fun tree(text: CharSequence): CSharpFile = NativeCSharpSyntaxModel.parse(text)
            }
            return com.intellij.openapi.application.runReadAction<String?> { CSharpGhostText.suggest(clean, offset, context) }
        }
        assertEquals(";", semicolon(code("        var user = new User()<caret>\n        var other = 1;")))
        assertEquals(";", semicolon(code("        var user = new User()\n        {\n            Name = \"a\"\n        }<caret>\n        var other = 1;")))
        assertNull("a block", semicolon(code("        if (true)\n        {\n        }<caret>\n        var other = 1;")))
    }

    // ---- 3. the empty initializer

    fun testMembersOfAnEmptyInitializerWithTheirValues() {
        val text = code("        var user = new User()\n        {\n            <caret>\n        };", parameters = "UserDto userDto, int age")
        assertEquals("Name = userDto.Name,\n            Email = userDto.Email,\n            Age = age,\n            Admin = ", gray(text))
        val required = code("        var account = new Account\n        {\n            <caret>\n        };")
        assertEquals("Id = ,\n            Title = ", gray(required))
        assertNull("not an initializer", gray(code("        if (true)\n        {\n            <caret>\n        }")))
    }

    // ---- 4, 5. `Name = |`

    fun testTheValueOfAMember() {
        assertEquals("name", gray(code("        var user = new User()\n        {\n            Name = <caret>\n        };", parameters = "string name")))
        assertEquals("Dto.Name", gray(code("        var user = new User()\n        {\n            Name = user<caret>\n        };", parameters = "UserDto userDto")))
        assertEquals("the exact name over a property of it", "name",
            gray(code("        var user = new User { Name = <caret> };", parameters = "UserDto userDto, string name")))
        assertNull("nothing of that type", gray(code("        var user = new User { Age = <caret> };", parameters = "string name")))
        assertNull("two as good", gray(code("        var user = new User { Name = <caret> };", parameters = "UserDto userDto, UserDto other")))
    }

    fun testTheValueOfAMemberIsNotTheStatementValue() {
        val text = code("        var user = new User()\n        {\n            Name = |\n        };", parameters = "string name")
        val offset = text.indexOf('|')
        val clean = text.removeRange(offset, offset + 1)
        assertTrue(NativeCSharpTypingGhost.claims(clean, offset))
        assertNull("no `name;` of the value rule in an initializer", CSharpGhostText.suggest(clean, offset))
    }

    // ---- `member.Name = |` as a statement (0.1.103)

    fun testTheValueOfAnAssignedMember() {
        val user = "        var user = new User();\n"
        assertEquals("isAdmin;", gray(code("$user        var isAdmin = true;\n        user.Admin = <caret>")))
        assertEquals("userDto.Email;", gray(code("$user        user.Email = <caret>", parameters = "UserDto userDto")))
        assertEquals("the exact name over a property of it", "name;", gray(code("$user        user.Name = <caret>", parameters = "UserDto userDto, string name")))
        assertNull("never the member itself", gray(code("$user        user.Age = <caret>")))
        // the spaces around `=` change nothing but the space the gray text brings itself
        for (line in listOf("user.Name =<caret>", "user.Name=<caret>")) assertEquals(line, " name;", gray(code("$user        $line", parameters = "UserDto userDto, string name")))
        for (line in listOf("user.Name = <caret>", "user.Name  =  <caret>", "user.Name= <caret>")) assertEquals(line, "name;", gray(code("$user        $line", parameters = "UserDto userDto, string name")))
        assertEquals("to.Name;", gray(code("$user        user.Name = d<caret>", parameters = "UserDto dto, string name")))
    }

    fun testTheListOrdersTheValuesAsTheGrayText() {
        // `name` is the gray text: the path `userDto.Name` comes under it, not above
        myFixture.configureByText("Order${counter++}.cs", code("        var user = new User();\n        user.Name = <caret>", parameters = "UserDto userDto, string name"))
        myFixture.completeBasic()
        val rows = myFixture.lookupElementStrings.orEmpty()
        assertTrue(rows.toString(), rows.indexOf("name") in 0 until rows.indexOf("userDto.Name"))
    }

    fun testTheValuesOfTheListAtAnAssignment() {
        myFixture.configureByText("Values${counter++}.cs", code("        var user = new User();\n        user.Email = <caret>", parameters = "UserDto userDto, string name"))
        val values = NativeCSharpTypingGhost.valuesAt(myFixture.file as CSharpFile, myFixture.editor.document.charsSequence, myFixture.caretOffset)
        assertEquals("userDto.Email", values.firstOrNull())
        myFixture.completeBasic()
        assertTrue(myFixture.lookupElementStrings.orEmpty().toString(), "userDto.Email" in myFixture.lookupElementStrings.orEmpty())
    }

    fun testAMemberOfAValueIsNoType() {
        assertNull("`user.Admin ` is no type to name", gray(code("        var user = new User();\n        user.Admin <caret>")))
        assertEquals("the type of a namespace still is", "user", gray(code("        Shop.User <caret>")))
    }

    // ---- 6. a name after a type

    fun testNameAfterAType() {
        assertEquals("userDto", gray(code("", parameters = "UserDto <caret>")))
        assertEquals("userService", gray(code("", parameters = "string name, IUserService <caret>")))
        assertEquals("Dto", gray(code("", parameters = "UserDto user<caret>")))
        assertEquals("users", gray(code("        List<User> <caret>")))
        assertEquals("user", gray(code("        foreach (User <caret>")))
        assertEquals("user", gray(code("        var users = new List<User>();\n        foreach (var <caret> in users) { }")))
        assertEquals("userDto1", gray(code("", parameters = "UserDto userDto, UserDto <caret>")))
        assertNull("a predefined type", gray(code("", parameters = "string <caret>")))
        assertNull("a value", gray(code("        var x = count <caret>")))
        assertEquals("userDto", CSharpVariableNames.full("UserDto"))
    }

    fun testNameOfAFieldAndOfAProperty() {
        fun member(line: String): String? = gray("namespace Shop;\npublic class UserDto { }\npublic class Holder\n{\n$line\n}\n")
        assertEquals("_userDto", member("    private readonly UserDto <caret>"))
        assertEquals("UserDto", member("    public UserDto <caret>"))
        assertNull("a method", member("    public Task <caret>"))
    }

    // ---- 7. `class` once, the file's name after it

    fun testClassKeywordOnce() {
        com.intellij.codeInsight.template.impl.LiveTemplateCompletionContributor.setShowTemplatesInTests(true, testRootDisposable)
        val settings = com.intellij.codeInsight.CodeInsightSettings.getInstance()
        val autocomplete = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        try {
            myFixture.configureByText("Once${counter++}.cs", "namespace Shop;\n\npublic class Order\n{\n    public clas<caret>\n}\n")
            myFixture.completeBasic()
            val items = myFixture.lookupElementStrings.orEmpty()
            assertEquals(items.toString(), 1, items.count { it == "class" })
            // where no keyword `class` stands, the template is there
            myFixture.configureByText("Once${counter++}.cs", "namespace Shop;\n\npublic class Order\n{\n    void M()\n    {\n        cla<caret>\n    }\n}\n")
            myFixture.completeBasic()
            assertTrue(myFixture.lookupElementStrings.toString(), myFixture.lookupElements.orEmpty().any { it.lookupString == "class" && it.javaClass.name.contains("LiveTemplate") })
        } finally {
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
        }
    }

    fun testFileNameAfterClass() {
        myFixture.configureByText("OrderEndpoints.cs", "namespace Shop;\n\npublic static class <caret>\n")
        myFixture.completeBasic()
        val items = myFixture.lookupElements
        if (items != null) {
            val item = items.firstOrNull { it.lookupString == "OrderEndpoints" } ?: error("no OrderEndpoints in ${items.map { it.lookupString }}")
            myFixture.lookup.currentItem = item
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("public static class OrderEndpoints\n{\n    \n}"))
    }

    fun testNoFileNameWhenTheFileHasTheType() {
        myFixture.configureByText("Taken.cs", "namespace Shop;\n\npublic class Taken { }\n\npublic class <caret>\n")
        myFixture.completeBasic()
        assertFalse(myFixture.lookupElementStrings.orEmpty().contains("Taken"))
    }

    companion object {
        private var counter = 0

        private const val DEFAULT_TYPES = """
public class User
{
    public string Name { get; set; } = "";
    public string Email { get; set; } = "";
    public int Age { get; set; }
    public bool Admin { get; set; }
}

public class UserDto
{
    public string Name { get; set; } = "";
    public string Email { get; set; } = "";
}

public interface IUserService { }

public class Customer
{
    public Customer(string name) { }
}

public class Account
{
    public required int Id { get; set; }
    public required string Title { get; set; }
}
"""

        private fun bytes(name: String): ByteArray? = TypingGhostTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
