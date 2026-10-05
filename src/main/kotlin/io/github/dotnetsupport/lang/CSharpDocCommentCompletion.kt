package io.github.dotnetsupport.lang

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.csharp.lang.psi.CSharpSimpleName

/**
 * COMPLETION in XML documentation comments (task 2.4 of docs/COMPLETION_GAPS.md, Rider's dumps 35 / 35b): `/// <` → the tags with their
 * attributes and closing tag written for you; `</` → the tags still open; `<param name="` → the parameters of the documented member not
 * documented yet, `<typeparam name="` the same for type parameters, `<paramref name="` / `<typeparamref name="` → all of them;
 * `cref="` → the members of the type around and the types the place sees (common exceptions first in `<exception cref="`);
 * `<list type="` and `<see langword="`. The comment and the declaration under it are read as text, so on both trees; the types of a
 * `cref` come from the native one.
 */
class CSharpDocCommentCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? CSharpFile ?: return
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, file.project)) return
        val text = parameters.editor.document.charsSequence
        val offset = parameters.offset
        val rest = CSharpDocCommentItems.restOfLine(text, offset) ?: return
        if (!CSharpLeaves.isTrivia(parameters.position) && parameters.position.parent?.let(CSharpLeaves::isTrivia) != true) return
        val items = CSharpDocCommentItems.items(rest, text, offset, parameters.position, parameters.originalFile) ?: return
        val set = result.withPrefixMatcher(rest.takeLastWhile { it.isLetterOrDigit() || it == '_' })
        items.forEach(set::addElement)
        result.stopHere()
    }
}

object CSharpDocCommentItems {
    private val LINE = Regex("""^\s*///(.*)$""")
    private val OPEN_TAG = Regex("""<(/?)([A-Za-z]*)$""")
    private val NAME = Regex("""<(param|typeparam|paramref|typeparamref)\s+name\s*=\s*"(\w*)$""")
    private val CREF = Regex("""<(\w+)[^<>]*\bcref\s*=\s*"([\w.]*)$""")
    private val LIST_TYPE = Regex("""<list\s+type\s*=\s*"(\w*)$""")
    private val LANGWORD = Regex("""<see\s+langword\s*=\s*"(\w*)$""")
    private val TAG = Regex("""<(/?)([A-Za-z]+)\b[^<>]*?(/?)>""")

    /** The text of the `///` line between `///` and the caret; null outside one. */
    fun restOfLine(text: CharSequence, offset: Int): String? {
        val line = text.subSequence(CSharpPreprocessor.lineStart(text, offset), offset).toString()
        return LINE.matchEntire(line)?.groupValues?.get(1)
    }

    /** Tag, attributes, what it holds; in the order Rider lists them. */
    private class Tag(val name: String, val after: String, val caret: Int, val popup: Boolean = false, val description: String)

    private val TAGS = listOf(
        Tag("summary", "></summary>", 1, description = "summary of the member"),
        Tag("param", " name=\"\"></param>", 7, popup = true, description = "a parameter"),
        Tag("typeparam", " name=\"\"></typeparam>", 7, popup = true, description = "a type parameter"),
        Tag("returns", "></returns>", 1, description = "the returned value"),
        Tag("remarks", "></remarks>", 1, description = "more about the member"),
        Tag("exception", " cref=\"\"></exception>", 7, popup = true, description = "an exception it throws"),
        Tag("inheritdoc", "/>", 2, description = "the documentation of the base"),
        Tag("see", " cref=\"\"/>", 7, popup = true, description = "a link"),
        Tag("seealso", " cref=\"\"/>", 7, popup = true, description = "a link in See Also"),
        Tag("paramref", " name=\"\"/>", 7, popup = true, description = "a reference to a parameter"),
        Tag("typeparamref", " name=\"\"/>", 7, popup = true, description = "a reference to a type parameter"),
        Tag("value", "></value>", 1, description = "the value of a property"),
        Tag("example", "></example>", 1, description = "an example"),
        Tag("code", "></code>", 1, description = "a block of code"),
        Tag("c", "></c>", 1, description = "code in text"),
        Tag("para", "></para>", 1, description = "a paragraph"),
        Tag("list", " type=\"bullet\"></list>", 15, description = "a list"),
        Tag("item", "><description></description></item>", 14, description = "an item of a list"),
        Tag("description", "></description>", 1, description = "the text of an item"),
        Tag("term", "></term>", 1, description = "the term of an item"),
        Tag("listheader", "></listheader>", 1, description = "the header of a table"),
        Tag("br", "/>", 2, description = "a line break"),
    )

