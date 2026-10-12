rootProject.name = "idea-dotnet-support"

// csharp-psi (native C# PSI, CSHARP_PSI_MIGRATION.md; moved from ../csharp-psi at step 7): library modules composed into the plugin jar
// (packages io.github.dotnetsupport.csharp.*, descriptors META-INF/csharp-psi-*.xml). csharp-psi-core holds the parser; -semantic and -ide
// are still empty.
include(
    "csharp-psi-core",
    "csharp-psi-semantic",
    "csharp-psi-ide",
)

// Shared ML completion engine: ml-core/ is a plain copy of the pure-Kotlin ml-core module of
// https://github.com/dvislobokov/idea-ml-completion (refreshed with tools/ml/sync-ml-core.sh; the training CLI and the corpus
// tools stay in that repository). It carries the n-gram LM, the ranker, the neural inference and our native SIMD kernels
// (ml-core/src/main/resources/native/libcmlkernels-<os>-<arch>.*; loaded from the jar, scalar Kotlin fallback).
include(":ml-core")
