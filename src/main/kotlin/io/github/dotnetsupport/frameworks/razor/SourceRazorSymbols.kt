package io.github.dotnetsupport.frameworks.razor

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.psi.CSharpAttributeList
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseNamespaceDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpClassOrStructConstraint
import io.github.dotnetsupport.csharp.lang.psi.CSharpConstructorConstraint
import io.github.dotnetsupport.csharp.lang.psi.CSharpDelegateDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpIndexerDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpInvocationExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpParenthesizedExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpPropertyDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpRecordDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpTypeConstraint
import io.github.dotnetsupport.csharp.lang.psi.CSharpTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpTypeOfExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpTypeParameter
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.TypeInfo
import io.github.dotnetsupport.lang.TypeKind
import io.github.dotnetsupport.lang.semantic.CSharpConstantValues
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.razor.lang.discovery.RAccessibility
import io.github.dotnetsupport.razor.lang.discovery.RArray
import io.github.dotnetsupport.razor.lang.discovery.RAssembly
import io.github.dotnetsupport.razor.lang.discovery.RAttribute
import io.github.dotnetsupport.razor.lang.discovery.RConstant
import io.github.dotnetsupport.razor.lang.discovery.RConstantKind
import io.github.dotnetsupport.razor.lang.discovery.RError
import io.github.dotnetsupport.razor.lang.discovery.RMethod
import io.github.dotnetsupport.razor.lang.discovery.RNamed
import io.github.dotnetsupport.razor.lang.discovery.RParameter
import io.github.dotnetsupport.razor.lang.discovery.RProperty
import io.github.dotnetsupport.razor.lang.discovery.RType
import io.github.dotnetsupport.razor.lang.discovery.RTypeDefinition
import io.github.dotnetsupport.razor.lang.discovery.RTypeKind
import io.github.dotnetsupport.razor.lang.discovery.RTypeParameter
import io.github.dotnetsupport.razor.lang.discovery.RTypeParameterRef
import io.github.dotnetsupport.razor.lang.discovery.RTypes

/**
 * The symbols of the sources of a solution for the tag helper discovery (RAZOR_PLAN.md, R5c): the types the C# files of a project declare —
 * and the components of its `.razor` files, through the declarations the engine makes of them ([RazorCSharpDocument.declaration]) — read by
 * the plugin's own semantics: the base classes, interfaces, attribute types and property types resolved where they are written, the types
 * of the assemblies converted through [library], the arguments of attributes evaluated as constants. One question at a time (the semantic
 * session is not shared between threads): a read action of one thread.
 *
 * A type of another project of the solution (a project reference) is a type of that project's assembly, named by its `AssemblyName`.
 */
class SourceRazorSymbols(private val project: Project, private val library: IndexRazorCompilation) {
    private val session = CSharpSemanticSession(project)
    private val definitions = HashMap<String, SourceDefinition>()
    private val model = CompilationModel.getInstance(project)
    /** The declarations of light files (the components of `.razor` files) by the key of their type: the stub index has them not. */
    private val lightParts = HashMap<String, MutableList<PsiElement>>()

    /**
     * Makes the types of [files] known before any is asked for: the parts a light file declares (a component's declaration) join the parts
     * of the same type the index has (its `.razor.cs`), in the order of [files].
     */
    fun register(files: List<CSharpFile>) {
        for (file in files) {
            if (file.viewProvider.virtualFile !is com.intellij.testFramework.LightVirtualFile) continue
            val syntax = resolver(file).syntax
            fun visit(members: List<CSharpMemberDeclaration>) {
                for (member in members) when (member) {
                    is CSharpBaseNamespaceDeclaration -> visit(namespaceMembers(member))
                    is CSharpBaseTypeDeclaration, is CSharpDelegateDeclaration -> {
                        val name = syntax.qualifiedName(member) ?: continue
                        lightParts.getOrPut(io.github.dotnetsupport.lang.TypePart.key(name, io.github.dotnetsupport.lang.TypePart.typeParameters(member))) { ArrayList() } += member
                        (member as? CSharpTypeDeclaration)?.let { visit(it.members) }
                    }
                }
            }
            visit(file.compilationUnit?.members.orEmpty())
        }
    }

