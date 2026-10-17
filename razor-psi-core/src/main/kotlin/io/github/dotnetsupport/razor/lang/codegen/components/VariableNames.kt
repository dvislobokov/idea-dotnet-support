package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.codegen.*

/** The names of the variables of the component writer's nested render lambdas (`BuilderVariableName.cs`, `FormNameVariableName.cs`, `RenderModeVariableName.cs`). */

/** `BuilderVariableName`: `__builder` at the first level, `__builder2`, `__builder3`... in nested lambdas. */
data class BuilderVariableName(val index: Int) : IWriteableValue {
    val length: Int
        get() = when (index) {
            1 -> ComponentsApi.RenderTreeBuilder.builderParameter.length
            else -> ComponentsApi.RenderTreeBuilder.builderParameter.length + index.toString().length // CountDigits
        }

    override fun writeTo(writer: CodeWriter) {
        if (index == 1) {
            writer.write(ComponentsApi.RenderTreeBuilder.builderParameter)
        } else {
            writer.write(ComponentsApi.RenderTreeBuilder.builderParameter)
            writer.writeIntegerLiteral(index)
        }
    }

    /** `GetDebuggerDisplay`. */
    override fun toString(): String =
        if (index == 1) ComponentsApi.RenderTreeBuilder.builderParameter else "${ComponentsApi.RenderTreeBuilder.builderParameter}$index"

    companion object {
        val DEFAULT = BuilderVariableName(1)
    }
}

/** `FormNameVariableName`: `__formName` at the first form of the first level, `__formName{builder}_{index}` otherwise. */
data class FormNameVariableName(val index: Int, val builderIndex: Int) : IWriteableValue {
    override fun writeTo(writer: CodeWriter) {
        if (builderIndex == 1 && index == 0) {
            writer.write(ComponentsApi.RenderTreeBuilder.formNameVariableName)
        } else {
            writer.write(ComponentsApi.RenderTreeBuilder.formNameVariableName)
            writer.writeIntegerLiteral(builderIndex)
            writer.write("_")
            writer.writeIntegerLiteral(index)
        }
    }

    /** `GetDebuggerDisplay`. */
    override fun toString(): String =
        if (builderIndex == 1 && index == 0) ComponentsApi.RenderTreeBuilder.formNameVariableName
        else "${ComponentsApi.RenderTreeBuilder.formNameVariableName}${builderIndex}_$index"

    companion object {
        val DEFAULT = FormNameVariableName(0, 1)
    }
}

/** `RenderModeVariableName`: `__renderMode` at the first component of the first level, `__renderMode{builder}_{index}` otherwise. */
data class RenderModeVariableName(val index: Int, val builderIndex: Int) : IWriteableValue {
    override fun writeTo(writer: CodeWriter) {
        if (builderIndex == 1 && index == 0) {
            writer.write(ComponentsApi.RenderTreeBuilder.renderModeVariableName)
        } else {
            writer.write(ComponentsApi.RenderTreeBuilder.renderModeVariableName)
            writer.writeIntegerLiteral(builderIndex)
            writer.write("_")
            writer.writeIntegerLiteral(index)
        }
    }

    /** `GetDebuggerDisplay`. */
    override fun toString(): String =
        if (builderIndex == 1 && index == 0) ComponentsApi.RenderTreeBuilder.renderModeVariableName
        else "${ComponentsApi.RenderTreeBuilder.renderModeVariableName}${builderIndex}_$index"

    companion object {
        val DEFAULT = RenderModeVariableName(0, 1)
    }
}
