package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*

/**
 * The keywords of C# that the place of the caret allows ([NativeCSharpCompletionPlace]), for the native completion: a statement's start
 * gets the statement keywords (`break` / `continue` only in a loop or `switch`, `else` after an `if` without one, `catch` / `finally`
 * after a `try`, `yield` in an iterator, `case` / `default` in a switch section), a value the expression keywords (`this` / `base` not in
 * a static member, `await` only where the function is or can be made `async`), a member's start the modifiers not typed yet and
 * compatible with those that are (one accessibility, `virtual` / `abstract` / `override` exclusive, no `static` beside them...).
 */
object NativeCSharpKeywords {
    // `global::`, `ref x`, `stackalloc`, `static` lambdas: offered by the server too, which loses its keyword items to these (robot, 0.1.60)
    private val EXPRESSION = listOf(
        "new", "null", "true", "false", "default", "typeof", "nameof", "sizeof", "checked", "unchecked", "from", "async", "delegate", "global", "ref", "stackalloc", "static",
    )
    private val STATEMENT = listOf(
        "if", "for", "foreach", "while", "do", "switch", "try", "return", "throw", "using", "lock", "goto", "var", "const", "unsafe", "static", "fixed",
        "dynamic", "extern", "scoped", "void",
    )

    /** Where only a type goes, beside the predefined types: `dynamic`, `global::`, `delegate*` (as the server). */
    val TYPE: List<String> = listOf("dynamic", "global", "delegate")
    private val LOOPS = setOf(CSharpWhileStatement::class.java, CSharpDoStatement::class.java, CSharpForStatement::class.java, CSharpCommonForEachStatement::class.java)
    private val ACCESS = listOf("public", "private", "protected", "internal")
    private val TYPE_KINDS = listOf("class", "struct", "interface", "enum", "record", "delegate")

    fun statement(place: NativeCSharpCompletionPlace): List<String> {
        val at = place.name ?: place.leaf
        val result = ArrayList<String>()
        result += STATEMENT
        val statement = place.name?.let(NativeCSharpCompletionPlace::statementStart)
        val previous = statement?.let { PsiTreeUtil.getPrevSiblingOfType(it, CSharpStatement::class.java) }
        if (previous is CSharpIfStatement && (previous.`else`?.textLength ?: 0) == 0) result += "else"
        if (previous is CSharpTryStatement) {
            result += "catch"
            if ((previous.finally?.textLength ?: 0) == 0) result += "finally"
        }
        if (inLoop(at)) result += listOf("break", "continue")
        else if (PsiTreeUtil.getParentOfType(at, CSharpSwitchSection::class.java, true, CSharpMemberDeclaration::class.java, CSharpAnonymousFunctionExpression::class.java) != null) result += "break"
        if (statement?.parent is CSharpSwitchSection) result += listOf("case", "default")
        val function = NativeCSharpCommonCalls.function(at)
        if (function != null && NativeCSharpCommonCalls.returnType(function)?.let(::isIterator) == true) result += "yield"
        result += expression(place)
        return result
    }

    fun expression(place: NativeCSharpCompletionPlace): List<String> {
        val at = place.name ?: place.leaf
        val result = ArrayList<String>(EXPRESSION)
        if (!NativeCSharpLocals.inStaticContext(at) && PsiTreeUtil.getParentOfType(at, CSharpBaseTypeDeclaration::class.java) != null) result += listOf("this", "base")
        if (NativeCSharpCommonCalls.awaitState(at) != NativeCSharpCommonCalls.AwaitState.NO) result += "await"
        // `throw` is an expression after `??`, `=>`, `?` `:`
        if (place.prev?.text in setOf("??", "=>", "?", ":") && place.prev?.parent !is CSharpSwitchLabel) result += "throw"
        return result
    }

    /** At the start of a member: what may still be written before its type. */
    fun memberStart(typed: List<String>, type: CSharpTypeDeclaration?): List<String> {
        val has = typed.toSet()
        val result = ArrayList<String>()
        when {
            has.none { it in ACCESS } -> result += ACCESS
            has == setOf("protected") || "protected" in has && "internal" !in has && "private" !in has -> result += listOf("internal", "private")
            "private" in has && "protected" !in has -> result += "protected"
            "internal" in has && "protected" !in has -> result += "protected"
        }
        val inheritance = listOf("virtual", "abstract", "override")
        if (has.none { it in inheritance } && "static" !in has && "const" !in has) result += inheritance
        // the server offers `sealed` before `override` too (`sealed override`)
        if ("sealed" !in has && has.none { it in setOf("static", "const", "virtual", "abstract") }) result += "sealed"
        if ("static" !in has && "const" !in has && has.none { it in inheritance }) result += "static"
        if ("readonly" !in has && "const" !in has) result += "readonly"
        if ("const" !in has && "static" !in has && "readonly" !in has && has.none { it in inheritance }) result += "const"
        if ("async" !in has && "const" !in has && "abstract" !in has) result += "async"
        for (modifier in listOf("new", "extern", "unsafe", "partial", "required", "volatile")) if (modifier !in has) result += modifier
        result += listOf("void", "event", "implicit", "explicit", "dynamic", "global")
        if ("ref" !in has && "const" !in has) result += "ref"
        result += TYPE_KINDS
        if (type is CSharpInterfaceDeclaration) result.removeAll(listOf("private", "override", "sealed", "readonly", "volatile", "const", "required", "implicit", "explicit"))
        return result.filter { it !in has }
    }

