rootProject.name = "idea-dotnet-support"

// csharp-psi (native C# PSI, ../csharp-psi; CSHARP_PSI_MIGRATION.md, step 1): library modules composed into the plugin jar.
// Empty until step 7 moves the code of ../csharp-psi here unchanged (same module names, packages io.github.dotnetsupport.csharp.*,
// descriptors META-INF/csharp-psi-*.xml).
include(
    "csharp-psi-core",
    "csharp-psi-semantic",
    "csharp-psi-ide",
)
