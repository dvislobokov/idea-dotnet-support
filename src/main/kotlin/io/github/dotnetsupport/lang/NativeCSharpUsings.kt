package io.github.dotnetsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.lang.semantic.CSharpGlobalUsingIndex
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.CSharpTypeFacts
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.lsp.RoslynServerStatus
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.dotnetsupport.msbuild.DotNetProjects

/*
 * `using` directives and `using` / `await using` statements and declarations on csharp-psi's tree (CSHARP_PSI_MIGRATION.md, task A8), the
 * part that needs no types: completion of the directives and of `using var` / `await using var`, the conversions between the statement and
 * the declaration, "Wrap in 'using' statement", "Sort 'using' directives", "Convert to 'global using'". Since 0.1.65 the types of C2 tell
 * whether a resource is `IDisposable` / `IAsyncDisposable` ([NativeCSharpUsingChecks]: CS1674 and its kin, the `using` list filtered by
 * it); "Add await" and which directive a name came through (unused directives) still wait for the semantics (C3 / D2).
 */

/** The completion of `using`: the targets of a directive, `using var` / `await using var` at a statement's start, `global using` at the top of a file. */
object NativeCSharpUsingCompletion {
    private const val MAX_TYPES = 500

    /**
     * [element] stands among the directives of the file: before it in the compilation unit only `extern alias` and `using` directives
     * (only `global using` ones when [global]: a `global using` cannot follow a plain one).
     */
    fun atUsingsOfFile(element: PsiElement, global: Boolean): Boolean {
        var top: PsiElement = element
        while (top.parent != null && top.parent !is CSharpCompilationUnit) {
            val parent = top.parent
            if (parent is CSharpFile || parent is CSharpBaseNamespaceDeclaration || parent is CSharpBaseTypeDeclaration) return false
            top = parent
        }
        if (top.parent !is CSharpCompilationUnit) return false
        var sibling = top.prevSibling
        while (sibling != null) {
            when (sibling) {
                is PsiWhiteSpace, is PsiComment -> {}
                is CSharpExternAliasDirective -> {}
                is CSharpUsingDirective -> if (global && sibling.globalKeyword == null) return false
                else -> if (sibling.textLength > 0 && !CSharpLeaves.isTrivia(sibling)) return false
            }
            sibling = sibling.prevSibling
        }
        return true
    }

    /** `using var` and, where `await` may stand (or the function can be made `async`), `await using var` — at the start of a statement in a body. */
    fun statementItems(place: NativeCSharpCompletionPlace): List<LookupElement> {
        val at = place.name ?: return emptyList()
        if (place.kind != NativeCompletionKind.STATEMENT) return emptyList()
        val result = ArrayList<LookupElement>()
        result += item("using var", async = false)
        if (NativeCSharpCommonCalls.awaitState(at) != NativeCSharpCommonCalls.AwaitState.NO) result += item("await using var", async = true)
        return result
    }

    /** `global using` at the top of a file, before anything but other `global using` directives. */
    fun topItems(place: NativeCSharpCompletionPlace): List<LookupElement> {
        val at = place.name ?: return emptyList()
        if (place.modifiers.isNotEmpty() || !atUsingsOfFile(at, global = true)) return emptyList()
        return listOf(item("global using", async = false))
    }

    private fun item(text: String, async: Boolean): LookupElement {
        val builder = LookupElementBuilder.create(text).bold().withInsertHandler(InsertHandler { context, _ ->
            if (context.completionChar != Lookup.NORMAL_SELECT_CHAR && context.completionChar != Lookup.REPLACE_SELECT_CHAR) return@InsertHandler
            val document = context.document
            val offset = context.tailOffset
            if (document.charsSequence.getOrNull(offset) != ' ') document.insertString(offset, " ")
            context.editor.caretModel.moveToOffset(offset + 1)
            context.commitDocument()
            if (async) NativeCSharpCommonCalls.makeAsyncAt(context.file, context.startOffset, context.editor)
        })
        builder.putUserData(NativeCSharpCompletion.NATIVE, true)
        return PrioritizedLookupElement.withPriority(builder, NativeCSharpCompletion.KEYWORD).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
    }

