package io.github.dotnetsupport.razor.lang.syntax

import io.github.dotnetsupport.razor.RazorTestUtil
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentMetadata
import io.github.dotnetsupport.razor.lang.codegen.mvc.ViewComponentMetadata
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** [TagHelperDescriptorJson] gives razordump's text back: every fixture of `testData/codegen-tagHelpers`, and every metadata kind. */
class TagHelperDescriptorJsonTest : TestCase() {

    fun testEveryFixtureRoundTrips() {
        val root = Path.of(RazorTestUtil.testDataPath("codegen-tagHelpers"))
        val fixtures = Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) && it.name.endsWith(".taghelpers.json") }.sorted().toList() }
        assertTrue("fixtures under $root (${fixtures.size})", fixtures.size >= 7)
        for (fixture in fixtures) {
            val text = RazorTestUtil.readText(fixture).replace("\r\n", "\n")
            val descriptors = TagHelperDescriptorJson.read(text)
            assertTrue("$fixture: no descriptors", descriptors.isNotEmpty())
            assertEquals("$fixture", text, TagHelperDescriptorJson.write(descriptors))
        }
    }

    fun testTheMvcTagHelpersOfTheReferences() {
        val fixture = Path.of(RazorTestUtil.testDataPath("codegen-tagHelpers/Mvc.Razor.Extensions/CodeGenerationIntegrationTest/ViewComponentTagHelper.taghelpers.json"))
        val descriptors = TagHelperDescriptorJson.read(RazorTestUtil.readText(fixture))
        val head = descriptors.single { it.name == "Microsoft.AspNetCore.Mvc.Razor.TagHelpers.HeadTagHelper" }
        assertEquals("Microsoft.AspNetCore.Mvc.Razor", head.assemblyName)
        assertEquals("HeadTagHelper", head.typeNameIdentifier)
        assertEquals("head", head.tagMatchingRules.single().tagName)
        assertSame(head, head.tagMatchingRules.single().parent)
        val viewComponent = descriptors.single { it.kind == TagHelperKind.ViewComponent }
        assertEquals(RuntimeKind.ITagHelper, viewComponent.runtimeKind)
        assertEquals("Test", (viewComponent.metadata as ViewComponentMetadata).name)
    }

    fun testEveryMetadataKindAndDiagnostics() {
        val span = SourceSpan("Test.razor", 10, 1, 2, 3, 1, 5)
        val diagnostic = RazorDiagnostic(RazorDiagnosticDescriptor("RZ3000", "Bad {0}.", RazorDiagnosticSeverity.WARNING, 9), span, listOf("thing {1}"))
        val bound = { name: String, metadata: MetadataObject, flags: Int ->
            BoundAttributeDescriptor(
                flags, name, name.uppercase(), TypeNameObject.from("System.String"), "$name-", TypeNameObject.from("bool"), "doc \"$name\"\n\tline", name, "My.Type",
                listOf(BoundAttributeParameterDescriptor(BoundAttributeParameterFlags.IS_ENUM or BoundAttributeParameterFlags.BIND_ATTRIBUTE_GET_SET, "format", "Format", TypeNameObject.from("string"), null, emptyList())),
                metadata, if (name == "a") listOf(diagnostic) else emptyList(),
            )
        }
        val attributes = listOf(
            bound("a", PropertyMetadata("global::A", isChildContent = true, acceptsStringLiteral = true, acceptsAssetPath = true), BoundAttributeFlags.CASE_SENSITIVE or BoundAttributeFlags.IS_WEAKLY_TYPED),
            bound("b", BindMetadata(isFallback = true, valueAttribute = "value", format = "yyyy"), BoundAttributeFlags.HAS_INDEXER or BoundAttributeFlags.IS_ENUM),
            bound("c", TypeParameterMetadata(isCascading = true, constraints = "where T : class", nameWithAttributes = "[A] T"), BoundAttributeFlags.IS_EDITOR_REQUIRED),
            bound("d", ChildContentParameterMetadata.DEFAULT, BoundAttributeFlags.IS_DIRECTIVE_ATTRIBUTE),
            bound("e", MetadataObject.NONE, 0),
        )
        val descriptors = TagHelperCollection(
            listOf(
                TagHelperDescriptor(
                    TagHelperFlags.CASE_SENSITIVE or TagHelperFlags.IS_FULLY_QUALIFIED_NAME_MATCH, TagHelperKind.Component, RuntimeKind.IComponent, "My.Comp<T>", "My",
                    "My.Comp<T>", TypeNameObject.from("My.Comp<T>", "My", "Comp"), "\u0001 doc", "div",
                    listOf(
                        TagMatchingRuleDescriptor(
                            "Comp", "parent", TagStructure.NormalOrSelfClosing, true,
                            listOf(RequiredAttributeDescriptor(RequiredAttributeDescriptorFlags.IS_DIRECTIVE_ATTRIBUTE, "@bind-", RequiredAttributeNameComparison.PrefixMatch, "x", RequiredAttributeValueComparison.SuffixMatch, emptyList())),
                            listOf(diagnostic),
                        ),
                    ),
                    attributes, listOf(AllowedChildTagDescriptor("li", "li", emptyList())), ComponentMetadata(isGeneric = true, acceptsUnmatchedAttributes = true), listOf(diagnostic),
                ),
                descriptorWith(EventHandlerMetadata("Microsoft.AspNetCore.Components.Web.MouseEventArgs"), TagHelperKind.EventHandler),
                descriptorWith(ViewComponentMetadata("Test", TypeNameObject.from("A.TestViewComponent", "A", "TestViewComponent")), TagHelperKind.ViewComponent),
                descriptorWith(AssetPathMetadata("img", "src"), TagHelperKind.AssetPath),
            ),
        )
        val text = TagHelperDescriptorJson.write(descriptors)
        val again = TagHelperDescriptorJson.read(text)
        assertEquals(text, TagHelperDescriptorJson.write(again))
        assertEquals(attributes.map { it.metadata }, again[0].boundAttributes.map { it.metadata })
        assertEquals(ComponentMetadata(isGeneric = true, acceptsUnmatchedAttributes = true), again[0].metadata)
        assertEquals("Bad thing {1}.", again[0].diagnostics.single().message)
        assertEquals(span, again[0].diagnostics.single().span)
        assertEquals(again[0].boundAttributes[0].flags, attributes[0].flags)
        assertTrue(again[0].boundAttributes[0].isIndexerBooleanProperty)
        assertEquals("TestViewComponent", (again[2].metadata as ViewComponentMetadata).originalTypeNameObject.name)
    }

    private fun descriptorWith(metadata: MetadataObject, kind: TagHelperKind) = TagHelperDescriptor(
        0, kind, RuntimeKind.None, kind.name, "A", kind.name, TypeNameObject.from(kind.name), null, null, emptyList(), emptyList(), emptyList(), metadata, emptyList(),
    )
}
