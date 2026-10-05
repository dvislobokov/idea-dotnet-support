package io.github.dotnetsupport.lang

import com.intellij.codeInsight.template.TemplateBuilderImpl
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.actions.ExtractMethodAction
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.idea.ActionsBundle
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * Extract Method of Rider's Refactor This on csharp-psi's tree and the semantics of the plugin (CSHARP_PSI_MIGRATION.md, task C4d),
 * without the language server. The selection is statements of one block or one expression. The data flow of the locals decides the
 * signature: what is declared outside and read inside comes in as a parameter; one variable written inside and used after the selection
 * is returned (declared inside: `var x = M(...)`, else `x = M(...)`), more of them declared outside go by `out` (`ref` when read inside
 * too); `await` inside makes the method `async Task` / `async Task<T>`; it is `static` when nothing of the instance is used. Not
 * extracted (a hint says why): a `return`, `yield`, `goto`, or `break` / `continue` that leaves the selection; a local function of the
 * member; two variables declared inside and used after it.
 */
object NativeCSharpExtractMethod {
    class Parameter(val name: String, val type: String, val modifier: String)

    /** [declare]: declared outside and written before it is read in the selection: the method declares it itself. */
    class Output(val name: String, val type: String, val declaredInside: Boolean, val declare: Boolean = false)

    class Extraction(
        val file: CSharpFile, val member: CSharpMemberDeclaration, val range: TextRange, val expression: CSharpExpression?, val parameters: List<Parameter>,
        val returned: Output?, val returnType: String, val isStatic: Boolean, val isAsync: Boolean, val typeParameters: String, val constraints: String,
        val typeArguments: String, val name: String, val usings: Set<String>,
    )

    sealed class Result {
        class Ok(val extraction: Extraction) : Result()
        class Error(val message: String) : Result()
    }

    private class Access(var read: Boolean = false, var write: Boolean = false)

    fun analyze(file: CSharpFile, selection: TextRange): Result {
        if (file.compilationUnit == null) return Result.Error("Extract Method needs the native C# tree")
        val text = file.text
        var start = selection.startOffset
        var end = selection.endOffset
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) return Result.Error("Select statements or an expression to extract")
        val range = TextRange(start, end)
        val expression = selectedExpression(file, range)
        val statements = if (expression == null) selectedStatements(file, range) else emptyList()
        if (expression == null && statements.isEmpty()) return Result.Error("Select whole statements of one block or one expression")
        // an expression statement selected as its expression: extracted as a statement when it has no value
        val root: PsiElement = expression ?: statements.first()
        val member = memberOf(root) ?: return Result.Error("Extract Method works inside a member of a type")
        val owner = member.parent as? CSharpTypeDeclaration ?: return Result.Error("Extract Method works inside a member of a type")
        val semantic = CSharpSemanticSession(file.project).resolver(file)
        val writer = CSharpCodeWriter(semantic, member, CSharpGenerateSite.nullableContext(file, start))
        val nodes: List<PsiElement> = expression?.let(::listOf) ?: statements
        controlFlowProblem(nodes, range)?.let { return Result.Error(it) }

        val memberIsStatic = member.modifiers.any { it.text == "static" }
        var instance = false
        val accesses = LinkedHashMap<LocalSymbol, Access>()
        val firstAccessIsRead = HashMap<LocalSymbol, Boolean>()
        var usesTypeParameters = false
        val syntax = semantic.syntax
        for (node in nodes) for (name in PsiTreeUtil.findChildrenOfType(node, CSharpSimpleName::class.java) + listOfNotNull(node as? CSharpSimpleName)) {
            val leaf = name.identifier ?: continue
            val symbol = syntax.symbolAt(leaf)
            if (symbol != null) {
                if (range.contains(symbol.declaration.textRange)) continue
                when (symbol.kind) {
                    LocalSymbolKind.LOCAL, LocalSymbolKind.PARAMETER -> {
                        val access = access(name)
                        val known = accesses.getOrPut(symbol) { Access() }
                        if (symbol !in firstAccessIsRead) firstAccessIsRead[symbol] = access.read
                        known.read = known.read || access.read
                        known.write = known.write || access.write
                    }
                    LocalSymbolKind.LOCAL_FUNCTION -> return Result.Error("The selection calls the local function '${symbol.name}' of the member")
                    LocalSymbolKind.TYPE_PARAMETER -> if (PsiTreeUtil.isAncestor(member, symbol.declaration, true)) usesTypeParameters = true
                    LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER -> instance = true
                    LocalSymbolKind.LABEL -> return Result.Error("The selection jumps to a label")
                }
                continue
            }
            if (!instance && usesInstance(name, semantic)) instance = true
        }
        if (!instance && nodes.any { node -> node is CSharpInstanceExpression || PsiTreeUtil.findChildrenOfType(node, CSharpInstanceExpression::class.java).isNotEmpty() }) instance = true

