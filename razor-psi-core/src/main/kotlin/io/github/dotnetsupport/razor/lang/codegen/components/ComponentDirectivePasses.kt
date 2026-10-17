package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.codegen.IntermediateNodePassBase
import io.github.dotnetsupport.razor.lang.codegen.RazorCodeDocument
import io.github.dotnetsupport.razor.lang.codegen.RazorConfiguration
import io.github.dotnetsupport.razor.lang.codegen.RazorDirectiveClassifierPass
import io.github.dotnetsupport.razor.lang.codegen.isNullOrDotNetWhiteSpace
import io.github.dotnetsupport.razor.lang.intermediate.BaseTypeWithModel
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ClassDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CommonModifiers
import io.github.dotnetsupport.razor.lang.intermediate.DirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DirectiveTokenIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeFactory
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeWalker
import io.github.dotnetsupport.razor.lang.intermediate.MalformedDirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/** The directive classifier passes of components (`ComponentInjectDirectivePass.cs`, `ComponentLayoutDirectivePass.cs`, `ComponentPageDirectivePass.cs`, `ComponentRenderModeDirectivePass.cs`). */

/**
 * `ComponentInjectDirectivePass`: each `@inject Type Name` (malformed ones too, for the tooling) becomes a [ComponentInjectIntermediateNode]
 * in the class, the last one of a member name winning (the directives are walked backwards).
 */
class ComponentInjectDirectivePass : IntermediateNodePassBase(), RazorDirectiveClassifierPass {
    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val visitor = Visitor()
        visitor.visit(documentNode)

        val properties = HashSet<String>()
        val classNode = documentNode.findPrimaryClass()

        for (i in visitor.directives.indices.reversed()) {
            val directive = visitor.directives[i]
            val tokens = directive.children.filterIsInstance<DirectiveTokenIntermediateNode>()
            val isMalformed = directive is MalformedDirectiveIntermediateNode

            val hasType = tokens.isNotEmpty() && !tokens[0].content.isNullOrDotNetWhiteSpace()
            assert(hasType || isMalformed) // Debug.Assert
            val typeName = if (hasType) tokens[0].content!! else ""
            val typeSpan: SourceSpan? = if (hasType) tokens[0].source else directive.source?.zeroWidthEndSpan()

            val hasMemberName = tokens.size > 1 && !tokens[1].content.isNullOrDotNetWhiteSpace()
            assert(hasMemberName || isMalformed) // Debug.Assert
            val memberName = if (hasMemberName) tokens[1].content else null
            val memberSpan = if (hasMemberName) tokens[1].source else null

            if (hasMemberName && !properties.add(memberName!!)) continue

            classNode!!.children.add(ComponentInjectIntermediateNode(typeName, memberName, typeSpan, memberSpan))
        }
    }

    private class Visitor : IntermediateNodeWalker() {
        val directives: MutableList<IntermediateNode> = ArrayList()

        override fun visitDirective(node: DirectiveIntermediateNode) {
            if (node.directive === ComponentInjectDirective.directive) directives.add(node)
        }

        override fun visitMalformedDirective(node: MalformedDirectiveIntermediateNode) {
            if (node.directive === ComponentInjectDirective.directive) directives.add(node)
        }
    }
}

/** `ComponentLayoutDirectivePass`: `@layout Type` becomes `[global::Microsoft.AspNetCore.Components.LayoutAttribute(typeof(Type))]` on the class. */
class ComponentLayoutDirectivePass : IntermediateNodePassBase(), RazorDirectiveClassifierPass {
    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val namespace = documentNode.findPrimaryNamespace() ?: return
        val clazz = documentNode.findPrimaryClass() ?: return

        val directives = documentNode.findDirectiveReferences(ComponentLayoutDirective.directive)
        if (directives.isEmpty()) return

        val token = directives[0].node.tokens.firstOrNull() ?: return

        val attributeNode = CSharpCodeIntermediateNode()
        attributeNode.children.addAll(
            listOf(
                IntermediateNodeFactory.csharpToken("[global::${ComponentsApi.LayoutAttribute.fullTypeName}(typeof("),
                IntermediateNodeFactory.csharpToken(token.content ?: "", token.source),
                IntermediateNodeFactory.csharpToken("))]"),
            ),
        )

        // Insert the new attribute on top of the class
        for (i in namespace.children.indices) {
            if (namespace.children[i] === clazz) {
                namespace.children.add(i, attributeNode)
                break
            }
        }
    }
}

/**
 * `ComponentPageDirectivePass`: each `@page "/route"` becomes a [RouteAttributeExtensionNode] on top of the class (classes don't directly
 * support attributes); an imported one is RZ9987, a route not in quotes or not starting with `/` RZ9988.
 */
class ComponentPageDirectivePass : IntermediateNodePassBase(), RazorDirectiveClassifierPass {
    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val namespace = documentNode.findPrimaryNamespace() ?: return
        val clazz = documentNode.findPrimaryClass() ?: return

        val directives = documentNode.findDirectiveReferences(ComponentPageDirective.directive)
        if (directives.isEmpty()) return

        // We don't allow @page directives in imports
        for (directive in directives) {
            if (codeDocument.fileKind == RazorFileKind.ComponentImport || directive.node.isImported) {
                // `Source.GetValueOrDefault()`: the default span has no path.
                directive.node.addDiagnostic(ComponentDiagnosticFactory.createPageDirective_CannotBeImported(directive.node.source ?: SourceSpan(null, 0, 0, 0, 0)))
            }
        }

        // Insert the attributes 'on-top' of the class declaration, since classes don't directly support attributes.
        var index = 0
        while (index < namespace.children.size) {
            if (clazz === namespace.children[index]) break
            index++
        }

