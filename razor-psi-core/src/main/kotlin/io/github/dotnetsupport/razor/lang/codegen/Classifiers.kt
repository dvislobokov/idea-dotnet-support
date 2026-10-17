package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.lang.codegen.mvc.MvcImportProjectFeature
import io.github.dotnetsupport.razor.lang.intermediate.CommonModifiers
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentDiagnosticFactory
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentHelpers
import io.github.dotnetsupport.razor.lang.codegen.extensions.InheritsDirective
import io.github.dotnetsupport.razor.lang.codegen.extensions.NamespaceDirective
import io.github.dotnetsupport.razor.lang.intermediate.ClassDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeBuilder
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeVisitor
import io.github.dotnetsupport.razor.lang.intermediate.MemberDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MethodDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.NamespaceDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UsingDirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.parser.RazorSyntaxTree
import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind
import io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpSyntaxNode
import io.github.dotnetsupport.razor.lang.syntax.red.RazorDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RazorUsingDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RedSyntaxWalker
import io.github.dotnetsupport.razor.lang.syntax.red.directiveBody

/**
 * `DocumentClassifierPassBase`: the first pass that matches a document gives it a kind and a code target and rewrites the flat node list
 * into namespace / class / method (usings into the namespace, members into the class, the rest into the method).
 */
abstract class DocumentClassifierPassBase : IntermediateNodePassBase(), RazorDocumentClassifierPass {
    private var targetExtensionsField: List<ICodeTargetExtension> = emptyList()

    protected abstract val documentKind: String
    protected val targetExtensions: List<ICodeTargetExtension> get() = targetExtensionsField

    override fun onInitialized() {
        targetExtensionsField = engine.getFeatures<IRazorTargetExtensionFeature>().firstOrNull()?.targetExtensions?.toList() ?: emptyList()
    }

    final override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (documentNode.documentKind != null) return
        if (!isMatch(codeDocument, documentNode)) return
        documentNode.documentKind = documentKind
        documentNode.target = createTarget(codeDocument)
        rewrite(codeDocument, documentNode)
    }

    private fun rewrite(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val children = ArrayList(documentNode.children)
        documentNode.children.clear()

        val namespace = NamespaceDeclarationIntermediateNode().also { it.isPrimaryNamespace = true }
        val clazz = ClassDeclarationIntermediateNode().also { it.isPrimaryClass = true }
        val method = MethodDeclarationIntermediateNode().also { it.isPrimaryMethod = true }

        val documentBuilder = IntermediateNodeBuilder.create(documentNode)
        val namespaceBuilder = IntermediateNodeBuilder.create(documentBuilder.current!!)
        namespaceBuilder.push(namespace)
        val classBuilder = IntermediateNodeBuilder.create(namespaceBuilder.current!!)
        classBuilder.push(clazz)
        val methodBuilder = IntermediateNodeBuilder.create(classBuilder.current!!)
        methodBuilder.push(method)

        val visitor = Visitor(namespaceBuilder, classBuilder, methodBuilder)
        for (child in children) visitor.visit(child)

        onDocumentStructureCreated(codeDocument, namespace, clazz, method)
    }

    protected abstract fun isMatch(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode): Boolean

    protected open fun createTarget(codeDocument: RazorCodeDocument): CodeTarget = CodeTarget.createDefault(codeDocument) { it.targetExtensions.addAll(targetExtensions) }

    protected open fun onDocumentStructureCreated(
        codeDocument: RazorCodeDocument, namespace: NamespaceDeclarationIntermediateNode, clazz: ClassDeclarationIntermediateNode, method: MethodDeclarationIntermediateNode,
    ) {}

    private class Visitor(private val namespace: IntermediateNodeBuilder, private val clazz: IntermediateNodeBuilder, private val method: IntermediateNodeBuilder) : IntermediateNodeVisitor() {
        override fun visitUsingDirective(node: UsingDirectiveIntermediateNode) {
            val children = namespace.current!!.children
            var i = children.size - 1
            while (i >= 0) {
                if (children[i] is UsingDirectiveIntermediateNode) break
                i--
            }
            namespace.insert(i + 1, node)
        }

        override fun visitDefault(node: IntermediateNode) {
            if (node is MemberDeclarationIntermediateNode) {
                clazz.add(node)
                return
            }
            method.add(node)
        }
    }
}

