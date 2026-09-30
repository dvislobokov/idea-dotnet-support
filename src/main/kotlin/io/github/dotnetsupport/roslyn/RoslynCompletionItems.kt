package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.platform.lsp.api.customization.LspCompletionSupport
import io.github.dotnetsupport.lang.CSharpCalls
import io.github.dotnetsupport.suggest.SuggestionStats
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind

/**
 * Completion of the server with what a client of Roslyn adds itself, as Rider and VS Code do: a method comes as its bare name
 * (`WriteLine`, plain text), so a chosen method got no parentheses, the caret stayed after the name and Ctrl+P had no argument list to
 * show the overloads for (reported). Now `()` go after it with the caret inside, and the parameter info pops up at once. A method that
 * returns nothing gets its `;` as well (reported: Rider completes `Console.WriteLine(|);`).
 */
class RoslynCompletionSupport : LspCompletionSupport() {
    /**
     * `(` is a trigger character of Roslyn, and the platform opened the whole list of types with an empty prefix next to the parameter info
     * (seen on a screenshot of the user). After `(` Rider and Visual Studio show the parameters only; the list comes once a name is typed.
     */
    override fun isTriggerCharacterRespected(c: Char): Boolean = RoslynCompletionPolicy.isTrigger(c)

    /**
     * The tail and the type of a row as in Rider (`WriteLine`  `(string? value)  +18 overloads`  `void`): Roslyn sends neither, only the
     * signature in the documentation of a resolved item. The platform resolves the rows in sight in the background and draws them again,
     * so a row gets its tail a moment after the list opens.
     */
    override fun getTailText(item: CompletionItem): String? = RoslynSignatureTail.of(item)?.tail ?: super.getTailText(item)

    override fun getTypeText(item: CompletionItem): String? = RoslynSignatureTail.of(item)?.type ?: super.getTypeText(item)

    override fun createLookupElement(parameters: CompletionParameters, item: CompletionItem): LookupElement? {
        val created = super.createLookupElement(parameters, item) ?: return null
        val element = RoslynCompletionPolicy.lookupStringOverride(item)?.let { label -> MatchedByLabel(created, label) } ?: created
        val callable = RoslynCompletionPolicy.isCallable(item.kind, item.label)
        // a type gets its parentheses only after `new`: `new HttpClient(|)`, as Rider completes a constructor
        val type = RoslynCompletionPolicy.isType(item.kind)
        // `List<>`, `AddSingleton<>`: the server says the item is generic, and inserts the bare name
        val generic = RoslynCompletionPolicy.isGeneric(item.label)
        val genericType = generic && RoslynCompletionPolicy.isGenericType(item.kind)
        val withParentheses = if (!callable && !type && !genericType) element
        // the insertion of the platform first (the text edit of the item, the `using` of its resolve), then the parentheses
        else LookupElementDecorator.withInsertHandler(element) { context: InsertionContext, decorator: LookupElementDecorator<LookupElement> ->
            decorator.delegate.handleInsert(context)
            val afterNew = RoslynCompletionPolicy.afterNew(context.document.charsSequence, context.startOffset)
            when {
                callable -> addParentheses(context, resolved(context, item), generic)
                genericType -> addTypeArguments(context, constructed = afterNew && type)
                afterNew -> addParentheses(context, null, false)
            }
        }
        // what is in scope above what is merely spelled alike: `names` before `nameof`, as Rider orders the list; and above both
        // what fits the place: of the type that is wanted, named as the parameter, declared a line above, chosen here before
        val name = item.label.orEmpty().removeSuffix("<>")
        val context = runCatching { parameters.originalFile.project.service<RoslynCompletionContext>().at(parameters) }.getOrDefault(RoslynCompletionRanking.Context.NONE)
        if (RoslynCompletionRanking.isBeingDeclared(name, item.kind, context)) return null
        val bonus = RoslynCompletionRanking.bonus(name, item.kind, context, SuggestionStats.getInstance().labelCount(name))
        val ranked = PrioritizedLookupElement.withPriority(withParentheses, RoslynCompletionPolicy.priority(item.kind, item.preselect == true) + bonus.value)
        if (bonus.signals.isNotEmpty()) ranked.putUserData(SuggestionStats.SIGNALS, bonus.signals)
        return ranked
    }

