package io.github.dotnetsupport.msbuild

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import org.jdom.Element

/** What `$(`, `@(` and `%(` in an MSBuild file are being completed to, and where the names come from. Pure functions, tested without an editor. */
object MsBuildReferences {
    enum class Kind { PROPERTY, ITEM, METADATA }

    /** [prefix] is what is typed of the name; [qualifier] is the item of `%(Item.Meta`. */
    data class Context(val kind: Kind, val prefix: String, val qualifier: String? = null)

    /** The reference that is open at the end of [before] (the value up to the caret): `Include="$(Mod|` -> property `Mod`; null when none or when it is a property function. */
    fun contextAt(before: String): Context? {
        var i = before.length - 1
        while (i >= 1) {
            val c = before[i]
            if (c == ')') return null
            if (c == '(' && before[i - 1] in "$@%") {
                val rest = before.substring(i + 1)
                val kind = when (before[i - 1]) { '$' -> Kind.PROPERTY; '@' -> Kind.ITEM; else -> Kind.METADATA }
                if (!rest.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) return null
                if (kind == Kind.METADATA && '.' in rest) return Context(kind, rest.substringAfterLast('.'), rest.substringBeforeLast('.'))
                if (kind != Kind.METADATA && '.' in rest) return null
                return Context(kind, rest)
            }
            i--
        }
        return null
    }

    /** What an MSBuild file declares by itself: property names, item types, and the metadata of each item type (lower case type -> names). */
    class Declared(val properties: Set<String>, val items: Set<String>, val metadata: Map<String, Set<String>>)

    private val NOT_METADATA = setOf("include", "update", "remove", "exclude", "condition", "label", "keepmetadata", "removemetadata", "keepduplicates", "matchonmetadata")

    fun declared(texts: List<CharSequence>): Declared {
        val props = LinkedHashSet<String>()
        val items = LinkedHashSet<String>()
        val meta = LinkedHashMap<String, MutableSet<String>>()
        for (text in texts) {
            val root = try { JDOMUtil.load(text) } catch (_: Exception) { continue }
            fun walk(e: Element, parent: Element?) {
                when (parent?.name) {
                    "PropertyGroup" -> props += e.name
                    "ItemGroup" -> {
                        items += e.name
                        val set = meta.getOrPut(e.name.lowercase()) { LinkedHashSet() }
                        e.attributes.map { it.name }.filterTo(set) { it.lowercase() !in NOT_METADATA }
                        e.children.mapTo(set) { it.name }
                    }
                }
                if (e.name == "Output") e.getAttributeValue("PropertyName")?.let { props += it }
                e.children.forEach { walk(it, e) }
            }
            walk(root, null)
        }
        return Declared(props, items, meta)
    }

    val WELL_KNOWN_PROPERTIES = listOf(
        "MSBuildProjectDirectory", "MSBuildProjectFile", "MSBuildProjectName", "MSBuildProjectExtension", "MSBuildProjectFullPath", "MSBuildProjectDirectoryNoRoot",
        "MSBuildThisFileDirectory", "MSBuildThisFile", "MSBuildThisFileName", "MSBuildThisFileExtension", "MSBuildThisFileFullPath", "MSBuildThisFileDirectoryNoRoot",
        "MSBuildStartupDirectory", "MSBuildBinPath", "MSBuildToolsPath", "MSBuildToolsVersion", "MSBuildRuntimeType", "MSBuildSDKsPath", "MSBuildExtensionsPath",
        "MSBuildExtensionsPath32", "MSBuildExtensionsPath64", "MSBuildProgramFiles32", "MSBuildNodeCount", "MSBuildProjectDefaultTargets", "MSBuildAllProjects",
        "MSBuildLastTaskResult", "MSBuildOverrideTasksPath", "MSBuildFrameworkToolsPath", "MSBuildUserExtensionsPath", "MSBuildVersion", "MSBuildRuntimeVersion",
        "Configuration", "Platform", "OutputPath", "IntermediateOutputPath", "BaseIntermediateOutputPath", "BaseOutputPath", "TargetFramework", "TargetFrameworks",
        "TargetFrameworkIdentifier", "TargetFrameworkVersion", "TargetPlatformIdentifier",
        "SolutionDir", "SolutionPath", "SolutionName", "SolutionFileName", "SolutionExt", "ProjectDir", "ProjectPath", "ProjectName", "ProjectFileName", "ProjectExt",
        "TargetPath", "TargetDir", "TargetName", "TargetFileName", "TargetExt", "OutDir", "OS", "NuGetPackageRoot", "NuGetPackageFolders", "DOTNET_HOST_PATH",
        "RuntimeIdentifier", "AssemblyName", "RootNamespace", "DefineConstants", "MSBuildProgramFiles32", "VisualStudioVersion", "VSToolsPath", "DevEnvDir",
        "NetCoreTargetingPackRoot", "DOTNET_ROOT", "HOME", "USERPROFILE", "TEMP", "PATH",
    )

