package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.lang.codegen.components.ComponentCodeDirective
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentConstrainedTypeParamDirective
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentHelpers
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentImportProjectFeature
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentInjectDirective
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentLayoutDirective
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentPageDirective
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentPreserveWhitespaceDirective
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentRenderModeDirective
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentTypeParamDirective
import io.github.dotnetsupport.razor.lang.codegen.components.addComponentPasses
import io.github.dotnetsupport.razor.lang.codegen.extensions.AttributeDirective
import io.github.dotnetsupport.razor.lang.codegen.extensions.DefaultMetadataIdentifierFeature
import io.github.dotnetsupport.razor.lang.codegen.extensions.DefaultTagHelperOptimizationPass
import io.github.dotnetsupport.razor.lang.codegen.extensions.DefaultTagHelperTargetExtension
import io.github.dotnetsupport.razor.lang.codegen.extensions.DirectiveRemovalOptimizationPass
import io.github.dotnetsupport.razor.lang.codegen.extensions.DocumentationDirective
import io.github.dotnetsupport.razor.lang.codegen.extensions.EliminateMethodBodyPass
import io.github.dotnetsupport.razor.lang.codegen.extensions.FunctionsDirective
import io.github.dotnetsupport.razor.lang.codegen.extensions.ImplementsDirective
import io.github.dotnetsupport.razor.lang.codegen.extensions.InheritsDirective
import io.github.dotnetsupport.razor.lang.codegen.extensions.MetadataAttributePass
import io.github.dotnetsupport.razor.lang.codegen.extensions.MetadataAttributeTargetExtension
import io.github.dotnetsupport.razor.lang.codegen.extensions.NamespaceDirective
import io.github.dotnetsupport.razor.lang.codegen.extensions.PreallocatedAttributeTargetExtension
import io.github.dotnetsupport.razor.lang.codegen.extensions.PreallocatedTagHelperAttributeOptimizationPass
import io.github.dotnetsupport.razor.lang.codegen.extensions.ViewCssScopePass
import io.github.dotnetsupport.razor.lang.codegen.mvc.RazorExtensions
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.parser.RazorParser
import io.github.dotnetsupport.razor.lang.parser.RazorSyntaxTree
import io.github.dotnetsupport.razor.lang.parser.RazorSyntaxTreePasses
import io.github.dotnetsupport.razor.lang.parser.SeekableTextReader
import io.github.dotnetsupport.razor.lang.syntax.DirectiveDescriptor
import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import io.github.dotnetsupport.razor.lang.syntax.RazorParserOptions
import io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument
import io.github.dotnetsupport.razor.lang.syntax.TagHelperCollection
import java.io.File

/**
 * `RazorProjectEngine` (RAZOR_PLAN.md, R3b): the engine of a project — the configuration (the Razor language version, the MVC extension),
 * the file system the imports are found in, the features and the phases — built as `RazorProjectEngine.Create` builds it, so that the
 * output is the compiler's. `process(item)` runs a file through the phases.
 */

/** `IRazorFeature`. */
interface RazorFeature

/** `IRazorProjectEngineFeature`. */
interface RazorProjectEngineFeature : RazorFeature {
    fun initialize(projectEngine: RazorProjectEngine)
}

abstract class RazorProjectEngineFeatureBase : RazorProjectEngineFeature {
    private var projectEngineField: RazorProjectEngine? = null
    val projectEngine: RazorProjectEngine get() = projectEngineField ?: throw IllegalStateException("The feature ${javaClass.simpleName} is not initialized.")

    override fun initialize(projectEngine: RazorProjectEngine) {
        if (projectEngineField != null) throw IllegalStateException("The feature ${javaClass.simpleName} is already initialized.")
        projectEngineField = projectEngine
        onInitialized()
    }

    protected open fun onInitialized() {}
}

/** `IImportProjectFeature`: the imports of a file (the default `@using`s, `_ViewImports.cshtml` / `_Imports.razor` up the folders). */
interface ImportProjectFeature : RazorProjectEngineFeature {
    fun collectImports(projectItem: RazorProjectItem, imports: MutableList<RazorProjectItem>)
}

class DefaultImportProjectFeature : RazorProjectEngineFeatureBase(), ImportProjectFeature {
    override fun collectImports(projectItem: RazorProjectItem, imports: MutableList<RazorProjectItem>) {}
}

interface ConfigureRazorParserOptionsFeature : RazorEngineFeature {
    val order: Int
    fun configure(builder: RazorParserOptionsBuilder)
}

interface ConfigureRazorCodeGenerationOptionsFeature : RazorEngineFeature {
    val order: Int
    fun configure(builder: RazorCodeGenerationOptionsBuilder)
}

/** `IRazorTargetExtensionFeature`. */
interface IRazorTargetExtensionFeature : RazorEngineFeature {
    val targetExtensions: MutableList<ICodeTargetExtension>
}

class DefaultRazorTargetExtensionFeature : RazorEngineFeatureBase(), IRazorTargetExtensionFeature {
    override val targetExtensions: MutableList<ICodeTargetExtension> = ArrayList()
}

/** `ConfigureDirectivesFeature`: the directives registered per file kind go into the parser options. */
class ConfigureDirectivesFeature : RazorEngineFeatureBase(), ConfigureRazorParserOptionsFeature {
    private val fileKindToDirectivesMap = HashMap<RazorFileKind, ArrayList<DirectiveDescriptor>>()

