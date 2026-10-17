package io.github.dotnetsupport.frameworks.unityjson

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.icons.AllIcons
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ProcessingContext
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion

/**
 * The assembly definitions of a Unity project (UNITY_PLAN.md, "плюшки"): `.asmdef` / `.asmref` are JSON with a schema of their own (the
 * keys, their meaning, the types), and the names in `"references"` (`.asmref`: `"reference"`) are links — completion of the assembly
 * definitions of the project, Ctrl+B to the `.asmdef`, a warning on one that does not exist. A reference is a name or
 * `"GUID:<guid>"` of the `.asmdef.meta`.
 */
object AssemblyDefinitions {
    class Definition(val name: String, val file: VirtualFile, val guid: String?)

    fun isAssemblyFile(file: VirtualFile?): Boolean = file != null && (file.extension.equals("asmdef", ignoreCase = true) || file.extension.equals("asmref", ignoreCase = true))

    /** Every `.asmdef` of the project with its `name` and the GUID of its `.meta`. */
    fun all(project: Project): List<Definition> = FilenameIndex.getAllFilesByExt(project, "asmdef", GlobalSearchScope.projectScope(project)).mapNotNull { file ->
        val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@mapNotNull null
        val name = NAME.find(text)?.groupValues?.get(1) ?: return@mapNotNull null
        val meta = file.parent?.findChild(file.name + ".meta")?.let { runCatching { VfsUtilCore.loadText(it) }.getOrNull() }
        Definition(name, file, meta?.let { GUID.find(it)?.groupValues?.get(1) })
    }

    fun find(project: Project, reference: String): Definition? {
        val definitions = all(project)
        return if (reference.startsWith("GUID:", ignoreCase = true)) definitions.firstOrNull { it.guid.equals(reference.substring(5), ignoreCase = true) }
        else definitions.firstOrNull { it.name == reference }
    }

    /** The string of `"references": [ … ]` of an `.asmdef`, or of `"reference": "…"` of an `.asmref`. */
    fun isReference(literal: JsonStringLiteral): Boolean {
        if (!isAssemblyFile(literal.containingFile?.originalFile?.virtualFile)) return false
        val property = (literal.parent as? JsonArray)?.parent as? JsonProperty ?: literal.parent as? JsonProperty
        return when (property?.name) {
            "references" -> literal.parent is JsonArray
            "reference" -> property.value == literal
            else -> false
        }
    }

    private val NAME = Regex(""""name"\s*:\s*"([^"]+)"""")
    private val GUID = Regex("""^guid:\s*([0-9a-fA-F]+)""", RegexOption.MULTILINE)
}

class AssemblyDefinitionReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(JsonStringLiteral::class.java), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val literal = element as? JsonStringLiteral ?: return PsiReference.EMPTY_ARRAY
                if (!AssemblyDefinitions.isReference(literal)) return PsiReference.EMPTY_ARRAY
                return arrayOf(AssemblyDefinitionReference(literal))
            }
        })
    }
}

/** `"Game.Core"` → `Game.Core.asmdef`; the variants are the assembly definitions of the project. */
class AssemblyDefinitionReference(literal: JsonStringLiteral) : PsiReferenceBase<JsonStringLiteral>(literal, TextRange(1, maxOf(1, literal.textLength - 1)), true) {
    override fun resolve(): PsiElement? {
        val definition = AssemblyDefinitions.find(element.project, element.value) ?: return null
        return PsiManager.getInstance(element.project).findFile(definition.file)
    }

    override fun getVariants(): Array<Any> {
        val own = element.containingFile?.originalFile?.virtualFile
        return AssemblyDefinitions.all(element.project).filter { it.file != own }
            .map { LookupElementBuilder.create(it.name).withIcon(AllIcons.FileTypes.Json).withTypeText(VfsUtilCore.getRelativePath(it.file, it.file.parent?.parent ?: it.file).orEmpty()) }
            .toTypedArray()
    }
}

/** A reference to an assembly definition the project does not have: Unity reports it and leaves the assembly uncompiled. */
class AssemblyDefinitionReferenceInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!AssemblyDefinitions.isAssemblyFile(holder.file.originalFile.virtualFile)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val literal = element as? JsonStringLiteral ?: return
                if (!AssemblyDefinitions.isReference(literal) || literal.value.isBlank()) return
                if (AssemblyDefinitions.find(literal.project, literal.value) != null) return
                holder.registerProblem(literal, "No assembly definition '${literal.value}' in the project")
            }
        }
    }
}

/** The schema of `.asmdef` (`unityjson/asmdef.schema.json`). */
class AssemblyDefinitionSchemaProviderFactory : JsonSchemaProviderFactory, DumbAware {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(AssemblyDefinitionSchemaProvider)
}

object AssemblyDefinitionSchemaProvider : JsonSchemaFileProvider {
    override fun isAvailable(file: VirtualFile): Boolean = file.extension.equals("asmdef", ignoreCase = true)
    override fun getName(): String = "Unity Assembly Definition"
    override fun getSchemaFile(): VirtualFile? = JsonSchemaProviderFactory.getResourceFile(AssemblyDefinitionSchemaProvider::class.java, "/unityjson/asmdef.schema.json")
    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
    override fun getSchemaVersion(): JsonSchemaVersion = JsonSchemaVersion.SCHEMA_7
}
