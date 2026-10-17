package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.lang.codegen.components.isAnyComponentDocumentTagHelper
import io.github.dotnetsupport.razor.lang.parser.RazorSyntaxTree
import io.github.dotnetsupport.razor.lang.syntax.ChunkGenerator
import io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument
import io.github.dotnetsupport.razor.lang.syntax.TagHelperCollection
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDocumentContext
import io.github.dotnetsupport.razor.lang.syntax.red.BaseRazorDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpStatementLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RazorDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RazorUsingDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RedSyntaxWalker

/** `ITagHelperFeature`: the descriptors of the project, asked for by the discovery phase when the document carries none. */
interface ITagHelperFeature : RazorEngineFeature {
    fun getTagHelpers(): TagHelperCollection
}

/**
 * `TagHelperDiscoveryService`: the compiler's walks the Roslyn `Compilation` (the assembly and its references) through the tag helper
 * producers. This plugin has no compilation: the descriptors will come from its own assembly index and the project's components (R5).
 * The feature stays registered as in the engine and yields no descriptors; it is not an [ITagHelperFeature] (neither is the compiler's).
 */
class TagHelperDiscoveryService : RazorEngineFeatureBase() {
    fun getTagHelpers(): TagHelperCollection = TagHelperCollection.EMPTY
}

/** `DirectiveTagHelperContribution`: the tag helpers a directive (`@using` of a component, `@addTagHelper` of a view) brought in. */
data class DirectiveTagHelperContribution(val directiveSpanStart: Int, val contributedTagHelpers: TagHelperCollection)

// The tag helper items of the code document (`RazorCodeDocument.TagHelpers` and the rest), over [RazorCodeDocument.items].

fun RazorCodeDocument.getTagHelpers(): TagHelperCollection? = items[RazorCodeDocument.TAG_HELPERS] as TagHelperCollection?
fun RazorCodeDocument.withTagHelpers(value: TagHelperCollection?): RazorCodeDocument = also { items[RazorCodeDocument.TAG_HELPERS] = value }

fun RazorCodeDocument.getTagHelperContext(): TagHelperDocumentContext? = items[RazorCodeDocument.TAG_HELPER_CONTEXT] as TagHelperDocumentContext?
fun RazorCodeDocument.withTagHelperContext(value: TagHelperDocumentContext?): RazorCodeDocument = also { items[RazorCodeDocument.TAG_HELPER_CONTEXT] = value }

fun RazorCodeDocument.getReferencedTagHelpers(): TagHelperCollection? = items[RazorCodeDocument.REFERENCED_TAG_HELPERS] as TagHelperCollection?
fun RazorCodeDocument.withReferencedTagHelpers(value: TagHelperCollection?): RazorCodeDocument = also { items[RazorCodeDocument.REFERENCED_TAG_HELPERS] = value }

@Suppress("UNCHECKED_CAST")
fun RazorCodeDocument.getDirectiveTagHelperContributions(): List<DirectiveTagHelperContribution> =
    items[RazorCodeDocument.DIRECTIVE_TAG_HELPER_CONTRIBUTIONS] as List<DirectiveTagHelperContribution>? ?: emptyList()

fun RazorCodeDocument.withDirectiveTagHelperContributions(value: List<DirectiveTagHelperContribution>): RazorCodeDocument =
    also { items[RazorCodeDocument.DIRECTIVE_TAG_HELPER_CONTRIBUTIONS] = value }

/**
 * `DefaultRazorTagHelperContextDiscoveryPhase`: the descriptors in scope of the document — `@addTagHelper` / `@removeTagHelper` /
 * `@tagHelperPrefix` of a view, `@using` (and the document's own namespace) of a component —, the imports first, then the file.
 */
class DefaultRazorTagHelperContextDiscoveryPhase : RazorEnginePhaseBase() {
    override fun executeCore(codeDocument: RazorCodeDocument): RazorCodeDocument {
        // The canonical syntax tree is established by DefaultRazorParsingPhase (which runs before this phase).
        val syntaxTree = throwForMissingDocumentDependency(codeDocument.getSyntaxTree())

        var tagHelpers = codeDocument.getTagHelpers()
        if (tagHelpers == null) {
            // No feature, nothing to do.
            val tagHelperFeature = engine.getFeatures<ITagHelperFeature>().firstOrNull() ?: return codeDocument
            tagHelpers = tagHelperFeature.getTagHelpers()
        }

        val visitor = getVisitor(codeDocument, tagHelpers)

        // The directives of the imports count as well: they come logically before the file, in the order they are processed.
        for (import in codeDocument.getImportSyntaxTrees()) visitor.visit(import)

        visitor.visit(syntaxTree)

        // Always null for a component document.
        val tagHelperPrefix = visitor.tagHelperPrefix

        val directiveContributions = visitor.getDirectiveTagHelperContributions()
        val context = TagHelperDocumentContext.create(tagHelperPrefix, visitor.getResults())
        return codeDocument.withTagHelperContext(context).withDirectiveTagHelperContributions(directiveContributions)
    }

