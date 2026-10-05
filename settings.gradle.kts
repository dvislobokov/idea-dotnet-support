rootProject.name = "idea-dotnet-support"

// csharp-psi (native C# PSI, CSHARP_PSI_MIGRATION.md; moved from ../csharp-psi at step 7): library modules composed into the plugin jar
// (packages io.github.dotnetsupport.csharp.*, descriptors META-INF/csharp-psi-*.xml). csharp-psi-core holds the parser; -semantic and -ide
// are still empty.
include(
    "csharp-psi-core",
    "csharp-psi-semantic",
    "csharp-psi-ide",
)