    fun addDirective(directive: DirectiveDescriptor, vararg fileKinds: RazorFileKind) {
        val kinds = if (fileKinds.isEmpty()) arrayOf(RazorFileKind.Legacy) else fileKinds
        for (fileKind in kinds) fileKindToDirectivesMap.getOrPut(fileKind) { ArrayList() }.add(directive)
    }

    fun getDirectives(fileKind: RazorFileKind? = null): List<DirectiveDescriptor> = fileKindToDirectivesMap[fileKind ?: RazorFileKind.Legacy]?.toList() ?: emptyList()

    override val order: Int get() = 100

    override fun configure(builder: RazorParserOptionsBuilder) {
        builder.directives = getDirectives(builder.fileKind)
    }
}

/** `RazorParserOptions.Builder`. */
class RazorParserOptionsBuilder(val languageVersion: RazorLanguageVersion, val fileKind: RazorFileKind) {
    var directives: List<DirectiveDescriptor> = emptyList()
    var parseLeadingDirectives: Boolean = false
    var useRoslynTokenizer: Boolean = false
    var enableSpanEditHandlers: Boolean = false

    fun toOptions(): RazorParserOptions = RazorParserOptions(languageVersion, fileKind, directives, useRoslynTokenizer, enableSpanEditHandlers, parseLeadingDirectives)
}

/** `RazorCodeGenerationOptions.Builder`. */
class RazorCodeGenerationOptionsBuilder {
    var indentSize: Int = 4
    var newLine: String = System.lineSeparator()
    var rootNamespace: String? = null
    var cssScope: String? = null
    var suppressUniqueIds: String? = null
    var razorWarningLevel: Int = 0
    var indentWithTabs: Boolean = false
    var suppressChecksum: Boolean = false
    var suppressMetadataAttributes: Boolean = false
    var suppressMetadataSourceChecksumAttributes: Boolean = false
    var suppressPrimaryMethodBody: Boolean = false
    var suppressNullabilityEnforcement: Boolean = false
    var omitMinimizedComponentAttributeValues: Boolean = false
    var supportLocalizedComponentNames: Boolean = false
    var useEnhancedLinePragma: Boolean = true
    var suppressAddComponentParameter: Boolean = false
    var remapLinePragmaPathsOnWindows: Boolean = false
    var writeHtmlUtf8StringLiterals: Boolean = false
    var enableMarkupSplit: Boolean = false

    fun toOptions(): RazorCodeGenerationOptions = RazorCodeGenerationOptions(
        indentSize, newLine, rootNamespace, cssScope, suppressUniqueIds, razorWarningLevel, indentWithTabs, suppressChecksum, suppressMetadataAttributes,
        suppressMetadataSourceChecksumAttributes, suppressPrimaryMethodBody, suppressNullabilityEnforcement, omitMinimizedComponentAttributeValues,
        supportLocalizedComponentNames, useEnhancedLinePragma, suppressAddComponentParameter, remapLinePragmaPathsOnWindows, writeHtmlUtf8StringLiterals, enableMarkupSplit,
    )
}

/** `RazorExtension`. */
data class RazorExtension(val extensionName: String)

/**
 * `RazorConfiguration`: the Razor language version, the configuration name (`MVC-3.0` registers the MVC extension), the C# language
 * version as a major number (null: the compiler's default, the latest), and the switches of the generator.
 */
data class RazorConfiguration(
    val languageVersion: RazorLanguageVersion,
    val configurationName: String,
    val extensions: List<RazorExtension> = emptyList(),
    val csharpLanguageVersion: Int? = null,
    val useConsolidatedMvcViews: Boolean = true,
    val suppressAddComponentParameter: Boolean = false,
    val useRoslynTokenizer: Boolean = false,
    val preprocessorSymbols: List<String> = emptyList(),
    val razorWarningLevel: Int = 0,
) {
    companion object {
        val DEFAULT = RazorConfiguration(RazorLanguageVersion.LATEST, "unnamed")

        /** `LanguageVersionFacts.MapSpecifiedToEffectiveVersion` of the default: the latest C# of the compiler the SDK ships (C# 14). */
        const val LATEST_CSHARP_MAJOR = 14
    }
}

/** `RazorProjectItem`: a file of the project as the engine sees it — the paths, the kind, the text. */
abstract class RazorProjectItem {
    abstract val basePath: String?
    abstract val filePath: String?
    abstract val physicalPath: String?
    open val relativePhysicalPath: String? get() = null
    open val cssScope: String? get() = null
    open val fileKind: RazorFileKind get() = filePath?.let { FileKinds.getFileKindFromPath(it) } ?: RazorFileKind.Legacy
    abstract val exists: Boolean

    /** `Read`: the text of the file (the compiler reads a stream; the encoding is the file's, UTF-8 by default). */
    abstract fun read(): String

    val combinedPath: String? get() = if (basePath == RazorProjectFileSystem.DEFAULT_BASE_PATH) filePath else basePath + filePath
    val fileName: String get() = filePath!!.substringAfterLast('/')
    val extension: String? get() = fileName.lastIndexOf('.').let { if (it == -1) null else fileName.substring(it) }
    val filePathWithoutExtension: String get() = filePath!!.lastIndexOf('.').let { if (it == -1) filePath!! else filePath!!.substring(0, it) }

