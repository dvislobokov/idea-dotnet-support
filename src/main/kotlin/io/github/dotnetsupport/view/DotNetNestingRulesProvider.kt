package io.github.dotnetsupport.view

import com.intellij.ide.projectView.ProjectViewNestingRulesProvider

/**
 * File nesting as in Rider and Visual Studio: generated and companion files sit under the file they belong to.
 * A rule pairs two files with the same base name: `Foo` + parent suffix and `Foo` + child suffix.
 */
class DotNetNestingRulesProvider : ProjectViewNestingRulesProvider {
    override fun addFileNestingRules(consumer: ProjectViewNestingRulesProvider.Consumer) {
        for ((parent, children) in RULES) children.forEach { consumer.addNestingRule(parent, it) }
    }

    companion object {
        // The environment of appsettings.{Environment}.json is free text: the conventional names are listed.
        private val ENVIRONMENTS = listOf("Development", "Staging", "Production", "Local", "Test", "Testing", "Docker")

        val RULES: List<Pair<String, List<String>>> = listOf(
            ".json" to ENVIRONMENTS.map { ".$it.json" },
            ".config" to listOf(".Debug.config", ".Release.config"),
            ".razor" to listOf(".razor.cs", ".razor.css", ".razor.js", ".razor.scss"),
            ".cshtml" to listOf(".cshtml.cs", ".cshtml.css", ".cshtml.js"),
            ".xaml" to listOf(".xaml.cs"),
            ".axaml" to listOf(".axaml.cs"),
            ".resx" to listOf(".Designer.cs"), // suffixes are matched case-insensitively: one spelling is enough
            ".settings" to listOf(".Designer.cs"),
            ".cs" to listOf(".g.cs", ".generated.cs", ".Designer.cs"),
        )
    }
}
