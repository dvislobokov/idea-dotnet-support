package io.github.dotnetsupport.metrics

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** The levels of the tree, as in the Code Metrics Results of Visual Studio. */
enum class MetricsLevel { SOLUTION, PROJECT, NAMESPACE, TYPE, MEMBER }

/**
 * A row of the results. The metrics of a container come from its children the way Visual Studio counts them: the maintainability
 * index is their average, cyclomatic complexity and lines add up, depth of inheritance is the deepest type, class coupling counts the
 * distinct types used anywhere below ([coupled]).
 */
class MetricsNode(
    val level: MetricsLevel,
    val name: String,
    /** `class`, `record`, `method`, `property`... */
    val kind: String,
    val file: String?,
    val line: Int,
    val maintainability: Int,
    val complexity: Int,
    /** Null for a member: the depth belongs to types. */
    val inheritance: Int?,
    val coupling: Int,
    val lines: Int,
    val executable: Int,
    val children: List<MetricsNode> = emptyList(),
    /** Why a project has no metrics: MSBuild could not tell its sources, the helper failed. */
    val error: String? = null,
    internal val coupled: Set<String> = emptySet(),
) {
    override fun toString(): String = name

    companion object {
        /** Green from 20, yellow from 10, red below: the bands of Visual Studio. */
        fun band(maintainability: Int): Int = when {
            maintainability >= 20 -> 2
            maintainability >= 10 -> 1
            else -> 0
        }

        fun container(level: MetricsLevel, name: String, children: List<MetricsNode>, file: String? = null, error: String? = null): MetricsNode {
            val coupled = children.flatMapTo(HashSet()) { it.coupled }
            return MetricsNode(
                level, name, level.name.lowercase(), file, 0,
                maintainability = if (children.isEmpty()) 100 else Math.round(children.sumOf { it.maintainability }.toDouble() / children.size).toInt(),
                complexity = children.sumOf { it.complexity },
                inheritance = children.mapNotNull { it.inheritance }.maxOrNull() ?: 0,
                coupling = coupled.size,
                lines = children.sumOf { it.lines },
                executable = children.sumOf { it.executable },
                children = children, error = error, coupled = coupled,
            )
        }
    }
}

object CodeMetricsReport {
    const val GLOBAL_NAMESPACE = "<global namespace>"

    /** The output of the helper (metrics/Program.cs) → a project per element, namespaces under it (the global one first), types, members. */
    fun parse(json: String): List<MetricsNode> {
        val root = runCatching { JsonParser.parseString(json.removePrefix("﻿")) as? JsonObject }.getOrNull() ?: return emptyList()
        return root.objects("projects").map { project ->
            val error = project.string("error")
            val types = project.objects("types").map(::type)
            val namespaces = types.groupBy { it.first }.toSortedMap(compareBy<String> { it.isNotEmpty() }.thenBy { it })
                .map { (namespace, list) -> MetricsNode.container(MetricsLevel.NAMESPACE, namespace.ifEmpty { GLOBAL_NAMESPACE }, list.map { it.second }) }
            MetricsNode.container(MetricsLevel.PROJECT, project.string("name").orEmpty(), namespaces, project.string("file"), error)
        }
    }

    private fun type(json: JsonObject): Pair<String, MetricsNode> {
        val members = json.objects("members").map { member ->
            MetricsNode(
                MetricsLevel.MEMBER, member.string("name").orEmpty(), member.string("kind").orEmpty(), member.string("file"), member.int("line"),
                maintainability = member.int("maintainability"), complexity = member.int("complexity"), inheritance = null,
                coupling = member.int("coupling"), lines = member.int("lines"), executable = member.int("executable"),
            )
        }
        val coupled = (json.get("coupled") as? JsonArray)?.mapNotNullTo(HashSet()) { runCatching { it.asString }.getOrNull() }.orEmpty()
        val node = MetricsNode(
            MetricsLevel.TYPE, json.string("name").orEmpty(), json.string("kind").orEmpty(), json.string("file"), json.int("line"),
            maintainability = if (members.isEmpty()) 100 else Math.round(members.sumOf { it.maintainability }.toDouble() / members.size).toInt(),
            complexity = members.sumOf { it.complexity },
            inheritance = json.int("inheritance"),
            coupling = coupled.size,
            lines = json.int("lines"),
            executable = members.sumOf { it.executable },
            children = members, coupled = coupled,
        )
        return json.string("namespace").orEmpty() to node
    }

