package io.github.dotnetsupport.razor.lang.codegen

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilderFactory
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.LazyParseableElement
import com.intellij.psi.tree.ILazyParseableElementTypeBase
import io.github.dotnetsupport.csharp.lang.CSharpDocCommentElementType
import io.github.dotnetsupport.csharp.lang.CSharpLanguageLevel
import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion
import io.github.dotnetsupport.csharp.lang.CSharpMissingTokenType
import io.github.dotnetsupport.csharp.lang.CSharpParserDefinition
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexer
import io.github.dotnetsupport.csharp.lang.lexer.CSharpTokenTypes
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentInjectIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.components.RouteAttributeExtensionNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.TemplateIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.ClassDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.FieldDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeCollection
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.MarkupBlockIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MarkupElementIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MethodDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.NamespaceDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.PropertyDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TypeParameter
import io.github.dotnetsupport.razor.lang.intermediate.UnresolvedElementIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.getAllDiagnostics
import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind
import io.github.dotnetsupport.razor.lang.syntax.RazorParserOptions
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

// The decl/impl markup split of a component (RAZOR_PLAN.md, R3b): DefaultRazorMarkupSplitPhase.cs, MarkupSplitter.cs (+ .Analysis,
// .Classify, .Slicing) and SplitDecision.cs, line for line. Roslyn's parse of the throwaway analysis class is csharp-psi's parser
// (the port of Roslyn's), its member `FullSpan`s recomputed from the tree by Roslyn's trivia rules (MemberSpans).

/**
 * `DefaultRazorMarkupSplitPhase`: splits a component into decl and impl halves after directive classification but before tag-helper
 * resolution — the markup-free decl surface (base type, interfaces, type parameters, members) is stashed on the document node for
 * [DefaultRazorDeclCSharpLoweringPhase], the working node is rewritten into the impl half. Only with
 * [RazorCodeGenerationOptions.enableMarkupSplit] (off by default); a body with markup the analysis can't route safely (a markup property,
 * an unsupported member, a preprocessor directive, an unrecoverable parse) falls back to single-document lowering.
 */
class DefaultRazorMarkupSplitPhase : RazorEnginePhaseBase() {
    override fun executeCore(codeDocument: RazorCodeDocument): RazorCodeDocument {
        val documentNode = throwForMissingDocumentDependency(codeDocument.getDocumentNode())

        // Opt-in: the decl document only a host consuming both halves (the source generator) knows how to emit.
        if (!codeDocument.codeGenerationOptions.enableMarkupSplit) return codeDocument

        // Only components are split: a component import or a legacy .cshtml has no component surface to partition.
        if (codeDocument.fileKind != RazorFileKind.Component) return codeDocument

        val primaryClass = documentNode.findPrimaryClass()
        val renderMethod = documentNode.findPrimaryMethod()
        val primaryNamespace = documentNode.findPrimaryNamespace()

        // Fallback discovery is keyed by the component's type name: without a primary class there is nothing to discover or split.
        if (primaryClass == null) return codeDocument

        // Routes the document to fallback discovery: its namespace-qualified type name, the type-shell decl (null when there is none) and
        // the full discoverable decl over the whole class body (no plan) when the render method and the namespace are there.
        fun routeToFallbackDiscovery(shellDecl: DocumentIntermediateNode?): RazorCodeDocument {
            check(primaryClass.name != null) { "A fallback component is missing its classified primary class." }

            documentNode.declDocumentNode = shellDecl
            documentNode.fallbackComponentTypeName = primaryNamespace?.name?.let { "$it.${primaryClass.name}" } ?: primaryClass.name

            if (renderMethod != null && primaryNamespace != null) {
                documentNode.fallbackDiscoveryDeclDocumentNode = buildDeclDocument(documentNode, primaryNamespace, primaryClass, renderMethod, plan = null)
            }

            return codeDocument.withDocumentNode(documentNode)
        }

        // A suppressed primary method body, or no render method or namespace: fallback discovery, no pre-compilation shell.
        if (codeDocument.codeGenerationOptions.suppressPrimaryMethodBody || renderMethod == null || primaryNamespace == null) {
            return routeToFallbackDiscovery(shellDecl = null)
        }

        // Only an unroutable body (fallback) stays a single document; NoSplit and SplitPlan both produce a decl.
        val decision = MarkupSplitter.split(primaryClass, renderMethod, codeDocument.parserOptions)
        if (decision is SplitDecision.SplitFallback) {
            return routeToFallbackDiscovery(buildStubDeclDocument(documentNode, primaryNamespace, primaryClass))
        }

        val plan = decision as? SplitDecision.SplitPlan

        // The decl captures its view of the class body first: makeImplInPlace then rewrites the shared primary class in place.
        val declDocNode = buildDeclDocument(documentNode, primaryNamespace, primaryClass, renderMethod, plan)
        makeImplInPlace(primaryClass, renderMethod, plan)
        stripClassDecorationsFromImpl(documentNode, primaryNamespace)

        documentNode.declDocumentNode = declDocNode

        return codeDocument.withDocumentNode(documentNode)
    }