    /** `DirectiveVisitor`: walks the directives of a tree, collecting the descriptors they bring into scope ([getResults]). */
    abstract class DirectiveVisitor : RedSyntaxWalker() {
        private var isInitializedField = false
        private var filePath: String? = null
        private var sourceField: RazorSourceDocument? = null

        private var matchesField: LinkedHashSet<TagHelperDescriptor>? = null
        private var directiveContributions: MutableList<DirectiveTagHelperContribution>? = null

        private val matches: LinkedHashSet<TagHelperDescriptor> get() = matchesField ?: LinkedHashSet<TagHelperDescriptor>().also { matchesField = it }

        protected val isInitialized: Boolean get() = isInitializedField
        protected val source: RazorSourceDocument get() = sourceField!!

        open val tagHelperPrefix: String? get() = null

        /** True when visiting the source document itself (not the imports). */
        protected val isSourceDocument: Boolean
            get() {
                val filePath = filePath ?: return false
                val sourceFilePath = sourceField?.filePath ?: return false
                return filePath == sourceFilePath
            }

        fun visit(tree: RazorSyntaxTree) {
            sourceField = tree.source
            visit(tree.redRoot)
        }

        fun getResults(): TagHelperCollection = matchesField?.let { TagHelperCollection(it.toList()) } ?: TagHelperCollection.EMPTY

        fun getDirectiveTagHelperContributions(): List<DirectiveTagHelperContribution> =
            directiveContributions?.takeIf { it.isNotEmpty() }?.toList() ?: emptyList()

        protected fun initialize(filePath: String?) {
            this.filePath = filePath
            isInitializedField = true
        }

        protected fun recordDirectiveTagHelperContribution(directive: BaseRazorDirectiveSyntax, contributedTagHelpers: TagHelperCollection) {
            // Only track directive usage in source documents, not imports.
            if (isSourceDocument) {
                (directiveContributions ?: ArrayList<DirectiveTagHelperContribution>().also { directiveContributions = it })
                    .add(DirectiveTagHelperContribution(directive.position, contributedTagHelpers))
            }
        }

        protected fun addMatch(tagHelper: TagHelperDescriptor) {
            matches.add(tagHelper)
        }

        protected fun addMatches(tagHelpers: List<TagHelperDescriptor>) {
            for (tagHelper in tagHelpers) matches.add(tagHelper)
        }

        protected fun removeMatch(tagHelper: TagHelperDescriptor) {
            matches.remove(tagHelper)
        }

        protected fun removeMatches(tagHelpers: List<TagHelperDescriptor>) {
            for (tagHelper in tagHelpers) matches.remove(tagHelper)
        }

        protected abstract fun processChunkGenerator(node: BaseRazorDirectiveSyntax, chunkGenerator: ChunkGenerator)

        override fun visitRazorDirective(node: RazorDirectiveSyntax) {
            visitDirective(node)
        }

        override fun visitRazorUsingDirective(node: RazorUsingDirectiveSyntax) {
            visitDirective(node)
        }

        private fun visitDirective(node: BaseRazorDirectiveSyntax) {
            for (child in node.descendantNodes()) {
                if (child is CSharpStatementLiteralSyntax) {
                    val chunkGenerator = child.chunkGenerator
                    if (chunkGenerator != null) processChunkGenerator(node, chunkGenerator)
                }
            }
        }
    }

    /** `TagHelperDirectiveVisitor`: `@addTagHelper`, `@removeTagHelper` and `@tagHelperPrefix` of a view. */
    class TagHelperDirectiveVisitor : DirectiveVisitor() {
        private var tagHelpers: TagHelperCollection? = null
        private var tagHelperPrefixField: String? = null

        override val tagHelperPrefix: String? get() = tagHelperPrefixField

        /** The non-component descriptors by assembly name (components are not considered in a view document). */
        private val tagHelperMap: Map<String, List<TagHelperDescriptor>> by lazy(LazyThreadSafetyMode.NONE) {
            val map = LinkedHashMap<String, MutableList<TagHelperDescriptor>>()
            for (tagHelper in tagHelpers!!) {
                if (!tagHelper.isAnyComponentDocumentTagHelper()) map.getOrPut(tagHelper.assemblyName) { ArrayList() }.add(tagHelper)
            }
            map
        }