    val WELL_KNOWN_METADATA = listOf(
        "FullPath", "RootDir", "Filename", "Extension", "RelativeDir", "Directory", "RecursiveDir", "Identity", "ModifiedTime", "CreatedTime", "AccessedTime",
        "DefiningProjectDirectory", "DefiningProjectExtension", "DefiningProjectFullPath", "DefiningProjectName",
    )

    /** Names for [context]; [enclosingItem] is the item whose metadata `%(` completes where no item is named. Without duplicates, case-insensitively. */
    fun names(context: Context, declared: Declared, enclosingItem: String?): List<String> {
        val raw: List<String> = when (context.kind) {
            Kind.PROPERTY -> declared.properties.toList() + MsBuildSchema.properties.map { it.name } + WELL_KNOWN_PROPERTIES
            Kind.ITEM -> declared.items.toList() + MsBuildSchema.items.map { it.name }
            Kind.METADATA -> {
                val item = context.qualifier ?: enclosingItem
                if (item != null) declared.metadata[item.lowercase()].orEmpty().toList() + MsBuildSchema.metadataOf(item).map { it.name } + WELL_KNOWN_METADATA
                else declared.metadata.values.flatten() + MsBuildSchema.commonMetadata.map { it.name } + WELL_KNOWN_METADATA
            }
        }
        val seen = HashSet<String>()
        return raw.filter { seen.add(it.lowercase()) }
    }

    /** `Import Project=` takes any MSBuild file, `ProjectReference Include=` only a project. */
    val IMPORT_EXTENSIONS = setOf("props", "targets", "csproj", "fsproj", "vbproj", "projitems", "proj", "tasks", "shproj")
    val PROJECT_EXTENSIONS = setOf("csproj", "fsproj", "vbproj")

    fun pathExtensions(tag: String, attribute: String): Set<String>? = when {
        tag == "Import" && attribute.equals("Project", ignoreCase = true) -> IMPORT_EXTENSIONS
        tag == "ProjectReference" && attribute.equals("Include", ignoreCase = true) -> PROJECT_EXTENSIONS
        else -> null
    }

    /** `..\Lib\X` -> directory part `..\Lib\` and the typed name `X`; `$(MSBuildThisFileDirectory)` is the directory of the file itself. */
    fun splitPath(typed: String): Pair<String, String> {
        val cut = maxOf(typed.lastIndexOf('/'), typed.lastIndexOf('\\'))
        return typed.substring(0, cut + 1) to typed.substring(cut + 1)
    }

    fun relativeDirectory(dirPart: String): String =
        dirPart.replace("$(MSBuildThisFileDirectory)", "").replace("$(MSBuildProjectDirectory)", "").replace("$(MSBuildProjectDirectory)/", "").replace('\\', '/')

    /** Entries of [directory]: folders (shown with the separator) and the files with one of [extensions]. Hidden entries and `bin` / `obj` are left out. */
    fun pathEntries(directory: VirtualFile, extensions: Set<String>, separator: Char): List<String> {
        val result = ArrayList<String>()
        for (child in directory.children.orEmpty().sortedBy { it.name.lowercase() }) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) { if (child.name.lowercase() !in setOf("bin", "obj", "node_modules")) result += child.name + separator }
            else if (child.extension?.lowercase() in extensions) result += child.name
        }
        return result
    }
}