    /**
     * The assembly [assemblyName] of the C# [files] in the order of the compilation, its types as `TagHelperDiscoverer` walks them: the members
     * of a namespace in the order they first appear, a namespace where its first declaration is, the nested types of public types after their type.
     */
    fun assembly(assemblyName: String, references: List<String>, files: List<CSharpFile>): RAssembly = RAssembly(assemblyName, references) {
        val root = NamespaceNode()
        for (file in files) {
            val unit = file.compilationUnit ?: continue
            collect(unit.members, root, file)
        }
        val result = ArrayList<RTypeDefinition>()
        fun visitType(definition: SourceDefinition) {
            result += definition
            if (definition.accessibility == RAccessibility.Public) for (nested in definition.nestedDeclarations()) visitType(nested)
        }
        fun visit(node: NamespaceNode) {
            for (member in node.members.values) when (member) {
                is NamespaceNode -> visit(member)
                is SourceDefinition -> visitType(member)
            }
        }
        visit(root)
        result
    }

    private class NamespaceNode {
        val members = LinkedHashMap<String, Any>()
    }

    private fun collect(members: List<CSharpMemberDeclaration>, node: NamespaceNode, file: CSharpFile) {
        for (member in members) when (member) {
            is CSharpBaseNamespaceDeclaration -> {
                var at = node
                for (segment in member.nameElement?.text.orEmpty().split('.').map { it.trim() }.filter { it.isNotEmpty() }) {
                    at = at.members.getOrPut("n:$segment") { NamespaceNode() } as NamespaceNode
                }
                collect(namespaceMembers(member), at, file)
            }
            is CSharpBaseTypeDeclaration, is CSharpDelegateDeclaration -> {
                val definition = definitionOf(member, file) ?: continue
                node.members.putIfAbsent("t:" + definition.metadataName, definition)
            }
        }
    }

    private fun namespaceMembers(declaration: CSharpBaseNamespaceDeclaration): List<CSharpMemberDeclaration> = when (declaration) {
        is io.github.dotnetsupport.csharp.lang.psi.CSharpNamespaceDeclaration -> declaration.members
        is io.github.dotnetsupport.csharp.lang.psi.CSharpFileScopedNamespaceDeclaration -> declaration.members
        else -> emptyList()
    }

    private fun resolver(file: CSharpFile): CSharpNameResolver = session.resolver(file)

    private fun definitionOf(declaration: PsiElement, file: CSharpFile): SourceDefinition? {
        val info = resolver(file).syntax.declaredType(declaration) ?: return null
        return definition(info)
    }

    /** The definition of a type of the solution: the parts of [info] that belong to the assembly of its first part. */
    fun definition(info: TypeInfo): SourceDefinition {
        val assembly = assemblyOf(info)
        return definitions.getOrPut("$assembly|${info.key}") { SourceDefinition(info, assembly) }
    }

    private fun assemblyOf(info: TypeInfo): String = info.parts.firstNotNullOfOrNull { part -> part.element()?.containingFile?.let(::assemblyOf) } ?: "?"

    private fun assemblyOf(file: com.intellij.psi.PsiFile): String? {
        val projectFile = CSharpSemanticEnvironment.projectOf(file) ?: return null
        return assemblyName(projectFile)
    }

    /** `AssemblyName` of a project: the property, else the name of the project file. */
    fun assemblyName(projectFile: VirtualFile): String = model.options(projectFile).assemblyName?.takeIf { it.isNotBlank() } ?: projectFile.nameWithoutExtension

    // ---- types

    fun type(type: SemanticType?): RType = when (type) {
        null -> RError("?")
        is SemanticType.Source -> {
            val definition = definition(type.info)
            RNamed(definition, type.arguments.map(::type), type.outer?.let { type(it) as? RNamed })
        }
        is SemanticType.Library -> library.named(type.type, type.arguments.map(::type), type.tupleNames?.map { it ?: "" }, annotated = false)
        is SemanticType.ArrayOf -> RArray(type(type.element), type.rank)
        is SemanticType.Parameter -> RTypeParameterRef(type.name, type.ofMethod)
    }

