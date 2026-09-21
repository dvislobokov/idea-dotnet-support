package io.github.dotnetsupport.nuget

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.settings.Unavailable
import io.github.dotnetsupport.settings.Unavailable.unavailableCheckBox
import io.github.dotnetsupport.settings.Unavailable.unavailableComboBox
import io.github.dotnetsupport.settings.Unavailable.unavailableLegend

/** Settings | Tools | .NET | NuGet: the page of Rider, option by option, plus what only a CLI-based restore has. */
class NuGetSettingsConfigurable : BoundConfigurable("NuGet") {
    private val settings get() = NuGetSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        unavailableLegend()
        group("Search") {
            row {
                checkBox("Include prerelease").bindSelected(settings::includePrerelease)
                    .comment("The initial state of \"Prerelease\" in the NuGet window, and whether Upgrade Packages in Solution goes to prerelease versions")
            }
            unavailableCheckBox("Include unlisted", false, "The search service of a feed never returns unlisted packages, and the version list of a package always has its unlisted versions")
            unavailableCheckBox("Search in dotnetfeed*.blob feeds", false, "The blob feeds of Microsoft are retired; their packages are on nuget.org and in the Azure Artifacts feeds")
        }
        group("Install and Update") {
            unavailableComboBox("Dependency behavior", "Lowest", Unavailable.OWN_ENGINE)
            unavailableComboBox("File conflict action", "Prompt", Unavailable.PACKAGES_CONFIG)
        }
        group("Uninstall") {
            unavailableCheckBox("Remove dependencies", false, Unavailable.PACKAGES_CONFIG)
            unavailableCheckBox("Force uninstall, even if there are dependencies on it", false, Unavailable.PACKAGES_CONFIG)
        }
        group("Restore") {
            unavailableComboBox("Allow the IDE to restore missing packages", "Always use value from NuGet.Config", "The dotnet CLI reads packageRestore of nuget.config itself")
            row {
                checkBox("Automatically restore missing packages when necessary").bindSelected(settings::automaticRestore)
                    .comment("A quiet <code>dotnet restore</code> after a project file, Directory.Packages.props or nuget.config has changed; reported to the Log tab of the NuGet window")
            }
            unavailableComboBox("PackageReference Restore Engine", "dotnet CLI", Unavailable.OWN_ENGINE)
            row {
                checkBox("Smart Restore on Build").bindSelected(settings::smartRestore)
                    .comment("Build with <code>--no-restore</code> while project.assets.json is newer than everything that decides the packages")
            }
            row { checkBox("Do not use the HTTP cache").bindSelected(settings::noCache).comment("<code>--no-cache</code>: for a feed where a version was re-published") }
            row { checkBox("Allow interactive authentication").bindSelected(settings::interactive).comment("<code>--interactive</code>: a credential provider may print a device-login link into the log") }
        }
        group("Package Management") {
            unavailableComboBox("Default package management format", "PackageReference", Unavailable.PACKAGES_CONFIG)
        }
        group("Credential Providers") {
            unavailableCheckBox("Use bundled Azure credential provider (Experimental)", false, "Nothing is bundled: the dotnet CLI uses the credential providers installed in ~/.nuget/plugins")
            unavailableComboBox("Use credential providers", "dotnet CLI plugins", "Nothing is bundled: the dotnet CLI uses the credential providers installed in ~/.nuget/plugins")
            row { link("Install the Azure Artifacts Credential Provider...") { BrowserUtil.browse("https://github.com/microsoft/artifacts-credprovider#setup") } }
        }
    }
}
