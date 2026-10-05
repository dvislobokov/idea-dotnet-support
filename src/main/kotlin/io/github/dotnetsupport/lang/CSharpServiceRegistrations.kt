package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys

/**
 * Registrations of services (task 3.9 of docs/COMPLETION_GAPS.md), as Rider completes them: in `services.AddScoped<IService, |>` (and
 * `AddTransient`, `AddSingleton`, their `TryAdd…` and `AddKeyed…` forms) the second type argument is an implementation of the first — the
 * classes of the solution that derive from it, directly or through others, not abstract and not static, first in the list. By the text
 * before the caret (an unfinished `<…, ` is no type argument list for the parser yet) and the stub index of supertypes.
 */
object CSharpServiceRegistrations {
    private val PLACE = Regex("""\.\s*(?:Try)?Add(?:Keyed)?(?:Scoped|Transient|Singleton)\s*<\s*((?:global::)?[A-Za-z_][\w.]*(?:<[^<>;()]*>)?)\s*,\s*([A-Za-z_]\w*)?$""")

    /** The service type of the place before [offset] of [text] (its simple name), and what is typed of the implementation. */
    class Place(val service: String, val prefix: String)

    fun placeAt(text: CharSequence, offset: Int): Place? {
        val lineStart = maxOf(0, offset - 300)
        val match = PLACE.find(text.subSequence(lineStart, offset)) ?: return null
        val service = match.groupValues[1].substringBefore('<').substringAfterLast('.').trim()
        return Place(service, match.groupValues[2])
    }

    /** The classes of the solution that implement or extend [service] (by its simple name), nearer first. */
    fun implementations(project: Project, service: String, limit: Int = 200): List<CSharpBaseTypeDeclaration> {
        val found = LinkedHashMap<String, CSharpBaseTypeDeclaration>()
        val seen = HashSet<String>()
        var level = listOf(service)
        var depth = 0
        while (level.isNotEmpty() && depth++ < 8 && found.size < limit) {
            val next = ArrayList<String>()
            for (name in level) {
                if (!seen.add(name)) continue
                StubIndex.getInstance().processElements(CSharpStubIndexKeys.SUPERTYPES, name, project, GlobalSearchScope.projectScope(project), CSharpElement::class.java) { element ->
                    ProgressManager.checkCanceled()
                    val type = element as? CSharpBaseTypeDeclaration ?: return@processElements true
                    val typeName = type.identifier?.text ?: return@processElements true
                    next += typeName
                    if (isImplementation(type)) found.putIfAbsent(qualifiedName(type), type)
                    true
                }
            }
            level = next
        }
        return found.values.toList()
    }

    private fun isImplementation(type: CSharpBaseTypeDeclaration): Boolean {
        val keyword = (type as? CSharpTypeDeclaration)?.keyword?.text ?: return false
        if (keyword == "interface") return false
        val modifiers = type.modifiers.map { it.text }
        return "abstract" !in modifiers && "static" !in modifiers
    }

    fun namespaceOf(element: PsiElement): String =
        generateSequence(element.parent) { it.parent }.takeWhile { it !is CSharpFile }.filterIsInstance<CSharpBaseNamespaceDeclaration>()
            .mapNotNull { it.nameElement?.text?.filterNot(Char::isWhitespace) }.toList().asReversed().joinToString(".")

    private fun qualifiedName(type: CSharpBaseTypeDeclaration): String = namespaceOf(type).let { if (it.isEmpty()) "" else "$it." } + type.identifier?.text
}

/**
 * COMPLETION of the implementation in a service registration ([CSharpServiceRegistrations]): the implementations on top, each writes its
 * name (and the `using` of its namespace); the rest of the list follows without a second item of the same name.
 */
class CSharpServiceRegistrationCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val original = parameters.originalFile as? CSharpFile ?: return
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, original.project)) return
        val place = CSharpServiceRegistrations.placeAt(parameters.editor.document.charsSequence, parameters.offset) ?: return
        val implementations = CSharpServiceRegistrations.implementations(original.project, place.service)
        if (implementations.isEmpty()) return
        val fileNamespace = CSharpServiceRegistrations.namespaceOf(parameters.position)
        val set = result.withPrefixMatcher(place.prefix)
        val names = HashSet<String>()
        for ((index, type) in implementations.withIndex()) {
            val name = type.identifier?.text ?: continue
            names += name
            val namespace = CSharpServiceRegistrations.namespaceOf(type)
            val generic = (type as? CSharpTypeDeclaration)?.typeParameterList?.parameters?.size ?: 0
            val element = LookupElementBuilder.create(type, name).withIcon(AllIcons.Nodes.Class).withTypeText(namespace.ifEmpty { null }, true)
                .withTailText(if (generic > 0) "<" + ",".repeat(generic - 1) + ">" else null, true)
                .withInsertHandler(usingOf(namespace, fileNamespace))
            set.addElement(PrioritizedLookupElement.withPriority(element, 1000.0 - index))
        }
        set.runRemainingContributors(parameters) { found ->
            if (NativeCSharpCompletion.nameOf(found.lookupElement.lookupString) !in names) set.passResult(found)
        }
        set.stopHere()
    }

    private fun usingOf(namespace: String, fileNamespace: String): InsertHandler<LookupElement>? {
        if (namespace.isEmpty() || namespace == fileNamespace || fileNamespace.startsWith("$namespace.")) return null
        return InsertHandler { context, _ -> NativeCSharpImportCompletion.addUsing(context, namespace) }
    }
}
