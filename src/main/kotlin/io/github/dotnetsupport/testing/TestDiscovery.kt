package io.github.dotnetsupport.testing

import com.intellij.openapi.util.TextRange
import io.github.dotnetsupport.lang.CSharpSyntaxModel

/** A test class ([methodName] is null) or a test method, with the range of its name in the file. */
class TestTarget(val className: String, val methodName: String?, val nameRange: TextRange) {
    val displayName: String get() = className.substringAfterLast('.').replace('+', '.') + methodName?.let { ".$it" }.orEmpty()

    /**
     * "Contains" rather than "equals": the fully qualified name of a parameterized NUnit test includes its arguments.
     * The trailing dot of a class filter keeps `OrderTests` from matching `OrderTestsBase`.
     */
    val filter: String get() = "FullyQualifiedName~" + escape(if (methodName == null) "$className." else "$className.$methodName")

    companion object {
        private val SPECIAL = Regex("""[\\()&|=!~]""")
        fun escape(value: String): String = SPECIAL.replace(value) { "\\" + it.value }
    }
}

/**
 * Finds xUnit, NUnit and MSTest tests: a method directly inside a type, marked with a test attribute ([CSharpSyntaxModel.attributedMethods]).
 * There is no symbol resolution, so inherited tests and custom attributes derived from the known ones are not seen.
 */
object TestDiscovery {
    private val ATTRIBUTES = listOf("Fact", "Theory", "Test", "TestCase", "TestCaseSource", "TestMethod", "DataTestMethod")
        .flatMap { listOf(it, it + "Attribute") }.toSet()

    /** Cheap check before tokenizing a file that obviously has no tests. */
    fun mayContainTests(text: CharSequence): Boolean = ATTRIBUTES.any { text.contains("[$it") || text.contains(", $it") || text.contains(".$it") }

    fun targetAt(text: CharSequence, offset: Int): TestTarget? = targets(text).find { it.nameRange.containsOffset(offset) }

    fun find(text: CharSequence, className: String, methodName: String?): TestTarget? =
        targets(text).find { it.className == className && it.methodName == methodName }

    fun targets(text: CharSequence): List<TestTarget> {
        if (!mayContainTests(text)) return emptyList()
        val found = CSharpSyntaxModel.current.attributedMethods(text, ATTRIBUTES)
        val result = found.methods.map { TestTarget(it.typeName, it.name, it.nameRange) }
        // a class is a target when it has test methods of its own; a partial class declared twice in the file is at its last declaration
        val classNameRanges = found.types.toMap()
        val classes = result.map { it.className }.distinct()
        return result + classes.mapNotNull { name -> classNameRanges[name]?.let { TestTarget(name, null, it) } }
    }
}