    /**
     * The targets of a directive at [place]: the namespaces under the qualifier (of the solution — the stub index — and of the assemblies
     * of the project), and after `using static` / `using X =` the types of the qualifier's namespace too; `static` as the first word.
     */
    fun directiveItems(place: NativeCSharpCompletionPlace, file: CSharpFile, matcher: PrefixMatcher): List<LookupElement> {
        val project = file.project
        val qualifier = place.usingQualifier
        val result = ArrayList<LookupElement>()
        val libraries = libraryIndexes(file)
        val namespaces = StubIndex.getInstance().getAllKeys(CSharpStubIndexKeys.NAMESPACES, project) + libraries.flatMap { it.namespaces }
        for (name in childNamespaces(qualifier, namespaces)) {
            if (!matcher.prefixMatches(name)) continue
            result += element(name, AllIcons.Nodes.Package, null, NativeCSharpCompletion.TYPE + 1, null)
        }
        if (place.usingTypes) {
            val seen = HashSet<String>()
            for (part in solutionTypesIn(file, qualifier, matcher)) if (seen.add(part.first)) result += typeElement(part.first, part.second, qualifier)
            for (index in libraries) for (type in index.typesIn(qualifier)) {
                if (type.isHidden || type.isProtected || seen.size > MAX_TYPES) continue
                val name = type.simpleName
                if (!matcher.prefixMatches(name) || !seen.add(name)) continue
                result += typeElement(name, type.arity, qualifier)
            }
        }
        if (place.usingFirst && qualifier.isEmpty()) {
            val keyword = LookupElementBuilder.create("static").bold().withInsertHandler(InsertHandler { context, _ ->
                if (context.completionChar != Lookup.NORMAL_SELECT_CHAR && context.completionChar != Lookup.REPLACE_SELECT_CHAR) return@InsertHandler
                val offset = context.tailOffset
                if (context.document.charsSequence.getOrNull(offset) != ' ') context.document.insertString(offset, " ")
                context.editor.caretModel.moveToOffset(offset + 1)
            })
            keyword.putUserData(NativeCSharpCompletion.NATIVE, true)
            result += PrioritizedLookupElement.withPriority(keyword, NativeCSharpCompletion.KEYWORD).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
        }
        return result
    }

    /** The next segment of each of [namespaces] under [qualifier]: `System.Collections.Generic` under `System` is `Collections`. */
    fun childNamespaces(qualifier: String, namespaces: Collection<String>): Set<String> {
        val result = java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER)
        val prefix = if (qualifier.isEmpty()) "" else "$qualifier."
        for (namespace in namespaces) {
            val name = namespace.removePrefix("global::")
            if (name.isEmpty() || !name.startsWith(prefix) || name.length == prefix.length) continue
            val child = name.substring(prefix.length).substringBefore('.')
            if (child.isNotEmpty()) result += child
        }
        return result
    }

    /** The types of the solution declared at the top of [namespace] whose names [matcher] takes (all when null): name and arity. */
    fun solutionTypesIn(file: CSharpFile, namespace: String, matcher: PrefixMatcher?): List<Pair<String, Int>> {
        val resolver = NativeCSharpResolver(file)
        val result = ArrayList<Pair<String, Int>>()
        for ((name, _) in NativeCSharpTypeNames.candidates(file, matcher, resolver)) {
            for (part in resolver.typeParts(name)) if (part.namespace == namespace) result += name to part.arity
        }
        return result
    }

    private fun libraryIndexes(file: CSharpFile): List<io.github.dotnetsupport.index.AssemblyIndex> {
        val virtualFile = file.originalFile.virtualFile ?: return emptyList()
        val projectFile = DotNetProjects.findOwningProject(virtualFile) ?: return emptyList()
        return AssemblyIndexService.getInstance(file.project).indexes(projectFile)
    }

    private fun typeElement(name: String, arity: Int, namespace: String): LookupElement {
        val generic = arity > 0
        val presentable = if (generic) "$name<${"".padEnd(arity - 1, ',')}>" else name
        return element(name, AllIcons.Nodes.Class, presentable, NativeCSharpCompletion.TYPE, if (namespace.isEmpty()) null else " ($namespace)",
            if (generic) NativeCSharpCalls.typeHandler(generic = true, constructed = false) else null)
    }

    private fun element(name: String, icon: javax.swing.Icon, presentable: String?, priority: Double, tail: String?, handler: InsertHandler<LookupElement>? = null): LookupElement {
        var builder = LookupElementBuilder.create(name).withIcon(icon).withPresentableText(presentable ?: name)
        if (tail != null) builder = builder.withTailText(tail, true)
        if (handler != null) builder = builder.withInsertHandler(handler)
        builder.putUserData(NativeCSharpCompletion.NATIVE, true)
        return PrioritizedLookupElement.withPriority(builder, priority).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
    }
}

/** A name for the variable of `expr.using` / `expr.awaitusing`: of the type of `new T(...)`, of the called method (`BeginTransactionAsync()` → `transaction`), else `value`. */
object CSharpUsingNames {
    private val CREATION = Regex("""^new\s+([\w.:]+(?:<[^()]*>)?)\s*[({]""")
    private val CALL = Regex("""(\w+)\s*(?:<[^()]*>)?\s*\([^()]*\)\s*$""")
    private val VERBS = listOf("Get", "Create", "Open", "Begin", "Start", "Acquire", "New", "Rent", "Build")

