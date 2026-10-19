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

    /**
     * Whether [other] is a file the build generates from [document] that is the same code as [document] itself: the C# document of a Razor
     * file (an injection) and the generator's `Counter_razor.g.cs` of it — the helper's output, or the SDK's in `obj/…/generated` of a
     * project with `EmitCompilerGeneratedFiles`. The resolver of [document] does not take the declarations of such a file as other parts of
     * its types — they are its own ones a second time (every member of `@code` was "no best of [Sum(int, int), Sum(int, int)]", the
     * semantics of `@code` silent, no members after `Navigation.`: the WSL sandbox, 0.1.288). Asked once per file of a part, per resolver.
     */
    fun isDocumentOutput(document: com.intellij.psi.PsiFile, other: com.intellij.openapi.vfs.VirtualFile): Boolean = false

    /**
     * Whether the build compiles [document] (an injected C# document of the module's files): false for a file its project leaves out — the
     * views nopCommerce's build copies into `Nop.Web/Plugins/…` (`<Content Remove="Plugins\**">`) gave 467 errors no compiler reports
     * (the harness, 0.1.289). The semantics reports nothing in a document the build does not compile. Null: not the module's document.
     */
    fun isDocumentCompiled(document: com.intellij.psi.PsiFile): Boolean? = null

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
