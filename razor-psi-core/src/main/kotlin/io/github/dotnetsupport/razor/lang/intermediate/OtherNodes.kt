package io.github.dotnetsupport.razor.lang.intermediate

import io.github.dotnetsupport.razor.lang.codegen.*
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentsApi
import io.github.dotnetsupport.razor.lang.codegen.components.TypeNameHelper

/** The remaining nodes of the IR: `@ref`, `@key`, `@attributes`, `@rendermode`, `@formname`, `@documentation`, and `CommonModifiers`. */

/** `bool.ToString()` of C#, as the formatter prints a flag: `True` / `False`. */
fun Boolean.toCSharpString(): String = if (this) "True" else "False"

/** `ReferenceCaptureIntermediateNode`: `@ref="field"` of an element or (with a capture type) of a component. */
class ReferenceCaptureIntermediateNode(val identifierToken: IntermediateToken) : IntermediateNode() {
    private var componentCaptureTypeNameValue: String? = null
    private var fieldTypeNameValue: String = DEFAULT_FIELD_TYPE_NAME
    private var typeNameValue: String = DEFAULT_TYPE_NAME

    var isComponentCapture: Boolean = false
        private set

    /** Not `global::`-prefixed; [fieldTypeName] is. */
    val componentCaptureTypeName: String? get() = componentCaptureTypeNameValue
    val fieldTypeName: String get() = fieldTypeNameValue
    val typeName: String get() = typeNameValue

    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY

    init {
        source = identifierToken.source
    }

    constructor(identifierToken: IntermediateToken, componentCaptureTypeName: String) : this(identifierToken) {
        isComponentCapture = true
        updateComponentCaptureTypeName(componentCaptureTypeName)
    }

    fun updateComponentCaptureTypeName(componentCaptureTypeName: String) {
        require(componentCaptureTypeName.isNotEmpty()) { "componentCaptureTypeName must not be empty" }
        assert(isComponentCapture) // Debug.Assert
        componentCaptureTypeNameValue = componentCaptureTypeName
        fieldTypeNameValue = TypeNameHelper.getGloballyQualifiedNameIfNeeded(componentCaptureTypeName)
        typeNameValue = "global::System.Action<$fieldTypeNameValue>"
    }

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitReferenceCapture(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(identifierToken.content)
        formatter.writeProperty("IdentifierToken", identifierToken.content)
        formatter.writeProperty("ComponentCaptureTypeName", componentCaptureTypeName)
    }

    private companion object {
        const val DEFAULT_FIELD_TYPE_NAME = "global::${ComponentsApi.ElementReference.fullTypeName}"
        const val DEFAULT_TYPE_NAME = "global::System.Action<$DEFAULT_FIELD_TYPE_NAME>"
    }
}

/** `SetKeyIntermediateNode`: `@key="value"`. */
class SetKeyIntermediateNode(val keyValueToken: IntermediateToken) : IntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY

    init {
        source = keyValueToken.source
    }

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitSetKey(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(keyValueToken.content)
        formatter.writeProperty("KeyValueToken", keyValueToken.content)
    }
}

/** `SplatIntermediateNode`: `@attributes="dictionary"`. */
class SplatIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitSplat(this)
    override fun formatNode(formatter: IntermediateNodeFormatter) {} // No properties
}

/** `RenderModeIntermediateNode`: `@rendermode="mode"` of a component. */
class RenderModeIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitRenderMode(this)
}

/** `FormNameIntermediateNode`: `@formname="name"` of a form. */
class FormNameIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitFormName(this)
    override fun formatNode(formatter: IntermediateNodeFormatter) {} // No properties
}

/** `DocumentationIntermediateNode`: the `@documentation { <summary>…</summary> }` directive, written as a `/** … */` comment. */
class DocumentationIntermediateNode(val content: String = "", val isValid: Boolean = false) : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun cloneNode(): IntermediateNode = DocumentationIntermediateNode(content, isValid).also { it.isSynthesizedHelper = isSynthesizedHelper }

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        // The directive's diagnostics have been reported; a comment terminator in the content must not become executable C#.
        if (!isValid) return
        if (content.isEmpty()) return

        // A leading '*' or '/' of the content stays separate from the comment opener.
        val commentStart = "/**"
        val commentEnd = "*/"
        val inlineCommentStartLength = commentStart.length + 1

        val writer = context.codeWriter
        val source = source
        if (context.options.useEnhancedLinePragma) {
            context.buildEnhancedLinePragma(source, inlineCommentStartLength).use {
                writer.write(commentStart).write(" ").write(content).writeLine(commentEnd)
            }
        } else if (source != null && source.lineIndex > 0 && source.characterIndex < inlineCommentStartLength) {
            // "@documentation\n{<summary>...</summary>}": the XML follows the opening brace. An inline "/** " shifts it three columns right,
            // which ordinary pragmas cannot correct: the opener goes on the preceding mapped line to keep the XML's column.
            context.buildLinePragma(source.copy(lineIndex = source.lineIndex - 1)).use {
                writer.writeLine(commentStart)
                writer.writePadding(0, source, context)
                context.addSourceMappingFor(this)
                writer.write(content).writeLine(commentEnd)
            }
        } else {
            context.buildLinePragma(source).use {
                writer.writePadding(inlineCommentStartLength, source, context)
                writer.write(commentStart).write(" ")
                context.addSourceMappingFor(this)
                writer.write(content).writeLine(commentEnd)
            }
        }
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(content)
        formatter.writeProperty("IsValid", isValid.toCSharpString())
    }
}

/** `CommonModifiers`: the modifier lists the code generators use (`SyntaxFacts.GetText` of the keywords). */
object CommonModifiers {
    val internal: List<String> = listOf("internal")
    val internalSealed: List<String> = listOf("internal", "sealed")
    val internalStatic: List<String> = listOf("internal", "static")
    val private: List<String> = listOf("private")
    val privateReadOnly: List<String> = listOf("private", "readonly")
    val privateSealed: List<String> = listOf("private", "sealed")
    val fileSealed: List<String> = listOf("file", "sealed")
    val protected: List<String> = listOf("protected")
    val protectedOverride: List<String> = listOf("protected", "override")
    val public: List<String> = listOf("public")
    val publicAsyncOverride: List<String> = listOf("public", "async", "override")
    val publicOverrideAsync: List<String> = listOf("public", "override", "async")
    val publicPartial: List<String> = listOf("public", "partial")
}