    companion object {
        /**
         * `BuildStubDeclDocument`: the bodiless type shell of an unsplit component — the document → namespace → class spine, the class
         * with its name, modifiers and type parameters (names only), no base type, interfaces, members or constraints.
         */
        private fun buildStubDeclDocument(
            documentNode: DocumentIntermediateNode, primaryNamespace: NamespaceDeclarationIntermediateNode, primaryClass: ClassDeclarationIntermediateNode,
        ): DocumentIntermediateNode {
            val stubDocNode = RazorCSharpDocumentWriter.cloneContainer(documentNode)

            // Byte-stable across markup edits: the checksum suppressed, as the full decl's.
            val stubOptions = stubDocNode.options
            if (stubOptions != null && !stubOptions.suppressChecksum) stubDocNode.options = stubOptions.copy(suppressChecksum = true)

            val stubNamespace = RazorCSharpDocumentWriter.cloneContainer(primaryNamespace)
            val stubClass = RazorCSharpDocumentWriter.cloneContainer(primaryClass)
            stubClass.baseType = null
            stubClass.interfaces = emptyList()
            stubClass.typeParameters = stripTypeParameterConstraints(primaryClass.typeParameters)

            stubNamespace.children.add(stubClass)
            stubDocNode.children.add(stubNamespace)

            return stubDocNode
        }

        /** `StripTypeParameterConstraints`: the names kept for the generic arity, the constraints dropped. */
        private fun stripTypeParameterConstraints(typeParameters: List<TypeParameter>): List<TypeParameter> {
            if (typeParameters.isEmpty()) return typeParameters
            return typeParameters.map { TypeParameter(it.name.content) }
        }

        /**
         * `BuildDeclDocument`: the markup-free decl, a synthetic document → namespace → class spine sharing every kept leaf with the
         * original by reference — a plan's decl pieces, or without one every non-render, non-synthesized member.
         */
        private fun buildDeclDocument(
            documentNode: DocumentIntermediateNode, primaryNamespace: NamespaceDeclarationIntermediateNode, primaryClass: ClassDeclarationIntermediateNode,
            renderMethod: MethodDeclarationIntermediateNode, plan: SplitDecision.SplitPlan?,
        ): DocumentIntermediateNode {
            val declDocNode = RazorCSharpDocumentWriter.cloneContainer(documentNode)

            // The decl's text tracks the declaration surface, not the source bytes: its checksum suppressed (the impl keeps its own).
            val declOptions = declDocNode.options
            if (declOptions != null && !declOptions.suppressChecksum) declDocNode.options = declOptions.copy(suppressChecksum = true)

            val declNamespace = RazorCSharpDocumentWriter.cloneContainer(primaryNamespace)
            val declClass = RazorCSharpDocumentWriter.cloneContainer(primaryClass)

            if (plan != null) {
                for (member in plan.members) for (piece in member.declPieces) declClass.children.add(piece)
            } else {
                for (classChild in primaryClass.children) {
                    if (classChild === renderMethod || classChild.isSynthesizedHelper) continue
                    declClass.children.add(classChild)
                }
            }

            for (nsChild in primaryNamespace.children) {
                if (nsChild.isSynthesizedHelper) continue
                declNamespace.children.add(if (nsChild === primaryClass) declClass else nsChild)
            }

            for (docChild in documentNode.children) {
                // Type-inference helper namespaces are compiler plumbing for the impl half only.
                if (docChild is NamespaceDeclarationIntermediateNode && docChild.isGenericTyped) continue
                declDocNode.children.add(if (docChild === primaryNamespace) declNamespace else docChild)
            }

            // The diagnostics of the document / namespace / class nodes themselves are not reachable from the clone: on the decl root.
            for (diagnostic in documentNode.getAllDiagnostics()) declDocNode.addDiagnostic(diagnostic)

            return declDocNode
        }

        /** `MakeImplInPlace`: the render method, the synthesized helpers, then a plan's impl pieces; the class header stays. */
        private fun makeImplInPlace(primaryClass: ClassDeclarationIntermediateNode, renderMethod: MethodDeclarationIntermediateNode, plan: SplitDecision.SplitPlan?) {
            val implChildren = ArrayList<IntermediateNode>()

            implChildren.add(renderMethod)

            for (child in primaryClass.children) if (child.isSynthesizedHelper) implChildren.add(child)

            if (plan != null) {
                for (member in plan.members) for (piece in member.implPieces) implChildren.add(piece)
            }

            primaryClass.children.clear()

            for (child in implChildren) primaryClass.children.add(child)
        }

        /**
         * `StripClassDecorationsFromImpl`: `@layout` / `@attribute` (C# code nodes), `@page` (route attributes) and the documentation decorate
         * the class from the decl half; kept in the impl too they would be emitted twice on the combined type.
         */
        private fun stripClassDecorationsFromImpl(documentNode: DocumentIntermediateNode, primaryNamespace: NamespaceDeclarationIntermediateNode) {
            removeClassDecorationChildren(primaryNamespace.children)
            removeClassDecorationChildren(documentNode.children)
        }

        private fun removeClassDecorationChildren(children: IntermediateNodeCollection) {
            for (i in children.size - 1 downTo 0) {
                // A compiler-synthesized decoration (the @rendermode attribute helper) is impl-half plumbing and stays.
                val child = children[i]
                if ((child is CSharpCodeIntermediateNode && !child.isSynthesizedHelper) || child is RouteAttributeExtensionNode || child is DocumentationIntermediateNode) {
                    children.removeAt(i)
                }
            }
        }
    }
}

