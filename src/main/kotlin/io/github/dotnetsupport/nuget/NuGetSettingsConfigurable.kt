package io.github.dotnetsupport.nuget

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/** Settings | Tools | .NET | NuGet: the groups and the wording of Rider, only the options the plugin has something behind. */
class NuGetSettingsConfigurable : BoundConfigurable("NuGet") {
    private val settings get() = NuGetSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group("Search") {
            row {
                checkBox("Include prerelease").bindSelected(settings::includePrerelease)
                    .comment("The initial state of \"Prerelease\" in the NuGet window, and whether Upgrade Packages in Solution goes to prerelease versions")
            }
        }
        group("Restore") {
            row {
                checkBox("Automatically restore missing packages when necessary").bindSelected(settings::automaticRestore)
                    .comment("A quiet <code>dotnet restore</code> after a project file, Directory.Packages.props or nuget.config has changed; reported to the Log tab of the NuGet window")
            }
            row {
                checkBox("Smart Restore on Build").bindSelected(settings::smartRestore)
                    .comment("Build with <code>--no-restore</code> while project.assets.json is newer than everything that decides the packages")
            }
            row { checkBox("Do not use the HTTP cache").bindSelected(settings::noCache).comment("<code>--no-cache</code>: for a feed where a version was re-published") }
            row { checkBox("Allow interactive authentication").bindSelected(settings::interactive).comment("<code>--interactive</code>: a credential provider may print a device-login link into the log") }
        }
        group("Credential Providers") {
            row { comment("The dotnet CLI uses the credential providers installed in <code>~/.nuget/plugins</code>") }
            row { link("Install the Azure Artifacts Credential Provider...") { BrowserUtil.browse("https://github.com/microsoft/artifacts-credprovider#setup") } }
        }
    }
}
