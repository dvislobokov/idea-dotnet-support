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
import io.github.dotnetsupport.lang.semantic.CSharpReachability
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * Extract Method of Rider's Refactor This on csharp-psi's tree and the semantics of the plugin (CSHARP_PSI_MIGRATION.md, task C4d),
 * without the language server. The selection is statements of one block or one expression. The data flow of the locals decides the
 * signature: what is declared outside and read inside comes in as a parameter; one variable written inside and used after the selection
 * is returned (declared inside: `var x = M(...)`, else `x = M(...)`), more of them declared outside go by `out` (`ref` when read inside
 * too); `await` inside makes the method `async Task` / `async Task<T>`; it is `static` when nothing of the instance is used. Inside a
 * loop "after" includes the rest of the loop and the next iteration (the condition, the code before the selection, the selection's own
 * reads); a variable written on some paths only comes in as well. One kind of `break` / `continue` / `return;` leaving the selection
 * becomes `if (M(...)) break;` (or `M(...); break;` when every path jumps), as in Rider. Not extracted (a hint says why): `return` with a
 * value, `yield`, `goto`, two kinds of jumps, a jump together with a value to return; a local function of the member; two variables
 * declared inside and used after it.
 */
object NativeCSharpExtractMethod {
    class Parameter(val name: String, val type: String, val modifier: String)

    /** [declare]: declared outside and written before it is read in the selection: the method declares it itself. */
    class Output(val name: String, val type: String, val declaredInside: Boolean, val declare: Boolean = false)