    fun topLevel(typed: List<String>): List<String> {
        val has = typed.toSet()
        val result = ArrayList<String>()
        if (has.isEmpty()) result += listOf("using", "namespace", "global")
        if (has.none { it in ACCESS || it == "file" }) result += listOf("public", "internal", "file")
        for (modifier in listOf("static", "abstract", "sealed", "partial", "readonly", "unsafe")) if (modifier !in has) result += modifier
        result += TYPE_KINDS
        return result
    }

    private fun inLoop(at: PsiElement): Boolean {
        var current: PsiElement? = at.parent
        while (current != null && current !is CSharpFile) {
            if (current is CSharpMemberDeclaration || current is CSharpAnonymousFunctionExpression || current is CSharpLocalFunctionStatement) return false
            if (current is CSharpSwitchSection) return false
            if (LOOPS.any { it.isInstance(current) }) {
                // the body of the loop, not its header
                val body = when (current) {
                    is CSharpWhileStatement -> current.statement
                    is CSharpDoStatement -> current.statement
                    is CSharpForStatement -> current.statement
                    is CSharpCommonForEachStatement -> current.statement
                    else -> null
                }
                return body != null && PsiTreeUtil.isAncestor(body, at, false)
            }
            current = current.parent
        }
        return false
    }

    private fun isIterator(type: String): Boolean = type.substringAfterLast('.').let {
        it.startsWith("IEnumerable") || it.startsWith("IEnumerator") || it.startsWith("IAsyncEnumerable") || it.startsWith("IAsyncEnumerator")
    }

    private val PARENTHESES = setOf("typeof", "nameof", "sizeof", "checked", "unchecked", "if", "while", "for", "foreach", "switch", "lock", "using", "fixed", "catch")
    private val SEMICOLON = setOf("break", "continue")
    private val NOTHING = setOf("null", "true", "false", "this", "base", "default", "else", "try", "finally", "do", "void", "new()", "in", "out", "ref")

    /**
     * What a chosen keyword brings, as Rider completes them: `typeof(|)`, `if (|)` (a statement's header; the statement keywords with a
     * parenthesis), `break;`, a space after the others (`return |`, `public |`). Nothing when the keyword is chosen by typing a character.
     * `await` in a function that is not `async` makes it `async` ([NativeCSharpCommonCalls.makeAsync]); `return` in a `void` function
     * is `return;`.
     */
    fun handler(keyword: String, place: NativeCSharpCompletionPlace): InsertHandler<LookupElement> = InsertHandler { context, _ ->
        if (context.completionChar != Lookup.NORMAL_SELECT_CHAR && context.completionChar != Lookup.REPLACE_SELECT_CHAR) return@InsertHandler
        val document = context.document
        val offset = context.tailOffset
        val next = document.charsSequence.getOrNull(offset)
        val headerKeyword = keyword in PARENTHESES && (keyword !in setOf("using", "fixed", "checked", "unchecked") || place.kind == NativeCompletionKind.STATEMENT && keyword in setOf("using", "fixed"))
        when {
            keyword == "await" -> {
                if (next != ' ') insert(context, " ", 1)
                NativeCSharpCommonCalls.makeAsyncAt(context.file, context.startOffset, context.editor)
            }
            keyword == "return" && place.kind == NativeCompletionKind.STATEMENT && NativeCSharpCommonCalls.function(place.leaf)?.let(NativeCSharpCommonCalls::returnsNothing) == true ->
                if (next != ';') insert(context, ";", 1)
            keyword in SEMICOLON -> if (next != ';') insert(context, ";", 1)
            headerKeyword -> if (next != '(') {
                val space = if (keyword in setOf("typeof", "nameof", "sizeof", "checked", "unchecked")) "" else " "
                insert(context, "$space()", space.length + 1)
            }
            keyword in NOTHING -> {}
            next != ' ' -> insert(context, " ", 1)
            else -> context.editor.caretModel.moveToOffset(offset + 1)
        }
    }

    private fun insert(context: InsertionContext, text: String, caret: Int) {
        val offset = context.tailOffset
        context.document.insertString(offset, text)
        context.editor.caretModel.moveToOffset(offset + caret)
        context.commitDocument()
    }
}
