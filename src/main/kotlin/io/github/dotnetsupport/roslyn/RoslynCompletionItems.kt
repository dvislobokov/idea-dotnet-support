package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.platform.lsp.api.customization.LspCompletionSupport
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind

/**
 * Completion of the server with what a client of Roslyn adds itself, as Rider and VS Code do: a method comes as its bare name
 * (`WriteLine`, plain text), so a chosen method got no parentheses, the caret stayed after the name and Ctrl+P had no argument list to
 * show the overloads for (reported). Now `()` go after it with the caret inside, and the parameter info pops up at once.
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
        if (!RoslynCompletionPolicy.isCallable(item.kind)) return element
        // the insertion of the platform first (the text edit of the item, the `using` of its resolve), then the parentheses
        return LookupElementDecorator.withInsertHandler(element) { context: InsertionContext, decorator: LookupElementDecorator<LookupElement> ->
            decorator.delegate.handleInsert(context)
            addParentheses(context)
        }
    }

    private fun addParentheses(context: InsertionContext) {
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
            document.insertString(offset, "()")
            context.editor.caretModel.moveToOffset(offset + 1)
        }
        context.commitDocument()
        AutoPopupController.getInstance(context.project).autoPopupParameterInfo(context.editor, null)
    }
}

/** The element of the platform, matched by [label] only; inserting is its own business. */
internal class MatchedByLabel(delegate: LookupElement, private val label: String) : LookupElementDecorator<LookupElement>(delegate) {
    override fun getLookupString(): String = label
    override fun getAllLookupStrings(): Set<String> = setOf(label)
}

object RoslynCompletionPolicy {
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

    /** Methods, extension methods included (Roslyn sends them as `Method`); a constructor comes as its type, which is not called by name. */
    fun isCallable(kind: CompletionItemKind?): Boolean = kind in CALLABLE

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
    private val OVERLOADS = Regex("""\+\s*(\d+)\s+overloads?""")
    private val KIND_PREFIX = Regex("""^\([^)]*\)\s*""")

    fun of(item: CompletionItem): Tail? {
        if (item.kind !in MEMBERS) return null
        val documentation = item.documentation ?: return null
        val markdown = if (documentation.isRight) documentation.right?.value else documentation.left
        return parse(markdown ?: return null, item.label ?: return null)
    }

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
