package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.codegen.*

/**
 * `ScopeStack` (`ScopeStack.cs`, `ScopeStack.Entry.cs`): the nesting of the render lambdas while the component writer writes a component —
 * each child content or template opens a lambda with its own builder variable (`__builder2`...) and its own render mode / form name counters.
 */
class ScopeStack {
    private var current: Entry = Entry.createFirst()
    private var stack: ArrayDeque<Entry>? = null

    val builderVariableName: BuilderVariableName get() = current.builderVariableName
    val renderModeVariableName: RenderModeVariableName get() = current.renderModeVariableName
    val formNameVariableName: FormNameVariableName get() = current.formNameVariableName

    val depth: Int get() = stack?.size ?: 0

    /** `Scope`: closing it closes the lambda of the scope. */
    class Scope internal constructor(private val instance: ScopeStack) : AutoCloseable {
        override fun close() {
            instance.closeScope()
        }
    }

    /** Writes `((__builder) => { ... })`, or `((context) => (__builder) => { ... })` with a [parameterName]. */
    fun openComponentScope(context: CodeRenderingContext, parameterName: String?): Scope {
        if (parameterName != null) context.codeWriter.writeLambdaHeader(parameterName)
        return openScope(context)
    }

    fun openTemplateScope(context: CodeRenderingContext): Scope = openScope(context)

    private fun openScope(context: CodeRenderingContext): Scope {
        val stack = stack ?: ArrayDeque<Entry>().also { this.stack = it }
        stack.addLast(current)
        current = current.next(context)
        return Scope(this)
    }

    private fun closeScope() {
        val stack = stack
        assert(stack != null && stack.isNotEmpty()) // Debug.Assert
        current.close()
        current = stack!!.removeLast()
    }

    fun incrementRenderMode() = current.incrementRenderMode()

    fun incrementFormName() = current.incrementFormName()

    /** `ScopeStack.Entry`: one level — its builder index, the lambda it opened (none at the first level) and its counters. */
    private class Entry private constructor(private val builderIndex: Int, context: CodeRenderingContext?) : AutoCloseable {
        private val scope: CSharpCodeWritingScope?
        private var renderModeIndex = 0
        private var formNameIndex = 0

        val builderVariableName: BuilderVariableName get() = BuilderVariableName(builderIndex)
        val renderModeVariableName: RenderModeVariableName get() = RenderModeVariableName(renderModeIndex, builderIndex)
        val formNameVariableName: FormNameVariableName get() = FormNameVariableName(formNameIndex, builderIndex)

        init {
            scope = context?.codeWriter?.buildLambda(builderVariableName)
        }

        /** `Dispose`: a default (unopened) scope of the first level disposes of nothing. */
        override fun close() {
            scope?.close()
        }

        fun next(context: CodeRenderingContext): Entry = Entry(builderIndex + 1, context)

        fun incrementRenderMode() {
            renderModeIndex++
        }

        fun incrementFormName() {
            formNameIndex++
        }

        companion object {
            fun createFirst(): Entry = Entry(1, null)
        }
    }
}