    fun of(expression: String): String {
        val text = expression.trim().removePrefix("await ").trim()
        CREATION.find(text)?.let { match -> CSharpVariableNames.forType(match.groupValues[1]).firstOrNull()?.let { return it } }
        CALL.find(text)?.let { match ->
            ofMethod(match.groupValues[1])?.let { name -> CSharpVariableNames.forType(name).firstOrNull()?.let { return it } }
        }
        return "value"
    }

    /** The noun a method's name makes a value of: `GetCustomerAsync` → `Customer`, `BeginTransaction` → `Transaction`; null when there is none. */
    fun ofMethod(method: String): String? {
        var name = method.removeSuffix("Async")
        VERBS.firstOrNull { name.startsWith(it) && name.length > it.length && name[it.length].isUpperCase() }?.let { name = name.substring(it.length) }
        return name.takeIf { it.firstOrNull()?.isUpperCase() == true }
    }
}

/**
 * Server code actions the plugin's own Alt+Enter actions stand for when their feature is NATIVE: the platform shows both otherwise (the
 * server's under its own title). Read by the client of the server (`RoslynCodeActionsSupport`) per action; exact titles of Roslyn.
 */
object NativeCSharpServerActions {
    private const val FIX_ALL = "Fix All: "

    private val SHADOWED = mapOf(
        "Use simple 'using' statement" to CSharpFeature.EDITING,
        "Make method async" to CSharpFeature.COMPLETION,
        // A7, the native context actions (NativeCSharpContextActions.kt)
        "Convert to conditional expression" to CSharpFeature.CONTEXT_ACTIONS,
        "Use explicit type" to CSharpFeature.CONTEXT_ACTIONS,
        "Use explicit type instead of 'var'" to CSharpFeature.CONTEXT_ACTIONS,
        "Use implicit type" to CSharpFeature.CONTEXT_ACTIONS,
        "Use 'var' instead of explicit type" to CSharpFeature.CONTEXT_ACTIONS,
        "Inline temporary variable" to CSharpFeature.CONTEXT_ACTIONS,
        // the server's row of "Convert '?:' to 'if' statement" (robot, E-76, 0.1.72: both on `return c ? a : b;`)
        "Replace conditional expression with statements" to CSharpFeature.CONTEXT_ACTIONS,
    )

    // titles with the member kind or the expression in them: "Use expression body for method", "Introduce local for 'a + b'"
    private val SHADOWED_PREFIXES = listOf(
        "Use expression body for " to CSharpFeature.CONTEXT_ACTIONS,
        "Use block body for " to CSharpFeature.CONTEXT_ACTIONS,
        "Introduce local for " to CSharpFeature.CONTEXT_ACTIONS,
        // 0.1.81: Introduce Parameter / Introduce Field of Refactor This ("Introduce parameter for all occurrences of 'x'" too)
        "Introduce parameter for " to CSharpFeature.CONTEXT_ACTIONS,
        "Introduce field for " to CSharpFeature.CONTEXT_ACTIONS,
    )

    fun shadowed(title: String?, project: Project): Boolean {
        // its "Fix All: …" row goes with it: above the plugin's row, it took the first place of the list (robot 0.1.63, "Make method async")
        val title = title?.removePrefix(FIX_ALL) ?: return false
        val feature = SHADOWED[title] ?: SHADOWED_PREFIXES.firstOrNull { title.startsWith(it.first) }?.second ?: return false
        return CSharpFeatures.native(feature, project)
    }
}

/**
 * What the types of C2 tell about `using` (task A8): CS1674 / CS8410 / CS8417 / CS8418 on a resource that is not disposable as its
 * `using` / `await using` asks ([CSharpTypeFacts.usingError]), and the `using (|` / `using var x = |` list without what is known not to
 * be disposable. Only where the type is known through and through: an unknown type, an unresolved base, a type parameter, a type that
 * may be disposable by the pattern (`DisposeAsync`, the `Dispose` of a `ref struct`) is never reported nor left out.
 */
object NativeCSharpUsingChecks {
    class Problem(val range: TextRange, val error: CSharpTypeFacts.UsingError)