        fun initialize(tagHelpers: TagHelperCollection, filePath: String?) {
            assert(!isInitialized) // Debug.Assert
            this.tagHelpers = tagHelpers
            initialize(filePath)
        }

        override fun processChunkGenerator(node: BaseRazorDirectiveSyntax, chunkGenerator: ChunkGenerator) {
            when (chunkGenerator) {
                is ChunkGenerator.AddTagHelper -> handleAddTagHelper(node, chunkGenerator)
                is ChunkGenerator.RemoveTagHelper -> handleRemoveTagHelper(chunkGenerator)
                is ChunkGenerator.TagHelperPrefix -> handleTagHelperPrefix(chunkGenerator)
                else -> {}
            }
        }

        private fun handleAddTagHelper(node: BaseRazorDirectiveSyntax, addTagHelper: ChunkGenerator.AddTagHelper) {
            // Skip this one, it's an error.
            val assemblyName = addTagHelper.assemblyName ?: return

            // No tag helpers in the assembly.
            val tagHelpers = tagHelperMap[assemblyName] ?: return

            // Directives are recorded for the source document only, so there is no point collecting them otherwise.
            val contributed = if (isSourceDocument) ArrayList<TagHelperDescriptor>() else null
            val pattern = getMemoryWithoutGlobalPrefix(addTagHelper.typePattern ?: "")
            if (pattern == "*") {
                addMatches(tagHelpers)
                contributed?.addAll(tagHelpers)
            } else if (pattern.endsWith('*')) {
                val prefix = pattern.substring(0, pattern.length - 1)
                for (tagHelper in tagHelpers) {
                    if (tagHelper.name.startsWith(prefix)) {
                        addMatch(tagHelper)
                        contributed?.add(tagHelper)
                    }
                }
            } else {
                for (tagHelper in tagHelpers) {
                    if (tagHelper.name == pattern) {
                        addMatch(tagHelper)
                        contributed?.add(tagHelper)
                    }
                }
            }

            if (contributed != null) recordDirectiveTagHelperContribution(node, createCollection(contributed))
        }

        private fun handleRemoveTagHelper(removeTagHelper: ChunkGenerator.RemoveTagHelper) {
            // Skip this one, it's an error.
            val assemblyName = removeTagHelper.assemblyName ?: return

            // No tag helpers in the assembly.
            val nonComponentTagHelpers = tagHelperMap[assemblyName] ?: return

            val pattern = getMemoryWithoutGlobalPrefix(removeTagHelper.typePattern ?: "")
            if (pattern == "*") {
                removeMatches(nonComponentTagHelpers)
            } else if (pattern.endsWith('*')) {
                val prefix = pattern.substring(0, pattern.length - 1)
                for (tagHelper in nonComponentTagHelpers) if (tagHelper.name.startsWith(prefix)) removeMatch(tagHelper)
            } else {
                for (tagHelper in nonComponentTagHelpers) if (tagHelper.name == pattern) removeMatch(tagHelper)
            }
        }

        private fun handleTagHelperPrefix(tagHelperPrefix: ChunkGenerator.TagHelperPrefix) {
            // A single one per file is expected; that is enforced elsewhere.
            if (tagHelperPrefix.directiveText.isNotEmpty()) tagHelperPrefixField = tagHelperPrefix.directiveText
        }
    }

    /** `ComponentDirectiveVisitor`: the components in scope of a component document — by `@using`, by the document's namespace, fully qualified. */
    class ComponentDirectiveVisitor : DirectiveVisitor() {
        // The components by the namespace they are declared in.
        private val namespaceToComponentsMap = HashMap<String, MutableList<TagHelperDescriptor>>()

        // The components without a namespace.
        private var componentsWithoutNamespace: MutableList<TagHelperDescriptor>? = null

        fun initialize(tagHelpers: TagHelperCollection, filePath: String?, currentNamespace: String?) {
            assert(!isInitialized) // Debug.Assert

            for (component in tagHelpers) {
                // Legacy tag helpers are not considered in a component document.
                if (!component.isAnyComponentDocumentTagHelper() || isTagHelperFromMangledClass(component)) continue

                if (component.isFullyQualifiedNameMatch) {
                    // A component matching by its fully qualified name does not depend on the using directives.
                    addMatch(component)
                    continue
                }

                val typeNamespace = component.typeNamespace ?: ""

                if (typeNamespace.isEmpty()) {
                    (componentsWithoutNamespace ?: ArrayList<TagHelperDescriptor>().also { componentsWithoutNamespace = it }).add(component)
                } else {
                    namespaceToComponentsMap.getOrPut(typeNamespace) { ArrayList() }.add(component)
                }

                // A type already in scope of the document's namespace needs no using.
                if (currentNamespace != null && isTypeNamespaceInScope(typeNamespace, currentNamespace)) addMatch(component)
            }

            initialize(filePath)
        }