    /**
     * What is known of the method when it is chosen: the platform resolves the rows in sight, and the resolved item (with the signature
     * in its documentation) is kept by the object of the lookup element, not by the item the element was made of.
     */
    /**
     * The item with what its resolve has brought, the signature among it. The platform wraps the element made here into one of its
     * own and keeps the resolved item there, so it is the element of the list that is asked (`context.elements`), not the one this
     * handler belongs to: that one holds the item as it came (seen live: a void method got no `;`).
     */
    private fun resolved(context: InsertionContext, item: CompletionItem): CompletionItem =
        context.elements.firstNotNullOfOrNull { RoslynCompletionPolicy.resolvedItem(it.`object`) }?.takeIf { it.label == item.label } ?: item

    /** `List<|>`, and `new List<|>()` for a class or a struct after `new`. */
    private fun addTypeArguments(context: InsertionContext, constructed: Boolean) {
        val document = context.document
        val offset = context.tailOffset
        val text = document.charsSequence
        if (!RoslynCompletionPolicy.addsTypeArguments(context.completionChar, text, context.startOffset, offset)) return
        document.insertString(offset, if (constructed) "<>()" else "<>")
        context.editor.caretModel.moveToOffset(offset + 1)
        context.commitDocument()
    }

    private fun addParentheses(context: InsertionContext, item: CompletionItem?, generic: Boolean) {
        val signature = item?.let(RoslynSignatureTail::of)
        val typeArguments = generic && item != null && RoslynCompletionPolicy.needsTypeArguments(item)
        val document = context.document
        val offset = context.tailOffset
        val text = document.charsSequence
        val start = context.startOffset
        if (!RoslynCompletionPolicy.addsParentheses(context.completionChar, text, start, offset)) return
        // "(" typed to choose the item: it is the one of the pair, not a second one
        if (context.completionChar == '(') context.setAddCompletionChar(false)
        if (offset < text.length && text[offset] == '(') {
            context.editor.caretModel.moveToOffset(offset + 1)
        } else {
            // a void method is a statement and nothing else: `Console.WriteLine(|);`, as Rider completes it
            val call = RoslynCompletionPolicy.call(signature?.type, signature?.tail, RoslynCompletionPolicy.restOfLine(text, offset), typeArguments,
                RoslynCompletionPolicy.endsStatement(text, start))
            document.insertString(offset, call.text)
            context.editor.caretModel.moveToOffset(offset + call.caret)
            // chosen before the platform has resolved it: the plain `()` now, the rest when the server answers
            if (item != null && signature == null && RoslynCompletionPolicy.isCallable(item.kind)) {
                context.commitDocument()
                completeWhenResolved(context, item, offset, generic)
            }
            if (call.caret > 1 || typeArguments) {
                // nothing to type between the parentheses, or the type arguments come first: no parameter info yet
                context.commitDocument()
                return
            }
        }
        context.commitDocument()
        AutoPopupController.getInstance(context.project).autoPopupParameterInfo(context.editor, null)
        // the caret is where the first argument begins: what is at hand for it is offered as if `(` had been typed
        RoslynLambdaGhost.offer(context.editor)
    }
}

/**
 * A method chosen faster than the platform resolves it has no signature yet, so it got its plain `()`. The item is resolved here, and
 * when the answer comes and nothing has been typed meanwhile, the call is completed as it would have been: `();` of a void method,
 * `<|>()` of a generic one whose type arguments nothing infers.
 */
