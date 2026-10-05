package io.github.dotnetsupport.lang

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*

/** What the caret stands for in the completion of csharp-psi's tree ([NativeCSharpCompletion]); see [NativeCSharpCompletionPlace.of]. */
enum class NativeCompletionKind {
    /** The first token of a statement in a body: statement and expression keywords, names, types. */
    STATEMENT,

    /** A value: names, types, expression keywords. */
    EXPRESSION,

    /** Where only a type can stand (`new |`, a parameter's type, `typeof(|)`, a base list...): types, type parameters, predefined types. */
    TYPE,

    /** The start of a member in a type body: modifiers, `void`, types; after `override` the members to override, after `partial` the partial methods. */
    MEMBER_START,

    /** The start of a declaration in a namespace or at the top of the file. */
    TOP_LEVEL,

    /** The name of a variable, field or parameter being declared after its type: names made of the type (`StringBuilder |` → `builder`). */
    DECLARATION_NAME,

    /** After `goto`: the labels of the function. */
    LABEL,

    /** A place where only some keywords go: `when` after `catch (...)`, `where` after a type parameter list, the query keywords. */
    KEYWORDS_ONLY,

    /** After `this.` / `base.`: the members of the own type / of its base types. */
    THIS_MEMBERS,

    /** The name of an attribute: the attribute types of the solution without their `Attribute` suffix. */
    ATTRIBUTE,

    /** The target of a `using` directive: namespaces, and types after `using static` / `using X =` ([NativeCSharpUsingCompletion]). */
    USING_DIRECTIVE,
}

/**
 * The place of the caret for the native completion: decided by the token before it and the nodes around the identifier the platform
 * inserts at the caret (`IntellijIdeaRulezzz`, see the probes in `CSharpCompletionNativeTest`). The parser of a half-typed line makes
 * odd trees (`Foo⏎ var y` is a declaration of `y` of type `Foo`), so the place is decided by the token before the caret first and by the
 * node only where the token tells nothing. Null where the native completion has nothing to say (after a dot outside a `using` directive, inside an
 * accessor list...): the list is then the server's and the other contributors' alone.
 */
