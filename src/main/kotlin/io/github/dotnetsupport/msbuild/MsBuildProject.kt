package io.github.dotnetsupport.msbuild

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element

data class PackageReference(val name: String, val version: String?)

enum class TestFramework { XUNIT, NUNIT, MSTEST, TUNIT }

/** `<Compile Remove="Legacy\**" />`: files of [itemType] the project leaves out of the default globs. */
data class ItemRemove(val itemType: String, val glob: MsBuildGlob)

/**
 * An item whose `Include` points outside of the project directory, shown inside the project under [link]
 * (`Link="Shared\Util.cs"`, or `LinkBase="Shared"` plus the path under the wildcard, or just the file name).
 */
data class LinkedItem(val itemType: String, val include: MsBuildGlob, val link: String?, val linkBase: String?)

/**
 * What can be read from an MSBuild file statically, without evaluating it:
 * conditions, imports and property expansion are ignored.
 */
data class MsBuildProject(
    val targetFrameworks: List<String> = emptyList(),
    val packages: List<PackageReference> = emptyList(),
    /** Paths relative to the project directory, with `/` separators. */
    val projectReferences: List<String> = emptyList(),
    val assemblies: List<String> = emptyList(),
    /** `PackageVersion` items of a `Directory.Packages.props` (central package management). */
    val packageVersions: Map<String, String> = emptyMap(),
    val sdk: String? = null,
    val outputType: String? = null,
    val rootNamespace: String? = null,
    val implicitUsings: Boolean = false,
    /** Explicit `<Import Project="...">` paths that do not depend on properties. */
    val imports: List<String> = emptyList(),
    /** `Remove` of the file items: what is taken out of the default globs of the SDK. */
    val removes: List<ItemRemove> = emptyList(),
    /** `DefaultItemExcludes` without the `$(DefaultItemExcludes)` it usually starts with: excluded from every default glob. */
    val defaultItemExcludes: List<MsBuildGlob> = emptyList(),
    /** File items included from outside of the project directory. */
    val linkedItems: List<LinkedItem> = emptyList(),
    /** Path of a file relative to the project (lower case, `/`) -> the file it is nested under, `<DependentUpon>`. */
    val dependentUpon: Map<String, String> = emptyMap(),
    /** Properties of the test runner: `EnableMSTestRunner`, `UseMicrosoftTestingPlatformRunner`, `TestingPlatformDotnetTestSupport`, when true. */
    val testingPlatformProperties: Set<String> = emptySet(),
) {
    val isTestProject: Boolean
        get() = packages.any { it.name.equals("Microsoft.NET.Test.Sdk", ignoreCase = true) || it.name.equals("xunit.v3", ignoreCase = true) || it.name.equals("TUnit", ignoreCase = true) ||
            it.name.startsWith("Microsoft.Testing.Platform", ignoreCase = true) } || sdk.equals("MSTest.Sdk", ignoreCase = true)

    /** The test framework the project uses, by its packages; null when none is known. */
    val testFramework: TestFramework?
        get() = when {
            packages.any { it.name.equals("TUnit", ignoreCase = true) } -> TestFramework.TUNIT
            packages.any { it.name.equals("xunit", ignoreCase = true) || it.name.equals("xunit.v3", ignoreCase = true) || it.name.startsWith("xunit.v3.", ignoreCase = true) } -> TestFramework.XUNIT
            packages.any { it.name.equals("NUnit", ignoreCase = true) } -> TestFramework.NUNIT
            packages.any { it.name.equals("MSTest", ignoreCase = true) || it.name.equals("MSTest.TestFramework", ignoreCase = true) } || sdk.equals("MSTest.Sdk", ignoreCase = true) -> TestFramework.MSTEST
            else -> null
        }

    /**
     * The tests run on Microsoft.Testing.Platform (the test project is an executable) rather than on VSTest: the MSTest runner,
     * xunit.v3 with its runner, TUnit, `MSTest.Sdk`, a direct reference to the platform.
     */
    val usesTestingPlatform: Boolean
        get() = testingPlatformProperties.any { it.equals("EnableMSTestRunner", ignoreCase = true) || it.equals("UseMicrosoftTestingPlatformRunner", ignoreCase = true) } ||
            sdk.equals("MSTest.Sdk", ignoreCase = true) ||
            packages.any { it.name.equals("TUnit", ignoreCase = true) || it.name.equals("Microsoft.Testing.Platform", ignoreCase = true) || it.name.equals("Microsoft.Testing.Platform.MSBuild", ignoreCase = true) }

    /** `TestingPlatformDotnetTestSupport`: `dotnet test` of SDK 8 / 9 hands the arguments after `--` to the platform instead of running VSTest. */
    val testingPlatformDotnetTestSupport: Boolean
        get() = testingPlatformProperties.any { it.equals("TestingPlatformDotnetTestSupport", ignoreCase = true) }

    /** Something `dotnet run` can start: an executable or a web / worker SDK project. */
    val isRunnable: Boolean
        get() = outputType.equals("Exe", ignoreCase = true) || outputType.equals("WinExe", ignoreCase = true) ||
            RUNNABLE_SDKS.any { sdk.orEmpty().startsWith(it, ignoreCase = true) }

    /** Whether the SDK treats `wwwroot` as content: web and Razor SDKs. */
    val isWebSdk: Boolean get() = WEB_SDKS.any { sdk.orEmpty().startsWith(it, ignoreCase = true) }

    companion object {
        private val RUNNABLE_SDKS = listOf("Microsoft.NET.Sdk.Web", "Microsoft.NET.Sdk.Worker", "Microsoft.NET.Sdk.BlazorWebAssembly")
        private val WEB_SDKS = listOf("Microsoft.NET.Sdk.Web", "Microsoft.NET.Sdk.Razor", "Microsoft.NET.Sdk.BlazorWebAssembly")

        /** Items that stand for files of the project, the ones the Solution view shows. */
        val FILE_ITEMS: Set<String> = setOf(
            "Compile", "None", "Content", "EmbeddedResource", "Page", "Resource", "ApplicationDefinition", "AdditionalFiles",
            "AvaloniaResource", "AvaloniaXaml", "MauiXaml", "MauiCss", "MauiImage", "TypeScriptCompile", "Protobuf",
        )

        fun parse(text: CharSequence): MsBuildProject {
            val root = try {
                JDOMUtil.load(text)
            } catch (_: Exception) {
                return MsBuildProject()
            }

            val frameworks = LinkedHashSet<String>()
            val packages = LinkedHashMap<String, PackageReference>()
            val projects = LinkedHashSet<String>()
            val assemblies = LinkedHashSet<String>()
            val versions = LinkedHashMap<String, String>()
            var outputType: String? = null
            var rootNamespace: String? = null
            var implicitUsings = false
            val imports = LinkedHashSet<String>()
            val removes = ArrayList<ItemRemove>()
            val excludes = ArrayList<MsBuildGlob>()
            val linked = ArrayList<LinkedItem>()
            val dependent = LinkedHashMap<String, String>()
            val testingPlatform = LinkedHashSet<String>()

            // Element names are compared without namespace: old-style projects declare the msbuild/2003 one.
            for (element in root.descendants()) {
                if (element.name in FILE_ITEMS) {
                    splitList(element.getAttributeValue("Remove")).mapTo(removes) { ItemRemove(element.name, MsBuildGlob(it)) }
                    for (include in includes(element)) {
                        if (isOutside(include)) linked += LinkedItem(element.name, MsBuildGlob(include), itemMetadata(element, "Link"), itemMetadata(element, "LinkBase"))
                    }
                    val parent = itemMetadata(element, "DependentUpon")
                    if (parent != null) {
                        for (path in includes(element) + splitList(element.getAttributeValue("Update"))) {
                            if (!isOutside(path) && !MsBuildGlob(path).hasWildcards) dependent[MsBuildGlob.normalize(path).lowercase()] = parent.replace('\\', '/').substringAfterLast('/')
                        }
                    }
                }
                when (element.name) {
                    "DefaultItemExcludes" -> splitList(element.textTrim).filter { !it.startsWith("$(") }.mapTo(excludes, ::MsBuildGlob)
                    "EnableMSTestRunner", "UseMicrosoftTestingPlatformRunner", "TestingPlatformDotnetTestSupport" ->
                        if (element.textTrim.equals("true", ignoreCase = true)) testingPlatform += element.name
                    "TargetFramework", "TargetFrameworks" -> frameworks += splitList(element.textTrim)
                    "TargetFrameworkVersion" -> frameworks += splitList(element.textTrim).map { "net" + it.removePrefix("v").replace(".", "") }
                    "OutputType" -> outputType = outputType ?: element.textTrim.takeIf { it.isNotEmpty() }
                    "RootNamespace" -> rootNamespace = rootNamespace ?: element.textTrim.takeIf { it.isNotEmpty() && '$' !in it }
                    "Import" -> element.getAttributeValue("Project")?.takeIf { it.isNotBlank() && '$' !in it }?.let { imports += it.replace('\\', '/') }
                    "ImplicitUsings" -> implicitUsings = element.textTrim.lowercase() in setOf("enable", "true")
                    "PackageReference" -> for (name in includes(element)) {
                        packages[name.lowercase()] = PackageReference(name, itemMetadata(element, "Version"))
                    }
                    "PackageVersion" -> for (name in includes(element)) {
                        itemMetadata(element, "Version")?.let { versions[name.lowercase()] = it }
                    }
                    "ProjectReference" -> projects += includes(element).map { it.replace('\\', '/') }
                    // Include may be a strong name: "Foo, Version=1.0.0.0, Culture=neutral"
                    "Reference" -> assemblies += includes(element).map { it.substringBefore(',').trim() }
                }
            }
            return MsBuildProject(
                frameworks.toList(), packages.values.toList(), projects.toList(), assemblies.toList(), versions,
                sdk = root.getAttributeValue("Sdk")?.substringBefore('/'),
                outputType = outputType,
                rootNamespace = rootNamespace,
                implicitUsings = implicitUsings,
                imports = imports.toList(),
                removes = removes,
                defaultItemExcludes = excludes,
                linkedItems = linked,
                dependentUpon = dependent,
                testingPlatformProperties = testingPlatform,
            )
        }

        /** `..\Shared\X.cs`, `C:\...`, `/...`, `$(SolutionDir)...`: not a path inside the project directory. */
        private fun isOutside(include: String): Boolean {
            val path = MsBuildGlob.normalize(include)
            return path.startsWith("../") || path == ".." || path.startsWith("/") || path.startsWith("$(") || (path.length > 1 && path[1] == ':')
        }

        private fun Element.descendants(): Sequence<Element> =
            sequenceOf(this) + children.asSequence().flatMap { it.descendants() }

        private fun includes(item: Element): List<String> = splitList(item.getAttributeValue("Include"))

        private fun itemMetadata(item: Element, name: String): String? =
            item.getAttributeValue(name)?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.children.firstOrNull { it.name == name }?.textTrim?.takeIf { it.isNotEmpty() }

        private fun splitList(value: String?): List<String> =
            value.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
