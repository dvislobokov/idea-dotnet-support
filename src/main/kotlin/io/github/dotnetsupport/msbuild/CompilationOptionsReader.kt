package io.github.dotnetsupport.msbuild

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import java.util.TreeMap

/**
 * [CompilationOptions] read from the project file and the nearest `Directory.Build.props`, without MSBuild: for a project MsBuildHost has
 * not answered for yet, or cannot (no SDK it runs on, the project is not trusted, the evaluation failed). The order of MSBuild is kept
 * where it matters: `Directory.Build.props`, the props of the SDK (`TRACE`), the project; properties before items; then what the targets
 * of the SDK add (the configuration symbol, the framework symbols of [FrameworkDefaults], the default `LangVersion`).
 *
 * A project of the old format (no SDK) gets its `DefineConstants` of the configuration and its `LangVersion` as written, `Compile`
 * items as listed (wildcards kept as patterns), and nothing implicit: such a project defines what it lists, the default `LangVersion`
 * of .NET Framework is 7.3 (`Microsoft.CSharp.Core.targets`, also the one of Visual Studio 2019 16.3 and newer).
 *
 * Limits, where MsBuildHost is right and this is not: no imports besides the nearest `Directory.Build.props` (not the ones it imports,
 * not `Directory.Build.targets`, not the props of NuGet packages); conditions are `==` / `!=` of strings joined by `and` / `or` with
 * `$(Property)` in them, anything else (`Exists`, property functions, `$([MSBuild]::...)`) is taken for false; property functions in
 * values are not run; `Exclude` of items is ignored; the platform `_OR_GREATER` symbols of `net8.0-windows` are not added.
 */
object CompilationOptionsReader {
    class Input(
        /** The project file, full path with `/`. */
        val projectPath: String,
        val projectText: CharSequence,
        /** The nearest `Directory.Build.props` above the project, if any. */
        val directoryBuildProps: CharSequence? = null,
        val configuration: String = "Debug",
        /** Global properties of the build options; `Configuration` is [configuration]. */
        val globalProperties: Map<String, String> = emptyMap(),
        /** The framework chosen in the toolbar; for a multi-targeted project the first one is taken when it is not one of its frameworks. */
        val selectedFramework: String? = null,
    )

    /** `Microsoft.NET.Sdk.CSharp.props` with `ImplicitUsings`; `System.Net.Http` not for .NET Framework. */
    private val IMPLICIT_USINGS = listOf("System", "System.Collections.Generic", "System.IO", "System.Linq", "System.Net.Http", "System.Threading", "System.Threading.Tasks")
    /** `Microsoft.NET.Sdk.Web/targets/Sdk.Server.props`. */
    private val WEB_USINGS = listOf(
        "System.Net.Http.Json", "Microsoft.AspNetCore.Builder", "Microsoft.AspNetCore.Hosting", "Microsoft.AspNetCore.Http", "Microsoft.AspNetCore.Routing",
        "Microsoft.Extensions.Configuration", "Microsoft.Extensions.DependencyInjection", "Microsoft.Extensions.Hosting", "Microsoft.Extensions.Logging",
    )
    /** `Microsoft.NET.Sdk.Worker/targets/Microsoft.NET.Sdk.Worker.props`. */
    private val WORKER_USINGS = listOf("Microsoft.Extensions.Configuration", "Microsoft.Extensions.DependencyInjection", "Microsoft.Extensions.Hosting", "Microsoft.Extensions.Logging")

