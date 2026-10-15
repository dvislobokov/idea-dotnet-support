package io.github.dotnetsupport.frameworks.xaml

import io.github.dotnetsupport.lang.semantic.CSharpCodeGenerators
import io.github.dotnetsupport.lang.semantic.CSharpProjectFacts

/**
 * The XAML of WPF, MAUI, Uno and UWP: its compiler makes partial classes with the named elements. What WPF's targets make in `obj/` is
 * known once it is there and fresh (0.1.82); MAUI and Uno generate in steps the IDE does not see.
 */
class XamlCodeGenerators : CSharpCodeGenerators {
    override fun mayGenerateTypes(project: CSharpProjectFacts): Boolean =
        project.sdkContains("Maui") || project.hasPackage("Uno.") ||
            !project.buildGeneratedKnown && (project.sdkContains("WindowsDesktop") || project.hasFiles("xaml"))
}
