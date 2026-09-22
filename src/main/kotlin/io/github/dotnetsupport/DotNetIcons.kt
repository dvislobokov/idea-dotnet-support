package io.github.dotnetsupport

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object DotNetIcons {
    @JvmField val CSharp: Icon = load("csharp")

    /** Glyph of the "New Class/Interface" popup. */
    @JvmField val CSharpType: Icon = load("csharpType")
    @JvmField val FSharp: Icon = load("fsharp")
    @JvmField val VisualBasic: Icon = load("vb")

    @JvmField val Solution: Icon = load("solution")
    @JvmField val Project: Icon = load("project")
    @JvmField val ProjectFSharp: Icon = load("projectFSharp")
    @JvmField val ProjectVisualBasic: Icon = load("projectVb")
    @JvmField val MsBuild: Icon = load("msbuild")

    @JvmField val Razor: Icon = load("razor")
    @JvmField val Xaml: Icon = load("xaml")
    @JvmField val Resx: Icon = load("resx")
    @JvmField val Config: Icon = load("config")
    @JvmField val SettingsJson: Icon = load("settingsJson")
    @JvmField val NuGet: Icon = load("nuget")
    @JvmField val Assembly: Icon = load("assembly")

    private val BY_EXTENSION: Map<String, Icon> = mapOf(
        "cs" to CSharp, "csx" to CSharp,
        "fs" to FSharp, "fsx" to FSharp, "fsi" to FSharp,
        "vb" to VisualBasic,
        "sln" to Solution, "slnx" to Solution, "slnf" to Solution,
        "csproj" to Project, "shproj" to Project, "proj" to Project,
        "fsproj" to ProjectFSharp,
        "vbproj" to ProjectVisualBasic,
        "props" to MsBuild, "targets" to MsBuild, "tasks" to MsBuild,
        "razor" to Razor, "cshtml" to Razor,
        "xaml" to Xaml, "axaml" to Xaml,
        "resx" to Resx,
        "config" to Config, "runsettings" to Config, "ruleset" to Config,
        "nupkg" to NuGet, "snupkg" to NuGet, "nuspec" to NuGet,
        "dll" to Assembly, "exe" to Assembly, "pdb" to Assembly,
    )

    private val BY_NAME: Map<String, Icon> = mapOf(
        "nuget.config" to NuGet,
        "packages.config" to NuGet,
        "packages.lock.json" to NuGet,
        "directory.packages.props" to NuGet,
        "global.json" to SettingsJson,
        "launchsettings.json" to SettingsJson,
    )

    /** Icon of a .NET-specific file, or null when the file should keep its regular icon. */
    fun forFile(fileName: String): Icon? {
        val name = fileName.lowercase()
        BY_NAME[name]?.let { return it }
        // appsettings.json, appsettings.Development.json, ...
        if (name.startsWith("appsettings.") && name.endsWith(".json")) return SettingsJson
        return BY_EXTENSION[name.substringAfterLast('.', "")]
    }

    fun forProjectFile(fileName: String): Icon = forFile(fileName.substringAfterLast('/')) ?: Project

    private fun load(name: String): Icon = IconLoader.getIcon("/icons/$name.svg", DotNetIcons::class.java)
}