    class Extraction(
        val file: CSharpFile, val member: CSharpMemberDeclaration, val range: TextRange, val expression: CSharpExpression?, val parameters: List<Parameter>,
        val returned: Output?, val returnType: String, val isStatic: Boolean, val isAsync: Boolean, val typeParameters: String, val constraints: String,
        val typeArguments: String, val name: String, val usings: Set<String>,
        /** `break` / `continue` / `return` the call makes when the method says so ([jumps] become `return true;`, or `return;` when [endReachable] is false). */
        val jump: String? = null, val jumps: List<TextRange> = emptyList(), val endReachable: Boolean = false, val endsWithJump: Boolean = false,
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
        val exits = when (val flow = controlFlow(nodes, range)) {
            is Flow.Problem -> return Result.Error(flow.message)
            is Flow.Exits -> flow
        }

        val memberIsStatic = member.modifiers.any { it.text == "static" }
        var instance = false
        val accesses = LinkedHashMap<LocalSymbol, Access>()
        val uses = HashMap<LocalSymbol, MutableList<Use>>()
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
                        uses.getOrPut(symbol) { ArrayList() } += use(name, access, nodes)
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
        // the loops around the selection: what the selection writes is read again by the next iteration
        val loops = loopsAround(nodes.first(), member)
        fun carriedBy(symbol: LocalSymbol) = loops.filter { loop -> loopBody(loop)?.textRange?.contains(symbol.declaration.textRange) != true }
        fun incoming(symbol: LocalSymbol): Boolean {
            val list = uses[symbol].orEmpty()
            return list.any { read -> read.read && list.none { it.definite && it.writeAt <= read.readAt } }
        }
        fun usedLater(symbol: LocalSymbol): Boolean {
            val carried = carriedBy(symbol)
            return syntax.references(symbol).any { ref ->
                ref.textRange.startOffset >= end || !range.contains(ref.textRange) && carried.any { it.textRange.contains(ref.textRange) }
            } || carried.isNotEmpty() && incoming(symbol)
        }
        val writtenOutside = accesses.filter { (s, a) -> a.write && usedLater(s) }.keys.toList()
        if (expression != null && (usedAfterInside.isNotEmpty() || writtenOutside.isNotEmpty())) return Result.Error("The expression assigns variables used after it")
        if (usedAfterInside.size > 1) return Result.Error("More than one variable declared in the selection is used after it: ${usedAfterInside.joinToString { it.name }}")
        // an output written on some paths only keeps its old value on the others: it comes in too
        fun comesIn(symbol: LocalSymbol) = incoming(symbol) || symbol in writtenOutside && uses[symbol].orEmpty().none { it.definite }

        val jump = exits.kind
        if (jump != null) {
            if (statements.all { it in exits.statements }) return Result.Error("The selection only leaves by '$jump': there is nothing to extract")
            // the method returns whether to jump: what it assigns goes back by `ref`, which needs no assignment on the jumping paths
            usedAfterInside.firstOrNull()?.let { return Result.Error("The selection leaves by '${jump}' and declares '${it.name}' used after it: a method cannot return both") }
            writtenOutside.firstOrNull { !comesIn(it) }?.let { return Result.Error("The selection leaves by '${jump}' and assigns '${it.name}' used after it without reading it: a method cannot return both") }
        }
        val returnedSymbol = if (jump != null) null else usedAfterInside.firstOrNull() ?: writtenOutside.firstOrNull()
        val returned = returnedSymbol?.let { s ->
            Output(s.name, typeOf(s, writer, semantic) ?: return Result.Error("Cannot infer the type of '${s.name}'"), s in usedAfterInside, s !in usedAfterInside && !comesIn(s))
        }
        val parameters = ArrayList<Parameter>()
        for (symbol in accesses.keys) {
            val reads = comesIn(symbol)
            val isOutput = symbol in writtenOutside && symbol != returnedSymbol
            // the returned variable comes in only when its value is read before it is written
            if (symbol == returnedSymbol && !reads) continue
            val type = typeOf(symbol, writer, semantic) ?: return Result.Error("Cannot infer the type of '${symbol.name}'")
            val modifier = if (isOutput) (if (reads) "ref" else "out") else ""
            parameters += Parameter(symbol.name, type, modifier)
        }

        val isAsync = awaits(nodes, range)
        val endReachable = jump != null && statements.isNotEmpty() && CSharpReachability(semantic).endOf(statements.last()) != CSharpReachability.Reach.NO
        var returnType = when {
            expression != null -> {
                val type = semantic.typeOf(expression) ?: return Result.Error("Cannot infer the type of the expression")
                writer.type(type) ?: return Result.Error("Cannot infer the type of the expression")
            }
            returned != null -> returned.type
            jump != null && endReachable -> "bool"
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
            expression, parameters, returned, returnType, memberIsStatic || !instance, isAsync, typeParameters, constraints, typeArguments, name, writer.usings,
            jump, exits.statements.map { it.textRange }, endReachable, statements.lastOrNull()?.let { it in exits.statements } == true))
    }

    /** A read and / or a write of a variable of the member in the selection; [definite]: a write every path through the selection makes. */
    private class Use(val read: Boolean, val readAt: Int, val writeAt: Int, val definite: Boolean)

    private fun use(name: CSharpSimpleName, access: Access, roots: List<PsiElement>): Use {
        var expression: PsiElement = name
        while (expression.parent is CSharpParenthesizedExpression) expression = expression.parent
        val parent = expression.parent
        // the write takes place when the whole assignment / the call with `out x` is done: `x = x + 1` reads first
        val writer: PsiElement = when (parent) {
            is CSharpArgument -> (parent.parent?.parent as? CSharpExpression) ?: parent
            else -> parent ?: name
        }
        val definite = access.write && unconditional(writer, roots)
        return Use(access.read, name.textRange.startOffset, writer.textRange.endOffset, definite)
    }

    /** Whether [element] runs every time the selection does: no condition, loop, `try`, `?.` or lambda between them. */
    private fun unconditional(element: PsiElement, roots: List<PsiElement>): Boolean {
        var at: PsiElement = element
        while (at !in roots) {
            val parent = at.parent ?: return false
            when (parent) {
                is CSharpAssignmentExpression, is CSharpParenthesizedExpression, is CSharpExpressionStatement, is CSharpArgument, is CSharpArgumentList, is CSharpBlock,
                is CSharpInvocationExpression, is CSharpEqualsValueClause, is CSharpVariableDeclarator, is CSharpVariableDeclaration, is CSharpLocalDeclarationStatement -> Unit
                else -> return false
            }
            at = parent
        }
        return true
    }

    /** The loops around [element] in [member] whose body holds it (not the ones whose condition or header does). */
    private fun loopsAround(element: PsiElement, member: PsiElement): List<CSharpStatement> {
        val result = ArrayList<CSharpStatement>()
        var at: PsiElement? = element.parent
        while (at != null && at != member) {
            if (at is CSharpAnonymousFunctionExpression || at is CSharpLocalFunctionStatement) break
            if (at is CSharpStatement && loopBody(at)?.textRange?.contains(element.textRange) == true) result += at
            at = at.parent
        }
        return result
    }

    private fun loopBody(loop: PsiElement): CSharpStatement? = when (loop) {
        is CSharpWhileStatement -> loop.statement
        is CSharpDoStatement -> loop.statement
        is CSharpForStatement -> loop.statement
        is CSharpCommonForEachStatement -> loop.statement
        else -> null
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

    private sealed class Flow {
        class Problem(val message: String) : Flow()

        /** The statements leaving the selection, all of one [kind] (`break`, `continue` or `return` without a value), as Rider's `if (M()) break;`. */
        class Exits(val kind: String?, val statements: List<CSharpStatement>) : Flow()
    }

    /**
     * The jumps out of the selection. One kind of `break` / `continue` to a loop (or a `switch`) around it, or `return;` of a function that
     * returns nothing, is kept: the method tells the call to jump. A `return` with a value, `yield`, `goto` and two kinds at once are not.
     */
    private fun controlFlow(nodes: List<PsiElement>, range: TextRange): Flow {
        val exits = ArrayList<CSharpStatement>()
        for (node in nodes) {
            val all = PsiTreeUtil.findChildrenOfAnyType(node, false, CSharpReturnStatement::class.java, CSharpYieldStatement::class.java, CSharpGotoStatement::class.java,
                CSharpBreakStatement::class.java, CSharpContinueStatement::class.java)
            for (statement in all) {
                if (insideNestedFunction(statement, node)) continue
                when (statement) {
                    is CSharpReturnStatement -> when {
                        statement.expression != null -> return Flow.Problem("The selection contains 'return' with a value: the method would not return from the member")
                        !returnsNothing(nodes.first()) -> return Flow.Problem("The selection contains 'return' of a function whose result is not known")
                        else -> exits += statement
                    }
                    is CSharpYieldStatement -> return Flow.Problem("The selection contains 'yield'")
                    is CSharpGotoStatement -> return Flow.Problem("The selection contains 'goto'")
                    else -> if (!targetInside(statement, range)) exits += statement
                }
            }
        }
        val kinds = exits.map(::jumpKind).distinct()
        if (kinds.size > 1) return Flow.Problem("The selection leaves by ${kinds.joinToString(" and ") { "'$it'" }}: a method can tell the call only one way out")
        return Flow.Exits(kinds.firstOrNull(), exits)
    }

    private fun jumpKind(statement: CSharpStatement): String = when (statement) {
        is CSharpBreakStatement -> "break"
        is CSharpContinueStatement -> "continue"
        else -> "return"
    }

    /** Whether the function around [element] is one `return;` may leave: a `void` method / local function, `async Task`, a constructor, a setter. */
    private fun returnsNothing(element: PsiElement): Boolean {
        var at: PsiElement? = element.parent
        while (at != null && at !is PsiFile) {
            when (at) {
                is CSharpAnonymousFunctionExpression -> return false
                is CSharpLocalFunctionStatement -> return voidResult(at.returnType?.text, at.modifiers)
                is CSharpAccessorDeclaration -> return at.keyword?.text in setOf("set", "init", "add", "remove")
                is CSharpMethodDeclaration -> return voidResult(at.returnType?.text, at.modifiers)
                is CSharpConstructorDeclaration, is CSharpDestructorDeclaration -> return true
                is CSharpMemberDeclaration -> return false
            }
            at = at.parent
        }
        return false
    }

    private fun voidResult(type: String?, modifiers: List<PsiElement>): Boolean =
        type == "void" || modifiers.any { it.text == "async" } && type?.substringAfterLast('.') in setOf("Task", "ValueTask")

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

    /** Whether the use writes the variable (`x = `, `x++`, `ref x`, `out x`). */
    internal fun isWritten(name: CSharpSimpleName): Boolean = access(name).write

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
            val jump = extraction.jump
            replacement = when {
                jump == null -> "$prefix$awaited;"
                extraction.endReachable -> "if ($awaited) $jump;"
                else -> "$awaited;\n${NativeCSharpUsingEdits.indentOf(text, extraction.range.startOffset).orEmpty()}$jump;"
            }
            callNameAt = replacement.indexOf(name, prefix.length)
            val statements = jumpsReturned(selected, extraction)
            body = (returned?.takeIf { it.declare }?.let { "$bodyIndent${it.type} ${it.name};\n" } ?: "") + bodyIndent + relines(statements, text, extraction.range.startOffset, bodyIndent) +
                (returned?.let { "\n${bodyIndent}return ${it.name};" } ?: "") + (if (jump != null && extraction.endReachable) "\n${bodyIndent}return false;" else "")
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

    /**
     * The selected text with each jump out of it replaced by the method's answer: `return true;`, or `return;` when every path jumps (then a
     * jump closing the selection is dropped: the call jumps after it anyway).
     */
    private fun jumpsReturned(selected: String, extraction: Extraction): String {
        if (extraction.jump == null) return selected
        val start = extraction.range.startOffset
        var result = selected
        for (jump in extraction.jumps.sortedByDescending { it.startOffset }) {
            val from = jump.startOffset - start
            val to = jump.endOffset - start
            result = if (!extraction.endReachable && extraction.endsWithJump && jump.endOffset == extraction.range.endOffset) result.substring(0, from).trimEnd() + result.substring(to)
            else result.substring(0, from) + (if (extraction.endReachable) "return true;" else "return;") + result.substring(to)
        }
        return result
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
