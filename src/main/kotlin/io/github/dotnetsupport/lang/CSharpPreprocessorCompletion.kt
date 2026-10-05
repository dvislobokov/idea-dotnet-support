package io.github.dotnetsupport.lang

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.msbuild.FrameworkDefaults

/**
 * COMPLETION of preprocessor directives (task 2.3 of docs/COMPLETION_GAPS.md, Rider's dump 36): `#` at the start of a line → the
 * directives; `#if ` / `#elif ` (and after `&&`, `||`, `!`, `(`) / `#define ` / `#undef ` → the symbols of the project (`DefineConstants`
 * of its configuration with the SDK's implicit ones, of every target framework, `#define`s of the file); `#nullable ` → `enable disable
 * restore`, then `annotations warnings`; `#pragma ` → `warning checksum`; `#pragma warning ` → `disable restore`; `#pragma warning disable `
 * → warning codes: those the solution already names in `#pragma`s and `NoWarn`, then the common ones of the compiler with their titles.
 * By the text of the line, so on both trees.
 */
class CSharpPreprocessorCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? CSharpFile ?: return
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, file.project)) return
        if (CSharpLeaves.STRINGS.contains(parameters.position.elementType)) return
        val text = parameters.editor.document.charsSequence
        val offset = parameters.offset
        val line = text.subSequence(CSharpPreprocessor.lineStart(text, offset), offset).toString()
        val items = CSharpPreprocessor.items(line, file, text) ?: return
        val set = result.withPrefixMatcher(CSharpPreprocessor.prefix(line))
        items.forEach(set::addElement)
        result.stopHere()
    }
}

object CSharpPreprocessor {
    private val DIRECTIVE = Regex("""^\s*#\s*([A-Za-z]*)$""")
    private val CONDITION = Regex("""^\s*#\s*(if|elif)\b(.*)$""")
    private val DEFINE = Regex("""^\s*#\s*(define|undef)\s+\w*$""")
    private val NULLABLE = Regex("""^\s*#\s*nullable\s+\w*$""")
    private val NULLABLE_TARGET = Regex("""^\s*#\s*nullable\s+(enable|disable|restore)\s+\w*$""")
    private val PRAGMA = Regex("""^\s*#\s*pragma\s+\w*$""")
    private val PRAGMA_WARNING = Regex("""^\s*#\s*pragma\s+warning\s+\w*$""")
    private val PRAGMA_CODES = Regex("""^\s*#\s*pragma\s+warning\s+(disable|restore)\b([^/]*)$""")
    private val CODE = Regex("""\b((?:CS|CA|IDE|NU|SYSLIB|RS|xUnit|NUnit|MSTEST)\d{3,5})\b""")

    /** Rider's order: the two templates first, then the rest alphabetically. */
    val DIRECTIVES = listOf("if", "region", "define", "elif", "else", "endif", "endregion", "error", "line", "nullable", "pragma", "undef", "warning")
    private val WITH_ARGUMENT = setOf("if", "region", "define", "elif", "error", "line", "nullable", "pragma", "undef", "warning")
    private val POPUP_AFTER = setOf("if", "elif", "define", "undef", "nullable", "pragma")

