package io.github.dotnetsupport.frameworks.razor

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.lang.CSharpLanguage
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.razor.lang.codegen.NotFoundProjectItem
import io.github.dotnetsupport.razor.lang.codegen.RazorGeneratorEngine
import io.github.dotnetsupport.razor.lang.codegen.RazorProjectEngine
import io.github.dotnetsupport.razor.lang.codegen.RazorProjectFileSystem
import io.github.dotnetsupport.razor.lang.codegen.RazorProjectItem
import io.github.dotnetsupport.razor.lang.codegen.SourceMapping
import io.github.dotnetsupport.razor.lang.codegen.getTagHelperContext
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import java.util.concurrent.ConcurrentHashMap

/**
 * The virtual C# document of a Razor file (RAZOR_PLAN.md, R3b): the C# the SDK's source generator makes of it — the port of the compiler's
 * pipeline in razor-psi-core ([RazorGeneratorEngine]) run on the text of the editor, with the project's `_Imports.razor` /
 * `_ViewImports.cshtml` up the folders, its root namespace and its Razor language version. One C# injection of many pieces: every source
 * mapping of the generated code whose original span lies in a C# literal of the PSI is a host (the Razor text as written), the generated
 * text between the mappings is the glue. The platform maps the positions: completion, navigation, errors and rename work in the Razor
 * editor through the pieces.
 */
object RazorCSharpDocument {
    /** A place of the injection: the [range] of [host] with the generated text before and after it. */
    class Place(val prefix: String, val host: RazorCodeLiteral, val range: TextRange, var suffix: String)

    /** What the generator made of the file: the C#, the mappings (original → generated) and the diagnostics of the compiler. */
    class Generated(
        val code: String, val mappings: List<SourceMapping>, val diagnostics: List<RazorDiagnostic>, val filePath: String,
        /** The components and tag helpers in scope of the file (R5e: completion and navigation in the markup); null without the project's. */
        val tagHelperContext: io.github.dotnetsupport.razor.lang.syntax.TagHelperDocumentContext? = null,
    )

    private val GENERATED = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<Generated?>>("razor.generated")

    /** The generator's output for [file]; null when the file has no text. Cached until the file changes. */
    fun generate(file: RazorFile): Generated? = CachedValuesManager.getManager(file.project).getCachedValue(file, GENERATED, {
        // made again when the tag helpers of the project change (a component's new parameter in another file)
        CachedValueProvider.Result.create(generateNow(file), file, RazorTagHelperService.getInstance(file.project).tracker)
    }, false)

    private fun generateNow(file: RazorFile): Generated? = withEngine(file) { engine, item, projectFile ->
        // the components and tag helpers of the project (R5d): their elements become C# — parameters, `@bind`, event handlers
        val tagHelpers = projectFile?.let { RazorTagHelperService.getInstance(file.project).tagHelpers(it) }
        val document = engine.process(item, tagHelpers)
        val csharp = document.getCSharpDocument() ?: return@withEngine null
        val context = if (tagHelpers == null) null else document.getTagHelperContext()
        Generated(csharp.generatedCode, csharp.sourceMappingsSortedByGenerated, csharp.diagnostics, item.getSource().filePath ?: item.filePath ?: "", context)
    }

    private val DECLARATION = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<String?>>("razor.declaration")

    /**
     * The declaration of the component [file] declares, as the source generator compiles it for the tag helper discovery
     * (`ProcessDeclarationOnly`: the class with its usings, `@inherits`, `@typeparam`, `@attribute` and the members of `@code`, no rendering);
     * null for a file that is no component (`.cshtml`, `_Imports.razor`) or has no text. Cached until the file changes.
     */
    fun declaration(file: RazorFile): String? = CachedValuesManager.getManager(file.project).getCachedValue(file, DECLARATION, {
        val component = io.github.dotnetsupport.razor.lang.codegen.FileKinds.getFileKindFromPath(file.name) == RazorFileKind.Component
        val text = if (!component) null else withEngine(file) { engine, item, _ -> engine.processDeclarationOnly(item).getCSharpDocument()?.generatedCode }
        CachedValueProvider.Result.create(text, file)
    }, false)

    /**
     * The C# the SDK's source generator writes for [file] (its engine without [RazorIdeLayoutPass], the project's tag helpers): what
     * [RazorGeneratedAhead] puts where the helper puts the generator's output; null when the file has no text.
     */
    fun compilerOutput(file: RazorFile): String? = withEngine(file, ide = false) { engine, item, projectFile ->
        val tagHelpers = projectFile?.let { RazorTagHelperService.getInstance(file.project).tagHelpers(it) }
        engine.process(item, tagHelpers).getCSharpDocument()?.generatedCode
    }