/**
 * `SplitDecision`: the outcome of analyzing a component's primary class body — [NoSplit] (no class-body markup), a [SplitPlan] (markup and
 * C# mixed, routable), or a [SplitFallback] (markup that cannot be split safely; the original layout is kept).
 */
sealed class SplitDecision {
    /** True when this decision requires the caller to build separate decl and impl halves. */
    val requiresSplit: Boolean get() = this is SplitPlan

    /** True when the file has markup but cannot be split. */
    val isFallback: Boolean get() = this is SplitFallback

    private object NoSplitDecision : SplitDecision()

    /** The class body has markup that cannot be split safely; [reason] for diagnostics and tests. */
    class SplitFallback(val reason: FallbackReason) : SplitDecision()

    /** How each class-body member routes between the halves, in original order. */
    class SplitPlan(members: List<RoutedMember>?) : SplitDecision() {
        val members: List<RoutedMember> = members ?: emptyList()
    }

    companion object {
        /** The class body has no markup (or nothing that needs splitting): the single-file behavior. Shared singleton. */
        val NoSplit: SplitDecision get() = NoSplitDecision

        fun fallback(reason: FallbackReason): SplitFallback = SplitFallback(reason)
    }
}

/**
 * `RoutedMember`: a user-authored class-body member after routing — the pieces of the decl half and of the impl half (original nodes by
 * reference). A member is either markup-free (all in decl) or a markup-bearing method (all in impl).
 */
class RoutedMember(declPieces: List<IntermediateNode>?, implPieces: List<IntermediateNode>?) {
    val declPieces: List<IntermediateNode> = declPieces ?: emptyList()
    val implPieces: List<IntermediateNode> = implPieces ?: emptyList()
}

/** `FallbackReason`: why a markup-bearing class body keeps its original layout. */
enum class FallbackReason {
    /** A property/indexer carries markup: it is descriptor surface and must stay in the markup-free decl half. */
    MarkupProperty,

    /** A field/event, a nested type, a constructor/operator or an incomplete member carries markup: it cannot be lifted safely. */
    UnsupportedMarkupMember,

    /** Brace mismatch, or a marker / C# content not contained by any member: the member boundaries can't be trusted. */
    UnrecoverableParse,

    /** A class-body node that is neither raw C#, a recognized markup node nor a decl-surface declaration. */
    UnsupportedClassBodyNode,

    /** A preprocessor directive in the class body: routing could orphan a member from its directive's scope. */
    ClassBodyHasDirectives,
}

/**
 * `ChildSpan`: where one class-body child landed in the analysis text, so the parser's member boundaries map back to the IR node whatever
 * the length difference between a markup node and its marker.
 */
class ChildSpan(val start: Int, val length: Int, val node: IntermediateNode) {
    val end: Int get() = start + length
}

/** `AnalysisDocument`: the throwaway analysis text and the placements mapping its spans back to IR nodes. */
class AnalysisDocument(val text: String, val children: List<ChildSpan>)

/** Roslyn's `TextSpan` of a member's `FullSpan`: half-open, [contains] as `TextSpan.Contains(int)`. */
class MemberSpan(val start: Int, val end: Int) {
    val length: Int get() = end - start
    fun contains(position: Int): Boolean = position - start in 0 until length
    override fun toString(): String = "[$start..$end)"
}

/**
 * `MarkupSplitter`: decides, for a component's primary class body, which parts of `@code` belong in the markup-free decl half and which
 * markup-bearing parts in the impl half. A cheap structural gate ([hasClassBodyMarkup]) first: no class-body markup — [SplitDecision.NoSplit]
 * without parsing. Otherwise the body is rendered into a throwaway C# class (markup replaced by markers), parsed, and its members routed.
 */
object MarkupSplitter {
    /** The identifier that stands for a markup transition in the analysis document; never matched by name, never emitted. */
    const val MARKER_METHOD_NAME = "__RazorMarkupTransition"

    // A markup transition in expression position (an `@<...>` template) becomes an expression, one in statement position a statement.
    private const val EXPRESSION_MARKER = "$MARKER_METHOD_NAME()"
    private const val STATEMENT_MARKER = "$MARKER_METHOD_NAME();"

    // The class-body children are wrapped in a throwaway class so they parse as member declarations.
    private const val ANALYSIS_CLASS_HEADER = "class __C {\n"
    private const val ANALYSIS_CLASS_FOOTER = "\n}\n"