    fun lineStart(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    /** What is typed of the word at the caret. */
    fun prefix(line: String): String = line.takeLastWhile { it.isLetterOrDigit() || it == '_' }

    /** The items for the [line] up to the caret, null where the line is no directive. */
    fun items(line: String, file: PsiFile, text: CharSequence): List<LookupElement>? {
        DIRECTIVE.matchEntire(line)?.let { return DIRECTIVES.mapIndexed { i, name -> directive(name, DIRECTIVES.size - i) } }
        CONDITION.matchEntire(line)?.let { match ->
            val condition = match.groupValues[2]
            if (condition.isNotEmpty() && !condition[0].isWhitespace() && condition[0] != '(' && condition[0] != '!') return null
            return symbols(file, text) + listOf("true", "false").map { keyword(it, 0.0) }
        }
        if (DEFINE.matches(line)) return symbols(file, text)
        if (NULLABLE.matches(line)) return listOf("enable", "disable", "restore").mapIndexed { i, it -> keyword(it, 3.0 - i, popup = true) }
        if (NULLABLE_TARGET.matches(line)) return listOf("annotations", "warnings").mapIndexed { i, it -> keyword(it, 2.0 - i) }
        if (PRAGMA.matches(line)) return listOf(keyword("warning", 2.0, popup = true), keyword("checksum", 1.0))
        if (PRAGMA_WARNING.matches(line)) return listOf(keyword("disable", 2.0, popup = true), keyword("restore", 1.0, popup = true))
        PRAGMA_CODES.matchEntire(line)?.let { match ->
            val written = CODE.findAll(match.groupValues[2]).map { it.value }.toSet()
            return codes(file, text).filter { it.first !in written }.mapIndexed { i, (code, title) ->
                val element = LookupElementBuilder.create(code).withIcon(AllIcons.General.InspectionsEye).withTypeText(title ?: "", true)
                PrioritizedLookupElement.withPriority(element, 1000.0 - i)
            }
        }
        return null
    }

    private fun directive(name: String, priority: Int): LookupElement {
        val handler = InsertHandler<LookupElement> { context, _ ->
            if (name !in WITH_ARGUMENT) return@InsertHandler
            val document = context.document
            val tail = context.tailOffset
            if (document.charsSequence.getOrNull(tail) != ' ') document.insertString(tail, " ")
            context.editor.caretModel.moveToOffset(tail + 1)
            if (name in POPUP_AFTER) AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
        }
        val element = LookupElementBuilder.create(name).withPresentableText("#$name").bold().withInsertHandler(handler)
        return PrioritizedLookupElement.withPriority(element, priority.toDouble())
    }

    private fun keyword(word: String, priority: Double, popup: Boolean = false): LookupElement {
        var element = LookupElementBuilder.create(word).bold()
        if (popup) element = element.withInsertHandler { context, _ ->
            val tail = context.tailOffset
            if (context.document.charsSequence.getOrNull(tail) != ' ') context.document.insertString(tail, " ")
            context.editor.caretModel.moveToOffset(tail + 1)
            AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
        }
        return PrioritizedLookupElement.withPriority(element, priority)
    }

    /**
     * The symbols of conditional compilation: those defined for the file now (bold, first), those of the other frameworks and
     * configurations of its project, the `#define`s of the file; without a project the usual `DEBUG`, `TRACE`.
     */
    private fun symbols(file: PsiFile, text: CharSequence): List<LookupElement> {
        val virtualFile = file.virtualFile ?: file.originalFile.virtualFile
        val options = virtualFile?.let { CompilationModel.getInstance(file.project).optionsFor(it) }
        val defined = LinkedHashSet<String>()
        options?.defineConstants?.let(defined::addAll)
        Regex("""(?m)^\s*#\s*define\s+(\w+)""").findAll(text).forEach { defined += it.groupValues[1] }
        val other = LinkedHashSet<String>()
        options?.targetFrameworks?.forEach { tfm -> other += FrameworkDefaults.implicitDefines(FrameworkDefaults.parse(tfm)) }
        other += listOf("DEBUG", "TRACE", "RELEASE")
        other.removeAll(defined)
        val out = ArrayList<LookupElement>()
        for ((i, symbol) in defined.withIndex()) {
            out += PrioritizedLookupElement.withPriority(LookupElementBuilder.create(symbol).bold().withIcon(AllIcons.Nodes.Constant).withTypeText("defined", true), 2000.0 - i)
        }
        for ((i, symbol) in other.withIndex()) {
            out += PrioritizedLookupElement.withPriority(LookupElementBuilder.create(symbol).withIcon(AllIcons.Nodes.Constant), 1000.0 - i)
        }
        return out
    }

    /** The warning codes: named by the solution (`#pragma` lines, `NoWarn` of the project), then [COMMON]; with the title where known. */
    private fun codes(file: PsiFile, text: CharSequence): List<Pair<String, String?>> {
        val seen = LinkedHashSet<String>()
        CODE.findAll(pragmaLines(text)).forEach { seen += it.value }
        seen += solutionCodes(file.project)
        val virtualFile = file.virtualFile ?: file.originalFile.virtualFile
        val projectFile = virtualFile?.let { CompilationModel.getInstance(file.project).projectOf(it) }
        projectFile?.let { FileDocumentManager.getInstance().getDocument(it)?.charsSequence }?.let { project ->
            Regex("""<NoWarn>([^<]*)</NoWarn>""").findAll(project).forEach { match -> CODE.findAll(match.groupValues[1]).forEach { seen += it.value } }
        }
        val titles = COMMON.toMap()
        return seen.map { it to titles[it] } + COMMON.filter { it.first !in seen }
    }

    private fun pragmaLines(text: CharSequence): String =
        Regex("""(?m)^\s*#\s*pragma\s+warning\s+(?:disable|restore)\b.*$""").findAll(text).joinToString("\n") { it.value }

    /** The codes in `#pragma warning` lines of the solution's files that mention `pragma` (the word index finds them). */
    private fun solutionCodes(project: Project): Set<String> = CachedValuesManager.getManager(project).getCachedValue(project) {
        val codes = LinkedHashSet<String>()
        try {
            PsiSearchHelper.getInstance(project).processAllFilesWithWord("pragma", GlobalSearchScope.projectScope(project), { file ->
                if (file is CSharpFile) CODE.findAll(pragmaLines(file.viewProvider.contents)).forEach { codes += it.value }
                codes.size < 200
            }, true)
        } catch (_: IndexNotReadyException) {
        }
        CachedValueProvider.Result.create(codes as Set<String>, PsiModificationTracker.MODIFICATION_COUNT)
    }

    /** Warnings of the compiler that code turns off most often. */
    val COMMON: List<Pair<String, String>> = listOf(
        "CS0168" to "The variable is declared but never used",
        "CS0169" to "The field is never used",
        "CS0219" to "The variable is assigned but its value is never used",
        "CS0414" to "The field is assigned but its value is never used",
        "CS0649" to "Field is never assigned to, and will always have its default value",
        "CS0067" to "The event is never used",
        "CS0162" to "Unreachable code detected",
        "CS0612" to "Member is obsolete",
        "CS0618" to "Member is obsolete (with a message)",
        "CS0108" to "Member hides inherited member; missing new keyword",
        "CS0114" to "Member hides inherited member; missing override keyword",
        "CS0659" to "Type overrides Equals but not GetHashCode",
        "CS0660" to "Type defines operator == but does not override Equals",
        "CS0661" to "Type defines operator == but does not override GetHashCode",
        "CS1570" to "XML comment has badly formed XML",
        "CS1573" to "Parameter has no matching param tag in the XML comment",
        "CS1574" to "XML comment has cref attribute that could not be resolved",
        "CS1591" to "Missing XML comment for publicly visible type or member",
        "CS1998" to "Async method lacks 'await' operators and will run synchronously",
        "CS4014" to "Call is not awaited",
        "CS8019" to "Unnecessary using directive",
        "CS8600" to "Converting null literal or possible null value to non-nullable type",
        "CS8601" to "Possible null reference assignment",
        "CS8602" to "Dereference of a possibly null reference",
        "CS8603" to "Possible null reference return",
        "CS8604" to "Possible null reference argument",
        "CS8618" to "Non-nullable member must contain a non-null value when exiting constructor",
        "CS8625" to "Cannot convert null literal to non-nullable reference type",
        "CS8509" to "The switch expression does not handle all possible values",
        "CS8632" to "The annotation for nullable reference types should only be used in a '#nullable' context",
        "CS0105" to "The using directive appeared previously in this namespace",
        "CS1717" to "Assignment made to same variable",
        "CS0436" to "The type conflicts with an imported type",
        "CS8981" to "The type name only contains lower-cased ascii characters",
    )
}

/** Opens the list after `#` at the start of a line, as Rider does (dump 36): the directives. */
class CSharpPreprocessorTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != '#' || file !is CSharpFile) return Result.CONTINUE
        val text = editor.document.charsSequence
        val offset = editor.caretModel.offset
        if (text.subSequence(CSharpPreprocessor.lineStart(text, offset), offset).isNotBlank()) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.CONTINUE
    }
}
