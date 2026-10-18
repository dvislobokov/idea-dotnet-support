// The link with the Unity editor of the last open project (UNITY_PLAN.md, "Интеграция с редактором"): the status and the state the editor
// wrote (UnityEditorConnection.link, looked at anew), the status bar text; __SEND__ — a command to send first (refresh, play, stop,
// pause, step; empty: none). Classes of the content module come through the loader of its action.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = ActionManager.getInstance().getAction("Unity.Play").getClass().getClassLoader()
var connectionClass = loader.loadClass("io.github.dotnetsupport.frameworks.unity.UnityEditorConnection")
var connection = project.getService(connectionClass)
var out = "project: " + project.getName() + ", root: " + connection.root() + "\n"
connection.update()
var send = "__SEND__"
if (send.length > 0) out += "sent " + send + ": " + connection.send(send) + "\n"
var link = connection.getLink()
out += "link: " + link + "\n"
var widget = loader.loadClass("io.github.dotnetsupport.frameworks.unity.UnityEditorStatusWidget")
var companion = widget.getField("Companion").get(null)
out += "status bar: Unity: " + companion.short(link) + "\n"
out
