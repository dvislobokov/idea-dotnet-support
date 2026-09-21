package io.github.dotnetsupport.msbuild

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A part of the schema: the SDK itself, or what a tool of the community adds to a project that references it. */
class SchemaFragment(
    val id: String,
    val title: String,
    /** Packages that bring the elements of the fragment; with [sdks] empty too, the fragment is always relevant. */
    val packages: List<String>,
    val sdks: List<String>,
    val url: String?,
) {
    val isAlwaysActive: Boolean get() = packages.isEmpty() && sdks.isEmpty()

    fun isActiveFor(project: MsBuildProject): Boolean = isAlwaysActive ||
        sdks.any { project.sdk.equals(it, ignoreCase = true) } ||
        packages.any { wanted -> project.packages.any { it.name.equals(wanted, ignoreCase = true) } || project.packageVersions.keys.any { it.equals(wanted, ignoreCase = true) } }
}

/**
 * A property, an item or a metadata of an item. [values] are what completion offers; when the entry is not [isOpen],
 * they are all there is, and anything else is reported.
 */
class SchemaEntry(val kind: Kind, val name: String, val doc: String, val values: List<String>, val isOpen: Boolean, val fragment: SchemaFragment, val owner: String? = null) {
    enum class Kind(val title: String) { PROPERTY("property"), ITEM("item"), METADATA("metadata"), ELEMENT("element"), TASK("task") }

    /** Items only. */
    var metadata: List<SchemaEntry> = emptyList()
        internal set

    /** Items whose Include is a file: they take [MsBuildSchema.commonMetadata] too. */
    var isFileItem: Boolean = false
        internal set

    val isClosedEnumeration: Boolean get() = values.isNotEmpty() && !isOpen
}

/**
 * The schema of MSBuild files the plugin completes, documents and checks with. Not an XSD: MSBuild is open (any tag is a
 * legal property or item), so nothing unknown is an error here, and the schema is assembled from fragments
 * (the JSON files of `resources/msbuildSchema`): the SDK, and one per popular tool (Grpc.Tools, coverlet, MinVer, SDK containers, ...),
 * which is preferred in completion when the project references the tool.
 */
object MsBuildSchema {
    /** Order matters: for a name several fragments describe, the first one wins. */
    val FRAGMENT_FILES = listOf("core", "nuget", "packaging", "publish", "analysis", "aspnet", "containers", "testing", "grpc", "efcore", "versioning", "desktop")

    private val BOOLEAN = listOf("true", "false")
    private val STRUCTURE = SchemaFragment("structure", "MSBuild", emptyList(), emptyList(), "https://learn.microsoft.com/visualstudio/msbuild/msbuild-project-file-schema-reference")

    class Loaded(val fragments: List<SchemaFragment>, val properties: Map<String, SchemaEntry>, val items: Map<String, SchemaEntry>, val commonMetadata: List<SchemaEntry>)

