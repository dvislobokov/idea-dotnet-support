package io.github.dotnetsupport.frameworks.unity

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.CSharpAttribute
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseFieldDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames
import io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpVariableDeclarator
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpServiceRegistrations

/**
 * The uses of a declaration of a script in the assets of a Unity project (UNITY_PLAN.md, the asset index), as Rider shows them: the
 * components of a class in the scenes and prefabs, the UnityEvent handlers and animation events of a method, the values the inspector
 * gave a serialized field. The lenses ([UnityImplicitUsage.usageNote]), Find Usages (the YAML module) and the rename of a field
 * (`[FormerlySerializedAs]`) ask here.
 */
object UnityAssetUsages {
    /** A place in an asset: the file and the offset of what names the declaration there. */
    class Place(val file: VirtualFile, val offset: Int, val length: Int)

    /**
     * The GUID Unity knows the script of [type] by: the `.meta` of its file, when the class is named as the file (Unity finds the class of
     * a script by the file's name) and derives from a Unity base.
     */
    fun scriptGuid(type: CSharpBaseTypeDeclaration): String? {
        val file = type.containingFile?.originalFile?.virtualFile ?: return null
        if (UnityProjects.rootOf(file) == null || type.identifier?.text != file.nameWithoutExtension) return null
        if (UnityTypes.unityBase(type) == null) return null
        return UnityAssets.guidOf(file)
    }

    /** `Demo.Gameplay.Door`: how `m_TargetAssemblyTypeName` names the class. */
    fun qualifiedName(type: CSharpBaseTypeDeclaration): String =
        CSharpServiceRegistrations.namespaceOf(type).let { if (it.isEmpty()) "" else "$it." } + type.identifier?.text

    /** The components of the script of [type] in the assets. */
    fun components(type: CSharpBaseTypeDeclaration, scope: GlobalSearchScope = GlobalSearchScope.allScope(type.project)): List<UnityAssets.Located> =
        scriptGuid(type)?.let { UnityAssets.components(type.project, it, scope) }.orEmpty()

    /** The persistent calls and animation events of [method]. */
    fun handlers(method: CSharpMethodDeclaration, scope: GlobalSearchScope = GlobalSearchScope.allScope(method.project)): List<UnityAssets.Located> {
        val name = method.identifier?.text ?: return emptyList()
        val type = PsiTreeUtil.getParentOfType(method, CSharpBaseTypeDeclaration::class.java) ?: return emptyList()
        val base = UnityTypes.unityBase(type) ?: return emptyList()
        return UnityAssets.handlers(method.project, name, qualifiedName(type), scriptGuid(type), base == "UnityEngine.MonoBehaviour", scope)
    }

    /** The field [declaration] belongs to (a field of one declarator, or a declarator) when Unity serializes it; null otherwise. */
    fun serializedField(declaration: PsiElement): CSharpBaseFieldDeclaration? {
        val field = declaration as? CSharpBaseFieldDeclaration ?: PsiTreeUtil.getParentOfType(declaration, CSharpBaseFieldDeclaration::class.java) ?: return null
        val attributes = UnityTypes.attributeNames(field)
        if ("NonSerialized" in attributes) return null
        val modifiers = field.modifiers.map { it.text }
        if (modifiers.any { it == "static" || it == "const" || it == "readonly" }) return null
        if ("SerializeField" !in attributes && "SerializeReference" !in attributes && "public" !in modifiers) return null
        val type = PsiTreeUtil.getParentOfType(field, CSharpBaseTypeDeclaration::class.java) ?: return null
        // a class of the project marked [Serializable] is serialized as a field of a component: its fields are set the same way
        return if (UnityTypes.unityBase(type) in SERIALIZED_BASES || "Serializable" in UnityTypes.attributeNames(type)) field else null
    }

    val SERIALIZED_BASES = setOf("UnityEngine.MonoBehaviour", "UnityEngine.ScriptableObject", "UnityEngine.StateMachineBehaviour", "UnityEditor.EditorWindow")

    /** The name of the field [declaration] names (its declarator's identifier). */
    fun fieldName(declaration: PsiElement): String? = when (declaration) {
        is CSharpVariableDeclarator -> declaration.identifier?.text
        else -> (declaration as? CSharpBaseFieldDeclaration ?: PsiTreeUtil.getParentOfType(declaration, CSharpBaseFieldDeclaration::class.java))
            ?.let { CSharpDeclarationNames.nameElement(it)?.text }
    }