    // ---- attributes and constants

    /** [owner]: the type the attributes are of (or around the member they are of): its constants are in scope (`Attributes = FOR_ATTRIBUTE_NAME`). */
    private fun attributes(lists: List<CSharpAttributeList>, targets: Set<String?>, file: CSharpFile, owner: PsiElement? = null): List<RAttribute> {
        val resolver = resolver(file)
        val constants = CSharpConstantValues(resolver)
        val result = ArrayList<RAttribute>()
        for (list in lists) {
            if (list.target?.identifier?.text !in targets) continue
            for (attribute in list.attributes) {
                val attributeType = resolver.attributeType(attribute)
                val definition = (type(attributeType) as? RNamed)?.definition ?: MissingAttribute(attribute.nameElement?.text.orEmpty())
                val positional = ArrayList<RConstant>()
                val named = ArrayList<Pair<String, RConstant>>()
                for (argument in attribute.argumentList?.arguments.orEmpty()) {
                    val value = constant(argument.expression, resolver, constants, owner)
                    val name = argument.nameEquals?.nameElement?.identifier?.text
                    if (name != null) named += name to value else positional += value
                }
                result += RAttribute(definition, positional, named)
            }
        }
        return result
    }

    /** A constant the compiler would bind: literals and constants, `typeof`, enum members, arrays of them, `nameof`. */
    private fun constant(expression: CSharpExpression?, resolver: CSharpNameResolver, constants: CSharpConstantValues, owner: PsiElement? = null): RConstant {
        var e = expression
        while (e is CSharpParenthesizedExpression) e = e.expression
        if (e == null) return RConstant(RConstantKind.Error, null, null)
        when (e) {
            is CSharpTypeOfExpression -> return RConstant(RConstantKind.Type, null, type(e.type?.let(resolver::resolveType)))
            is io.github.dotnetsupport.csharp.lang.psi.CSharpArrayCreationExpression ->
                return RConstant(RConstantKind.Array, null, e.initializer?.expressions.orEmpty().map { constant(it, resolver, constants, owner) })
            is io.github.dotnetsupport.csharp.lang.psi.CSharpImplicitArrayCreationExpression ->
                return RConstant(RConstantKind.Array, null, e.initializer?.expressions.orEmpty().map { constant(it, resolver, constants, owner) })
            is io.github.dotnetsupport.csharp.lang.psi.CSharpCollectionExpression -> return RConstant(RConstantKind.Array, null, e.elements.mapNotNull {
                (it as? io.github.dotnetsupport.csharp.lang.psi.CSharpExpressionElement)?.expression?.let { x -> constant(x, resolver, constants, owner) }
            })
            is CSharpInvocationExpression -> if (e.expression?.text == "nameof") {
                val argument = e.argumentList?.arguments?.singleOrNull()?.expression?.text.orEmpty()
                return RConstant(RConstantKind.Primitive, null, argument.substringAfterLast('.').substringBefore('<').trim())
            }
        }
        when (e.text) {
            "true" -> return RConstant(RConstantKind.Primitive, null, true)
            "false" -> return RConstant(RConstantKind.Primitive, null, false)
        }
        val value = constants.of(e) ?: ownConstant(e, owner, constants) ?: return RConstant(RConstantKind.Error, null, null)
        return when (value.kind) {
            CSharpConstantValues.Kind.STRING -> RConstant(RConstantKind.Primitive, null, value.text)
            CSharpConstantValues.Kind.NULL -> RConstant(RConstantKind.Primitive, null, null)
            CSharpConstantValues.Kind.ENUM -> RConstant(RConstantKind.Enum, enumType(value.enum), value.number?.toLong())
            else -> RConstant(RConstantKind.Primitive, null, value.number?.toLong())
        }
    }

