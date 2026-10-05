package io.github.dotnetsupport.csharp.lang.psi.stubs

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.StubBuilder
import com.intellij.psi.stubs.DefaultStubBuilder
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.LanguageStubDefinition
import com.intellij.psi.stubs.PsiFileStub
import com.intellij.psi.stubs.PsiFileStubImpl
import com.intellij.psi.stubs.StubBase
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.psi.stubs.StubRegistry
import com.intellij.psi.stubs.StubRegistryExtension
import com.intellij.psi.stubs.StubSerializer
import com.intellij.psi.stubs.StubSerializingElementFactory
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.dotnetsupport.csharp.lang.CSharpParserDefinition
import io.github.dotnetsupport.csharp.lang.SyntaxKind as K
import io.github.dotnetsupport.csharp.lang.psi.CSharpAliasQualifiedName
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseMethodDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames
import io.github.dotnetsupport.csharp.lang.psi.CSharpDelegateDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpElement
import io.github.dotnetsupport.csharp.lang.psi.CSharpIndexerDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpName
import io.github.dotnetsupport.csharp.lang.psi.CSharpQualifiedName
import io.github.dotnetsupport.csharp.lang.psi.CSharpSimpleName
import io.github.dotnetsupport.csharp.lang.psi.CSharpTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.impl.CSharpPsiImplTable

/**
 * The stub of a node of a stub-based class (docs/csharp-psi/GRAMMAR.md, "Stubs"): syntax only, as written. [name] is
 * [CSharpDeclarationNames.name] (null for the compilation unit, an extension block, the variable declaration of a field, a field with several
 * declarators and a declaration whose name is missing); [flags] the modifiers ([CSharpStubs.MODIFIERS], bit per position) and
 * [CSharpStubs.EXTENSION]; [arity] the number of type parameters of a type, delegate or method; [parameters] the parameter list of a method,
 * constructor, destructor, operator, indexer, delegate or primary constructor with its whitespace collapsed to one space; [baseTypes] the
 * types of a base list as written (whitespace collapsed); [attributes] the simple names of the attributes of a member as written (`Fact` of
 * `[Xunit.Fact]`, `FactAttribute`).
 */
class CSharpStub(
    parent: StubElement<*>?, type: IElementType,
    val name: String?, val flags: Int, val arity: Int, val parameters: String?, val baseTypes: List<String>, val attributes: List<String>,
) : StubBase<CSharpElement>(parent, type) {
    /** The modifier keywords of the declaration, in [CSharpStubs.MODIFIERS] order. */
    val modifiers: List<String> get() = CSharpStubs.MODIFIERS.filterIndexed { i, _ -> flags and (1 shl i) != 0 }

    val isExtensionMethod: Boolean get() = flags and CSharpStubs.EXTENSION != 0

    override fun toString(): String = buildString {
        append("CSharpStub")
        name?.let { append(" name=").append(it) }
        if (flags != 0) append(" modifiers=").append(modifiers).append(if (isExtensionMethod) " extension" else "")
        if (arity != 0) append(" arity=").append(arity)
        parameters?.let { append(" parameters=").append(it) }
        if (baseTypes.isNotEmpty()) append(" bases=").append(baseTypes)
        if (attributes.isNotEmpty()) append(" attributes=").append(attributes)
    }
}

/** The stub layer of csharp-psi (CSHARP_PSI_MIGRATION.md, step 8): version, modifier bits, the rules of which nodes are stubbed. */
object CSharpStubs {
    /** The version of the stubs: bump on any change of what is stubbed or how it is serialized ([CSharpStubElementFactory]), of [CSharpStubRules]. */
    const val VERSION = 2

    /** Roslyn's modifier keywords; a stub keeps bit `i` for `MODIFIERS[i]`. Append only (the bits are serialized). */
    val MODIFIERS: List<String> = listOf(
        "public", "private", "protected", "internal", "file", "static", "abstract", "sealed", "virtual", "override", "new", "readonly", "const",
        "volatile", "extern", "unsafe", "async", "partial", "required", "ref", "fixed", "scoped",
    )

