// Whether the last opened project is in dumb mode (indexing) and what the indicators run: dumb mode skips the completion contributors,
// annotators and inspections that are not DumbAware.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.project.DumbService)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var text = "dumb: " + DumbService.getInstance(project).isDumb() + "\n"
var frame = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
var indicators = com.intellij.openapi.progress.impl.CoreProgressManager.getCurrentIndicators()
for (var i = 0; i < indicators.size(); i++) text += "progress: " + indicators.get(i).getText() + " " + indicators.get(i).getText2() + "\n"
text