    /**
     * [action] with the engine of [file]'s project and the item of [file] (its text as the PSI has it); [ide]: with [RazorIdeLayoutPass],
     * the members of the class in the order of the file (the injection needs it), else the generator's own output.
     */
    private fun <T> withEngine(file: RazorFile, ide: Boolean = true, action: (RazorProjectEngine, RazorProjectItem, VirtualFile?) -> T?): T? {
        val text = file.text
        if (text.isEmpty()) return null
        // a copy of the file (completion works on one) has a virtual file without a parent: the project is the original's
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val projectFile = virtualFile.takeIf { it.isInLocalFileSystem }?.let { DotNetProjects.findOwningProject(it) }
        val root = projectFile?.parent ?: virtualFile.parent
        val relative = root?.let { VfsUtilCore.getRelativePath(virtualFile, it, '/') } ?: virtualFile.name
        val options = if (projectFile != null) CompilationModel.getInstance(file.project).optionsFor(virtualFile) else null
        val rootNamespace = options?.rootNamespace?.takeIf { it.isNotBlank() } ?: projectFile?.nameWithoutExtension ?: file.project.name
        val version = RazorGeneratorEngine.versionOf(options?.targetFramework ?: options?.targetFrameworks?.firstOrNull())
        // a folder deleted and made again is another file: the engine of the old one would read an invalid one
        val engine = engines.compute(EngineKey(root?.path, rootNamespace, version, ide)) { _, old ->
            old?.takeIf { (it.fileSystem as HostFileSystem).root?.isValid != false }
                ?: RazorGeneratorEngine.create(HostFileSystem(root), version, rootNamespace, "\n") { b -> if (ide) b.features.add(RazorIdeLayoutPass()) }
        }!!
        val fileSystem = engine.fileSystem as HostFileSystem
        fileSystem.current.set(Current("/$relative", virtualFile.path, text))
        try {
            return action(engine, fileSystem.getItem("/$relative", null), projectFile)
        } finally {
            fileSystem.current.remove()
        }
    }

    /**
     * The places of [file], in the order of the generated code; empty when the file has no C# at all. The hosts must come in the order of the
     * file (the platform assumes it: completion asserts the caret lies between the first and the last host), so of the mappings the longest
     * chain in the order of the file (weighted by length, [RazorIdeLayoutPass] has put the members in order already) are the hosts, the
     * rest is glue text — the `@page` route before the `@using` lines, a mapping of the same expression written twice.
     */
    fun places(file: RazorFile): List<Place> = CachedValuesManager.getManager(file.project).getCachedValue(file, PLACES, {
        CachedValueProvider.Result.create(computePlaces(file), file, RazorTagHelperService.getInstance(file.project).tracker)
    }, false)

    private val PLACES = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<List<Place>>>("razor.places")

    /**
     * Whether [offset] is in (or right at the end of) a literal of markup that hosts C# (`asp-for="Name"`): the view provider then answers
     * `findElementAt` with the leaf of the Razor tree, so the platform finds the injection there — for the markup it prefers the HTML tree,
     * whose element knows no injection ([RazorFileViewProvider.findElementAt]).
     */
    fun hostsMarkupAt(file: RazorFile, offset: Int): Boolean = places(file).any { isAttributeValueHost(it.host) && it.host.textRange.grown(1).contains(offset) }

    /**
     * A host of markup that is the value of an attribute: the C# the compiler reads there (`asp-for="Name"`, `Step="2"`). Not the name of
     * an attribute, which the compiler maps too (`nameof(Counter.Step)`): a name stays markup to the editor — the XML target of Alt+F7 and
     * Shift+F6, which [RazorTagHelperEditor] turns into the parameter.
     */
    fun isAttributeValueHost(host: RazorCodeLiteral): Boolean =
        !host.isCode && host.isValid && (host.parent as? RazorElement)?.kind == io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind.MarkupLiteralAttributeValue

    private fun computePlaces(file: RazorFile): List<Place> {
        val generated = generate(file) ?: return emptyList()
        class Candidate(val mapping: SourceMapping, val host: RazorCodeLiteral, val range: TextRange)
        val candidates = ArrayList<Candidate?>()
        val text = file.text
        for (mapping in generated.mappings) {
            val o = mapping.originalSpan
            var length = o.length
            var host = if (o.filePath == generated.filePath) hostOf(file, o.absoluteIndex, length) else null
            if (host == null && o.filePath == generated.filePath) {
                // the value of an attribute with whitespace after it (`asp-for="Name "`, completion's dummy identifier): the compiler maps the
                // whitespace too, the literal of the tree ends before it — the host is the value, the whitespace is no C#
                while (length > 0 && o.absoluteIndex + length <= text.length && text[o.absoluteIndex + length - 1].isWhitespace()) length--
                if (length < o.length) host = hostOf(file, o.absoluteIndex, length)
            }
            candidates += host?.let { Candidate(mapping, it, TextRange(o.absoluteIndex - it.textRange.startOffset, o.absoluteIndex - it.textRange.startOffset + length)) }
        }
        val chosen = longestChain(candidates.map { c -> c?.let { it.mapping.originalSpan.absoluteIndex to it.mapping.originalSpan.length } })
        val places = ArrayList<Place>()
        val pending = StringBuilder()
        var at = 0
        for ((i, candidate) in candidates.withIndex()) {
            if (candidate == null || i !in chosen) continue
            val g = candidate.mapping.generatedSpan
            if (g.absoluteIndex < at) continue // overlapping the previous mapping in the generated code: glue
            pending.append(generated.code, at, g.absoluteIndex)
            at = g.absoluteIndex + g.length
            places += Place(pending.toString(), candidate.host, candidate.range, "")
            pending.setLength(0)
        }
        if (places.isEmpty()) return emptyList()
        pending.append(generated.code, at, generated.code.length)
        places.last().suffix = pending.toString()
        return places
    }