    /**
     * `Split`: gates on class-body markup, builds the analysis document and classifies it. Pure and uncached. [RazorParserOptions] has no
     * `CSharpParseOptions` here: the analysis parse takes [languageVersion], the compiler's default being `CSharpParseOptions.Default`.
     */
    fun split(
        primaryClass: ClassDeclarationIntermediateNode, renderMethod: MethodDeclarationIntermediateNode, parserOptions: RazorParserOptions,
        languageVersion: CSharpLanguageVersion = CSharpLanguageVersion.Default,
    ): SplitDecision {
        // Fast path: with no class-body markup there is nothing to move to the impl half.
        if (!hasClassBodyMarkup(primaryClass, renderMethod)) return SplitDecision.NoSplit

        val children = collectClassBodyChildren(primaryClass, renderMethod)
        val analysis = buildAnalysisDocument(children)
        return classifyFromAnalysis(analysis, languageVersion)
    }

    /** `GetRoutablePlan`: the routable plan of the classified class body, or null when the body keeps its unsplit shape. */
    fun getRoutablePlan(primaryClass: ClassDeclarationIntermediateNode, renderMethod: MethodDeclarationIntermediateNode, parserOptions: RazorParserOptions): SplitDecision.SplitPlan? =
        split(primaryClass, renderMethod, parserOptions) as? SplitDecision.SplitPlan

    /** `HasPreprocessorDirective`: a line of the analysis text begins (after leading whitespace) with `#`. */
    fun hasPreprocessorDirective(text: String): Boolean {
        var atLineStart = true

        for (c in text) {
            if (c == '\n' || c == '\r') {
                atLineStart = true
            } else if (!atLineStart) {
                continue
            } else if (c == '#') {
                return true
            } else if (!c.isWhitespace()) {
                atLineStart = false
            }
        }

        return false
    }

    /** `HasClassBodyMarkup`: the primary class body has a markup transition. O(children), no parsing. */
    fun hasClassBodyMarkup(primaryClass: ClassDeclarationIntermediateNode, renderMethod: MethodDeclarationIntermediateNode): Boolean {
        for (child in primaryClass.children) {
            if (child === renderMethod || child.isSynthesizedHelper) continue
            if (isClassBodyMarkup(child)) return true
        }
        return false
    }

    /** `IsClassBodyMarkup`: the over-eager gate — anything but the known C# / structured-declaration kinds counts as markup. */
    fun isClassBodyMarkup(node: IntermediateNode): Boolean =
        !(node is CSharpCodeIntermediateNode || node is FieldDeclarationIntermediateNode || node is PropertyDeclarationIntermediateNode ||
            node is MethodDeclarationIntermediateNode || node is ComponentInjectIntermediateNode)

    /** `IsMarkupNode`: the precise allow-list of markup kinds the splitter routes to the impl half. */
    fun isMarkupNode(node: IntermediateNode): Boolean =
        node is TemplateIntermediateNode || node is MarkupElementIntermediateNode || node is UnresolvedElementIntermediateNode ||
            node is MarkupBlockIntermediateNode || node is HtmlContentIntermediateNode

    /** `IsSupportedClassBodyNode`: raw C#, a recognized markup node, or a decl-surface declaration. */
    fun isSupportedClassBodyNode(node: IntermediateNode): Boolean = node is CSharpCodeIntermediateNode || isMarkupNode(node) || isDeclSurfaceDeclaration(node)

    /** `IsDeclSurfaceDeclaration`: an `@inject` — no source text (a zero-length analysis span), always in the decl half. */
    fun isDeclSurfaceDeclaration(node: IntermediateNode): Boolean = node is ComponentInjectIntermediateNode

    /** `CollectClassBodyChildren`: the user-authored class-body children, the render method and synthesized helpers left out. */
    fun collectClassBodyChildren(primaryClass: ClassDeclarationIntermediateNode, renderMethod: MethodDeclarationIntermediateNode): List<IntermediateNode> {
        val builder = ArrayList<IntermediateNode>()
        for (child in primaryClass.children) {
            if (child === renderMethod || child.isSynthesizedHelper) continue
            builder.add(child)
        }
        return builder
    }

    // ---- MarkupSplitter.Analysis.cs ----

    /**
     * `BuildAnalysisDocument`: the children rendered into a parse-only class, each markup node replaced by a position-aware marker, with
     * where each child landed ([ChildSpan]).
     */
    fun buildAnalysisDocument(children: List<IntermediateNode>): AnalysisDocument {
        val builder = StringBuilder()
        builder.append(ANALYSIS_CLASS_HEADER)

        val spans = ArrayList<ChildSpan>(children.size)

        for (child in children) {
            val start = builder.length

            when {
                child is CSharpCodeIntermediateNode -> appendCSharpText(builder, child)
                isMarkupNode(child) -> builder.append(if (isExpressionPositionMarkup(child)) EXPRESSION_MARKER else STATEMENT_MARKER)
                // A synthesized structured declaration (an injected property): no analysis text, but a zero-length span for routing.
                else -> {}
            }

            spans.add(ChildSpan(start, builder.length - start, child))
        }

        builder.append(ANALYSIS_CLASS_FOOTER)

        return AnalysisDocument(builder.toString(), spans)
    }

