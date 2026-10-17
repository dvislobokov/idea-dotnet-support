package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.parser.RazorSyntaxTree
import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind
import io.github.dotnetsupport.razor.lang.syntax.RazorParserOptions
import io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument

/**
 * `RazorCodeDocument` (RAZOR_PLAN.md, R3b): the state of one file through the engine's phases — the source and its imports, the options,
 * then the syntax trees, the document node and the C# documents the phases attach. The compiler's `With*` return a document with the
 * value set; here they set it on this one and return it, the pipeline being the only writer.
 */
class RazorCodeDocument private constructor(
    val source: RazorSourceDocument,
    val imports: List<RazorSourceDocument>,
    val parserOptions: RazorParserOptions,
    val codeGenerationOptions: RazorCodeGenerationOptions,
) {
    val fileKind: RazorFileKind get() = parserOptions.fileKind

    private var syntaxTree: RazorSyntaxTree? = null
    private var tagHelperRewrittenSyntaxTree: RazorSyntaxTree? = null
    private var importSyntaxTrees: List<RazorSyntaxTree>? = null
    private var documentNode: DocumentIntermediateNode? = null
    private var unresolvedDocumentNode: DocumentIntermediateNode? = null
    private var implCSharpDocument: RazorCSharpDocument? = null
    private var declCSharpDocument: RazorCSharpDocument? = null

    /** The items the tag helper phases hang on the document, by name ([TAG_HELPERS], [REFERENCED_TAG_HELPERS], [TAG_HELPER_CONTEXT], [DIRECTIVE_TAG_HELPER_CONTRIBUTIONS]). */
    val items: MutableMap<String, Any?> = HashMap()

    fun getSyntaxTree(): RazorSyntaxTree? = syntaxTree
    fun getRequiredSyntaxTree(): RazorSyntaxTree = syntaxTree ?: throw IllegalStateException("The syntax tree is not set.")
    fun withSyntaxTree(value: RazorSyntaxTree): RazorCodeDocument = also { syntaxTree = value }

    fun getTagHelperRewrittenSyntaxTree(): RazorSyntaxTree? = tagHelperRewrittenSyntaxTree
    fun getRequiredTagHelperRewrittenSyntaxTree(): RazorSyntaxTree = tagHelperRewrittenSyntaxTree ?: throw IllegalStateException("The tag helper rewritten syntax tree is not set.")
    fun withTagHelperRewrittenSyntaxTree(value: RazorSyntaxTree): RazorCodeDocument = also { tagHelperRewrittenSyntaxTree = value }

    fun getImportSyntaxTrees(): List<RazorSyntaxTree> = importSyntaxTrees ?: emptyList()
    fun withImportSyntaxTrees(value: List<RazorSyntaxTree>): RazorCodeDocument = also { importSyntaxTrees = value }

    fun getDocumentNode(): DocumentIntermediateNode? = documentNode
    fun getRequiredDocumentNode(): DocumentIntermediateNode = documentNode ?: throw IllegalStateException("The document node is not set.")
    fun withDocumentNode(value: DocumentIntermediateNode): RazorCodeDocument = also { documentNode = value }

    fun getUnresolvedDocumentNode(): DocumentIntermediateNode? = unresolvedDocumentNode
    fun withUnresolvedDocumentNode(value: DocumentIntermediateNode): RazorCodeDocument = also { unresolvedDocumentNode = value }

    fun getCSharpDocument(declarationDocument: Boolean = false): RazorCSharpDocument? = if (declarationDocument) declCSharpDocument else implCSharpDocument
    fun getRequiredCSharpDocument(declarationDocument: Boolean = false): RazorCSharpDocument =
        getCSharpDocument(declarationDocument) ?: throw IllegalStateException("The C# document is not set.")
    fun getDeclCSharpDocument(): RazorCSharpDocument? = declCSharpDocument
    fun withImplCSharpDocument(value: RazorCSharpDocument): RazorCodeDocument = also { implCSharpDocument = value }
    fun withDeclCSharpDocument(value: RazorCSharpDocument): RazorCodeDocument = also { declCSharpDocument = value }

    companion object {
        const val TAG_HELPERS = "TagHelpers"
        const val REFERENCED_TAG_HELPERS = "ReferencedTagHelpers"
        const val TAG_HELPER_CONTEXT = "TagHelperContext"
        const val DIRECTIVE_TAG_HELPER_CONTRIBUTIONS = "DirectiveTagHelperContributions"

        fun create(
            source: RazorSourceDocument, imports: List<RazorSourceDocument> = emptyList(), parserOptions: RazorParserOptions? = null,
            codeGenerationOptions: RazorCodeGenerationOptions? = null,
        ): RazorCodeDocument = RazorCodeDocument(source, imports, parserOptions ?: RazorParserOptions(), codeGenerationOptions ?: RazorCodeGenerationOptions.DEFAULT)
    }
}

