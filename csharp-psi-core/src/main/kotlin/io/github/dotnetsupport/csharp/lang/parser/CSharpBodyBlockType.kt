// Reparseable bodies: the element type of member, accessor, local function and anonymous function bodies, which
// carries the parse context of Roslyn's `ParseMethodOrAccessorBodyBlock` (LanguageParser.cs 9122), `ParseLambdaBody`
// (13963) and `ParseAnonymousMethodExpression` (13795) at roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c, so
// that a body can be reparsed alone. Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
// Design and the proof obligations: docs/csharp-psi/GRAMMAR.md, "Reparseable bodies".
package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.PsiBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LazyParseableElement
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.tree.ICompositeElementType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IReparseableElementType
import com.intellij.psi.tree.TokenSet
import io.github.dotnetsupport.csharp.lang.CSharpLanguage
import io.github.dotnetsupport.csharp.lang.CSharpLanguageLevel
import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion
import io.github.dotnetsupport.csharp.lang.CSharpParserDefinition
import io.github.dotnetsupport.csharp.lang.CSharpPreprocessorSymbols
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexer
import io.github.dotnetsupport.csharp.lang.lexer.CSharpTokenTypes
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The parse context of a body, packed into a `Long` ([key]): everything the parse of a block reads from the parser
 * state it starts in (the audit is in docs/csharp-psi/GRAMMAR.md, "Reparseable bodies"), plus two facts of the body's own parse
 * that an enclosing decision may observe.
 *
 * Inputs: [termState] (Roslyn `_termState`: the terminator predicates of the enclosing constructs stay active inside
 * a body, e.g. `IsPossibleMemberStartOrStop` ends a method body early at `public`), [isInAsync], [isInQuery],
 * [isInFieldKeywordContext], [forceConditionalAccessExpression], and [depth], the `recursionDepth` the body starts at
 * (exact: a body reparsed alone must give the bodies nested in it the types the full parse gives them, and those
 * carry their own start depths).
 *
 * [errorSensitive] marks local function and anonymous function bodies: enclosing decisions read whether a range
 * containing them has errors (`ParseStatementCoreRest`'s `await` retry, `looksLikeVariableInitializer`, the clean
 * parameter of `ParseParameterList`, `type.ContainsDiagnostics` of types with array ranks) and the conditional's
 * `ContainsTernaryCollectionToReinterpret` walks into them, so for them [hadErrors], [hadErrorDiagnostic] (errors
 * outside skipped tokens, `ContainsErrorDiagnostic`: `looksLikeVariableInitializer`) and [hadTernaryCollection] (the
 * results of their parse) are part of the key and must be reproduced by a reparse. Member and accessor bodies are never
 * inside such a range, and their keys keep both bits clear.
 */
