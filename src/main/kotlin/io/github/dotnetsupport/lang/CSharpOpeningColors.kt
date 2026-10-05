package io.github.dotnetsupport.lang

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dotnetsupport.lsp.RoslynServerStatus
import org.jetbrains.annotations.TestOnly

/**
 * The colors of the annotators on screen as a C# editor opens (0.1.84). The daemon starts its first pass about half a second after an editor
 * is created (its delay and the loading of the editor), so identifiers, inactive `#if` text and format items got their colors visibly after
 * the lexer's. This layer paints the same at once, before the daemon:
 * - from the colors the file had when its editor was closed, if its text is still the same ([Snapshot], in memory): before the first paint;
 * - else computed right away in the background by the same functions as the annotators ([compute]); the semantic part is cached on the
 *   file ([NativeCSharpSemanticColors.colors]), so the pass of the daemon right after reuses it instead of computing it again.
 *
 * The layer is highlighters of the editor's own markup at the daemon's layer with the same keys; when the daemon has finished with the
 * editor they are removed ([Daemon]), and as its highlighters cover the same ranges in the same colors, nothing repaints. A document that
 * still has the daemon's colors (its editor was closed and opened again, or the platform restored them from its cache) is left alone.
 */
object CSharpOpeningColors {
    /** The colors of a file's text: ranges and keys, for the text with [hash] and [length] only. */
    class Snapshot(val hash: Long, val length: Int, val starts: IntArray, val ends: IntArray, val keys: Array<TextAttributesKey>) {
        val colors: List<Pair<TextRange, TextAttributesKey>> get() = keys.indices.map { TextRange(starts[it], ends[it]) to keys[it] }
        fun matches(text: CharSequence): Boolean = text.length == length && hash(text) == hash
    }