    /** `GetSource`: `RazorSourceDocument.ReadFrom(this)` — the file path is the project-relative one, the relative path the physical one. */
    open fun getSource(): RazorSourceDocument = RazorSourceDocument(read(), filePath, relativePhysicalPath, hasBom = hasBom)

    /** Whether the item's bytes start with the UTF-8 BOM: the compiler's checksum is of the stream, BOM included. */
    open val hasBom: Boolean get() = false

    override fun toString(): String = combinedPath ?: javaClass.simpleName
}

/** `DefaultRazorProjectItem`: a file on disk. */
class DefaultRazorProjectItem(
    override val basePath: String, override val filePath: String, override val physicalPath: String, override val relativePhysicalPath: String?,
    private val fileKindOverride: RazorFileKind?, override val cssScope: String?,
) : RazorProjectItem() {
    override val exists: Boolean get() = File(physicalPath).exists()
    override val fileKind: RazorFileKind get() = fileKindOverride ?: super.fileKind
    override fun read(): String = readText(File(physicalPath))
    override val hasBom: Boolean get() = File(physicalPath).inputStream().use { val b = ByteArray(3); it.read(b) == 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() }

    companion object {
        /** As .NET's `StreamReader` with detection: a BOM picks the encoding, else UTF-8. */
        fun readText(file: File): String {
            val bytes = file.readBytes()
            return when {
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                else -> String(bytes, Charsets.UTF_8)
            }
        }
    }
}

/** `DefaultImportProjectItem`: the default directives of a kind, held in memory (no path). */
class DefaultImportProjectItem(private val name: String, private val content: String) : RazorProjectItem() {
    override val basePath: String? get() = null
    override val filePath: String? get() = null
    override val physicalPath: String? get() = null
    override val exists: Boolean get() = true
    override fun read(): String = content
    private val cachedSource: RazorSourceDocument by lazy { super.getSource() }
    override fun getSource(): RazorSourceDocument = cachedSource
    override fun toString(): String = name
}

/** `NotFoundProjectItem`: an import that is not there. */
class NotFoundProjectItem(override val filePath: String, private val fileKindOverride: RazorFileKind?) : RazorProjectItem() {
    override val basePath: String? get() = null
    override val physicalPath: String? get() = null
    override val exists: Boolean get() = false
    override val fileKind: RazorFileKind get() = fileKindOverride ?: super.fileKind
    override fun read(): String = throw IllegalStateException("The item $filePath does not exist.")
}

/** An item over a text, for the tests and the oracle (`TestRazorProjectItem`). */
class TextRazorProjectItem(
    override val filePath: String, private val content: String, override val physicalPath: String? = null, override val relativePhysicalPath: String? = null,
    override val basePath: String = RazorProjectFileSystem.DEFAULT_BASE_PATH, private val fileKindOverride: RazorFileKind? = null, override val cssScope: String? = null,
) : RazorProjectItem() {
    override val exists: Boolean get() = true
    override val fileKind: RazorFileKind get() = fileKindOverride ?: super.fileKind
    override fun read(): String = content
}

/** `FileKinds`. */
object FileKinds {
    const val COMPONENT_FILE_EXTENSION = ".razor"
    const val LEGACY_FILE_EXTENSION = ".cshtml"

    fun tryGetFileKindFromPath(filePath: String): RazorFileKind? {
        val fileName = filePath.substring(filePath.lastIndexOfAny(charArrayOf('/', '\\')) + 1)
        return when {
            fileName == ComponentHelpers.IMPORTS_FILE_NAME -> RazorFileKind.ComponentImport
            fileName.endsWith(COMPONENT_FILE_EXTENSION, ignoreCase = true) -> RazorFileKind.Component
            fileName.endsWith(LEGACY_FILE_EXTENSION, ignoreCase = true) -> RazorFileKind.Legacy
            else -> null
        }
    }

    fun getFileKindFromPath(filePath: String): RazorFileKind = tryGetFileKindFromPath(filePath) ?: RazorFileKind.Legacy
}

/** `RazorProjectFileSystem`: where the items and their hierarchical imports come from. */
abstract class RazorProjectFileSystem {
    abstract fun enumerateItems(basePath: String): Sequence<RazorProjectItem>
    fun getItem(path: String): RazorProjectItem = getItem(path, null)
    abstract fun getItem(path: String, fileKind: RazorFileKind?): RazorProjectItem

    /** `FindHierarchicalItems`: `fileName` in every folder from the root down to the folder of [path], the root first. */
    fun findHierarchicalItems(path: String, fileName: String): List<RazorProjectItem> = findHierarchicalItems(DEFAULT_BASE_PATH, path, fileName)

    fun findHierarchicalItems(basePath: String, path: String, fileName: String): List<RazorProjectItem> {
        val base = normalizeAndEnsureValidPath(basePath)
        var normalized = normalizeAndEnsureValidPath(path)
        if (normalized.length == 1) return emptyList()
        if (!normalized.startsWith(base, ignoreCase = true)) return emptyList()
        val fileNameIndex = normalized.lastIndexOf('/')
        if (fileNameIndex == -1) throw IllegalStateException("Cannot find file name in path '$normalized'")
        if (normalized.substring(fileNameIndex + 1) == fileName) normalized = normalized.substring(0, fileNameIndex)
        val result = ArrayList<RazorProjectItem>()
        var pathMemory = normalized
        var index = pathMemory.length
        while (index > base.length) {
            index = pathMemory.lastIndexOf('/')
            if (index < 0) break
            pathMemory = pathMemory.substring(0, index + 1)
            result.add(getItem(pathMemory + fileName, null))
            pathMemory = pathMemory.substring(0, pathMemory.length - 1)
        }
        result.reverse()
        return result
    }

