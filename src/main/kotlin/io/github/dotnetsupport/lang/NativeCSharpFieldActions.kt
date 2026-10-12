package io.github.dotnetsupport.lang

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*

/**
 * The text edits of "Initialize field from constructor" and "Initialize members from constructor" (0.1.168, the user's report: Rider offers
 * them on a field no constructor assigns, the plugin offered nothing). Pure functions on the tree and the document's text, tested without an
 * editor. A field qualifies when it is an instance field of a class, struct or record, has no initializer and some instance constructor does
 * not assign it (`field = …` / `this.field = …`; a constructor chaining to `this(…)` counts as assigning); with no constructor at all one
 * is created, with a primary constructor the parameter goes there and the field gets `= parameter` (as Rider writes them).
 */
object NativeCSharpFieldEdits {
    private fun present(token: PsiElement?): Boolean = (token?.textLength ?: 0) > 0

    private fun lineStart(text: CharSequence, offset: Int): Int = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    private fun lineIndent(text: CharSequence, offset: Int): String =
        text.subSequence(lineStart(text, offset), offset).takeWhile { it == ' ' || it == '\t' }.toString()

    /** A field of a type: its declaration, the declarator, the name and the type as written. */
    class Field(val declaration: CSharpFieldDeclaration, val declarator: CSharpVariableDeclarator, val name: String, val type: String) {
        /** The parameter's name: `_logger` → `logger`, `m_name` → `name`, `Count` → `count`; a keyword gets `@`. */
        val parameter: String
            get() {
                val bare = name.removePrefix("m_").removePrefix("s_").trimStart('_').ifEmpty { "value" }
                val lower = bare.replaceFirstChar { it.lowercase() }
                return if (lower in NativeCSharpCompletionPlace.ALL_KEYWORDS) "@$lower" else lower
            }

        /** `_logger = logger;`, `this.name = name;` when the parameter hides the field. */
        val assignment: String get() = if (parameter == name) "this.$name = $parameter;" else "$name = $parameter;"
    }

    /** A change of the text: [start] … [end] becomes [text] (an insertion when they are equal). */
    private class Change(val start: Int, val end: Int, val text: String)

    /** The field under [offset] that qualifies ([qualifies]), with the type that declares it. */
    fun fieldAt(file: PsiFile, offset: Int): Pair<Field, CSharpTypeDeclaration>? {
        for (candidate in listOf(offset, offset - 1)) {
            val leaf = file.findElementAt(candidate.coerceAtLeast(0)) ?: continue
            val declaration = PsiTreeUtil.getParentOfType(leaf, CSharpFieldDeclaration::class.java, false) ?: continue
            val type = declaration.parent as? CSharpTypeDeclaration ?: continue
            val declarators = declaration.declaration?.variables.orEmpty()
            val declarator = declarators.firstOrNull { it.textRange.containsOffset(candidate) } ?: declarators.singleOrNull() ?: continue
            val field = fieldOf(declaration, declarator) ?: continue
            if (qualifies(field, type)) return field to type
        }
        return null
    }

    private fun fieldOf(declaration: CSharpFieldDeclaration, declarator: CSharpVariableDeclarator): Field? {
        val name = declarator.identifier?.text?.takeIf { it.isNotEmpty() } ?: return null
        val type = declaration.declaration?.type?.text?.takeIf { it.isNotBlank() } ?: return null
        return Field(declaration, declarator, name, type)
    }

    /** The instance fields of [type] without initializer that some constructor leaves unassigned, in order. */
    fun unassignedFields(type: CSharpTypeDeclaration): List<Field> = type.members.filterIsInstance<CSharpFieldDeclaration>().flatMap { declaration ->
        declaration.declaration?.variables.orEmpty().mapNotNull { fieldOf(declaration, it) }
    }.filter { qualifies(it, type) }

    fun qualifies(field: Field, type: CSharpTypeDeclaration): Boolean {
        if (type is CSharpInterfaceDeclaration) return false
        if (field.declaration.modifiers.any { it.text == "static" || it.text == "const" }) return false
        if (field.declarator.initializer != null) return false
        if (type.parameterList != null) return true
        val constructors = constructorsOf(type)
        return constructors.isEmpty() || constructors.any { !assigns(it, field.name) }
    }

    /** The instance constructors of [type]. */
    private fun constructorsOf(type: CSharpTypeDeclaration): List<CSharpConstructorDeclaration> =
        type.members.filterIsInstance<CSharpConstructorDeclaration>().filter { c -> c.modifiers.none { it.text == "static" } }

    /** [constructor] assigns the field [name] in its body, or chains to `this(…)`, which does. */
    private fun assigns(constructor: CSharpConstructorDeclaration, name: String): Boolean {
        if (constructor.initializer?.thisOrBaseKeyword?.text == "this") return true
        val body = constructor.body?.text ?: constructor.expressionBody?.text ?: return false
        return Regex("""(?<![\w.])(?:this\.)?${Regex.escape(name)}\s*(?:=(?!=)|\?\?=)""").containsMatchIn(body)
    }

    /** The statement after which the assignments go: the last of the assignments the body starts with; null: they go first. */
    private fun lastLeadingAssignment(body: CSharpBlock): CSharpStatement? =
        body.statements.takeWhile { s -> s is CSharpExpressionStatement && Regex("""^(?:this\.)?@?\w+\s*=(?!=)""").containsMatchIn(s.text) }.lastOrNull()

