package io.github.dotnetsupport.ml

import com.intellij.codeInsight.inline.completion.DefaultInlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.lang.CSharpFileType

/**
 * Grey text to the end of the line from our transformer ([CSharpMlModels], `NnCompletion` of the engine) while typing in a C# file, while
 * the completion popup is open, or on an explicit call; Tab accepts it. Only what passes the gate ([CSharpMlSettings.inlineThreshold],
 * the lower ones after a dot and on an empty line; no lone closers unless [CSharpMlSettings.inlineShowClosers]), otherwise nothing.
 *
 * Registered `order="first"` like the Go plugin's: the platform asks only the first enabled provider, so this one asks the next enabled
 * provider first (a host's own grey text, if any) and answers where it has nothing; the insert handler of whoever answered applies.
 */
class CSharpNnInlineCompletionProvider internal constructor(private val engine: () -> CSharpNnEngine) : InlineCompletionProvider {
    constructor() : this({ CSharpMlModels.getInstance() })

    override val id: InlineCompletionProviderID get() = InlineCompletionProviderID("io.github.dotnetsupport.nn")

    /** The next enabled provider for an event, found in [isEnabled] (on the platform's thread, where they may look at the editor). */
    @Volatile private var next: Pair<InlineCompletionEvent, InlineCompletionProvider>? = null
    /** Who answered the last request when it was not the network, for [insertHandler]. */
    @Volatile private var answered: InlineCompletionProvider? = null

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        // lookup events too: while the completion list is open the platform hides the grey text of a typing event
        if (event !is InlineCompletionEvent.DocumentChange && event !is InlineCompletionEvent.DirectCall && event !is InlineCompletionEvent.InlineLookupEvent) return false
        if (!CSharpMlSettings.getInstance().inlineEnabled || event.toRequest()?.file?.fileType != CSharpFileType) return false
        val providers = InlineCompletionProvider.EP_NAME.extensionList
        next = providers.indexOfFirst { it === this }.takeIf { it >= 0 }
            ?.let { i -> providers.subList(i + 1, providers.size).firstOrNull { it.isEnabled(event) } }?.let { event to it }
        return true
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        next?.takeIf { it.first === request.event }?.second?.let { provider ->
            val suggestion = provider.getSuggestion(request)
            if (suggestion !== InlineCompletionSuggestion.Empty) { answered = provider; return suggestion }
        }
        answered = null
        val context = readAction { CSharpNnInline.context(request.document.immutableCharSequence, request.endOffset, path(request.file)) }
        return CSharpNnInline.suggestion(CSharpNnInline.text(engine().complete(request.editor, context), context.after))
    }

    override val insertHandler: InlineCompletionInsertHandler = object : InlineCompletionInsertHandler {
        override fun afterInsertion(environment: InlineCompletionInsertEnvironment, elements: List<InlineCompletionElement>) {
            val other = answered
            if (other != null) return other.insertHandler.afterInsertion(environment, elements)
            DefaultInlineCompletionInsertHandler.INSTANCE.afterInsertion(environment, elements)
            engine().accepted()
        }
    }

    companion object {
        fun path(file: PsiFile): String =
            CSharpNnInline.relativePath(file.project.basePath, file.virtualFile?.path ?: file.originalFile.virtualFile?.path ?: file.name)
    }
}

/**
 * Loads and warms up the network when the first C# editor opens (the first call costs up to a second) and prefills the KV cache of every
 * C# editor opened with the file's text at its caret, in the background on the model's thread; frees an editor's KV cache when it closes.
 */
class CSharpNnEditorListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val settings = CSharpMlSettings.getInstance()
        if (!settings.inlineEnabled || !CSharpMlModels.isNnBundled && settings.modelDirectory.isBlank()) return
        val editor = event.editor
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (file.fileType != CSharpFileType) return
        val models = CSharpMlModels.getInstance()
        if (models.nn(settings.modelDirectory) == null) return   // loading now (or nothing to load): the first keystroke pays the prefill
        val project = editor.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread { prefill(models, editor, project, file.path) }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        ApplicationManager.getApplication().serviceIfCreated<CSharpMlModels>()?.release(event.editor)
    }

    private fun prefill(models: CSharpMlModels, editor: Editor, project: Project, filePath: String) {
        if (editor.isDisposed || project.isDisposed) return
        val context = ReadAction.compute<CSharpNnInline.Context?, RuntimeException> {
            if (editor.isDisposed) null
            else CSharpNnInline.context(editor.document.immutableCharSequence, editor.caretModel.offset, CSharpNnInline.relativePath(project.basePath, filePath))
        } ?: return
        models.prefill(editor, context)
    }
}

/**
 * Loads the models when a project with C# files opens (in the background, after indexing: a cheap look at the file-type index), so the
 * first completion in the project does not wait for the ~1 s of loading and warm-up. A project without `.cs` files pays nothing; the
 * application start loads nothing either (the project is unknown then).
 */
class CSharpMlPreloadActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = CSharpMlSettings.getInstance()
        if (!settings.inlineEnabled && !settings.rankerEnabled) return
        if (!CSharpMlModels.isBundled && settings.modelDirectory.isBlank()) return
        val hasCSharp = smartReadAction(project) { FileTypeIndex.containsFileOfType(CSharpFileType, GlobalSearchScope.projectScope(project)) }
        if (hasCSharp) CSharpMlModels.getInstance().preload()
    }
}

/** Routes the debug lines of the network (setting "Log every answer of the network") into the plugin log, category `ml`. */
class CSharpMlLogBridge : ProjectActivity {
    override suspend fun execute(project: Project) {
        CSharpMlModels.debugSink = { PluginLog.info("ml", it) }
    }
}
