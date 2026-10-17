package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.codegen.CSharpIdentifier
import io.github.dotnetsupport.razor.lang.codegen.RazorCodeDocument
import io.github.dotnetsupport.razor.lang.codegen.RazorOptimizationPass
import io.github.dotnetsupport.razor.lang.codegen.isNullOrDotNetWhiteSpace
import io.github.dotnetsupport.razor.lang.intermediate.CSharpIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.CascadingGenericTypeParameter
import io.github.dotnetsupport.razor.lang.intermediate.ClassDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CommonModifiers
import io.github.dotnetsupport.razor.lang.intermediate.ComponentAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentTypeArgumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentTypeInferenceMethodIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeWalker
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.NamespaceDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.findDescendantNodes
import io.github.dotnetsupport.razor.lang.syntax.BoundAttributeDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TypeParameterMetadata
import io.github.dotnetsupport.razor.lang.syntax.getGloballyQualifiedTypeName

/**
 * `ComponentGenericTypePass` (`ComponentGenericTypePass.cs`): for each use of a generic component, the diagnostics of missing type
 * arguments; the component's type name and the types of its parameters and child contents with the type arguments substituted; and,
 * when no type argument is given, the type inference method the C# compiler infers them with (also from the type parameters ancestors
 * cascade). Runs after the components, event handler, ref, bind and template passes.
 */
