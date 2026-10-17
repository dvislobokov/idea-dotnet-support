package io.github.dotnetsupport.razor.lang.codegen.extensions

import io.github.dotnetsupport.razor.lang.codegen.*
import io.github.dotnetsupport.razor.lang.intermediate.*
import io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument

/**
 * The metadata attributes of a compiled item (`RazorCompiledItem*AttributeIntermediateNode.cs`, `RazorSourceChecksumAttributeIntermediateNode.cs`,
 * `IMetadataAttributeTargetExtension.cs`, `MetadataAttributeTargetExtension.cs`, `IMetadataIdentifierFeature.cs`,
 * `DefaultMetadataIdentifierFeature.cs`): `[assembly: RazorCompiledItem(…)]`, `[RazorSourceChecksum(…)]`, `[RazorCompiledItemMetadata(…)]`.
 */

/** Roslyn's `SourceHashAlgorithm`, as `RazorSourceChecksumAttribute` prints it (`Sha256`). */
enum class SourceHashAlgorithm { None, Sha1, Sha256 }

/** `RazorCompiledItemAttributeIntermediateNode`: `[assembly: RazorCompiledItem(typeof(T), @"kind", @"/identifier")]`. */
class RazorCompiledItemAttributeIntermediateNode : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY
    var typeName: String? = null
    var kind: String? = null
    var identifier: String? = null

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IMetadataAttributeTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IMetadataAttributeTargetExtension::class.java)
            return
        }
        extension.writeRazorCompiledItemAttribute(context, this)
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeProperty("Identifier", identifier)
        formatter.writeProperty("Kind", kind)
        formatter.writeProperty("TypeName", typeName)
    }
}

/** `RazorCompiledItemMetadataAttributeIntermediateNode`: `[RazorCompiledItemMetadata("key", "value")]`; [valueStringSyntax] is an optional `// language=` of the value. */
class RazorCompiledItemMetadataAttributeIntermediateNode : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY
    var key: String? = null
    var value: String? = null
    var valueStringSyntax: String? = null

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun cloneNode(): IntermediateNode = RazorCompiledItemMetadataAttributeIntermediateNode().also {
        it.key = key
        it.value = value
        it.valueStringSyntax = valueStringSyntax
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IMetadataAttributeTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IMetadataAttributeTargetExtension::class.java)
            return
        }
        extension.writeRazorCompiledItemMetadataAttribute(context, this)
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeProperty("Key", key)
        formatter.writeProperty("Value", value)
        formatter.writeProperty("ValueStringSyntax", valueStringSyntax)
    }
}

/** `RazorSourceChecksumAttributeIntermediateNode`: `[RazorSourceChecksum(@"Sha256", @"…", @"/identifier")]`. */
class RazorSourceChecksumAttributeIntermediateNode : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY

    /** The checksum bytes; null is the compiler's default (unset) `ImmutableArray`. */
    var checksum: List<Byte>? = null
    var checksumAlgorithm: SourceHashAlgorithm = SourceHashAlgorithm.None
    var identifier: String? = null

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IMetadataAttributeTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IMetadataAttributeTargetExtension::class.java)
            return
        }
        extension.writeRazorSourceChecksumAttribute(context, this)
    }
}

interface IMetadataAttributeTargetExtension : ICodeTargetExtension {
    fun writeRazorCompiledItemAttribute(context: CodeRenderingContext, node: RazorCompiledItemAttributeIntermediateNode)
    fun writeRazorSourceChecksumAttribute(context: CodeRenderingContext, node: RazorSourceChecksumAttributeIntermediateNode)
    fun writeRazorCompiledItemMetadataAttribute(context: CodeRenderingContext, node: RazorCompiledItemMetadataAttributeIntermediateNode)
}

/** `MetadataAttributeTargetExtension`. */
class MetadataAttributeTargetExtension : IMetadataAttributeTargetExtension {
    var compiledItemAttributeName: String = "global::Microsoft.AspNetCore.Razor.Hosting.RazorCompiledItemAttribute"
    var sourceChecksumAttributeName: String = "global::Microsoft.AspNetCore.Razor.Hosting.RazorSourceChecksumAttribute"
    var compiledItemMetadataAttributeName: String = "global::Microsoft.AspNetCore.Razor.Hosting.RazorCompiledItemMetadataAttribute"