    fun read(input: Input): CompilationOptions {
        val projectPath = EvaluatedFiles.normalize(input.projectPath)
        val project = load(input.projectText)
        val props = input.directoryBuildProps?.let(::load)
        val sdk = project?.let(::sdkOf)
        val globals = LinkedHashMap(input.globalProperties).apply { put("Configuration", input.configuration) }

        var evaluation = Evaluation(projectPath, globals, project, props, sdk)
        val frameworks: List<String>
        val active: String?
        if (sdk != null) {
            val many = split(evaluation.property("TargetFrameworks"))
            val one = evaluation.property("TargetFramework").trim()
            frameworks = many.ifEmpty { listOfNotNull(one.ifEmpty { null }) }
            active = when {
                globals.keys.any { it.equals("TargetFramework", ignoreCase = true) } || many.isEmpty() -> one.ifEmpty { null }
                else -> (input.selectedFramework?.let { s -> many.firstOrNull { it.equals(s, ignoreCase = true) } } ?: many.first()).also {
                    // an inner build of a multi-targeted project: evaluated again with the framework as a global property
                    evaluation = Evaluation(projectPath, globals + ("TargetFramework" to it), project, props, sdk)
                }
            }
        } else {
            val framework = FrameworkDefaults.of(evaluation.property("TargetFrameworkIdentifier"), evaluation.property("TargetFrameworkVersion"))
            active = framework?.takeIf { it.identifier == FrameworkDefaults.NET_FRAMEWORK }?.let { "net" + it.version.replace(".", "") }
            frameworks = listOfNotNull(active)
        }
        return evaluation.options(input.configuration, active, frameworks)
    }

    /** The SDK of a project: the `Sdk` attribute, an `<Sdk Name>` or an `<Import Sdk>`; null for a project of the old format. */
    private fun sdkOf(root: Element): String? =
        root.getAttributeValue("Sdk")?.substringBefore(';')?.substringBefore('/')?.trim()?.takeIf { it.isNotEmpty() }
            ?: root.children.firstOrNull { it.name == "Sdk" }?.getAttributeValue("Name")?.substringBefore('/')?.trim()
            ?: root.children.firstOrNull { it.name == "Import" && it.getAttributeValue("Sdk") != null }?.getAttributeValue("Sdk")?.substringBefore('/')?.trim()

    private fun load(text: CharSequence): Element? = try {
        JDOMUtil.load(text)
    } catch (_: Exception) {
        null
    }

