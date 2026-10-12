// Run to Cursor: __FILE__ line __LINE__ (1-based)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(com.intellij.xdebugger.XDebuggerUtil)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const session = XDebuggerManager.getInstance(project).getCurrentSession()
const file = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
const position = XDebuggerUtil.getInstance().createPosition(file, __LINE__ - 1)
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { session.runToPosition(position, false) } }))
"ok"