/** `DefaultDocumentClassifierPassFeature`: how the default classifier names the class, the namespace and the method. */
class DefaultDocumentClassifierPassFeature : RazorEngineFeatureBase() {
    val configureClass = ArrayList<(RazorCodeDocument, ClassDeclarationIntermediateNode) -> Unit>()
    val configureNamespace = ArrayList<(RazorCodeDocument, NamespaceDeclarationIntermediateNode) -> Unit>()
    val configureMethod = ArrayList<(RazorCodeDocument, MethodDeclarationIntermediateNode) -> Unit>()
}

class DefaultDocumentClassifierPass : DocumentClassifierPassBase() {
    override val order: Int get() = DEFAULT_FEATURE_ORDER
    override val documentKind: String get() = "default"
    override fun isMatch(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode): Boolean = true

    override fun onDocumentStructureCreated(codeDocument: RazorCodeDocument, namespace: NamespaceDeclarationIntermediateNode, clazz: ClassDeclarationIntermediateNode, method: MethodDeclarationIntermediateNode) {
        val configuration = engine.getFeatures<DefaultDocumentClassifierPassFeature>().firstOrNull() ?: return
        for (configure in configuration.configureClass) configure(codeDocument, clazz)
        for (configure in configuration.configureNamespace) configure(codeDocument, namespace)
        for (configure in configuration.configureMethod) configure(codeDocument, method)
    }
}

/** `CSharpIdentifier`: a path to a class name, with the characters C# cannot have in an identifier replaced by `_`. */
object CSharpIdentifier {
    fun getClassNameFromPath(path: String): String {
        if (path.isEmpty()) return path
        var span = path
        if (span.endsWith(".cshtml", ignoreCase = true)) span = span.substring(0, span.length - ".cshtml".length)
        return sanitizeIdentifier(span)
    }

    fun sanitizeIdentifier(inputName: CharSequence): String {
        if (inputName.isEmpty()) return ""
        return StringBuilder().also { appendSanitized(it, inputName) }.toString()
    }

    fun appendSanitized(builder: StringBuilder, inputName: CharSequence) {
        if (inputName.isEmpty()) return
        val firstChar = inputName[0]
        if (!isIdentifierStartCharacter(firstChar) && isIdentifierPartCharacter(firstChar)) builder.append('_')
        var i = 0
        while (i < inputName.length) {
            val ch = inputName[i]
            if (isIdentifierPartCharacter(ch)) builder.append(ch)
            else {
                builder.append('_')
                if (Character.isHighSurrogate(ch) && i + 1 < inputName.length && Character.isLowSurrogate(inputName[i + 1])) i++ // Skip the low surrogate
            }
            i++
        }
    }

    /** `SyntaxFacts.IsIdentifierStartCharacter`: a letter, `_`, or a letter category Unicode class (Lu, Ll, Lt, Lm, Lo, Nl). */
    fun isIdentifierStartCharacter(ch: Char): Boolean {
        if (ch < 'a') return (ch in 'A'..'Z') || ch == '_'
        if (ch <= 'z') return true
        if (ch <= '\u007F') return false
        return when (Character.getType(ch).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER, Character.LETTER_NUMBER -> true
            else -> false
        }
    }