    /** A method whose first parameter has the `this` modifier. */
    const val EXTENSION: Int = 1 shl 30

    private val WHITESPACE = Regex("""\s+""")

    /** Whitespace runs to one space, as the declaration model of the host shows parameter lists and types. */
    fun collapse(text: String): String = text.replace(WHITESPACE, " ")

    /** `Fact` of `Fact`, `Xunit.Fact`, `global::Xunit.Fact`; null for a name that is not there. */
    fun simpleName(name: CSharpName?): String? = when (name) {
        is CSharpSimpleName -> name.identifier?.text
        is CSharpQualifiedName -> simpleName(name.right)
        is CSharpAliasQualifiedName -> simpleName(name.nameElement)
        else -> null
    }

    /** The key of an attribute in [CSharpStubIndexKeys.ATTRIBUTES]: its simple name without the `Attribute` suffix (`Fact` for `FactAttribute`). */
    fun attributeKey(name: String): String = name.removeSuffix("Attribute").ifEmpty { name }

    /** The stub of [psi] under [parent]: everything from the PSI (the AST is there while stubs are built). */
    fun create(psi: PsiElement, parent: StubElement<*>?, type: IElementType): CSharpStub {
        var flags = 0
        (psi as? CSharpMemberDeclaration)?.modifiers?.forEach { modifier -> MODIFIERS.indexOf(modifier.text).takeIf { it >= 0 }?.let { flags = flags or (1 shl it) } }
        if (psi is CSharpMethodDeclaration && psi.parameterList?.parameters?.firstOrNull()?.modifiers?.any { it.node.elementType == K.ThisKeyword } == true) flags = flags or EXTENSION
        val arity = when (psi) {
            is CSharpTypeDeclaration -> psi.typeParameterList?.parameters?.size
            is CSharpDelegateDeclaration -> psi.typeParameterList?.parameters?.size
            is CSharpMethodDeclaration -> psi.typeParameterList?.parameters?.size
            else -> null
        } ?: 0
        val parameters = when (psi) {
            is CSharpTypeDeclaration -> psi.parameterList
            is CSharpDelegateDeclaration -> psi.parameterList
            is CSharpBaseMethodDeclaration -> psi.parameterList
            is CSharpIndexerDeclaration -> psi.parameterList
            else -> null
        }?.let { collapse(it.text) }
        val baseTypes = (psi as? CSharpBaseTypeDeclaration)?.baseList?.types.orEmpty().mapNotNull { it.type?.text?.let(::collapse) }
        val attributes = (psi as? CSharpMemberDeclaration)?.attributeLists.orEmpty().flatMap { list -> list.attributes.mapNotNull { simpleName(it.nameElement) } }
        return CSharpStub(parent, type, CSharpDeclarationNames.name(psi), flags, arity, parameters, baseTypes, attributes)
    }
}

/**
 * Which nodes of a stub-based class get a stub: the declarations outside bodies, each under a node that has one, so that the parent of a stub is
 * always the parent of its node (`StubBasedPsiElementBase.getParent` answers by the stub). Namespaces under the compilation unit or a namespace;
 * types and delegates there or in a class, struct, interface or record; members (methods, constructors, destructors, operators, properties,
 * indexers, events, fields) in those or in their `extension` blocks; enum members in an enum; the variable declaration of a field and its
 * declarators. As the host's declaration model sees declarations: members at the level of a namespace or the file (errors, top-level
 * statements) and everything inside bodies (local functions, locals) get none.
 */
object CSharpStubRules {
    @JvmField val NAMESPACES: TokenSet = TokenSet.create(K.NamespaceDeclaration, K.FileScopedNamespaceDeclaration)

