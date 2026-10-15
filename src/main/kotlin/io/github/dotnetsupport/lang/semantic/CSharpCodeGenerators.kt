package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.extensions.ExtensionPointName

/**
 * What a framework module knows about the C# the build makes of a project (RAZOR_PLAN.md, F0): whether the project may have types and
 * members no file the IDE sees declares. Any `true` keeps the semantics of the project silent about missing names
 * ([CSharpSemanticEnvironment.mayGenerateTypes]). The core knows no framework: Razor, XAML, gRPC... answer from their modules.
 */
interface CSharpCodeGenerators {
    fun mayGenerateTypes(project: CSharpProjectFacts): Boolean

    /**
     * Whether the source generator [generatorTypeName] reads only the additional files of the project (`.razor`), not its C#: an edit of
     * a C# file then leaves what it made fresh, and the project is not silent about missing names while one is typed (0.1.198). False
     * for a generator the module does not know (the JSON one reads the C#).
     */
    fun readsOnlyAdditionalFiles(generatorTypeName: String): Boolean = false

    companion object {
        val EP_NAME: ExtensionPointName<CSharpCodeGenerators> = ExtensionPointName.create("io.github.dotnetsupport.csharpCodeGenerators")
    }
}

/**
 * What [CSharpCodeGenerators] may ask about a project, each fact computed when first asked. [buildGeneratedKnown]: what the targets of the
 * build make in `obj/` is there and newer than its sources (0.1.82); [sourceGeneratorsRan]: the source generators of the project ran in the
 * helper (D4) since its last edit, [ranGenerator] says which. [hasFiles] is true while the IDE indexes: then nothing is known.
 */
class CSharpProjectFacts(
    val sdk: String,
    private val packageNames: () -> List<String>,
    buildGeneratedKnown: () -> Boolean,
    sourceGeneratorsRan: () -> Boolean,
    private val generatorNames: () -> Set<String> = { emptySet() },
    private val files: (String) -> Boolean,
) {
    val buildGeneratedKnown: Boolean by lazy(buildGeneratedKnown)
    val sourceGeneratorsRan: Boolean by lazy(sourceGeneratorsRan)
    private val packages by lazy(packageNames)
    private val generators by lazy(generatorNames)

    /** The source generator [typeName] ran with the others, and what they made is fresh: what it declares is in the index. */
    fun ranGenerator(typeName: String): Boolean = sourceGeneratorsRan && typeName in generators

    fun sdkContains(fragment: String): Boolean = sdk.contains(fragment, ignoreCase = true)

    /** A package reference whose name contains [fragment] (`Uno.`, `Grpc.Tools`). */
    fun hasPackage(fragment: String): Boolean = packages.any { it.contains(fragment, ignoreCase = true) }

    /** A file with [extension] (no dot) under the folder of the project. */
    fun hasFiles(extension: String): Boolean = files(extension)
}
