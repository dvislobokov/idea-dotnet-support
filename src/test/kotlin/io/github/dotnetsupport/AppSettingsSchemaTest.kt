package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection
import io.github.dotnetsupport.appsettings.AppSettingsResponses
import io.github.dotnetsupport.appsettings.AppSettingsSchemaService
import io.github.dotnetsupport.appsettings.AppSettingsSchemas
import io.github.dotnetsupport.appsettings.CodeSchemaSource
import io.github.dotnetsupport.jsonschema.AppSettingsSchemaFiles
import io.github.dotnetsupport.jsonschema.AppSettingsUnknownKeyInspection

/**
 * The schema of appsettings*.json: the answer of DotNetHelper as it came from a real run on `debug-playground/Console`
 * (`resources/appsettings/playground-console.json`), merged with a package schema and the base, and given to the JSON support of the IDE.
 */
class AppSettingsSchemaTest : BasePlatformTestCase() {
    private fun resource(name: String): String = javaClass.getResourceAsStream("/appsettings/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private val answer by lazy { JsonParser.parseString(resource("playground-console.json")) }

    fun testFileNames() {
        assertTrue(AppSettingsSchemas.isAppSettings("appsettings.json"))
        assertTrue(AppSettingsSchemas.isAppSettings("appsettings.Development.json"))
        assertTrue(AppSettingsSchemas.isAppSettings("AppSettings.Staging.json"))
        assertFalse(AppSettingsSchemas.isAppSettings("appsettings.jsonc"))
        assertFalse(AppSettingsSchemas.isAppSettings("launchSettings.json"))
        assertFalse(AppSettingsSchemas.isAppSettings("myappsettings.json"))
    }

    fun testSavedAnswerOfTheHelper() {
        val code = AppSettingsResponses.parse(answer)!!
        assertEquals(
            listOf("Position Configure", "Shop Get", "Shop:Retry Get", "Features:Beta BindConfiguration", "Limits:TimeoutSeconds GetValue",
                "Limits:Greeting indexer", "Cache:Redis comment"),
            code.sections.map { "${it.path} ${it.how}" },
        )
        assertEmpty(code.unresolved)
        val shop = code.schema.getAsJsonObject("properties").getAsJsonObject("Shop")
        assertEquals("Playground.Editor.ShopOptions", shop.get(AppSettingsSchemas.CLOSED_TYPE).asString)
        val properties = shop.getAsJsonObject("properties")
        assertEquals(listOf("Name", "Mode", "Timeout", "Tags", "Prices", "Retry", "Owner"), properties.keySet().toList())
        assertEquals(listOf("Fast", "Cheap", "Balanced"), properties.getAsJsonObject("Mode").getAsJsonArray("enum").map { it.asString })
        assertEquals("Balanced", properties.getAsJsonObject("Mode").get("default").asString)
        assertEquals("00:00:30", properties.getAsJsonObject("Timeout").get("default").asString)
        assertEquals("How orders are shipped.\nFast: The next day.", properties.getAsJsonObject("Mode").get("description").asString)
        assertTrue(properties.getAsJsonObject("Prices").has("additionalProperties"))
        assertEquals("array", properties.getAsJsonObject("Tags").get("type").asString)
        // a record's positional parameters; an int defaults from the call of GetValue
        assertEquals(setOf("Name", "Age"), properties.getAsJsonObject("Owner").getAsJsonObject("properties").keySet())
        val limits = code.schema.getAsJsonObject("properties").getAsJsonObject("Limits").getAsJsonObject("properties")
        assertEquals(30, limits.getAsJsonObject("TimeoutSeconds").get("default").asInt)
        // where the keys come from: a property and its line, for the navigation of step 3
        val mode = code.sources.single { it.path == "Shop:Mode" }
        assertTrue(mode.isProperty)
        assertEquals("debug-playground/Console/Editor/AppSettingsSchema.cs", mode.file)
        assertTrue(mode.line > 0)
        assertTrue(code.sources.any { it.path == "Shop:Retry:Count" })
        assertTrue(code.sources.any { it.path == "Cache:Redis:Endpoints" })
    }

    fun testMergeWithPackagesAndTheBase() {
        val code = AppSettingsResponses.parse(answer)!!.schema
        val pkg = AppSettingsSchemas.parsePackageSchema("system.clientmodel", resource("package-system.clientmodel.json"))!!

        val offline = AppSettingsSchemas.merge(code, listOf(pkg), schemaStore = null)
        val properties = offline.getAsJsonObject("properties")
        assertTrue(properties.has("Shop") && properties.has("Clients") && properties.has("Logging") && properties.has("Kestrel"))
        val definitions = offline.getAsJsonObject("definitions").keySet()
        assertTrue(definitions.toString(), "system.clientmodel.credential" in definitions && "base.logLevel" in definitions)
        assertFalse(offline.toString().contains("\"#/definitions/credential\""))

        // SchemaStore's schema instead of our base, not both: its definitions under its own prefix
        val schemaStore = AppSettingsSchemas.parseSchema(resource("schemastore-appsettings.json"))!!
        val online = AppSettingsSchemas.merge(code, listOf(pkg), schemaStore)
        val onlineDefinitions = online.getAsJsonObject("definitions").keySet()
        assertTrue("schemastore.kestrel" in onlineDefinitions)
        assertFalse(onlineDefinitions.any { it.startsWith("base.") })
        assertEquals("#/definitions/schemastore.logging", online.getAsJsonObject("properties").getAsJsonObject("Logging").get("\$ref").asString)
        assertTrue(online.getAsJsonObject("properties").has("Shop"))
    }

    fun testTwoDescriptionsOfOneObjectOpenIt() {
        val closed = JsonParser.parseString("""{"properties":{"A":{"type":"object","x-dotnet-type":"N.A","properties":{"X":{}}}}}""").asJsonObject
        val other = JsonParser.parseString("""{"properties":{"A":{"type":"object","properties":{"Y":{}},"additionalProperties":false}}}""").asJsonObject
        val merged = AppSettingsSchemas.merge(closed, listOf(AppSettingsSchemas.PackageSchema("p", other)), schemaStore = null)
        val a = merged.getAsJsonObject("properties").getAsJsonObject("A")
        assertEquals(setOf("X", "Y"), a.getAsJsonObject("properties").keySet())
        assertFalse(a.has(AppSettingsSchemas.CLOSED_TYPE))
        assertFalse(a.has("additionalProperties"))
    }

    fun testUnknownKeys() {
        val schema = AppSettingsSchemas.merge(AppSettingsResponses.parse(answer)!!.schema, emptyList(), schemaStore = null)
        assertEquals("Playground.Editor.PositionOptions", AppSettingsSchemas.unknownKey(schema, listOf("Position"), "Colour"))
        assertNull("keys ignore case", AppSettingsSchemas.unknownKey(schema, listOf("position"), "title"))
        assertNull("the root is open", AppSettingsSchemas.unknownKey(schema, emptyList(), "Whatever"))
        assertNull("a section made of a path only is open", AppSettingsSchemas.unknownKey(schema, listOf("Limits"), "Other"))
        assertNull("a dictionary takes any key", AppSettingsSchemas.unknownKey(schema, listOf("Shop", "Prices"), "apple"))
        assertEquals("Playground.Editor.RetryOptions", AppSettingsSchemas.unknownKey(schema, listOf("Shop", "Retry"), "Tries"))
        assertNull(AppSettingsSchemas.unknownKey(schema, listOf("Nothing", "Here"), "X"))
    }

    // ---- the IDE

    private fun dotNetProject(name: String): VirtualFile {
        val projectFile = myFixture.addFileToProject("$name/$name.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>").virtualFile
        val service = AppSettingsSchemaService.getInstance(project)
        service.useSource(CodeSchemaSource { _, _ -> answer }, testRootDisposable)
        service.refresh(projectFile)
        return projectFile
    }

    private fun schemaNames(file: VirtualFile): List<String> = JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file).map { it.name }