class ComponentGenericTypePass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = 160

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        val visitor = Visitor()
        visitor.visit(documentNode)
    }

    internal class Visitor : IntermediateNodeWalker() {
        // Incrementing ID for type inference method names
        private var id = 0

        override fun visitComponent(node: ComponentIntermediateNode) {
            // Not generic, ignore.
            if (node.component!!.isGenericTypedComponent()) process(node)

            super.visitDefault(node)
        }

        private fun process(node: ComponentIntermediateNode) {
            // First collect all of the information we have about each type parameter: all the type parameters that exist.
            val bindings = LinkedHashMap<String, Binding>()
            val componentTypeParameters = node.component!!.getTypeParameters()
            val supplyCascadingTypeParameters = componentTypeParameters.filter { it.isCascadingTypeParameterProperty() }.map { it.name }
            for (attribute in componentTypeParameters) {
                check(!bindings.containsKey(attribute.name)) { "An item with the same key has already been added. Key: ${attribute.name}" } // Dictionary.Add
                bindings[attribute.name] = Binding(attribute)
            }

            // Listing all type arguments that have been specified.
            var hasTypeArgumentSpecified = false
            for (typeArgumentNode in node.typeArguments) {
                hasTypeArgumentSpecified = true

                val binding = bindings.getValue(typeArgumentNode.typeParameterName)
                binding.node = typeArgumentNode
                binding.content = typeArgumentNode.value

                // Offer this explicit type argument to descendants too
                if (supplyCascadingTypeParameters.contains(typeArgumentNode.typeParameterName)) {
                    val provides = node.providesCascadingGenericTypes ?: LinkedHashMap<String, CascadingGenericTypeParameter>().also { node.providesCascadingGenericTypes = it }
                    provides[typeArgumentNode.typeParameterName] = CascadingGenericTypeParameter().also {
                        it.genericTypeNames = listOf(typeArgumentNode.typeParameterName)
                        it.valueType = typeArgumentNode.typeParameterName
                        it.valueExpression = "default(${binding.content!!.content})"
                    }
                }
            }

            if (hasTypeArgumentSpecified) {
                // The developer specified at least one type parameter: either all of them, and it is rewritten, or it is an error.
                if (validateTypeArguments(node, bindings)) {
                    val mappings = bindings.mapValuesTo(LinkedHashMap()) { it.value.node }
                    rewriteTypeNames(GenericTypeNameRewriter(mappings), node, hasTypeArgumentSpecified)
                }

                return
            }

            // No type arguments were specified: the C# compiler infers them from a method emitted to represent this use of the component.

            // The code is generated in a different namespace: all the types are globally qualified, to avoid clashes with it.
            rewriteTypeNames(GlobalQualifiedTypeNameRewriter(bindings.keys), node, hasTypeArgumentSpecified = false, bindings = bindings)

            // An argument must 'cover' each type parameter: a repeater whose generic item type comes from the unset items cannot be inferred.
            for (attribute in node.attributes) {
                var typeParameters = tryFindGenericTypeNames(attribute.boundAttribute, attribute.globallyQualifiedTypeName) ?: continue

                // Keep only type parameters defined by this component.
                typeParameters = typeParameters.filter { bindings.containsKey(it) }

                val attributeValueIsLambda = CSharpTypeNames.isLambdaExpression(getContent(attribute))
                val provideCascadingGenericTypes = CascadingGenericTypeParameter().also {
                    it.genericTypeNames = typeParameters
                    it.valueType = attribute.globallyQualifiedTypeName
                    it.valueSourceNode = attribute
                }

                for (typeName in typeParameters) {
                    if (supplyCascadingTypeParameters.contains(typeName)) {
                        // This inferred generic type is available to descendants. Of several sources the one with the fewest other generic
                        // types is preferred: List<T> rather than Dictionary<T, U>.
                        val provides = node.providesCascadingGenericTypes ?: LinkedHashMap<String, CascadingGenericTypeParameter>().also { node.providesCascadingGenericTypes = it }
                        val existingValue = provides[typeName]
                        if (existingValue == null || existingValue.genericTypeNames!!.size > typeParameters.size) provides[typeName] = provideCascadingGenericTypes
                    }

                    if (attributeValueIsLambda) {
                        // Whether a lambda covers the generic type depends on its content ("() => 123" covers Func<T>, "() => null" does
                        // not): the cascaded generic types of the ancestors are tried first, the binding stays uncovered until then.
                        bindings[typeName]?.coveredByLambda = true
                    } else {
                        bindings.remove(typeName)
                    }
                }
            }

            // The remaining bindings: an ancestor component that cascades a generic parameter that can cover this one.
            var receivesCascadingGenericTypes: MutableList<CascadingGenericTypeParameter>? = null
            for (uncoveredBindingKey in bindings.keys.toList()) {
                for (ancestor in ancestors) {
                    if (ancestor !is ComponentIntermediateNode) continue

                    val genericTypeProvider = ancestor.providesCascadingGenericTypes?.get(uncoveredBindingKey) ?: continue

                    // A value of several generic types is used only when all of them are wanted: MyType<T0, T1> supplies Child<T0, T1>,
                    // not Child<T0> (the extra types would need unique names in the inference method's type parameters).
                    if (genericTypeProvider.genericTypeNames!!.all { typeName -> componentTypeParameters.any { it.name == typeName } }) {
                        bindings.remove(uncoveredBindingKey)
                        if (receivesCascadingGenericTypes == null) receivesCascadingGenericTypes = ArrayList()
                        receivesCascadingGenericTypes.add(genericTypeProvider)

                        // It's sufficient to identify the closest provider for each type parameter
                        break
                    }
                }
            }

            // Two lower-priority sources than the cascades, treated as covering since they may resolve the types:
            // [1] Attributes given as lambda expressions (in most cases they provide no type info).
            for (entryToRemove in bindings.entries.filter { it.value.coveredByLambda }.toList()) bindings.remove(entryToRemove.key)

            // [2] Child content parameters: nearly always untyped lambdas, but they can be a RenderFragment<Something> with type info.
            for (attribute in node.childContents) {
                val typeParameters = tryFindGenericTypeNames(attribute.boundAttribute, globallyQualifiedTypeName = null) ?: continue
                for (typeName in typeParameters) bindings.remove(typeName)
            }

            // Remaining bindings can never be inferred: the user hasn't set properties that include all of the types. The inference code
            // is still generated, for the most helpful errors, with 'object' for those type parameters, and an error.
            if (bindings.size > 0) {
                val mappings = bindings.mapValuesTo(LinkedHashMap()) { it.value.node }
                rewriteTypeNames(GenericTypeNameRewriter(mappings), node, bindings = bindings)

                node.addDiagnostic(ComponentDiagnosticFactory.create_GenericComponentTypeInferenceUnderspecified(node.source, node, node.component!!.getTypeParameters()))
            }

            // The type inference 'method' node: a method that contains all of the operations on the render tree; calling it lets the C#
            // compiler perform the type inference.
            val documentNode = ancestors[ancestors.size - 1] as DocumentIntermediateNode
            createTypeInferenceMethod(documentNode, node, receivesCascadingGenericTypes)
        }

        /** `TryFindGenericTypeNames`: the type parameters in the type of a generic-typed bound attribute; null otherwise. */
        private fun tryFindGenericTypeNames(boundAttribute: BoundAttributeDescriptor?, globallyQualifiedTypeName: String?): List<String>? {
            // Null for attributes set on the component that don't match a declared component parameter
            if (boundAttribute == null) return null

            if (!boundAttribute.isGenericTypedProperty()) return null

            // A simple identifier like TItem, or type parameters inside the name like Dictionary<string, TItem>.
            var typeParameters = parseTypeParameters(globallyQualifiedTypeName ?: boundAttribute.typeName)
            if (typeParameters.isEmpty()) typeParameters = listOf(boundAttribute.typeName)

            return typeParameters
        }

        private fun getContent(node: ComponentAttributeIntermediateNode): String = node.findDescendantNodes<CSharpIntermediateToken>().joinToString("") { it.content }

        private fun validateTypeArguments(node: ComponentIntermediateNode, bindings: Map<String, Binding>): Boolean {
            val missing = ArrayList<BoundAttributeDescriptor>()
            for ((_, binding) in bindings) {
                if (binding.node == null || binding.content?.content.isNullOrDotNetWhiteSpace()) missing.add(binding.attribute)
            }

            if (missing.size > 0) {
                // Our own error: the user will likely see other, confusing errors from the incorrect code without the types, ours says
                // pretty clearly what to do.
                node.addDiagnostic(ComponentDiagnosticFactory.create_GenericComponentMissingTypeArgument(node.source, node, missing))
                return false
            }

            return true
        }

        private fun rewriteTypeNames(rewriter: TypeNameRewriter, node: ComponentIntermediateNode, hasTypeArgumentSpecified: Boolean? = null, bindings: Map<String, Binding>? = null) {
            // Rewrite the component type name
            rewriter.rewriteComponentTypeName(node)

            for (attribute in node.attributes) {
                attribute.concreteContainingType = node.typeName

                var globallyQualifiedTypeName = attribute.boundAttribute?.getGloballyQualifiedTypeName()

                val attributeTypeName = attribute.typeName
                if (attributeTypeName != null) {
                    globallyQualifiedTypeName = rewriter.rewrite(globallyQualifiedTypeName ?: attributeTypeName)
                    attribute.globallyQualifiedTypeName = globallyQualifiedTypeName
                }

                if (attribute.boundAttribute?.isGenericTypedProperty() == true && attribute.typeName != null) {
                    // A known type name: the generic type parameters inside it replaced with the known types.
                    attribute.typeName = globallyQualifiedTypeName
                    // A property TItem explicitly given a partially qualified type (MyType) by the user: the globally qualified name
                    // comes from the Roslyn symbol except for a generic type parameter, so it is marked, and not qualified again when
                    // the code is written.
                    assert(bindings != null || hasTypeArgumentSpecified == true) // Debug.Assert
                    if (hasTypeArgumentSpecified == true) {
                        attribute.hasExplicitTypeName = true
                    } else if (attribute.boundAttribute?.isEventCallbackProperty() ?: false) {
                        val typeParameters = parseTypeParameters(attribute.typeName!!)
                        for (parameter in typeParameters) {
                            if (bindings!!.containsKey(parameter)) {
                                attribute.isOpenGeneric = true
                                break
                            }
                        }
                    }
                } else if (attribute.typeName == null && (attribute.boundAttribute?.isDelegateProperty() ?: false)) {
                    // This is a weakly typed delegate, treat it as Action<object>
                    attribute.typeName = "global::System.Action<global::System.Object>"
                } else if (attribute.typeName == null && (attribute.boundAttribute?.isEventCallbackProperty() ?: false)) {
                    // This is a weakly typed event-callback, treat it as EventCallback (non-generic)
                    attribute.typeName = "global::${ComponentsApi.EventCallback.fullTypeName}"
                } else if (attribute.typeName == null) {
                    // This is a weakly typed attribute, treat it as System.Object
                    attribute.typeName = "global::System.Object"
                }
            }

            for (capture in node.captures) {
                if (capture.isComponentCapture) capture.updateComponentCaptureTypeName(rewriter.rewrite(capture.componentCaptureTypeName!!))
            }

            for (childContent in node.childContents) {
                val typeName = childContent.typeName
                if (childContent.boundAttribute?.isGenericTypedProperty() == true && typeName != null) {
                    // A known type name: the generic type parameters inside it replaced with the known types.
                    childContent.typeName = rewriter.rewrite(typeName)
                } else if (childContent.isParameterized) {
                    // This is a non-generic parameterized child content like RenderFragment<int>, leave it as is.
                } else {
                    // This is a weakly typed child content, treat it as RenderFragment
                    childContent.typeName = ComponentsApi.RenderFragment.fullTypeName
                }
            }
        }

        private fun createTypeInferenceMethod(documentNode: DocumentIntermediateNode, node: ComponentIntermediateNode, receivesCascadingGenericTypes: MutableList<CascadingGenericTypeParameter>?) {
            var namespace = documentNode.findPrimaryNamespace()!!.name
            namespace = if (namespace.isNullOrEmpty()) "__Blazor" else "__Blazor.$namespace"
            namespace += "." + documentNode.findPrimaryClass()!!.name

            val genericTypeConstraints = ArrayList<String>()

            for (attribute in node.component!!.boundAttributes) {
                val constraints = (attribute.metadata as? TypeParameterMetadata)?.constraints
                if (constraints != null) genericTypeConstraints.add(constraints)
            }

            val typeInferenceNode = ComponentTypeInferenceMethodIntermediateNode().also {
                it.component = node

                // Generated and guaranteed not to collide: unique for each component call site.
                it.methodName = "Create${CSharpIdentifier.sanitizeIdentifier(node.tagName ?: "")}_${id++}"
                it.fullTypeName = "$namespace.TypeInference"

                it.receivesCascadingGenericTypes = receivesCascadingGenericTypes
                it.genericTypeConstraints = genericTypeConstraints.toList()
            }

            node.typeInferenceNode = typeInferenceNode

            // Now we need to insert the type inference node into the tree.
            var namespaceNode = documentNode.children.filterIsInstance<NamespaceDeclarationIntermediateNode>().firstOrNull { it.isGenericTyped }
            if (namespaceNode == null) {
                namespaceNode = NamespaceDeclarationIntermediateNode().also {
                    it.name = namespace
                    it.isGenericTyped = true
                }

                documentNode.children.add(namespaceNode)
            }

            var classNode = namespaceNode.children.filterIsInstance<ClassDeclarationIntermediateNode>().firstOrNull { it.name == "TypeInference" }
            if (classNode == null) {
                classNode = ClassDeclarationIntermediateNode().also {
                    it.name = "TypeInference"
                    it.modifiers = CommonModifiers.internalStatic
                }
                namespaceNode.children.add(classNode)
            }

            classNode.children.add(typeInferenceNode)
        }

        /**
         * `ParseTypeParameters`: the type parameters a type name uses, by parsing it — none for a simple identifier; the elements of an
         * array or a tuple; otherwise the identifiers among the type arguments of every type argument list in it (generic names
         * themselves are skipped: their argument lists are visited on their own).
         */
        fun parseTypeParameters(typeName: String): List<String> {
            val parsed = CSharpTypeNames.parseTypeName(typeName)

            if (parsed is CSharpTypeNames.IdentifierName) return emptyList()

            tryParseCore(parsed)?.let { return it }

            return parsed.descendantNodesAndSelf()
                .filterIsInstance<CSharpTypeNames.TypeArgumentList>()
                .flatMap { it.arguments }
                .flatMap { parseCore(it) }
        }

        private fun tryParseCore(parsed: CSharpTypeNames.Node): List<String>? {
            if (parsed is CSharpTypeNames.ArrayType) return parseCore(parsed.elementType)

            if (parsed is CSharpTypeNames.TupleType) return parsed.tupleElements.flatMap { parseCore(it.type) }

            return null
        }

        private fun parseCore(parsed: CSharpTypeNames.Node): List<String> {
            // Recursively drill into arrays `T[]` and tuples `(T, T)`.
            tryParseCore(parsed)?.let { return it }

            // When we encounter an identifier, we assume it's a type parameter.
            if (parsed is CSharpTypeNames.IdentifierName) return listOf(parsed.identifier.text)

            // Generic names like `C<T>` are ignored here: their type argument list is visited by `descendantNodesAndSelf` above.
            return emptyList()
        }
    }

    private class Binding(val attribute: BoundAttributeDescriptor) {
        var content: IntermediateToken? = null
        var node: ComponentTypeArgumentIntermediateNode? = null
        var coveredByLambda: Boolean = false
    }
}