        for (directive in directives) {
            val pageDirective = directive.node

            // The parser also adds errors for invalid syntax, we just need to not crash.
            val routeToken = pageDirective.tokens.first()

            val content = routeToken.content
            if (content == null || !(content.length >= 3 && content[0] == '"' && content[1] == '/' && content[content.length - 1] == '"')) {
                pageDirective.addDiagnostic(ComponentDiagnosticFactory.createPageDirective_MustSpecifyRoute(pageDirective.source))
            }

            namespace.children.add(index++, RouteAttributeExtensionNode(content ?: "").also { it.source = routeToken.source })
        }
    }
}

/**
 * `ComponentRenderModeDirectivePass`: `@rendermode Mode` becomes a nested (or, for a generic component with C# 11 and Razor 11, a
 * file-scoped) `RenderModeAttribute` subclass returning the mode, and the attribute on the class. The compiler reads the C# language
 * version from the parser options' `CSharpParseOptions`, which the port's parser options do not carry: [csharpLanguageVersion] is it
 * (a major version; the default is the compiler's default, the latest).
 */
class ComponentRenderModeDirectivePass(private val csharpLanguageVersion: Int = RazorConfiguration.LATEST_CSHARP_MAJOR) :
    IntermediateNodePassBase(), RazorDirectiveClassifierPass {
    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val namespace = documentNode.findPrimaryNamespace() ?: return
        val clazz = documentNode.findPrimaryClass() ?: return

        val directives = documentNode.findDirectiveReferences(ComponentRenderModeDirective.directive)
        if (directives.isEmpty()) return

        // We don't need to worry about duplicate attributes as we have already replaced any multiples with MalformedDirective
        assert(directives.size == 1) // Debug.Assert

        val child = directives[0].node.children.firstOrNull() ?: return

        // Razor 11 or higher, C# 11 or higher and a generic component: a file-scoped class for the generated attribute, so that
        // everything compiles.
        val useFileScopedClass = csharpLanguageVersion >= 11 &&
            codeDocument.parserOptions.languageVersion >= RazorLanguageVersion.VERSION_11_0 &&
            clazz.typeParameters.isNotEmpty()

        // generate the inner attribute class
        val classDecl = ClassDeclarationIntermediateNode().also {
            it.name = GENERATED_RENDER_MODE_ATTRIBUTE_NAME
            it.baseType = BaseTypeWithModel("global::${ComponentsApi.RenderModeAttribute.fullTypeName}")
            it.modifiers = if (useFileScopedClass) CommonModifiers.fileSealed else CommonModifiers.privateSealed
            // The helper class and its [__PrivateComponentRenderModeAttribute] decoration (added below) are compiler plumbing, not user API
            // surface: the decl/impl split keeps both in the impl half (the file-scoped variant in a single file, the nested one with its
            // decoration next to its definition).
            it.isSynthesizedHelper = true
        }

        val modeExpression: IntermediateNode = if (child !is DirectiveTokenIntermediateNode) {
            child
        } else {
            // To avoid breaking hot reload, the content is not mapped back to the source before Razor 11.
            IntermediateNodeFactory.csharpToken(
                child.content ?: "",
                if (codeDocument.parserOptions.languageVersion >= RazorLanguageVersion.VERSION_11_0) child.source else null,
            )
        }

        classDecl.children.add(
            CSharpCodeIntermediateNode().also {
                it.children.add(IntermediateNodeFactory.csharpToken("private static global::${ComponentsApi.IComponentRenderMode.fullTypeName} ModeImpl => "))
                it.children.add(
                    CSharpCodeIntermediateNode().also { inner ->
                        // The compiler's main gives the inner node the directive's source at every version; the SDK's 10.0 compiler (the
                        // oracle of the corpus gate) writes the mode without a `#line`, so below Razor 11 the source stays off here too.
                        inner.source = if (codeDocument.parserOptions.languageVersion >= RazorLanguageVersion.VERSION_11_0) child.source else null
                        inner.children.add(modeExpression)
                    },
                )
                it.children.add(IntermediateNodeFactory.csharpToken(";"))
            },
        )

        classDecl.children.add(
            CSharpCodeIntermediateNode().also {
                it.children.add(IntermediateNodeFactory.csharpToken("public override global::${ComponentsApi.IComponentRenderMode.fullTypeName} Mode => ModeImpl;"))
            },
        )

        if (useFileScopedClass) namespace.children.add(classDecl) else clazz.children.add(classDecl)

        // generate the attribute usage on top of the class
        val attributeNode = CSharpCodeIntermediateNode().also { it.isSynthesizedHelper = true }
        val namespaceSeparator = if (namespace.name.isNullOrEmpty()) "" else "."
        val attributeContents = if (useFileScopedClass) GENERATED_RENDER_MODE_ATTRIBUTE_NAME
        else "global::${namespace.name ?: ""}$namespaceSeparator${clazz.name ?: ""}.$GENERATED_RENDER_MODE_ATTRIBUTE_NAME"
        attributeNode.children.add(IntermediateNodeFactory.csharpToken("[$attributeContents]"))

        // Insert the new attribute on top of the class
        val childCount = namespace.children.size
        for (i in 0 until childCount) {
            if (namespace.children[i] === clazz) {
                namespace.children.add(i, attributeNode)
                break
            }
        }

        assert(namespace.children.size == childCount + 1) // Debug.Assert
    }

    private companion object {
        const val GENERATED_RENDER_MODE_ATTRIBUTE_NAME = "__PrivateComponentRenderModeAttribute"
    }
}