    /** A `const` field of [owner] (or of a type around it) named by [e]: the attribute of a type sees the type's members. */
    private fun ownConstant(e: CSharpExpression, owner: PsiElement?, constants: CSharpConstantValues): CSharpConstantValues.Value? {
        val name = (e as? io.github.dotnetsupport.csharp.lang.psi.CSharpIdentifierName)?.identifier?.text ?: return null
        var type = owner
        while (type is CSharpTypeDeclaration) {
            for (field in type.members.filterIsInstance<io.github.dotnetsupport.csharp.lang.psi.CSharpFieldDeclaration>()) {
                if (field.modifiers.none { it.text == "const" }) continue
                val variable = field.declaration?.variables?.firstOrNull { it.identifier?.text == name } ?: continue
                return constants.of(variable.initializer?.value)
            }
            type = type.parent
        }
        return null
    }

    /** The type of an enum by the key the constant evaluator gives (a type of the solution's key, or an assembly type's full name). */
    private fun enumType(key: Any?): RType? {
        val text = key as? String ?: return null
        library.typesByMetadataName(text).firstOrNull()?.let { return RNamed(it) }
        return definitions.values.firstOrNull { it.info.key == text }?.let { RNamed(it) }
    }

    private class MissingAttribute(private val text: String) : RTypeDefinition {
        override val key: String = "?|$text"
        override val assemblyName: String get() = "?"
        override val name: String get() = text.substringAfterLast('.')
        override val metadataName: String get() = name
        override val namespace: String get() = ""
        override val containingType: RTypeDefinition? get() = null
        override val kind: RTypeKind get() = RTypeKind.Error
        override val accessibility: RAccessibility get() = RAccessibility.NotApplicable
        override val isAbstract: Boolean get() = false
        override val isStatic: Boolean get() = false
        override val specialType: String? get() = null
        override val arity: Int get() = 0
        override val typeParameters: List<RTypeParameter> get() = emptyList()
        override val baseType: RNamed? get() = null
        override val allInterfaces: List<RNamed> get() = emptyList()
        override val attributes: List<RAttribute> get() = emptyList()
        override val documentation: String? get() = null
        override val properties: List<RProperty> get() = emptyList()
        override val methods: List<RMethod> get() = emptyList()
    }

    // ---- definitions