    protected open fun normalizeAndEnsureValidPath(path: String): String {
        if (path.isEmpty()) throw IllegalArgumentException("The path is empty.")
        if (path[0] != '/') throw IllegalArgumentException("Path must begin with a forward slash '/'.")
        return path
    }

    private class EmptyFileSystem : RazorProjectFileSystem() {
        override fun enumerateItems(basePath: String): Sequence<RazorProjectItem> = emptySequence()
        override fun getItem(path: String, fileKind: RazorFileKind?): RazorProjectItem = NotFoundProjectItem(normalizeAndEnsureValidPath(path), fileKind)
    }

    companion object {
        const val DEFAULT_BASE_PATH = "/"
        val EMPTY: RazorProjectFileSystem = EmptyFileSystem()
        fun create(rootDirectoryPath: String): RazorProjectFileSystem = DefaultRazorProjectFileSystem(rootDirectoryPath)
    }
}

/** `DefaultRazorProjectFileSystem`: the files under a root directory. */
class DefaultRazorProjectFileSystem(root: String) : RazorProjectFileSystem() {
    val root: String = if (root == DEFAULT_BASE_PATH) DEFAULT_BASE_PATH else root.replace('\\', '/').trimEnd('/').also { require(it.isNotEmpty()) { "Invalid path provided." } }

    override fun enumerateItems(basePath: String): Sequence<RazorProjectItem> {
        val absoluteBasePath = normalizeAndEnsureValidPath(basePath)
        val directory = File(absoluteBasePath)
        if (!directory.isDirectory) return emptySequence()
        val files = directory.walkTopDown().filter { it.isFile }
        val cshtml = files.filter { it.name.endsWith(".cshtml", ignoreCase = true) }.map { createItem(it.path, null, basePath, absoluteBasePath) }
        val razor = files.filter { it.name.endsWith(".razor", ignoreCase = true) }.map { createItem(it.path, null, basePath, absoluteBasePath) }
        return cshtml + razor
    }

    override fun getItem(path: String, fileKind: RazorFileKind?): RazorProjectItem {
        val absolutePath = normalizeAndEnsureValidPath(path)
        if (!absolutePath.startsWith(root, ignoreCase = true)) throw IllegalStateException("The file '$absolutePath' is not a descendent of the base path '$root'.")
        return createItem(absolutePath, fileKind, DEFAULT_BASE_PATH, root)
    }

    private fun createItem(path: String, fileKind: RazorFileKind?, basePath: String, absoluteBasePath: String): DefaultRazorProjectItem {
        val physicalPath = File(path).absolutePath
        val relativePhysicalPath = physicalPath.substring(absoluteBasePath.length + 1) // Don't include leading separator
        val filePath = "/" + relativePhysicalPath.replace(File.separatorChar, '/').replace('\\', '/')
        return DefaultRazorProjectItem(basePath, filePath, physicalPath, relativePhysicalPath, fileKind, null)
    }

    override fun normalizeAndEnsureValidPath(path: String): String {
        if (path == DEFAULT_BASE_PATH) return root
        require(path.isNotEmpty())
        val normalizedPath = path.replace('\\', '/')
        if (PlatformInformation.isWindows && File(path).isAbsolute) return normalizedPath
        if (normalizedPath.startsWith("//") || normalizedPath.startsWith(root, ignoreCase = true)) return normalizedPath
        val needsSlash = !root.endsWith('/') && !normalizedPath.startsWith('/')
        return root + (if (needsSlash) "/" else "") + normalizedPath
    }
}

/** `VirtualRazorProjectFileSystem`: items added by hand, for the tests and the oracle's cases. */
class VirtualRazorProjectFileSystem : RazorProjectFileSystem() {
    private val items = java.util.TreeMap<String, RazorProjectItem>(String.CASE_INSENSITIVE_ORDER)

    override fun enumerateItems(basePath: String): Sequence<RazorProjectItem> {
        val base = normalizeAndEnsureValidPath(basePath)
        return items.filterKeys { it.startsWith(base, ignoreCase = true) }.values.asSequence()
    }

    override fun getItem(path: String, fileKind: RazorFileKind?): RazorProjectItem {
        val normalized = normalizeAndEnsureValidPath(path)
        return items[normalized] ?: NotFoundProjectItem(normalized, fileKind)
    }

    fun add(projectItem: RazorProjectItem) {
        items[normalizeAndEnsureValidPath(projectItem.filePath!!)] = projectItem
    }
}

/** `RazorProjectEngineBuilder`. */
class RazorProjectEngineBuilder(val configuration: RazorConfiguration, val fileSystem: RazorProjectFileSystem) {
    val features: MutableList<RazorFeature> = ArrayList()
    val phases: MutableList<RazorEnginePhase> = ArrayList()