class NativeCSharpCompletionPlace(
    val kind: NativeCompletionKind,
    /** The identifier leaf at the caret (in the copy of the file the platform completes in). */
    val leaf: PsiElement,
    val name: CSharpSimpleName?,
    /** The token before the caret, whitespace, comments and tokens the parser made up skipped. */
    val prev: PsiElement?,
    /** Keywords this place adds to those of its kind (`var` in a local's type, `null` / `not` in a pattern) or, for KEYWORDS_ONLY, all of them. */
    val keywords: List<String> = emptyList(),
    /** MEMBER_START / TOP_LEVEL: the modifiers already typed before the caret. */
    val modifiers: List<String> = emptyList(),
    /** MEMBER_START: the declaration of the type whose body the caret is in. */
    val typeDeclaration: CSharpTypeDeclaration? = null,
    /** DECLARATION_NAME: the type written before the name, and how the name is spelled. */
    val declaredType: String? = null,
    val nameStyle: NameStyle = NameStyle.LOCAL,
    /** THIS_MEMBERS: `base.` rather than `this.`. */
    val base: Boolean = false,
    /** USING_DIRECTIVE: the namespace written before the caret (`using System.Coll|` → `System`), empty at the start. */
    val usingQualifier: String = "",
    /** USING_DIRECTIVE: `using static` or an alias: types go there too, not only namespaces. */
    val usingTypes: Boolean = false,
    /** USING_DIRECTIVE: the first name after `using` (`static` may still be typed). */
    val usingFirst: Boolean = false,
) {
    enum class NameStyle { LOCAL, PRIVATE_FIELD, PUBLIC_MEMBER }

    /** The tree decides which keywords are legal here: keyword items of the server are dropped (see [NativeCSharpCompletion]). */
    val keywordsAreNative: Boolean get() = kind != NativeCompletionKind.THIS_MEMBERS && kind != NativeCompletionKind.DECLARATION_NAME && kind != NativeCompletionKind.ATTRIBUTE &&
        kind != NativeCompletionKind.USING_DIRECTIVE

    val offset: Int get() = leaf.textRange.startOffset

    companion object {
        fun of(leaf: PsiElement): NativeCSharpCompletionPlace? {
            if (!CSharpLeaves.isIdentifier(leaf)) return null
            val prev = previousToken(leaf)
            byPreviousToken(leaf, prev)?.let { return it }
            when (val parent = leaf.parent) {
                is CSharpVariableDeclarator -> if (parent.identifier == leaf) return declarator(leaf, prev, parent)
                is CSharpParameter -> if (parent.identifier == leaf) {
                    val type = parent.type?.text ?: return null
                    return NativeCSharpCompletionPlace(NativeCompletionKind.DECLARATION_NAME, leaf, null, prev, declaredType = type)
                }
                is CSharpSingleVariableDesignation -> if (parent.identifier == leaf) return designation(leaf, prev, parent)
            }
            val name = leaf.parent as? CSharpSimpleName ?: return null
            if (name.identifier != leaf) return null
            val holder = name.parent ?: return null
            PsiTreeUtil.getParentOfType(name, CSharpUsingDirective::class.java)?.let { return usingDirective(leaf, name, prev, it) }
            when {
                holder is CSharpMemberAccessExpression && holder.nameElement == name -> {
                    val qualifier = holder.expression
                    return if (qualifier is CSharpThisExpression || qualifier is CSharpBaseExpression) {
                        NativeCSharpCompletionPlace(NativeCompletionKind.THIS_MEMBERS, leaf, name, prev, base = qualifier is CSharpBaseExpression)
                    } else null
                }
                holder is CSharpMemberBindingExpression || holder is CSharpQualifiedName && holder.right == name || holder is CSharpAliasQualifiedName -> return null
                holder is CSharpNameColon || holder is CSharpNameEquals -> return null
                holder is CSharpGotoStatement -> return if (holder.caseOrDefaultKeyword == null) NativeCSharpCompletionPlace(NativeCompletionKind.LABEL, leaf, name, prev) else null
                holder is CSharpAttribute && holder.nameElement == name -> return NativeCSharpCompletionPlace(NativeCompletionKind.ATTRIBUTE, leaf, name, prev)
            }
            if (PsiTreeUtil.getParentOfType(name, CSharpUsingDirective::class.java, CSharpExternAliasDirective::class.java) != null) return null
            if (generateSequence<PsiElement>(name) { it.parent }.takeWhile { it !is CSharpFile }.any { (it.parent as? CSharpBaseNamespaceDeclaration)?.nameElement == it }) return null
            statementStart(name)?.let { statement ->
                val global = statement.parent is CSharpGlobalStatement
                return NativeCSharpCompletionPlace(NativeCompletionKind.STATEMENT, leaf, name, prev, keywords = if (global) TOP_LEVEL_STARTS else emptyList())
            }
            memberStart(name)?.let { (member, container) ->
                val modifiers = member.modifiers.map { it.text }
                return when (container) {
                    is CSharpTypeDeclaration -> NativeCSharpCompletionPlace(NativeCompletionKind.MEMBER_START, leaf, name, prev, modifiers = modifiers, typeDeclaration = container)
                    is CSharpBaseNamespaceDeclaration, is CSharpCompilationUnit -> NativeCSharpCompletionPlace(NativeCompletionKind.TOP_LEVEL, leaf, name, prev, modifiers = modifiers)
                    else -> null
                }
            }
            if (holder is CSharpCaseSwitchLabel || holder is CSharpConstantPattern || holder is CSharpBinaryExpression && holder.operatorToken?.text == "is" && holder.right == name) {
                return NativeCSharpCompletionPlace(NativeCompletionKind.EXPRESSION, leaf, name, prev, keywords = PATTERN_KEYWORDS)
            }
            if (NativeCSharpTypePositions.isType(name)) return typePlace(leaf, name, prev)
            // `using (|`: a resource, or the declaration of one
            if (holder is CSharpUsingStatement && holder.expression == name && prev == holder.openParenToken) {
                return NativeCSharpCompletionPlace(NativeCompletionKind.EXPRESSION, leaf, name, prev, keywords = listOf("var"))
            }
            if (inCode(name)) return NativeCSharpCompletionPlace(NativeCompletionKind.EXPRESSION, leaf, name, prev, keywords = argumentKeywords(name, prev))
            return null
        }

        /** Keywords that may follow some tokens whatever the parser made of the rest. */
        private fun byPreviousToken(leaf: PsiElement, prev: PsiElement?): NativeCSharpCompletionPlace? {
            prev ?: return null
            val parent = prev.parent
            fun only(vararg keywords: String) = NativeCSharpCompletionPlace(NativeCompletionKind.KEYWORDS_ONLY, leaf, leaf.parent as? CSharpSimpleName, prev, keywords = keywords.toList())
            when {
                prev.text == ")" && parent is CSharpCatchDeclaration -> return only("when")
                prev.text == ">" && parent is CSharpTypeParameterList && parent.parent !is CSharpMethodDeclaration -> return only("where")
                prev.text == ")" && parent is CSharpParameterList && (parent.parent as? CSharpMethodDeclaration)?.typeParameterList != null &&
                    (parent.parent as CSharpMethodDeclaration).body.let { it == null || it.textRange.startOffset > leaf.textRange.startOffset } -> return only("where")
            }
            return query(leaf, prev)
        }

        /**
         * After a clause of a query (`from x in xs |`, `where x > 0 |`) the parser has ended the query, and the caret is a statement of
         * its own: the keywords that continue a query. `from x |` → `in`; `orderby x |` → `ascending`, `descending`; a `join` → `on`,
         * `equals`; `group x |` → `by`.
         */
        private fun query(leaf: PsiElement, prev: PsiElement): NativeCSharpCompletionPlace? {
            val query = PsiTreeUtil.getParentOfType(prev, CSharpQueryExpression::class.java) ?: return null
            if (PsiTreeUtil.isAncestor(query, leaf, false)) {
                // inside the query: `from x Zummy` (no `in` yet), `orderby x Zummy`, `join y Zummy`, `group x Zummy`
                val clause = PsiTreeUtil.getParentOfType(leaf, CSharpFromClause::class.java, CSharpJoinClause::class.java, CSharpGroupClause::class.java, CSharpOrdering::class.java)
                val keywords = when {
                    clause is CSharpFromClause && clause.identifier == prev -> listOf("in")
                    clause is CSharpJoinClause && clause.identifier == prev -> listOf("in")
                    else -> return null
                }
                return NativeCSharpCompletionPlace(NativeCompletionKind.KEYWORDS_ONLY, leaf, null, prev, keywords = keywords)
            }
            if (query.textRange.endOffset > leaf.textRange.startOffset) return null
            val body = query.body
            fun <T : PsiElement> present(element: T?): T? = element?.takeIf { it.textLength > 0 }
            val selectOrGroup = present(body?.selectOrGroup)
            val continuation = present(body?.continuation)
            val last: PsiElement? = continuation ?: selectOrGroup ?: body?.clauses?.lastOrNull { it.textLength > 0 } ?: query.fromClause
            val keywords = ArrayList<String>()
            when (last) {
                is CSharpFromClause -> if (last.inKeyword == null || last.inKeyword!!.textLength == 0) keywords += "in"
                is CSharpOrderByClause -> keywords += listOf("ascending", "descending")
                is CSharpJoinClause -> when {
                    last.onKeyword == null || last.onKeyword!!.textLength == 0 -> keywords += "on"
                    last.equalsKeyword == null || last.equalsKeyword!!.textLength == 0 -> keywords += "equals"
                    else -> keywords += "into"
                }
                is CSharpGroupClause -> if (last.byKeyword == null || last.byKeyword!!.textLength == 0) keywords += "by"
            }
            if (selectOrGroup == null || last is CSharpOrderByClause || last is CSharpJoinClause) keywords += QUERY_CLAUSES
            if (selectOrGroup != null && continuation == null) keywords += "into"
            return NativeCSharpCompletionPlace(NativeCompletionKind.KEYWORDS_ONLY, leaf, null, prev, keywords = keywords.distinct())
        }

        private fun declarator(leaf: PsiElement, prev: PsiElement?, declarator: CSharpVariableDeclarator): NativeCSharpCompletionPlace? {
            val declaration = declarator.parent as? CSharpVariableDeclaration ?: return null
            val type = declaration.type?.text?.trim() ?: return null
            // `yield |` is read as a declaration of a local of type `yield`
            if (type == "yield" && declaration.parent is CSharpLocalDeclarationStatement) {
                return NativeCSharpCompletionPlace(NativeCompletionKind.KEYWORDS_ONLY, leaf, null, prev, keywords = listOf("return", "break"))
            }
            val field = declaration.parent as? CSharpBaseFieldDeclaration
            // `partial |`, `async |`: a contextual modifier is read as the type of a field
            if (field != null && type in CONTEXTUAL_MODIFIERS && declaration.variables.size == 1) {
                val modifiers = field.modifiers.map { it.text } + type
                return when (val container = field.parent) {
                    is CSharpTypeDeclaration -> NativeCSharpCompletionPlace(NativeCompletionKind.MEMBER_START, leaf, null, prev, modifiers = modifiers, typeDeclaration = container)
                    is CSharpBaseNamespaceDeclaration, is CSharpCompilationUnit -> NativeCSharpCompletionPlace(NativeCompletionKind.TOP_LEVEL, leaf, null, prev, modifiers = modifiers)
                    else -> null
                }
            }
            // `global |` at the top of a file is read as a local of type `global`: `using` follows it
            if (type == "global" && declaration.parent?.parent is CSharpGlobalStatement && NativeCSharpUsingCompletion.atUsingsOfFile(declaration, global = true)) {
                return NativeCSharpCompletionPlace(NativeCompletionKind.KEYWORDS_ONLY, leaf, null, prev, keywords = listOf("using"))
            }
            if (type == "var" || type in PREDEFINED_TYPES || type in ALL_KEYWORDS) return null
            val style = when {
                field == null -> NameStyle.LOCAL
                field.modifiers.any { it.text == "const" } -> NameStyle.PUBLIC_MEMBER
                field.modifiers.any { it.text == "public" || it.text == "protected" || it.text == "internal" } -> NameStyle.PUBLIC_MEMBER
                else -> NameStyle.PRIVATE_FIELD
            }
            return NativeCSharpCompletionPlace(NativeCompletionKind.DECLARATION_NAME, leaf, null, prev, declaredType = type, nameStyle = style)
        }

        /** `x is Order |`, `case Order |`, `out Order |`: the variable's name, and `when` in a `case` label or an arm of a `switch` expression. */
        private fun designation(leaf: PsiElement, prev: PsiElement?, designation: CSharpSingleVariableDesignation): NativeCSharpCompletionPlace? {
            val type = when (val owner = designation.parent) {
                is CSharpDeclarationPattern -> owner.type
                is CSharpDeclarationExpression -> owner.type
                is CSharpRecursivePattern -> owner.type
                else -> null
            }?.text?.trim() ?: return null
            if (type == "var" || type in PREDEFINED_TYPES) return null
            val inCase = PsiTreeUtil.getParentOfType(designation, CSharpCasePatternSwitchLabel::class.java, CSharpSwitchExpressionArm::class.java) != null
            return NativeCSharpCompletionPlace(NativeCompletionKind.DECLARATION_NAME, leaf, null, prev, keywords = if (inCase) listOf("when") else emptyList(), declaredType = type)
        }

        private fun typePlace(leaf: PsiElement, name: CSharpSimpleName, prev: PsiElement?): NativeCSharpCompletionPlace {
            var top: PsiElement = name
            while (top.parent is CSharpQualifiedName || top.parent is CSharpAliasQualifiedName) top = top.parent
            val keywords = ArrayList<String>()
            when (val owner = top.parent) {
                is CSharpVariableDeclaration -> if (owner.parent !is CSharpBaseFieldDeclaration) keywords += "var"
                is CSharpForEachStatement, is CSharpDeclarationExpression -> keywords += "var"
                is CSharpParameter -> {
                    keywords += listOf("ref", "out", "in", "params", "scoped")
                    val list = owner.parent as? CSharpParameterList
                    if (list?.parameters?.firstOrNull() == owner && list.parent is CSharpMethodDeclaration) keywords += "this"
                }
                is CSharpTypeConstraint -> keywords += listOf("class", "struct", "notnull", "unmanaged", "new()", "default")
                is CSharpArgument -> keywords += "var"
            }
            return NativeCSharpCompletionPlace(NativeCompletionKind.TYPE, leaf, name, prev, keywords = keywords)
        }

        /**
         * The target of a `using` directive (`using |`, `using System.Coll|`, `using static |`, `using X = |`): what is before the dot is
         * the namespace to list. Not the alias being named, not a type argument (`using L = List<|>` is a type place the server has).
         */
        private fun usingDirective(leaf: PsiElement, name: CSharpSimpleName, prev: PsiElement?, directive: CSharpUsingDirective): NativeCSharpCompletionPlace? {
            if (directive.alias != null && PsiTreeUtil.isAncestor(directive.alias, name, false)) return null
            if (PsiTreeUtil.getParentOfType(name, CSharpTypeArgumentList::class.java, true, CSharpUsingDirective::class.java) != null) return null
            val parent = name.parent
            val qualifier = when {
                parent is CSharpQualifiedName && parent.right == name -> NativeCSharpResolver.compact(parent.left).removePrefix("global::")
                parent is CSharpAliasQualifiedName -> ""
                parent == directive -> ""
                else -> return null
            }
            val first = parent == directive && directive.staticKeyword == null && directive.alias == null
            return NativeCSharpCompletionPlace(
                NativeCompletionKind.USING_DIRECTIVE, leaf, name, prev, usingQualifier = qualifier,
                usingTypes = directive.staticKeyword != null || directive.alias != null, usingFirst = first,
            )
        }

        /** `out` / `ref` / `in` where an argument begins. */
        private fun argumentKeywords(name: CSharpSimpleName, prev: PsiElement?): List<String> {
            val argument = name.parent as? CSharpArgument ?: return emptyList()
            if (argument.expression != name || argument.parent !is CSharpArgumentList) return emptyList()
            return if (prev?.text == "(" || prev?.text == ",") listOf("out", "ref", "in") else emptyList()
        }

        /** The statement [name] begins, when that statement is in a body (a block, a switch section, the top of the file, the body of `if`...). */
        fun statementStart(name: CSharpSimpleName): CSharpStatement? {
            val start = name.textRange.startOffset
            var statement: CSharpStatement? = null
            var current: PsiElement? = name.parent
            while (current != null && current !is CSharpFile && current.textRange.startOffset == start) {
                if (current is CSharpStatement) statement = current
                if (current is CSharpMemberDeclaration || current is CSharpBlock) break
                current = current.parent
            }
            statement ?: return null
            if (statement is CSharpBlock) return null
            return when (statement.parent) {
                is CSharpBlock, is CSharpSwitchSection, is CSharpGlobalStatement, is CSharpLabeledStatement, is CSharpElseClause -> statement
                is CSharpStatement -> statement.takeIf { (statement.parent as? CSharpStatement)?.let(::bodyOf) == statement }
                else -> null
            }
        }

        private fun bodyOf(statement: CSharpStatement): CSharpStatement? = when (statement) {
            is CSharpIfStatement -> statement.statement
            is CSharpWhileStatement -> statement.statement
            is CSharpDoStatement -> statement.statement
            is CSharpForStatement -> statement.statement
            is CSharpCommonForEachStatement -> statement.statement
            is CSharpUsingStatement -> statement.statement
            is CSharpLockStatement -> statement.statement
            is CSharpFixedStatement -> statement.statement
            else -> null
        }

        /**
         * [name] is the type of a member being typed (`public |`, `|` alone, `static |` before the next member): the member and its
         * container. Not when the caret is past the type (that is a name) or in a body.
         */
        private fun memberStart(name: CSharpSimpleName): Pair<CSharpMemberDeclaration, PsiElement>? {
            var top: PsiElement = name
            while (top.parent is CSharpQualifiedName || top.parent is CSharpAliasQualifiedName) top = top.parent
            if (top != name) return null
            val owner = name.parent
            val member: CSharpMemberDeclaration = when {
                owner is CSharpIncompleteMember && owner.type == name -> owner
                owner is CSharpMethodDeclaration && owner.returnType == name -> owner
                owner is CSharpBasePropertyDeclaration && owner.type == name -> owner
                owner is CSharpVariableDeclaration && owner.type == name && owner.parent is CSharpBaseFieldDeclaration -> owner.parent as CSharpMemberDeclaration
                else -> return null
            }
            val container = member.parent ?: return null
            return member to container
        }

        /** Somewhere a value is written: a body, an initializer, an argument, an expression body. */
        private fun inCode(name: PsiElement): Boolean = PsiTreeUtil.getParentOfType(
            name, CSharpBlock::class.java, CSharpArrowExpressionClause::class.java, CSharpEqualsValueClause::class.java, CSharpArgumentList::class.java,
            CSharpGlobalStatement::class.java, CSharpAttributeArgumentList::class.java, CSharpBracketedArgumentList::class.java,
        ) != null

        /** The token before [leaf]: whitespace, comments and the empty tokens the parser puts in for missing ones are skipped. */
        fun previousToken(leaf: PsiElement): PsiElement? {
            var current = PsiTreeUtil.prevLeaf(leaf)
            while (current != null && (current is PsiWhiteSpace || current is PsiComment || current.textLength == 0 || CSharpLeaves.isTrivia(current))) {
                current = PsiTreeUtil.prevLeaf(current)
            }
            return current
        }

        val PREDEFINED_TYPES: List<String> = listOf(
            "bool", "byte", "char", "decimal", "double", "float", "int", "long", "object", "sbyte", "short", "string", "uint", "ulong", "ushort", "nint", "nuint",
        )

        val QUERY_CLAUSES: List<String> = listOf("where", "select", "orderby", "join", "let", "group", "from")

        private val CONTEXTUAL_MODIFIERS = setOf("partial", "async", "required", "file")

        private val PATTERN_KEYWORDS = listOf("null", "not", "var", "true", "false")

        /** At the top of a file of top-level statements: what may also start a declaration there. */
        private val TOP_LEVEL_STARTS = listOf("using", "namespace", "class", "struct", "interface", "enum", "record", "delegate")

        /** Reserved and contextual keywords of C#: a server item spelled so is a keyword (see [keywordsAreNative]); a name spelled so needs `@`. */
        val RESERVED: Set<String> = setOf(
            "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked", "class", "const", "continue", "decimal", "default",
            "delegate", "do", "double", "else", "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for", "foreach", "goto",
            "if", "implicit", "in", "int", "interface", "internal", "is", "lock", "long", "namespace", "new", "null", "object", "operator", "out",
            "override", "params", "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed", "short", "sizeof", "stackalloc",
            "static", "string", "struct", "switch", "this", "throw", "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using",
            "virtual", "void", "volatile", "while",
        )

        val ALL_KEYWORDS: Set<String> = RESERVED + setOf(
            "add", "and", "alias", "ascending", "args", "async", "await", "by", "descending", "dynamic", "equals", "file", "from", "get", "global", "group",
            "init", "into", "join", "let", "managed", "nameof", "nint", "not", "notnull", "nuint", "on", "or", "orderby", "partial", "record", "remove",
            "required", "scoped", "select", "set", "unmanaged", "value", "var", "when", "where", "with", "yield",
        ) - setOf("args", "value")
    }
}
