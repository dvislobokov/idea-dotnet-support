package io.github.dotnetsupport.index

/**
 * What the indexer read from the nullable attributes of `System.Diagnostics.CodeAnalysis` on a member, its return value and its parameters,
 * and which of their reference types are oblivious (no nullable context: code written before nullable reference types), for the nullable
 * flow analysis (`lang/semantic/CSharpNullableFlow`). The index stores it as `target:code` items (`MemberEntry.Nullability` of
 * indexer/Program.cs); [returns] is the return value of a method and the type of a property or a field.
 */
class IndexedNullability private constructor(
    val returns: Annotations,
    private val parameters: Map<Int, Annotations>,
    /** `[DoesNotReturn]`. */
    val doesNotReturn: Boolean,
    /** `[MemberNotNull(...)]`: the members that are not null once it returns. */
    val memberNotNull: List<String>,
    /** `[MemberNotNullWhen(true, ...)]` / `[MemberNotNullWhen(false, ...)]`: by the bool returned. */
    val memberNotNullWhen: Map<Boolean, List<String>>,
) {
    /** The attributes of a parameter or of the return value. */
    class Annotations(
        val oblivious: Boolean = false, val maybeNull: Boolean = false, val notNull: Boolean = false, val allowNull: Boolean = false,
        val disallowNull: Boolean = false,
        /** `[NotNullWhen(b)]` → `b`. */
        val notNullWhen: Boolean? = null,
        /** `[MaybeNullWhen(b)]` → `b`. */
        val maybeNullWhen: Boolean? = null,
        /** `[DoesNotReturnIf(b)]` → `b`. */
        val doesNotReturnIf: Boolean? = null,
        /** `[NotNullIfNotNull("name")]` → `name`. */
        val notNullIfNotNull: String? = null,
    ) {
        val isEmpty: Boolean get() = !oblivious && !maybeNull && !notNull && !allowNull && !disallowNull && notNullWhen == null && maybeNullWhen == null && doesNotReturnIf == null && notNullIfNotNull == null

        /** Something beyond the annotation of the type and obliviousness: an attribute the flow analysis has to understand. */
        val hasAttributes: Boolean get() = maybeNull || notNull || allowNull || disallowNull || notNullWhen != null || maybeNullWhen != null || doesNotReturnIf != null || notNullIfNotNull != null

        internal fun with(code: String): Annotations = when {
            code == "o" -> copy(oblivious = true)
            code == "M" -> copy(maybeNull = true)
            code == "N" -> copy(notNull = true)
            code == "A" -> copy(allowNull = true)
            code == "D" -> copy(disallowNull = true)
            code.startsWith("W") -> copy(notNullWhen = code.endsWith("1"))
            code.startsWith("w") -> copy(maybeNullWhen = code.endsWith("1"))
            code.startsWith("X") -> copy(doesNotReturnIf = code.endsWith("1"))
            code.startsWith("I=") -> copy(notNullIfNotNull = code.substring(2))
            else -> this
        }

        private fun copy(
            oblivious: Boolean = this.oblivious, maybeNull: Boolean = this.maybeNull, notNull: Boolean = this.notNull, allowNull: Boolean = this.allowNull,
            disallowNull: Boolean = this.disallowNull, notNullWhen: Boolean? = this.notNullWhen, maybeNullWhen: Boolean? = this.maybeNullWhen,
            doesNotReturnIf: Boolean? = this.doesNotReturnIf, notNullIfNotNull: String? = this.notNullIfNotNull,
        ) = Annotations(oblivious, maybeNull, notNull, allowNull, disallowNull, notNullWhen, maybeNullWhen, doesNotReturnIf, notNullIfNotNull)

        companion object {
            val NONE = Annotations()
        }
    }

    fun parameter(index: Int): Annotations = parameters[index] ?: Annotations.NONE

    companion object {
        val NONE = IndexedNullability(Annotations.NONE, emptyMap(), false, emptyList(), emptyMap())

        fun parse(text: String): IndexedNullability {
            if (text.isEmpty()) return NONE
            var returns = Annotations.NONE
            val parameters = HashMap<Int, Annotations>()
            var doesNotReturn = false
            val notNull = ArrayList<String>()
            val notNullWhen = HashMap<Boolean, List<String>>()
            for (item in text.split(';')) {
                val colon = item.indexOf(':')
                if (colon <= 0) continue
                val target = item.substring(0, colon)
                val code = item.substring(colon + 1)
                when {
                    target == "r" -> returns = returns.with(code)
                    target == "m" -> when {
                        code == "R" -> doesNotReturn = true
                        code.startsWith("MN=") -> notNull += code.substring(3).split(',').filter { it.isNotEmpty() }
                        code.startsWith("MW") && code.length > 3 && code[3] == '=' ->
                            notNullWhen.merge(code[2] == '1', code.substring(4).split(',').filter { it.isNotEmpty() }) { a, b -> a + b }
                    }
                    else -> target.toIntOrNull()?.let { i -> parameters[i] = (parameters[i] ?: Annotations.NONE).with(code) }
                }
            }
            return IndexedNullability(returns, parameters, doesNotReturn, notNull, notNullWhen)
        }
    }
}
