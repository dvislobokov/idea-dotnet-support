package io.github.dotnetsupport

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpConstructorMembers
import io.github.dotnetsupport.lang.CSharpVariableNames

/** The C# live templates up to Rider's set (COMPLETION_GAPS 3.1): what they expand to, their macros, their descriptions. */
class CSharpLiveTemplatesTest : BasePlatformTestCase() {
    private var files = 0

    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    private fun expand(key: String, text: String): String {
        myFixture.configureByText("Live${files++}.cs", text.replace("<caret>", "$key<caret>"))
        myFixture.type('\t')
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)
        return myFixture.editor.document.text
    }

    fun testRiderTemplatesArePresentWithDescriptions() {
        val templates = TemplateSettings.getInstance().templates.filter { it.groupName == "C#" }.associateBy { it.key }
        val expected = listOf(
            "ctorf", "ctorp", "propdp", "dependencyProperty", "attachedProperty", "indexer", "equals", "iterator", "iterindex", "sim", "psvm", "Attribute",
            "Exception", "checked", "unchecked", "unsafe", "#if", "#region", "nguid", "itli", "itar", "ritar", "sfc", "outv", "out", "asrt", "asrtn",
            "pci", "pcs", "psr", "ear", "~", "namespace", "from", "join", "mbox", "tryf", "hal", "ua", "rta", "ctx",
        )
        assertTrue((expected - templates.keys).toString(), templates.keys.containsAll(expected))
        assertTrue(templates.size >= 73)
        assertTrue(templates.values.all { !it.description.isNullOrBlank() })
        assertEquals("Simple \"for\" loop", templates.getValue("for").description)
        assertEquals("Iterate a IList<T>", templates.getValue("itli").description)
    }

    fun testLoopsNameTheElementAfterTheCollection() {
        val text = expand("itli", "class A { void M() {\n    <caret>\n} }")
        assertTrue(text, text.contains("for (int i = 0; i < list.Count; i++)\n    {\n        var item = list[i];"))
    }

    fun testBlocksAndDirectives() {
        assertTrue(expand("unchecked", "class A { void M() {\n    <caret>\n} }").contains("unchecked\n    {\n"))
        val directive = expand("#if", "class A { void M() {\n<caret>\n} }")
        assertTrue(directive, directive.contains("#if DEBUG\n\n#endif"))
    }

    fun testGuidAndConstructors() {
        val guid = expand("nguid", "class A { string Id = \"<caret>\"; }")
        assertTrue(guid, Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").containsMatchIn(guid))

        val fields = expand("ctorf", "class Order\n{\n    private readonly int _id;\n    private string name;\n    private static int s;\n\n    <caret>\n}\n")
        assertTrue(fields, fields.contains("    public Order(int id, string name)\n    {\n        _id = id;\n        this.name = name;\n    }"))
        val properties = expand("ctorp", "class Order\n{\n    public int Id { get; set; }\n    public string Name { get; init; } = \"\";\n\n    <caret>\n}\n")
        assertTrue(properties, properties.contains("    public Order(int id)\n    {\n        Id = id;\n    }"))
    }

    fun testAspNetCoreTemplates() {
        val action = expand("hal", "class HomeController\n{\n    <caret>\n}\n")
        assertTrue(action, action.contains("[HttpGet]\n    public IActionResult Index()"))
        assertTrue(expand("rta", "class A { object M() {\n    <caret>\n} }").contains("return RedirectToAction(\"Index\");"))
        assertTrue(expand("ua", "class A { object M() {\n    var u = <caret>;\n} }").contains("Url.Action(\"Index\", \"Home\")"))
        assertTrue(expand("ctx", "class A { object M() => <caret>; }").contains("HttpContext."))
    }

    fun testNamesByType() {
        assertEquals(listOf("builder", "stringBuilder"), CSharpVariableNames.forType("StringBuilder"))
        assertEquals("name", CSharpConstructorMembers.Member("_name", "string").parameter)
        assertEquals("this.name = name;", CSharpConstructorMembers.Member("name", "string").assignment)
    }
}
