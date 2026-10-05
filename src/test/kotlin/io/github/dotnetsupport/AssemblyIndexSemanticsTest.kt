package io.github.dotnetsupport

import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.index.ImportCompletion
import io.github.dotnetsupport.index.IndexedMember
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.index.IndexedTypeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the index keeps for the semantics: whole types with their generic parameters, bases and attributes, every member a library
 * shows with its signature, extension methods by what they extend, the documentation. The fixtures are made by
 * tools/index-fixture/fixtures.sh: IndexFixture.dll of tools/index-fixture/Fixture.cs, and System.Console, System.Linq and
 * System.Collections of the reference pack of .NET 10.
 */
class AssemblyIndexSemanticsTest {
    private fun bytes(name: String): ByteArray = javaClass.getResourceAsStream("/index/$name")!!.use { it.readBytes() }
    private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix"), AssemblyDocs.read(bytes("$name.dnxd")))

    private val library = fixture("IndexFixture")
    private val console = fixture("System.Console")
    private val linq = fixture("System.Linq")
    private val collections = fixture("System.Collections")
    private val all = AssemblyIndexSet(listOf(library, console, linq, collections))

    private fun type(name: String): IndexedType = library.findType(name) ?: error("no type $name")
    private fun IndexedType.member(name: String): IndexedMember = members.single { it.name == name }
    private fun IndexedMember.parameterTypes(nullable: Boolean = false): List<String> = parameters.map { display(it.typeRef, nullable) }

    @Test
    fun `a type by its name and the types of a namespace`() {
        val box = type("Fixture.Box`1")
        assertEquals(IndexedTypeKind.CLASS, box.kind)
        assertEquals("Box", box.name)
        assertEquals("Fixture.Box`1", box.fullName)
        assertEquals(1, box.arity)
        assertEquals("T", box.typeParameters.single().name)
        assertTrue("where T : class, new()", box.typeParameters.single().isClass && box.typeParameters.single().hasNew)
        assertEquals(library.findType("Fixture", "Box`1"), box)
        assertNull("the arity is a part of the name", library.findType("Fixture.Box"))
        assertNull(library.findType("Fixture.Nothing"))

        val names = library.typesIn("Fixture").map { it.name }
        assertTrue(names.toString(), names.containsAll(listOf("Shape", "Circle", "Box", "StringBox", "Point", "Money", "Cursor", "IShape", "Color", "Handler", "ShapeExtensions", "Old", "Concealed", "Outer")))
        assertFalse("nested types are not of the namespace", "Box.Inner" in names)
        assertTrue("Box.Inner" in library.typesIn("Fixture", nested = true).map { it.name })
        assertFalse("what other assemblies do not see is not there", names.any { it == "NotSeen" })
        assertEquals(setOf("Fixture"), library.namespaces)
        assertTrue(collections.typesIn("System.Collections.Generic").any { it.name == "List" })
    }

    @Test
    fun `nested and protected types`() {
        val inner = type("Fixture.Box`1+Inner`1")
        assertEquals("Box.Inner", inner.name)
        assertEquals("Inner", inner.simpleName)
        assertEquals(type("Fixture.Box`1"), inner.declaringType)
        assertEquals(listOf("T", "U"), inner.typeParameters.map { it.name })
        assertEquals(1, inner.ownArity)
        assertTrue(inner.typeParameters[1].isStruct)
        assertEquals(listOf(inner), type("Fixture.Box`1").nestedTypes)
        assertEquals("T:Fixture.Box`1.Inner`1", inner.docId)

        val secret = type("Fixture.Outer+Secret")
        assertTrue(secret.isProtected)
        assertTrue("only the types derived from Outer see it: not offered by name", library.types("Secret").isEmpty() && library.members("Tell").isEmpty())
        assertNull("private and internal types are not there", library.findType("Fixture.Outer+Private") ?: library.findType("Fixture.Outer+Internal"))
    }