    /** The errors of the `using` statements and declarations of [file], cached until the next change of PSI; none while the IDE indexes. */
    fun of(file: CSharpFile): List<Problem> {
        if (file.compilationUnit == null || DumbService.isDumb(file.project)) return emptyList()
        return CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(compute(file), PsiModificationTracker.MODIFICATION_COUNT) }
    }

    private fun compute(file: CSharpFile): List<Problem> {
        val unit = file.compilationUnit ?: return emptyList()
        // most files have no `using` statement: no resolver for them
        val resolver by lazy { CSharpSemanticSession(file.project).resolver(file) }
        val found = ArrayList<Problem>()
        PsiTreeUtil.processElements(unit) { element ->
            when (element) {
                is CSharpUsingStatement -> if (present(element.usingKeyword)) {
                    val async = present(element.awaitKeyword)
                    val declaration = element.declaration
                    val expression = element.expression
                    if (declaration != null) check(resolver, declaredType(resolver, declaration), async, declaration, found)
                    else if (expression != null) check(resolver, resolver.typeOf(expression), async, expression, found)
                }
                is CSharpLocalDeclarationStatement -> if (present(element.usingKeyword)) {
                    element.declaration?.let { check(resolver, declaredType(resolver, it), present(element.awaitKeyword), it, found) }
                }
            }
            true
        }
        return found
    }

    private fun check(resolver: CSharpNameResolver, type: SemanticType?, async: Boolean, at: PsiElement, found: MutableList<Problem>) {
        CSharpTypeFacts.usingError(resolver, type, async, at)?.let { found += Problem(at.textRange, it) }
    }

    private fun declaredType(resolver: CSharpNameResolver, declaration: CSharpVariableDeclaration): SemanticType? {
        val type = declaration.type ?: return null
        return if (resolver.isVar(type)) resolver.expressionType(type) else resolver.resolveType(type)
    }

    private fun present(token: PsiElement?): Boolean = (token?.textLength ?: 0) > 0

    /** The resource of a `using` at the completion [place] (`using (|`, `using var x = |`, `using (var x = |`): `await using` or not; null elsewhere. */
    fun resourcePlace(place: NativeCSharpCompletionPlace): Boolean? {
        if (place.kind != NativeCompletionKind.EXPRESSION) return null
        val name = place.name ?: return null
        when (val holder = name.parent) {
            is CSharpUsingStatement -> if (holder.expression == name) return present(holder.awaitKeyword)
            is CSharpEqualsValueClause -> {
                val declaration = holder.parent?.parent as? CSharpVariableDeclaration ?: return null
                return when (val owner = declaration.parent) {
                    is CSharpUsingStatement -> present(owner.awaitKeyword)
                    is CSharpLocalDeclarationStatement -> if (present(owner.usingKeyword)) present(owner.awaitKeyword) else null
                    else -> null
                }
            }
        }
        return null
    }

    /** The question of the completion at a resource: is this local or member known not to be disposable as [async] asks? */
    class Filter(file: CSharpFile, private val async: Boolean, private val site: PsiElement) {
        private val resolver = CSharpSemanticSession(file.project).resolver(file)

        fun rejects(symbol: LocalSymbol): Boolean {
            if (symbol.kind != LocalSymbolKind.LOCAL && symbol.kind != LocalSymbolKind.PARAMETER && symbol.kind != LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER) return false
            return rejects(resolver.valueType(CSharpSymbol.Local(symbol)))
        }

        fun rejects(member: Member): Boolean {
            val target = member.targets().firstOrNull() ?: return false
            // a field is told by its declarator (or its name), a property by its declaration
            val type = when (target) {
                is CSharpBasePropertyDeclaration -> target.type
                is CSharpBaseFieldDeclaration -> target.declaration?.type
                else -> PsiTreeUtil.getParentOfType(target, CSharpVariableDeclaration::class.java, false)?.type
            } ?: return false
            val owner = (type.containingFile as? CSharpFile)?.let { resolver.session.reachable(it) } ?: return false
            return rejects(owner.resolveType(type))
        }

        private fun rejects(type: SemanticType?): Boolean = CSharpTypeFacts.usingError(resolver, type, async, site) != null
    }
}

/** One replacement in the file at the caret: the range, the new text, where the caret goes (an offset in the new text). */
class CSharpTextEdit(val range: TextRange, val text: String, val caret: Int = 0)

/**
 * The edits of the `using` intentions, by the tree (pure: tested without an editor). The texts are re-indented by lines: the body of a
 * statement loses one level as a declaration, the statements after a declaration gain one inside the new block.
 */
object NativeCSharpUsingEdits {
    /** The `using` statement whose header holds [offset] (from `await` / `using` to its `)`). */
    fun usingStatementAt(file: PsiFile, offset: Int): CSharpUsingStatement? {
        for (candidate in listOf(offset, offset - 1)) {
            val leaf = file.findElementAt(candidate) ?: continue
            val statement = PsiTreeUtil.getParentOfType(leaf, CSharpUsingStatement::class.java, false) ?: continue
            val headerEnd = statement.closeParenToken?.textRange?.endOffset ?: statement.declaration?.textRange?.endOffset ?: continue
            if (offset >= statement.textRange.startOffset && offset <= headerEnd) return statement
        }
        return null
    }