private fun completeWhenResolved(context: InsertionContext, item: CompletionItem, offset: Int, generic: Boolean) {
    val start = context.startOffset
    val project = context.project
    val editor = context.editor
    val document = context.document
    val workspace = project.service<RoslynWorkspace>()
    val client = workspace.clients.firstOrNull() ?: return
    val stamp = document.modificationStamp
    ApplicationManager.getApplication().executeOnPooledThread {
        val resolved = runCatching { client.sendRequestSync(RESOLVE_TIMEOUT_MS) { it.textDocumentService.resolveCompletionItem(item) } }.getOrNull() ?: return@executeOnPooledThread
        val signature = RoslynSignatureTail.of(resolved) ?: return@executeOnPooledThread
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || editor.isDisposed || document.modificationStamp != stamp || editor.caretModel.offset != offset + 1) return@invokeLater
            val text = document.charsSequence
            if (offset + 2 > text.length || text.subSequence(offset, offset + 2).toString() != "()") return@invokeLater
            val call = RoslynCompletionPolicy.call(signature.type, signature.tail, RoslynCompletionPolicy.restOfLine(text, offset + 2),
                generic && RoslynCompletionPolicy.needsTypeArguments(resolved), RoslynCompletionPolicy.endsStatement(text, start))
            if (call.text == "()" && call.caret == 1) return@invokeLater
            WriteCommandAction.runWriteCommandAction(project, "Complete Call", null, {
                document.replaceString(offset, offset + 2, call.text)
                editor.caretModel.moveToOffset(offset + call.caret)
            })
            // the change has taken the gray text of the arguments away
            if (call.caret == 1 && !call.text.startsWith("<")) RoslynLambdaGhost.offer(editor)
        }, ModalityState.nonModal())
    }
}

private const val RESOLVE_TIMEOUT_MS = 1_500

/** The element of the platform, matched by [label] only; inserting is its own business. */
internal class MatchedByLabel(delegate: LookupElement, private val label: String) : LookupElementDecorator<LookupElement>(delegate) {
    override fun getLookupString(): String = label
    override fun getAllLookupStrings(): Set<String> = setOf(label)
}

object RoslynCompletionPolicy {
    /**
     * The order of the list when several items match the prefix, as Rider has it: the names of the scope (locals, parameters, members)
     * first, then methods, then types, and the keywords last; what the server preselects stays on top. The server sends `sortText` in
     * alphabetical order, which puts the keyword `nameof` above the variable `names` on a typed `n`.
     */
    fun priority(kind: CompletionItemKind?, preselect: Boolean): Double {
        val base = when (kind) {
            CompletionItemKind.Variable, CompletionItemKind.Field, CompletionItemKind.Property, CompletionItemKind.EnumMember, CompletionItemKind.Event, CompletionItemKind.Constant -> 40.0
            CompletionItemKind.Method, CompletionItemKind.Function, CompletionItemKind.Constructor -> 30.0
            CompletionItemKind.Class, CompletionItemKind.Struct, CompletionItemKind.Interface, CompletionItemKind.Enum, CompletionItemKind.TypeParameter, CompletionItemKind.Module -> 20.0
            CompletionItemKind.Keyword -> 0.0
            CompletionItemKind.Snippet -> -10.0
            else -> 10.0
        }
        return if (preselect) base + 100.0 else base
    }

    private val CALLABLE = setOf(CompletionItemKind.Method, CompletionItemKind.Function)
    private val SUBSCRIPTION = Regex("""[+-]=\s*$""")

    /**
     * `await` comes with `textEditText` equal to what has been typed (`p`): its real edit, which also makes the method `async`, comes with the
     * resolve. The platform makes the text of the edit the lookup string, so `await` matched whatever was typed and stayed in the list
     * (reported on a screenshot). Such an item is matched by its label; what it inserts does not change.
     */
    fun lookupStringOverride(item: CompletionItem): String? {
        val edit = item.textEditText ?: return null
        return item.label.takeIf { item.filterText == null && edit != it && it.isNotEmpty() }
    }