    fun build(): RazorProjectEngine {
        val engineFeatures = ArrayList<RazorEngineFeature>()
        val projectEngineFeatures = ArrayList<RazorProjectEngineFeature>()
        for (feature in features) when (feature) {
            is RazorEngineFeature -> engineFeatures.add(feature)
            is RazorProjectEngineFeature -> projectEngineFeatures.add(feature)
            else -> throw IllegalStateException("Encountered a RazorFeature that is neither an engine feature nor a project engine feature: $feature")
        }
        val engine = RazorEngine(engineFeatures, ArrayList(phases))
        return RazorProjectEngine(configuration, engine, fileSystem, projectEngineFeatures)
    }

    /** `RazorProjectEngineBuilderExtensions`. */
    fun addDirective(directive: DirectiveDescriptor, vararg fileKinds: RazorFileKind): RazorProjectEngineBuilder {
        getOrCreateFeature { ConfigureDirectivesFeature() }.addDirective(directive, *fileKinds)
        return this
    }

    fun addTargetExtension(extension: ICodeTargetExtension): RazorProjectEngineBuilder {
        getOrCreateFeature<IRazorTargetExtensionFeature> { DefaultRazorTargetExtensionFeature() }.targetExtensions.add(extension)
        return this
    }

    fun configureParserOptions(configure: (RazorParserOptionsBuilder) -> Unit): RazorProjectEngineBuilder {
        features.add(ConfigureParserOptionsFeature(configure))
        return this
    }

    fun configureCodeGenerationOptions(configure: (RazorCodeGenerationOptionsBuilder) -> Unit): RazorProjectEngineBuilder {
        features.add(ConfigureCodeGenerationOptionsFeature(configure))
        return this
    }

    fun setRootNamespace(rootNamespace: String?): RazorProjectEngineBuilder = configureCodeGenerationOptions { it.rootNamespace = rootNamespace }

    fun setSupportLocalizedComponentNames(): RazorProjectEngineBuilder = configureCodeGenerationOptions { it.supportLocalizedComponentNames = true }

    /** `SetCSharpLanguageVersion`: [csharpMajor] null is the compiler's default (the latest). */
    fun setCSharpLanguageVersion(csharpMajor: Int?): RazorProjectEngineBuilder {
        features.removeAll { it is ConfigureParserForCSharpVersionFeature }
        features.add(ConfigureParserForCSharpVersionFeature(configuration.languageVersion, csharpMajor ?: RazorConfiguration.LATEST_CSHARP_MAJOR))
        return this
    }

    /** `RegisterExtensions`: the MVC extension of the configuration name (`MVC-3.0`; the 1.x / 2.x extensions are not ported). */
    fun registerExtensions() {
        val name = configuration.configurationName
        if (!name.startsWith("MVC-")) return
        when (name.substring("MVC-".length)) {
            "3.0" -> RazorExtensions.register(this)
            "1.0", "1.1", "2.0", "2.1" -> throw UnsupportedOperationException("The MVC $name extension is not ported.")
        }
    }

    private inline fun <reified T : RazorEngineFeature> getOrCreateFeature(create: () -> T): T {
        features.filterIsInstance<T>().firstOrNull()?.let { return it }
        return create().also { features.add(it) }
    }

    private class ConfigureParserOptionsFeature(private val configureBuilder: (RazorParserOptionsBuilder) -> Unit) : RazorEngineFeatureBase(), ConfigureRazorParserOptionsFeature {
        override val order: Int get() = 0
        override fun configure(builder: RazorParserOptionsBuilder) = configureBuilder(builder)
    }

    private class ConfigureCodeGenerationOptionsFeature(private val configureBuilder: (RazorCodeGenerationOptionsBuilder) -> Unit) : RazorEngineFeatureBase(), ConfigureRazorCodeGenerationOptionsFeature {
        override val order: Int get() = 0
        override fun configure(builder: RazorCodeGenerationOptionsBuilder) = configureBuilder(builder)
    }

    /** `ConfigureParserForCSharpVersionFeature`: nullability enforcement needs C# 8 and Razor 3; the enhanced `#line` C# 10. */
    private class ConfigureParserForCSharpVersionFeature(private val razorLanguageVersion: RazorLanguageVersion, private val csharpMajor: Int) : RazorEngineFeatureBase(), ConfigureRazorCodeGenerationOptionsFeature {
        override val order: Int get() = 0
        override fun configure(builder: RazorCodeGenerationOptionsBuilder) {
            builder.suppressNullabilityEnforcement = razorLanguageVersion.major < 3 || csharpMajor < 8
            if (razorLanguageVersion.major >= 5) builder.omitMinimizedComponentAttributeValues = true
            if (csharpMajor >= 10) builder.useEnhancedLinePragma = true
        }
    }
}

