// The state of the action __ID__ as the main menu would show it: its update run with the context of the last open project, then
// visible / enabled / text / description of the presentation, as the main menu shows the action.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.AnActionEvent)
importClass(com.intellij.openapi.actionSystem.ActionUiKind)
importClass(com.intellij.openapi.actionSystem.impl.SimpleDataContext)

var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var action = ActionManager.getInstance().getAction("__ID__")
var presentation = action.getTemplatePresentation().clone()
var event = AnActionEvent.createEvent(action, SimpleDataContext.getProjectContext(project), presentation, "MainMenu", ActionUiKind.MAIN_MENU, null)
action.update(event)
"__ID__: visible " + presentation.isVisible() + ", enabled " + presentation.isEnabled() + ", text '" + presentation.getText() + "', description '" + presentation.getDescription() + "'"