    /** `IsExpressionPositionMarkup`: exactly a template (`@<...>` / `@:`); every other class-body markup node is in statement position. */
    fun isExpressionPositionMarkup(node: IntermediateNode): Boolean = node is TemplateIntermediateNode

    private fun appendCSharpText(builder: StringBuilder, node: CSharpCodeIntermediateNode) {
        for (child in node.children) if (child is IntermediateToken) builder.append(child.content)
    }

    // ---- MarkupSplitter.Classify.cs ----

    /**
     * `ClassifyFromAnalysis`: the pre-parse gates (an unsupported node, a preprocessor directive), the parse of the throwaway class, the
     * coverage checks, then the classification and routing of each member in one pass. Never [SplitDecision.NoSplit].
     */
    fun classifyFromAnalysis(analysis: AnalysisDocument, languageVersion: CSharpLanguageVersion = CSharpLanguageVersion.Default): SplitDecision {
        // Every routable node is raw C# or recognized markup; a structured/extension member can't be placed.
        for (child in analysis.children) {
            if (!isSupportedClassBodyNode(child.node)) return SplitDecision.fallback(FallbackReason.UnsupportedClassBodyNode)
        }

        // A preprocessor directive scopes across members; routing one to the other half would orphan it.
        if (hasPreprocessorDirective(analysis.text)) return SplitDecision.fallback(FallbackReason.ClassBodyHasDirectives)

        val markerClass = MemberSpans.parseFirstClass(analysis.text, languageVersion)
        if (markerClass == null || !markerClass.hasOpenBrace || !markerClass.hasCloseBrace) return SplitDecision.fallback(FallbackReason.UnrecoverableParse)

        // Member spans in analysis-document coordinates, as the child spans: the markup/marker length difference never matters.
        val members = markerClass.members
        val memberSpans = Array(members.size) { members[it].fullSpan }

        // Every marker and every non-whitespace C# character must land inside some member: a leak is unrecoverable.
        if (!allMarkupCovered(analysis.children, memberSpans) || !allCSharpContentCovered(analysis, memberSpans)) {
            return SplitDecision.fallback(FallbackReason.UnrecoverableParse)
        }

        // A markup-bearing plain method lifts wholesale; markup anywhere else fails the whole file.
        val liftToImpl = BooleanArray(members.size)
        for (i in members.indices) {
            if (!memberCoversMarkup(analysis.children, memberSpans[i])) continue

            when (members[i].kind) {
                // Only a plain method can be lifted wholesale to impl.
                SyntaxKind.MethodDeclaration -> liftToImpl[i] = true
                // A property/indexer is descriptor surface: markup in one forces fallback.
                SyntaxKind.PropertyDeclaration, SyntaxKind.IndexerDeclaration -> return SplitDecision.fallback(FallbackReason.MarkupProperty)
                // A field/event, a nested type, a constructor/operator or an incomplete member isn't safe to lift.
                else -> return SplitDecision.fallback(FallbackReason.UnsupportedMarkupMember)
            }
        }

        return SplitDecision.SplitPlan(routeMembers(analysis, memberSpans, liftToImpl))
    }

    /** `RouteMembers`: the children grouped under their owning members (straddling C# chunks sliced), one [RoutedMember] per member. */
    private fun routeMembers(analysis: AnalysisDocument, memberSpans: Array<MemberSpan>, liftToImpl: BooleanArray): List<RoutedMember> {
        val pieceBuilders = Array(memberSpans.size) { ArrayList<IntermediateNode>() }

        // Decl-surface declarations (@inject) have a zero-length span and always belong in the decl half.
        val declSurface = ArrayList<IntermediateNode>()

        for (child in analysis.children) {
            val node = child.node
            if (node is CSharpCodeIntermediateNode) {
                routeCSharpChild(node, child, memberSpans, pieceBuilders)
            } else if (isDeclSurfaceDeclaration(node)) {
                declSurface.add(node)
            } else {
                // A marker lives entirely inside one member (allMarkupCovered guarantees an owner): the original node by reference.
                val owner = findMemberIndex(memberSpans, child.start)
                if (owner >= 0) pieceBuilders[owner].add(node)
            }
        }

        val result = ArrayList<RoutedMember>(memberSpans.size + if (declSurface.isNotEmpty()) 1 else 0)
        for (i in memberSpans.indices) {
            val pieces = pieceBuilders[i].toList()
            // A markup-free member stays in decl; a markup-bearing method lifts wholesale to impl.
            result.add(if (liftToImpl[i]) RoutedMember(declPieces = emptyList(), implPieces = pieces) else RoutedMember(declPieces = pieces, implPieces = emptyList()))
        }

        // The decl-surface declarations as a final decl-only member.
        if (declSurface.isNotEmpty()) result.add(RoutedMember(declPieces = declSurface.toList(), implPieces = emptyList()))

        return result
    }