    /** The trigger characters of Roslyn that open the list; `(` opens the parameter info instead, see [RoslynCompletionSupport]. */
    fun isTrigger(c: Char): Boolean = c != '('

    private val TYPES = setOf(CompletionItemKind.Class, CompletionItemKind.Struct)
    private val NEW_BEFORE = Regex("""\bnew\s+$""")

    /** A class or a struct: with `new` before it, the item is a constructor call. */
    fun isType(kind: CompletionItemKind?): Boolean = kind in TYPES

    /** `new ` right before the name at [nameStart], on the same line. */
    fun afterNew(text: CharSequence, nameStart: Int): Boolean {
        if (nameStart !in 0..text.length) return false
        val lineStart = text.lastIndexOf('\n', nameStart - 1) + 1
        return NEW_BEFORE.containsMatchIn(text.subSequence(lineStart, nameStart))
    }

    /** The keywords that are written with parentheses, as Rider completes them: `typeof(|)`, `nameof(|)`, `sizeof(|)`, `checked(|)`. */
    private val KEYWORDS_WITH_PARENTHESES = setOf("typeof", "nameof", "sizeof", "checked", "unchecked", "stackalloc")

    /** Methods, extension methods included (Roslyn sends them as `Method`), and the keyword operators; a constructor comes as its type, which is not called by name. */
    fun isCallable(kind: CompletionItemKind?, label: String? = null): Boolean =
        kind in CALLABLE || kind == CompletionItemKind.Keyword && label in KEYWORDS_WITH_PARENTHESES

    const val COMPLETION_OBJECT = "com.intellij.platform.lsp.impl.features.completion.LspCompletionObject"

    /**
     * The item of a lookup element of the platform, resolved when the platform has resolved it. The class is internal to the platform
     * (there is no API to ask for the resolved item), hence by name: when it is renamed, methods get their plain `()` and the test says so.
     */
    fun resolvedItem(lookupObject: Any?): CompletionItem? {
        if (lookupObject == null || lookupObject.javaClass.name != COMPLETION_OBJECT) return null
        return runCatching { lookupObject.javaClass.getMethod("getCompletionItem").invoke(lookupObject) as? CompletionItem }.getOrNull()
    }

    /** What goes after the name of a chosen method, and where the caret lands in it. */
    class Call(val text: String, val caret: Int)

    /**
     * `()` with the caret inside; `();` for a method that returns nothing, when nothing follows on the line: such a call can only be a
     * statement (or the body of a lambda or of an expression-bodied member, and there something follows). For a method that takes no
     * arguments in any of its overloads the caret goes after the call. [type] and [tail] are the ones of [RoslynSignatureTail]: null when
     * the item is not resolved yet, and then the method gets its plain `()`.
     */
    fun call(type: String?, tail: String?, restOfLine: CharSequence, typeArguments: Boolean = false, endsStatement: Boolean = false): Call {
        val statement = (type == "void" || endsStatement) && restOfLine.isBlank()
        val text = (if (typeArguments) "<>" else "") + (if (statement) "();" else "()")
        // `AddSingleton<|>()`: the type arguments are what is typed first
        return Call(text, if (typeArguments || tail != "()") 1 else text.length)
    }

    /** The label of a generic method or type, as the server sends it: `AddSingleton<>`, `List<>`. */
    fun isGeneric(label: String?): Boolean = label != null && label.length > 2 && label.endsWith("<>")

    private val GENERIC_TYPES = setOf(CompletionItemKind.Class, CompletionItemKind.Struct, CompletionItemKind.Interface)

    fun isGenericType(kind: CompletionItemKind?): Boolean = kind in GENERIC_TYPES