    private val loaded: Loaded by lazy {
        load(FRAGMENT_FILES.map { name -> MsBuildSchema::class.java.getResourceAsStream("/msbuildSchema/$name.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("No schema fragment $name") })
    }

    val fragments: List<SchemaFragment> get() = loaded.fragments
    val properties: Collection<SchemaEntry> get() = loaded.properties.values
    val items: Collection<SchemaEntry> get() = loaded.items.values
    val commonMetadata: List<SchemaEntry> get() = loaded.commonMetadata

    // MSBuild names are case-insensitive
    fun property(name: String): SchemaEntry? = loaded.properties[name.lowercase()]
    fun item(name: String): SchemaEntry? = loaded.items[name.lowercase()]

    /** Metadata of [itemName]: its own, the common ones of file items, and the ones every item has. */
    fun metadataOf(itemName: String): List<SchemaEntry> {
        val item = item(itemName)
        return item?.metadata.orEmpty() + if (item == null || item.isFileItem) commonMetadata else emptyList()
    }

    fun metadata(itemName: String, name: String): SchemaEntry? = metadataOf(itemName).firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun load(jsons: List<String>): Loaded {
        val fragments = ArrayList<SchemaFragment>()
        val properties = LinkedHashMap<String, SchemaEntry>()
        val items = LinkedHashMap<String, SchemaEntry>()
        val common = ArrayList<SchemaEntry>()
        for (json in jsons) {
            val root = JsonParser.parseString(json).asJsonObject
            val fragment = SchemaFragment(root.string("id").orEmpty(), root.string("title").orEmpty(), root.strings("packages"), root.strings("sdks"), root.string("url"))
            fragments += fragment
            root.objects("properties").forEach { properties.putIfAbsent(it.string("name").orEmpty().lowercase(), entry(SchemaEntry.Kind.PROPERTY, it, fragment)) }
            root.objects("commonMetadata").mapTo(common) { entry(SchemaEntry.Kind.METADATA, it, fragment) }
            for (itemJson in root.objects("items")) {
                val item = entry(SchemaEntry.Kind.ITEM, itemJson, fragment)
                item.isFileItem = itemJson.get("files")?.asBoolean == true
                item.metadata = itemJson.objects("metadata").map { entry(SchemaEntry.Kind.METADATA, it, fragment, owner = item.name) }
                items.putIfAbsent(item.name.lowercase(), item)
            }
        }
        return Loaded(fragments, properties, items, common)
    }

    private fun entry(kind: SchemaEntry.Kind, json: JsonObject, fragment: SchemaFragment, owner: String? = null): SchemaEntry {
        val values = json.get("values")
        val list = when {
            values == null -> emptyList()
            values.isJsonPrimitive && values.asString == "bool" -> BOOLEAN
            values.isJsonArray -> values.asJsonArray.map { it.asString }
            else -> emptyList()
        }
        return SchemaEntry(kind, json.string("name").orEmpty(), json.string("doc").orEmpty(), list, json.get("open")?.asBoolean == true, fragment, owner)
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.strings(name: String): List<String> = (get(name) as? JsonArray)?.map { it.asString }.orEmpty()
    private fun JsonObject.objects(name: String): List<JsonObject> = (get(name) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    // ---- the structure of a project file: not data, it does not grow with the ecosystem ----

    private fun element(name: String, doc: String) = SchemaEntry(SchemaEntry.Kind.ELEMENT, name, doc, emptyList(), true, STRUCTURE)
    private fun task(name: String, doc: String) = SchemaEntry(SchemaEntry.Kind.TASK, name, doc, emptyList(), true, STRUCTURE)
    private fun attribute(owner: String, name: String, doc: String, values: List<String> = emptyList(), open: Boolean = true) =
        SchemaEntry(SchemaEntry.Kind.METADATA, name, doc, values, open, STRUCTURE, owner)

    val PROJECT_CHILDREN = listOf(
        element("PropertyGroup", "Properties: <Name>value</Name>."),
        element("ItemGroup", "Items: <Kind Include=\"...\" />, the inputs of the build."),
        element("Target", "A named step of the build: tasks run in order."),
        element("Import", "Includes another project file at this place."),
        element("ItemDefinitionGroup", "Default metadata of the items of a kind."),
        element("Choose", "When / Otherwise: alternative groups of properties and items."),
        element("UsingTask", "Declares a task implemented in an assembly."),
        element("Sdk", "An additional project SDK: <Sdk Name=\"...\" Version=\"...\" />."),
        element("ImportGroup", "Imports under one condition."),
        element("ProjectExtensions", "Data for tools; MSBuild ignores it."),
    )
    val CHOOSE_CHILDREN = listOf(element("When", "The groups to use when the Condition is true."), element("Otherwise", "The groups to use when no When has matched."))
    val GROUPS = PROJECT_CHILDREN.filter { it.name in setOf("PropertyGroup", "ItemGroup", "Choose") }
    val TARGET_CHILDREN = PROJECT_CHILDREN.filter { it.name in setOf("PropertyGroup", "ItemGroup") } + listOf(
        element("OnError", "Targets to run when a task of this target fails."),
        task("Message", "Logs a message; Importance=\"high\" to see it at the minimal verbosity."),
        task("Warning", "Logs a warning."),
        task("Error", "Logs an error and stops the build."),
        task("Exec", "Runs a command; a non-zero exit code fails the build."),
        task("Copy", "Copies SourceFiles to DestinationFolder or DestinationFiles."),
        task("Delete", "Deletes Files."),
        task("MakeDir", "Creates Directories."),
        task("RemoveDir", "Removes Directories with their content."),
        task("Touch", "Sets the modification time of Files, creating them with AlwaysCreate."),
        task("WriteLinesToFile", "Writes Lines to File; Overwrite, WriteOnlyWhenDifferent."),
        task("ReadLinesFromFile", "Reads File into an item."),
        task("MSBuild", "Builds Projects with Targets and Properties."),
        task("CallTarget", "Runs Targets from inside this one."),
        task("Move", "Moves SourceFiles."),
        task("ZipDirectory", "Packs SourceDirectory into DestinationFile."),
        task("Unzip", "Unpacks SourceFiles into DestinationFolder."),
        task("DownloadFile", "Downloads SourceUrl into DestinationFolder."),
        task("XmlPoke", "Sets the nodes of XmlInputPath selected by Query to Value."),
        task("XmlPeek", "Reads the nodes of XmlInputPath selected by Query."),
    )

    private val CONDITION = attribute("*", "Condition", "The element applies when the expression is true: '$(Configuration)' == 'Debug'.")
    private val ITEM_ATTRIBUTES = listOf(
        attribute("*", "Include", "What the item is: files (globs), package ids, names; semicolon-separated."),
        attribute("*", "Remove", "Takes items out of the list: files the SDK has included by default."),
        attribute("*", "Update", "Changes the metadata of items that are already in the list."),
        attribute("*", "Exclude", "What an Include glob leaves out."),
        attribute("*", "Label", "A name for tools; MSBuild ignores it."),
    )
    private val ELEMENT_ATTRIBUTES: Map<String, List<SchemaEntry>> = mapOf(
        "project" to listOf(
            attribute("Project", "Sdk", "The project SDK that brings the props and targets.", listOf(
                "Microsoft.NET.Sdk", "Microsoft.NET.Sdk.Web", "Microsoft.NET.Sdk.Worker", "Microsoft.NET.Sdk.Razor", "Microsoft.NET.Sdk.BlazorWebAssembly",
                "Microsoft.NET.Sdk.WindowsDesktop", "Microsoft.Build.NoTargets", "Microsoft.Build.Traversal", "Aspire.AppHost.Sdk", "MSTest.Sdk",
            )),
            attribute("Project", "InitialTargets", "Targets that run before everything else."),
            attribute("Project", "DefaultTargets", "Targets that run when none is asked for."),
            attribute("Project", "TreatAsLocalProperty", "Properties a command line value does not override here."),
        ),
        "target" to listOf(
            attribute("Target", "Name", "The name the target is run and referred to by."),
            attribute("Target", "BeforeTargets", "Run before these targets.", listOf("Build", "BeforeBuild", "CoreCompile", "Restore", "Publish", "Pack", "Clean", "PrepareForBuild")),
            attribute("Target", "AfterTargets", "Run after these targets.", listOf("Build", "AfterBuild", "CoreCompile", "Publish", "Pack", "Clean", "Restore")),
            attribute("Target", "DependsOnTargets", "Targets that have to run first."),
            attribute("Target", "Inputs", "With Outputs: the target is skipped while the outputs are newer than the inputs."),
            attribute("Target", "Outputs", "The files the target produces."),
            attribute("Target", "Returns", "Items the MSBuild task gets back from the target."),
            attribute("Target", "KeepDuplicateOutputs", "Keep duplicates in the returned items.", BOOLEAN, open = false),
        ),
        "import" to listOf(
            attribute("Import", "Project", "The file to import; globs are allowed."),
            attribute("Import", "Sdk", "Import the file from a project SDK."),
        ),
        "propertygroup" to listOf(attribute("PropertyGroup", "Label", "A name for tools; MSBuild ignores it.")),
        "itemgroup" to listOf(attribute("ItemGroup", "Label", "A name for tools; MSBuild ignores it.")),
        "usingtask" to listOf(
            attribute("UsingTask", "TaskName", "The name of the task."),
            attribute("UsingTask", "AssemblyFile", "The assembly with the task."),
            attribute("UsingTask", "TaskFactory", "The factory of an inline task: RoslynCodeTaskFactory."),
        ),
        "message" to listOf(attribute("Message", "Text", "What to log."), attribute("Message", "Importance", "When it is shown.", listOf("high", "normal", "low"), open = false)),
        "exec" to listOf(
            attribute("Exec", "Command", "The command line."), attribute("Exec", "WorkingDirectory", "Where it runs."),
            attribute("Exec", "ContinueOnError", "A failure is a warning.", BOOLEAN), attribute("Exec", "ConsoleToMSBuild", "Capture the output into ConsoleOutput.", BOOLEAN, open = false),
            attribute("Exec", "EnvironmentVariables", "NAME=value pairs, semicolon-separated."),
        ),
        "copy" to listOf(
            attribute("Copy", "SourceFiles", "The files to copy."), attribute("Copy", "DestinationFolder", "Where to."), attribute("Copy", "DestinationFiles", "Where to, file by file."),
            attribute("Copy", "SkipUnchangedFiles", "Skip the files that are the same.", BOOLEAN, open = false),
        ),
        "error" to listOf(attribute("Error", "Text", "The message."), attribute("Error", "Code", "The code of the error.")),
        "warning" to listOf(attribute("Warning", "Text", "The message."), attribute("Warning", "Code", "The code of the warning.")),
    )

    /** Where a tag is, as far as the schema cares. */
    enum class Place { PROJECT, PROPERTY_GROUP, ITEM_GROUP, ITEM, TARGET, CHOOSE, WHEN, OTHER }

    /** [ancestors]: the names of the tags around, the nearest first. */
    fun placeOf(ancestors: List<String>): Place {
        val parent = ancestors.firstOrNull()?.lowercase() ?: return Place.OTHER
        return when (parent) {
            "project" -> Place.PROJECT
            "propertygroup" -> Place.PROPERTY_GROUP
            "itemgroup", "itemdefinitiongroup" -> Place.ITEM_GROUP
            "target" -> Place.TARGET
            "choose" -> Place.CHOOSE
            "when", "otherwise" -> Place.WHEN
            else -> if (ancestors.getOrNull(1)?.lowercase() in setOf("itemgroup", "itemdefinitiongroup")) Place.ITEM else Place.OTHER
        }
    }

    /** The tags that make sense as children of the tag whose [ancestors] (itself first) are given. */
    fun childTags(ancestors: List<String>): List<SchemaEntry> = when (placeOf(ancestors)) {
        Place.PROJECT -> PROJECT_CHILDREN
        Place.PROPERTY_GROUP -> properties.toList()
        Place.ITEM_GROUP -> items.toList()
        Place.ITEM -> metadataOf(ancestors.first())
        Place.TARGET -> TARGET_CHILDREN
        Place.CHOOSE -> CHOOSE_CHILDREN
        Place.WHEN -> GROUPS
        Place.OTHER -> emptyList()
    }

    /** Attributes of [tag], whose [ancestors] start with its parent: Condition everywhere, the item attributes and the metadata on items. */
    fun attributes(tag: String, ancestors: List<String>): List<SchemaEntry> {
        val own = ELEMENT_ATTRIBUTES[tag.lowercase()].orEmpty()
        val item = if (placeOf(ancestors) == Place.ITEM_GROUP) ITEM_ATTRIBUTES + metadataOf(tag) else emptyList()
        return own + item + CONDITION
    }

    /** What a tag or an attribute means where it stands; null for what the schema does not know. */
    fun describeTag(tag: String, ancestors: List<String>): SchemaEntry? = childTags(ancestors).firstOrNull { it.name.equals(tag, ignoreCase = true) }

    fun describeAttribute(tag: String, ancestors: List<String>, attribute: String): SchemaEntry? =
        attributes(tag, ancestors).firstOrNull { it.name.equals(attribute, ignoreCase = true) }

    private val REFERENCE = Regex("""[$@%]\(""")

    /** The message for a value that is not among the closed enumeration of [entry]; null when it is fine or cannot be judged. */
    fun problemWith(entry: SchemaEntry, value: String): String? {
        val text = value.trim()
        // computed values are beyond a static check; lists are checked element by element nowhere: they are all open
        if (!entry.isClosedEnumeration || text.isEmpty() || REFERENCE.containsMatchIn(text)) return null
        if (entry.values.any { it.equals(text, ignoreCase = true) }) return null
        return "'$text' is not a value of ${entry.name}: ${entry.values.joinToString(", ")}"
    }
}
