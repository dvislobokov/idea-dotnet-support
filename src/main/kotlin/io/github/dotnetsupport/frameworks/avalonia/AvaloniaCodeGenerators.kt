package io.github.dotnetsupport.frameworks.avalonia

import io.github.dotnetsupport.lang.semantic.CSharpCodeGenerators
import io.github.dotnetsupport.lang.semantic.CSharpProjectFacts

/** Avalonia: its targets compile `.axaml` into partial classes (named controls, `InitializeComponent`), which the IDE does not see. */
class AvaloniaCodeGenerators : CSharpCodeGenerators {
    override fun mayGenerateTypes(project: CSharpProjectFacts): Boolean = project.hasPackage("Avalonia") || project.hasFiles("axaml")
}