    /** As many files as a long session reopens; a big file costs some 40 KB. */
    private const val MAX_SNAPSHOTS = 200
    private val snapshots = object : LinkedHashMap<String, Snapshot>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?): Boolean = size > MAX_SNAPSHOTS
    }

    private val LAYER = Key.create<List<RangeHighlighter>>("dotnet.openingColors")
    /** The modification stamp of the document when the daemon last finished with the editor: its colors are complete for that text. */
    private val COMPLETE_AT = Key.create<Long>("dotnet.openingColors.completeAt")
    /** The layer was computed while the IDE indexed. */
    private val PROVISIONAL = Key.create<Boolean>("dotnet.openingColors.provisional")

    @Volatile private var enabledInTests = false

    fun hash(text: CharSequence): Long {
        var h = 1125899906842597L
        for (i in 0 until text.length) h = 31 * h + text[i].code
        return h
    }

    // ---- what the annotators paint

    /**
     * Everything the color annotators of C# paint on [file] — inactive `#if` text ([NativeCSharpSemanticColorsAnnotator]), identifiers
     * (the same, or [CSharpIdentifierAnnotator] when the native colors do not serve), format items ([CSharpFormatItemsAnnotator]) — in the
     * order of the text. Under a read action; reads the stubs only when the native colors serve, which they do not in dumb mode.
     */
    fun compute(file: CSharpFile): List<Pair<TextRange, TextAttributesKey>> {
        val out = ArrayList<Pair<TextRange, TextAttributesKey>>()
        if (file.compilationUnit != null) for (range in NativeCSharpInactiveCode.ranges(file)) out += range to CSharpColors.INACTIVE_BRANCH
        when {
            NativeCSharpSemanticColors.serves(file) -> out += NativeCSharpSemanticColors.colors(file)
            RoslynServerStatus.colorsIdentifiers(file.project, file.virtualFile) -> {}
            else -> for ((range, kind) in CSharpIdentifierClassifier.classify(file.viewProvider.contents)) out += range to CSharpIdentifierAnnotator.keyFor(kind)
        }
        for ((range, second) in CSharpFormatItems.items(file.viewProvider.contents)) {
            out += range to if (second) CSharpSyntaxHighlighter.FORMAT_ITEM_2 else CSharpSyntaxHighlighter.FORMAT_ITEM
        }
        return out.sortedBy { it.first.startOffset }
    }

    /** A key the annotators of C# color with: the palette's `CSHARP_*`, which the daemon's other highlights (problems, usages) never use. */
    fun isColorKey(key: TextAttributesKey?): Boolean = key != null && key.externalName.startsWith("CSHARP_")

    /** The colors the daemon shows in [document] now: its highlights of [isColorKey] at INFORMATION, in the order of the text. */
    fun daemonColors(document: Document, project: Project): List<Pair<TextRange, TextAttributesKey>> {
        val model = DocumentMarkupModel.forDocument(document, project, false) ?: return emptyList()
        val out = ArrayList<Pair<TextRange, TextAttributesKey>>()
        for (highlighter in model.allHighlighters) {
            if (!highlighter.isValid) continue
            val key = highlighter.textAttributesKey
            if (!isColorKey(key)) continue
            val info = HighlightInfo.fromRangeHighlighter(highlighter)
            // the platform's restored highlighters (its markup cache) carry no HighlightInfo: the same colors all the same
            if (info != null && info.severity != HighlightSeverity.INFORMATION) continue
            out += highlighter.textRange to key!!
        }
        return out.sortedWith(compareBy({ it.first.startOffset }, { it.first.endOffset }))
    }

    // ---- snapshots

    fun remember(path: String, text: CharSequence, colors: List<Pair<TextRange, TextAttributesKey>>) {
        val snapshot = Snapshot(hash(text), text.length, IntArray(colors.size) { colors[it].first.startOffset }, IntArray(colors.size) { colors[it].first.endOffset },
            Array(colors.size) { colors[it].second })
        synchronized(snapshots) { snapshots[path] = snapshot }
    }

    /** The colors remembered for [path] if they were taken from this very [text]. */
    fun remembered(path: String, text: CharSequence): List<Pair<TextRange, TextAttributesKey>>? {
        val snapshot = synchronized(snapshots) { snapshots[path] } ?: return null
        return if (snapshot.matches(text)) snapshot.colors else null
    }

    @TestOnly
    fun forgetAll() = synchronized(snapshots) { snapshots.clear() }

    // ---- the layer of an editor

    /** The highlighters this layer has put on [editor] (empty when none or when they are gone). */
    fun layer(editor: Editor): List<RangeHighlighter> = editor.getUserData(LAYER).orEmpty()

    fun apply(editor: Editor, colors: List<Pair<TextRange, TextAttributesKey>>) {
        clear(editor)
        val length = editor.document.textLength
        val markup = editor.markupModel
        val added = ArrayList<RangeHighlighter>(colors.size)
        for ((range, key) in colors) {
            if (range.endOffset > length || range.isEmpty) continue
            added += markup.addRangeHighlighter(key, range.startOffset, range.endOffset, HighlighterLayer.ADDITIONAL_SYNTAX, HighlighterTargetArea.EXACT_RANGE)
        }
        editor.putUserData(LAYER, added)
    }

    fun clear(editor: Editor) {
        val layer = editor.getUserData(LAYER) ?: return
        editor.putUserData(LAYER, null)
        if (editor.isDisposed) return
        for (highlighter in layer) if (highlighter.isValid) editor.markupModel.removeHighlighter(highlighter)
    }

    /** Lets the listeners act in unit tests (they keep away from the editors of other tests otherwise). */
    @TestOnly
    fun enableInTests(disposable: com.intellij.openapi.Disposable) {
        enabledInTests = true
        com.intellij.openapi.util.Disposer.register(disposable) { enabledInTests = false }
    }

    private fun acts(): Boolean = !ApplicationManager.getApplication().isUnitTestMode || enabledInTests

    /** A main editor of a C# file of an open project: the file, or null. */
    private fun csharpFile(editor: Editor, project: Project?): com.intellij.openapi.vfs.VirtualFile? {
        if (project == null || project.isDisposed) return null
        // the editors of tests are untyped
        if (editor.editorKind != EditorKind.MAIN_EDITOR && !(enabledInTests && editor.editorKind == EditorKind.UNTYPED)) return null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        return file.takeIf { it.fileType == CSharpFileType }
    }

    /** As the editor of a C# file is created: the remembered colors at once, or the computed ones as soon as they are there. */
    class Opener : EditorFactoryListener {
        override fun editorCreated(event: EditorFactoryEvent) {
            if (!acts()) return
            val editor = event.editor
            val project = editor.project
            val file = csharpFile(editor, project) ?: return
            if (ApplicationManager.getApplication().isDispatchThread) open(editor, project!!, file)
            else ApplicationManager.getApplication().invokeLater({ if (!editor.isDisposed) open(editor, project!!, file) }, ModalityState.any())
        }

        override fun editorReleased(event: EditorFactoryEvent) {
            if (!acts()) return
            val editor = event.editor
            val project = editor.project
            val file = csharpFile(editor, project) ?: return
            clear(editor)
            // the daemon's colors of the text as it is now: what this editor showed, taken for the next time the file opens
            val document = editor.document
            if (editor.getUserData(COMPLETE_AT) != document.modificationStamp) return
            val colors = daemonColors(document, project!!)
            if (colors.isNotEmpty()) remember(file.path, document.immutableCharSequence, colors)
        }

        private fun open(editor: Editor, project: Project, file: com.intellij.openapi.vfs.VirtualFile) {
            val document = editor.document
            // still colored by the daemon (the document outlived its editor) or by the platform's markup cache
            if (daemonColors(document, project).isNotEmpty()) return
            remembered(file.path, document.immutableCharSequence)?.let { apply(editor, it); return }
            paint(editor, project, file, replacing = false)
        }
    }

    /**
     * Computes the colors of [file] in the background and puts them on [editor], unless the daemon has come first. [replacing]: the layer
     * there was computed while the IDE indexed (the plain colors of [CSharpIdentifierAnnotator]) and gives way to the ones from the stubs.
     */
    private fun paint(editor: Editor, project: Project, file: com.intellij.openapi.vfs.VirtualFile, replacing: Boolean) {
        val document = editor.document
        val stamp = document.modificationStamp
        ReadAction.nonBlocking<Pair<List<Pair<TextRange, TextAttributesKey>>, Boolean>?> {
            val psi = PsiManager.getInstance(project).findFile(file) as? CSharpFile ?: return@nonBlocking null
            if (!PsiDocumentManager.getInstance(project).isCommitted(document)) return@nonBlocking null
            val dumb = DumbService.isDumb(project)
            try { compute(psi) to dumb } catch (_: IndexNotReadyException) { null }
        }
            .expireWhen { editor.isDisposed || project.isDisposed || document.modificationStamp != stamp }
            .coalesceBy(editor, CSharpOpeningColors)
            .finishOnUiThread(ModalityState.any()) { result ->
                val (colors, dumb) = result ?: return@finishOnUiThread
                // the daemon came first: its colors are on screen already (and the layer is gone)
                if (replacing) { if (layer(editor).isEmpty()) return@finishOnUiThread }
                else if (editor.getUserData(COMPLETE_AT) != null || daemonColors(document, project).isNotEmpty()) return@finishOnUiThread
                apply(editor, colors)
                editor.putUserData(PROVISIONAL, if (dumb) true else null)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** The indexes are ready: a layer computed without them (a project that opens) gets the colors from the stubs before the daemon's pass. */
    class Smart(private val project: Project) : DumbService.DumbModeListener {
        override fun exitDumbMode() {
            if (!acts()) return
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                for (editor in com.intellij.openapi.editor.EditorFactory.getInstance().allEditors) {
                    if (editor.project != project || editor.getUserData(PROVISIONAL) != true || layer(editor).isEmpty()) continue
                    val file = csharpFile(editor, project) ?: continue
                    paint(editor, project, file, replacing = true)
                }
            }, ModalityState.any())
        }
    }

    /** The daemon has finished with editors: their colors are its own now, the layer goes; the text is marked as completely colored. */
    class Daemon : DaemonCodeAnalyzer.DaemonListener {
        override fun daemonFinished(fileEditors: Collection<FileEditor>) {
            if (!acts()) return
            for (fileEditor in fileEditors) {
                val editor = (fileEditor as? TextEditor)?.editor ?: continue
                if (editor.document.let { FileDocumentManager.getInstance().getFile(it)?.fileType } != CSharpFileType) continue
                editor.putUserData(COMPLETE_AT, editor.document.modificationStamp)
                clear(editor)
            }
        }
    }
}