class RazorProjectEngine internal constructor(
    val configuration: RazorConfiguration,
    val engine: RazorEngine,
    val fileSystem: RazorProjectFileSystem,
    @get:JvmName("featureList") val features: List<RazorProjectEngineFeature>,
) {
    val phases: List<RazorEnginePhase> get() = engine.phases
    private val configureParserOptionsFeatures: List<ConfigureRazorParserOptionsFeature>
    private val configureCodeGenerationOptionsFeatures: List<ConfigureRazorCodeGenerationOptionsFeature>

    init {
        for (feature in features) feature.initialize(this)
        configureParserOptionsFeatures = engine.getFeatures<ConfigureRazorParserOptionsFeature>().sortedBy { it.order }
        configureCodeGenerationOptionsFeatures = engine.getFeatures<ConfigureRazorCodeGenerationOptionsFeature>().sortedBy { it.order }
    }

    inline fun <reified T : RazorProjectEngineFeature> getFeatures(): List<T> = features.filterIsInstance<T>()

    fun process(projectItem: RazorProjectItem): RazorCodeDocument = engine.process(createCodeDocument(projectItem))

    fun process(source: RazorSourceDocument, fileKind: RazorFileKind, importSources: List<RazorSourceDocument>, tagHelpers: TagHelperCollection?): RazorCodeDocument =
        engine.process(createCodeDocumentCore(source, fileKind, importSources, tagHelpers, null, null, null))

    fun processDeclarationOnly(projectItem: RazorProjectItem): RazorCodeDocument =
        engine.process(createCodeDocumentCore(projectItem, null) { it.suppressPrimaryMethodBody = true })

    fun createCodeDocument(projectItem: RazorProjectItem): RazorCodeDocument = createCodeDocumentCore(projectItem, null, null)

    fun createCodeDocument(source: RazorSourceDocument, fileKind: RazorFileKind, importSources: List<RazorSourceDocument>, tagHelpers: TagHelperCollection?, cssScope: String?): RazorCodeDocument =
        createCodeDocumentCore(source, fileKind, importSources, tagHelpers, cssScope, null, null)

    private fun createCodeDocumentCore(
        projectItem: RazorProjectItem, configureParser: ((RazorParserOptionsBuilder) -> Unit)?, configureCodeGeneration: ((RazorCodeGenerationOptionsBuilder) -> Unit)?,
    ): RazorCodeDocument {
        val source = projectItem.getSource()
        val importSources = getImportSources(projectItem)
        return createCodeDocumentCore(source, projectItem.fileKind, importSources, null, projectItem.cssScope, configureParser, configureCodeGeneration)
    }

    private fun createCodeDocumentCore(
        source: RazorSourceDocument, fileKind: RazorFileKind, importSources: List<RazorSourceDocument>, tagHelpers: TagHelperCollection?, cssScope: String?,
        configureParser: ((RazorParserOptionsBuilder) -> Unit)?, configureCodeGeneration: ((RazorCodeGenerationOptionsBuilder) -> Unit)?,
    ): RazorCodeDocument {
        val parserOptions = computeParserOptions(fileKind, configureParser)
        val codeGenerationOptions = computeCodeGenerationOptions(cssScope, configureCodeGeneration)
        val codeDocument = RazorCodeDocument.create(source, importSources, parserOptions, codeGenerationOptions)
        if (tagHelpers != null) codeDocument.items[RazorCodeDocument.TAG_HELPERS] = tagHelpers
        return codeDocument
    }

    private fun computeParserOptions(fileKind: RazorFileKind, configure: ((RazorParserOptionsBuilder) -> Unit)?): RazorParserOptions {
        val builder = RazorParserOptionsBuilder(configuration.languageVersion, fileKind)
        builder.useRoslynTokenizer = configuration.useRoslynTokenizer
        configure?.invoke(builder)
        for (feature in configureParserOptionsFeatures) feature.configure(builder)
        return builder.toOptions()
    }

    private fun computeCodeGenerationOptions(cssScope: String?, configure: ((RazorCodeGenerationOptionsBuilder) -> Unit)?): RazorCodeGenerationOptions {
        val builder = RazorCodeGenerationOptionsBuilder()
        builder.cssScope = cssScope
        builder.suppressAddComponentParameter = configuration.suppressAddComponentParameter
        builder.razorWarningLevel = configuration.razorWarningLevel
        configure?.invoke(builder)
        for (feature in configureCodeGenerationOptionsFeatures) feature.configure(builder)
        return builder.toOptions()
    }

    fun collectImports(projectItem: RazorProjectItem, importItems: MutableList<RazorProjectItem>) {
        for (feature in getFeatures<ImportProjectFeature>()) feature.collectImports(projectItem, importItems)
    }

    fun getImports(projectItem: RazorProjectItem, predicate: ((RazorProjectItem) -> Boolean)? = null): List<RazorProjectItem> {
        val imports = ArrayList<RazorProjectItem>()
        collectImports(projectItem, imports)
        return if (predicate == null) imports else imports.filter(predicate)
    }

    private fun getImportSources(projectItem: RazorProjectItem): List<RazorSourceDocument> {
        val importItems = ArrayList<RazorProjectItem>()
        collectImports(projectItem, importItems)
        return getImportSourceDocuments(importItems)
    }

    companion object {
        fun getImportSourceDocuments(importItems: List<RazorProjectItem>): List<RazorSourceDocument> = importItems.filter { it.exists }.map { it.getSource() }

        fun createEmpty(configure: ((RazorProjectEngineBuilder) -> Unit)? = null): RazorProjectEngine {
            val builder = RazorProjectEngineBuilder(RazorConfiguration.DEFAULT, RazorProjectFileSystem.EMPTY)
            configure?.invoke(builder)
            return builder.build()
        }

        fun create(configure: (RazorProjectEngineBuilder) -> Unit): RazorProjectEngine = create(RazorConfiguration.DEFAULT, RazorProjectFileSystem.EMPTY, configure)

        fun create(configuration: RazorConfiguration, fileSystem: RazorProjectFileSystem, configure: ((RazorProjectEngineBuilder) -> Unit)? = null): RazorProjectEngine {
            val builder = RazorProjectEngineBuilder(configuration, fileSystem)
            addDefaultPhases(builder.phases)
            addDefaultFeatures(builder.features)
            if (configuration.languageVersion >= RazorLanguageVersion.VERSION_12_0) DocumentationDirective.register(builder)
            if (configuration.languageVersion >= RazorLanguageVersion.VERSION_5_0) builder.features.add(ViewCssScopePass())
            if (configuration.languageVersion >= RazorLanguageVersion.VERSION_3_0) {
                FunctionsDirective.register(builder)
                ImplementsDirective.register(builder)
                InheritsDirective.register(builder)
                NamespaceDirective.register(builder)
                AttributeDirective.register(builder)
                addComponentFeatures(builder, configuration.languageVersion)
            }
            configure?.invoke(builder)
            return builder.build()
        }

        private fun addDefaultPhases(phases: MutableList<RazorEnginePhase>) {
            phases.add(DefaultRazorParsingPhase())
            phases.add(DefaultRazorSyntaxTreePhase())
            phases.add(DefaultRazorIntermediateNodeLoweringPhase())
            phases.add(DefaultRazorDocumentClassifierPhase())
            phases.add(DefaultRazorDirectiveClassifierPhase())
            phases.add(DefaultRazorMarkupSplitPhase())
            phases.add(DefaultRazorDeclCSharpLoweringPhase())
            phases.add(DefaultRazorTagHelperContextDiscoveryPhase())
            phases.add(DefaultTagHelperResolutionPhase())
            phases.add(DefaultRazorTagHelperRewritePhase())
            phases.add(DefaultRazorOptimizationPhase())
            phases.add(Utf8WriteLiteralPhase())
            phases.add(DefaultRazorCSharpLoweringPhase())
        }

        private fun addDefaultFeatures(features: MutableList<RazorFeature>) {
            features.add(DefaultImportProjectFeature())
            features.add(TagHelperDiscoveryService())
            features.add(ConfigureDirectivesFeature())
            features.add(DefaultMetadataIdentifierFeature())
            features.add(DefaultDirectiveSyntaxTreePass())
            features.add(HtmlNodeOptimizationPass())
            features.add(DefaultDocumentClassifierPass())
            features.add(MetadataAttributePass())
            features.add(DirectiveRemovalOptimizationPass())
            features.add(DefaultTagHelperOptimizationPass())
            features.add(PreallocatedTagHelperAttributeOptimizationPass())
            features.add(EliminateMethodBodyPass())
            val targetExtensionFeature = DefaultRazorTargetExtensionFeature()
            features.add(targetExtensionFeature)
            targetExtensionFeature.targetExtensions.add(MetadataAttributeTargetExtension())
            targetExtensionFeature.targetExtensions.add(DefaultTagHelperTargetExtension())
            targetExtensionFeature.targetExtensions.add(PreallocatedAttributeTargetExtension())
            val configurationFeature = DefaultDocumentClassifierPassFeature()
            features.add(configurationFeature)
            configurationFeature.configureClass.add { _, clazz ->
                clazz.name = "Template"
                clazz.modifiers = io.github.dotnetsupport.razor.lang.intermediate.CommonModifiers.public
            }
            configurationFeature.configureNamespace.add { _, namespace -> namespace.name = "Razor" }
            configurationFeature.configureMethod.add { _, method ->
                method.name = "ExecuteAsync"
                method.returnType = "global::System.Threading.Tasks.Task"
                method.modifiers = io.github.dotnetsupport.razor.lang.intermediate.CommonModifiers.publicAsyncOverride
            }
        }

        private fun addComponentFeatures(builder: RazorProjectEngineBuilder, razorLanguageVersion: RazorLanguageVersion) {
            builder.features.add(ComponentImportProjectFeature())
            ComponentCodeDirective.register(builder)
            ComponentInjectDirective.register(builder)
            ComponentLayoutDirective.register(builder)
            ComponentPageDirective.register(builder)
            if (razorLanguageVersion >= RazorLanguageVersion.VERSION_6_0) ComponentConstrainedTypeParamDirective.register(builder) else ComponentTypeParamDirective.register(builder)
            if (razorLanguageVersion >= RazorLanguageVersion.VERSION_5_0) ComponentPreserveWhitespaceDirective.register(builder)
            if (razorLanguageVersion >= RazorLanguageVersion.VERSION_8_0) ComponentRenderModeDirective.register(builder)
            builder.features.add(ComponentDocumentClassifierPass())
            addComponentPasses(builder, razorLanguageVersion)
        }
    }
}