    /** The local declaration statement holding [offset] (or ending right before it). */
    fun declarationAt(file: PsiFile, offset: Int): CSharpLocalDeclarationStatement? {
        for (candidate in listOf(offset, offset - 1)) {
            val leaf = file.findElementAt(candidate) ?: continue
            val statement = PsiTreeUtil.getParentOfType(leaf, CSharpLocalDeclarationStatement::class.java, false) ?: continue
            // not the declarations of a lambda's body inside the initializer
            if (PsiTreeUtil.getParentOfType(leaf, CSharpAnonymousFunctionExpression::class.java, false)?.let { PsiTreeUtil.isAncestor(statement, it, true) } == true) continue
            return statement
        }
        return null
    }

    /**
     * `using (var x = …) { body }`, the last statement of its block → `using var x = …;` and the body below it, one level less. Stacked
     * statements (`using (a) using (b) { }`) become declarations together. Only the declaration form: `using (expr)` has nothing to name.
     */
    fun toDeclaration(statement: CSharpUsingStatement, text: CharSequence): CSharpTextEdit? {
        if (!complete(statement) || statement.declaration == null) return null
        val block = statement.parent as? CSharpBlock ?: return null
        if (block.statements.lastOrNull() != statement) return null
        val headers = ArrayList<CSharpUsingStatement>()
        var body: CSharpStatement? = statement
        while (body is CSharpUsingStatement && body.declaration != null && complete(body)) {
            headers += body
            body = body.statement
        }
        body ?: return null
        val indent = indentOf(text, statement.textRange.startOffset) ?: return null
        val lines = if (body is CSharpBlock) {
            val open = body.openBraceToken?.takeIf { it.textLength > 0 } ?: return null
            val close = body.closeBraceToken?.takeIf { it.textLength > 0 } ?: return null
            contentLines(text.subSequence(open.textRange.endOffset, close.textRange.startOffset))
        } else {
            contentLines(text.subSequence(lineStart(text, body.textRange.startOffset), body.textRange.endOffset))
        }
        val result = StringBuilder()
        headers.forEachIndexed { i, header ->
            if (i > 0) result.append('\n').append(indent)
            if (header.awaitKeyword?.textLength ?: 0 > 0) result.append("await ")
            result.append("using ").append(header.declaration!!.text).append(';')
        }
        for (line in reindent(lines, indent)) result.append('\n').append(line)
        return CSharpTextEdit(statement.textRange, result.toString())
    }

    /** `using var x = …;` → `using (var x = …) { the rest of the block }`. */
    fun toStatement(declaration: CSharpLocalDeclarationStatement, text: CharSequence, unit: String): CSharpTextEdit? {
        if (declaration.usingKeyword == null || declaration.usingKeyword!!.textLength == 0) return null
        val await = (declaration.awaitKeyword?.textLength ?: 0) > 0
        return wrap(declaration, text, unit, (if (await) "await " else "") + "using")
    }

    /** `var x = new T(…);` → `using (var x = new T(…)) { the rest of the block }`. Only an object creation: whether another value is disposable is for the semantics. */
    fun wrapInUsing(declaration: CSharpLocalDeclarationStatement, text: CharSequence, unit: String): CSharpTextEdit? {
        if (declaration.usingKeyword != null || declaration.awaitKeyword != null || declaration.modifiers.isNotEmpty()) return null
        val variables = declaration.declaration?.variables.orEmpty()
        if (variables.size != 1) return null
        if (variables[0].initializer?.value !is CSharpBaseObjectCreationExpression) return null
        return wrap(declaration, text, unit, "using")
    }

    private fun wrap(declaration: CSharpLocalDeclarationStatement, text: CharSequence, unit: String, keyword: String): CSharpTextEdit? {
        val variable = declaration.declaration ?: return null
        if ((declaration.semicolonToken?.textLength ?: 0) == 0 || variable.variables.isEmpty()) return null
        val block = declaration.parent as? CSharpBlock ?: return null
        if ((block.closeBraceToken?.textLength ?: 0) == 0) return null
        val indent = indentOf(text, declaration.textRange.startOffset) ?: return null
        val statements = block.statements
        val following = statements.subList(statements.indexOf(declaration) + 1, statements.size)
        val end = following.lastOrNull()?.textRange?.endOffset ?: declaration.textRange.endOffset
        val rest = text.subSequence(declaration.textRange.endOffset, end).toString()
        // a comment on the rest of the declaration's line stays on the header's line
        val sameLine = rest.substringBefore('\n').trim().takeIf { '\n' in rest || following.isEmpty() }.orEmpty()
        val bodyText = if ('\n' in rest) rest.substringAfter('\n') else rest
        val lines = if (following.isEmpty()) emptyList() else contentLines(bodyText)
        val result = StringBuilder()
        result.append(keyword).append(" (").append(variable.text).append(')')
        if (sameLine.isNotEmpty()) result.append(' ').append(sameLine)
        result.append('\n').append(indent).append('{')
        for (line in reindent(lines, indent + unit)) result.append('\n').append(line)
        result.append('\n').append(indent).append('}')
        return CSharpTextEdit(TextRange(declaration.textRange.startOffset, end), result.toString())
    }

