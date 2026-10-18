package io.github.dotnetsupport

import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpPropertyDeclaration
import io.github.dotnetsupport.frameworks.razor.RazorFile
import io.github.dotnetsupport.frameworks.razor.RazorLanguage
import com.intellij.psi.search.GlobalSearchScope
import io.github.dotnetsupport.frameworks.razor.RazorMarkupFindUsagesHandlerFactory
import io.github.dotnetsupport.frameworks.razor.RazorTagHelperEditor
import io.github.dotnetsupport.frameworks.razor.RazorTagHelperUsages
import io.github.dotnetsupport.frameworks.razor.RazorTagHelperGotoDeclarationHandler
import io.github.dotnetsupport.frameworks.razor.RazorTagHelperService
import io.github.dotnetsupport.frameworks.razor.RazorTagHelpers
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.razor.lang.syntax.TagHelperKind

/**
 * RAZOR_PLAN.md, R5e part 3: the tag helpers of `.cshtml` — MVC's (`asp-*` of a stand-in of Microsoft.AspNetCore.Mvc.TagHelpers:
 * `src/test/resources/index/AspNetCoreMvc.dnix`, tools/index-fixture/mvc) and the project's own, in scope by `@addTagHelper` of
 * `_ViewImports.cshtml`: completion of their tags and attributes, Go to Declaration, Find Usages and rename.
 */
class RazorCshtmlTagHelpersTest : BasePlatformTestCase() {
    private lateinit var projectFile: VirtualFile
    private var round = 0
    private lateinit var dir: String

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        dir = "Mvc${round++}${getTestName(false)}"
        val indexes = listOf("System.Runtime", "AspNetCoreMvc").map(CSharpUsingTypesTest::fixture)
        CSharpSemanticEnvironment.setAssembliesForTests { AssemblyIndexSet(indexes, "Shop") }
        projectFile = myFixture.addFileToProject("$dir/Shop.csproj",
            "<Project Sdk=\"Microsoft.NET.Sdk.Web\"><PropertyGroup><TargetFramework>net10.0</TargetFramework><RootNamespace>Shop</RootNamespace></PropertyGroup></Project>").virtualFile
        AssemblyIndexService.getInstance(project).set(projectFile, indexes, isComplete = true)
        myFixture.addFileToProject("$dir/Views/_ViewImports.cshtml", "@addTagHelper *, Microsoft.AspNetCore.Mvc.TagHelpers\n@addTagHelper *, Shop\n")
        myFixture.addFileToProject("$dir/TagHelpers/EmailTagHelper.cs", """
            using Microsoft.AspNetCore.Razor.TagHelpers;
            namespace Shop.TagHelpers;
            [HtmlTargetElement("email")]
            public class EmailTagHelper : TagHelper
            {
                public string? MailTo { get; set; }
                [HtmlAttributeName(DictionaryAttributePrefix = "header-")] public System.Collections.Generic.IDictionary<string, string> Headers { get; set; } = null!;
            }
            [HtmlTargetElement(Attributes = "tooltip")]
            public class TooltipTagHelper : TagHelper
            {
                [HtmlAttributeName("tooltip")] public string? Text { get; set; }
            }
            """.trimIndent())
        myFixture.addFileToProject("$dir/Models/Person.cs", "namespace Shop.Models;\npublic class Person\n{\n    public string Name { get; set; } = \"\";\n}\n")
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

    private fun razor(file: PsiFile): RazorFile = file.viewProvider.getPsi(RazorLanguage) as RazorFile