    /** `RouteCSharpChild`: a raw C# chunk sliced at the member boundaries inside it, each slice to the member owning its start. */
    private fun routeCSharpChild(node: CSharpCodeIntermediateNode, child: ChildSpan, memberSpans: Array<MemberSpan>, pieceBuilders: Array<ArrayList<IntermediateNode>>) {
        // Members are in source order with contiguous, increasing spans: the cuts come out strictly increasing.
        val cuts = ArrayList<Int>()
        for (span in memberSpans) {
            val boundary = span.end
            if (boundary > child.start && boundary < child.end) cuts.add(boundary - child.start)
        }

        val slices = splitCSharpNode(node, cuts)

        for (i in slices.indices) {
            val localStart = if (i == 0) 0 else cuts[i - 1]
            val owner = findMemberIndex(memberSpans, child.start + localStart)
            if (owner >= 0) pieceBuilders[owner].add(slices[i])
        }
    }

    /** `FindMemberIndex`: the member whose span contains [offset]; a boundary offset belongs to the following member (half-open spans). */
    private fun findMemberIndex(memberSpans: Array<MemberSpan>, offset: Int): Int {
        for (i in memberSpans.indices) if (memberSpans[i].contains(offset)) return i
        return -1
    }

    /** `AllCSharpContentCovered`: every non-whitespace character of every C# child falls within some member's span. */
    private fun allCSharpContentCovered(analysis: AnalysisDocument, memberSpans: Array<MemberSpan>): Boolean {
        val text = analysis.text

        for (child in analysis.children) {
            if (child.node !is CSharpCodeIntermediateNode) continue

            for (index in child.start until child.end) {
                if (!text[index].isWhitespace() && !isCoveredByMember(memberSpans, index)) return false
            }
        }

        return true
    }

    /** `IsCoveredByMember`: a binary search of the source-ordered, contiguous member spans. */
    private fun isCoveredByMember(memberSpans: Array<MemberSpan>, index: Int): Boolean {
        var lo = 0
        var hi = memberSpans.size - 1

        while (lo <= hi) {
            val mid = lo + (hi - lo) / 2
            val span = memberSpans[mid]

            if (index < span.start) {
                hi = mid - 1
            } else if (index >= span.end) {
                lo = mid + 1
            } else {
                return true
            }
        }

        return false
    }

    /** `AllMarkupCovered`: every marker starts within some member's span. */
    private fun allMarkupCovered(children: List<ChildSpan>, memberSpans: Array<MemberSpan>): Boolean {
        for (child in children) {
            if (isMarkupNode(child.node) && !isCoveredByMember(memberSpans, child.start)) return false
        }
        return true
    }

    /** `MemberCoversMarkup`: a markup child's marker starts within the member's span (by node kind, never by the marker name). */
    private fun memberCoversMarkup(children: List<ChildSpan>, memberSpan: MemberSpan): Boolean {
        for (child in children) {
            if (isMarkupNode(child.node) && memberSpan.contains(child.start)) return true
        }
        return false
    }

    // ---- MarkupSplitter.Slicing.cs ----

    /**
     * `SplitCSharpNode`: a raw C# node cut at [cuts] (strictly increasing offsets of its concatenated text), a piece per sub-range; a token
     * spanning a cut is itself sliced, each slice's source recomputed (line and character too).
     */
    fun splitCSharpNode(node: CSharpCodeIntermediateNode, cuts: List<Int>): List<CSharpCodeIntermediateNode> {
        if (cuts.isEmpty()) return listOf(node)

        val pieces = Array(cuts.size + 1) { CSharpCodeIntermediateNode().also { p -> p.source = node.source; p.isImported = node.isImported } }

        // Each token's start offset in the node's concatenated text.
        var tokenStart = 0
        for (child in node.children) {
            if (child !is IntermediateToken) continue

            val content = child.content
            val tokenEnd = tokenStart + content.length

            // The cut offsets strictly inside this token become local slice boundaries.
            val localBoundaries = ArrayList<Int>()
            localBoundaries.add(0)
            val pieceOfFirstSlice = pieceIndexOf(cuts, tokenStart)

            for (cut in cuts) if (cut > tokenStart && cut < tokenEnd) localBoundaries.add(cut - tokenStart)

            localBoundaries.add(content.length)

            for (i in 0 until localBoundaries.size - 1) {
                val localStart = localBoundaries[i]
                val localLength = localBoundaries[i + 1] - localBoundaries[i]
                if (localLength == 0) continue

                val pieceIndex = pieceOfFirstSlice + i
                pieces[pieceIndex].children.add(sliceToken(child, localStart, localLength))
            }

            tokenStart = tokenEnd
        }

        return pieces.toList()
    }

    /** `PieceIndexOf`: the number of cut points at or before [offset]. */
    private fun pieceIndexOf(cuts: List<Int>, offset: Int): Int {
        var index = 0
        for (cut in cuts) {
            if (cut <= offset) index++ else break
        }
        return index
    }

