// Activates the tool window __ID__ (as a click on its stripe button) and prints its content tabs; take a screen shot after it.
importPackage(com.intellij.openapi.project);
importPackage(com.intellij.openapi.application);
importPackage(com.intellij.openapi.wm);
var project = ProjectManager.getInstance().getOpenProjects()[0];
var result = "";
ApplicationManager.getApplication().invokeAndWait(function () {
    var tw = ToolWindowManager.getInstance(project).getToolWindow("__ID__");
    if (tw == null) { result = "no tool window __ID__"; return; }
    tw.activate(null, true);
    var cm = tw.getContentManager(); var names = [];
    for (var i = 0; i < cm.getContentCount(); i++) names.push(cm.getContent(i).getDisplayName());
    result = "tool window __ID__: visible " + tw.isVisible() + ", tabs: " + names.join(" | ");
});
result;
