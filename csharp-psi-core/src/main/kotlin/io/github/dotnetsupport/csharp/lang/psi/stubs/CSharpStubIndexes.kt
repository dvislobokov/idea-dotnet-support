package io.github.dotnetsupport.csharp.lang.psi.stubs

import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndexKey
import io.github.dotnetsupport.csharp.lang.psi.CSharpElement

/** The stub indexes of csharp-psi ([CSharpStubRules.index]); keys are names as written. */
object CSharpStubIndexKeys {
    /** Classes, structs, interfaces, enums, records, delegates by name (Go to Class). */
    @JvmField val TYPE_NAMES: StubIndexKey<String, CSharpElement> = StubIndexKey.createIndexKey("csharp.type.name")

    /** Members of types by name: methods, constructors, destructors (`~T`), operators (`operator ==`), properties, indexers (`this`), events, fields, enum members (Go to Symbol). */
    @JvmField val MEMBER_NAMES: StubIndexKey<String, CSharpElement> = StubIndexKey.createIndexKey("csharp.member.name")

    /** Extension methods (the first parameter is `this`) by name. */
    @JvmField val EXTENSION_METHODS: StubIndexKey<String, CSharpElement> = StubIndexKey.createIndexKey("csharp.extension.method")

    /** Types and methods by the simple names of their attributes without the `Attribute` suffix ([CSharpStubs.attributeKey]): `Fact`, `Test`. */
    @JvmField val ATTRIBUTES: StubIndexKey<String, CSharpElement> = StubIndexKey.createIndexKey("csharp.attribute")

    /**
     * Namespace declarations by the full name of every namespace they declare: `namespace A.B { namespace C {} }` is under `A`, `A.B` and
     * (its inner declaration) `A.B.C` — whether a namespace of the solution exists (the semantics, step 11a).
     */
    @JvmField val NAMESPACES: StubIndexKey<String, CSharpElement> = StubIndexKey.createIndexKey("csharp.namespace")

    /**
     * Types by the simple names of the types of their base lists ([CSharpStubs.baseTypeKey]): `class A : Ns.B<int>, IC` is under `B` and
     * `IC` — the candidates for the subtypes of a type (Go to Implementation, Type Hierarchy, the gutter of the host).
     */
    @JvmField val SUPERTYPES: StubIndexKey<String, CSharpElement> = StubIndexKey.createIndexKey("csharp.supertype")
}

abstract class CSharpStubIndex(private val key: StubIndexKey<String, CSharpElement>) : StringStubIndexExtension<CSharpElement>() {
    override fun getKey(): StubIndexKey<String, CSharpElement> = key
    override fun getVersion(): Int = super.getVersion() + CSharpStubs.VERSION
}

class CSharpTypeNameIndex : CSharpStubIndex(CSharpStubIndexKeys.TYPE_NAMES)

class CSharpMemberNameIndex : CSharpStubIndex(CSharpStubIndexKeys.MEMBER_NAMES)

class CSharpExtensionMethodIndex : CSharpStubIndex(CSharpStubIndexKeys.EXTENSION_METHODS)

class CSharpAttributeIndex : CSharpStubIndex(CSharpStubIndexKeys.ATTRIBUTES)

class CSharpNamespaceIndex : CSharpStubIndex(CSharpStubIndexKeys.NAMESPACES)

class CSharpSupertypeIndex : CSharpStubIndex(CSharpStubIndexKeys.SUPERTYPES)