    /** `SliceToken`: a token for `[localStart, localStart + localLength)` of [token], its source advanced across the skipped prefix. */
    fun sliceToken(token: IntermediateToken, localStart: Int, localLength: Int): CSharpIntermediateToken {
        val content = token.content
        val slicedContent = content.substring(localStart, localStart + localLength)

        val source = token.source ?: return CSharpIntermediateToken(slicedContent, source = null)

        val (startAbsolute, startLine, startCharacter) = advanceLocation(source.absoluteIndex, source.lineIndex, source.characterIndex, content, 0, localStart)

        val (_, endLine, endCharacter) = advanceLocation(startAbsolute, startLine, startCharacter, content, localStart, localLength)

        val slicedSource = SourceSpan(
            source.filePath, startAbsolute, startLine, startCharacter, localLength,
            // The line breaks the span covers (0 for a single-line slice), as the writer derives the enhanced #line end line.
            lineCount = endLine - startLine,
            endCharacterIndex = endCharacter,
        )

        return CSharpIntermediateToken(slicedContent, slicedSource)
    }

    /**
     * `AdvanceLocation`: a location advanced across [count] characters of [text] from [start]. `\r\n`, a lone `\r` and a lone `\n` are one
     * line break each; the `\n` of a `\r\n` is consumed only inside the range.
     */
    fun advanceLocation(absolute: Int, line: Int, character: Int, text: String, start: Int, count: Int): Triple<Int, Int, Int> {
        var absoluteIndex = absolute
        var lineIndex = line
        var characterIndex = character
        var i = start
        val end = start + count

        while (i < end) {
            val c = text[i]
            absoluteIndex++

            if (c == '\r') {
                if (i + 1 < end && text[i + 1] == '\n') {
                    // The paired newline as a single line break.
                    absoluteIndex++
                    i++
                }

                lineIndex++
                characterIndex = 0
            } else if (c == '\n') {
                lineIndex++
                characterIndex = 0
            } else {
                characterIndex++
            }

            i++
        }

        return Triple(absoluteIndex, lineIndex, characterIndex)
    }
}

/**
 * What `ClassifyFromAnalysis` reads of Roslyn's parse of the analysis text, from csharp-psi's tree of it: the first `ClassDeclaration` of
 * the compilation unit, whether its braces are real (`OpenBraceToken.IsMissing`), and its members with their `FullSpan`. The tree keeps
 * trivia as leaves between nodes, so a member's full span is rebuilt by Roslyn's rule: the trailing trivia of a token runs through the
 * first line break (whitespace and comments; never a doc comment, a directive or skipped text), everything after it is the leading trivia
 * of the next token — so the members partition the body from the end of `{`'s trailing trivia, and a member ends where the trailing trivia
 * of its last token ends. Skipped tokens between members (error elements) fall into the next member, as Roslyn's leading skipped trivia.
 * Member bodies are lazy (reparseable) nodes and are not entered.
 */
internal object MemberSpans {
    class Member(val kind: com.intellij.psi.tree.IElementType, val fullSpan: MemberSpan)

    class ParsedClass(val hasOpenBrace: Boolean, val hasCloseBrace: Boolean, val members: List<Member>)

    /** `CSharpSyntaxTree.ParseText(text, parseOptions)` and `root.Members.OfType<ClassDeclarationSyntax>().FirstOrDefault()`. */
    fun parseFirstClass(text: String, languageVersion: CSharpLanguageVersion): ParsedClass? {
        val builder = PsiBuilderFactory.getInstance().createBuilder(CSharpParserDefinition.NATIVE, CSharpLexer(), text)
        builder.putUserData(CSharpLanguageLevel.KEY, languageVersion.effective())
        val file = builder.mark()
        CSharpParserDefinition.parseCompilationUnit(builder)
        file.done(CSharpParserDefinition.FILE)
        val root = builder.treeBuilt
        val compilationUnit = generateSequence(root.firstChildNode) { it.treeNext }.firstOrNull { it.elementType === SyntaxKind.CompilationUnit } ?: return null
        val classNode = generateSequence(compilationUnit.firstChildNode) { it.treeNext }.firstOrNull { it.elementType === SyntaxKind.ClassDeclaration } ?: return null
        return members(classNode, text)
    }

    private fun members(classNode: ASTNode, text: CharSequence): ParsedClass {
        val children = generateSequence(classNode.firstChildNode) { it.treeNext }.toList()
        val open = children.indexOfFirst { it.elementType === SyntaxKind.OpenBraceToken || isMissing(it, SyntaxKind.OpenBraceToken) }
        val close = children.indexOfLast { it.elementType === SyntaxKind.CloseBraceToken || isMissing(it, SyntaxKind.CloseBraceToken) }
        val hasOpenBrace = open >= 0 && children[open].elementType === SyntaxKind.OpenBraceToken
        val hasCloseBrace = close > open && children[close].elementType === SyntaxKind.CloseBraceToken
        if (open < 0) return ParsedClass(false, hasCloseBrace, emptyList())
        val bodyEnd = if (close > open) close else children.size
        val result = ArrayList<Member>()
        // The full start of the first member: after the trailing trivia of `{`.
        var fullStart = trailingEnd(text, children, open, children[open].startOffset + children[open].textLength)
        for (i in open + 1 until bodyEnd) {
            val child = children[i]
            if (!isMember(child)) continue
            val fullEnd = trailingEnd(text, children, i, significantEnd(child))
            result += Member(child.elementType, MemberSpan(fullStart, maxOf(fullStart, fullEnd)))
            fullStart = maxOf(fullStart, fullEnd)
        }
        return ParsedClass(hasOpenBrace, hasCloseBrace, result)
    }

