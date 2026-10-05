package io.github.dotnetsupport.lang

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ex.ApplicationUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.JDOMUtil
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.msbuild.PackageCompletionService
import io.github.dotnetsupport.nuget.NuGetService
import io.github.dotnetsupport.nuget.NuGetSettings
import io.github.dotnetsupport.nuget.NuGetVersion
import org.jdom.Element
import java.util.concurrent.Callable

/**
 * COMPLETION of the rare places (task 3.14 of docs/COMPLETION_GAPS.md), by the text of the line, so on both trees:
 * `[assembly: InternalsVisibleTo("` → the projects of the solution; `extern alias ` → the `Aliases` of the references of the project;
 * `delegate* unmanaged[` → the calling conventions; `#:package ` of a file-based app → package ids of the feeds (and, after `@`, their versions),
 * `#:` → the directives of such an app.
 */
class CSharpRareCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? CSharpFile ?: return
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, file.project)) return
        val text = parameters.editor.document.charsSequence
        val offset = parameters.offset
        val line = text.subSequence(CSharpPreprocessor.lineStart(text, offset), offset).toString()
        val place = CSharpRare.placeOf(line) ?: return
        val project = file.project
        val items: List<Pair<String, String?>> = when (place.kind) {
            CSharpRare.Kind.INTERNALS_VISIBLE_TO -> NuGetService.getInstance(project).projects().map { it.first to "project" }
            CSharpRare.Kind.EXTERN_ALIAS -> {
                val projectFile = file.virtualFile?.let { CompilationModel.getInstance(project).projectOf(it) }
                val xml = projectFile?.let { FileDocumentManager.getInstance().getDocument(it)?.charsSequence ?: String(it.contentsToByteArray(), it.charset) }
                CSharpRare.externAliases(xml?.toString().orEmpty()).map { it to "alias" }
            }
            CSharpRare.Kind.CALLING_CONVENTION -> CSharpRare.CALLING_CONVENTIONS.map { it to null }
            CSharpRare.Kind.FILE_DIRECTIVE -> CSharpRare.FILE_DIRECTIVES.map { it to "file-based app" }
            CSharpRare.Kind.PACKAGE_ID -> {
                if (place.prefix.length < 2) emptyList() else await { PackageCompletionService.getInstance(project).search(place.prefix, NuGetSettings.getInstance().includePrerelease) }
                    .map { it.id to it.version }
            }
            CSharpRare.Kind.PACKAGE_VERSION -> {
                val versions = await { PackageCompletionService.getInstance(project).versions(place.argument.orEmpty()) }
                    .filter { NuGetSettings.getInstance().includePrerelease || '-' in place.prefix || NuGetVersion.parse(it)?.isPrerelease != true }
                versions.mapIndexed { index, version -> version to if (index == 0) "latest" else null }
            }
        }
        val set = result.withPrefixMatcher(if (place.kind == CSharpRare.Kind.PACKAGE_ID) Containing(place.prefix) else result.prefixMatcher.cloneWithPrefix(place.prefix)).caseInsensitive()
        items.forEachIndexed { index, (name, type) ->
            var element = LookupElementBuilder.create(name).withIcon(if (place.kind == CSharpRare.Kind.PACKAGE_ID || place.kind == CSharpRare.Kind.PACKAGE_VERSION) AllIcons.Nodes.PpLib else AllIcons.Nodes.Constant)
                .withCaseSensitivity(false)
            if (type != null) element = element.withTypeText(type, true)
            if (place.kind == CSharpRare.Kind.FILE_DIRECTIVE) element = element.withInsertHandler { context, _ ->
                val tail = context.tailOffset
                if (context.document.charsSequence.getOrNull(tail) != ' ') context.document.insertString(tail, " ")
                context.editor.caretModel.moveToOffset(tail + 1)
                AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
            }
            // the order of the feed is its relevance
            set.addElement(PrioritizedLookupElement.withPriority(element, (items.size - index).toDouble()))
        }
        result.stopHere()
    }

    /** The feed runs on a pooled thread and is waited for with cancellation checks, as for the packages of a project file. */
    private fun <T> await(load: () -> List<T>): List<T> = try {
        ApplicationUtil.runWithCheckCanceled(Callable { load() }, ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator())
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (_: Exception) {
        emptyList() // a feed that is down is no reason to break completion
    }

    /** The feed has matched the query already; the rest of what is typed narrows the list by containing it. */
    private class Containing(prefix: String) : PrefixMatcher(prefix) {
        override fun prefixMatches(name: String): Boolean = name.contains(prefix, ignoreCase = true)
        override fun cloneWithPrefix(prefix: String): PrefixMatcher = Containing(prefix)
    }
}