    /** A view of the project in the editor, the caret at `|`, after the project's descriptors are discovered. */
    private fun open(name: String, text: String): RazorFile {
        val offset = text.indexOf('|').coerceAtLeast(0)
        val file = myFixture.addFileToProject("$dir/Views/Home/$name", text.replace("|", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(offset)
        val service = RazorTagHelperService.getInstance(project)
        service.tagHelpers(projectFile)
        PlatformTestUtil.waitWithEventsDispatching("the tag helpers of the project", { service.tagHelpers(projectFile) != null }, 60)
        return razor(file)
    }

    private fun completions(): List<String> = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()

    fun testTheTagHelpersOfMvcAndOfTheProject() {
        val found = RazorTagHelpers.discover(project, projectFile)
        val anchor = found.first { it.name == "Microsoft.AspNetCore.Mvc.TagHelpers.AnchorTagHelper" }
        assertEquals(TagHelperKind.ITagHelper, anchor.kind)
        assertTrue(anchor.boundAttributes.map { it.name }.containsAll(listOf("asp-action", "asp-controller", "asp-all-route-data")))
        val email = found.first { it.name == "Shop.TagHelpers.EmailTagHelper" }
        assertEquals(listOf("mail-to", "headers"), email.boundAttributes.map { it.name })
        // in scope of a view by `@addTagHelper` of _ViewImports.cshtml
        val view = open("Index.cshtml", "<email mail-to=\"a\" />\n|")
        val context = RazorTagHelperEditor.context(view)
        assertNotNull("the tag helper context of a view", context)
        assertTrue(context!!.tagHelpers.any { it.name == "Shop.TagHelpers.EmailTagHelper" } && context.tagHelpers.any { it.name.endsWith("AnchorTagHelper") })
    }

    fun testCompletionOfTheAttributesOfMvc() {
        open("Anchor.cshtml", "<a asp-|>x</a>\n")
        val anchor = completions()
        assertTrue(anchor.toString(), anchor.containsAll(listOf("asp-action", "asp-controller", "asp-area")))
        open("Input.cshtml", "@model Shop.Models.Person\n<input asp-| />\n")
        assertTrue("asp-for", "asp-for" in completions())
        open("Form.cshtml", "<form asp-|></form>\n")
        assertTrue("the attributes of a tag helper of every form", completions().containsAll(listOf("asp-action", "asp-antiforgery")))
    }

    fun testCompletionOfTheTagsAndAttributesOfTheProjectsTagHelpers() {
        open("Tags.cshtml", "<em|\n")
        val tags = completions() + listOfNotNull(myFixture.editor.document.text.takeIf { "<email" in it }?.let { "email" })
        assertTrue(tags.toString(), "email" in tags || "environment" in tags)
        // an element HTML knows is offered once: a tag helper of it by its attributes adds no second item (`<embed asp-…>`, `<a asp-…>`)
        open("Tags1.cshtml", "<a|\n")
        val anchors = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(anchors.toString(), anchors.count { it == "a" } <= 1)
        // an attribute another element of the tag in the file has is offered once too: HTML adds it to an unknown tag without the type
        open("Tags1a.cshtml", "<email mail-to=\"a\"></email>\n<email m| ></email>\n")
        val again = myFixture.completeBasic()?.map { it.lookupString } ?: listOf("mail-to")
        assertEquals(again.toString(), 1, again.count { it == "mail-to" })
        open("Tags2.cshtml", "<email m| />\n")
        val attributes = completions() + listOfNotNull(myFixture.editor.document.text.takeIf { "mail-to" in it }?.let { "mail-to" })
        assertTrue(attributes.toString(), "mail-to" in attributes)
        open("Tags3.cshtml", "<div to| ></div>\n")
        val any = completions() + listOfNotNull(myFixture.editor.document.text.takeIf { "tooltip" in it }?.let { "tooltip" })
        assertTrue("an attribute of a tag helper of every element: $any", "tooltip" in any)
    }

    /** Found in the WSL sandbox (0.1.281): HTML's inspections said "Unknown html tag email", "Attribute tooltip is not allowed here", "Missing associated label" on `<input asp-for>`. */
    fun testTheTagsAndAttributesOfTagHelpersAreNoUnknownHtml() {
        val tools = ArrayList<com.intellij.codeInspection.LocalInspectionTool>()
        tools += com.intellij.codeInspection.htmlInspections.HtmlUnknownTagInspection()
        tools += com.intellij.codeInspection.htmlInspections.HtmlUnknownAttributeInspection()
        // of the html-tools plugin, when the IDE of the tests has it
        com.intellij.codeInspection.LocalInspectionEP.LOCAL_INSPECTION.extensionList.firstOrNull { it.shortName == "HtmlFormInputWithoutLabel" }
            ?.instantiateTool()?.let { tools += it as com.intellij.codeInspection.LocalInspectionTool }
        myFixture.enableInspections(*tools.toTypedArray())
        open("Clean.cshtml", "@model Shop.Models.Person\n<email mail-to=\"a\">x</email>\n<div tooltip=\"t\"></div>\n<a asp-controller=\"Home\">x</a>\n<form><input asp-for=\"Name\" /><input name=\"plain\" /></form>\n<foo bar=\"1\"></foo>\n|")
        val problems = myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.WARNING).filter { it.severity >= com.intellij.lang.annotation.HighlightSeverity.WARNING }
            .map { myFixture.editor.document.text.substring(it.startOffset, it.endOffset) + ": " + it.description }
        // the tag helpers' tags and attributes are known, an input a tag helper renders has its label; plain HTML is checked as before
        assertEquals(problems.toString(), problems.filter { it.startsWith("foo") || it.startsWith("bar") }.size + problems.count { it.startsWith("input") }, problems.size)
        assertTrue(problems.toString(), problems.any { it.startsWith("foo: Unknown html tag") })
        if (tools.size == 3) assertEquals(problems.toString(), 1, problems.count { it.startsWith("input") })
    }

    /** R5e: the tags of tag helpers in the color of a class, their bound attributes in the color of a property; HTML's stay HTML's. */
    fun testTheColorsOfTagHelpers() {
        open("Colors.cshtml", "<email mail-to=\"a\">x</email>\n<div tooltip=\"t\" class=\"c\"></div>\n<a asp-controller=\"Home\" href=\"/\">x</a>\n<p>plain</p>\n|")
        val text = myFixture.editor.document.text
        val colored = myFixture.doHighlighting().filter { it.forcedTextAttributesKey != null }
            .map { text.substring(it.startOffset, it.endOffset) to it.forcedTextAttributesKey.externalName }
        val elements = colored.filter { it.second == "RAZOR_COMPONENT_ELEMENT" }.map { it.first }
        val attributes = colored.filter { it.second == "RAZOR_COMPONENT_ATTRIBUTE" }.map { it.first }
        assertEquals(colored.toString(), listOf("email", "email", "a", "a"), elements)
        assertEquals(colored.toString(), listOf("mail-to", "tooltip", "asp-controller"), attributes)
    }

    /**
     * The value of `asp-for` is C# to the compiler (`__model => __model.Name`, a source mapping over `Name`): a place of the injection, so
     * the members of the model complete and resolve there (found in the WSL sandbox, 0.1.281: the value was markup only, no completion).
     */
    fun testTheValueOfAspForIsCSharp() {
        val view = open("Model.cshtml", "@model Shop.Models.Person\n<input asp-for=\"Name\" />\n|")
        val places = io.github.dotnetsupport.frameworks.razor.RazorCSharpDocument.places(view)
        val name = places.firstOrNull { it.range.substring(it.host.text) == "Name" && it.host.text == "Name" }
        val generated = io.github.dotnetsupport.frameworks.razor.RazorCSharpDocument.generate(view)!!
        val valueAt = view.text.indexOf("Name\" />")
        val chain = generateSequence(view.findElementAt(valueAt)) { it.parent }.take(5).joinToString(" < ") { it.javaClass.simpleName + "(" + it.node.elementType + ")" }
        val mappings = generated.mappings.joinToString { "${it.originalSpan.absoluteIndex}+${it.originalSpan.length}" }
        assertNotNull("places ${places.map { it.range.substring(it.host.text) }}, mappings $mappings, value at $valueAt: $chain\n${generated.code.take(3000)}", name)
        assertTrue("`__model => __model.` before it: " + name!!.prefix.takeLast(120), name.prefix.trimEnd().lines().takeLast(3).any { it.endsWith("__model => __model.") })
        val at = myFixture.editor.document.text.indexOf("Name\" />")
        // the injection was made when the editor opened, before the descriptors were ready: the service drops it when they are
        // (the platform would keep it until an edit of the file)
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val manager = com.intellij.lang.injection.InjectedLanguageManager.getInstance(project)
        val injected = manager.findInjectedElementAt(myFixture.file, at)
        assertEquals("C#", injected?.language?.displayName)
        // and the C# of the fragment is the model's member: completion in the value offers the model's members
        val fragment = com.intellij.psi.util.PsiTreeUtil.getParentOfType(injected, io.github.dotnetsupport.lang.CSharpFile::class.java)!!
        val lambda = Regex("""__model => __model\.\s*(#[^\n]*\n\s*)*Name""")
        assertTrue(fragment.text.substringAfter("ExecuteAsync").take(2500), lambda.containsMatchIn(fragment.text))
        assertEquals("the member's name is the file's text", "Name", injected!!.text)
        // completion inserts its dummy identifier with a space after it (`N⟨dummy⟩ `): the compiler maps the value with the space, the
        // literal of the tree ends before it — the host is the value without the space (live: no completion in the value, 0.1.281)
        val spaced = open("Spaced.cshtml", "@model Shop.Models.Person\n<input asp-for=\"Name \" />\n|")
        val spacedPlaces = io.github.dotnetsupport.frameworks.razor.RazorCSharpDocument.places(spaced)
        assertTrue(spacedPlaces.map { it.range.substring(it.host.text) }.toString(), spacedPlaces.any { it.range.substring(it.host.text) == "Name" })
    }

    private fun targets(word: String, occurrence: Int = 0): List<PsiElement> {
        var at = -1
        repeat(occurrence + 1) { at = myFixture.editor.document.text.indexOf(word, at + 1) }
        val element = myFixture.file.viewProvider.findElementAt(at, HTMLLanguage.INSTANCE)
        return RazorTagHelperGotoDeclarationHandler().getGotoDeclarationTargets(element, at, myFixture.editor)?.toList().orEmpty()
    }

    fun testGoToDeclarationOfTheProjectsTagHelpers() {
        open("Navigate.cshtml", "<email mail-to=\"a\" />\n<div tooltip=\"t\"></div>\n|")
        assertEquals("EmailTagHelper", (targets("email").single() as CSharpBaseTypeDeclaration).identifier?.text)
        assertEquals("MailTo", (targets("mail-to").single() as CSharpPropertyDeclaration).identifier?.text)
        assertEquals("an attribute of a tag helper of every element", "Text", (targets("tooltip").single() as CSharpPropertyDeclaration).identifier?.text)
        // MVC's: the property in the view of the assembly
        open("Navigate2.cshtml", "<a asp-controller=\"Home\">x</a>\n|")
        val controller = targets("asp-controller").single().text
        assertTrue(controller, controller.contains("Controller { get; set; }"))
    }

    private val usages = "<email mail-to=\"a\" />\n<email mail-to=\"b\" header-subject=\"s\">x</email>\n<email>no attribute</email>\n<div tooltip=\"t\"></div>\n<a asp-controller=\"Home\">x</a>\n<a href=\"/\">plain</a>\n|"

    private fun texts(places: List<RazorTagHelperUsages.Place>): List<String> = places.map { it.range.substring(it.file.text) }

    fun testFindUsagesOfTheProjectsTagHelpers() {
        open("Usages.cshtml", usages)
        val scope = GlobalSearchScope.projectScope(project)
        val email = targets("email").single()
        val tags = RazorTagHelperUsages.places(email, scope)
        assertEquals("the start and end tags of the tag helper's elements", listOf("email", "email", "email", "email", "email"), texts(tags))
        assertTrue("its tags are no names of the C#: not renamed with the class", tags.none { it.renamable })
        val mailTo = targets("mail-to").single()
        val attributes = RazorTagHelperUsages.places(mailTo, scope)
        assertEquals(listOf("mail-to", "mail-to"), texts(attributes))
        assertEquals("a rename writes the new name in HTML case", "send-to", attributes.first().spelling!!.invoke("SendTo"))
        val text = targets("tooltip").single()
        val tooltip = RazorTagHelperUsages.places(text, scope)
        assertEquals(listOf("tooltip"), texts(tooltip))
        assertFalse("named by [HtmlAttributeName]: the attribute keeps its name", tooltip.single().renamable)
        // a dictionary by the attributes of its prefix (`asp-route-id` of RouteValues): usages, not renamed
        val headers = RazorTagHelperUsages.places(com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(email.containingFile, CSharpPropertyDeclaration::class.java).first { it.identifier?.text == "Headers" }, scope)
        assertEquals(listOf("header-subject"), texts(headers))
        assertFalse(headers.single().renamable)
        // Alt+F7 on a tag and an attribute of the markup: the class and the property
        val factory = RazorMarkupFindUsagesHandlerFactory()
        fun markup(word: String): PsiElement = myFixture.file.viewProvider.findElementAt(myFixture.editor.document.text.indexOf(word), HTMLLanguage.INSTANCE)!!.parent
        assertTrue(factory.canFindUsages(markup("email")))
        assertTrue(factory.canFindUsages(markup("mail-to")))
        assertTrue("MVC's anchor with an asp- attribute", factory.canFindUsages(markup("asp-controller")))
        assertFalse("an anchor without one is HTML", factory.canFindUsages(markup("href").parent))
    }

    /** RAZOR_PLAN.md, R4: the usages lens over a tag helper and its property counts the markup (the WSL sandbox: "EmailTagHelper — no usages"). */
    fun testTheUsagesLensCountsTheMarkup() {
        open("Lens.cshtml", "<email mail-to=\"a\">x</email>\n<email mail-to=\"b\" />\n|")
        val csharp = myFixture.psiManager.findFile(myFixture.findFileInTempDir("$dir/TagHelpers/EmailTagHelper.cs")!!)!!
        val type = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(csharp, CSharpBaseTypeDeclaration::class.java).first { it.identifier?.text == "EmailTagHelper" }
        val mailTo = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(csharp, CSharpPropertyDeclaration::class.java).first { it.identifier?.text == "MailTo" }
        val session = io.github.dotnetsupport.lang.semantic.CSharpSemanticSession(project)
        val markup = io.github.dotnetsupport.frameworks.razor.RazorMarkupUsages()
        assertEquals("the start and end names of the two elements", "3 usages in markup", markup.usageNote(type, session))
        assertEquals("2 usages in markup", markup.usageNote(mailTo, session))
        // a tag helper of every element: the elements it applies to
        open("Lens2.cshtml", "<div tooltip=\"a\"></div>\n<p>none</p>\n|")
        val tooltip = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(csharp, CSharpBaseTypeDeclaration::class.java).first { it.identifier?.text == "TooltipTagHelper" }
        assertEquals("1 usage in markup", markup.usageNote(tooltip, session))
        // a class no descriptor names: not searched
        val person = myFixture.psiManager.findFile(myFixture.findFileInTempDir("$dir/Models/Person.cs")!!)!!
        assertNull(markup.usageNote(com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(person, CSharpBaseTypeDeclaration::class.java).first(), session))
    }

    /** Alt+F7 on the property in its `.cs` before any view was opened: the descriptors of its project are discovered for the search. */
    fun testFindUsagesFromTheCSharpBeforeAnyViewIsOpen() {
        myFixture.addFileToProject("$dir/Views/Home/Closed.cshtml", "<email mail-to=\"a\" />\n")
        val csharp = myFixture.psiManager.findFile(myFixture.findFileInTempDir("$dir/TagHelpers/EmailTagHelper.cs")!!)!!
        val mailTo = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(csharp, CSharpPropertyDeclaration::class.java).first { it.identifier?.text == "MailTo" }
        val found = RazorTagHelperUsages.places(mailTo, GlobalSearchScope.projectScope(project))
        assertEquals(listOf("Closed.cshtml"), found.map { it.file.name })
    }

    fun testRenameAPropertyOfATagHelperFromItsAttribute() {
        open("Rename.cshtml", "<email mail-to=\"a\" />\n<email mail-to=\"b\" />\n|")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("mail-to") + 2)
        myFixture.renameElementAtCaretUsingHandler("SendTo")
        assertEquals("<email send-to=\"a\" />\n<email send-to=\"b\" />\n", myFixture.editor.document.text)
        val csharp = myFixture.findFileInTempDir("$dir/TagHelpers/EmailTagHelper.cs")!!
        assertTrue(com.intellij.openapi.vfs.VfsUtilCore.loadText(csharp).let { it.contains("SendTo { get; set; }") || com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(csharp)!!.text.contains("SendTo { get; set; }") })
    }
}