    private fun split(value: String): List<String> = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }

    /** One evaluation: the property pass over every file, then the item pass, as MSBuild does them. */
    private class Evaluation(
        private val projectPath: String,
        globals: Map<String, String>,
        private val project: Element?,
        props: Element?,
        private val sdk: String?,
    ) {
        private val properties = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        /** Global and reserved properties: a project cannot change them. */
        private val fixed = java.util.TreeSet(String.CASE_INSENSITIVE_ORDER)
        private val directory = projectPath.substringBeforeLast('/')
        private val compile = ArrayList<String>()
        private val usings = ArrayList<GlobalUsing>()

        init {
            properties += globals
            properties["MSBuildProjectName"] = projectPath.substringAfterLast('/').substringBeforeLast('.')
            properties["MSBuildProjectFile"] = projectPath.substringAfterLast('/')
            properties["MSBuildProjectExtension"] = "." + projectPath.substringAfterLast('.')
            properties["MSBuildProjectDirectory"] = directory
            fixed += properties.keys
            if (sdk != null) {
                setDefault("Configuration", "Debug")
                setDefault("Platform", "AnyCPU")
            }
            // pass 1, properties: Directory.Build.props (from Microsoft.Common.props, at the top of the SDK's props and of an old project),
            // the props of the SDK, the project
            props?.let { properties(it) }
            if (sdk != null) {
                // Microsoft.NET.Sdk.CSharp.props
                set("DefineConstants", property("DefineConstants").let { if (it.isEmpty()) "TRACE" else "$it;TRACE" })
                setDefault("RootNamespace", "")
            }
            project?.let { properties(it) }
            if (sdk != null) afterProject()
            // pass 3, items: their conditions see the final properties
            props?.let { items(it) }
            if (sdk != null) implicitUsings()
            project?.let { items(it) }
        }

        fun property(name: String): String = properties[name].orEmpty()

        private fun set(name: String, value: String) {
            if (name !in fixed) properties[name] = value
        }

        private fun setDefault(name: String, value: String) {
            if (property(name).isEmpty()) set(name, value)
        }

        /** What the targets of the SDK set from the properties of the project (`Microsoft.NET.TargetFrameworkInference.targets`, ...). */
        private fun afterProject() {
            setDefault("RootNamespace", property("MSBuildProjectName"))
            FrameworkDefaults.parse(property("TargetFramework"))?.let { framework ->
                setDefault("TargetFrameworkIdentifier", framework.identifier)
                setDefault("TargetFrameworkVersion", "v" + framework.version)
            }
            set("Language", "C#")
        }

        private fun properties(root: Element) {
            for (element in root.children) {
                when (element.name) {
                    "PropertyGroup" -> if (condition(element)) {
                        for (child in element.children) if (condition(child)) set(child.name, expand(child.textTrim))
                    }
                    "Choose" -> chosen(element)?.let { properties(it) }
                }
            }
        }

        private fun items(root: Element) {
            for (element in root.children) {
                when (element.name) {
                    "ItemGroup" -> if (condition(element)) element.children.filter(::condition).forEach(::item)
                    "Choose" -> chosen(element)?.let { items(it) }
                }
            }
        }

        /** The `When` of a `Choose` whose condition holds, else its `Otherwise`. */
        private fun chosen(choose: Element): Element? =
            choose.children.firstOrNull { it.name == "When" && condition(it) } ?: choose.children.firstOrNull { it.name == "Otherwise" }

        private fun item(element: Element) {
            val include = split(expand(element.getAttributeValue("Include").orEmpty()))
            val remove = split(expand(element.getAttributeValue("Remove").orEmpty()))
            when (element.name) {
                "Compile" -> {
                    compile += include.map(::absolute)
                    val removed = remove.map { MsBuildGlob(absolute(it)) }
                    compile.removeAll { path -> removed.any { it.matches(path) } }
                }
                "Using" -> {
                    include.mapTo(usings) { CompilationOptions.usingOf(it, metadata(element, "Alias"), metadata(element, "Static")) }
                    usings.removeAll { using -> remove.any { it.equals(using.namespace, ignoreCase = true) } }
                }
            }
        }

        private fun implicitUsings() {
            if (!CompilationOptions.isTrue(property("ImplicitUsings"))) return
            val framework = property("TargetFrameworkIdentifier") == FrameworkDefaults.NET_FRAMEWORK
            IMPLICIT_USINGS.filter { !(framework && it == "System.Net.Http") }.mapTo(usings) { GlobalUsing(it) }
            when {
                sdk.equals("Microsoft.NET.Sdk.Web", ignoreCase = true) -> WEB_USINGS.mapTo(usings) { GlobalUsing(it) }
                sdk.equals("Microsoft.NET.Sdk.Worker", ignoreCase = true) -> WORKER_USINGS.mapTo(usings) { GlobalUsing(it) }
            }
        }

        private fun metadata(element: Element, name: String): String? =
            (element.getAttributeValue(name) ?: element.children.firstOrNull { it.name == name }?.textTrim)?.let(::expand)

        private fun absolute(path: String): String {
            val normalized = path.replace('\\', '/')
            return EvaluatedFiles.normalize(if (normalized.startsWith("/") || (normalized.length > 1 && normalized[1] == ':')) normalized else "$directory/$normalized")
        }

        fun options(configuration: String, framework: String?, frameworks: List<String>): CompilationOptions {
            var defines = property("DefineConstants")
            var langVersion = property("LangVersion").trim()
            // a global DefineConstants (`-p:DefineConstants=...`) is what the compiler gets: not even the targets of the SDK change it
            if (sdk != null && "DefineConstants" !in fixed) {
                // Microsoft.NET.Sdk.CSharp.targets, then AddImplicitDefineConstants (Microsoft.NET.Sdk.BeforeCommon.targets)
                if (!isTrue("DisableImplicitConfigurationDefines")) {
                    defines += ";" + configuration.uppercase().replace('-', '_').replace('.', '_').replace(' ', '_')
                }
                if (!isTrue("DisableImplicitFrameworkDefines")) {
                    FrameworkDefaults.implicitDefines(FrameworkDefaults.parse(framework)).forEach { defines += ";$it" }
                }
                if (isTrue("DisableDiagnosticTracing")) defines = defines.split(';').filter { it.trim() != "TRACE" }.joinToString(";")
            }
            if (langVersion.isEmpty()) {
                val parsed = if (sdk != null) FrameworkDefaults.parse(framework)
                else FrameworkDefaults.of(property("TargetFrameworkIdentifier"), property("TargetFrameworkVersion"))
                langVersion = FrameworkDefaults.defaultLangVersion(parsed).orEmpty()
            }
            val explicitCompile = sdk == null || property("EnableDefaultCompileItems").equals("false", ignoreCase = true) ||
                property("EnableDefaultItems").equals("false", ignoreCase = true)
            return CompilationOptions(
                projectPath = projectPath,
                configuration = configuration,
                targetFramework = framework,
                targetFrameworks = frameworks,
                defineConstants = CompilationOptions.symbols(defines),
                langVersion = langVersion.ifEmpty { null },
                nullable = property("Nullable").trim().ifEmpty { null },
                implicitUsings = CompilationOptions.isTrue(property("ImplicitUsings")),
                usings = usings.distinct(),
                rootNamespace = property("RootNamespace").trim().ifEmpty { if (sdk == null) null else property("MSBuildProjectName") },
                compileFiles = if (explicitCompile) compile.distinctBy { it.lowercase() } else null,
                source = CompilationOptions.Source.STATIC,
            )
        }

        private fun isTrue(name: String): Boolean = property(name).trim().equals("true", ignoreCase = true)

        /** `$(Name)` replaced by the property; property functions and item lists are left as they are. */
        fun expand(text: String): String = PROPERTY.replace(text) { property(it.groupValues[1]) }

        private fun condition(element: Element): Boolean = condition(element.getAttributeValue("Condition"))

        /** A condition of MSBuild, the simple kinds; whatever cannot be told is false (see the limits of the reader). */
        fun condition(text: String?): Boolean {
            if (text.isNullOrBlank()) return true
            return OR.split(text.trim()).any { alternative -> AND.split(alternative.trim()).all { term(it.trim()) } }
        }

        private fun term(raw: String): Boolean {
            var text = raw
            while (text.startsWith("(") && text.endsWith(")")) text = text.substring(1, text.length - 1).trim()
            val match = COMPARISON.matchEntire(text) ?: return when (text.lowercase()) {
                "true", "'true'" -> true
                else -> false
            }
            val (left, operator, right) = match.destructured
            val a = expand(left.trim().removeSurrounding("'"))
            val b = expand(right.trim().removeSurrounding("'"))
            if (UNKNOWN.containsMatchIn(a) || UNKNOWN.containsMatchIn(b)) return false
            val equal = a.trim().equals(b.trim(), ignoreCase = true)
            return if (operator == "==") equal else !equal
        }

        companion object {
            private val PROPERTY = Regex("""\$\(([A-Za-z_][\w-]*)\)""")
            private val UNKNOWN = Regex("""[$@%]\(""")
            private val OR = Regex("""\s+or\s+""", RegexOption.IGNORE_CASE)
            private val AND = Regex("""\s+and\s+""", RegexOption.IGNORE_CASE)
            private val COMPARISON = Regex("""^('[^']*'|[^\s=!']+)\s*(==|!=)\s*('[^']*'|[^\s=!']+)$""")
        }
    }
}
