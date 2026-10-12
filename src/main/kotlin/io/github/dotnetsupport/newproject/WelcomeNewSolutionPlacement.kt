package io.github.dotnetsupport.newproject

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Anchor
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.DynamicActionConfigurationCustomizer

/**
 * New Solution of the Welcome screen as a button right after New Project, as in Rider. The screen shows the first
 * `welcome.screen.primaryButtonsCount` (3) actions of `WelcomeScreen.QuickStart` as buttons and the rest under "⋮", and New Project is
 * not the same action in every IDE, so no anchor of plugin.xml works everywhere:
 * - IntelliJ IDEA 2026.1: `WelcomeScreen.DefaultNewProjectActionGroup`, a child of the group (older IDEs: `WelcomeScreen.CreateNewProject`);
 * - GoLand, PyCharm, WebStorm…: `WelcomeScreen.CreateDirectoryProject` inside `WelcomeScreen.Platform.NewProject` (with Open), a group
 *   the screen shows flat.
 * The action is put after New Project in the group that holds it, when the actions are registered (also on a dynamic load of the
 * plugin); the IDE without a known New Project gets it at the end of the group, under "⋮". Get from VCS goes there instead.
 */
class WelcomeNewSolutionPlacement : DynamicActionConfigurationCustomizer {
    override fun registerActions(actionManager: ActionManager) {
        val action = actionManager.getAction(ACTION) ?: return
        val (group, anchor) = target(actionManager) ?: return
        val holder = actionManager.getAction(group) as? DefaultActionGroup ?: return
        if (holder.childActionsOrStubs.any { actionManager.getId(it) == ACTION }) return
        holder.addAction(action, anchor?.let { Constraints(Anchor.AFTER, it) } ?: Constraints.LAST, actionManager)
    }

    override fun unregisterActions(actionManager: ActionManager) {
        val action = actionManager.getAction(ACTION) ?: return
        for (group in groups(actionManager, QUICK_START)) (actionManager.getAction(group) as? DefaultActionGroup)?.remove(action, actionManager)
    }

    private fun target(actionManager: ActionManager): Pair<String, String?>? =
        target(QUICK_START) { group -> (actionManager.getAction(group) as? DefaultActionGroup)?.childActionsOrStubs?.mapNotNull { actionManager.getId(it) } }

    /** The group and its nested groups, as the Welcome screen flattens them. */
    private fun groups(actionManager: ActionManager, root: String): List<String> =
        listOf(root) + (actionManager.getAction(root) as? DefaultActionGroup)?.childActionsOrStubs.orEmpty()
            .mapNotNull { actionManager.getId(it) }.filter { it != ACTION && actionManager.getAction(it) is DefaultActionGroup }.flatMap { groups(actionManager, it) }

    companion object {
        const val ACTION = "DotNet.NewSolution.Welcome"
        const val QUICK_START = "WelcomeScreen.QuickStart"

        /** New Project of the IDEs, in the order they are looked for. */
        val NEW_PROJECT = listOf("WelcomeScreen.CreateNewProject", "WelcomeScreen.DefaultNewProjectActionGroup", "WelcomeScreen.CreateDirectoryProject")

        /**
         * Where to put New Solution: the group that holds New Project and New Project itself, or [root] and null (at the end) when there
         * is no New Project. [children] gives the ids of the children of a group, null for an action; New Project may be a group itself.
         */
        fun target(root: String, children: (String) -> List<String>?): Pair<String, String?>? {
            if (children(root) == null) return null
            fun find(group: String, depth: Int): Pair<String, String>? {
                val ids = children(group) ?: return null
                for (newProject in NEW_PROJECT) if (newProject in ids) return group to newProject
                if (depth == 0) return null
                return ids.firstNotNullOfOrNull { find(it, depth - 1) }
            }
            return find(root, 2) ?: (root to null)
        }
    }
}
