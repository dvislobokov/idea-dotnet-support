package io.github.dotnetsupport.msbuild

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.nuget.NuGetClient
import io.github.dotnetsupport.nuget.NuGetPackageInfo
import io.github.dotnetsupport.nuget.NuGetService
import io.github.dotnetsupport.nuget.NuGetSettings
import io.github.dotnetsupport.nuget.NuGetVersion
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

/**
 * What the feeds say, for completion in project files: the same client and sources as the NuGet window, with a short-lived
 * cache, because completion asks again on every typed character. Blocking: see [MsBuildPackageCompletionContributor].
 */
@Service(Service.Level.PROJECT)
class PackageCompletionService(private val project: Project) {
    private class Cached<T>(val value: T, val time: Long)

    private var client: NuGetClient? = null
    private var fixedSources: List<String>? = null
    private val searches = ConcurrentHashMap<String, Cached<List<NuGetPackageInfo>>>()
    private val versionLists = ConcurrentHashMap<String, Cached<List<String>>>()
    @Volatile private var sources: Cached<List<String>>? = null

    @TestOnly
    fun useForTests(client: NuGetClient?, sources: List<String>?) {
        this.client = client
        fixedSources = sources
        searches.clear(); versionLists.clear()
    }

    private fun client(): NuGetClient = client ?: NuGetService.getInstance(project).client

    /** `dotnet nuget list source` takes a fraction of a second: not on every keystroke. */
    private fun sources(): List<String> {
        fixedSources?.let { return it }
        sources?.takeIf { fresh(it.time, SOURCES_TTL_MS) }?.let { return it.value }
        return NuGetService.getInstance(project).sources().also { sources = Cached(it, System.currentTimeMillis()) }
    }

    fun search(query: String, includePrerelease: Boolean): List<NuGetPackageInfo> = cached(searches, "$includePrerelease|${query.lowercase()}") {
        client().search(query, includePrerelease, sources(), take = SEARCH_SIZE)
    }

    /** Newest first. */
    fun versions(packageId: String): List<String> = cached(versionLists, packageId.lowercase()) {
        client().versions(packageId, sources()).mapNotNull(NuGetVersion::parse).sortedDescending().map { it.text }
    }

    private fun <T> cached(cache: ConcurrentHashMap<String, Cached<T>>, key: String, load: () -> T): T {
        cache[key]?.takeIf { fresh(it.time, RESULTS_TTL_MS) }?.let { return it.value }
        if (cache.size > MAX_ENTRIES) cache.clear()
        return load().also { cache[key] = Cached(it, System.currentTimeMillis()) }
    }

    private fun fresh(time: Long, ttl: Long): Boolean = System.currentTimeMillis() - time < ttl

    companion object {
        private const val SEARCH_SIZE = 30
        private const val MAX_ENTRIES = 200
        private const val RESULTS_TTL_MS = 5 * 60 * 1000L
        private const val SOURCES_TTL_MS = 10 * 60 * 1000L

        fun getInstance(project: Project): PackageCompletionService = project.service()
    }
}

/**
 * Package ids in `Include` of PackageReference / PackageVersion / ..., and versions in their `Version`: from the feeds of
 * the solution. The request runs on a pooled thread and is waited for with cancellation checks: completion holds a read
 * action, and typing the next character has to cancel it at once rather than wait for the network.
 */
class MsBuildPackageCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (!MsBuildFiles.isMsBuild(parameters.originalFile)) return
        val position = parameters.position
        val value = position.parent as? XmlAttributeValue
        val attribute = value?.parent as? XmlAttribute
        val tag = attribute?.parent ?: PsiTreeUtil.getParentOfType(position, XmlText::class.java, false)?.parentTag?.parentTag
        if (tag == null || tag.localName.lowercase() !in PACKAGE_ITEMS) return
        // <PackageReference Include="X"><Version>|</Version></PackageReference>
        val name = attribute?.name ?: PsiTreeUtil.getParentOfType(position, XmlText::class.java, false)?.parentTag?.localName ?: return
        val start = value?.valueTextRange?.startOffset ?: PsiTreeUtil.getParentOfType(position, XmlText::class.java, false)!!.textRange.startOffset
        val typed = parameters.editor.document.charsSequence.subSequence(start.coerceAtMost(parameters.offset), parameters.offset).toString().trim()
        val service = PackageCompletionService.getInstance(parameters.originalFile.project)
        when {
            attribute != null && name.lowercase() in ID_ATTRIBUTES -> addPackages(result, typed, service, tag)
            name.lowercase() in VERSION_NAMES -> addVersions(result, typed, service, packageId(tag) ?: return)
        }
    }

    private fun addPackages(result: CompletionResultSet, typed: String, service: PackageCompletionService, tag: XmlTag) {
        if (typed.length < MIN_QUERY) return
        val includePrerelease = NuGetSettings.getInstance().includePrerelease
        val found = await { service.search(typed, includePrerelease) }
        // the feed has done the matching ("json" finds Newtonsoft.Json), and it is asked again for a longer query
        val all = result.withPrefixMatcher(AnyMatcher(typed))
        all.restartCompletionOnAnyPrefixChange()
        val declaresVersion = tag.localName.lowercase() in VERSIONED_ITEMS && tag.getAttribute("Version") == null && tag.findFirstSubTag("Version") == null && !usesCentralVersions(tag)
        found.forEachIndexed { index, info ->
            val builder = LookupElementBuilder.create(info, info.id)
                .withIcon(DotNetIcons.NuGet)
                .withTailText("  ${info.version}", true)
                .withTypeText(downloads(info.totalDownloads) + if (info.isVerified) " ✓" else "", true)
                .withInsertHandler(if (declaresVersion) VersionAfterId else null)
            // the order of the feed is its relevance
            all.addElement(PrioritizedLookupElement.withPriority(builder, (found.size - index).toDouble()))
        }
    }

    private fun addVersions(result: CompletionResultSet, typed: String, service: PackageCompletionService, packageId: String) {
        val versions = await { service.versions(packageId) }
        // prerelease versions by the setting, or as soon as one is being typed: 9.0.0-
        val shown = versions.filter { NuGetSettings.getInstance().includePrerelease || '-' in typed || NuGetVersion.parse(it)?.isPrerelease != true }
        val matching = result.withPrefixMatcher(typed)
        shown.forEachIndexed { index, version ->
            val builder = LookupElementBuilder.create(version).withTypeText(if (index == 0) "latest" else packageId, true)
            matching.addElement(PrioritizedLookupElement.withPriority(builder, (shown.size - index).toDouble()))
        }
    }

    private fun packageId(tag: XmlTag): String? =
        (tag.getAttributeValue("Include") ?: tag.getAttributeValue("Update"))?.trim()?.takeIf { it.isNotEmpty() && '$' !in it && ';' !in it }

    /** Central package management: the version is in Directory.Packages.props, a Version here is an error (NU1008). */
    private fun usesCentralVersions(tag: XmlTag): Boolean {
        if (tag.localName.equals("PackageVersion", ignoreCase = true)) return false
        val directory = tag.containingFile.originalFile.virtualFile?.parent ?: return false
        return generateSequence(directory) { it.parent }.any { it.findChild("Directory.Packages.props") != null }
    }

    private fun <T> await(load: () -> List<T>): List<T> = try {
        ApplicationUtil.runWithCheckCanceled(Callable { load() }, ProgressManager.getInstance().progressIndicator ?: com.intellij.openapi.progress.EmptyProgressIndicator())
    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
        throw e
    } catch (_: Exception) {
        emptyList() // a feed that is down is no reason to break completion
    }

    /** Everything the feed has returned is shown; [prefix] still decides what is replaced. */
    private class AnyMatcher(prefix: String) : PrefixMatcher(prefix) {
        override fun prefixMatches(name: String): Boolean = true
        override fun cloneWithPrefix(prefix: String): PrefixMatcher = AnyMatcher(prefix)
    }

    /** `Include="Serilog|"` -> `Include="Serilog" Version="4.2.0"`. */
    private object VersionAfterId : InsertHandler<LookupElement> {
        override fun handleInsert(context: com.intellij.codeInsight.completion.InsertionContext, item: LookupElement) {
            val info = item.`object` as? NuGetPackageInfo ?: return
            val text = context.document.charsSequence
            val quote = (context.tailOffset until text.length).firstOrNull { text[it] == '"' || text[it] == '\'' || text[it] == '\n' } ?: return
            if (text[quote] == '\n') return
            val version = " Version=\"${info.version}\""
            context.document.insertString(quote + 1, version)
            context.editor.caretModel.moveToOffset(quote + 1 + version.length)
        }
    }

    companion object {
        private val PACKAGE_ITEMS = setOf("packagereference", "packageversion", "globalpackagereference", "packagedownload", "dotnetclitoolreference")
        private val VERSIONED_ITEMS = setOf("packagereference", "packageversion", "globalpackagereference")
        private val ID_ATTRIBUTES = setOf("include", "update")
        private val VERSION_NAMES = setOf("version", "versionoverride")
        private const val MIN_QUERY = 2

        fun downloads(count: Long): String = when {
            count >= 1_000_000_000 -> "%.1fB".format(java.util.Locale.ROOT, count / 1e9)
            count >= 1_000_000 -> "%.1fM".format(java.util.Locale.ROOT, count / 1e6)
            count >= 1_000 -> "%.1fK".format(java.util.Locale.ROOT, count / 1e3)
            else -> count.toString()
        }
    }
}
