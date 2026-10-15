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
 * Roslyn's method type inference, ported (CSHARP_PSI_MIGRATION.md, D6b): what the strict inference of the compiler errors did not model
 * before (upper bounds, `T?` with a lower bound, interfaces a class implements, dependencies) gives no error where Roslyn gives none, and
 * a sure failure is still CS0411. The inferred type is seen through an assignment that converts only from the right one.
 */
class RoslynMethodTypeInferenceTest : BasePlatformTestCase() {
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
        myFixture.configureByText("Inference${files++}.cs", text.trimIndent())
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
            .sortedBy(HighlightInfo::getStartOffset)
            .map { "${document.substring(0, it.startOffset).count { c -> c == '\n' } + 1}: " + document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    fun testUpperBoundThroughAContravariantInterface() {
        // IComparer<in T>: IComparer<object> gives T an upper bound object, "s" a lower bound string — T is string
        assertEmpty(errors("""
            using System.Collections.Generic;
            class C
            {
                static T Pick<T>(IComparer<T> comparer, T value) => value;
                string M(IComparer<object> comparer) => Pick(comparer, "s");
            }
        """))
    }

    fun testNullableWithALowerBound() {
        // SPEC ERROR of Roslyn: M<T>(T?, T) with (char?, int) infers lower bounds char and int, and chooses int
        assertEmpty(errors("""
            class C
            {
                static T Pick<T>(T? first, T second) where T : struct => second;
                int M(char? c) => Pick(c, 1);
            }
        """))
    }

    fun testInterfaceImplementedByAClass() {
        assertEmpty(errors("""
            using System.Collections.Generic;
            class Bag : List<string> { }
            class C
            {
                static T First<T>(IEnumerable<T> items) => default!;
                string M(Bag bag) => First(bag);
            }
        """))
    }

    fun testDependentTypeParametersFixedInTurn() {
        assertEmpty(errors("""
            using System;
            class C
            {
                static TResult Apply<TSource, TMiddle, TResult>(TSource source, Func<TSource, TMiddle> first, Func<TMiddle, TResult> second) => second(first(source));
                string M() => Apply(1, x => x * 2.0, y => y.ToString());
            }
        """))
    }

    /** The type of a call (the lenient inference of expressions, D6b): the best of the bounds, not the first argument's type. */
    fun testTheTypeOfACallIsTheBestOfTheBounds() {
        val found = errors("""
            class C
            {
                static T Pick<T>(T a, T b) => a;
                int M() => Pick(1, 2L);
            }
        """)
        // T is long (int converts to it), not int of the first argument: long to int is CS0266 as Roslyn reports it
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found.single(), "CS0266" in found.single() && "'long'" in found.single())
    }

    fun testSureFailureIsStillCS0411() {
        val found = errors("""
            class C
            {
                static T Same<T>(T a, T b) => a;
                void M() => Same(1, "s");
            }
        """)
        assertEquals(found.toString(), listOf("CS0411"), found.map { it.substringAfter(" -> ").substringBefore(':') })
    }
}
