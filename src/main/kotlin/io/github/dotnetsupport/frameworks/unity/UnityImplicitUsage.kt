package io.github.dotnetsupport.frameworks.unity

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseFieldDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpImplicitUsage
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch
import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * What Unity uses without code (UNITY_PLAN.md, step 2), as Rider's Unity support shows it in place of "no usages": the event functions of
 * a `MonoBehaviour` / `ScriptableObject` / editor class ("Event function": the engine calls `Update` by its name), the fields the inspector
 * sets ("Set by Unity": `[SerializeField]`, a public field of a serializable class), the methods of Unity's attributes (`[MenuItem]`,
 * `[RuntimeInitializeOnLoadMethod]`: "Called by Unity") and the component classes themselves ("Unity script": a scene creates them).
 */
class UnityImplicitUsage : CSharpImplicitUsage {
    override fun implicitUsage(declaration: PsiElement, session: CSharpSemanticSession): String? {
        val file = declaration.containingFile as? CSharpFile ?: return null
        if (file.virtualFile?.let(UnityProjects::rootOf) == null) return null
        return when (declaration) {
            is CSharpBaseTypeDeclaration -> if (unityBase(declaration) != null) UNITY_SCRIPT else null
            is CSharpMethodDeclaration -> method(declaration)
            else -> field(declaration)
        }
    }

    private fun method(method: CSharpMethodDeclaration): String? {
        if (attributeNames(method).any { it in CALLED_BY_ATTRIBUTES }) return CALLED_BY_UNITY
        if (method.modifiers.any { it.text == "override" }) return null // an override is called through its base: the lens counts those
        val name = method.identifier?.text ?: return null
        val type = PsiTreeUtil.getParentOfType(method, CSharpBaseTypeDeclaration::class.java) ?: return null
        val base = unityBase(type) ?: return null
        return if (name in UnityMessages.of(base)) EVENT_FUNCTION else null
    }

    private fun field(declaration: PsiElement): String? {
        val field = declaration as? CSharpBaseFieldDeclaration ?: PsiTreeUtil.getParentOfType(declaration, CSharpBaseFieldDeclaration::class.java) ?: return null
        val attributes = attributeNames(field)
        if ("NonSerialized" in attributes) return null
        val modifiers = field.modifiers.map { it.text }
        if (modifiers.any { it == "static" || it == "const" || it == "readonly" }) return null
        val serialized = "SerializeField" in attributes || "SerializeReference" in attributes || "public" in modifiers
        if (!serialized) return null
        val type = PsiTreeUtil.getParentOfType(field, CSharpBaseTypeDeclaration::class.java) ?: return null
        val base = unityBase(type)
        // a class of the project marked [Serializable] is serialized as a field of a component: its fields are set the same way
        return if (base in SERIALIZED_BASES || "Serializable" in attributeNames(type)) SET_BY_UNITY else null
    }

    /** The Unity class [type] derives from (`UnityEngine.MonoBehaviour`...), the nearest one; null for a class of no Unity base. Cached until the PSI changes. */
    private fun unityBase(type: CSharpBaseTypeDeclaration): String? = CachedValuesManager.getCachedValue(type) {
        CachedValueProvider.Result.create(computeUnityBase(type), PsiModificationTracker.MODIFICATION_COUNT)
    }

    private fun computeUnityBase(type: CSharpBaseTypeDeclaration): String? {
        val file = type.containingFile as? CSharpFile ?: return null
        val session = CSharpSemanticSession(type.project)
        val assemblies = CSharpSemanticEnvironment.assemblies(file)
        val seen = HashSet<Any>()
        var level: List<SemanticType> = CSharpSolutionSearch.supertypes(type, session)
        repeat(MAX_DEPTH) {
            val next = ArrayList<SemanticType>()
            for (supertype in level) {
                ProgressManager.checkCanceled()
                when (supertype) {
                    is SemanticType.Library -> libraryBase(assemblies, supertype.type, seen)?.let { return it }
                    is SemanticType.Source -> {
                        // Unity's own classes as sources (a package in the solution, a test): the same names
                        if (supertype.info.qualifiedName in UnityMessages.BASES) return supertype.info.qualifiedName
                        for (part in supertype.info.parts) {
                            val element = part.element() as? CSharpBaseTypeDeclaration ?: continue
                            if (seen.add(element)) next += CSharpSolutionSearch.supertypes(element, session)
                        }
                    }
                    else -> {}
                }
            }
            if (next.isEmpty()) return null
            level = next
        }
        return null
    }

    private fun libraryBase(assemblies: AssemblyIndexSet, type: IndexedType, seen: HashSet<Any>): String? {
        var current: IndexedType? = type
        while (current != null && seen.add(current.fullName)) {
            if (current.fullName in UnityMessages.BASES) return current.fullName
            current = assemblies.baseTypes(current).firstOrNull()?.type
        }
        return null
    }

    /** `[SerializeField]`, `[UnityEngine.SerializeField]`, `[SerializeFieldAttribute]` → `SerializeField`. */
    private fun attributeNames(member: CSharpMemberDeclaration): Set<String> =
        member.attributeLists.flatMap { list -> PsiTreeUtil.getChildrenOfTypeAsList(list, io.github.dotnetsupport.csharp.lang.psi.CSharpAttribute::class.java) }
            .mapNotNullTo(HashSet()) { attribute -> attribute.nameElement?.text?.substringAfterLast('.')?.removeSuffix("Attribute")?.trim() }

