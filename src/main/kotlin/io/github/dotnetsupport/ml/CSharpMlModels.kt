package io.github.dotnetsupport.ml

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import io.github.completionml.core.bpe.BpeTokenizer
import io.github.completionml.core.ngram.NgramModel
import io.github.completionml.core.nn.NnCompletion
import io.github.completionml.core.nn.NnFormat
import io.github.completionml.core.nn.NnModel
import io.github.completionml.core.nn.NnSession
import io.github.completionml.core.nn.native.NativeLib
import io.github.completionml.core.rank.FeatureExtractor
import io.github.completionml.core.rank.LinearRanker
import java.io.File
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The trained models of the C# plugin (ML_INLINE_TASK.md, ML_RANKER_EXPORT_TASK.md §5), either bundled under `ml/csharp/` (a build with
 * `-PmlEnabled=true`) or taken from the directory of [CSharpMlSettings.modelDirectory] (the file names of `ml-models/csharp`):
 *  - the ranker pair — the n-gram language model [LM] and the linear ranker [RANKER] of [CSharpMlCompletionRanker]; loaded once, in the
 *    background, on the first completion (or by [CSharpMlPreloadActivity] when a project with C# files opens); until then the ranker abstains;
 *  - the network — the transformer [NN_MODEL] (or [NN_BIG_MODEL] with [CSharpMlSettings.bigModel]) and its vocabulary [NN_VOCAB] of the grey
 *    text ([CSharpNnInlineCompletionProvider]). One [NnModel] per application (~100 MB, the int8 weights memory-mapped from a copy of the
 *    resource in the system directory, keyed by SHA-256), loaded and warmed up on the first C# editor or at project open. The model is not
 *    reentrant, so everything that touches it — loading, `complete`, the prefill of an opened file, closing sessions — runs on one daemon
 *    thread of this service; the KV-cache sessions are kept per editor on that thread and freed when the editor closes.
 */
@Service(Service.Level.APP)
class CSharpMlModels : CSharpNnEngine, Disposable {
    /** Everything the ranker needs, built once per model set. */
    class Loaded(val lm: NgramModel, val ranker: LinearRanker, val source: String) {
        val extractor = FeatureExtractor(CSharpMlFeatures.schema, lm.vocab, lm, CACHE_LAMBDA)
        val description: String get() = "$source: ${lm.vocab.size} words, ${ranker.schema.size} weights"
    }

    /** The loaded network. The gates are applied per call ([complete]: the main one, after a dot, on an empty line), not by [completion]'s options. */
    class Nn(val completion: NnCompletion, val model: NnModel, val name: String)

    private sealed class State {
        object Idle : State()
        object Loading : State()
        class Ready(val loaded: Loaded?, val key: String, val error: String?) : State()
    }

    private sealed class NnState {
        object Idle : NnState()
        object Loading : NnState()
        class Ready(val nn: Nn?, val key: String, val error: String?) : NnState()
    }

    private val state = AtomicReference<State>(State.Idle)

    // --- network state: [nnState] and [generation] under the lock; [current] and [sessions] only on [thread]
    private var nnState: NnState = NnState.Idle
    private var generation = 0
    private var current: Nn? = null
    private val sessions = HashMap<Any, NnSession>()
    /** The model's thread: low priority for the background work, normal while a completion waits ([NnThread]). */
    private val thread = NnThread("C# NN completion")
    private val shown = AtomicInteger()
    private val accepted = AtomicInteger()
    /** Editors whose prefill is queued and not yet done: a second request for the same editor is dropped, a completion in between cancels it. */
    private val prefillQueued = HashSet<Any>()

    /** Suggestions of the network shown since the start of the IDE (its own policy said `show`). */
    val shownCount: Int get() = shown.get()
    /** Suggestions of the network accepted with Tab since the start of the IDE. */
    val acceptedCount: Int get() = accepted.get()
    /** Sessions alive (tests: no leak after the editors close). */
    val sessionCount: Int get() = thread.submit(Callable { sessions.size }).get()

    /** The ranker models for [modelDirectory] (empty: bundled), or null while they load or when there are none. Never blocks. */
    fun get(modelDirectory: String): Loaded? {
        val key = modelDirectory.trim()
        when (val s = state.get()) {
            is State.Ready -> if (s.key == key) return s.loaded
            State.Loading -> return null
            State.Idle -> {}
        }
        if (state.compareAndSet(state.get().takeUnless { it === State.Loading } ?: return null, State.Loading)) {
            ApplicationManager.getApplication().executeOnPooledThread { state.set(load(key)) }
        }
        return null
    }

    /**
     * The network for [modelDirectory] (empty: bundled), or null while it loads, when there is none or when the grey text is off. The first
     * call starts loading on the network's thread; never blocks, so it may be called on the EDT (the editor listener warms up this way).
     */
    fun nn(modelDirectory: String): Nn? {
        val settings = CSharpMlSettings.getInstance()
        if (!settings.inlineEnabled) return null
        val key = nnKey(modelDirectory, settings.bigModel)
        val g = synchronized(this) {
            when (val s = nnState) {
                is NnState.Ready -> if (s.key == key) return s.nn
                NnState.Loading -> return null
                NnState.Idle -> {}
            }
            nnState = NnState.Loading
            generation
        }
        thread.execute {
            closeNn()
            val ready = loadNn(key)
            val stale = synchronized(this) { (generation != g).also { if (!it) { nnState = ready; current = ready.nn } } }
            if (stale) ready.nn?.model?.close()
        }
        return null
    }

    /** Starts loading whatever the settings ask for (the ranker pair, the network) without waiting: the preload at project open. */
    fun preload() {
        val settings = CSharpMlSettings.getInstance()
        if (settings.rankerEnabled) get(settings.modelDirectory)
        nn(settings.modelDirectory)
    }

    override suspend fun complete(editor: Any, context: CSharpNnInline.Context): CSharpNnInline.Answer? {
        val settings = CSharpMlSettings.getInstance()
        nn(settings.modelDirectory) ?: return null
        return thread.complete {
            prefillQueued.remove(editor)
            // a reset between the check and this task closed the model: [current] is null then
            val nn = current ?: return@complete null
            val dot = CSharpNnInline.afterDot(context.before)
            val blank = CSharpNnInline.blankLine(context.before)
            val gate = when { blank -> settings.inlineEmptyLineThreshold; dot -> settings.inlineDotThreshold; else -> settings.inlineThreshold }
            val completion = nn.completion
            val session = sessions.getOrPut(editor) { nn.model.newSession(SESSION_CAPACITY) }
            val started = System.nanoTime()
            val r = completion.complete(context.path, context.before, context.after, session)
            // the engine's show rule with the gate of this call (its options carry none that matters): a sound answer above the gate
            val sound = r.text.isNotEmpty() && !r.repeated && !r.healMiss && !(r.punctOnly && !settings.inlineShowClosers)
            // the gate over the code tokens only: a guessed string literal does not hide a certain line (CSharpNnInline.codeConfidence)
            val code = if (settings.inlineGuessStrings && sound && r.confProd < gate)
                CSharpNnInline.codeConfidence(CSharpNnInline.lineBefore(context.before, r.typed.size), r.tokens.map { completion.tok.tokenBytes(it) }, r.logProbs, r.stopLogProb)
            else r.confProd
            var show = sound && (r.confProd >= gate || code >= gate)
            var text = r.textString
            // the whole line is not certain: its certain start may be
            if (!show && sound && r.tokens.isNotEmpty()) {
                val prefix = CSharpNnInline.certainPrefix(r.tokens.map { completion.tok.tokenBytes(it) }, r.logProbs, r.typed.size, gate)
                if (prefix != null) { text = String(prefix, Charsets.UTF_8); show = true }
            }
            if (show) shown.incrementAndGet()
            val line = "NN completion ${(System.nanoTime() - started) / 1_000_000} ms, confProd ${"%.3f".format(r.confProd)}, code ${"%.3f".format(code)}, " +
                "gate $gate${if (dot) " (dot)" else if (blank) " (empty line)" else ""}, show $show: $text${if (text != r.textString) " (of: ${r.textString})" else ""}"
            if (settings.inlineDebugLog) debugSink?.invoke(line) ?: LOG.info(line) else LOG.debug(line)
            CSharpNnInline.Answer(text, show, maxOf(r.confProd, code))
        }
    }

    /**
     * Fills the KV cache of [editor]'s session with the prompt at [context] in the background (the file just opened, the caret where it
     * is), so the first grey-text request there reuses it instead of paying the cold prefill. Skipped while the network is not loaded;
     * queued at most once per editor; a completion request in between takes precedence (same thread, the queue is FIFO — the prefill
     * then only shortens that request). Never blocks the caller.
     */
    fun prefill(editor: Any, context: CSharpNnInline.Context) {
        if (thread.isShutdown) return
        val nn = synchronized(this) { (nnState as? NnState.Ready)?.nn } ?: return
        thread.execute {
            if (!prefillQueued.add(editor)) return@execute
            try {
                if (current !== nn || editor !in sessions && sessions.size >= MAX_PREFILL_SESSIONS) return@execute
                val session = sessions.getOrPut(editor) { nn.model.newSession(SESSION_CAPACITY) }
                val completion = nn.completion
                val started = System.nanoTime()
                val boundary = completion.healedBoundary(context.before, context.after)
                session.prefill(completion.buildPrompt(context.path, context.before, boundary, context.after))
                if (LOG.isDebugEnabled) LOG.debug("NN prefill of ${String(context.path)} in ${(System.nanoTime() - started) / 1_000_000} ms")
            } catch (e: Throwable) {
                LOG.warn("NN prefill failed", e)
            } finally { prefillQueued.remove(editor) }
        }
    }

    override fun accepted() { accepted.incrementAndGet() }

    /** Frees the KV cache of a closed editor (native memory). */
    fun release(editor: Any) { if (!thread.isShutdown) thread.execute { prefillQueued.remove(editor); sessions.remove(editor)?.close() } }

    /** Status of the ranker for the settings page: what is loaded, or why nothing is. */
    fun status(modelDirectory: String): String = when (val s = state.get()) {
        State.Idle -> when {
            !CSharpMlSettings.getInstance().rankerEnabled -> "off"
            isRankerBundled || modelDirectory.isNotBlank() -> "not loaded yet (the first completion loads them)"
            else -> "no bundled models"
        }
        State.Loading -> "loading…"
        is State.Ready -> s.loaded?.description ?: (s.error ?: "no models")
    }

    /** Status of the inline (grey text) network for the settings page: model name and kernels, or why nothing is loaded. */
    fun nnStatus(modelDirectory: String): String = when (val s = synchronized(this) { nnState }) {
        NnState.Idle -> when {
            !CSharpMlSettings.getInstance().inlineEnabled -> "off"
            isNnBundled || modelDirectory.isNotBlank() -> "not loaded yet (the first C# editor loads it)"
            else -> "no bundled network"
        }
        NnState.Loading -> "loading…"
        is NnState.Ready -> s.nn?.let { "model ${it.name}, ${it.model.nThreads} threads, kernels: ${NativeLib.status}; shown ${shown.get()}, accepted ${accepted.get()}" }
            ?: (s.error ?: "no network")
    }

    /** Forgets the loaded models so that the next completion reads them again (after the settings changed). */
    fun reset() {
        state.set(State.Idle)
        synchronized(this) { generation++; nnState = NnState.Idle }
        thread.execute { closeNn() }
    }

    override fun dispose() {
        synchronized(this) { generation++; nnState = NnState.Idle }
        thread.execute { closeNn() }
        thread.shutdown()
    }

    /** On [thread]: closes the sessions and the model. */
    private fun closeNn() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        prefillQueued.clear()
        current?.model?.close()
        current = null
    }

    private fun load(key: String): State.Ready {
        val started = System.currentTimeMillis()
        return try {
            val loaded = if (key.isEmpty()) loadBundled() else loadDirectory(File(key))
            if (loaded != null) {
                check(loaded.ranker.schema.names == CSharpMlFeatures.schema.names) { "$RANKER was trained for another feature set; retrain with this plugin's export" }
                LOG.info("ML completion models: ${loaded.description} in ${System.currentTimeMillis() - started} ms")
            }
            State.Ready(loaded, key, if (loaded == null) "no models in ${key.ifEmpty { "the plugin" }}" else null)
        } catch (e: Exception) {
            LOG.warn("ML completion models could not be loaded", e)
            State.Ready(null, key, e.message ?: e.toString())
        }
    }

    private fun loadBundled(): Loaded? {
        val cl = CSharpMlModels::class.java.classLoader
        val lm = cl.getResourceAsStream("$RESOURCE_DIR/$LM") ?: return null
        val rank = cl.getResourceAsStream("$RESOURCE_DIR/$RANKER") ?: return null
        return Loaded(lm.use { NgramModel.read(it, "bundled $LM") }, rank.use { LinearRanker.read(it, "bundled $RANKER") }, "bundled")
    }

    private fun loadDirectory(dir: File): Loaded? {
        val lm = File(dir, LM); val rank = File(dir, RANKER)
        if (!lm.isFile || !rank.isFile) return null
        return Loaded(NgramModel.read(lm), LinearRanker.read(rank), dir.path)
    }

    /** On [thread]: reads, builds and warms up the network. */
    private fun loadNn(key: String): NnState.Ready = try {
        val big = key.endsWith(BIG_SUFFIX)
        val dir = key.removeSuffix(BIG_SUFFIX).takeIf { it.isNotEmpty() }?.let(::File)
        val nn = loadNn(dir, big)
        NnState.Ready(nn, key, if (nn == null) "no network in ${dir ?: "the plugin"}" else null)
    } catch (e: Throwable) {
        LOG.warn("ML inline completion network could not be loaded", e)
        NnState.Ready(null, key, e.message ?: e.toString())
    }

    companion object {
        private val LOG = logger<CSharpMlModels>()
        /** Weight of the per-file cache in the mixed language model; the value the models were trained with. */
        const val CACHE_LAMBDA = 0.3
        private const val RESOURCE_DIR = "ml/csharp"
        /** The files of `ml-models/csharp` (bundled by the ML build, or in the directory of the settings). */
        const val LM = "e15-a.cml"
        const val RANKER = "e18-rank.cml"
        const val NN_MODEL = "cs31m-e2-lr2e3.cml"
        const val NN_BIG_MODEL = "cs50m-e3-lr2e3.cml"
        const val NN_VOCAB = "cs-16384.bpe"
        /** KV cache of an editor's session: the prompt (≤ 2000 tokens) and the generated line; capped by the model's context. */
        private const val SESSION_CAPACITY = 2048
        /** A prefill opens no session beyond this many editors (each holds a KV cache); a completion always gets one. */
        private const val MAX_PREFILL_SESSIONS = 16
        private const val BIG_SUFFIX = "|big"
        private const val WARM_UP = "using System;\n\nnamespace Demo\n{\n    public class Program\n    {\n        public static void Main()\n        {\n            Console.Wri"

        fun getInstance(): CSharpMlModels = service()

        private fun nnKey(modelDirectory: String, big: Boolean) = modelDirectory.trim() + if (big) BIG_SUFFIX else ""

        private fun resource(name: String) = CSharpMlModels::class.java.classLoader.getResource("$RESOURCE_DIR/$name")
        /** True when the build carries the ranker pair. */
        val isRankerBundled: Boolean by lazy { resource(LM) != null && resource(RANKER) != null }
        /** Where the debug lines go with [CSharpMlSettings.inlineDebugLog] on: the plugin log (set by [CSharpMlLogBridge]), else idea.log at INFO. */
        @Volatile var debugSink: ((String) -> Unit)? = null

        /** True when the build carries the grey-text network. */
        val isNnBundled: Boolean by lazy { resource(NN_MODEL) != null && resource(NN_VOCAB) != null }
        /** True when the build carries the big network too. */
        val isBigBundled: Boolean by lazy { resource(NN_BIG_MODEL) != null }
        /** True in a build that carries any of the models (and therefore shows the ML completion settings page). */
        val isBundled: Boolean by lazy { isRankerBundled || isNnBundled }

        /**
         * Builds and warms up the network from [dir] (null: bundled): [NN_BIG_MODEL] when [big] and it exists, else [NN_MODEL], with
         * [NN_VOCAB]. A directory without the network files falls back to the bundled network. Null when there is none at all. Blocking (a second).
         */
        fun loadNn(dir: File?, big: Boolean = false, options: NnCompletion.Options = NnCompletion.Options()): Nn? {
            val started = System.currentTimeMillis()
            val (modelFile, vocab) = dir?.let { directoryNn(it, big) } ?: bundledNn(big) ?: return null
            val model = NnModel(NnFormat.read(modelFile), nThreads = minOf(8, Runtime.getRuntime().availableProcessors()))
            val nn = try {
                Nn(NnCompletion(model, vocab, options), model, modelFile.name.removeSuffix(".cml"))
            } catch (e: Throwable) { model.close(); throw e }
            val loadedMillis = System.currentTimeMillis() - started
            model.newSession(SESSION_CAPACITY).use { nn.completion.complete("Program.cs".toByteArray(), WARM_UP.toByteArray(), "\n        }\n    }\n}\n".toByteArray(), it) }
            LOG.info("ML inline completion: ${nn.name} (${dir ?: "bundled"}) loaded in $loadedMillis ms, warmed up in ${System.currentTimeMillis() - started - loadedMillis} ms, " +
                "${model.nThreads} threads, kernels: ${NativeLib.status}")
            return nn
        }

        private fun directoryNn(dir: File, big: Boolean): Pair<File, BpeTokenizer>? {
            val model = (if (big) File(dir, NN_BIG_MODEL).takeIf { it.isFile } else null) ?: File(dir, NN_MODEL).takeIf { it.isFile } ?: return null
            val vocab = File(dir, NN_VOCAB).takeIf { it.isFile } ?: return null
            return model to BpeTokenizer.load(vocab.toPath())
        }

        private fun bundledNn(big: Boolean): Pair<File, BpeTokenizer>? {
            val cl = CSharpMlModels::class.java.classLoader
            val vocab = cl.getResourceAsStream("$RESOURCE_DIR/$NN_VOCAB")?.use { BpeTokenizer.load(it) } ?: return null
            val model = (if (big) extract("$RESOURCE_DIR/$NN_BIG_MODEL") else null) ?: extract("$RESOURCE_DIR/$NN_MODEL") ?: return null
            return model to vocab
        }

        /**
         * Copies a bundled resource to `<system>/dotnet-support/ml/<sha256>/` for the memory mapping of [NnFormat.read]. The directories are
         * the user's only (0700 where POSIX permissions exist); an existing copy is reused only when its SHA-256 equals the resource's,
         * otherwise it is written again — to a temp name in the same directory, then an atomic move (another IDE of the same system
         * directory may be extracting it too; on Windows a mapped file is locked, then the copy must already be right).
         */
        private fun extract(resource: String): File? {
            val cl = CSharpMlModels::class.java.classLoader
            val sha = cl.getResourceAsStream(resource)?.use(::sha256) ?: return null
            val dir = privateDirectories(Path.of(PathManager.getSystemPath(), "dotnet-support", "ml", sha))
            val out = dir.resolve(resource.substringAfterLast('/'))
            if (Files.isRegularFile(out) && Files.newInputStream(out).use(::sha256) == sha) return out.toFile()
            val tmp = Files.createTempFile(dir, "model", ".tmp")
            try {
                cl.getResourceAsStream(resource)!!.use { s -> Files.newOutputStream(tmp).use { s.copyTo(it, 1 shl 16) } }
                check(Files.newInputStream(tmp).use(::sha256) == sha) { "$resource changed while it was extracted" }
                try { Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (e: Exception) { if (!(Files.isRegularFile(out) && Files.newInputStream(out).use(::sha256) == sha)) throw IllegalStateException("cannot create $out", e) }
            } finally { Files.deleteIfExists(tmp) }
            return out.toFile()
        }

        private fun sha256(stream: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            while (true) { val n = stream.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Creates [dir] and its missing parents with 0700 where the file system has POSIX permissions (not on Windows). */
        private fun privateDirectories(dir: Path): Path {
            val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
            if (!posix) return Files.createDirectories(dir)
            val missing = generateSequence(dir) { it.parent }.takeWhile { !Files.exists(it) }.toList().asReversed()
            for (d in missing) try {
                Files.createDirectory(d, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } catch (_: FileAlreadyExistsException) {}
            return dir
        }
    }
}