    private fun complete(statement: CSharpUsingStatement): Boolean =
        (statement.closeParenToken?.textLength ?: 0) > 0 && (statement.openParenToken?.textLength ?: 0) > 0 && statement.statement != null

    // ---- sort

    /** The directives of the place of [directive] (the compilation unit or a namespace) in Rider's order, when they are not in it yet. */
    fun sort(directive: CSharpUsingDirective, text: CharSequence, systemFirst: Boolean = true): CSharpTextEdit? {
        val usings = when (val owner = directive.parent) {
            is CSharpCompilationUnit -> owner.usings
            is CSharpBaseNamespaceDeclaration -> owner.usings
            else -> return null
        }
        if (usings.size < 2 || usings.any { (it.semicolonToken?.textLength ?: 0) == 0 }) return null
        // only whitespace between them: a comment or `#if` there belongs to its neighbor, and moving them apart would lose it
        for (i in 1 until usings.size) if (text.subSequence(usings[i - 1].textRange.endOffset, usings[i].textRange.startOffset).isNotBlank()) return null
        val sorted = usings.sortedWith(order(systemFirst))
        if (sorted == usings) return null
        val indent = indentOf(text, usings.first().textRange.startOffset) ?: ""
        val joined = sorted.joinToString("\n$indent") { CSharpStubsText.collapse(it.text) }
        return CSharpTextEdit(TextRange(usings.first().textRange.startOffset, usings.last().textRange.endOffset), joined)
    }

    /** Global ones first (the compiler wants them so), then namespaces, `using static`, aliases; `System` first, then the alphabet. */
    val ORDER: Comparator<CSharpUsingDirective> = order(systemFirst = true)

    /** [ORDER], with `System` among the others when [systemFirst] is off (`dotnet_sort_system_directives_first = false` of `.editorconfig`). */
    fun order(systemFirst: Boolean): Comparator<CSharpUsingDirective> = compareBy<CSharpUsingDirective> { if ((it.globalKeyword?.textLength ?: 0) > 0) 0 else 1 }
        .thenBy { if (it.alias != null) 2 else if (it.staticKeyword != null) 1 else 0 }
        .thenBy { sortKey(if (it.alias != null) it.alias!!.nameElement?.text.orEmpty() else NativeCSharpResolver.compact(it.namespaceOrType).removePrefix("global::"), systemFirst) }

    fun sortKey(name: String, systemFirst: Boolean = true): String = (if (systemFirst && (name == "System" || name.startsWith("System."))) "0" else "1") + name.lowercase()

    // ---- text

    /** The whitespace before [offset] on its line; when something else is there too, the whitespace the line starts with. */
    fun indentOf(text: CharSequence, offset: Int): String? {
        val start = lineStart(text, offset)
        val before = text.subSequence(start, offset).toString()
        return if (before.isBlank()) before else before.takeWhile { it == ' ' || it == '\t' }
    }

    private fun lineStart(text: CharSequence, offset: Int): Int = if (offset <= 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    /** The lines of a body without the blank ones at its ends; one line when it was written on the line of its braces. */
    fun contentLines(content: CharSequence): List<String> {
        val lines = content.toString().split('\n').toMutableList()
        if (lines.size == 1) return listOf(lines[0].trim()).filter { it.isNotEmpty() }
        while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        return lines
    }

    /** [lines] moved to [indent]: their common leading whitespace replaced by it; blank lines stay empty. */
    fun reindent(lines: List<String>, indent: String): List<String> {
        val common = lines.filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        return lines.map { line -> if (line.isBlank()) "" else indent + line.substring(minOf(common, line.length)).trimEnd() }
    }
}

/**
 * The `using` intentions on the native tree. Those the server has too (its "Use simple 'using' statement") stand back while it is ready and
 * the feature is its; when the feature is NATIVE, the server's one is dropped ([NativeCSharpServerActions]).
 */
abstract class NativeCSharpUsingIntention(private val title: String, private val serverHasIt: Boolean) : IntentionAction, PriorityAction, DumbAware {
    override fun getText(): String = title
    override fun getFamilyName(): String = title
    override fun startInWriteAction(): Boolean = true
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.NORMAL

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is CSharpFile || file.compilationUnit == null) return false
        if (serverHasIt && !CSharpFeatures.native(CSharpFeature.EDITING, file) && RoslynServerStatus.isReady(project, file.virtualFile)) return false
        return edit(file, editor.caretModel.offset, editor.document.charsSequence) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        val edit = edit(file, editor.caretModel.offset, editor.document.charsSequence) ?: return
        editor.document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.text)
        editor.caretModel.moveToOffset(edit.range.startOffset + edit.caret)
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
    }

    abstract fun edit(file: CSharpFile, offset: Int, text: CharSequence): CSharpTextEdit?

    protected fun unit(file: PsiFile): String {
        val options = CodeStyle.getSettings(file).getIndentOptions(CSharpFileType)
        return if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE)
    }
}

