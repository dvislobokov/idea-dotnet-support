// The lines of the selected editor that contain __TEXT__, with their numbers: to see what an action has done to the text.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const lines = String(FileEditorManager.getInstance(projects[projects.length - 1]).getSelectedTextEditor().getDocument().getText()).split("\n")
let text = ""
for (let i = 0; i < lines.length; i++) if (lines[i].indexOf("__TEXT__") >= 0) text += (i + 1) + ": [" + lines[i] + "]\n"
text