        override fun processChunkGenerator(node: BaseRazorDirectiveSyntax, chunkGenerator: ChunkGenerator) {
            if (chunkGenerator is ChunkGenerator.AddImport && !chunkGenerator.isStatic) processAddImport(node, chunkGenerator)
        }

        private fun processAddImport(node: BaseRazorDirectiveSyntax, addImport: ChunkGenerator.AddImport) {
            // The namespace of the using statement.
            val namespace = addImport.parsedNamespace

            // Usings with an alias are not supported.
            if (namespace.contains('=')) return

            if (namespaceToComponentsMap.isEmpty() && componentsWithoutNamespace.isNullOrEmpty()) {
                // There aren't any non-qualified components to add.
                recordDirectiveTagHelperContribution(node, TagHelperCollection.EMPTY)
                return
            }

            var contributedTagHelpers = TagHelperCollection.EMPTY

            // All tag helpers with an empty type namespace.
            componentsWithoutNamespace?.takeIf { it.isNotEmpty() }?.let { addMatches(it) }

            if (namespaceToComponentsMap.isNotEmpty()) {
                // The global:: prefix removed from the namespace; all tag helpers of the namespace.
                val components = namespaceToComponentsMap[getMemoryWithoutGlobalPrefix(namespace)]
                if (components != null) {
                    addMatches(components)
                    if (isSourceDocument) contributedTagHelpers = createCollection(components)
                }
            }

            recordDirectiveTagHelperContribution(node, contributedTagHelpers)
        }

        companion object {
            /**
             * `IsTypeNamespaceInScope`: is a type's namespace already in scope of the document's namespace? For `MyComponents.Components.Shared`,
             * the types of `MyComponents`, `MyComponents.Components` and `MyComponents.Components.Shared` are, of `MyComponents.SomethingElse` not.
             */
            internal fun isTypeNamespaceInScope(typeNamespace: String, namespace: String): Boolean {
                // Either the type name is not the full type name or the type is at the top level.
                if (typeNamespace.isEmpty()) return true

                // typeName: MyComponents.Shared.SomeCoolNamespace, currentNamespace: MyComponents.Shared.
                if (!namespace.startsWith(typeNamespace)) return false

                // typeName: MyComponents.SharedFoo, currentNamespace: MyComponent.Shared (as the compiler: unreachable after the check above).
                if (typeNamespace.length > namespace.length && typeNamespace[namespace.length] != '.') return false

                return true
            }

            /** The duplicate descriptors from the file open in the editor: its class name is mangled for the generated code (`ComponentHelpers.IsMangledClass`). */
            internal fun isTagHelperFromMangledClass(tagHelper: TagHelperDescriptor): Boolean = tagHelper.typeNameIdentifier?.startsWith(MANGLED_CLASS_NAME_PREFIX) == true

            private const val MANGLED_CLASS_NAME_PREFIX = "__generated__"
        }
    }

    companion object {
        internal fun getMemoryWithoutGlobalPrefix(s: String): String {
            val globalPrefix = "global::"
            return if (s.startsWith(globalPrefix)) s.substring(globalPrefix.length) else s
        }

        /** `TagHelperCollection.Create`: the descriptors without duplicates, in order. */
        private fun createCollection(tagHelpers: List<TagHelperDescriptor>): TagHelperCollection =
            if (tagHelpers.isEmpty()) TagHelperCollection.EMPTY else TagHelperCollection(LinkedHashSet(tagHelpers).toList())

        /**
         * `GetPooledVisitor` without the pooling: the component visitor for a component document (initialized with the document's namespace,
         * the root namespace as the fallback), the tag helper directive visitor otherwise.
         */
        internal fun getVisitor(codeDocument: RazorCodeDocument, tagHelpers: TagHelperCollection): DirectiveVisitor {
            val useComponentDirectiveVisitor = codeDocument.parserOptions.allowComponentFileKind && codeDocument.fileKind.isComponent
            val filePath = codeDocument.source.filePath
            return if (useComponentDirectiveVisitor) {
                val currentNamespace = codeDocument.tryGetNamespace(fallbackToRootNamespace = true)?.first
                ComponentDirectiveVisitor().also { it.initialize(tagHelpers, filePath, currentNamespace) }
            } else {
                TagHelperDirectiveVisitor().also { it.initialize(tagHelpers, filePath) }
            }
        }
    }
}
