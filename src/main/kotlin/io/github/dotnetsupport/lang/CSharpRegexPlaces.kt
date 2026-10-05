package io.github.dotnetsupport.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.Key
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*

/**
 * Which string literals are regular expressions (task 2.5 of docs/COMPLETION_GAPS.md), as Rider and Roslyn's embedded languages decide
 * without the semantics: the pattern of `new Regex(…)` and of the static `Regex.IsMatch / Match / Matches / Replace / Split / Count /
 * EnumerateMatches / EnumerateSplits`, of `[GeneratedRegex(…)]` and `[RegularExpression(…)]`; an argument of a method or a constructor of
 * the solution whose parameter is `[StringSyntax(StringSyntaxAttribute.Regex)]` (by the name of the method: the index of assemblies keeps
 * no attributes of parameters, so of the libraries only the list above); a literal after a comment `// lang=regex` (`language=regex`,
 * `/*lang=regex*/` in front of the literal or on the line before the statement). Only the native tree.
 */
object CSharpRegexPlaces {
    /** The static methods of `Regex` whose second argument is the pattern (the first is the input). */
    private val STATIC_METHODS = setOf("IsMatch", "Match", "Matches", "Replace", "Split", "Count", "EnumerateMatches", "EnumerateSplits")
    private val REGEX_TYPE = setOf("Regex", "System.Text.RegularExpressions.Regex", "global::System.Text.RegularExpressions.Regex")
    private val ATTRIBUTES = setOf("GeneratedRegex", "RegularExpression")
    private val COMMENT = Regex("""(?i)\b(lang|language)\s*=\s*regexp?\b""")

    fun isRegex(literal: PsiElement): Boolean {
        val expression = literal.parent as? CSharpLiteralExpression ?: return false
        if (commentSaysRegex(expression)) return true
        return when (val argument = expression.parent) {
            is CSharpAttributeArgument -> attributeArgument(argument)
            is CSharpArgument -> callArgument(argument)
            else -> false
        }
    }

    private fun attributeArgument(argument: CSharpAttributeArgument): Boolean {
        val attribute = argument.parent?.parent as? CSharpAttribute ?: return false
        val name = attribute.nameElement?.text?.substringAfterLast('.')?.removeSuffix("Attribute") ?: return false
        if (name !in ATTRIBUTES) return false
        val list = argument.parent as CSharpAttributeArgumentList
        if (argument.nameEquals != null) return false
        return argument.nameColon?.nameElement?.text == "pattern" || argument.nameColon == null && list.arguments.indexOf(argument) == 0
    }

    private fun callArgument(argument: CSharpArgument): Boolean {
        val list = argument.parent as? CSharpArgumentList ?: return false
        val index = list.arguments.indexOf(argument)
        val named = argument.nameColon?.nameElement?.text
        fun at(position: Int): Boolean = if (named != null) named == "pattern" else index == position
        return when (val call = list.parent) {
            is CSharpObjectCreationExpression -> {
                val type = call.type?.text?.trim() ?: return false
                if (type in REGEX_TYPE) at(0) else solutionParameter(call.project, type.substringAfterLast('.').substringBefore('<'), index, named)
            }
            is CSharpImplicitObjectCreationExpression -> declaredType(call) in REGEX_TYPE && at(0)
            is CSharpInvocationExpression -> {
                val callee = call.expression
                val (receiver, name) = when (callee) {
                    is CSharpMemberAccessExpression -> callee.expression?.text?.trim() to callee.nameElement?.identifier?.text
                    is CSharpSimpleName -> null to callee.identifier?.text
                    else -> return false
                }
                name ?: return false
                if (receiver in REGEX_TYPE && name in STATIC_METHODS) return at(1)
                solutionParameter(call.project, name, index, named)
            }
            else -> false
        }
    }

    /**
     * The type the target-typed `new` creates, as written: `Regex r = new("…")`, `Regex P { get; } = new("…")`, `Regex M() => new("…")`,
     * `return new("…");` in a method returning `Regex`.
     */
    private fun declaredType(creation: CSharpImplicitObjectCreationExpression): String? {
        val owner = when (val parent = creation.parent) {
            is CSharpEqualsValueClause -> (parent.parent as? CSharpVariableDeclarator)?.parent ?: parent.parent
            is CSharpArrowExpressionClause -> parent.parent
            is CSharpReturnStatement -> PsiTreeUtil.getParentOfType(parent, CSharpMethodDeclaration::class.java, CSharpLocalFunctionStatement::class.java,
                CSharpBasePropertyDeclaration::class.java, CSharpAnonymousFunctionExpression::class.java)
            else -> null
        }
        val type = when (owner) {
            is CSharpVariableDeclaration -> owner.type
            is CSharpMethodDeclaration -> owner.returnType
            is CSharpLocalFunctionStatement -> owner.returnType
            is CSharpBasePropertyDeclaration -> owner.type
            else -> null
        }
        return type?.text?.trim()
    }

    private fun commentSaysRegex(expression: CSharpLiteralExpression): Boolean = commentSays(expression, COMMENT)

    /** A comment matching [pattern] (`// lang=…`) stands right before [expression] or before the statement or member it is in. */
    fun commentSays(expression: CSharpLiteralExpression, pattern: Regex): Boolean {
        commentBefore(expression)?.let { if (pattern.containsMatchIn(it.text)) return true }
        val holder = PsiTreeUtil.getParentOfType(expression, CSharpStatement::class.java, CSharpMemberDeclaration::class.java) ?: return false
        return commentBefore(holder)?.let { pattern.containsMatchIn(it.text) } == true
    }

    private fun commentBefore(element: PsiElement): PsiComment? {
        var leaf = PsiTreeUtil.prevLeaf(element)
        while (leaf is PsiWhiteSpace || leaf != null && leaf.textLength == 0) leaf = PsiTreeUtil.prevLeaf(leaf)
        return leaf as? PsiComment
    }

    /** A parameter of a method or a constructor of the solution named [name] at [index] (or named [named]) marked `[StringSyntax(…Regex)]`. */
    private fun solutionParameter(project: Project, name: String, index: Int, named: String?): Boolean = syntaxParameter(project, name, index, named, "Regex")

    /** A parameter of a method or a constructor of the solution named [name] at [index] (or named [named]) marked `[StringSyntax(…[syntax])]`. */
    fun syntaxParameter(project: Project, name: String, index: Int, named: String?, syntax: String): Boolean {
        val parameters = syntaxParameters(project)[name] ?: return false
        return parameters.any { (position, parameter, kind) -> kind == syntax && if (named != null) parameter == named else position == index }
    }

    /** By the name of a method: the positions and names of its regex parameters. */
    fun regexParameters(project: Project): Map<String, List<Pair<Int, String>>> =
        syntaxParameters(project).mapValues { (_, list) -> list.filter { it.third == "Regex" }.map { it.first to it.second } }.filterValues { it.isNotEmpty() }

    private val LAST_PARAMETERS = Key.create<Map<String, List<Triple<Int, String, String>>>>("dotnet.stringSyntaxParameters.last")

    /**
     * By the name of a method (of a type for constructors): the positions, names and syntaxes (`Regex`, `Json`, `Route`…, the last word of
     * the argument of `[StringSyntax(…)]`) of its parameters marked so. Only files with the word
     * `StringSyntax` are read. The injector is also asked on the EDT (typing, the editor's own highlighting), where the word index is a
     * prohibited slow operation: there the last computed map is used.
     */
    fun syntaxParameters(project: Project): Map<String, List<Triple<Int, String, String>>> {
        if (ApplicationManager.getApplication().isDispatchThread && !ApplicationManager.getApplication().isUnitTestMode) {
            return project.getUserData(LAST_PARAMETERS) ?: emptyMap()
        }
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val map = HashMap<String, MutableList<Triple<Int, String, String>>>()
            try {
                PsiSearchHelper.getInstance(project).processAllFilesWithWord("StringSyntax", GlobalSearchScope.projectScope(project), { file ->
                    if (file is CSharpFile) collect(file, map)
                    true
                }, true)
            } catch (_: IndexNotReadyException) {
            }
            project.putUserData(LAST_PARAMETERS, map)
            CachedValueProvider.Result.create(map as Map<String, List<Triple<Int, String, String>>>, PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    private fun collect(file: CSharpFile, map: HashMap<String, MutableList<Triple<Int, String, String>>>) {
        for (parameter in PsiTreeUtil.findChildrenOfType(file, CSharpParameter::class.java)) {
            val syntax = parameter.attributeLists.firstNotNullOfOrNull { list ->
                list.attributes.firstOrNull { it.nameElement?.text?.substringAfterLast('.')?.removeSuffix("Attribute") == "StringSyntax" }
                    ?.argumentList?.arguments?.firstOrNull()?.expression?.text?.let(::syntaxName)
            } ?: continue
            val list = parameter.parent as? CSharpParameterList ?: continue
            val owner = when (val declaration = list.parent) {
                is CSharpMethodDeclaration -> declaration.identifier?.text
                is CSharpConstructorDeclaration -> (declaration.parent as? CSharpBaseTypeDeclaration)?.identifier?.text
                is CSharpLocalFunctionStatement -> declaration.identifier?.text
                is CSharpTypeDeclaration -> declaration.identifier?.text
                else -> null
            } ?: continue
            map.getOrPut(owner) { ArrayList() } += Triple(list.parameters.indexOf(parameter), parameter.identifier?.text ?: "", syntax)
        }
    }

    /** `StringSyntaxAttribute.Regex`, `"Regex"`, `System.Diagnostics.CodeAnalysis.StringSyntaxAttribute.Json` -> `Regex` / `Json`. */
    private fun syntaxName(argument: String): String? = argument.trim().trim('"').substringAfterLast('.').takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
}