    /** Above the projects: a solution row when there are several (one project is its own root, as in Visual Studio). */
    fun root(name: String, projects: List<MetricsNode>): MetricsNode =
        projects.singleOrNull() ?: MetricsNode.container(MetricsLevel.SOLUTION, name, projects)

    /** All rows, depth first, for a CSV: the columns of Visual Studio's "Open List in Excel". */
    fun csv(root: MetricsNode): String = buildString {
        appendLine("Scope,Project,Namespace,Type,Member,Maintainability Index,Cyclomatic Complexity,Depth of Inheritance,Class Coupling,Lines of Source Code,Lines of Executable Code")
        fun walk(node: MetricsNode, project: String, namespace: String, type: String) {
            val p = if (node.level == MetricsLevel.PROJECT) node.name else project
            val n = if (node.level == MetricsLevel.NAMESPACE) node.name else namespace
            val t = if (node.level == MetricsLevel.TYPE) node.name else type
            val m = if (node.level == MetricsLevel.MEMBER) node.name else ""
            val scope = if (node.level == MetricsLevel.MEMBER) node.kind.replaceFirstChar(Char::uppercase) else node.level.name.lowercase().replaceFirstChar(Char::uppercase)
            appendLine(listOf(scope, p, n, t, m, node.maintainability, node.complexity, node.inheritance?.toString().orEmpty(), node.coupling, node.lines, node.executable)
                .joinToString(",") { quote(it.toString()) })
            node.children.forEach { walk(it, p, n, t) }
        }
        walk(root, "", "", "")
    }

    private fun quote(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun JsonObject.objects(name: String): List<JsonObject> = (get(name) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(name: String): Int = get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0
}

/**
 * What the helper needs of a project, from MSBuild: `dotnet msbuild -getItem:... -getProperty:...` prints the evaluated items and
 * properties as JSON after running [TARGET], which resolves the references to the assemblies the compiler would get.
 */
object MetricsInput {
    const val TARGET = "FindReferenceAssembliesForReferences"

    fun msBuildArguments(projectFile: File, targetFramework: String?): List<String> = buildList {
        addAll(listOf("msbuild", projectFile.path, "-nologo", "-restore", "-t:$TARGET"))
        addAll(listOf("ReferencePathWithRefAssemblies", "Compile", "Using").map { "-getItem:$it" })
        addAll(listOf("LangVersion", "DefineConstants", "Nullable", "AllowUnsafeBlocks").map { "-getProperty:$it" })
        // a project with several frameworks has no references until one is chosen: the first, as the editor does
        if (targetFramework != null) add("-p:TargetFramework=$targetFramework")
    }

    /** The answer of MSBuild → the project of the helper's input; null when it is not that JSON (an error went to stdout). */
    fun fromMsBuild(json: String, projectFile: File): JsonObject? {
        val root = runCatching { JsonParser.parseString(json.substring(json.indexOf('{').coerceAtLeast(0))) as? JsonObject }.getOrNull() ?: return null
        val items = root.get("Items") as? JsonObject ?: return null
        val properties = root.get("Properties") as? JsonObject
        fun items(name: String): List<JsonObject> = (items.get(name) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        fun property(name: String): String = properties?.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        fun path(item: JsonObject): String? = (item.get("FullPath") ?: item.get("Identity"))?.asString?.let {
            if (File(it).isAbsolute) it else File(projectFile.parentFile, it).path
        }
        return JsonObject().apply {
            addProperty("name", projectFile.nameWithoutExtension)
            addProperty("file", projectFile.path)
            add("sources", array(items("Compile").mapNotNull(::path)))
            add("references", array(items("ReferencePathWithRefAssemblies").mapNotNull(::path)))
            add("defines", array(property("DefineConstants").split(';').map(String::trim).filter(String::isNotEmpty)))
            addProperty("langVersion", property("LangVersion"))
            addProperty("nullable", property("Nullable"))
            addProperty("allowUnsafe", property("AllowUnsafeBlocks").equals("true", ignoreCase = true))
            // `global using static` and aliases are left out: the generated file of a built project has them, and they rarely matter here
            add("usings", array(items("Using").filter { it.get("Alias") == null && it.get("Static")?.asString?.equals("true", true) != true }
                .mapNotNull { it.get("Identity")?.asString }))
        }
    }

    private fun array(values: List<String>): JsonArray = JsonArray().apply { values.forEach(::add) }
}