    /** `SyntaxFacts.IsIdentifierPartCharacter`: a start character, a digit, or Mn, Mc, Nd, Pc, Cf. */
    fun isIdentifierPartCharacter(ch: Char): Boolean {
        if (ch < 'a') return (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '_'
        if (ch <= 'z') return true
        if (ch <= '\u007F') return false
        return when (Character.getType(ch).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER, Character.LETTER_NUMBER,
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.DECIMAL_DIGIT_NUMBER, Character.CONNECTOR_PUNCTUATION, Character.FORMAT -> true
            else -> false
        }
    }
}

/** `RazorCodeDocumentExtensions`: the class name and the namespace of a document. */
fun RazorCodeDocument.tryComputeClassName(): String? {
    val filePath = source.relativePath ?: source.filePath
    if (filePath.isNullOrEmpty()) return null
    return CSharpIdentifier.getClassNameFromPath(filePath)
}

/** `TryGetNamespace`: the namespace with the span of the `@namespace` directive it came from (null without one). */
fun RazorCodeDocument.tryGetNamespace(fallbackToRootNamespace: Boolean, considerImports: Boolean = true): Pair<String, SourceSpan?>? =
    NamespaceComputer.tryComputeNamespace(this, fallbackToRootNamespace, considerImports)

fun RazorCodeDocument.isImportsFile(): Boolean =
    fileKind == RazorFileKind.ComponentImport || (fileKind == RazorFileKind.Legacy && source.filePath?.substringAfterLast('/')?.substringAfterLast('\\').equals(MvcImportProjectFeature.IMPORTS_FILE_NAME, ignoreCase = true))

/** `GetInheritsDirectiveValue`: the `@inherits` of a legacy file or, failing that, of its imports (the nearest first). */
fun RazorCodeDocument.getInheritsDirectiveValue(): String? {
    if (fileKind != RazorFileKind.Legacy) return null
    val syntaxTree = getSyntaxTree() ?: return null
    fun find(tree: RazorSyntaxTree): String? {
        for (node in tree.redRoot.descendantNodes()) {
            if (node is RazorDirectiveSyntax && node.directiveDescriptor === InheritsDirective.directive) {
                val csharpCode = node.directiveBody.csharpCode ?: continue
                return csharpCode.getContent().trim()
            }
        }
        return null
    }
    find(syntaxTree)?.let { return it }
    val imports = getImportSyntaxTrees()
    for (i in imports.indices.reversed()) find(imports[i])?.let { return it }
    return null
}

/** `GetUsingDirectives`: the namespaces of the `@using` directives, the imports first. */
fun RazorCodeDocument.getUsingDirectives(): List<String> {
    val syntaxTree = getSyntaxTree() ?: return emptyList()
    val usings = ArrayList<String>()
    fun collect(tree: RazorSyntaxTree) {
        for (node in tree.redRoot.descendantNodes()) {
            if (node is RazorUsingDirectiveSyntax) {
                val content = node.body.getContent().trim()
                if (content.startsWith("using ")) {
                    val ns = content.substring("using ".length).trimEnd(';').trim()
                    if (ns.isNotEmpty()) usings.add(ns)
                }
            }
        }
    }
    for (importTree in getImportSyntaxTrees()) collect(importTree)
    collect(syntaxTree)
    return usings
}

/** `NamespaceComputer`: the namespace of a document from `@namespace` (with the folders below the directive's file) or the root namespace and the relative path. */
object NamespaceComputer {
    private val PATH_SEPARATORS = charArrayOf('/', '\\')

    fun tryComputeNamespace(codeDocument: RazorCodeDocument, fallbackToRootNamespace: Boolean, considerImports: Boolean): Pair<String, SourceSpan?>? {
        val filePath = codeDocument.source.filePath
        val relativePath = codeDocument.source.relativePath
        if (filePath == null || relativePath == null || filePath.length < relativePath.length) return null

        val directive = tryGetNamespaceFromDirective(codeDocument, considerImports)
        if (directive != null) {
            val (directiveNamespaceName, directiveNamespaceSpan) = directive
            val builder = NamespaceBuilder()
            builder.appendNamespace(directiveNamespaceName)
            val directiveDirectory = normalizeDirectory(directiveNamespaceSpan.filePath)
            if (filePath.length > directiveDirectory.length && filePath.startsWith(directiveDirectory, ignoreCase = true)) {
                builder.appendRelativePath(filePath.substring(directiveDirectory.length))
            }
            return builder.toString() to directiveNamespaceSpan
        }

        if (fallbackToRootNamespace) {
            val rootNamespace = codeDocument.codeGenerationOptions.rootNamespace
            if (!rootNamespace.isNullOrEmpty() || codeDocument.fileKind.isComponent) {
                val builder = NamespaceBuilder()
                builder.appendNamespace(rootNamespace)
                builder.appendRelativePath(relativePath)
                return builder.toString() to null
            }
        }
        return null
    }