    /**
     * Whether a chosen generic type gets `<>`: chosen by Enter or Tab, nothing of the kind follows already, and not in a documentation
     * comment, where a type is written `List{T}`.
     */
    fun addsTypeArguments(completionChar: Char, text: CharSequence, nameStart: Int, nameEnd: Int): Boolean {
        if (completionChar != Lookup.NORMAL_SELECT_CHAR && completionChar != Lookup.REPLACE_SELECT_CHAR) return false
        if (nameStart !in 0..text.length || nameEnd !in nameStart..text.length) return false
        if (nameEnd < text.length && (text[nameEnd] == '<' || text[nameEnd] == '{')) return false
        val lineStart = text.lastIndexOf('\n', nameStart - 1) + 1
        return !text.subSequence(lineStart, nameStart).trimStart().startsWith("//")
    }

    /**
     * Whether the type arguments of a generic method have to be written: true when one of them is in no parameter, so nothing infers
     * it — `AddSingleton<TService>()`, `OfType<TResult>()`, `Convert<TSource, TResult>(TSource value)`; false for `Select` and `Same<T>(T
     * value)`. An extension method is shown by its receiver (`IEnumerable<int>.First<int>()`), which is its first parameter. The
     * signature is the one of the documentation of the resolved item, the first overload of several; not resolved: false.
     */
    fun needsTypeArguments(item: CompletionItem): Boolean {
        val markdown = RoslynSignatureTail.markdown(item) ?: return false
        return needsTypeArguments(markdown, item.label?.removeSuffix("<>") ?: return false)
    }

    fun needsTypeArguments(markdown: String, name: String): Boolean {
        val signature = RoslynSignatureTail.signature(markdown) ?: return false
        val at = Regex("""(?<=[.\s])""" + Regex.escape(name) + """<""").find(signature) ?: return false
        val open = at.range.last
        val close = closing(signature, open, '<', '>') ?: return false
        val arguments = io.github.dotnetsupport.lang.CSharpScopeNames.splitTopLevel(signature.substring(open + 1, close))
        val parametersOpen = close + 1
        if (signature.getOrNull(parametersOpen) != '(') return false
        val parametersClose = closing(signature, parametersOpen, '(', ')') ?: return false
        var inferredFrom = signature.substring(parametersOpen, parametersClose + 1)
        // the receiver alone: what stands before it is the type the method returns
        if (signature.startsWith("(extension)")) inferredFrom += " " + receiver(signature.substring(0, at.range.first))
        return arguments.any { argument -> !Regex("""(?<![\w.])""" + Regex.escape(argument) + """(?!\w)""").containsMatchIn(inferredFrom) }
    }

    /** `IEnumerable<int>` of `(extension) IEnumerable<TResult> IEnumerable<int>.`: the last word outside of angle brackets. */
    private fun receiver(before: String): String {
        val text = before.trimEnd().removeSuffix(".")
        var depth = 0
        for (i in text.indices.reversed()) {
            when (text[i]) {
                '>' -> depth++
                '<' -> depth--
                ' ' -> if (depth == 0) return text.substring(i + 1)
            }
        }
        return text
    }

    private fun closing(text: String, open: Int, left: Char, right: Char): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                left -> depth++
                right -> if (--depth == 0) return i
            }
        }
        return null
    }

    /** See [CSharpCalls.endsStatement]: shared with the items of the index of assemblies. */
    fun endsStatement(text: CharSequence, nameStart: Int): Boolean = CSharpCalls.endsStatement(text, nameStart)

    fun inInitializer(text: CharSequence, offset: Int): Boolean = CSharpCalls.inInitializer(text, offset)

    fun restOfLine(text: CharSequence, offset: Int): CharSequence = CSharpCalls.restOfLine(text, offset)

    /**
     * Whether a chosen method gets `()`. Only when it is chosen by Enter, Tab or `(` — a `.` or `;` typed to choose it means the user goes
     * on typing as they want. Not after `+=` / `-=`: that subscribes the method to an event, a method group without a call.
     */
    fun addsParentheses(completionChar: Char, text: CharSequence, nameStart: Int, nameEnd: Int): Boolean {
        if (completionChar != Lookup.NORMAL_SELECT_CHAR && completionChar != Lookup.REPLACE_SELECT_CHAR && completionChar != '(') return false
        if (nameStart !in 0..text.length || nameEnd !in nameStart..text.length) return false
        val lineStart = text.lastIndexOf('\n', nameStart - 1) + 1
        return !SUBSCRIPTION.containsMatchIn(text.subSequence(lineStart, nameStart))
    }
}

