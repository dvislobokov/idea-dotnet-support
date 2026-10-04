package io.github.dotnetsupport.roslyn

import com.intellij.openapi.project.Project
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatures

/**
 * The side of the server of the switches [CSharpFeatures] (CSHARP_PSI_MIGRATION.md, step 2): every place of this module that answers for a
 * [CSharpFeature] asks [serves] first and stands down when the feature is switched to the plugin's own PSI. Read per call, not when the
 * server starts: a switch takes effect without a restart. Today no feature has a native implementation, so the answer is always true.
 */
object RoslynFeatures {
    fun serves(feature: CSharpFeature, project: Project): Boolean = !CSharpFeatures.native(feature, project)
}
