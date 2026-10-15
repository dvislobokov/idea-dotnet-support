package io.github.dotnetsupport

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.psi.CSharpVariableDeclaration
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpTypeDisplay
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynTypes

/** The facade the ported binder of Roslyn asks (CSHARP_PSI_MIGRATION.md, D6a), on the fixtures of the assembly index and a type of the solution. */
class RoslynTypesTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
    }

    override fun tearDown() {
        try {
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private lateinit var types: RoslynTypes

    /** The types of the fields of [code] by field name. */
    private fun fields(code: String): Map<String, SemanticType?> {
        val file = myFixture.configureByText("RoslynTypes.cs", code.trimIndent()) as CSharpFile
        val resolver = CSharpSemanticSession(project).resolver(file)
        types = RoslynTypes(resolver)
        return PsiTreeUtil.findChildrenOfType(file, CSharpVariableDeclaration::class.java).associate { declaration ->
            declaration.variables.single().identifier!!.text to declaration.type?.let(resolver::resolveType)
        }
    }

    private fun shown(type: SemanticType?): String? = type?.let { CSharpTypeDisplay.display(it) }

    fun testTypesOfTheAssemblies() {
        val f = fields("""
            using System;
            using System.Collections.Generic;
            class Probe
            {
                List<int> list;
                int[] array;
                int? nullable;
                Func<int, string> func;
                IEnumerable<string> sequence;
                Action<string> action;
                (int, string) pair;
                string text;
            }
        """)
        val list = f["list"]!!
        assertEquals(RoslynTypes.TypeKind.CLASS, types.typeKind(list))
        assertEquals("object", shown(types.baseType(list)))
        val interfaces = types.allInterfaces(list).map(::shown)
        assertTrue(interfaces.toString(), "System.Collections.Generic.IEnumerable<int>" in interfaces && "System.Collections.Generic.IList<int>" in interfaces)

        val array = f["array"]!!
        assertEquals(RoslynTypes.TypeKind.ARRAY, types.typeKind(array))
        assertEquals("int", shown(types.arrayElementType(array)))
        assertTrue(types.isSZArray(array))
        assertTrue(types.allInterfaces(array).map(::shown).toString(), "System.Collections.Generic.IReadOnlyList<int>" in types.allInterfaces(array).map(::shown))

        val nullable = f["nullable"]!!
        assertTrue(types.isNullableType(nullable))
        assertEquals("int", shown(types.nullableUnderlyingType(nullable)))
        assertEquals(true, types.isValueType(nullable))

        val (parameters, returns) = types.delegateInvoke(f["func"]!!)!!
        assertEquals(listOf("int"), parameters.map(::shown))
        assertEquals("string", shown(returns))

        assertEquals(RoslynTypes.VarianceKind.OUT, types.variance(f["sequence"]!!, 0))
        assertEquals(RoslynTypes.VarianceKind.IN, types.variance(f["action"]!!, 0))
        assertEquals(RoslynTypes.VarianceKind.NONE, types.variance(list, 0))

        assertEquals(listOf("int", "string"), types.tupleElementTypes(f["pair"]!!)?.map(::shown))
        assertNull(types.tupleElementTypes(list))
        assertEquals(true, types.isReferenceType(f["text"]!!))
        assertEquals(true, types.implicitConversionExists(array, interfaces.let { types.allInterfaces(array).first { shown(it) == "System.Collections.Generic.IEnumerable<int>" } }))
    }

    fun testTypesOfTheSolution() {
        val f = fields("""
            using System.Collections.Generic;
            interface IThing { }
            interface IOut<out T> { }
            class Box<T> : List<T>, IThing { }
            class Probe
            {
                Box<int> box;
                IOut<string> covariant;
            }
        """)
        val box = f["box"]!!
        assertEquals("System.Collections.Generic.List<int>", shown(types.baseType(box)))
        val interfaces = types.allInterfaces(box).map(::shown)
        assertTrue(interfaces.toString(), "IThing" in interfaces && "System.Collections.Generic.IEnumerable<int>" in interfaces)
        assertEquals(RoslynTypes.VarianceKind.OUT, types.variance(f["covariant"]!!, 0))
        assertNull("an interface has no base type", types.baseType(f["covariant"]!!))
    }
}