/** The tail (parameters, overloads) and the type of a completion row, from the signature Roslyn puts in the documentation of a resolved item. */
object RoslynSignatureTail {
    class Tail(val tail: String?, val type: String?)

    private val MEMBERS = setOf(CompletionItemKind.Method, CompletionItemKind.Function, CompletionItemKind.Property, CompletionItemKind.Field,
        CompletionItemKind.Variable, CompletionItemKind.Constant, CompletionItemKind.Event)
    private val CODE_BLOCK = Regex("""```[a-z]*[ \t]*\r?\n(.+?)\r?\n""")
    // the server separates the words with no-break spaces, and says `generic overloads` of a generic method
    private val OVERLOADS = Regex("""\+[\s\u00A0]*(\d+)[\s\u00A0]+(?:generic[\s\u00A0]+)?overloads?""")
    private val KIND_PREFIX = Regex("""^\([^)]*\)\s*""")

    fun of(item: CompletionItem): Tail? {
        if (item.kind !in MEMBERS) return null
        // the label of a generic is `AddSingleton<>`, the signature has `AddSingleton<TService>`
        return parse(markdown(item) ?: return null, item.label?.removeSuffix("<>") ?: return null)
    }

    fun markdown(item: CompletionItem): String? {
        val documentation = item.documentation ?: return null
        return if (documentation.isRight) documentation.right?.value else documentation.left
    }

    /** The first line of the code block, the kind in parentheses kept: `(extension) T IServiceProvider.GetRequiredService<T>() where ...`. */
    fun signature(markdown: String): String? = CODE_BLOCK.find(markdown)?.groupValues?.get(1)?.trim()

    /**
     * The documentation of `WriteLine`: a csharp code block with `void Console.WriteLine()`, then `&nbsp;\(\+ 18 overloads\)`. Also
     * `string Console.Title { get; set; }`, `(local variable) int count`: the type is what stands before the name (and its container).
     */
    fun parse(markdown: String, label: String): Tail? {
        val signature = CODE_BLOCK.find(markdown)?.groupValues?.get(1)?.trim()?.replace(KIND_PREFIX, "") ?: return null
        val name = Regex("""(?<=[.\s])""" + Regex.escape(label) + """(?=[(<\s{]|$)""").find(signature) ?: return null
        var before = signature.substring(0, name.range.first).trimEnd()
        if (before.endsWith('.')) before = withoutLastTopLevelWord(before.dropLast(1))
        val type = before.trim().takeIf { it.isNotEmpty() }
        val after = signature.substring(name.range.last + 1)
        val parameters = if (after.startsWith("(") || after.startsWith("<")) callPart(after) else null
        val overloads = OVERLOADS.find(markdown.replace("\\", ""))?.groupValues?.get(1)
        val tail = listOfNotNull(parameters, overloads?.let { "+$it overload" + if (it == "1") "" else "s" }).joinToString("  ").takeIf { it.isNotEmpty() }
        return Tail(tail, type)
    }

    /** `<T>(T value)` of `<T>(T value) where T : class`: up to the parenthesis that closes the parameter list. */
    private fun callPart(text: String): String? {
        val open = text.indexOf('(').takeIf { it >= 0 } ?: return null
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(0, i + 1)
            }
        }
        return null
    }

    /** `Task<Dictionary<string, int>> Service` without `Service`: the last word outside of angle brackets. */
    private fun withoutLastTopLevelWord(text: String): String {
        var depth = 0
        for (i in text.indices.reversed()) {
            when (text[i]) {
                '>' -> depth++
                '<' -> depth--
                ' ' -> if (depth == 0) return text.substring(0, i)
            }
        }
        return ""
    }
}