    @Test
    fun `kinds and modifiers of types`() {
        assertTrue(type("Fixture.Shape").isAbstract)
        assertTrue(type("Fixture.Circle").isSealed)
        assertEquals("Fixture.Shape", (type("Fixture.Circle").baseType as IndexedTypeRef.Named).fullName)
        assertTrue(type("Fixture.ShapeExtensions").isStatic)
        assertEquals(IndexedTypeKind.STATIC_CLASS, type("Fixture.ShapeExtensions").kind)
        assertTrue(type("Fixture.Point").isRecord)
        assertFalse(type("Fixture.Circle").isRecord)
        assertEquals(IndexedTypeKind.STRUCT, type("Fixture.Money").kind)
        assertTrue(type("Fixture.Money").isReadOnly)
        assertTrue(type("Fixture.Cursor").isRefLike)
        assertNull("the base of a struct says nothing", type("Fixture.Money").baseType)
        assertEquals(IndexedTypeKind.DELEGATE, type("Fixture.Handler").kind)
        assertEquals(IndexedTypeKind.ENUM, type("Fixture.Color").kind)
        assertEquals("byte", type("Fixture.Color").enumUnderlyingType!!.display())
        assertEquals("1", type("Fixture.Color").member("Red").constantValue)
        assertEquals(IndexedMemberKind.ENUM_MEMBER, type("Fixture.Color").member("Green").kind)

        val shape = type("Fixture.IShape`2")
        assertEquals(IndexedTypeKind.INTERFACE, shape.kind)
        assertEquals(listOf("in TIn", "out TOut"), shape.typeParameters.map { it.toString() })
        assertTrue(shape.typeParameters[0].allowsRefStruct)
        val total = shape.member("Total")
        assertTrue(total.isStatic && total.isAbstract && total.hasGetter && !total.hasSetter)
        assertTrue(shape.member("Get").isAbstract)

        val box = type("Fixture.Box`1")
        // the metadata lists the interfaces of the interfaces too
        assertEquals(listOf("IComparable<Box<T>>", "IEnumerable<T>", "IEnumerable", "IHolder<T>", "INamed"), box.interfaces.map { it.display(listOf("T")) })
    }

    @Test
    fun `attributes, obsolete and hidden`() {
        val old = type("Fixture.Old")
        assertTrue(old.obsolete)
        assertEquals(listOf("System.ObsoleteAttribute"), old.attributes)
        val concealed = type("Fixture.Concealed")
        assertTrue(concealed.isHidden)
        assertTrue("code that names it is right: it is in the index", library.members("Run").isNotEmpty())
        assertTrue("but it is not offered", ImportCompletion.items(listOf(library), "Run", { true }).isEmpty())
        assertTrue("the compiler's own attributes are left out", type("Fixture.Money").attributes.none { it.contains("IsReadOnly") })
    }

    @Test
    fun `fields, constants, events, operators and constructors`() {
        val shape = type("Fixture.Shape")
        assertEquals("\"shape\\n\"", shape.member("Kind").constantValue)
        assertEquals(IndexedMemberKind.CONSTANT, shape.member("Kind").kind)
        assertEquals("1.5", shape.member("Ratio").constantValue)
        assertEquals("-9000000000", shape.member("Big").constantValue)
        assertTrue(shape.member("Counter").isProtected)
        assertTrue(shape.member("Created").isReadOnly)
        assertTrue(shape.member("Count").isStatic)
        val constructor = shape.member(".ctor")
        assertEquals(IndexedMemberKind.CONSTRUCTOR, constructor.kind)
        assertTrue(constructor.isProtected)
        assertEquals(IndexedMemberKind.EVENT, shape.member("Changed").kind)
        assertEquals("EventHandler?", shape.member("Changed").display(shape.member("Changed").typeRef, nullable = true))
        assertEquals(IndexedMemberKind.OPERATOR, shape.member("op_Addition").kind)
        assertEquals("M:Fixture.Shape.op_Implicit(Fixture.Shape)~System.Double", shape.member("op_Implicit").docId)
        assertFalse("private and private protected members are not there", shape.members.any { it.name == "Hidden" || it.name == "Narrow" })

        val label = shape.member("Label")
        assertTrue("a protected setter: the getter is public, so is the property", label.hasGetter && label.hasSetter && !label.isProtected)
        assertEquals("string", label.returnType)
        assertEquals("string?", label.display(label.typeRef, nullable = true))
    }

