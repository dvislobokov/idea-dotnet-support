package io.github.dotnetsupport.lang

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.registry.Registry

/**
 * The EditorConfig plugin of the platform ships the descriptors of the .NET options (`csharp_*`, `dotnet_*`: language style, formatting,
 * naming rules — `schemas/editorconfig/ms*.json` of `intellij.editorconfig.backend`) and of ReSharper's, but shows them only where the
 * registry says so (`EditorConfigRegistry.shouldSupportDotNet`, off by default: it is Rider that switches it on). With a .NET folder
 * open, they are switched on here: completion and documentation of `csharp_style_var_for_built_in_types` and the rest come with it.
 */
class EditorConfigDotNetSupport : ProjectActivity {
    override suspend fun execute(project: Project) {
        enable()
    }

    companion object {
        private val LOG = logger<EditorConfigDotNetSupport>()
        const val DOTNET_KEY = "editor.config.csharp.support"
        const val RESHARPER_KEY = "editor.config.resharper.support"

        /** True when the .NET options are on after the call (the key is absent without the EditorConfig plugin). */
        fun enable(): Boolean {
            for (key in listOf(DOTNET_KEY, RESHARPER_KEY)) {
                try {
                    val value = Registry.get(key)
                    if (!value.asBoolean()) value.setValue(true)
                } catch (_: Exception) {
                    // MissingResourceException: the key is not declared, the EditorConfig plugin is not there
                    LOG.info("EditorConfig: no registry key $key, the plugin is not there")
                    return false
                }
            }
            return true
        }
    }
}
