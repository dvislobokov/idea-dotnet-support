package io.github.dotnetsupport.msbuild

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element

data class PackageReference(val name: String, val version: String?)

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
) {
    companion object {
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

            // Element names are compared without namespace: old-style projects declare the msbuild/2003 one.
            for (element in root.descendants()) {
                when (element.name) {
                    "TargetFramework", "TargetFrameworks" -> frameworks += splitList(element.textTrim)
                    "TargetFrameworkVersion" -> frameworks += splitList(element.textTrim).map { "net" + it.removePrefix("v").replace(".", "") }
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
            return MsBuildProject(frameworks.toList(), packages.values.toList(), projects.toList(), assemblies.toList(), versions)
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