    override fun writeRazorCompiledItemAttribute(context: CodeRenderingContext, node: RazorCompiledItemAttributeIntermediateNode) {
        // [assembly: global::...RazorCompiledItem(typeof({node.TypeName}), @"{node.Kind}", @"{node.Identifier}")]
        context.codeWriter.write("[assembly: ")
        context.codeWriter.write(compiledItemAttributeName)
        context.codeWriter.write("(typeof(")
        context.codeWriter.write(node.typeName!!)
        context.codeWriter.write("), ")
        context.codeWriter.writeVerbatimStringLiteral(node.kind ?: "")
        context.codeWriter.write(", ")
        context.codeWriter.writeVerbatimStringLiteral(node.identifier ?: "")
        context.codeWriter.writeLine(")]")
    }

    override fun writeRazorCompiledItemMetadataAttribute(context: CodeRenderingContext, node: RazorCompiledItemMetadataAttributeIntermediateNode) {
        // [assembly: global::...RazorCompiledItemAttribute(@"{node.Key}", @"{node.Value}")]
        context.codeWriter.write("[")
        context.codeWriter.write(compiledItemMetadataAttributeName)
        context.codeWriter.write("(")
        context.codeWriter.writeStringLiteral(node.key ?: "")
        context.codeWriter.write(", ")
        val source = node.source
        if (source != null) {
            context.codeWriter.writeLine()
            val valueStringSyntax = node.valueStringSyntax
            if (valueStringSyntax != null) {
                context.codeWriter.write("// language=")
                context.codeWriter.writeLine(valueStringSyntax)
            }

            context.buildEnhancedLinePragma(source).use {
                context.addSourceMappingFor(node)
                context.codeWriter.writeStringLiteral(node.value ?: "")
            }
        } else {
            context.codeWriter.writeStringLiteral(node.value ?: "")
        }

        context.codeWriter.writeLine(")]")
    }

    override fun writeRazorSourceChecksumAttribute(context: CodeRenderingContext, node: RazorSourceChecksumAttributeIntermediateNode) {
        // [global::...RazorSourceChecksum(@"{node.ChecksumAlgorithm}", @"{node.Checksum}", @"{node.Identifier}")]
        context.codeWriter.write("[")
        context.codeWriter.write(sourceChecksumAttributeName)
        context.codeWriter.write("(@\"")
        context.codeWriter.write(node.checksumAlgorithm.toString())
        context.codeWriter.write("\", @\"")
        context.codeWriter.write(bytesToString(node.checksum ?: throw IllegalArgumentException("bytes")))
        context.codeWriter.write("\", ")
        context.codeWriter.writeVerbatimStringLiteral(node.identifier ?: "")
        context.codeWriter.writeLine(")]")
    }

    private companion object {
        /** `ChecksumUtilities.BytesToString`: lower-case hex. */
        fun bytesToString(bytes: List<Byte>): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(Character.forDigit((b.toInt() shr 4) and 0xf, 16))
                sb.append(Character.forDigit(b.toInt() and 0xf, 16))
            }
            return sb.toString()
        }
    }
}

/** `IMetadataIdentifierFeature`: the identifier of a source document in the metadata attributes. */
interface IMetadataIdentifierFeature : RazorEngineFeature {
    fun getIdentifier(codeDocument: RazorCodeDocument, sourceDocument: RazorSourceDocument): String?
}

/** `DefaultMetadataIdentifierFeature`: the relative path with forward slashes and a leading `/`; null without a relative path. */
class DefaultMetadataIdentifierFeature : RazorEngineFeatureBase(), IMetadataIdentifierFeature {
    override fun getIdentifier(codeDocument: RazorCodeDocument, sourceDocument: RazorSourceDocument): String? {
        val relativePath = sourceDocument.relativePath
        if (relativePath.isNullOrEmpty()) return null

        var identifier: String = relativePath
        identifier = identifier.replace("\\", "/")
        if (!identifier.startsWith("/")) identifier = "/$identifier"

        return identifier
    }
}