    inner class SourceDefinition(val info: TypeInfo, override val assemblyName: String) : RTypeDefinition {
        /** The declarations of the parts of this assembly, in the order of the files. */
        private val declarations: List<PsiElement> by lazy {
            // a component's declaration first (the compilation has the declarations of the `.razor` files before the C# files), then the
            // parts of the index — not the Razor generator's output of the same component
            val parts = lightParts[info.key].orEmpty() + info.parts.mapNotNull { it.element() }.filter { element ->
                element.containingFile?.viewProvider?.virtualFile?.let { !RazorTagHelpers.isRazorOutput(it) } != false
            }
            parts.distinct().filter { (it.containingFile?.let(::assemblyOf) ?: assemblyName) == assemblyName }
        }
        private val modifiers: Set<String> by lazy { declarations.flatMap { d -> (d as? CSharpMemberDeclaration)?.modifiers.orEmpty().map { it.text } }.toSet() }
        private val first: PsiElement? get() = declarations.firstOrNull()
        private val file: CSharpFile? get() = first?.containingFile as? CSharpFile

        override val key: String = "$assemblyName|${metadataFullName()}"
        override val name: String get() = info.qualifiedName.substringAfterLast('.')
        override val metadataName: String get() = if (info.arity > 0) "$name`${info.arity}" else name
        override val namespace: String
            get() {
                var outermost: PsiElement? = first
                while (outermost?.parent is CSharpBaseTypeDeclaration) outermost = outermost.parent
                return outermost?.let { resolver(file ?: return "").syntax.declaredType(it)?.qualifiedName?.substringBeforeLast('.', "") } ?: info.qualifiedName.substringBeforeLast('.', "")
            }
        override val containingType: RTypeDefinition? by lazy {
            val parent = first?.parent as? CSharpBaseTypeDeclaration ?: return@lazy null
            val owner = file ?: return@lazy null
            resolver(owner).syntax.declaredType(parent)?.let(::definition)
        }

        private fun metadataFullName(): String {
            val names = ArrayList<String>()
            var at: PsiElement? = info.parts.firstNotNullOfOrNull { it.element() }
            var namespace = info.qualifiedName.substringBeforeLast('.', "")
            while (at is CSharpBaseTypeDeclaration || at is CSharpDelegateDeclaration) {
                val arity = (at as? CSharpTypeDeclaration)?.typeParameterList?.parameters?.size ?: (at as? CSharpDelegateDeclaration)?.typeParameterList?.parameters?.size ?: 0
                val simple = (at as? CSharpBaseTypeDeclaration)?.identifier?.text ?: (at as? CSharpDelegateDeclaration)?.identifier?.text ?: ""
                names.add(0, if (arity > 0) "$simple`$arity" else simple)
                at = at.parent
                if (at is CSharpBaseTypeDeclaration) namespace = namespace.substringBeforeLast('.', "")
            }
            return (if (namespace.isEmpty()) "" else "$namespace.") + names.joinToString("+")
        }

        override val kind: RTypeKind
            get() = when (info.kind) {
                TypeKind.CLASS, TypeKind.STATIC_CLASS, TypeKind.RECORD -> RTypeKind.Class
                TypeKind.STRUCT, TypeKind.RECORD_STRUCT -> RTypeKind.Struct
                TypeKind.INTERFACE -> RTypeKind.Interface
                TypeKind.ENUM -> RTypeKind.Enum
                TypeKind.DELEGATE -> RTypeKind.Delegate
                null -> RTypeKind.Error
            }
        override val accessibility: RAccessibility
            get() = accessibilityOf(modifiers, nested = first?.parent is CSharpBaseTypeDeclaration, inInterface = false)
        override val isAbstract: Boolean get() = "abstract" in modifiers || "static" in modifiers || info.kind == TypeKind.INTERFACE
        override val isStatic: Boolean get() = "static" in modifiers
        override val specialType: String? get() = null
        override val arity: Int get() = info.arity

        private val typeParameterOwner: PsiElement? get() = declarations.firstOrNull { (it as? CSharpTypeDeclaration)?.typeParameterList != null || (it as? CSharpDelegateDeclaration)?.typeParameterList != null }
        override val typeParameters: List<RTypeParameter> by lazy {
            val owner = typeParameterOwner ?: return@lazy emptyList()
            val parameters = typeParameterList(owner)
            val clauses = declarations.flatMap { (it as? CSharpTypeDeclaration)?.constraintClauses.orEmpty() + (it as? CSharpDelegateDeclaration)?.constraintClauses.orEmpty() }
            parameters.mapIndexed { i, parameter ->
                // the parts of a partial type each may give a type parameter attributes: all of them, in the order of the parts
                val attributes = declarations.flatMap { part ->
                    val same = typeParameterList(part).getOrNull(i) ?: return@flatMap emptyList()
                    attributes(same.attributeLists, setOf(null, "typevar"), same.containingFile as CSharpFile, part)
                }
                typeParameter(parameter, clauses.firstOrNull { it.nameElement?.identifier?.text == parameter.identifier?.text }, attributes)
            }
        }

        private fun typeParameterList(declaration: PsiElement): List<CSharpTypeParameter> =
            (declaration as? CSharpTypeDeclaration)?.typeParameterList?.parameters ?: (declaration as? CSharpDelegateDeclaration)?.typeParameterList?.parameters.orEmpty()

        private fun typeParameter(parameter: CSharpTypeParameter, clause: io.github.dotnetsupport.csharp.lang.psi.CSharpTypeParameterConstraintClause?, attributes: List<RAttribute>): RTypeParameter {
            // a constraint is resolved where its clause is written (the `.razor.cs` part of a component with its usings)
            val owner = (clause?.containingFile ?: parameter.containingFile) as CSharpFile
            var isClass = false
            var isStruct = false
            var isUnmanaged = false
            var isNotNull = false
            var isNew = false
            val types = ArrayList<RType>()
            for (constraint in clause?.constraints.orEmpty()) when (constraint) {
                is CSharpClassOrStructConstraint -> if (constraint.classOrStructKeyword?.text == "class") isClass = true else isStruct = true
                is CSharpConstructorConstraint -> isNew = true
                is CSharpTypeConstraint -> when (constraint.type?.text) {
                    "notnull" -> isNotNull = true
                    "unmanaged" -> {
                        isUnmanaged = true
                        isStruct = true
                    }
                    else -> types += type(constraint.type?.let(resolver(owner)::resolveType))
                }
            }
            return RTypeParameter(parameter.identifier?.text.orEmpty(), isClass, isStruct, isUnmanaged, isNotNull, isNew, types, attributes)
        }

        private val bases: List<RType> by lazy {
            val owner = file ?: return@lazy emptyList()
            val resolver = resolver(owner)
            resolver.baseTypes(resolver.selfType(info)).map(::type)
        }

        override val baseType: RNamed? by lazy {
            bases.filterIsInstance<RNamed>().firstOrNull { it.definition.kind != RTypeKind.Interface }.takeIf { info.kind != TypeKind.INTERFACE }
        }

        override val allInterfaces: List<RNamed> by lazy {
            val found = LinkedHashMap<String, RNamed>()
            fun add(type: RNamed) {
                found.putIfAbsent(io.github.dotnetsupport.razor.lang.discovery.RazorTypeDisplay.globallyQualified(type), type)
            }
            for (base in bases.filterIsInstance<RNamed>()) {
                if (base.definition.kind == RTypeKind.Interface) add(base)
                if (base.definition !== this) RTypes.allInterfaces(base).forEach(::add)
            }
            found.values.toList()
        }

        override val attributes: List<RAttribute> by lazy {
            declarations.flatMap { d -> (d as? CSharpMemberDeclaration)?.let { attributes(it.attributeLists, setOf(null, "type"), it.containingFile as CSharpFile, it) }.orEmpty() }
        }
        override val documentation: String? get() = null

        override val properties: List<RProperty> by lazy {
            val result = ArrayList<RProperty>()
            for (declaration in declarations) {
                val owner = declaration.containingFile as? CSharpFile ?: continue
                // a record's positional parameters are its properties { get; init; }
                (declaration as? CSharpRecordDeclaration)?.parameterList?.parameters?.forEach { parameter ->
                    val type = type(parameter.type?.let(resolver(owner)::resolveType))
                    result += property(parameter.identifier?.text.orEmpty(), type, RAccessibility.Public, false, false, 0, RAccessibility.Public, RAccessibility.Public, true,
                        attributes(parameter.attributeLists, setOf("property"), owner))
                }
                for (member in (declaration as? CSharpTypeDeclaration)?.members.orEmpty()) {
                    when (member) {
                        is CSharpPropertyDeclaration -> result += sourceProperty(member, member.identifier?.text.orEmpty(), 0, owner)
                        is CSharpIndexerDeclaration -> result += sourceProperty(member, "this[]", member.parameterList?.parameters?.size ?: 1, owner)
                    }
                }
            }
            result
        }

        private fun sourceProperty(member: io.github.dotnetsupport.csharp.lang.psi.CSharpBasePropertyDeclaration, name: String, parameters: Int, owner: CSharpFile): RProperty {
            val modifiers = member.modifiers.map { it.text }.toSet()
            val inInterface = info.kind == TypeKind.INTERFACE
            val access = accessibilityOf(modifiers, nested = true, inInterface = inInterface)
            var getter: RAccessibility? = null
            var setter: RAccessibility? = null
            var initOnly = false
            val accessors = member.accessorList?.accessors
            if (accessors == null) {
                // `=> value`: a getter only
                getter = access
            } else {
                for (accessor in accessors) {
                    val own = accessor.modifiers.map { it.text }.toSet().takeIf { it.isNotEmpty() }?.let { accessibilityOf(it, nested = true, inInterface = inInterface) } ?: access
                    when (accessor.keyword?.text) {
                        "get" -> getter = own
                        "set" -> setter = own
                        "init" -> {
                            setter = own
                            initOnly = true
                        }
                    }
                }
            }
            val type = type(member.type?.let(resolver(owner)::resolveType))
            return property(name, type, access, "static" in modifiers, "override" in modifiers, parameters, getter, setter, initOnly,
                attributes(member.attributeLists, setOf(null, "property"), owner, member.parent))
        }

        private fun property(
            name: String, type: RType, access: RAccessibility, isStatic: Boolean, isOverride: Boolean, parameters: Int, getter: RAccessibility?, setter: RAccessibility?,
            initOnly: Boolean, attributes: List<RAttribute>,
        ): RProperty = RProperty(
            name, type, access, isStatic, isOverride, if (isOverride) ({ overridden(name) }) else null, parameters, getter, setter, initOnly, attributes, null,
            acceptsStringLiteral = false, dictionaryAssignable = library.acceptsStringObjectDictionary(type), dictionaryArguments = RTypes.dictionaryArguments(type),
            isAwaitableDelegate = library.isAwaitableDelegate(type),
        )

        /** The property of [name] in the nearest base class that has one (`OverriddenProperty`). */
        private fun overridden(name: String): RProperty? {
            var base = baseType
            val seen = HashSet<String>()
            while (base != null && seen.add(base.definition.key)) {
                base.definition.properties.firstOrNull { it.name == name }?.let { return it }
                base = base.definition.baseType
            }
            return null
        }

        override val methods: List<RMethod> by lazy {
            val result = ArrayList<RMethod>()
            for (declaration in declarations) {
                val owner = declaration.containingFile as? CSharpFile ?: continue
                val resolver = resolver(owner)
                if (declaration is CSharpDelegateDeclaration) {
                    // a delegate's Invoke: its return type and parameters
                    val returns = declaration.returnType
                    result += RMethod("Invoke", RAccessibility.Public, false, returns?.text == "void", type(returns?.let(resolver::resolveType)),
                        declaration.parameterList?.parameters.orEmpty().map { RParameter(it.identifier?.text.orEmpty(), type(it.type?.let(resolver::resolveType)), it.default != null) })
                    continue
                }
                for (member in (declaration as? CSharpTypeDeclaration)?.members.orEmpty()) {
                    val method = member as? CSharpMethodDeclaration ?: continue
                    if (method.explicitInterfaceSpecifier != null) continue
                    val modifiers = method.modifiers.map { it.text }.toSet()
                    val returns = method.returnType
                    result += RMethod(
                        method.identifier?.text.orEmpty(), accessibilityOf(modifiers, nested = true, inInterface = info.kind == TypeKind.INTERFACE), "static" in modifiers,
                        returns?.text == "void", type(returns?.let(resolver::resolveType)),
                        method.parameterList?.parameters.orEmpty().map { p ->
                            val parameterType = type(p.type?.let(resolver::resolveType))
                            RParameter(p.identifier?.text.orEmpty(), parameterType, p.default != null, RTypes.dictionaryArguments(parameterType))
                        },
                    )
                }
            }
            result
        }

        /** The types declared in the parts of this type, in the order of the declarations. */
        fun nestedDeclarations(): List<SourceDefinition> {
            val result = LinkedHashMap<String, SourceDefinition>()
            for (declaration in declarations) {
                val owner = declaration.containingFile as? CSharpFile ?: continue
                for (member in (declaration as? CSharpTypeDeclaration)?.members.orEmpty()) {
                    if (member !is CSharpBaseTypeDeclaration && member !is CSharpDelegateDeclaration) continue
                    val nested = definitionOf(member, owner) ?: continue
                    result.putIfAbsent(nested.key, nested)
                }
            }
            return result.values.toList()
        }

        override fun equals(other: Any?): Boolean = other is RTypeDefinition && other.key == key
        override fun hashCode(): Int = key.hashCode()
        override fun toString(): String = key
    }

    companion object {
        /** The accessibility its modifiers give a type or a member; none — internal at the top, private nested, public in an interface. */
        fun accessibilityOf(modifiers: Set<String>, nested: Boolean, inInterface: Boolean): RAccessibility = when {
            "public" in modifiers -> RAccessibility.Public
            "protected" in modifiers && "internal" in modifiers -> RAccessibility.ProtectedOrInternal
            "private" in modifiers && "protected" in modifiers -> RAccessibility.ProtectedAndInternal
            "protected" in modifiers -> RAccessibility.Protected
            "internal" in modifiers -> RAccessibility.Internal
            "private" in modifiers -> RAccessibility.Private
            inInterface -> RAccessibility.Public
            nested -> RAccessibility.Private
            else -> RAccessibility.Internal
        }
    }
}