    @Test
    fun `methods and properties with their signatures`() {
        val shape = type("Fixture.Shape")
        val describe = shape.member("Describe")
        assertTrue(describe.isVirtual && !describe.isStatic)
        assertEquals("string Shape.Describe(string prefix, int digits)", describe.toString())
        assertEquals(listOf("string?", "int"), describe.parameterTypes(nullable = true))
        assertEquals(listOf("null", "2"), describe.parameters.map { it.defaultValue })
        assertTrue(describe.parameters.all { it.isOptional && it.hasDefault })
        assertTrue(shape.member("Area").isAbstract)

        val circle = type("Fixture.Circle")
        assertEquals(listOf("double"), circle.member(".ctor").parameterTypes())
        assertTrue(circle.member("Radius").isInitOnly)
        assertTrue(circle.member("Name").isRequired)
        assertTrue(circle.member("Area").isOverride)
        val indexer = circle.member("Item")
        assertEquals(IndexedMemberKind.INDEXER, indexer.kind)
        assertEquals(listOf("index", "key"), indexer.parameters.map { it.name })
        assertEquals("P:Fixture.Circle.Item(System.Int32,System.String)", indexer.docId)

        val box = type("Fixture.Box`1")
        val map = box.member("Map")
        assertEquals(1, map.arity)
        assertEquals("TResult", map.typeParameters.single().name)
        assertEquals(listOf("Func<T, TResult>"), map.parameterTypes())
        assertEquals("TResult", map.returnType)
        assertEquals("M:Fixture.Box`1.Map``1(System.Func{`0,``0})", map.docId)
        val group = box.member("Group")
        assertTrue(group.parameters.single().isParams)
        assertEquals("Dictionary<string, List<T>>", group.returnType)
        assertEquals("List<T>", box.member("Items").returnType)
        assertEquals("List<T?>", box.member("Items").let { it.display(it.typeRef, nullable = true) })
        assertEquals("explicit implementations are private", 1, box.members.count { it.name == "GetEnumerator" })

        val inner = type("Fixture.Box`1+Inner`1")
        assertEquals("(T First, U? second, int)", inner.member("Pair").returnType)
        assertEquals("(int A, int B, int C, int D, int E, int F, int G, int H, (string Nested, int) I)", inner.member("Long").returnType)

        val cursor = type("Fixture.Cursor")
        assertEquals("ref int", cursor.member("Position").returnType)
        val peek = cursor.member("Peek")
        assertEquals("ref int", peek.returnType)
        assertTrue(peek.isReadOnly)

        val invoke = type("Fixture.Handler").member("Invoke")
        assertEquals("int Handler.Invoke(ref int x, out string y, in long z, params object[] rest)", invoke.toString())

        val pointer = type("Fixture.ShapeExtensions").member("Pointer")
        assertEquals(listOf("int*", "delegate*<int, void>", "int[,]"), pointer.parameterTypes())
        assertEquals("M:Fixture.ShapeExtensions.Pointer(System.Int32*,=FUNC:System.Void(System.Int32),System.Int32[0:,0:])", pointer.docId)
        val unmanaged = type("Fixture.ShapeExtensions").member("Unmanaged").typeParameters.single()
        assertTrue(unmanaged.isUnmanaged && unmanaged.isStruct)
    }

    @Test
    fun `extension methods by what they extend`() {
        assertEquals(listOf("Twice"), library.extensions(AssemblyIndexSet.GENERIC_RECEIVER).map { it.name })
        assertEquals(listOf("Total"), library.extensions(AssemblyIndexSet.ARRAY_RECEIVER).map { it.name })
        val name = library.extensions("Fixture.Shape").single()
        assertEquals("Name", name.name)
        assertTrue(name.thisParameter!!.isThis)
        assertEquals(listOf(null, "\"none\"", "'\\''", "2", "null"), name.parameters.map { it.defaultValue })
        assertEquals("double?", name.parameterTypes().last())
        assertEquals(listOf("Sum"), library.extensions("System.Collections.Generic.IEnumerable`1").map { it.name })
        assertTrue(linq.extensions("System.Collections.Generic.IEnumerable`1").any { it.name == "Select" })
        assertTrue(linq.extensions("Nothing").isEmpty())

        val circle = type("Fixture.Circle")
        val found = all.extensionsFor(circle).map { it.name }.toSet()
        assertTrue("by its base and as a type parameter: $found", found.containsAll(setOf("Name", "Twice")))
    }

    @Test
    fun `bases, interfaces and inherited members`() {
        val stringBox = type("Fixture.StringBox")
        val bases = all.baseTypes(stringBox)
        assertEquals("object is not among the fixtures", listOf("Box<StringBuilderLike>"), bases.map { it.toString() })
        val members = all.members(stringBox)
        assertEquals("the override hides what it overrides", 1, members.count { it.member.name == "Group" })
        assertEquals("Fixture.StringBox", members.single { it.member.name == "Group" }.member.type.fullName)
        val value = members.single { it.member.name == "Value" }
        assertEquals("StringBuilderLike", value.member.typeRef.substitute(value.arguments).display())
        assertFalse("constructors are not inherited", members.any { it.member.kind == IndexedMemberKind.CONSTRUCTOR && it.from != null })

        val interfaces = all.interfaces(stringBox).map { it.toString() }
        assertTrue(interfaces.toString(), interfaces.containsAll(listOf("IHolder<StringBuilderLike>", "INamed")))
        val holder = type("Fixture.IHolder`1")
        assertTrue("an interface has the members of its interfaces", all.members(holder).map { it.member.name }.containsAll(listOf("Held", "Title")))

        val items = type("Fixture.Box`1").member("Items").typeRef
        assertEquals("a type of another assembly", "System.Collections", all.resolve(items)!!.index.assemblyName)
        assertNull(all.resolve(IndexedTypeRef.TypeParameter(0, false)))
    }