/** `$(Property)`, `@(Item)`, `%(Metadata)` in any attribute or text of an MSBuild file, and the paths of `Import` and `ProjectReference`. */
class MsBuildReferenceCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (!MsBuildFiles.isMsBuild(parameters.originalFile)) return
        val position = parameters.position
        val value = position.parent as? XmlAttributeValue
        val attribute = value?.parent as? XmlAttribute
        val text = if (value == null) PsiTreeUtil.getParentOfType(position, XmlText::class.java, false) else null
        val start = value?.valueTextRange?.startOffset ?: text?.textRange?.startOffset ?: return
        val tag: XmlTag = attribute?.parent ?: text?.parentTag ?: return
        val before = parameters.editor.document.charsSequence.subSequence(start.coerceAtMost(parameters.offset), parameters.offset).toString()

        val context = MsBuildReferences.contextAt(before)
        if (context != null) {
            val file = parameters.originalFile
            val enclosing = generateSequence(tag) { it.parentTag }.firstOrNull { it.parentTag?.localName == "ItemGroup" }?.localName
            val names = MsBuildReferences.names(context, declaredFor(file), enclosing)
            val matching = result.withPrefixMatcher(context.prefix).caseInsensitive()
            val declared = names.size
            names.forEachIndexed { index, name ->
                val builder = LookupElementBuilder.create(name).withIcon(icon(context.kind)).withTypeText(context.kind.name.lowercase(), true).withCaseSensitivity(false)
                    .withTailText(MsBuildSchema.property(name).takeIf { context.kind == MsBuildReferences.Kind.PROPERTY }?.let { "  " + StringUtil.shortenTextWithEllipsis(it.doc.substringBefore(". "), 60, 0) }, true)
                    .withInsertHandler(CloseParenthesis)
                matching.addElement(PrioritizedLookupElement.withPriority(builder, (declared - index).toDouble()))
            }
            result.stopHere()
            return
        }

        val extensions = attribute?.let { MsBuildReferences.pathExtensions(tag.localName, it.name) } ?: return
        if (';' in before) return
        val (dirPart, name) = MsBuildReferences.splitPath(before)
        val base = parameters.originalFile.virtualFile?.parent ?: return
        val directory = (if (dirPart.isEmpty() || MsBuildReferences.relativeDirectory(dirPart).isEmpty()) base else base.findFileByRelativePath(MsBuildReferences.relativeDirectory(dirPart))) ?: return
        if (!directory.isDirectory) return
        val separator = if ('/' in before && '\\' !in before) '/' else '\\'
        val matching = result.withPrefixMatcher(name).caseInsensitive()
        val entries = MsBuildReferences.pathEntries(directory, extensions, separator) + if (dirPart.replace('\\', '/').split('/').all { it.isEmpty() || it == ".." } && (name.isEmpty() || "..".startsWith(name))) listOf("..$separator") else emptyList()
        for (entry in entries) {
            val isDirectory = entry.last() == separator
            matching.addElement(LookupElementBuilder.create(entry).withIcon(if (isDirectory) AllIcons.Nodes.Folder else AllIcons.FileTypes.Xml).withCaseSensitivity(false)
                .withInsertHandler(if (isDirectory) ReopenAfterFolder else null))
        }
        result.stopHere()
    }

    /** Declarations of the file, of what it imports explicitly and of the `Directory.*` files above it. */
    private fun declaredFor(file: com.intellij.psi.PsiFile): MsBuildReferences.Declared {
        val original = file.originalFile
        val texts = ArrayList<CharSequence>()
        texts += file.viewProvider.contents
        val directory = original.virtualFile?.parent
        if (directory != null) {
            val seen = HashSet<String>()
            fun add(vf: VirtualFile?) { if (vf != null && !vf.isDirectory && seen.add(vf.path) && vf != original.virtualFile) runCatching { texts += String(vf.contentsToByteArray(), vf.charset) } }
            for (import in MsBuildFiles.project(file).imports) add(directory.findFileByRelativePath(MsBuildReferences.relativeDirectory(import)))
            for (dir in generateSequence(directory) { it.parent }) for (name in DIRECTORY_FILES) add(dir.findChild(name))
        }
        return MsBuildReferences.declared(texts)
    }

    private fun icon(kind: MsBuildReferences.Kind) = when (kind) {
        MsBuildReferences.Kind.PROPERTY -> AllIcons.Nodes.Property
        MsBuildReferences.Kind.ITEM -> AllIcons.Nodes.Tag
        MsBuildReferences.Kind.METADATA -> AllIcons.Nodes.Parameter
    }

    private object CloseParenthesis : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val document = context.document
            if (document.charsSequence.getOrNull(context.tailOffset) == ')') { context.editor.caretModel.moveToOffset(context.tailOffset + 1); return }
            document.insertString(context.tailOffset, ")")
            context.editor.caretModel.moveToOffset(context.tailOffset)
        }
    }

    private object ReopenAfterFolder : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
        }
    }

    private val DIRECTORY_FILES = listOf("Directory.Build.props", "Directory.Build.targets", "Directory.Packages.props")
}