/** `IRazorEngineFeature`. */
interface RazorEngineFeature : RazorFeature {
    val engine: RazorEngine
    fun initialize(engine: RazorEngine)
}

/** `RazorEngineFeatureBase`. */
abstract class RazorEngineFeatureBase : RazorEngineFeature {
    private var engineField: RazorEngine? = null
    override val engine: RazorEngine get() = engineField ?: throw IllegalStateException("The feature ${javaClass.simpleName} is not initialized.")

    override fun initialize(engine: RazorEngine) {
        if (engineField != null) throw IllegalStateException("The feature ${javaClass.simpleName} is already initialized.")
        engineField = engine
        onInitialized()
    }

    protected open fun onInitialized() {}
}

/** `IRazorEnginePhase`. */
interface RazorEnginePhase {
    fun initialize(engine: RazorEngine)
    fun execute(codeDocument: RazorCodeDocument): RazorCodeDocument
}

/** `RazorEnginePhaseBase`. */
abstract class RazorEnginePhaseBase : RazorEnginePhase {
    private var engineField: RazorEngine? = null
    val engine: RazorEngine get() = engineField ?: throw IllegalStateException("The phase ${javaClass.simpleName} is not initialized.")

    override fun initialize(engine: RazorEngine) {
        if (engineField != null) throw IllegalStateException("The phase ${javaClass.simpleName} is already initialized.")
        engineField = engine
        onInitialized()
    }

    override fun execute(codeDocument: RazorCodeDocument): RazorCodeDocument = executeCore(codeDocument)

    protected inline fun <reified T : RazorEngineFeature> getRequiredFeature(): T =
        engine.getFeatures<T>().firstOrNull() ?: throw IllegalStateException("The phase ${javaClass.simpleName} requires the feature ${T::class.java.simpleName}.")

    protected fun <T> throwForMissingDocumentDependency(value: T?): T =
        value ?: throw IllegalStateException("The phase ${javaClass.simpleName} requires a value of the code document that is not set.")

    protected open fun onInitialized() {}
    protected abstract fun executeCore(codeDocument: RazorCodeDocument): RazorCodeDocument
}

/** `IntermediateNodePassBase`: a feature that rewrites the document node; [order] sorts the passes of a phase. */
abstract class IntermediateNodePassBase : RazorEngineFeatureBase() {
    open val order: Int get() = 0

    fun execute(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        engine // throws when not initialized
        executeCore(codeDocument, documentNode)
    }

    protected abstract fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode)

    companion object {
        const val DEFAULT_FEATURE_ORDER = 1000
    }
}

/** The pass interfaces by phase (`IRazorDocumentClassifierPass`, `IRazorDirectiveClassifierPass`, `IRazorOptimizationPass`, `IRazorIntermediateNodePass`). */
interface RazorDocumentClassifierPass : RazorEngineFeature {
    val order: Int
    fun execute(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode)
}

interface RazorDirectiveClassifierPass : RazorEngineFeature {
    val order: Int
    fun execute(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode)
}

interface RazorOptimizationPass : RazorEngineFeature {
    val order: Int
    fun execute(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode)
}

/** `RazorEngine`: the features and the phases, initialized with it; [process] runs the phases in order. */
class RazorEngine(@get:JvmName("featureList") val features: List<RazorEngineFeature>, val phases: List<RazorEnginePhase>) {
    init {
        for (feature in features) feature.initialize(this)
        for (phase in phases) phase.initialize(this)
    }

    inline fun <reified T> getFeatures(): List<T> = features.filterIsInstance<T>()

    fun process(codeDocument: RazorCodeDocument): RazorCodeDocument {
        var document = codeDocument
        for (phase in phases) document = phase.execute(document)
        return document
    }
}