        // symbols declared inside and used after
        val declaredInside = syntax.let { NativeCSharpScopes.of(file).symbols.filter { range.contains(it.declaration.textRange) && it.kind == LocalSymbolKind.LOCAL } }
        val usedAfterInside = declaredInside.filter { s -> syntax.references(s).any { it.textRange.startOffset >= end } }
        val writtenOutside = accesses.filter { (s, a) -> a.write && syntax.references(s).any { it.textRange.startOffset >= end } }.keys.toList()
        if (expression != null && (usedAfterInside.isNotEmpty() || writtenOutside.isNotEmpty())) return Result.Error("The expression assigns variables used after it")
        if (usedAfterInside.size > 1) return Result.Error("More than one variable declared in the selection is used after it: ${usedAfterInside.joinToString { it.name }}")

        val returnedSymbol = usedAfterInside.firstOrNull() ?: writtenOutside.firstOrNull()
        val returned = returnedSymbol?.let { s ->
            Output(s.name, typeOf(s, writer, semantic) ?: return Result.Error("Cannot infer the type of '${s.name}'"), s in usedAfterInside, s !in usedAfterInside && firstAccessIsRead[s] != true)
        }
        val parameters = ArrayList<Parameter>()
        for ((symbol, access) in accesses) {
            val firstRead = firstAccessIsRead[symbol] == true
            val isOutput = symbol in writtenOutside && symbol != returnedSymbol
            // the returned variable comes in only when its value is read before it is written
            if (symbol == returnedSymbol && !firstRead) continue
            val type = typeOf(symbol, writer, semantic) ?: return Result.Error("Cannot infer the type of '${symbol.name}'")
            val modifier = if (isOutput) (if (firstRead) "ref" else "out") else ""
            parameters += Parameter(symbol.name, type, modifier)
        }