object CSharpRare {
    enum class Kind { INTERNALS_VISIBLE_TO, EXTERN_ALIAS, CALLING_CONVENTION, FILE_DIRECTIVE, PACKAGE_ID, PACKAGE_VERSION }

    /** [prefix] is what is typed of the word; [argument] is the package id of a version. */
    class Place(val kind: Kind, val prefix: String, val argument: String? = null)

    val CALLING_CONVENTIONS = listOf("Cdecl", "Stdcall", "Thiscall", "Fastcall", "SuppressGCTransition")
    val FILE_DIRECTIVES = listOf("package", "sdk", "property", "project")

    private val INTERNALS = Regex("""^\s*\[\s*assembly\s*:\s*(?:System\s*\.\s*Runtime\s*\.\s*CompilerServices\s*\.\s*)?InternalsVisibleTo(?:Attribute)?\s*\(\s*"([^"]*)$""")
    private val EXTERN = Regex("""^\s*extern\s+alias\s+(\w*)$""")
    private val CONVENTION = Regex("""\bdelegate\s*\*\s*unmanaged\s*\[(?:[^\]]*,)?\s*(\w*)$""")
    private val DIRECTIVE = Regex("""^\s*#:(\w*)$""")
    private val PACKAGE_ID = Regex("""^\s*#:package\s+([\w.\-]*)$""")
    private val PACKAGE_VERSION = Regex("""^\s*#:package\s+([\w.\-]+)@([\w.\-+]*)$""")

    /** What the [line] up to the caret asks for; null where it is none of the rare places. */
    fun placeOf(line: String): Place? {
        INTERNALS.find(line)?.let { return Place(Kind.INTERNALS_VISIBLE_TO, it.groupValues[1]) }
        EXTERN.find(line)?.let { return Place(Kind.EXTERN_ALIAS, it.groupValues[1]) }
        CONVENTION.find(line)?.let { return Place(Kind.CALLING_CONVENTION, it.groupValues[1]) }
        PACKAGE_VERSION.find(line)?.let { return Place(Kind.PACKAGE_VERSION, it.groupValues[2], it.groupValues[1]) }
        PACKAGE_ID.find(line)?.let { return Place(Kind.PACKAGE_ID, it.groupValues[1]) }
        DIRECTIVE.find(line)?.let { return Place(Kind.FILE_DIRECTIVE, it.groupValues[1]) }
        return null
    }

    /** `Aliases="a,b"` (or `<Aliases>`) of the references of a project file: the names `extern alias` can use. */
    fun externAliases(projectXml: String): List<String> {
        val root = try { JDOMUtil.load(projectXml) } catch (_: Exception) { return emptyList() }
        val found = LinkedHashSet<String>()
        fun walk(e: Element) {
            if (e.name in REFERENCES) {
                val value = e.getAttributeValue("Aliases") ?: e.children.firstOrNull { it.name == "Aliases" }?.textTrim
                value?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() && it != "global" }?.let(found::addAll)
            }
            e.children.forEach { walk(it) }
        }
        walk(root)
        return found.toList()
    }

    private val REFERENCES = setOf("ProjectReference", "Reference", "PackageReference")
}