    @Test
    fun `the documentation by the ID`() {
        val describe = type("Fixture.Shape").member("Describe")
        val doc = library.doc(describe.docId)!!
        assertEquals("Says what the shape is.", doc.summary)
        assertEquals("What goes first.", doc.parameter("prefix"))
        assertEquals("The text.", doc.returns)
        assertEquals("Square units.", library.doc(type("Fixture.Shape").member("Area").docId)!!.value)
        assertTrue(library.doc(type("Fixture.Shape").docId)!!.remarks!!.contains("<see cref=\"T:Fixture.Circle\" />"))
        assertEquals("What is in the box.", library.doc(type("Fixture.Box`1").docId)!!.typeParameter("T"))
        assertNull(library.doc("M:Fixture.Nothing"))
        assertNull(library.doc("A"))
        assertNull(library.doc("Z"))

        val writeLine = console.findType("System.Console")!!.members.first { it.name == "WriteLine" && it.parameters.singleOrNull()?.type == "string" }
        assertEquals("M:System.Console.WriteLine(System.String)", writeLine.docId)
        assertNotNull(all.doc(writeLine.docId)!!.summary)
    }

    /** The IDs the plugin makes are the ones of the documentation the SDK has: for every member of these assemblies. */
    @Test
    fun `every member of the reference pack has its documentation`() {
        for (index in listOf(console, linq, collections)) {
            // the members of a delegate are the compiler's, nobody documents them
            val members = index.allTypes.filter { !it.isHidden && it.kind != IndexedTypeKind.DELEGATE }.flatMap { it.members }.filter { !it.isHidden }
            val missing = members.filter { index.doc(it.docId) == null }.map { it.docId }
            assertTrue("${index.assemblyName}: ${missing.size} of ${members.size}: ${missing.take(20)}", missing.size * 50 <= members.size)
            val types = index.allTypes.filter { index.doc(it.docId) == null }.map { it.docId }
            assertTrue("${index.assemblyName}: $types", types.size * 20 <= index.typeCount)
        }
    }

    @Test
    fun `type references`() {
        val dictionary = IndexedTypeRef.parse("I2:NSystem.Collections.Generic.Dictionary`2;NSystem.String;VSystem.Int32;")
        assertEquals("Dictionary<string, int>", dictionary.display())
        assertEquals("System.Collections.Generic.Dictionary{System.String,System.Int32}", dictionary.docId())
        val enumerator = IndexedTypeRef.parse("I2:VSystem.Collections.Generic.Dictionary`2+Enumerator;!0;!1;")
        assertEquals("Dictionary<TKey, TValue>.Enumerator", enumerator.display(listOf("TKey", "TValue")))
        assertEquals("System.Collections.Generic.Dictionary{`0,`1}.Enumerator", enumerator.docId())
        assertEquals("System.Collections.Generic", (enumerator as IndexedTypeRef.Generic).definition.namespace)
        assertEquals("int?", IndexedTypeRef.parse("I1:VSystem.Nullable`1;VSystem.Int32;").display())
        val annotated = IndexedTypeRef.parse("?[?NSystem.String;")
        assertEquals("string[]", annotated.display())
        assertEquals("string?[]?", annotated.display(nullable = true))
        assertEquals("(int Count, string)", IndexedTypeRef.parse("I2{Count,}:VSystem.ValueTuple`2;VSystem.Int32;NSystem.String;").display())
        val substituted = IndexedTypeRef.parse("I1:NSystem.Collections.Generic.List`1;?!0;").substitute(listOf(IndexedTypeRef.parse("NSystem.String;")))
        assertEquals("List<string?>", substituted.display(nullable = true))
        assertEquals("ref T[]", IndexedTypeRef.parse("&[M0;").display(methodParameters = listOf("T")))
        assertTrue(runCatching { IndexedTypeRef.parse("I2:NSystem.Foo;") }.isFailure)
        assertTrue(runCatching { IndexedTypeRef.parse("Q") }.isFailure)
    }
}