@JvmInline
value class BodyContext(val key: Long) {
    val termState: Int get() = (key and TERM_MASK).toInt()
    val isInAsync: Boolean get() = key and ASYNC != 0L
    val isInQuery: Boolean get() = key and QUERY != 0L
    val isInFieldKeywordContext: Boolean get() = key and FIELD != 0L
    val forceConditionalAccessExpression: Boolean get() = key and FORCE != 0L
    val errorSensitive: Boolean get() = key and SENSITIVE != 0L
    val hadErrors: Boolean get() = key and ERRORS != 0L
    val hadTernaryCollection: Boolean get() = key and TERNARY != 0L
    val hadErrorDiagnostic: Boolean get() = key and ERROR_DIAGNOSTIC != 0L
    val depth: Int get() = ((key ushr DEPTH_SHIFT) and DEPTH_MASK).toInt()

    /** The same inputs with the result bits of a parse. */
    fun withResults(hadErrors: Boolean, hadErrorDiagnostic: Boolean, hadTernaryCollection: Boolean): BodyContext =
        if (!errorSensitive) this
        else BodyContext(
            (key and (ERRORS or ERROR_DIAGNOSTIC or TERNARY).inv()) or (if (hadErrors) ERRORS else 0L) or
                (if (hadErrorDiagnostic) ERROR_DIAGNOSTIC else 0L) or (if (hadTernaryCollection) TERNARY else 0L),
        )

    /** Puts a fresh [parser] into this context: the one place where the state of a body's start is restored. */
    fun restore(parser: SyntaxParser) {
        parser.termState = termState
        parser.isInAsync = isInAsync
        parser.isInQuery = isInQuery
        parser.isInFieldKeywordContext = isInFieldKeywordContext
        parser.forceConditionalAccessExpression = forceConditionalAccessExpression
        parser.recursionDepth = depth
    }

    override fun toString(): String =
        "BodyContext(term=0x${termState.toString(16)}, async=$isInAsync, query=$isInQuery, field=$isInFieldKeywordContext, " +
            "force=$forceConditionalAccessExpression, depth=$depth, sensitive=$errorSensitive, errors=$hadErrors, errorDiagnostic=$hadErrorDiagnostic, ternary=$hadTernaryCollection)"

    companion object {
        private const val TERM_MASK = 0x1FFFFFFFL
        private const val ASYNC = 1L shl 29
        private const val QUERY = 1L shl 30
        private const val FIELD = 1L shl 31
        private const val FORCE = 1L shl 32
        private const val SENSITIVE = 1L shl 33
        private const val ERRORS = 1L shl 34
        private const val TERNARY = 1L shl 35
        private const val DEPTH_SHIFT = 36
        private const val DEPTH_MASK = 0x3FFL
        private const val ERROR_DIAGNOSTIC = 1L shl 46

        /** The inputs of a body parse starting now. */
        fun capture(parser: SyntaxParser, errorSensitive: Boolean): BodyContext {
            check(parser.termState.toLong() and TERM_MASK.inv() == 0L) { "TerminatorState 0x${parser.termState.toString(16)} does not fit TERM_MASK" }
            val depth = parser.recursionDepth.toLong().coerceIn(0L, DEPTH_MASK)
            return BodyContext(
                (parser.termState.toLong() and TERM_MASK) or
                    (if (parser.isInAsync) ASYNC else 0L) or (if (parser.isInQuery) QUERY else 0L) or
                    (if (parser.isInFieldKeywordContext) FIELD else 0L) or (if (parser.forceConditionalAccessExpression) FORCE else 0L) or
                    (if (errorSensitive) SENSITIVE else 0L) or (depth shl DEPTH_SHIFT),
            )
        }
    }
}

/**
 * File-level inputs of a body parsed alone, taken from the file the body is in (the host of the `DummyHolder` the
 * platform reparses a body in): the preprocessor symbols of the file's lexer ([CSharpPreprocessorSymbols.forFile])
 * and the file's language version ([CSharpLanguageLevel.forFile]), the inputs the full parse gets from
 * `CSharpFileElementType.doParseContents`.
 *
 * Neither is part of the [BodyContext] key of the body's type: the type is shared by bodies of files with different
 * versions, but every parse of a body (full or alone) reads the version of the file it belongs to, so the decisions of
 * a reparse are the full parse's (docs/csharp-psi/GRAMMAR.md, "Reparseable bodies").
 */
class BodyFileSettings(val symbols: Set<String>, val languageVersion: CSharpLanguageVersion) {
    companion object {
        fun of(psi: PsiElement): BodyFileSettings {
            val file = hostFile(psi)
            return BodyFileSettings(CSharpPreprocessorSymbols.forFile(file), CSharpLanguageLevel.forFile(file))
        }

        /** The file [psi] is in; for a `DummyHolder` (a body being reparsed) the file of its context, transitively. */
        fun hostFile(psi: PsiElement): PsiFile? {
            var file: PsiFile? = psi.containingFile
            val seen = HashSet<PsiFile>()
            while (file != null && seen.add(file)) {
                val context = file.context ?: return file
                file = context.containingFile
            }
            return file
        }
    }
}

/** What the parse of a body alone found ([CSharpBodyBlockType.doParseContents]); read by [CSharpBodyBlockType.isValidReparse]. */
class BodyParseOutcome(val valid: Boolean, val reason: String) {
    override fun toString() = if (valid) "valid" else "invalid: $reason"
}