    private fun isMissing(node: ASTNode, kind: com.intellij.psi.tree.IElementType): Boolean = (node.elementType as? CSharpMissingTokenType)?.tokenKind === kind

    /** A member of the class body: a composite node that is not trivia, an error element (skipped tokens) or a missing token. */
    private fun isMember(node: ASTNode): Boolean =
        node.firstChildNode != null && node.textLength > 0 && !isTrivia(node) && node.elementType !== TokenType.ERROR_ELEMENT && node.elementType !is CSharpMissingTokenType

    private fun isTrivia(node: ASTNode): Boolean {
        val type = node.elementType
        return type === TokenType.WHITE_SPACE || type in CSharpTokenTypes.WHITESPACES || type in CSharpTokenTypes.COMMENTS || type is CSharpDocCommentElementType
    }

    /** The end of the last significant (non-trivia, non-empty) leaf of [node]; a lazy body ends at its own end (its `}`). */
    private fun significantEnd(node: ASTNode): Int {
        if (node is LazyParseableElement || node.elementType is ILazyParseableElementTypeBase || node.firstChildNode == null) return node.startOffset + node.textLength
        var child = node.lastChildNode
        while (child != null) {
            if (child.textLength > 0 && !isTrivia(child)) return significantEnd(child)
            child = child.treePrev
        }
        return node.startOffset
    }

    /**
     * The end of the trailing trivia of the token ending at [tokenEnd], the last token of `children[index]`: trivia inside that child after
     * [tokenEnd] first, then the siblings after it — whitespace through the first line break, comments; anything else ends it.
     */
    private fun trailingEnd(text: CharSequence, children: List<ASTNode>, index: Int, tokenEnd: Int): Int {
        val node = children[index]
        val nodeEnd = node.startOffset + node.textLength
        // Trivia the node itself ends with (a missing token after whitespace): scanned as text, the node holds nothing else past tokenEnd.
        var end = tokenEnd
        var position = tokenEnd
        while (position < nodeEnd) {
            when (val step = triviaStep(text, position, nodeEnd)) {
                null -> return end
                else -> {
                    end = step.first
                    position = step.first
                    if (step.second) return end
                }
            }
        }
        var i = index + 1
        while (i < children.size) {
            val sibling = children[i]
            if (sibling.textLength == 0) {
                i++
                continue
            }
            val type = sibling.elementType
            when {
                type === TokenType.WHITE_SPACE || type === SyntaxKind.WhitespaceTrivia || type === SyntaxKind.EndOfLineTrivia -> {
                    val chars = sibling.chars
                    val newLine = (0 until chars.length).firstOrNull { chars[it] == '\r' || chars[it] == '\n' || chars[it] == '\u0085' || chars[it] == ' ' || chars[it] == ' ' }
                    if (newLine == null) {
                        end = sibling.startOffset + sibling.textLength
                    } else {
                        val width = if (chars[newLine] == '\r' && newLine + 1 < chars.length && chars[newLine + 1] == '\n') 2 else 1
                        return sibling.startOffset + newLine + width
                    }
                }
                type === SyntaxKind.SingleLineCommentTrivia || type === SyntaxKind.MultiLineCommentTrivia -> end = sibling.startOffset + sibling.textLength
                else -> return end
            }
            i++
        }
        return end
    }

    /** One run of trailing trivia in raw text at [position]: its end and whether a line break ended the trailing trivia; null for a token. */
    private fun triviaStep(text: CharSequence, position: Int, limit: Int): Pair<Int, Boolean>? {
        val c = text[position]
        if (c == '\r') return Pair(if (position + 1 < limit && text[position + 1] == '\n') position + 2 else position + 1, true)
        if (c == '\n' || c == '\u0085' || c == ' ' || c == ' ') return Pair(position + 1, true)
        if (c.isWhitespace()) return Pair(position + 1, false)
        if (c == '/' && position + 1 < limit && text[position + 1] == '/' && !(position + 2 < limit && text[position + 2] == '/')) {
            var end = position
            while (end < limit && text[end] != '\r' && text[end] != '\n') end++
            return Pair(end, false)
        }
        if (c == '/' && position + 1 < limit && text[position + 1] == '*') {
            val close = text.indexOf("*/", position + 2)
            return Pair(if (close < 0 || close + 2 > limit) limit else close + 2, false)
        }
        return null
    }

    private fun CharSequence.indexOf(s: String, from: Int): Int = toString().indexOf(s, from)
}
