package io.github.dotnetsupport.frameworks.unityyaml

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.ByteSequence
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider
import com.intellij.util.ProcessingContext
import com.intellij.util.Processor
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration
import io.github.dotnetsupport.frameworks.unity.UnityAssetParser
import io.github.dotnetsupport.frameworks.unity.UnityAssetUsages
import io.github.dotnetsupport.frameworks.unity.UnityAssets
import io.github.dotnetsupport.frameworks.unity.UnityProjects
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * The text assets of Unity in the editor (UNITY_PLAN.md, the asset index; needs the YAML plugin): scenes, prefabs, assets and `.meta`
 * files open as YAML (by their content: `.asset`, `.mat`, `.anim` are other things outside Unity), their `guid:` lead to the script or
 * asset of that GUID and `m_MethodName:` to the method of a UnityEvent, Find Usages of a script, a method or a serialized field lists
 * them, a component whose script is gone is reported.
 */
object UnityYaml {
    /** A file Unity wrote as text: `%YAML 1.1` with the tag of Unity, or a `.meta`. */
    fun isUnityYaml(file: PsiFile?): Boolean {
        val virtualFile = file?.originalFile?.virtualFile ?: return false
        if (virtualFile.name.endsWith(".meta")) return true
        return UnityAssetParser.isUnityYaml(file.viewProvider.contents)
    }

    /** The class a script defines: the type named as its file (Unity finds the class of a script so), else the file. */
    fun scriptElement(project: Project, file: VirtualFile): PsiElement? {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        if (!file.extension.equals("cs", ignoreCase = true)) return psi
        return PsiTreeUtil.findChildrenOfType(psi, CSharpBaseTypeDeclaration::class.java).firstOrNull { it.identifier?.text == file.nameWithoutExtension } ?: psi
    }

    /** The class [target] of a UnityEvent call names: `guid:<script>` or the full name (found by the file named as the class). */
    fun targetClass(project: Project, target: String): CSharpBaseTypeDeclaration? {
        if (target.startsWith("guid:")) return UnityAssets.fileOf(project, target.removePrefix("guid:"))?.let { scriptElement(project, it) } as? CSharpBaseTypeDeclaration
        val shortName = target.substringAfterLast('.')
        return FilenameIndex.getVirtualFilesByName("$shortName.cs", GlobalSearchScope.projectScope(project)).asSequence()
            .mapNotNull { scriptElement(project, it) as? CSharpBaseTypeDeclaration }
            .firstOrNull { UnityAssetUsages.qualifiedName(it) == target }
    }

    fun method(type: CSharpBaseTypeDeclaration, name: String): CSharpMethodDeclaration? =
        PsiTreeUtil.findChildrenOfType(type, CSharpMethodDeclaration::class.java).firstOrNull {
            it.identifier?.text == name && PsiTreeUtil.getParentOfType(it, CSharpBaseTypeDeclaration::class.java) == type
        }

    /** Whether the GUIDs of the packages (`Library/PackageCache`) are indexed: otherwise a script of a package would look missing. */
    fun packagesIndexed(project: Project, file: VirtualFile): Boolean {
        val root = UnityProjects.rootOf(file) ?: return false
        val cache = root.findFileByRelativePath("Library/PackageCache") ?: return true
        val index = ProjectFileIndex.getInstance(project)
        return !index.isExcluded(cache) && (index.isInContent(cache) || index.isInLibrary(cache))
    }

    const val SCRIPT_FILE_ID = "11500000"
}

/** Scenes, prefabs, assets and `.meta` files of Unity are YAML: by the header Unity writes, whatever the extension. */
class UnityYamlFileTypeDetector : FileTypeRegistry.FileTypeDetector {
    override fun detect(file: VirtualFile, firstBytes: ByteSequence, firstCharsIfText: CharSequence?): FileType? {
        val text = firstCharsIfText ?: return null
        if (UnityAssetParser.isUnityYaml(text)) return YAMLFileType.YML
        if (file.name.endsWith(".meta") && text.startsWith("fileFormatVersion:")) return YAMLFileType.YML
        return null
    }

    override fun getDesiredContentPrefixLength(): Int = 256
}

/**
 * Unity's files are Unity's YAML, not the YAML of the spec (`--- !u!114 &123 stripped`, the block sequences the YAML plugin calls invalid):
 * Unity reads them, the user does not write them; the errors of the YAML plugin are not shown in them, the warnings of the plugin are.
 */
class UnityYamlErrorFilter : HighlightInfoFilter {
    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        if (file == null || highlightInfo.severity != HighlightSeverity.ERROR || file.fileType != YAMLFileType.YML) return true
        return !UnityYaml.isUnityYaml(file)
    }
}

/**
 * The inspections of the YAML plugin say nothing useful of a file Unity writes: every `&1100` is "never used" (Unity refers to its objects
 * by `{fileID: 1100}`, not by YAML aliases), keys repeat by design. Off in Unity's files; the plugin's own inspections stay.
 */
class UnityYamlInspectionSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean =
        toolId.startsWith("YAML") && UnityYaml.isUnityYaml(element.containingFile)

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> = SuppressQuickFix.EMPTY_ARRAY
}

/** `guid: 0f1e…` → the script (its class) or asset of that GUID; `m_MethodName: Open` → the method the UnityEvent calls. */
class UnityYamlReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(YAMLScalar::class.java), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
                val keyValue = scalar.parent as? YAMLKeyValue ?: return PsiReference.EMPTY_ARRAY
                val range = TextRange(0, scalar.textLength)
                return when (keyValue.keyText) {
                    "guid" -> if (GUID.matches(scalar.textValue) && UnityYaml.isUnityYaml(scalar.containingFile)) arrayOf(GuidReference(scalar, range)) else PsiReference.EMPTY_ARRAY
                    "m_MethodName" -> if (scalar.textValue.isNotBlank() && UnityYaml.isUnityYaml(scalar.containingFile)) arrayOf(MethodReference(scalar, range)) else PsiReference.EMPTY_ARRAY
                    else -> PsiReference.EMPTY_ARRAY
                }
            }
        })
    }

    private class GuidReference(scalar: YAMLScalar, range: TextRange) : PsiReferenceBase<YAMLScalar>(scalar, range, true) {
        override fun resolve(): PsiElement? = UnityAssets.fileOf(element.project, element.textValue)?.let { UnityYaml.scriptElement(element.project, it) }
    }

    private class MethodReference(scalar: YAMLScalar, range: TextRange) : PsiReferenceBase<YAMLScalar>(scalar, range, true) {
        override fun resolve(): PsiElement? {
            val file = element.containingFile ?: return null
            val offset = element.textRange.startOffset
            val usage = UnityAssetParser.index(file.viewProvider.contents).values.flatten()
                .firstOrNull { it.kind == UnityAssetParser.HANDLER && it.offset == offset } ?: return null
            val type = UnityYaml.targetClass(element.project, usage.target) ?: return null
            return UnityYaml.method(type, usage.name)
        }
    }

    private companion object {
        val GUID = Regex("[0-9a-fA-F]{32}")
    }
}

/** A use of a script, method or field in an asset, for Find Usages: the GUID of `m_Script`, a method's name, a field's key. */
class UnityAssetReference(element: PsiElement, range: TextRange, private val target: PsiElement) : PsiReferenceBase<PsiElement>(element, range, true) {
    override fun resolve(): PsiElement = target
    override fun isReferenceTo(element: PsiElement): Boolean = element == target || element.manager.areElementsEquivalent(element, target)
    override fun handleElementRename(newElementName: String): PsiElement = element // the rename of the native tree writes the code alone
}

/** Find Usages and Show Usages of a script class, a method, a serialized field: their uses in the scenes, prefabs and animations. */
class UnityAssetReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = parameters.elementToSearch
        val scope = parameters.effectiveSearchScope
        val project = target.project
        for (place in UnityAssetUsages.places(target)) {
            if (!scope.contains(place.file)) continue
            val file = PsiManager.getInstance(project).findFile(place.file) ?: continue
            val leaf = file.findElementAt(place.offset) ?: continue
            // the scalar or key the place is in (the whole text of a file too large for the YAML parser)
            val element = PsiTreeUtil.getParentOfType(leaf, YAMLScalar::class.java, false) ?: leaf
            val start = place.offset - element.textRange.startOffset
            if (start < 0 || start + place.length > element.textLength) continue
            if (!consumer.process(UnityAssetReference(element, TextRange.from(start, place.length), target))) return
        }
    }
}

/** The uses in the assets are a group of their own in the usages view. */
class UnityAssetUsageTypeProvider : UsageTypeProvider {
    override fun getUsageType(element: PsiElement): UsageType? = if (UnityYaml.isUnityYaml(element.containingFile)) ASSET_USAGE else null

    private companion object {
        val ASSET_USAGE = UsageType { "Usage in Unity assets" }
    }
}

/**
 * A component whose script is gone (`m_Script` with the file id of a script and a GUID no `.meta` has): Unity shows "The referenced script
 * (Unknown) on this Behaviour is missing!" and drops its values on the next save. Reported where the packages' GUIDs are indexed only.
 */
class UnityMissingScriptInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        val virtualFile = file.originalFile.virtualFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (virtualFile.name.endsWith(".meta") || !UnityYaml.isUnityYaml(file) || !UnityYaml.packagesIndexed(file.project, virtualFile)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val keyValue = element as? YAMLKeyValue ?: return
                if (keyValue.keyText != "m_Script") return
                val mapping = keyValue.value as? YAMLMapping ?: return
                if (mapping.getKeyValueByKey("fileID")?.valueText != UnityYaml.SCRIPT_FILE_ID) return
                val guid = mapping.getKeyValueByKey("guid") ?: return
                val value = guid.value ?: return
                if (UnityAssets.isKnownGuid(element.project, guid.valueText)) return
                holder.registerProblem(value, "Missing script: no script of the project has the GUID ${guid.valueText}")
            }
        }
    }
}