    /** What Go to Class lists. */
    @JvmField val TYPES: TokenSet = TokenSet.create(
        K.ClassDeclaration, K.StructDeclaration, K.InterfaceDeclaration, K.RecordDeclaration, K.RecordStructDeclaration, K.EnumDeclaration, K.DelegateDeclaration,
    )

    /** Types that hold members and nested types. */
    @JvmField val CONTAINERS: TokenSet = TokenSet.create(K.ClassDeclaration, K.StructDeclaration, K.InterfaceDeclaration, K.RecordDeclaration, K.RecordStructDeclaration)

    @JvmField val MEMBERS: TokenSet = TokenSet.create(
        K.MethodDeclaration, K.ConstructorDeclaration, K.DestructorDeclaration, K.OperatorDeclaration, K.ConversionOperatorDeclaration,
        K.PropertyDeclaration, K.IndexerDeclaration, K.EventDeclaration, K.FieldDeclaration, K.EventFieldDeclaration,
    )

    @JvmField val FIELDS: TokenSet = TokenSet.create(K.FieldDeclaration, K.EventFieldDeclaration)

    fun isStubbed(node: ASTNode): Boolean {
        val type = node.elementType
        val parent = node.treeParent ?: return false
        val p = parent.elementType
        val placed = when {
            type === K.CompilationUnit -> return parent.treeParent == null
            type in NAMESPACES -> p === K.CompilationUnit || p in NAMESPACES
            type in TYPES -> p === K.CompilationUnit || p in NAMESPACES || p in CONTAINERS
            type === K.ExtensionBlockDeclaration -> p in CONTAINERS
            type in MEMBERS -> p in CONTAINERS || p === K.ExtensionBlockDeclaration
            type === K.EnumMemberDeclaration -> p === K.EnumDeclaration
            type === K.VariableDeclaration -> p in FIELDS
            type === K.VariableDeclarator -> p === K.VariableDeclaration
            else -> false
        }
        return placed && isStubbed(parent)
    }

    /**
     * What a stub is indexed as: a type of [TYPES] by name, a member by name (a field by its declarator when it has one, each declarator of a
     * field with several, an enum member), never a declaration inside a namespace or type whose name is missing (the declaration model drops
     * those with their contents).
     */
    fun index(stub: CSharpStub, sink: IndexSink) {
        val name = stub.name ?: return
        val type = stub.elementType
        if (type in NAMESPACES) {
            namespaceName(stub)?.let { full -> prefixes(full).forEach { sink.occurrence(CSharpStubIndexKeys.NAMESPACES, it) } }
            return
        }
        val member = type in MEMBERS || type === K.EnumMemberDeclaration || type === K.VariableDeclarator && (stub.parentStub?.childrenStubs?.size ?: 0) > 1
        if (type !in TYPES && !member) return
        var parent = stub.parentStub
        while (parent is CSharpStub) {
            if ((parent.elementType in NAMESPACES || parent.elementType in TYPES) && parent.name == null) return
            parent = parent.parentStub
        }
        sink.occurrence(if (type in TYPES) CSharpStubIndexKeys.TYPE_NAMES else CSharpStubIndexKeys.MEMBER_NAMES, name)
        if (stub.isExtensionMethod) sink.occurrence(CSharpStubIndexKeys.EXTENSION_METHODS, name)
        if (type in TYPES || type === K.MethodDeclaration) stub.attributes.mapTo(HashSet(), CSharpStubs::attributeKey).forEach { sink.occurrence(CSharpStubIndexKeys.ATTRIBUTES, it) }
    }

    /** `A.B.C` of a namespace declaration stub: the names of the declarations around it and its own; null when one is missing. */
    fun namespaceName(stub: CSharpStub): String? {
        val names = ArrayList<String>()
        var current: StubElement<*>? = stub
        while (current is CSharpStub) {
            if (current.elementType in NAMESPACES) names += current.name?.removePrefix("global::") ?: return null
            current = current.parentStub
        }
        return names.asReversed().joinToString(".")
    }

