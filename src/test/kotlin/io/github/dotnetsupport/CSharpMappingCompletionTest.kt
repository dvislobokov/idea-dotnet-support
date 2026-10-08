package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpMappingCompletion
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.settings.DotNetSettings

/**
 * The mapping completion (0.1.134, [NativeCSharpMappingCompletion]): `Name = user.Name,` in an initializer, `dto.Name = user.Name;` under
 * a block of assignments, the nested `user.Profile.Email`, a member of another type and a set member left out, the row that maps all,
 * where the rows stand, and nothing with the setting off.
 */
class CSharpMappingCompletionTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true
    private var counter = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        DotNetSettings.getInstance().mappingCompletion = true
    }

    override fun tearDown() {
        try {
            DotNetSettings.getInstance().mappingCompletion = true
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            runCatching { myFixture.lookup?.hideLookup(true) }
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun code(body: String): String = """
        using System;

        public class UserProfile { public string Email { get; set; } public string City { get; set; } }
        public class User
        {
            public int Id { get; set; }
            public string Name { get; set; }
            public string Login { get; set; }
            public DateTime Created { get; set; }
            public UserProfile Profile { get; set; }
        }
        public class UserDto
        {
            public int Id { get; set; }
            public string Name { get; set; }
            public string Email { get; set; }
            public int Created { get; set; }
            public string Note { get; init; }
            public bool Active { get; set; }
        }
        class Sample
        {
            void Run(User user, string note)
            {
                BODY
            }
        }
    """.trimIndent().replace("BODY", body.trimIndent().replace("\n", "\n        "))

    private fun lookup(text: String): List<LookupElement> {
        myFixture.configureByText("Mapping${counter++}.cs", text)
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElements?.toList().orEmpty()
    }

    private fun names(text: String): List<String> = lookup(text).map { it.lookupString }

    private fun choose(text: String, item: String): String {
        val elements = lookup(text)
        val element = elements.firstOrNull { it.lookupString == item } ?: error("no $item in ${elements.map { it.lookupString }}")
        myFixture.lookup.currentItem = element
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        return withCaret()
    }

    private fun withCaret(): String {
        val text = myFixture.editor.document.text
        val caret = myFixture.editor.caretModel.offset
        return text.substring(0, caret) + "<caret>" + text.substring(caret)
    }

    /** The body of `Run` as written, the caret marked. */
    private fun body(text: String): String {
        val start = text.indexOf("void Run(User user, string note)\n    {\n") + "void Run(User user, string note)\n    {\n".length
        val end = text.indexOf("\n    }\n}", start)
        return text.substring(start, end).lines().joinToString("\n") { it.removePrefix("        ") }
    }

    // ---- the initializer

    fun testInitializerRowsByNameAndType() {
        val names = names(code("var dto = new UserDto { <caret> };"))
        assertTrue(names.toString(), "Id = user.Id" in names && "Name = user.Name" in names && "Note = note" in names)
        // the nested one: UserDto.Email ← user.Profile.Email
        assertTrue(names.toString(), "Email = user.Profile.Email" in names)
        // a DateTime for an int, and nothing is named like Active
        assertFalse(names.toString(), names.any { it.startsWith("Created = ") || it.startsWith("Active = ") })
        assertTrue(names.toString(), NativeCSharpMappingCompletion.MAP_ALL + "user" in names)
        // the members themselves are still there
        assertTrue(names.toString(), "Id" in names && "Created" in names)
    }

    fun testASetMemberIsLeftOutAndTheRowsStandFirstBesideThePartner() {
        val names = names(code("var dto = new UserDto { Id = user.Id, <caret> };"))
        assertFalse(names.toString(), "Id = user.Id" in names)
        assertTrue(names.toString(), "Name = user.Name" in names)
        // an assignment from `user` is written already: clearly a mapping, the rows are first
        assertTrue(names.toString(), names.first().startsWith("Name = user.Name") || names.first() == NativeCSharpMappingCompletion.MAP_ALL + "user")
        assertTrue(names.toString(), names.indexOf("Name = user.Name") < names.indexOf("Name"))
    }

    fun testAnEmptyInitializerWithTheObjectAtHandIsClearlyAMapping() {
        val names = names(code("var dto = new UserDto { <caret> };"))
        assertTrue(names.toString(), names.indexOf("Id = user.Id") < names.indexOf("Id"))
    }

    fun testWithoutAPartnerTheRowsComeAfterTheMembers() {
        val names = names(code("var dto = new UserDto { Active = true, <caret> };"))
        assertTrue(names.toString(), "Id = user.Id" in names)
        assertTrue(names.toString(), names.indexOf("Id") < names.indexOf("Id = user.Id"))
    }

    fun testAnInitializerRowWritesTheEntryWithItsComma() {
        assertEquals("var dto = new UserDto { Name = user.Name<caret> };", body(choose(code("var dto = new UserDto { <caret> };"), "Name = user.Name")))
        val multi = choose(code("var dto = new UserDto\n{\n    <caret>\n};"), "Name = user.Name")
        assertEquals("var dto = new UserDto\n{\n    Name = user.Name,<caret>\n};", body(multi))
    }

    fun testMapAllWritesEveryConfidentMemberOfThePartner() {
        val text = choose(code("var dto = new UserDto\n{\n    <caret>\n};"), NativeCSharpMappingCompletion.MAP_ALL + "user")
        // in declaration order; Note ← note is another object, Created does not convert, Active has no value
        assertEquals("var dto = new UserDto\n{\n    Id = user.Id,\n    Name = user.Name,\n    Email = user.Profile.Email,<caret>\n};", body(text))
    }

    // ---- the assignment block

    fun testStatementRowsUnderAnAssignmentBlock() {
        val names = names(code("var dto = new UserDto();\ndto.Id = user.Id;\n<caret>"))
        assertTrue(names.toString(), "dto.Name = user.Name;" in names && "dto.Email = user.Profile.Email;" in names)
        // `init` only: not outside an initializer; set already: not again
        assertFalse(names.toString(), names.any { it.startsWith("dto.Note") || it.startsWith("dto.Id = ") })
        assertTrue(names.toString(), names.first().startsWith("dto.") || names.first() == NativeCSharpMappingCompletion.MAP_ALL + "user")
        assertEquals("var dto = new UserDto();\ndto.Id = user.Id;\ndto.Name = user.Name;<caret>", body(choose(code("var dto = new UserDto();\ndto.Id = user.Id;\n<caret>"), "dto.Name = user.Name;")))
    }

    fun testMapAllAsStatements() {
        val text = choose(code("var dto = new UserDto();\ndto.Id = user.Id;\n<caret>"), NativeCSharpMappingCompletion.MAP_ALL + "user")
        assertEquals("var dto = new UserDto();\ndto.Id = user.Id;\ndto.Name = user.Name;\ndto.Email = user.Profile.Email;<caret>", body(text))
    }

    fun testNoAssignmentAboveNoStatementRows() {
        val names = names(code("var dto = new UserDto();\n<caret>"))
        assertFalse(names.toString(), names.any { it.startsWith("dto.") || it.startsWith(NativeCSharpMappingCompletion.MAP_ALL) })
    }

    // ---- the setting

    fun testOffNothing() {
        DotNetSettings.getInstance().mappingCompletion = false
        val names = names(code("var dto = new UserDto { Id = user.Id, <caret> };"))
        assertFalse(names.toString(), names.any { " = " in it || it.startsWith(NativeCSharpMappingCompletion.MAP_ALL) })
        assertTrue(names.toString(), "Name" in names)
    }

    // ---- the names

    fun testSimilarity() {
        assertEquals(1.0, NativeCSharpMappingCompletion.similarity("Name", "Name"))
        assertEquals(0.95, NativeCSharpMappingCompletion.similarity("Name", "name"))
        assertEquals(0.95, NativeCSharpMappingCompletion.similarity("Name", "_name"))
        assertEquals(0.7, NativeCSharpMappingCompletion.similarity("UserId", "Id"))
        assertEquals(0.7, NativeCSharpMappingCompletion.similarity("Id", "UserId"))
        assertEquals(0.7, NativeCSharpMappingCompletion.similarity("Created", "CreatedAt"))
        assertEquals(0.0, NativeCSharpMappingCompletion.similarity("Email", "Phone"))
        // FirstName ↔ LastName: one word of three in common
        assertEquals(0.0, NativeCSharpMappingCompletion.similarity("FirstName", "LastName"))
        // CustomerOrderId ↔ OrderId: two of three
        assertEquals(0.7, NativeCSharpMappingCompletion.similarity("CustomerOrderId", "OrderId"))
        assertTrue(NativeCSharpMappingCompletion.similarity("OrderCustomerId", "CustomerOrderId") > 0.5)
    }
}