    /** The names the assets may store the field under: its own and those of `[FormerlySerializedAs("…")]`. */
    fun serializedNames(field: CSharpBaseFieldDeclaration, name: String): Set<String> = setOf(name) + formerNames(field)

    fun formerNames(field: CSharpMemberDeclaration): List<String> = field.attributeLists
        .flatMap { PsiTreeUtil.getChildrenOfTypeAsList(it, CSharpAttribute::class.java) }
        .filter { it.nameElement?.text?.substringAfterLast('.')?.removeSuffix("Attribute") == "FormerlySerializedAs" }
        .mapNotNull { attribute -> Regex(""""([^"]*)"""").find(attribute.text)?.groupValues?.get(1) }

    /** The values the inspector set to the serialized field [declaration] in the components of its class: the use and the field. */
    fun fieldValues(declaration: PsiElement, scope: GlobalSearchScope = GlobalSearchScope.allScope(declaration.project)): List<Pair<UnityAssets.Located, UnityAssetParser.Field>> {
        val field = serializedField(declaration) ?: return emptyList()
        val name = fieldName(declaration) ?: return emptyList()
        val type = PsiTreeUtil.getParentOfType(field, CSharpBaseTypeDeclaration::class.java) ?: return emptyList()
        val names = serializedNames(field, name)
        return components(type, scope).flatMap { located -> located.usage.fields.filter { it.name in names }.map { located to it } }
    }

    /** Every place of an asset that uses [declaration] (a class, a method, a field): what Find Usages lists. */
    fun places(declaration: PsiElement, scope: GlobalSearchScope = GlobalSearchScope.allScope(declaration.project)): List<Place> {
        if ((declaration.containingFile as? CSharpFile)?.virtualFile?.let(UnityProjects::rootOf) == null) return emptyList()
        return when (declaration) {
            is CSharpBaseTypeDeclaration -> components(declaration, scope).map { Place(it.file, it.usage.offset, GUID_LENGTH) }
            is CSharpMethodDeclaration -> handlers(declaration, scope).map { Place(it.file, it.usage.offset, it.usage.name.length) }
            is CSharpVariableDeclarator, is CSharpBaseFieldDeclaration -> fieldValues(declaration, scope).map { (located, field) -> Place(located.file, field.offset, field.name.length) }
            else -> emptyList()
        }
    }

    /** What the usages lens says of the uses in assets: "Used in Boot.unity", "Event handler in 2 assets", "Set by Unity: 5". */
    fun note(declaration: PsiElement): String? = when (declaration) {
        is CSharpBaseTypeDeclaration -> components(declaration).takeIf { it.isNotEmpty() }?.let { "Used in " + UnityAssets.where(it) }
        is CSharpMethodDeclaration -> handlers(declaration).takeIf { it.isNotEmpty() }?.let { uses ->
            (if (uses.any { it.usage.kind == UnityAssetParser.HANDLER }) "Event handler in " else "Animation event in ") + UnityAssets.where(uses)
        }
        else -> fieldValues(declaration).takeIf { it.isNotEmpty() }?.let { values ->
            val shown = values.mapNotNull { (_, field) -> presentation(field.value) }.distinct()
            when (shown.size) {
                0 -> "Set by Unity in " + UnityAssets.where(values.map { it.first })
                1 -> "Set by Unity: " + shown.single()
                else -> "Set by Unity: ${shown.size} values in " + UnityAssets.where(values.map { it.first })
            }
        }
    }

    /** How a serialized value reads: `5`, `None` for an empty reference, `object` for a reference; null for a nested value (a list, a struct). */
    fun presentation(value: String): String? = when {
        value.isEmpty() -> null
        value.startsWith("{fileID: 0}") -> "None"
        value.startsWith("{fileID") -> "object"
        value.startsWith("{") || value.startsWith("[") -> null
        else -> value.removeSurrounding("'").removeSurrounding("\"").let { if (it.length > 40) it.take(39) + "…" else it }
    }

    private const val GUID_LENGTH = 32
}