/** `DefaultRazorParsingPhase`: the file and its imports parsed (the import trees are not cached here). */
class DefaultRazorParsingPhase : RazorEnginePhaseBase() {
    override fun executeCore(codeDocument: RazorCodeDocument): RazorCodeDocument {
        val options = codeDocument.parserOptions
        val syntaxTree = RazorParser.parse(codeDocument.source.text, options, codeDocument.source.filePath, codeDocument.source, applyPasses = false)
        val importSyntaxTrees = codeDocument.imports.map { RazorParser.parse(it.text, options, it.filePath, it, applyPasses = false) }
        return codeDocument.withSyntaxTree(syntaxTree).withImportSyntaxTrees(importSyntaxTrees)
    }
}

/** `IRazorSyntaxTreePass`. */
interface RazorSyntaxTreePass : RazorEngineFeature {
    val order: Int
    fun execute(codeDocument: RazorCodeDocument, syntaxTree: RazorSyntaxTree): RazorSyntaxTree
}

/** `DefaultDirectiveSyntaxTreePass`: RZ2002 of nested `@section`s (not in components). */
class DefaultDirectiveSyntaxTreePass : RazorEngineFeatureBase(), RazorSyntaxTreePass {
    override val order: Int get() = 75

    override fun execute(codeDocument: RazorCodeDocument, syntaxTree: RazorSyntaxTree): RazorSyntaxTree {
        RazorSyntaxTreePasses.verifyNestedSections(syntaxTree.root, syntaxTree.options, SeekableTextReader(syntaxTree.text, syntaxTree.source?.filePath))
        return syntaxTree
    }
}