    private fun normalizeDirectory(path: String?): String {
        if (path.isNullOrEmpty()) return ""
        val lastSeparator = path.lastIndexOfAny(PATH_SEPARATORS)
        if (lastSeparator < 0) return ""
        return path.substring(0, lastSeparator + 1)
    }

    private class NamespaceBuilder {
        private val builder = StringBuilder()
        override fun toString(): String = builder.toString()

        fun appendNamespace(namespaceName: String?) {
            if (namespaceName == null) return
            for (token in namespaceName.split('.')) {
                if (token.isEmpty()) continue
                if (builder.isNotEmpty()) builder.append('.')
                CSharpIdentifier.appendSanitized(builder, token)
            }
        }

        fun appendRelativePath(relativePath: String) {
            val lastSeparatorIndex = relativePath.lastIndexOfAny(PATH_SEPARATORS)
            if (lastSeparatorIndex < 0) return
            val directory = relativePath.substring(0, lastSeparatorIndex)
            for (token in directory.split('/', '\\')) {
                if (token.isEmpty()) continue
                if (builder.isNotEmpty()) builder.append('.')
                CSharpIdentifier.appendSanitized(builder, token)
            }
        }
    }

    private fun tryGetNamespaceFromDirective(codeDocument: RazorCodeDocument, considerImports: Boolean): Pair<String, SourceSpan>? {
        val visitor = NamespaceDirectiveVisitor()
        codeDocument.getSyntaxTree()?.let { tree -> visitor.tryGetLastNamespaceDirective(tree)?.let { return it } }
        if (considerImports) {
            val importSyntaxTrees = codeDocument.getImportSyntaxTrees()
            for (i in importSyntaxTrees.indices.reversed()) visitor.tryGetLastNamespaceDirective(importSyntaxTrees[i])?.let { return it }
        }
        return null
    }

    private class NamespaceDirectiveVisitor : RedSyntaxWalker() {
        private var source: RazorSourceDocument? = null
        private var lastNamespaceName: String? = null
        private var lastNamespaceSpan: SourceSpan = SourceSpan.UNDEFINED

        fun tryGetLastNamespaceDirective(syntaxTree: RazorSyntaxTree): Pair<String, SourceSpan>? {
            source = syntaxTree.source ?: RazorSourceDocument(syntaxTree.text, null)
            lastNamespaceName = null
            lastNamespaceSpan = SourceSpan.UNDEFINED
            visit(syntaxTree.redRoot)
            val name = lastNamespaceName
            if (name.isNullOrEmpty()) return null
            return name to lastNamespaceSpan
        }

        override fun visitRazorDirective(node: RazorDirectiveSyntax) {
            if (node.directiveDescriptor === NamespaceDirective.directive) {
                val children = node.directiveBody.csharpCode?.children
                if (children != null && children.size >= 2 && children[1] is CSharpSyntaxNode) {
                    val namespace = children[1]
                    lastNamespaceName = namespace.getContent()
                    lastNamespaceSpan = namespace.getSourceSpan(source!!)
                }
            }
            super.visitRazorDirective(node)
        }
    }
}

/** `ComponentDocumentClassifierPass`: `.razor` files — the partial class deriving from `ComponentBase` with `BuildRenderTree`; imports get a dummy `Execute`. */
class ComponentDocumentClassifierPass : DocumentClassifierPassBase() {
    var fallbackRootNamespace: String = "__GeneratedComponent"
    var mangleClassNames: Boolean = false

    override val documentKind: String get() = COMPONENT_DOCUMENT_KIND
    override val order: Int get() = -100

    override fun isMatch(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode): Boolean = codeDocument.fileKind.isComponent

    override fun createTarget(codeDocument: RazorCodeDocument): CodeTarget = io.github.dotnetsupport.razor.lang.codegen.components.ComponentCodeTarget(codeDocument, targetExtensions)

