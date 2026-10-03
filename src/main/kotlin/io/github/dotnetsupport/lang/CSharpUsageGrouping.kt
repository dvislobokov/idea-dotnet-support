package io.github.dotnetsupport.lang

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.usages.Usage
import com.intellij.usages.UsageGroup
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewPresentation
import com.intellij.usages.UsageViewSettings
import com.intellij.usages.impl.FileStructureGroupRuleProvider
import com.intellij.usages.impl.rules.UsageGroupingRulesDefaultRanks
import com.intellij.usages.rules.SingleParentUsageGroupingRule
import com.intellij.usages.rules.UsageGroupingRule
import com.intellij.usages.rules.UsageGroupingRuleProvider
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.msbuild.DotNetProjects
import javax.swing.Icon

/**
 * The groups of the Usages view for C#, as in Rider, behind the toggles the IDE already has (no buttons of the plugin):
 * - Group by Usage Type: read / write / invocation / `nameof` / attribute / `new` / base type... ([CSharpUsageKinds]);
 * - Group by Module: the .NET project of the file under the module (a module of the IDE is a folder, not a `.csproj`);
 * - Group by File Structure: the type and the member the usage stands in ([CSharpMemberGroupRuleProvider]).
 *
 * The usages come from the LSP client of the platform (`textDocument/references` of the C# server) as text usages of a file and a range.
 * The usage-type rule of the platform asks such a usage for its type and gets none (it never reaches the `UsageTypeProvider`s), so the
 * kind is given by a rule of this provider of the same rank: the platform's rule stays silent on these usages, this one on every other.
 */
class CSharpUsageGroupingRuleProvider : UsageGroupingRuleProvider {
    override fun getActiveRules(project: Project, settings: UsageViewSettings, presentation: UsageViewPresentation?): Array<UsageGroupingRule> =
        listOfNotNull(UsageKindRule.takeIf { settings.isGroupByUsageType }, DotNetProjectRule.takeIf { settings.isGroupByModule }).toTypedArray()

    private object UsageKindRule : SingleParentUsageGroupingRule() {
        override fun getParentGroupFor(usage: Usage, targets: Array<out UsageTarget>): UsageGroup? {
            val (file, range) = CSharpUsages.locate(usage) ?: return null
            return UsageKindGroup(CSharpUsages.analysis(file).kindOf(range))
        }

        override fun getRank(): Int = UsageGroupingRulesDefaultRanks.USAGE_TYPE.absoluteRank
    }

    private object DotNetProjectRule : SingleParentUsageGroupingRule() {
        override fun getParentGroupFor(usage: Usage, targets: Array<out UsageTarget>): UsageGroup? {
            val (file, _) = CSharpUsages.locate(usage) ?: return null
            val project = file.virtualFile?.let(DotNetProjects::findOwningProject) ?: return null
            return DotNetProjectGroup(file.project, project)
        }

        override fun getRank(): Int = UsageGroupingRulesDefaultRanks.AFTER_MODULE.absoluteRank
    }
}

/** Group by File Structure for C#: the types around a usage, then its member (method, property, constructor...), as Java does it. */
class CSharpMemberGroupRuleProvider : FileStructureGroupRuleProvider {
    override fun getUsageGroupingRule(project: Project): UsageGroupingRule = MemberRule

    private object MemberRule : UsageGroupingRule {
        override fun getParentGroupsFor(usage: Usage, targets: Array<out UsageTarget>): List<UsageGroup> {
            val (file, range) = CSharpUsages.locate(usage) ?: return emptyList()
            val virtualFile = file.virtualFile ?: return emptyList()
            val path = CSharpUsages.containers(CSharpStructure.of(file), range.startOffset)
            return path.mapIndexed { index, declaration -> MemberGroup(file.project, virtualFile, declaration, path.getOrNull(index - 1)?.kind, path.take(index + 1).map { it.name + it.parameters.orEmpty() }) }
        }

        override fun getRank(): Int = UsageGroupingRulesDefaultRanks.AFTER_FILE_STRUCTURE.absoluteRank
    }
}

internal object CSharpUsages {
    /** The C# file and the range of a usage of the Usages view; null for a usage of anything else. */
    fun locate(usage: Usage): Pair<PsiFile, TextRange>? {
        val info = (usage as? UsageInfo2UsageAdapter)?.usageInfo ?: return null
        val file = info.file as? CSharpFile ?: return null
        val segment = info.segment ?: return null
        return file to TextRange(segment.startOffset, segment.endOffset)
    }

    /** The lexed file, once per change of it: every usage of the file is classified on the same tokens. */
    fun analysis(file: PsiFile): CSharpUsageKinds.Analysis = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(CSharpUsageKinds.Analysis(file.viewProvider.contents, CSharpStructure.of(file)), file)
    }

    /** The types and the member around [offset], the outermost first; namespaces are left to Group by Package / Directory. */
    fun containers(structure: CSharpFileStructure, offset: Int): List<CSharpDeclarationInfo> = structure.pathTo(offset).filter { it.kind != DeclarationKind.NAMESPACE }
}

private data class UsageKindGroup(val kind: CSharpUsageKind) : UsageGroup {
    override fun getPresentableGroupText(): String = kind.title
    override fun compareTo(other: UsageGroup): Int = if (other is UsageKindGroup) kind.compareTo(other.kind) else presentableGroupText.compareTo(other.presentableGroupText)
}

private class DotNetProjectGroup(private val project: Project, private val projectFile: VirtualFile) : UsageGroup {
    override fun getPresentableGroupText(): String = projectFile.nameWithoutExtension
    override fun getIcon(): Icon = DotNetIcons.Project
    override fun isValid(): Boolean = projectFile.isValid
    override fun navigate(requestFocus: Boolean) = OpenFileDescriptor(project, projectFile).navigate(requestFocus)
    override fun canNavigate(): Boolean = projectFile.isValid
    override fun canNavigateToSource(): Boolean = canNavigate()
    override fun compareTo(other: UsageGroup): Int = presentableGroupText.compareTo(other.presentableGroupText, ignoreCase = true)
    override fun equals(other: Any?): Boolean = other is DotNetProjectGroup && other.projectFile == projectFile
    override fun hashCode(): Int = projectFile.hashCode()
}

/** A type or a member of a file; equal to the same declaration of the same file however many usages stand in it. */
private class MemberGroup(private val project: Project, private val file: VirtualFile, declaration: CSharpDeclarationInfo, parent: DeclarationKind?, private val path: List<String>) : UsageGroup {
    private val text = declaration.name + declaration.parameters.orEmpty()
    private val icon = CSharpIcons.of(declaration.kind, declaration.modifiers, parent)
    private val offset = declaration.nameRange.startOffset

    override fun getPresentableGroupText(): String = text
    override fun getIcon(): Icon = icon
    override fun isValid(): Boolean = file.isValid
    override fun navigate(requestFocus: Boolean) = OpenFileDescriptor(project, file, offset).navigate(requestFocus)
    override fun canNavigate(): Boolean = file.isValid
    override fun canNavigateToSource(): Boolean = canNavigate()
    override fun compareTo(other: UsageGroup): Int = if (other is MemberGroup) offset.compareTo(other.offset) else text.compareTo(other.presentableGroupText)
    override fun equals(other: Any?): Boolean = other is MemberGroup && other.file == file && other.path == path
    override fun hashCode(): Int = file.hashCode() * 31 + path.hashCode()
}