/** Alt+Enter on the header of `using (var x = …) { }` that ends its block: `using var x = …;` (Rider: "Convert to 'using' declaration"). */
class NativeCSharpToUsingDeclarationIntention : NativeCSharpUsingIntention("Convert to 'using' declaration", serverHasIt = true) {
    override fun edit(file: CSharpFile, offset: Int, text: CharSequence): CSharpTextEdit? =
        NativeCSharpUsingEdits.usingStatementAt(file, offset)?.let { NativeCSharpUsingEdits.toDeclaration(it, text) }
}

/** Alt+Enter on `using var x = …;`: `using (var x = …) { the rest of the block }`. */
class NativeCSharpToUsingStatementIntention : NativeCSharpUsingIntention("Convert to 'using' statement", serverHasIt = false) {
    override fun edit(file: CSharpFile, offset: Int, text: CharSequence): CSharpTextEdit? =
        NativeCSharpUsingEdits.declarationAt(file, offset)?.let { NativeCSharpUsingEdits.toStatement(it, text, unit(file)) }
}

/** Alt+Enter on `var x = new T(…);`: the rest of the block in `using (var x = new T(…)) { }`. */
class NativeCSharpWrapInUsingIntention : NativeCSharpUsingIntention("Wrap in 'using' statement", serverHasIt = false) {
    override fun edit(file: CSharpFile, offset: Int, text: CharSequence): CSharpTextEdit? =
        NativeCSharpUsingEdits.declarationAt(file, offset)?.let { NativeCSharpUsingEdits.wrapInUsing(it, text, unit(file)) }
}

/** Alt+Enter on a `using` directive: the directives of its place sorted as Rider does (global first, `System` first, then the alphabet). */
class NativeCSharpSortUsingsIntention : NativeCSharpUsingIntention("Sort 'using' directives", serverHasIt = false) {
    override fun edit(file: CSharpFile, offset: Int, text: CharSequence): CSharpTextEdit? =
        NativeCSharpGlobalUsings.directiveAt(file, offset)?.let { NativeCSharpUsingEdits.sort(it, text) }
}

/**
 * Alt+Enter on a `using` directive at the top of a file: "Convert to 'global using'". As Rider: the directive moves to the project's file of
 * global usings — this file when it has `global using` directives itself, else `GlobalUsings.cs` (or the first file of the project with
 * `global using` directives), made in the project's folder when there is none.
 */
class NativeCSharpToGlobalUsingIntention : IntentionAction, PriorityAction, DumbAware {
    override fun getText(): String = "Convert to 'global using'"
    override fun getFamilyName(): String = text
    override fun startInWriteAction(): Boolean = true
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.NORMAL
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        // the file of global usings is looked up in an index (targetFile)
        if (editor == null || file !is CSharpFile || file.compilationUnit == null || DumbService.isDumb(project)) return false
        val directive = NativeCSharpGlobalUsings.directiveAt(file, editor.caretModel.offset) ?: return false
        return NativeCSharpGlobalUsings.convertible(directive)
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        val directive = NativeCSharpGlobalUsings.directiveAt(file, editor.caretModel.offset)?.takeIf(NativeCSharpGlobalUsings::convertible) ?: return
        NativeCSharpGlobalUsings.convert(project, file, directive, editor)
    }
}

object NativeCSharpGlobalUsings {
    const val FILE_NAME = "GlobalUsings.cs"

    fun directiveAt(file: PsiFile, offset: Int): CSharpUsingDirective? {
        for (candidate in listOf(offset, offset - 1)) {
            val leaf = file.findElementAt(candidate) ?: continue
            PsiTreeUtil.getParentOfType(leaf, CSharpUsingDirective::class.java, false)?.let { return it }
        }
        return null
    }