    override fun onDocumentStructureCreated(codeDocument: RazorCodeDocument, namespace: NamespaceDeclarationIntermediateNode, clazz: ClassDeclarationIntermediateNode, method: MethodDeclarationIntermediateNode) {
        val computed = codeDocument.tryGetNamespace(fallbackToRootNamespace = true)
        var computedNamespace = computed?.first ?: fallbackRootNamespace
        val computedNamespaceSpan = computed?.second
        var computedClass = tryComputeClassName(codeDocument) ?: "AspNetCore_${codeDocument.source.checksum}"

        val documentNode = codeDocument.getRequiredDocumentNode()
        if (computedClass.isNotEmpty() && Character.isLowerCase(computedClass[0])) {
            documentNode.addDiagnostic(ComponentDiagnosticFactory.create_ComponentNamesCannotStartWithLowerCase(computedClass, documentNode.source))
        }
        if (mangleClassNames) computedClass = ComponentHelpers.mangleClassName(computedClass)

        clazz.nullableContext = true
        namespace.name = computedNamespace
        namespace.source = computedNamespaceSpan
        clazz.name = computedClass
        clazz.modifiers = CommonModifiers.publicPartial

        if (codeDocument.fileKind == RazorFileKind.ComponentImport) {
            clazz.baseType = io.github.dotnetsupport.razor.lang.intermediate.BaseTypeWithModel("object")
            method.returnType = "void"
            method.name = "Execute"
            method.modifiers = CommonModifiers.protected
            method.parameters = emptyList()
        } else {
            clazz.baseType = io.github.dotnetsupport.razor.lang.intermediate.BaseTypeWithModel("global::" + io.github.dotnetsupport.razor.lang.codegen.components.ComponentsApi.ComponentBase.fullTypeName)
            val razorLanguageVersion = codeDocument.parserOptions.languageVersion
            val directiveType = if (razorLanguageVersion >= io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion.VERSION_6_0)
                io.github.dotnetsupport.razor.lang.codegen.components.ComponentConstrainedTypeParamDirective.directive
            else io.github.dotnetsupport.razor.lang.codegen.components.ComponentTypeParamDirective.directive
            val typeParameters = ArrayList<io.github.dotnetsupport.razor.lang.intermediate.TypeParameter>()
            for (typeParamReference in documentNode.findDirectiveReferences(directiveType)) {
                val typeParamNode = typeParamReference.node
                if (typeParamNode.hasDiagnostics) continue
                val tokens = typeParamNode.tokens
                val name = tokens.first()
                val constraints = tokens.getOrNull(1)
                typeParameters.add(io.github.dotnetsupport.razor.lang.intermediate.TypeParameter(name.content!!, name.source, constraints?.content, constraints?.source))
            }
            clazz.typeParameters = typeParameters
            method.returnType = "void"
            method.name = io.github.dotnetsupport.razor.lang.codegen.components.ComponentsApi.ComponentBase.buildRenderTree
            method.modifiers = CommonModifiers.protectedOverride
            method.parameters = listOf(
                io.github.dotnetsupport.razor.lang.intermediate.MethodParameter(
                    io.github.dotnetsupport.razor.lang.codegen.components.ComponentsApi.RenderTreeBuilder.builderParameter,
                    "global::" + io.github.dotnetsupport.razor.lang.codegen.components.ComponentsApi.RenderTreeBuilder.fullTypeName,
                ),
            )
        }
    }

    private fun tryComputeClassName(codeDocument: RazorCodeDocument): String? {
        if (codeDocument.source.filePath == null || codeDocument.source.relativePath == null) return null
        val relativePath = codeDocument.source.relativePath.replace('\\', '/')
        val fileName = relativePath.substringAfterLast('/')
        val withoutExtension = fileName.substringBeforeLast('.', fileName)
        return CSharpIdentifier.sanitizeIdentifier(withoutExtension)
    }

    companion object {
        const val COMPONENT_DOCUMENT_KIND = "component.1.0"
    }
}
