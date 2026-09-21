package io.github.dotnetsupport

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.msbuild.MsBuildAnnotator
import io.github.dotnetsupport.msbuild.MsBuildDocumentationProvider
import io.github.dotnetsupport.msbuild.MsBuildProject
import io.github.dotnetsupport.msbuild.MsBuildSchema
import io.github.dotnetsupport.msbuild.SchemaEntry

class MsBuildSchemaTest : BasePlatformTestCase() {
    fun testFragmentsAreConsistent() {
        assertEquals(MsBuildSchema.FRAGMENT_FILES, MsBuildSchema.fragments.map { it.id })
        assertTrue(MsBuildSchema.properties.size > 250)
        assertTrue(MsBuildSchema.items.size > 40)

        // every fragment alone: names are unique in it, everything is documented, enumerations are not empty strings
        for (name in MsBuildSchema.FRAGMENT_FILES) {
            val fragment = MsBuildSchema.load(listOf(javaClass.getResource("/msbuildSchema/$name.json")!!.readText()))
            val entries = fragment.properties.values + fragment.items.values + fragment.items.values.flatMap { it.metadata } + fragment.commonMetadata
            assertTrue(name, entries.isNotEmpty())
            for (entry in entries) {
                assertTrue("$name: ${entry.name}", entry.name.isNotBlank() && entry.name.all { it.isLetterOrDigit() || it == '_' })
                assertTrue("$name: ${entry.name} has no doc", entry.doc.length > 10)
                assertTrue("$name: ${entry.name}", entry.values.none { it.isBlank() })
            }
        }
        val raw = MsBuildSchema.FRAGMENT_FILES.associateWith { javaClass.getResource("/msbuildSchema/$it.json")!!.readText() }
        for ((name, json) in raw) {
            val names = Regex(""""properties":\s*\[(.*?)\n  ]""", RegexOption.DOT_MATCHES_ALL).find(json)?.groupValues?.get(1).orEmpty()
                .let { Regex("""\{ "name": "(\w+)"""").findAll(it).map { m -> m.groupValues[1].lowercase() }.toList() }
            assertEquals("duplicate properties in $name", names.distinct(), names)
        }
    }

    fun testLookupAndPlaces() {
        assertEquals(listOf("enable", "disable", "warnings", "annotations"), MsBuildSchema.property("nullable")!!.values)
        assertEquals(listOf("true", "false"), MsBuildSchema.property("PublishAot")!!.values)
        assertTrue(MsBuildSchema.property("TargetFramework")!!.isOpen)
        assertNull(MsBuildSchema.property("MyOwnProperty"))

        // Protobuf of Grpc.Tools: its own metadata, then the ones of any file item
        val protobuf = MsBuildSchema.item("Protobuf")!!
        assertEquals("grpc", protobuf.fragment.id)
        assertEquals(listOf("Both", "Server", "Client", "None"), MsBuildSchema.metadata("Protobuf", "GrpcServices")!!.values)
        assertNotNull(MsBuildSchema.metadata("Protobuf", "Link"))
        assertNull("a package is not a file", MsBuildSchema.metadata("PackageReference", "CopyToOutputDirectory"))
        // an item the schema does not know may be anything
        assertNotNull(MsBuildSchema.metadata("MyItem", "CopyToOutputDirectory"))

        assertEquals(MsBuildSchema.Place.PROPERTY_GROUP, MsBuildSchema.placeOf(listOf("PropertyGroup", "Project")))
        assertEquals(MsBuildSchema.Place.ITEM, MsBuildSchema.placeOf(listOf("Protobuf", "ItemGroup", "Project")))
        assertEquals(MsBuildSchema.Place.ITEM_GROUP, MsBuildSchema.placeOf(listOf("ItemGroup", "Target", "Project")))
        assertTrue(MsBuildSchema.childTags(listOf("Target", "Project")).any { it.name == "Exec" && it.kind == SchemaEntry.Kind.TASK })
        assertEquals(listOf("When", "Otherwise"), MsBuildSchema.childTags(listOf("Choose", "Project")).map { it.name })

        val attributes = MsBuildSchema.attributes("Protobuf", listOf("ItemGroup", "Project")).map { it.name }
        assertTrue(attributes.containsAll(listOf("Include", "Update", "Remove", "GrpcServices", "Link", "Condition")))
        assertEquals(listOf("Sdk", "InitialTargets", "DefaultTargets", "TreatAsLocalProperty", "Condition"), MsBuildSchema.attributes("Project", emptyList()).map { it.name })
    }

    fun testFragmentsFollowThePackagesOfTheProject() {
        val grpc = MsBuildSchema.fragments.first { it.id == "grpc" }
        val containers = MsBuildSchema.fragments.first { it.id == "containers" }
        val plain = MsBuildProject.parse("<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        val service = MsBuildProject.parse("<Project Sdk=\"Microsoft.NET.Sdk.Web\"><ItemGroup><PackageReference Include=\"grpc.aspnetcore\" Version=\"2.60.0\"/></ItemGroup></Project>")
        assertFalse(grpc.isActiveFor(plain) || containers.isActiveFor(plain))
        assertTrue(grpc.isActiveFor(service) && containers.isActiveFor(service))
        assertTrue(MsBuildSchema.fragments.first { it.id == "core" }.isActiveFor(plain))
        // central package management: the versions file names the packages
        assertTrue(grpc.isActiveFor(MsBuildProject.parse("<Project><ItemGroup><PackageVersion Include=\"Grpc.Tools\" Version=\"2.60.0\"/></ItemGroup></Project>")))
    }

    fun testValuesAreChecked() {
        val nullable = MsBuildSchema.property("Nullable")!!
        assertNull(MsBuildSchema.problemWith(nullable, " Enable "))
        assertNull("computed", MsBuildSchema.problemWith(nullable, "$(NullableDefault)"))
        assertEquals("'yes' is not a value of Nullable: enable, disable, warnings, annotations", MsBuildSchema.problemWith(nullable, "yes"))
        assertNull("open: any framework may come", MsBuildSchema.problemWith(MsBuildSchema.property("TargetFramework")!!, "net11.0"))
        assertNull("no enumeration", MsBuildSchema.problemWith(MsBuildSchema.property("RootNamespace")!!, "Anything"))
    }

    private fun complete(text: String): List<String> {
        myFixture.configureByText("Completion${counter++}.csproj", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testCompletionInProjectFile() {
        val properties = complete("<Project Sdk=\"Microsoft.NET.Sdk\">\n  <PropertyGroup>\n    <Nullable>enable</Nullable>\n    <<caret>\n  </PropertyGroup>\n</Project>")
        assertTrue(properties.toString(), properties.containsAll(listOf("TargetFramework", "PublishAot", "MinVerTagPrefix")))
        // set already: not offered by the schema again (the platform itself may still offer a tag name it has seen in the file)
        assertTrue(properties.count { it == "Nullable" } <= 1)
        assertFalse("items do not belong to a PropertyGroup", "PackageReference" in properties)

        val items = complete("<Project Sdk=\"Microsoft.NET.Sdk\">\n  <ItemGroup>\n    <<caret>\n  </ItemGroup>\n</Project>")
        assertTrue(items.containsAll(listOf("PackageReference", "ProjectReference", "Protobuf", "InternalsVisibleTo", "Using")))

        val structure = complete("<Project Sdk=\"Microsoft.NET.Sdk\">\n  <<caret>\n</Project>")
        assertTrue(structure.containsAll(listOf("PropertyGroup", "ItemGroup", "Target", "Import")))

        val values = complete("<Project Sdk=\"Microsoft.NET.Sdk\">\n  <PropertyGroup>\n    <DebugType><caret></DebugType>\n  </PropertyGroup>\n</Project>")
        assertEquals(listOf("portable", "embedded", "full", "pdbonly", "none"), values)

        val attributes = complete("<Project Sdk=\"Microsoft.NET.Sdk\">\n  <ItemGroup>\n    <Protobuf Include=\"a.proto\" <caret>/>\n  </ItemGroup>\n</Project>")
        assertTrue(attributes.toString(), attributes.containsAll(listOf("GrpcServices", "ProtoRoot", "Condition", "Update")))
        assertFalse("Include" in attributes)

        val attributeValues = complete("<Project Sdk=\"Microsoft.NET.Sdk\">\n  <ItemGroup>\n    <Protobuf Include=\"a.proto\" GrpcServices=\"<caret>\"/>\n  </ItemGroup>\n</Project>")
        assertEquals(listOf("Both", "Server", "Client", "None"), attributeValues)
    }

    fun testTagIsCompletedAsItIsWritten() {
        myFixture.configureByText("Insert.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <PropertyGroup>\n    <InvariantGlob<caret>\n  </PropertyGroup>\n</Project>")
        myFixture.completeBasic()
        myFixture.checkResult("<Project Sdk=\"Microsoft.NET.Sdk\">\n  <PropertyGroup>\n    <InvariantGlobalization><caret></InvariantGlobalization>\n  </PropertyGroup>\n</Project>")

        myFixture.configureByText("InsertItem.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <ItemGroup>\n    <InternalsVis<caret>\n  </ItemGroup>\n</Project>")
        myFixture.completeBasic()
        myFixture.checkResult("<Project Sdk=\"Microsoft.NET.Sdk\">\n  <ItemGroup>\n    <InternalsVisibleTo Include=\"<caret>\" />\n  </ItemGroup>\n</Project>")
    }

    /** Typed in lower case, completed with Tab or Enter: the name comes out as the schema spells it. */
    fun testCompletionKeepsTheCaseOfTheSchema() {
        fun completed(name: String, body: String, completionChar: Char): String {
            myFixture.configureByText(name, "<Project Sdk=\"Microsoft.NET.Sdk\">\n$body\n</Project>")
            myFixture.completeBasic()
            // several variants: the list is shown, and the first one is chosen with the key
            if (myFixture.lookup != null) myFixture.finishLookup(completionChar)
            return myFixture.editor.document.text
        }
        for (key in listOf(com.intellij.codeInsight.lookup.Lookup.REPLACE_SELECT_CHAR, com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR)) {
            val tag = completed("CaseTag${key.code}.csproj", "<PropertyGroup>\n<invariantglob<caret>\n</PropertyGroup>", key)
            assertTrue(tag, "<InvariantGlobalization></InvariantGlobalization>" in tag)
            val item = completed("CaseItem${key.code}.csproj", "<ItemGroup>\n<internalsvis<caret>\n</ItemGroup>", key)
            assertTrue(item, "<InternalsVisibleTo Include=\"\" />" in item)
            val attribute = completed("CaseAttribute${key.code}.csproj", "<ItemGroup>\n<Protobuf Include=\"a.proto\" grpcserv<caret>/>\n</ItemGroup>", key)
            assertTrue(attribute, "GrpcServices=\"\"" in attribute)
            val value = completed("CaseValue${key.code}.csproj", "<PropertyGroup>\n<OutputType>winex<caret></OutputType>\n</PropertyGroup>", key)
            assertTrue(value, "<OutputType>WinExe</OutputType>" in value)
        }
    }

    fun testWrongValuesAndReferencesAreHighlighted() {
        myFixture.configureByText(
            "Checks.csproj",
            "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <PropertyGroup>\n    <Nullable>yes</Nullable>\n    <OutputType>Exe</OutputType>\n    <MyOwn>anything</MyOwn>\n" +
                "    <OutputPath>$(ArtifactsPath)/bin</OutputPath>\n  </PropertyGroup>\n  <ItemGroup>\n    <Protobuf Include=\"@(Protos)\" GrpcServices=\"Everything\" Link=\"%(Filename)\"/>\n  </ItemGroup>\n</Project>",
        )
        val infos: List<HighlightInfo> = myFixture.doHighlighting()
        val warnings = infos.filter { it.severity == HighlightSeverity.WARNING }.map { it.text to it.description }
        assertEquals(
            listOf("yes" to "'yes' is not a value of Nullable: enable, disable, warnings, annotations", "Everything" to "'Everything' is not a value of GrpcServices: Both, Server, Client, None"),
            warnings,
        )
        fun colored(key: com.intellij.openapi.editor.colors.TextAttributesKey) = infos.filter { it.forcedTextAttributesKey == key }.map { it.text }
        assertEquals(listOf("ArtifactsPath"), colored(MsBuildAnnotator.PROPERTY_REFERENCE))
        assertEquals(listOf("Protos"), colored(MsBuildAnnotator.ITEM_REFERENCE))
        assertEquals(listOf("Filename"), colored(MsBuildAnnotator.METADATA_REFERENCE))
    }

    fun testDocumentation() {
        val html = MsBuildDocumentationProvider.render(MsBuildSchema.metadata("Protobuf", "GrpcServices")!!)
        assertTrue(html, "GrpcServices: metadata of Protobuf" in html)
        assertTrue(html, "<code>Server</code>" in html && "Grpc.Tools" in html && "BUILD-INTEGRATION.md" in html)

        myFixture.configureByText("Docs.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <PropertyGroup>\n    <Publish<caret>Aot>true</PublishAot>\n  </PropertyGroup>\n</Project>")
        val provider = MsBuildDocumentationProvider()
        val target = provider.getCustomDocumentationElement(myFixture.editor, myFixture.file, myFixture.file.findElementAt(myFixture.caretOffset), myFixture.caretOffset)!!
        assertTrue(provider.generateDoc(target, null)!!.contains("ahead of time"))
    }

    private companion object {
        var counter = 0
    }
}