    private val EXCEPTIONS = listOf(
        "ArgumentNullException", "ArgumentException", "ArgumentOutOfRangeException", "InvalidOperationException", "NotSupportedException",
        "NotImplementedException", "ObjectDisposedException", "FormatException", "KeyNotFoundException", "OperationCanceledException",
        "TimeoutException", "UnauthorizedAccessException", "IOException", "Exception",
    )

    fun items(rest: String, text: CharSequence, offset: Int, position: PsiElement, file: PsiFile): List<LookupElement>? {
        NAME.find(rest)?.let { match ->
            val target = target(text, offset) ?: return emptyList()
            val documented = Regex("""<${match.groupValues[1]}\s+name\s*=\s*"(\w+)"""").findAll(block(text, offset)).map { it.groupValues[1] }.toSet()
            val (names, icon) = when (match.groupValues[1]) {
                "param" -> target.parameters.filter { it !in documented } to AllIcons.Nodes.Parameter
                "typeparam" -> target.typeParameters.filter { it !in documented } to AllIcons.Nodes.Type
                "paramref" -> target.parameters to AllIcons.Nodes.Parameter
                else -> target.typeParameters to AllIcons.Nodes.Type
            }
            return names.mapIndexed { i, name -> PrioritizedLookupElement.withPriority(LookupElementBuilder.create(name).withIcon(icon).withInsertHandler(SKIP_QUOTE), 100.0 - i) }
        }
        CREF.find(rest)?.let { match -> return crefs(match.groupValues[1], match.groupValues[2], position) }
        LIST_TYPE.find(rest)?.let { return listOf("bullet", "number", "table").map { LookupElementBuilder.create(it).withInsertHandler(SKIP_QUOTE) } }
        LANGWORD.find(rest)?.let { return listOf("null", "true", "false", "static", "virtual", "abstract", "async", "await").map { LookupElementBuilder.create(it).bold().withInsertHandler(SKIP_QUOTE) } }
        OPEN_TAG.find(rest)?.let { match ->
            if (match.groupValues[1] == "/") return openTags(text, offset).mapIndexed { i, name ->
                PrioritizedLookupElement.withPriority(LookupElementBuilder.create(name).withPresentableText("/$name").withInsertHandler(CLOSE), 100.0 - i)
            }
            return TAGS.mapIndexed { i, tag ->
                val element = LookupElementBuilder.create(tag.name).withIcon(AllIcons.Nodes.Tag).withTypeText(tag.description, true).withInsertHandler(tagHandler(tag))
                PrioritizedLookupElement.withPriority(element, 100.0 - i)
            }
        }
        return null
    }

    private fun tagHandler(tag: Tag) = InsertHandler<LookupElement> { context, _ ->
        val document = context.document
        val tail = context.tailOffset
        // `<summary|>` typed before: what follows the name is kept
        val following = document.charsSequence.subSequence(tail, minOf(document.textLength, tail + 2)).toString()
        if (following.startsWith(">") || following.startsWith("/>")) return@InsertHandler
        document.insertString(tail, tag.after)
        context.editor.caretModel.moveToOffset(tail + tag.caret)
        if (tag.popup) AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
    }

    /** After a name in quotes: past the closing quote and `>` / `/>` written with the tag. */
    private val SKIP_QUOTE = InsertHandler<LookupElement> { context, _ -> skipQuote(context) }

    private fun skipQuote(context: InsertionContext) {
        val text = context.document.charsSequence
        var at = context.tailOffset
        if (text.getOrNull(at) != '"') return
        at++
        if (text.getOrNull(at) == '>') at++ else if (text.getOrNull(at) == '/' && text.getOrNull(at + 1) == '>') at += 2
        context.editor.caretModel.moveToOffset(at)
    }

    private val CLOSE = InsertHandler<LookupElement> { context, _ ->
        val tail = context.tailOffset
        if (context.document.charsSequence.getOrNull(tail) != '>') context.document.insertString(tail, ">")
        context.editor.caretModel.moveToOffset(tail + 1)
    }

    /** The `///` lines around the caret: the whole doc comment. */
    fun block(text: CharSequence, offset: Int): String = text.subSequence(blockStart(text, offset), blockEnd(text, offset)).toString()

    private fun lineEnd(text: CharSequence, from: Int): Int = text.indexOf('\n', from).let { if (it < 0) text.length else it }

    private fun blockStart(text: CharSequence, offset: Int): Int {
        var start = CSharpPreprocessor.lineStart(text, offset)
        while (start > 0) {
            val previous = CSharpPreprocessor.lineStart(text, start - 1)
            if (!text.subSequence(previous, start).trimStart().startsWith("///")) break
            start = previous
        }
        return start
    }

    private fun blockEnd(text: CharSequence, offset: Int): Int {
        var end = lineEnd(text, offset)
        while (end < text.length) {
            val next = lineEnd(text, end + 1)
            if (!text.subSequence(end + 1, next).trimStart().startsWith("///")) break
            end = next
        }
        return end
    }

    /** The tags open before the caret in the comment, the innermost first. */
    private fun openTags(text: CharSequence, offset: Int): List<String> {
        val stack = ArrayList<String>()
        for (match in TAG.findAll(text.subSequence(blockStart(text, offset), offset))) {
            val name = match.groupValues[2]
            when {
                match.groupValues[3] == "/" -> Unit
                match.groupValues[1] == "/" -> stack.lastIndexOf(name).takeIf { it >= 0 }?.let { while (stack.size > it) stack.removeAt(stack.size - 1) }
                else -> stack += name
            }
        }
        return stack.reversed().distinct()
    }

    // ---- the documented declaration

    class Target(val parameters: List<String>, val typeParameters: List<String>)

    /** The declaration under the comment at [offset], read as text: its parameters and type parameters (of the type around too for a type). */
    fun target(text: CharSequence, offset: Int): Target? {
        var at = text.indexOf('\n', offset).let { if (it < 0) return null else it + 1 }
        // the rest of the comment, attributes on lines of their own, blank lines
        while (at < text.length) {
            val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
            val line = text.subSequence(at, end).trim()
            if (line.startsWith("///") || line.isEmpty() || line.startsWith("[") && line.endsWith("]")) at = end + 1 else break
        }
        val declaration = StringBuilder()
        var depth = 0
        var i = at
        while (i < text.length && declaration.length < 4000) {
            val c = text[i]
            if (depth == 0 && (c == '{' || c == ';' || c == '=' && text.getOrNull(i + 1) == '>')) break
            when (c) { '(', '[', '<' -> depth++; ')', ']', '>' -> if (depth > 0) depth-- }
            declaration.append(c)
            i++
        }
        return parse(declaration.toString())
    }

    private val TYPE_HEADER = Regex("""\b(?:class|struct|interface|record(?:\s+(?:class|struct))?)\b""")

    fun parse(declaration: String): Target {
        var source = declaration
        // an indexer's `this[…]` reads as a parameter list; then attributes (and `[]` of arrays) go
        Regex("""\bthis\s*\[""").find(source)?.let { indexer ->
            val close = matching(source, indexer.range.last)
            if (close < source.length) source = source.substring(0, indexer.range.first) + "this(" + source.substring(indexer.range.last + 1, close) + ")" + source.substring(close + 1)
        }
        val text = source.replace(Regex("""\[[^\[\]]*]"""), " ")
        val open = text.indexOf('(').takeIf { it >= 0 }
        val head = if (open != null) text.substring(0, open) else text
        val type = TYPE_HEADER.find(head)
        val list = if (type != null) Regex("""^\s*@?\w+\s*<([^<>]*)>""").find(head.substring(type.range.last + 1))?.groupValues?.get(1)
        else Regex("""<([^<>()]*)>\s*$""").find(head.trimEnd())?.groupValues?.get(1)
        val typeParameters = list?.split(',')?.map { it.trim().split(Regex("""\s+""")).last() }?.filter { it.isNotEmpty() }.orEmpty()
        val parameters = if (open == null) emptyList() else {
            val close = matching(text, open)
            split(text.substring(open + 1, close)).mapNotNull { parameter ->
                Regex("""(@?\w+)\s*$""").find(parameter.substringBefore('=').trim())?.groupValues?.get(1)?.removePrefix("@")
            }.filter { it.isNotEmpty() && it !in setOf("this", "params", "ref", "out", "in") }
        }
        return Target(parameters, typeParameters)
    }

    private fun matching(text: String, open: Int): Int {
        var depth = 0
        for (k in open until text.length) {
            when (text[k]) { '(', '[' -> depth++; ')', ']' -> if (--depth == 0) return k }
        }
        return text.length
    }

    private fun split(parameters: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val current = StringBuilder()
        for (c in parameters) {
            when (c) {
                '<', '(', '[' -> depth++
                '>', ')', ']' -> depth--
                ',' -> if (depth == 0) { out += current.toString(); current.clear(); continue }
            }
            current.append(c)
        }
        if (current.isNotBlank()) out += current.toString()
        return out
    }

    // ---- cref

    private fun crefs(tag: String, written: String, position: PsiElement): List<LookupElement> {
        val out = ArrayList<LookupElement>()
        val added = HashSet<String>()
        fun add(element: LookupElement) { if (added.add(element.lookupString)) out += element }
        val qualifier = written.substringBeforeLast('.', "")
        val file = position.containingFile as? CSharpFile
        if (tag == "exception" && qualifier.isEmpty()) {
            EXCEPTIONS.forEachIndexed { i, name -> add(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.ExceptionClass), 500.0 - i)) }
        }
        if (file != null && file.compilationUnit != null) {
            val resolver = NativeCSharpResolver(file)
            if (qualifier.isNotEmpty()) {
                val type = resolver.visibleTypes(position, qualifier.substringAfterLast('.'), 0).firstOrNull() ?: return out
                members(resolver, type, file).forEach(::add)
                return out
            }
            resolver.enclosingTypes(position).firstOrNull()?.let { type -> members(resolver, type, file).forEach(::add) }
            val name = position.parent as? CSharpSimpleName
            if (CSharpLeaves.isIdentifier(position)) {
                val place = NativeCSharpCompletionPlace(NativeCompletionKind.TYPE, position, name, null)
                val matcher = com.intellij.codeInsight.completion.impl.CamelHumpMatcher("", false)
                for (item in NativeCSharpCompletion.items(place, file, matcher)) {
                    val keyword = item.lookupString in NativeCSharpCompletionPlace.ALL_KEYWORDS && item.lookupString !in NativeCSharpCompletionPlace.PREDEFINED_TYPES
                    if (!keyword) add(item)
                }
            }
        }
        NativeCSharpCompletionPlace.PREDEFINED_TYPES.forEach { add(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(it).bold(), 0.0)) }
        return out
    }

    private fun members(resolver: NativeCSharpResolver, type: TypeInfo, file: CSharpFile): List<LookupElement> =
        resolver.membersOf(type).mapNotNull { (key, member) ->
            if ('<' in key || '`' in key) return@mapNotNull null
            val icon = when {
                member.nestedType != null -> AllIcons.Nodes.Class
                else -> when (NativeCSharpMembers.kind(member)) {
                    NativeCSharpMembers.Kind.METHOD -> AllIcons.Nodes.Method
                    NativeCSharpMembers.Kind.PROPERTY -> AllIcons.Nodes.Property
                    NativeCSharpMembers.Kind.CONSTANT -> AllIcons.Nodes.Constant
                    else -> AllIcons.Nodes.Field
                }
            }
            val typeText = if (member.nestedType == null) NativeCSharpMembers.typeIn(member, file) else null
            PrioritizedLookupElement.withPriority(LookupElementBuilder.create(key).withIcon(icon).withTypeText(typeText).withInsertHandler(SKIP_QUOTE), 300.0)
        }
}

/** Opens the list after `<` in a `///` line and after `"` of `cref="` / `name="`, as Rider does. */
class CSharpDocCommentTypedCompletion : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file !is CSharpFile || charTyped != '<' && charTyped != '"') return Result.CONTINUE
        val text = editor.document.charsSequence
        val offset = editor.caretModel.offset
        val rest = CSharpDocCommentItems.restOfLine(text, offset) ?: return Result.CONTINUE
        val popup = charTyped == '<' || Regex("""\b(cref|name|type|langword)\s*=\s*$""").containsMatchIn(rest)
        if (popup) AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.CONTINUE
    }
}