        val isAsync = awaits(nodes, range)
        var returnType = when {
            expression != null -> {
                val type = semantic.typeOf(expression) ?: return Result.Error("Cannot infer the type of the expression")
                writer.type(type) ?: return Result.Error("Cannot infer the type of the expression")
            }
            returned != null -> returned.type
            else -> "void"
        }
        if (isAsync) {
            val task = writer.named("System.Threading.Tasks.Task")
            returnType = if (returnType == "void") task else "$task<$returnType>"
        }
        val method = member as? CSharpMethodDeclaration
        val typeParameters = if (usesTypeParameters) method?.typeParameterList?.text.orEmpty() else ""
        val constraints = if (usesTypeParameters) method?.constraintClauses.orEmpty().joinToString("") { " " + CSharpStubsText.collapse(it.text) } else ""
        val typeArguments = if (usesTypeParameters) method?.typeParameterList?.parameters.orEmpty().joinToString(", ", "<", ">") { it.identifier?.text.orEmpty() }.takeIf { it != "<>" }.orEmpty() else ""
        val taken = HashSet<String>()
        for (part in owner.members) CSharpDeclarationNames.nameElement(part)?.text?.let(taken::add)
        val name = CSharpVariableNames.unique("NewMethod", taken)
        return Result.Ok(Extraction(file, member, expression?.textRange ?: TextRange(statements.first().textRange.startOffset, statements.last().textRange.endOffset),
            expression, parameters, returned, returnType, memberIsStatic || !instance, isAsync, typeParameters, constraints, typeArguments, name, writer.usings))
    }

    /** The top member of a type the selection is in (a method, a property, a constructor...). */
    private fun memberOf(element: PsiElement): CSharpMemberDeclaration? {
        var at: PsiElement? = element.parent
        while (at != null && at !is PsiFile) {
            if (at is CSharpMemberDeclaration && at.parent is CSharpTypeDeclaration && at !is CSharpBaseTypeDeclaration) return at
            at = at.parent
        }
        return null
    }

    private fun selectedExpression(file: PsiFile, range: TextRange): CSharpExpression? {
        var element: PsiElement? = file.findElementAt(range.startOffset)
        var found: CSharpExpression? = null
        while (element != null && element !is PsiFile && element.textRange.startOffset == range.startOffset) {
            if (element.textRange.endOffset == range.endOffset && element is CSharpExpression) found = element
            if (element.textRange.endOffset > range.endOffset) break
            element = element.parent
        }
        found ?: return null
        // a whole expression statement is extracted as a statement; a name that is a type, an assignment target, a declaration are not values
        if (found.parent is CSharpExpressionStatement) return null
        if (found is CSharpSimpleName && NativeCSharpTypePositions.isType(found)) return null
        if ((found.parent as? CSharpAssignmentExpression)?.left == found) return null
        if (found is CSharpDeclarationExpression || found is CSharpInitializerExpression) return null
        return found
    }

    private fun selectedStatements(file: PsiFile, range: TextRange): List<CSharpStatement> {
        var element: PsiElement? = file.findElementAt(range.startOffset)
        var first: CSharpStatement? = null
        while (element != null && element !is PsiFile && element.textRange.startOffset == range.startOffset) {
            if (element is CSharpStatement && element.parent is CSharpBlock) first = element
            element = element.parent
        }
        first ?: return emptyList()
        val block = first.parent as CSharpBlock
        val all = block.statements
        val index = all.indexOf(first)
        val result = ArrayList<CSharpStatement>()
        for (i in index until all.size) {
            val statement = all[i]
            if (statement.textRange.endOffset > range.endOffset) return emptyList()
            result += statement
            if (statement.textRange.endOffset == range.endOffset) return result
        }
        return emptyList()
    }

    /** `return`, `yield`, `goto`, `break` / `continue` out of the selection: the method could not do the same. */
    private fun controlFlowProblem(nodes: List<PsiElement>, range: TextRange): String? {
        for (node in nodes) {
            val all = PsiTreeUtil.findChildrenOfAnyType(node, false, CSharpReturnStatement::class.java, CSharpYieldStatement::class.java, CSharpGotoStatement::class.java,
                CSharpBreakStatement::class.java, CSharpContinueStatement::class.java)
            for (statement in all) {
                if (insideNestedFunction(statement, node)) continue
                when (statement) {
                    is CSharpReturnStatement -> return "The selection contains 'return': the method would not return from the member"
                    is CSharpYieldStatement -> return "The selection contains 'yield'"
                    is CSharpGotoStatement -> return "The selection contains 'goto'"
                    else -> if (!targetInside(statement, range)) return "The selection contains '${statement.firstChild.text}' out of a loop it does not hold"
                }
            }
        }
        return null
    }

    private fun insideNestedFunction(element: PsiElement, root: PsiElement): Boolean {
        var at = element.parent
        while (at != null && at != root.parent) {
            if (at is CSharpAnonymousFunctionExpression || at is CSharpLocalFunctionStatement) return true
            at = at.parent
        }
        return false
    }

    private fun targetInside(statement: PsiElement, range: TextRange): Boolean {
        var at = statement.parent
        val isBreak = statement is CSharpBreakStatement
        while (at != null && at !is CSharpMemberDeclaration) {
            if (at is CSharpWhileStatement || at is CSharpDoStatement || at is CSharpForStatement || at is CSharpCommonForEachStatement || isBreak && at is CSharpSwitchStatement) {
                return range.contains(at.textRange)
            }
            at = at.parent
        }
        return false
    }

    private fun awaits(nodes: List<PsiElement>, range: TextRange): Boolean = nodes.any { node ->
        val awaits = PsiTreeUtil.findChildrenOfType(node, CSharpAwaitExpression::class.java).toList() + listOfNotNull(node as? CSharpAwaitExpression)
        val keywords = PsiTreeUtil.findChildrenOfAnyType(node, false, CSharpLocalDeclarationStatement::class.java, CSharpCommonForEachStatement::class.java, CSharpUsingStatement::class.java)
            .filter { s -> (s as? CSharpLocalDeclarationStatement)?.awaitKeyword?.textLength ?: (s as? CSharpCommonForEachStatement)?.awaitKeyword?.textLength ?: (s as? CSharpUsingStatement)?.awaitKeyword?.textLength ?: 0 > 0 }
        (awaits + keywords).any { range.contains(it.textRange) && !insideNestedFunction(it, node) }
    }

    /** How a use reads or writes the variable: `x = ` writes, `x += ` / `x++` / `ref x` both, `out x` writes. */
    private fun access(name: CSharpSimpleName): Access {
        var expression: PsiElement = name
        while (expression.parent is CSharpParenthesizedExpression) expression = expression.parent
        val parent = expression.parent
        return when {
            parent is CSharpAssignmentExpression && parent.left == expression -> Access(read = parent.operatorToken?.text != "=", write = true)
            parent is CSharpPrefixUnaryExpression && parent.operatorToken?.text.let { it == "++" || it == "--" } -> Access(true, true)
            parent is CSharpPostfixUnaryExpression && parent.operatorToken?.text.let { it == "++" || it == "--" } -> Access(true, true)
            parent is CSharpArgument && parent.refKindKeyword?.text == "out" -> Access(false, true)
            parent is CSharpArgument && parent.refKindKeyword?.text == "ref" -> Access(true, true)
            else -> Access(read = true)
        }
    }

    /** A name without a local symbol that stands for an instance member of the type (or can not be told: then it counts as one). */
    internal fun usesInstance(name: CSharpSimpleName, semantic: CSharpNameResolver): Boolean {
        val parent = name.parent
        if (parent is CSharpMemberAccessExpression && parent.nameElement == name) return false
        if (parent is CSharpQualifiedName && parent.right == name) return false
        if (parent is CSharpMemberBindingExpression) return false
        if (parent is CSharpNameColon || parent is CSharpNameEquals) return false
        if (NativeCSharpTypePositions.isType(name)) return false
        val symbols = runCatching { semantic.resolveName(name)?.symbols }.getOrNull() ?: return name.identifier?.text != "nameof"
        if (symbols.isEmpty()) return true
        return symbols.any { symbol ->
            when (symbol) {
                is CSharpSymbol.SourceMember -> symbol.member.modifiers.none { it == "static" || it == "const" } && symbol.member.nestedType == null
                is CSharpSymbol.LibraryMember -> !symbol.member.isStatic && symbol.member.kind != IndexedMemberKind.CONSTANT && symbol.member.kind != IndexedMemberKind.ENUM_MEMBER
                else -> false
            }
        }
    }

    /** The type of a local or parameter as written at its declaration, or the one the semantics infers for `var`. */
    private fun typeOf(symbol: LocalSymbol, writer: CSharpCodeWriter, semantic: CSharpNameResolver): String? {
        val declaration = symbol.declaration.parent
        val written: CSharpType? = when (declaration) {
            is CSharpParameter -> declaration.type
            is CSharpVariableDeclarator -> (declaration.parent as? CSharpVariableDeclaration)?.type
            is CSharpForEachStatement -> declaration.type
            is CSharpCatchDeclaration -> declaration.type
            else -> null
        }
        if (written != null && !(written is CSharpIdentifierName && written.identifier?.text == "var")) return CSharpStubsText.collapse(written.text)
        val inferred: SemanticType? = (declaration as? CSharpParameter)?.let(semantic::lambdaParameterType)
            ?: semantic.syntax.references(symbol).firstNotNullOfOrNull { (it.parent as? CSharpSimpleName)?.let(semantic::typeOf) }
        return writer.type(inferred)
    }

    // ---- the edit

    class Edit(val text: String, val methodNameOffset: Int, val callNameOffset: Int, val methodRange: TextRange)

    /** The text after extracting under [name]: the call in place of the selection, the method after the member, the `using`s needed. */
    fun apply(extraction: Extraction, text: String, unit: String, name: String = extraction.name): Edit {
        val member = extraction.member
        val memberIndent = NativeCSharpUsingEdits.indentOf(text, member.textRange.startOffset).orEmpty()
        val bodyIndent = memberIndent + unit
        val arguments = extraction.parameters.joinToString(", ") { listOf(it.modifier, it.name).filter(String::isNotEmpty).joinToString(" ") }
        val call = name + extraction.typeArguments + "(" + arguments + ")"
        val awaited = if (extraction.isAsync) "await $call" else call
        val selected = text.substring(extraction.range.startOffset, extraction.range.endOffset)
        val replacement: String
        val callNameAt: Int
        val body: String
        if (extraction.expression != null) {
            val parent = extraction.expression.parent
            val wrap = extraction.isAsync && (parent is CSharpMemberAccessExpression || parent is CSharpElementAccessExpression || parent is CSharpConditionalAccessExpression || parent is CSharpInvocationExpression)
            replacement = if (wrap) "($awaited)" else awaited
            callNameAt = replacement.indexOf(name)
            body = bodyIndent + "return " + relines(selected, text, extraction.range.startOffset, bodyIndent) + ";"
        } else {
            val returned = extraction.returned
            val prefix = when {
                returned == null -> ""
                returned.declaredInside -> "var ${returned.name} = "
                else -> "${returned.name} = "
            }
            replacement = "$prefix$awaited;"
            callNameAt = replacement.indexOf(name, prefix.length)
            body = (returned?.takeIf { it.declare }?.let { "$bodyIndent${it.type} ${it.name};\n" } ?: "") + bodyIndent + relines(selected, text, extraction.range.startOffset, bodyIndent) + (returned?.let { "\n${bodyIndent}return ${it.name};" } ?: "")
        }
        val modifiers = listOfNotNull("private", "static".takeIf { extraction.isStatic }, "async".takeIf { extraction.isAsync }).joinToString(" ")
        val parameters = extraction.parameters.joinToString(", ") { listOf(it.modifier, it.type, it.name).filter(String::isNotEmpty).joinToString(" ") }
        val header = "$modifiers ${extraction.returnType} $name${extraction.typeParameters}($parameters)${extraction.constraints}"
        val method = "\n\n$memberIndent$header\n$memberIndent{\n$body\n$memberIndent}"
        val memberEnd = member.textRange.endOffset
        var result = text.substring(0, memberEnd) + method + text.substring(memberEnd)
        var methodStart = memberEnd + 2
        val methodNameAt = methodStart + memberIndent.length + header.indexOf(" $name") + 1
        var methodNameOffset = methodNameAt
        // the call: before the method (the selection is inside the member)
        result = result.substring(0, extraction.range.startOffset) + replacement + result.substring(extraction.range.endOffset)
        val shift = replacement.length - extraction.range.length
        methodStart += shift
        methodNameOffset += shift
        var callNameOffset = extraction.range.startOffset + callNameAt
        var methodEnd = methodStart + method.length - 2
        for (namespace in extraction.usings) {
            if (CSharpUsings.isVisible(namespace, result)) continue
            val insertion = CSharpUsings.insertion(result, namespace) ?: continue
            result = result.substring(0, insertion.offset) + insertion.text + result.substring(insertion.offset)
            if (insertion.offset <= callNameOffset) callNameOffset += insertion.text.length
            methodStart += insertion.text.length
            methodEnd += insertion.text.length
            methodNameOffset += insertion.text.length
        }
        return Edit(result, methodNameOffset, callNameOffset, TextRange(methodStart, methodEnd))
    }

    /** The selected text with its later lines moved from their old indent to [indent] (the first line starts at the selection). */
    private fun relines(selected: String, text: String, start: Int, indent: String): String {
        val old = NativeCSharpUsingEdits.indentOf(text, start).orEmpty()
        val lines = selected.lines()
        return lines.mapIndexed { i, line ->
            when {
                i == 0 -> line
                line.isBlank() -> ""
                line.startsWith(old) -> indent + line.substring(old.length)
                else -> indent + line.trimStart()
            }
        }.joinToString("\n")
    }

    // ---- in the editor

    fun perform(project: Project, editor: Editor, file: CSharpFile) {
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val selection = editor.selectionModel.let { if (it.hasSelection()) TextRange(it.selectionStart, it.selectionEnd) else null }
        if (selection == null) {
            CommonRefactoringUtil.showErrorHint(project, editor, "Select statements or an expression to extract", TITLE, null)
            return
        }
        val result = ReadAction.compute<Result, RuntimeException> { analyze(file, selection) }
        val extraction = when (result) {
            is Result.Error -> {
                CommonRefactoringUtil.showErrorHint(project, editor, result.message, TITLE, null)
                return
            }
            is Result.Ok -> result.extraction
        }
        val documents = PsiDocumentManager.getInstance(project)
        var methodName = -1
        var callName = -1
        WriteCommandAction.writeCommandAction(project, file).withName(TITLE).run<RuntimeException> {
            val edit = apply(extraction, editor.document.text, NativeCSharpContextEdits.unit(file))
            replaceChanged(editor.document, edit.text)
            documents.commitDocument(editor.document)
            val methodMarker = editor.document.createRangeMarker(edit.methodRange)
            val nameMarker = editor.document.createRangeMarker(edit.methodNameOffset, edit.methodNameOffset + extraction.name.length)
            val callMarker = editor.document.createRangeMarker(edit.callNameOffset, edit.callNameOffset + extraction.name.length)
            if (NativeCSharpFormatting.engaged(file)) {
                runCatching { CodeStyleManager.getInstance(project).reformatText(file, methodMarker.startOffset, methodMarker.endOffset) }
                documents.doPostponedOperationsAndUnblockDocument(editor.document)
                documents.commitDocument(editor.document)
            }
            methodName = nameMarker.startOffset
            callName = callMarker.startOffset
            editor.selectionModel.removeSelection()
            editor.caretModel.moveToOffset(callName)
            listOf(methodMarker, nameMarker, callMarker).forEach { it.dispose() }
        }
        if (ApplicationManager.getApplication().isUnitTestMode || methodName < 0) return
        rename(project, editor, file, methodName, callName, extraction.name)
    }

    /** Replaces only what differs between the document and [text]: the rest of the file keeps its markers and folding. */
    private fun replaceChanged(document: com.intellij.openapi.editor.Document, text: String) {
        val old = document.charsSequence
        var prefix = 0
        val max = minOf(old.length, text.length)
        while (prefix < max && old[prefix] == text[prefix]) prefix++
        var suffix = 0
        while (suffix < max - prefix && old[old.length - 1 - suffix] == text[text.length - 1 - suffix]) suffix++
        document.replaceString(prefix, old.length - suffix, text.substring(prefix, text.length - suffix))
    }

    /** The name of the new method edited at the call and the declaration together, as Rider's inplace rename after the refactoring. */
    private fun rename(project: Project, editor: Editor, file: CSharpFile, declarationAt: Int, callAt: Int, name: String) {
        val declaration = file.findElementAt(declarationAt) ?: return
        val call = file.findElementAt(callAt) ?: return
        if (declaration.text != name || call.text != name) return
        val container = PsiTreeUtil.findCommonParent(declaration, call) ?: return
        val builder = TemplateBuilderImpl(container)
        builder.replaceElement(call, "NAME", ConstantNode(name), true)
        builder.replaceElement(declaration, "NAME_DECLARATION", "NAME", false)
        WriteCommandAction.writeCommandAction(project, file).withName(TITLE).run<RuntimeException> {
            editor.caretModel.moveToOffset(container.textRange.startOffset)
            builder.run(editor, true)
        }
    }

    const val TITLE = "Extract Method"
}

/**
 * Refactor | Extract | Method (Ctrl+Alt+M) and the row "Extract Method..." of Refactor This: in the editor of a C# file of the native tree
 * the native refactoring ([NativeCSharpExtractMethod]); anywhere else the platform's action, unchanged.
 */
class CSharpExtractMethodAction : AnAction(), DumbAware {
    private val platform = ExtractMethodAction()

    init {
        templatePresentation.setText(ActionsBundle.actionText(ID))
        templatePresentation.description = ActionsBundle.actionDescription(ID)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun native(e: AnActionEvent): Boolean = (e.getData(CommonDataKeys.PSI_FILE) as? CSharpFile)?.compilationUnit != null && e.getData(CommonDataKeys.EDITOR) != null

    override fun update(e: AnActionEvent) {
        if (!native(e)) return platform.update(e)
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isVisible = true
        e.presentation.isEnabled = project != null && editor != null && editor.selectionModel.hasSelection() && !DumbService.isDumb(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (!native(e)) return platform.actionPerformed(e)
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) as? CSharpFile ?: return
        NativeCSharpExtractMethod.perform(project, editor, file)
    }

    companion object {
        const val ID = "ExtractMethod"
    }
}