    private companion object {
        const val UNITY_SCRIPT = "Unity script"
        const val EVENT_FUNCTION = "Event function"
        const val SET_BY_UNITY = "Set by Unity"
        const val CALLED_BY_UNITY = "Called by Unity"
        const val MAX_DEPTH = 16

        val SERIALIZED_BASES = setOf("UnityEngine.MonoBehaviour", "UnityEngine.ScriptableObject", "UnityEngine.StateMachineBehaviour", "UnityEditor.EditorWindow")

        /** Methods Unity finds by an attribute: menu items, the hooks of loading, building and opening assets. */
        val CALLED_BY_ATTRIBUTES = setOf(
            "MenuItem", "ContextMenu", "ContextMenuItem", "RuntimeInitializeOnLoadMethod", "InitializeOnLoadMethod", "InitializeOnEnterPlayMode",
            "DidReloadScripts", "PostProcessBuild", "PostProcessScene", "OnOpenAsset", "Shortcut", "ClutchShortcut", "SettingsProvider",
            "SettingsProviderGroup",
        )
    }
}

/** The messages Unity sends by name to the classes that derive from its bases (the "Messages" of the scripting reference, Unity 6). */
object UnityMessages {
    private val OBJECT_LIFETIME = setOf("Awake", "OnEnable", "OnDisable", "OnDestroy", "OnValidate", "Reset")

    private val MONO_BEHAVIOUR = OBJECT_LIFETIME + setOf(
        "Start", "Update", "FixedUpdate", "LateUpdate", "OnGUI",
        "OnApplicationFocus", "OnApplicationPause", "OnApplicationQuit", "OnBecameVisible", "OnBecameInvisible",
        "OnCollisionEnter", "OnCollisionStay", "OnCollisionExit", "OnCollisionEnter2D", "OnCollisionStay2D", "OnCollisionExit2D",
        "OnTriggerEnter", "OnTriggerStay", "OnTriggerExit", "OnTriggerEnter2D", "OnTriggerStay2D", "OnTriggerExit2D",
        "OnControllerColliderHit", "OnJointBreak", "OnJointBreak2D", "OnParticleCollision", "OnParticleTrigger", "OnParticleSystemStopped",
        "OnParticleUpdateJobScheduled", "OnMouseDown", "OnMouseUp", "OnMouseUpAsButton", "OnMouseEnter", "OnMouseExit", "OnMouseOver",
        "OnMouseDrag", "OnAnimatorMove", "OnAnimatorIK", "OnAudioFilterRead", "OnDrawGizmos", "OnDrawGizmosSelected",
        "OnPreCull", "OnPreRender", "OnPostRender", "OnRenderImage", "OnRenderObject", "OnWillRenderObject",
        "OnRectTransformDimensionsChange", "OnRectTransformRemoved", "OnTransformChildrenChanged", "OnTransformParentChanged",
        "OnBeforeTransformParentChanged", "OnCanvasGroupChanged", "OnCanvasHierarchyChanged", "OnDidApplyAnimationProperties",
        "OnServerInitialized", "OnConnectedToServer", "OnDisconnectedFromServer", "OnFailedToConnect", "OnPlayerConnected",
        "OnPlayerDisconnected", "OnNetworkInstantiate", "OnSerializeNetworkView", "OnMasterServerEvent", "OnFailedToConnectToMasterServer",
    )

    private val EDITOR = setOf("OnEnable", "OnDisable", "OnDestroy", "OnSceneGUI", "HasFrameBounds", "OnGetFrameBounds", "OnValidate", "Awake", "Reset")

    private val EDITOR_WINDOW = OBJECT_LIFETIME + setOf(
        "OnGUI", "Update", "OnFocus", "OnLostFocus", "OnHierarchyChange", "OnProjectChange", "OnSelectionChange", "OnInspectorUpdate",
        "CreateGUI", "ShowButton", "ModifierKeysChanged", "OnBecameVisible", "OnBecameInvisible", "OnAddedAsTab", "OnBeforeRemovedAsTab",
        "OnTabDetached", "OnMainWindowMove",
    )

    private val ASSET_POSTPROCESSOR = setOf(
        "OnPreprocessAsset", "OnPostprocessAllAssets", "OnPreprocessTexture", "OnPostprocessTexture", "OnPreprocessModel", "OnPostprocessModel",
        "OnPreprocessAudio", "OnPostprocessAudio", "OnPreprocessAnimation", "OnPostprocessAnimation", "OnPostprocessPrefab", "OnPostprocessMaterial",
        "OnPostprocessSprites", "OnPostprocessGameObjectWithUserProperties", "OnPostprocessMeshHierarchy", "OnAssignMaterialModel",
        "OnPreprocessSpeedTree", "OnPostprocessSpeedTree", "OnPostprocessCubemap", "OnPostprocessAssetbundleNameChanged",
        "GetPostprocessOrder", "GetVersion",
    )

    private val BY_BASE: Map<String, Set<String>> = mapOf(
        "UnityEngine.MonoBehaviour" to MONO_BEHAVIOUR,
        "UnityEngine.ScriptableObject" to OBJECT_LIFETIME,
        "UnityEngine.StateMachineBehaviour" to OBJECT_LIFETIME,
        "UnityEditor.Editor" to EDITOR,
        "UnityEditor.EditorWindow" to EDITOR_WINDOW,
        "UnityEditor.AssetPostprocessor" to ASSET_POSTPROCESSOR,
        "UnityEditor.PropertyDrawer" to emptySet(),
        "UnityEditor.AssetModificationProcessor" to setOf("OnWillCreateAsset", "OnWillDeleteAsset", "OnWillMoveAsset", "OnWillSaveAssets", "IsOpenForEdit", "MakeEditable", "FileModeChanged", "CanOpenForEdit"),
    )

    /** The Unity classes whose subclasses Unity creates and messages; the nearest of them is what [of] is asked about. */
    val BASES: Set<String> = BY_BASE.keys

    fun of(base: String): Set<String> = BY_BASE[base].orEmpty()
}
