package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind

/**
 * `Utf8WriteLiteralPhase` (`CSharp/Utf8WriteLiteralPhase.cs`): records on the code generation options whether a legacy (`.cshtml`) document's
 * `@inherits` base type has a callable UTF-8 `WriteLiteral(ReadOnlySpan<byte>)`, so the emission can choose byte literals. Runs after the
 * optimization and before the C# lowering.
 *
 * The compiler learns that from a [Utf8SupportMap] the source generator builds over the project's Roslyn compilation and puts on the code
 * document; without one (the compiler's own tests, the tooling, every host but the generator) the phase leaves the document alone. There is
 * no compilation here: a host that knows the answer puts a map under [Utf8SupportMap.ITEM_KEY] of [RazorCodeDocument.items].
 */
class Utf8WriteLiteralPhase : RazorEnginePhaseBase() {
    override fun executeCore(codeDocument: RazorCodeDocument): RazorCodeDocument {
        val documentNode = throwForMissingDocumentDependency(codeDocument.getDocumentNode())

        val supportMap = codeDocument.items[Utf8SupportMap.ITEM_KEY] as? Utf8SupportMap
        val options = documentNode.options
        if (supportMap != null && options != null && codeDocument.fileKind == RazorFileKind.Legacy) {
            val baseTypeName = documentNode.findPrimaryClass()?.baseType?.baseType?.content
            val supported = !baseTypeName.isNullOrBlank() && supportMap.isSupported(codeDocument.source.filePath, baseTypeName)

            documentNode.options = options.copy(writeHtmlUtf8StringLiterals = supported)
        }

        return codeDocument.withDocumentNode(documentNode)
    }
}

/**
 * `Utf8SupportMap` (`CSharp/Utf8SupportMap.cs`), its lookup half: file path → the fully-qualified type its `@inherits` resolved to (case
 * insensitive), and type → whether it supports UTF-8 `WriteLiteral`. `Create` is not ported: it resolves the names against a Roslyn
 * `Compilation` (C# 11 or later, `GetTypeByMetadataName`, a probe compilation for names with usings) and asks
 * `HasCallableUtf8WriteLiteralOverload` of each type.
 */
class Utf8SupportMap(fileToType: Map<String, String>, typeSupport: Map<String, Boolean>) {
    private val fileToType: java.util.TreeMap<String, String> = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).also { it.putAll(fileToType) }
    private val typeSupport: java.util.TreeMap<String, Boolean> = java.util.TreeMap(typeSupport)

    fun isSupported(filePath: String?, baseTypeName: String): Boolean {
        // Create() keys a document without a file path as the empty string.
        val fqn = fileToType[filePath ?: ""]
        if (fqn != null) return typeSupport[fqn] == true

        // Fallback: the raw name as a fully-qualified name.
        return typeSupport[baseTypeName] == true
    }

    override fun equals(other: Any?): Boolean = other is Utf8SupportMap && fileToType == other.fileToType && typeSupport == other.typeSupport

    override fun hashCode(): Int = fileToType.entries.sumOf { it.key.lowercase().hashCode() * 31 + it.value.hashCode() } * 31 + typeSupport.hashCode()

    companion object {
        /** The key of the map in [RazorCodeDocument.items] (the compiler keeps it in a field of the code document, `WithUtf8SupportMap`). */
        const val ITEM_KEY = "Utf8SupportMap"

        val EMPTY = Utf8SupportMap(emptyMap(), emptyMap())
    }
}
