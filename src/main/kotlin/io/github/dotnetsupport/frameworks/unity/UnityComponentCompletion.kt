package io.github.dotnetsupport.frameworks.unity

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpElement
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.documentText

/**
 * The components first in `GetComponent<`, `TryGetComponent<`, `GetComponentsInChildren<`, `AddComponent<` and `[RequireComponent(typeof(`
 * (UNITY_PLAN.md, "плюшки", as Rider ranks them): the components of the project (the classes that derive from `MonoBehaviour`, through the
 * stub index of base lists) and the common ones of `UnityEngine`, above the rest of the types the general completion offers.
 */
class UnityComponentCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? CSharpFile ?: return
        if (file.virtualFile?.let(UnityProjects::rootOf) == null) return
        val text = parameters.documentText
        val offset = parameters.offset
        val line = text.subSequence(text.lastIndexOf('\n', offset - 1) + 1, offset)
        val match = PLACE.find(line) ?: return
        val set = result.withPrefixMatcher(match.groupValues[2])
        val ours = LinkedHashSet<String>()
        for (name in projectComponents(file.project)) {
            if (ours.add(name)) set.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Class).withTypeText("component of the project"), 110.0))
        }
        for (name in UNITY_COMPONENTS) {
            if (ours.add(name)) set.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Class).withTypeText("UnityEngine"), 100.0))
        }
        // the general completion offers the same types without the rank: its item of a name would stand for ours (one item per name in the
        // list), so its items of these names are left out, the rest passes as it is
        result.runRemainingContributors(parameters) { other -> if (other.lookupElement.lookupString !in ours) result.passResult(other) }
    }

    /** The classes of the project that derive from `MonoBehaviour`, by the simple names of base lists, level by level. */
    private fun projectComponents(project: Project): Set<String> {
        val found = LinkedHashSet<String>()
        var level = listOf("MonoBehaviour")
        val scope = GlobalSearchScope.projectScope(project)
        repeat(MAX_DEPTH) {
            val next = ArrayList<String>()
            for (base in level) {
                StubIndex.getInstance().processElements(CSharpStubIndexKeys.SUPERTYPES, base, project, scope, CSharpElement::class.java) { element ->
                    ProgressManager.checkCanceled()
                    val name = (element as? CSharpBaseTypeDeclaration)?.identifier?.text
                    if (name != null && found.add(name)) next += name
                    true
                }
            }
            if (next.isEmpty()) return found
            level = next
        }
        return found
    }

    private companion object {
        const val MAX_DEPTH = 6
        val PLACE = Regex("""\b(GetComponent|GetComponents|GetComponentInChildren|GetComponentsInChildren|GetComponentInParent|GetComponentsInParent|TryGetComponent|AddComponent|RequireComponent\s*\(\s*typeof)\s*[<(]\s*(\w*)$""")
        val UNITY_COMPONENTS = listOf(
            "Transform", "RectTransform", "Rigidbody", "Rigidbody2D", "Collider", "BoxCollider", "SphereCollider", "CapsuleCollider", "MeshCollider",
            "Collider2D", "BoxCollider2D", "CircleCollider2D", "CapsuleCollider2D", "PolygonCollider2D", "CharacterController", "Animator", "Animation",
            "AudioSource", "AudioListener", "Camera", "Light", "Renderer", "MeshRenderer", "SkinnedMeshRenderer", "SpriteRenderer", "LineRenderer",
            "TrailRenderer", "MeshFilter", "ParticleSystem", "Canvas", "CanvasGroup", "Joint", "HingeJoint", "FixedJoint", "SpringJoint", "LODGroup",
        )
    }
}
