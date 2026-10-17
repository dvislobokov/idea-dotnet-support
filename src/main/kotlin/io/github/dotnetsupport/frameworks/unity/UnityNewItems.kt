package io.github.dotnetsupport.frameworks.unity

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.project.DumbAware
import io.github.dotnetsupport.templates.ItemCategory
import io.github.dotnetsupport.templates.ItemFile
import io.github.dotnetsupport.templates.ItemTemplate
import io.github.dotnetsupport.templates.NewItemAction

/**
 * New → Unity ▸ the entities of Unity (UNITY_PLAN.md, as Rider's "Unity" section of New): the scripts a Unity project is made of, a
 * submenu beside the categories of .NET items, in the folders of a Unity project only. The templates render as the plugin's C# items do
 * (the namespace of the folder, `using` lines); the `.meta` beside a new file is the editor's to write.
 */
object UnityItemTemplates {
    private fun script(id: String, title: String, template: String, suffix: String? = null, prompt: String = "Name") =
        ItemTemplate(id, title, ItemCategory.CSHARP, listOf(ItemFile("\${NAME}.cs", "unity/$template")), suffix = suffix, namePrompt = prompt)

    val ALL: List<ItemTemplate> = listOf(
        script("unityMonoBehaviour", "MonoBehaviour Script", "monoBehaviour.cs"),
        script("unityScriptableObject", "ScriptableObject", "scriptableObject.cs"),
        script("unityEditor", "Editor Script", "editor.cs", suffix = "Editor", prompt = "Component name"),
        script("unityPropertyDrawer", "Property Drawer", "propertyDrawer.cs", suffix = "Drawer", prompt = "Type name"),
        script("unityEditorWindow", "Editor Window", "editorWindow.cs", suffix = "Window"),
        ItemTemplate("unityAsmdef", "Assembly Definition", ItemCategory.CSHARP, listOf(ItemFile("\${NAME}.asmdef", "unity/assembly.asmdef")), isIdentifier = false),
    )
}

class UnityNewItemGroup : ActionGroup(), DumbAware {
    private val items: List<AnAction> by lazy { UnityItemTemplates.ALL.map(::NewItemAction) }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val directory = e.getData(LangDataKeys.IDE_VIEW)?.directories?.firstOrNull()?.virtualFile
        e.presentation.isEnabledAndVisible = e.project != null && directory != null && UnityProjects.rootOf(directory) != null
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> = items.toTypedArray()
}
