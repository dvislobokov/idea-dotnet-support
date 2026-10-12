package io.github.dotnetsupport.nuget

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.DotNetBundle

/** Settings | .NET | NuGet: the groups and the wording of Rider, only the options the plugin has something behind. */
class NuGetSettingsConfigurable : BoundConfigurable(DotNetBundle.message("page.nuget")) {
    private val settings get() = NuGetSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group(DotNetBundle.message("nuget.search")) {
            row {
                checkBox(DotNetBundle.message("nuget.prerelease")).bindSelected(settings::includePrerelease)
                    .comment(DotNetBundle.message("nuget.prerelease.comment"))
            }
        }
        group(DotNetBundle.message("nuget.restore")) {
            row {
                checkBox(DotNetBundle.message("nuget.automatic")).bindSelected(settings::automaticRestore)
                    .comment(DotNetBundle.message("nuget.automatic.comment"))
            }
            row {
                checkBox(DotNetBundle.message("nuget.smart")).bindSelected(settings::smartRestore)
                    .comment(DotNetBundle.message("nuget.smart.comment"))
            }
            row { checkBox(DotNetBundle.message("nuget.noCache")).bindSelected(settings::noCache).comment(DotNetBundle.message("nuget.noCache.comment")) }
            row { checkBox(DotNetBundle.message("nuget.interactive")).bindSelected(settings::interactive).comment(DotNetBundle.message("nuget.interactive.comment")) }
        }
        group(DotNetBundle.message("nuget.credentials")) {
            row { comment(DotNetBundle.message("nuget.credentials.comment")) }
            row { link(DotNetBundle.message("nuget.credentials.azure")) { BrowserUtil.browse("https://github.com/microsoft/artifacts-credprovider#setup") } }
        }
    }
}