    /** The indices of the heaviest chain of [spans] (start, length; null — not a candidate) whose spans follow each other without overlap. */
    private fun longestChain(spans: List<Pair<Int, Int>?>): Set<Int> {
        val best = IntArray(spans.size)
        val previous = IntArray(spans.size) { -1 }
        var bestEnd = -1
        for (i in spans.indices) {
            val span = spans[i] ?: continue
            best[i] = span.second
            for (j in 0 until i) {
                val before = spans[j] ?: continue
                if (before.first + before.second <= span.first && best[j] + span.second > best[i]) {
                    best[i] = best[j] + span.second
                    previous[i] = j
                }
            }
            if (bestEnd < 0 || best[i] > best[bestEnd]) bestEnd = i
        }
        val chosen = HashSet<Int>()
        var i = bestEnd
        while (i >= 0) {
            chosen += i
            i = previous[i]
        }
        return chosen
    }

    /** The C# literal of the PSI that holds [start]..[start]+[length] whole, when it is a host. */
    private fun hostOf(file: RazorFile, start: Int, length: Int): RazorCodeLiteral? {
        if (length <= 0) return null
        // the leaf of the Razor tree itself: `findElementAt` of the file answers from the HTML view for the markup, where the value of a
        // tag helper's attribute is (`asp-for="Name"`)
        val leaf = file.node.findLeafElementAt(start)?.psi ?: return null
        val host = PsiTreeUtil.getParentOfType(leaf, RazorCodeLiteral::class.java, false) ?: return null
        if (!host.isValidHost) return null
        // markup hosts C# only as the value of an attribute: the compiler maps a parameter's name too (`nameof(Counter.Step)` over `Step=`),
        // which would make the name of an attribute C# — a usage found twice, a rename written twice (the WSL sandbox, 0.1.285)
        if (!host.isCode && !isAttributeValueHost(host)) return null
        return host.takeIf { it.textRange.contains(TextRange(start, start + length)) }
    }

    /** The text of the document as the C# parser sees it: the places glued, for tests and the trace. */
    fun text(file: RazorFile): String = places(file).joinToString("") { it.prefix + it.range.substring(it.host.text) + it.suffix }

    private data class EngineKey(val root: String?, val rootNamespace: String, val version: RazorLanguageVersion, val ide: Boolean)

    /** One engine per project root, root namespace and version: the engine holds no document state, the file system reads the IDE's documents. */
    private val engines = ConcurrentHashMap<EngineKey, RazorProjectEngine>()

    /**
     * The files of the project as the IDE sees them: the text of an open document (unsaved edits included), else the file on disk; the file
     * being generated — [current] — as the PSI has it.
     */
    /** The file being generated: its Razor path, physical path and the text of the PSI. */
    private class Current(val filePath: String, val physicalPath: String, val text: String)

    private class HostFileSystem(val root: VirtualFile?) : RazorProjectFileSystem() {
        val current = ThreadLocal<Current?>()

        override fun enumerateItems(basePath: String): Sequence<RazorProjectItem> = emptySequence()

        override fun getItem(path: String, fileKind: RazorFileKind?): RazorProjectItem {
            val normalized = normalizeAndEnsureValidPath(path)
            val currentFile = current.get()
            if (currentFile != null && currentFile.filePath == normalized) return HostItem(normalized, currentFile.physicalPath, normalized.removePrefix("/"), fileKind, currentFile.text)
            val file = root?.findFileByRelativePath(normalized.removePrefix("/"))
            if (file == null || !file.exists() || file.isDirectory) return NotFoundProjectItem(normalized, fileKind)
            val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtilCore.loadText(file)
            return HostItem(normalized, file.path, normalized.removePrefix("/"), fileKind, text)
        }
    }

    private class HostItem(
        override val filePath: String, override val physicalPath: String, override val relativePhysicalPath: String, private val kind: RazorFileKind?, private val content: String,
    ) : RazorProjectItem() {
        override val basePath: String get() = RazorProjectFileSystem.DEFAULT_BASE_PATH
        override val exists: Boolean get() = true
        override val fileKind: RazorFileKind get() = kind ?: super.fileKind
        override fun read(): String = content
    }
}

/** The injection: the C# document of a Razor file, one injection of its places. */
class RazorCSharpInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(RazorDocumentPsi::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val file = context.containingFile as? RazorFile ?: return
        val places = RazorCSharpDocument.places(file)
        if (places.isEmpty()) return
        registrar.startInjecting(CSharpLanguage)
        for (place in places) registrar.addPlace(place.prefix, place.suffix, place.host, place.range)
        registrar.doneInjecting()
    }
}