    /** A plain directive of the compilation unit, written out: one in a namespace would change its meaning as a global one. */
    fun convertible(directive: CSharpUsingDirective): Boolean =
        directive.globalKeyword == null && directive.parent is CSharpCompilationUnit && (directive.semicolonToken?.textLength ?: 0) > 0 &&
            directive.namespaceOrType != null

    /** `global using …;` for [directive]: its text with `global ` in front, whitespace collapsed. */
    fun globalText(directive: CSharpUsingDirective): String = "global " + CSharpStubsText.collapse(directive.text)

    fun convert(project: Project, file: CSharpFile, directive: CSharpUsingDirective, editor: Editor) {
        val document = editor.document
        val line = globalText(directive)
        val ownGlobals = (directive.parent as CSharpCompilationUnit).usings.filter { it.globalKeyword != null }
        val removal = lineRange(document.charsSequence, directive.textRange)
        if (ownGlobals.isNotEmpty()) {
            // this file is a file of global usings: the directive joins them
            val known = ownGlobals.any { CSharpStubsText.collapse(it.text) == line }
            val anchor = ownGlobals.last().textRange.endOffset
            document.deleteString(removal.startOffset, removal.endOffset)
            if (!known) {
                val at = if (anchor > removal.startOffset) anchor - removal.length else anchor
                document.insertString(at, "\n$line")
            }
            PsiDocumentManager.getInstance(project).commitDocument(document)
            return
        }
        val target = targetFile(project, file)
        document.deleteString(removal.startOffset, removal.endOffset)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val targetDocument = if (target != null) FileDocumentManager.getInstance().getDocument(target) else null
        if (target == null || targetDocument == null) {
            val directory = targetDirectory(file) ?: return
            val psiDirectory = PsiManager.getInstance(project).findDirectory(directory) ?: return
            val created = psiDirectory.createFile(FILE_NAME)
            val createdDocument = PsiDocumentManager.getInstance(project).getDocument(created) ?: return
            createdDocument.setText("$line\n")
            PsiDocumentManager.getInstance(project).commitDocument(createdDocument)
            return
        }
        val text = targetDocument.charsSequence
        if (CSharpGlobalUsingIndex.directives(text).contains(CSharpGlobalUsingIndex.directives("$line\n").firstOrNull())) return
        val last = Regex("""(?m)^[ \t]*global[ \t]+using[^;\n]*;[^\n]*""").findAll(text).lastOrNull()
        if (last != null) targetDocument.insertString(last.range.last + 1, "\n$line") else targetDocument.insertString(0, "$line\n")
        PsiDocumentManager.getInstance(project).commitDocument(targetDocument)
    }

    /** The directive's line with its line break, when nothing else is on it; else the directive alone. */
    private fun lineRange(text: CharSequence, range: TextRange): TextRange {
        var start = range.startOffset
        while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
        var end = range.endOffset
        while (end < text.length && (text[end] == ' ' || text[end] == '\t' || text[end] == '\r')) end++
        val wholeLine = (start == 0 || text[start - 1] == '\n') && (end == text.length || text[end] == '\n')
        return if (wholeLine) TextRange(start, if (end < text.length) end + 1 else end) else range
    }

    /** A file of the project of [file] with `global using` directives (not generated): `GlobalUsings.cs` first. */
    fun targetFile(project: Project, file: CSharpFile): VirtualFile? {
        val directory = targetDirectory(file) ?: return null
        val own = file.viewProvider.virtualFile
        val candidates = ArrayList<VirtualFile>()
        FileBasedIndex.getInstance().getContainingFiles(CSharpGlobalUsingIndex.NAME, CSharpGlobalUsingIndex.KEY, GlobalSearchScope.projectScope(project)).forEach { candidate ->
            if (candidate == own || !VfsUtilCore.isAncestor(directory, candidate, true)) return@forEach
            val relative = VfsUtilCore.getRelativePath(candidate, directory) ?: return@forEach
            if (relative.startsWith("obj/") || relative.startsWith("bin/")) return@forEach
            candidates += candidate
        }
        directory.findChild(FILE_NAME)?.takeIf { !it.isDirectory }?.let { if (it !in candidates) candidates += it }
        return candidates.sortedWith(compareBy<VirtualFile> { it.name != FILE_NAME }.thenBy { it.path.length }.thenBy { it.path }).firstOrNull()
    }

    /** The folder of the project of [file]; the file's own folder when it is in no project. */
    fun targetDirectory(file: CSharpFile): VirtualFile? {
        val virtualFile = file.viewProvider.virtualFile
        return DotNetProjects.findOwningProject(virtualFile)?.parent ?: virtualFile.parent
    }
}