/** `HtmlNodeOptimizationPass`: the whitespace rewriter. */
class HtmlNodeOptimizationPass : RazorEngineFeatureBase(), RazorSyntaxTreePass {
    override val order: Int get() = 100

    override fun execute(codeDocument: RazorCodeDocument, syntaxTree: RazorSyntaxTree): RazorSyntaxTree {
        RazorSyntaxTreePasses.rewriteWhitespace(syntaxTree.root)
        return syntaxTree
    }
}

class DefaultRazorSyntaxTreePhase : RazorEnginePhaseBase() {
    private var passes: List<RazorSyntaxTreePass> = emptyList()

    override fun onInitialized() {
        passes = engine.getFeatures<RazorSyntaxTreePass>().sortedBy { it.order }
    }

    override fun executeCore(codeDocument: RazorCodeDocument): RazorCodeDocument {
        var syntaxTree = throwForMissingDocumentDependency(codeDocument.getSyntaxTree())
        for (pass in passes) syntaxTree = pass.execute(codeDocument, syntaxTree)
        return codeDocument.withSyntaxTree(syntaxTree)
    }
}

class DefaultRazorDocumentClassifierPhase : RazorEnginePhaseBase() {
    private var passes: List<RazorDocumentClassifierPass> = emptyList()

    override fun onInitialized() {
        passes = engine.getFeatures<RazorDocumentClassifierPass>().sortedBy { it.order }
    }

    override fun executeCore(codeDocument: RazorCodeDocument): RazorCodeDocument {
        val documentNode = throwForMissingDocumentDependency(codeDocument.getDocumentNode())
        for (pass in passes) pass.execute(codeDocument, documentNode)
        return codeDocument.withDocumentNode(documentNode)
    }
}

class DefaultRazorDirectiveClassifierPhase : RazorEnginePhaseBase() {
    private var passes: List<RazorDirectiveClassifierPass> = emptyList()

    override fun onInitialized() {
        passes = engine.getFeatures<RazorDirectiveClassifierPass>().sortedBy { it.order }
    }

    override fun executeCore(codeDocument: RazorCodeDocument): RazorCodeDocument {
        val documentNode = throwForMissingDocumentDependency(codeDocument.getDocumentNode())
        for (pass in passes) pass.execute(codeDocument, documentNode)
        return codeDocument.withDocumentNode(documentNode)
    }
}

class DefaultRazorOptimizationPhase : RazorEnginePhaseBase() {
    private var passes: List<RazorOptimizationPass> = emptyList()

    override fun onInitialized() {
        passes = engine.getFeatures<RazorOptimizationPass>().sortedBy { it.order }
    }

    override fun executeCore(codeDocument: RazorCodeDocument): RazorCodeDocument {
        val documentNode = throwForMissingDocumentDependency(codeDocument.getDocumentNode())
        for (pass in passes) pass.execute(codeDocument, documentNode)
        return codeDocument.withDocumentNode(documentNode)
    }
}

/** `DefaultRazorCSharpLoweringPhase`: the document node written to C#. */
class DefaultRazorCSharpLoweringPhase : RazorEnginePhaseBase() {
    override fun executeCore(codeDocument: RazorCodeDocument): RazorCodeDocument {
        val documentNode = throwForMissingDocumentDependency(codeDocument.getDocumentNode())
        if (documentNode.target == null) throw IllegalStateException("The document of kind '${documentNode.documentKind}' has no code target: a classifier must set DocumentIntermediateNode.target.")
        val csharpDocument = RazorCSharpDocumentWriter.write(documentNode, codeDocument)
        return codeDocument.withImplCSharpDocument(csharpDocument)
    }
}

/** `DefaultRazorDeclCSharpLoweringPhase`: the declaration document of a split component, when the split phase made one. */
class DefaultRazorDeclCSharpLoweringPhase : RazorEnginePhaseBase() {
    override fun executeCore(codeDocument: RazorCodeDocument): RazorCodeDocument {
        val documentNode = throwForMissingDocumentDependency(codeDocument.getDocumentNode())
        val declDocNode = documentNode.declDocumentNode ?: return codeDocument
        if (codeDocument.getTagHelperRewrittenSyntaxTree() == null) codeDocument.withTagHelperRewrittenSyntaxTree(codeDocument.getRequiredSyntaxTree())
        val declDocument = RazorCSharpDocumentWriter.write(declDocNode, codeDocument, reportDiagnostics = false, isDeclarationDocument = true, isStubDocument = documentNode.fallbackComponentTypeName != null)
        return codeDocument.withDeclCSharpDocument(declDocument)
    }
}