    /** `A.B.C` -> `A`, `A.B`, `A.B.C`. */
    fun prefixes(namespace: String): List<String> {
        val parts = namespace.split('.')
        return List(parts.size) { parts.subList(0, it + 1).joinToString(".") }
    }
}

/** Stub, PSI from a stub, serializer and indexing of one element type of a stub-based class; registered by [CSharpStubRegistryExtension]. */
class CSharpStubElementFactory(private val type: IElementType) : StubSerializingElementFactory<CSharpStub, PsiElement> {
    override fun createStub(psi: PsiElement, parentStub: StubElement<out PsiElement>?): CSharpStub = CSharpStubs.create(psi, parentStub, type)

    override fun createPsi(stub: CSharpStub): PsiElement = CSharpPsiImplTable.stubConstructors.getValue(type)(stub, type)

    override fun shouldCreateStub(node: ASTNode): Boolean = CSharpStubRules.isStubbed(node)

    override fun getExternalId(): String = "csharp.$type"

    override fun serialize(stub: CSharpStub, out: StubOutputStream) {
        out.writeName(stub.name)
        out.writeVarInt(stub.flags)
        out.writeVarInt(stub.arity)
        out.writeName(stub.parameters)
        writeNames(out, stub.baseTypes)
        writeNames(out, stub.attributes)
    }

    override fun deserialize(input: StubInputStream, parentStub: StubElement<*>?): CSharpStub =
        CSharpStub(parentStub, type, input.readNameString(), input.readVarInt(), input.readVarInt(), input.readNameString(), readNames(input), readNames(input))

    override fun indexStub(stub: CSharpStub, sink: IndexSink) = CSharpStubRules.index(stub, sink)

    private fun writeNames(out: StubOutputStream, names: List<String>) {
        out.writeVarInt(names.size)
        names.forEach(out::writeName)
    }

    private fun readNames(input: StubInputStream): List<String> = List(input.readVarInt()) { input.readNameString().orEmpty() }
}

/** The file stub: nothing of its own (the root of the stubs of [CSharpParserDefinition.FILE]). */
object CSharpFileStubSerializer : StubSerializer<PsiFileStub<*>> {
    override fun getExternalId(): String = "csharp.FILE"
    override fun serialize(stub: PsiFileStub<*>, out: StubOutputStream) = Unit
    override fun deserialize(input: StubInputStream, parentStub: StubElement<*>?): PsiFileStub<*> = PsiFileStubImpl<PsiFile>(null)
    override fun indexStub(stub: PsiFileStub<*>, sink: IndexSink) = Unit
}

/** Stubs are built from the AST, without looking into what no stub can be under ([CSharpStubRules]): bodies, expressions, attribute arguments. */
class CSharpStubBuilder : DefaultStubBuilder() {
    override fun skipChildProcessingWhenBuildingStubs(parent: ASTNode, node: ASTNode): Boolean = parent.treeParent != null && !CSharpStubRules.isStubbed(parent)
}

/**
 * Stubs of the C# language (`languageStubDefinition`). The platform asks for them by language, and through the file element type of the
 * registered parser definition: in the plugin that is the host's switch, so a file has stubs only while the native tree is chosen (the
 * heuristic file element type has no serializer).
 */
class CSharpStubDefinition : LanguageStubDefinition {
    override val builder: StubBuilder get() = BUILDER
    override val stubVersion: Int get() = CSharpStubs.VERSION

    private companion object {
        val BUILDER = CSharpStubBuilder()
    }
}

/** Registers the file stub serializer and a [CSharpStubElementFactory] per kind of every stub-based class (`stubElementRegistryExtension`). */
class CSharpStubRegistryExtension : StubRegistryExtension {
    override fun register(registry: StubRegistry) {
        registry.registerStubSerializer(CSharpParserDefinition.FILE, CSharpFileStubSerializer)
        for (type in CSharpPsiImplTable.stubConstructors.keys) registry.registerStubSerializingFactory(type, CSharpStubElementFactory(type))
    }
}