    fun testTheProviderTakesOnlyAppSettingsOfDotNetProjects() {
        dotNetProject("AsProvider")
        val appSettings = myFixture.addFileToProject("AsProvider/appsettings.Development.json", "{}").virtualFile
        val other = myFixture.addFileToProject("AsProvider/other.json", "{}").virtualFile
        val outside = myFixture.addFileToProject("NotDotNet/appsettings.json", "{}").virtualFile
        myFixture.configureFromExistingVirtualFile(appSettings) // opening the file is what makes the project followed
        assertEquals(listOf("appsettings-AsProvider.schema.json"), schemaNames(appSettings))
        assertFalse(schemaNames(other).any { it.startsWith("appsettings-") })
        assertFalse(schemaNames(outside).any { it.startsWith("appsettings-") })
    }

    /** Completion at `|` of [text] in an appsettings file of [project]. */
    private fun complete(project: String, fileName: String, text: String): List<String> {
        val file = myFixture.addFileToProject("$project/$fileName", text.replace("|", "")).virtualFile
        myFixture.configureFromExistingVirtualFile(file)
        myFixture.editor.caretModel.moveToOffset(text.indexOf('|'))
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testCompletionOfKeysAndEnumValues() {
        dotNetProject("AsCompletion")
        val keys = complete("AsCompletion", "appsettings.json", "{ \"Shop\": { \"Name\": \"x\", \"|\" } }")
        assertTrue(keys.toString(), keys.containsAll(listOf("Mode", "Timeout", "Tags", "Prices", "Retry", "Owner")))
        assertFalse(keys.toString(), "Secret" in keys || "Total" in keys)

        val values = complete("AsCompletion", "appsettings.Staging.json", "{ \"Shop\": { \"Mode\": | } }")
        assertTrue(values.toString(), values.map { it.trim('"') }.containsAll(listOf("Fast", "Cheap", "Balanced")))
    }

    /** SchemaStore's appsettings schema (a saved copy, as the IDE downloads it) and the sections of the code complete together. */
    fun testSchemaStoreCompletesWithTheCode() {
        dotNetProject("AsSchemaStore")
        val schemaStore = JsonParser.parseString(resource("schemastore-appsettings.json")).asJsonObject
        project.service<AppSettingsSchemaFiles>().useSchemaStore(schemaStore, testRootDisposable)
        val keys = complete("AsSchemaStore", "appsettings.json", "{ \"|\" }")
        assertTrue(keys.toString(), keys.containsAll(listOf("Logging", "Kestrel", "AllowedHosts", "ConnectionStrings", "Shop", "Position")))
        val levels = complete("AsSchemaStore", "appsettings.Production.json", "{ \"Logging\": { \"LogLevel\": { \"Default\": | } } }")
        assertTrue(levels.toString(), levels.map { it.trim('"') }.containsAll(listOf("Information", "Warning")))
    }

    private fun highlight(project: String, text: String): List<HighlightInfo> {
        dotNetProject(project)
        val file = myFixture.addFileToProject("$project/appsettings.json", text).virtualFile
        myFixture.configureFromExistingVirtualFile(file)
        myFixture.enableInspections(JsonSchemaComplianceInspection::class.java, AppSettingsUnknownKeyInspection::class.java)
        return myFixture.doHighlighting()
    }

    private fun List<HighlightInfo>.on(text: String, severity: HighlightSeverity) = filter { it.text == text && it.severity == severity }

    fun testWrongTypeIsAWarning() {
        val infos = highlight("AsTypes", """{ "Position": { "Height": "tall", "Title": "x" }, "Shop": { "Timeout": "soon", "Retry": { "Count": "5" }, "Mode": "Slow" } }""")
        assertNotEmpty(infos.on("\"tall\"", HighlightSeverity.WARNING))
        assertNotEmpty(infos.on("\"soon\"", HighlightSeverity.WARNING))
        assertNotEmpty(infos.on("\"Slow\"", HighlightSeverity.WARNING))
        assertEmpty("the binder reads numbers from strings", infos.on("\"5\"", HighlightSeverity.WARNING))
        assertEmpty(infos.on("\"x\"", HighlightSeverity.WARNING))
    }

    fun testUnknownKeyIsAWeakWarning() {
        val infos = highlight("AsUnknown", """{ "Position": { "Colour": "red", "title": "x" }, "Whatever": 1, "Limits": { "Other": 2 } }""")
        val unknown = infos.on("\"Colour\"", HighlightSeverity.WEAK_WARNING)
        assertEquals(listOf("PositionOptions has no property Colour"), unknown.map { it.description })
        assertEmpty(infos.on("\"Colour\"", HighlightSeverity.WARNING))
        assertEmpty(infos.on("\"title\"", HighlightSeverity.WEAK_WARNING))
        assertEmpty(infos.on("\"Whatever\"", HighlightSeverity.WEAK_WARNING))
        assertEmpty(infos.on("\"Other\"", HighlightSeverity.WEAK_WARNING))
    }
}