/**
 * The element type of a reparseable body (`Block` to the tree mappings and the gates: its debug name is Roslyn's kind
 * `Block`). One type per [BodyContext], interned; the full parse gives a body this type when the body may be
 * reparsed alone ([LanguageParser.parseBodyBlock]), other blocks are plain [SyntaxKind.Block].
 *
 * Bodies are parsed eagerly with the file (the nodes are already-parsed [LazyParseableElement]s); the platform's
 * `BlockSupportImpl` reparses the innermost body around a change: [isReparseable] (cheap checks of the texts),
 * [createNode] + [doParseContents] (the body alone, in [context]), [isValidReparse] (the parse consumed exactly the new
 * text and reproduced the observable facts of the key). Rejected bodies fall back to the enclosing body, then to a full
 * reparse.
 */
class CSharpBodyBlockType private constructor(val context: BodyContext) :
    IReparseableElementType("Block", CSharpLanguage), ICompositeElementType {

    /** A parsed (not collapsed) node: the full parse builds the body's children itself. */
    override fun createCompositeNode(): ASTNode = LazyParseableElement(this, null)

    /** The chameleon of a reparse: [doParseContents] parses it on first access. */
    override fun createNode(text: CharSequence?): ASTNode = LazyParseableElement(this, text)

    override fun doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode? {
        PARSES.incrementAndGet()
        val settings = BodyFileSettings.of(psi)
        val lexer = CSharpLexer(settings.symbols)
        val builder = CSharpParserDefinition.createBuilder(psi.project, chameleon, lexer)
        builder.putUserData(CSharpLanguageLevel.KEY, settings.languageVersion)
        val outcome = parseAlone(builder, this, settings.languageVersion)
        chameleon.putUserData(OUTCOME, outcome)
        return builder.treeBuilt.firstChildNode
    }

    /**
     * Necessary conditions checked on the texts before parsing: [newText] is `{` ... `}`, and neither the old body nor
     * [newText] contains a preprocessor directive, disabled text or a conflict marker. Without them the new text lexes
     * alone (from the lexer's initial state, empty directive context) into the tokens the file's lexer makes of it, and
     * the file's lexer leaves it in the state it entered it with (docs/csharp-psi/GRAMMAR.md, "Reparseable bodies").
     */
    override fun isReparseable(currentNode: ASTNode, newText: CharSequence, fileLanguage: Language, project: Project): Boolean {
        if (newText.length < 2 || newText[0] != '{' || newText[newText.length - 1] != '}') return false
        if (mayHaveDirectives(currentNode.chars) && hasDirectiveLeaves(currentNode)) return false
        if (mayHaveDirectives(newText) && lexesDirectives(newText)) return false
        return true
    }

    override fun isValidReparse(oldNode: ASTNode, newNode: ASTNode): Boolean {
        newNode.firstChildNode // parses the chameleon
        val outcome = newNode.getUserData(OUTCOME)
        newNode.putUserData(OUTCOME, null)
        val valid = outcome?.valid == true
        if (valid) {
            ACCEPTED.incrementAndGet()
            lastAccepted = WeakReference(oldNode)
        } else {
            REJECTED.incrementAndGet()
        }
        return valid
    }

    override fun toString(): String = "Block"

    companion object {
        /** At most this many contexts get a type of their own; bodies in further contexts are plain blocks. */
        const val MAX_TYPES = 4096

        private val TYPES = ConcurrentHashMap<Long, CSharpBodyBlockType>()
        private val PARSES = AtomicLong()
        private val ACCEPTED = AtomicLong()
        private val REJECTED = AtomicLong()
        @Volatile private var lastAccepted: WeakReference<ASTNode>? = null
        private val OUTCOME = Key.create<BodyParseOutcome>("csharp.bodyParseOutcome")

        /** Token types the lexer produces only for directives, their excluded text and conflict markers. */
        @JvmField val DIRECTIVE_LIKE: TokenSet = TokenSet.orSet(
            CSharpTokenTypes.DIRECTIVE_TOKENS,
            TokenSet.create(SyntaxKind.PreprocessingMessageTrivia, SyntaxKind.DisabledTextTrivia, SyntaxKind.ConflictMarkerTrivia),
        )

        /** The type of bodies parsed in [context], or null when the registry is full. */
        fun forContext(context: BodyContext): CSharpBodyBlockType? {
            TYPES[context.key]?.let { return it }
            if (TYPES.size >= MAX_TYPES) return null
            return TYPES.computeIfAbsent(context.key) { CSharpBodyBlockType(context) }
        }

        /**
         * Whether [type] is Roslyn's `Block`: [SyntaxKind.Block] or a reparseable body ([CSharpBodyBlockType], one
         * type per context, created on demand, so no static `TokenSet` can list them). PSI, formatter and other
         * code that matches blocks by element type must use this instead of `=== SyntaxKind.Block` or a `TokenSet`.
         */
        @JvmStatic
        fun isBlock(type: IElementType?): Boolean = type === SyntaxKind.Block || type is CSharpBodyBlockType

        /** Body types created so far (tests, benchmarks). */
        fun typeCount(): Int = TYPES.size

        /** Bodies parsed alone so far ([doParseContents]); tests. */
        fun parseCount(): Long = PARSES.get()

        /** Reparses accepted / rejected by [isValidReparse] so far; tests and benchmarks. */
        fun acceptedCount(): Long = ACCEPTED.get()
        fun rejectedCount(): Long = REJECTED.get()

        /** The old body node of the last accepted reparse (tests). */
        fun lastAcceptedReparse(): ASTNode? = lastAccepted?.get()

        /** The body in [builder] (its whole text) parsed alone at [version] with the context of [type]; the root marker is [type]. */
        private fun parseAlone(builder: PsiBuilder, type: CSharpBodyBlockType, version: CSharpLanguageVersion): BodyParseOutcome {
            val context = type.context
            var outcome: BodyParseOutcome? = null
            val guarded = SyntaxParser.parseWithStackGuard(builder, builder.mark(), { b -> LanguageParser(b, version) }) { p ->
                context.restore(p)
                val closeEnd = p.parseBodyBlockContents()
                val atEnd = p.currentToken.isEof
                if (!atEnd) p.consumeUnexpectedTokens()
                val results = context.withResults(
                    hadErrors = p.errorCount != 0,
                    hadErrorDiagnostic = p.errorCount != p.skippedErrorCount,
                    hadTernaryCollection = p.ternaryCollectionCount != 0,
                )
                outcome = when {
                    closeEnd < 0 -> BodyParseOutcome(false, "missing brace")
                    !atEnd -> BodyParseOutcome(false, "the block ends before the text")
                    // EOF is also reached when trivia follows the `}`: `} // done` typed before the old `}` makes the
                    // comment swallow it here, while the full parse goes on after it.
                    closeEnd != builder.originalText.length -> BodyParseOutcome(false, "trivia after the closing brace")
                    results != context -> BodyParseOutcome(false, "observable results changed: $results")
                    else -> BodyParseOutcome(true, "")
                }
            }
            guarded.root.done(type)
            return if (guarded.overflowed) BodyParseOutcome(false, "stack overflow") else outcome ?: BodyParseOutcome(false, "no parse")
        }

        /** Quick filter: text that cannot contain a directive or a conflict marker. */
        private fun mayHaveDirectives(text: CharSequence): Boolean {
            for (i in text.indices) {
                when (text[i]) {
                    '#' -> return true
                    '<', '=', '>', '|' -> if (i + 7 <= text.length && (1 until 7).all { text[i + it] == text[i] }) return true
                }
            }
            return false
        }

        private fun hasDirectiveLeaves(node: ASTNode): Boolean {
            var leaf: ASTNode? = TreeUtil.findFirstLeaf(node)
            val last = TreeUtil.findLastLeaf(node)
            while (leaf != null) {
                if (DIRECTIVE_LIKE.contains(leaf.elementType)) return true
                if (leaf === last) return false
                leaf = TreeUtil.nextLeaf(leaf)
            }
            return false
        }

        private fun lexesDirectives(text: CharSequence): Boolean {
            val lexer = CSharpLexer()
            lexer.start(text, 0, text.length, 0)
            while (true) {
                val t: IElementType = lexer.tokenType ?: return false
                if (DIRECTIVE_LIKE.contains(t)) return true
                lexer.advance()
            }
        }
    }
}