    /** The new parameters of [list] for [wanted]: a parameter of that name already there is reused. */
    private fun parameters(list: CSharpParameterList, wanted: List<Field>): Change? {
        val close = list.closeParenToken?.takeIf(::present) ?: return null
        val existing = list.parameters.mapNotNull { it.identifier?.text }.toSet()
        val added = wanted.filter { it.parameter !in existing }.joinToString(", ") { "${it.type} ${it.parameter}" }
        if (added.isEmpty()) return null
        val at = close.textRange.startOffset
        return Change(at, at, if (list.parameters.isEmpty()) added else ", $added")
    }

    /** The assignments of [wanted] in [constructor]'s body (a block is made of an expression body); the offset the caret goes to. */
    private fun assignments(constructor: CSharpConstructorDeclaration, wanted: List<Field>, text: CharSequence, unit: String): Pair<Change, Int>? {
        val body = constructor.body
        if (body != null) {
            val open = body.openBraceToken?.takeIf(::present) ?: return null
            val close = body.closeBraceToken?.takeIf(::present) ?: return null
            val after = lastLeadingAssignment(body)
            val first = body.statements.firstOrNull()
            return when {
                after != null -> {
                    val indent = lineIndent(text, after.textRange.startOffset)
                    val at = after.textRange.endOffset
                    Change(at, at, wanted.joinToString("") { "\n$indent${it.assignment}" }) to at
                }
                first != null -> {
                    val at = lineStart(text, first.textRange.startOffset)
                    val indent = lineIndent(text, first.textRange.startOffset)
                    Change(at, at, wanted.joinToString("") { "$indent${it.assignment}\n" }) to at
                }
                else -> {
                    // `{ }` or `{` / `}` with nothing between: the braces on their lines, the assignments inside
                    val outer = lineIndent(text, open.textRange.startOffset)
                    val inner = outer + unit
                    Change(open.textRange.endOffset, close.textRange.startOffset, wanted.joinToString("") { "\n$inner${it.assignment}" } + "\n$outer") to
                        open.textRange.endOffset
                }
            }
        }
        // `=> expression;`: a block with the expression and the assignments, as Rider turns it
        val arrow = constructor.expressionBody ?: return null
        val expression = arrow.expression?.text ?: return null
        val semicolon = constructor.semicolonToken?.takeIf(::present) ?: return null
        val outer = lineIndent(text, constructor.textRange.startOffset)
        val inner = outer + unit
        var start = arrow.textRange.startOffset
        while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
        val block = "\n$outer{\n$inner$expression;" + wanted.joinToString("") { "\n$inner${it.assignment}" } + "\n$outer}"
        return Change(start, semicolon.textRange.endOffset, block) to start
    }

    /** Every [fields] initialized from the constructors of [type]: one edit over the span of the changes, the caret at the first assignment. */
    fun initialize(type: CSharpTypeDeclaration, fields: List<Field>, text: CharSequence, unit: String): CSharpTextEdit? {
        if (fields.isEmpty()) return null
        val changes = ArrayList<Change>()
        var caret: Int? = null
        val primary = type.parameterList
        if (primary != null) {
            parameters(primary, fields)?.let(changes::add)
            for (field in fields) {
                val end = field.declarator.identifier?.textRange?.endOffset ?: return null
                changes += Change(end, end, " = ${field.parameter}")
                if (caret == null) caret = end
            }
        } else {
            val constructors = constructorsOf(type)
            if (constructors.isEmpty()) {
                val last = type.members.filterIsInstance<CSharpFieldDeclaration>().lastOrNull() ?: return null
                val indent = lineIndent(text, last.textRange.startOffset)
                val name = type.identifier?.text ?: return null
                val body = fields.joinToString("") { "\n$indent$unit${it.assignment}" }
                val at = last.textRange.endOffset
                changes += Change(at, at, "\n\n${indent}public $name(${fields.joinToString(", ") { "${it.type} ${it.parameter}" }})\n$indent{$body\n$indent}")
                caret = at
            }
            for (constructor in constructors) {
                if (constructor.initializer?.thisOrBaseKeyword?.text == "this") continue
                val wanted = fields.filter { !assigns(constructor, it.name) }
                if (wanted.isEmpty()) continue
                constructor.parameterList?.let { parameters(it, wanted) }?.let(changes::add)
                val (change, at) = assignments(constructor, wanted, text, unit) ?: continue
                changes += change
                if (caret == null) caret = at
            }
        }
        if (changes.isEmpty()) return null
        changes.sortWith(compareBy<Change> { it.start }.thenBy { it.end })
        val from = changes.first().start
        val to = changes.maxOf { it.end }
        val out = StringBuilder()
        var at = from
        for (change in changes) {
            if (change.start < at) return null
            out.append(text, at, change.start).append(change.text)
            at = change.end
        }
        out.append(text, at, to)
        val relative = ((caret ?: from) - from).coerceIn(0, out.length)
        return CSharpTextEdit(TextRange(from, to), out.toString(), caret = relative)
    }
}

/** Alt+Enter on a field no constructor assigns: a parameter of each constructor and the assignment (Rider: "Initialize field from constructor"). */
class NativeCSharpInitializeFieldFromConstructorIntention : NativeCSharpContextAction("Initialize field from constructor", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val (field, type) = NativeCSharpFieldEdits.fieldAt(file, editor.caretModel.offset) ?: return null
        return NativeCSharpFieldEdits.initialize(type, listOf(field), editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}

/** Alt+Enter on one of several such fields: all of them at once (Rider: "Initialize members from constructor"). */
class NativeCSharpInitializeMembersFromConstructorIntention : NativeCSharpContextAction("Initialize members from constructor", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val (_, type) = NativeCSharpFieldEdits.fieldAt(file, editor.caretModel.offset) ?: return null
        val fields = NativeCSharpFieldEdits.unassignedFields(type).takeIf { it.size >= 2 } ?: return null
        return NativeCSharpFieldEdits.initialize(type, fields, editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}
