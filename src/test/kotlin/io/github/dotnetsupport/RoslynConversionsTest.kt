package io.github.dotnetsupport

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Roslyn's classification of implicit conversions, ported (CSHARP_PSI_MIGRATION.md, D6d): the conversions of the Razor corpus our own rules
 * missed (a type parameter to `Nullable` of it, to a type with a user-defined operator, from a type with one to a type parameter) give no
 * error, and a conversion that does not exist is still CS0029.
 */
class RoslynConversionsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The errors of [text] as `line: text under it -> CSxxxx: message`. */
    private fun errors(text: String): List<String> {
        myFixture.configureByText("Conversions${files++}.cs", text.trimIndent())
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
            .sortedBy(HighlightInfo::getStartOffset)
            .map { "${document.substring(0, it.startOffset).count { c -> c == '\n' } + 1}: " + document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    /** MudSlider: `T` with `struct` given to a `T?` parameter is the implicit nullable conversion. */
    fun testTypeParameterToItsNullable() {
        assertEmpty(errors("""
            class State<T> { public void Set(T value) { } }
            class Slider<T> where T : struct
            {
                private readonly State<T?> _nullable = new State<T?>();
                void M(T result) => _nullable.Set(result);
            }
        """))
    }

    /** MudSelectContext: `T` to `NullableObject<T?>` by its `implicit operator NullableObject<T>(T? item)`; `T?` of an unconstrained `T` is `T`. */
    fun testTypeParameterToATypeWithAnOperator() {
        assertEmpty(errors("""
            using System.Collections.Generic;
            readonly struct NullableObject<T>
            {
                public static implicit operator T?(NullableObject<T> o) => default;
                public static implicit operator NullableObject<T>(T? item) => default;
            }
            class Context<T>
            {
                private readonly Dictionary<NullableObject<T?>, int> _lookup = new();
                void M(T value) => _lookup.Remove(value);
            }
        """))
    }

    /** ChartData: `double?` by the operator taking `T?` of a `T : struct`, next to one taking `T`. */
    fun testNullableToATypeWithAnOperatorOfTheNullable() {
        assertEmpty(errors("""
            class ChartData<T> where T : struct
            {
                public static implicit operator ChartData<T>(T value) => new ChartData<T>();
                public static implicit operator ChartData<T>(T? value) => new ChartData<T>();
            }
            class C
            {
                void M()
                {
                    ChartData<double> withValue = (double?)7.0;
                    ChartData<double> withoutValue = (double?)null;
                }
            }
        """))
    }

    /** ObserverManager: a nested struct with `implicit operator TObserver(Entry entry)` given to a `TObserver` parameter. */
    fun testATypeWithAnOperatorToATypeParameter() {
        assertEmpty(errors("""
            using System.Collections.Generic;
            class ObserverManager<TObserver>
            {
                private readonly record struct Entry(TObserver Observer)
                {
                    public static implicit operator TObserver(Entry entry) => entry.Observer;
                }
                void M(Dictionary<int, TObserver> result, Entry observer) => result.Add(1, observer);
            }
        """))
    }

    fun testNoConversionIsStillAnError() {
        val found = errors("""
            class ChartData<T> where T : struct
            {
                public static implicit operator ChartData<T>(T value) => new ChartData<T>();
            }
            class C
            {
                void M()
                {
                    ChartData<double> fromText = "s";
                    string text = 1;
                }
            }
        """)
        assertEquals(found.toString(), listOf("CS0029", "CS0029"), found.map { it.substringAfter(" -> ").substringBefore(':') })
    }
}
