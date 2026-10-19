// Starts the run configuration __NAME__ with __EXECUTOR__ (Run / Debug) the way the toolbar button does — ExecutionUtil.runConfiguration,
// through the platform's rerun: a running instance of a configuration that does not allow parallel runs is stopped first ("Stop and
// Rerun"). `robot.py run` goes through ProgramRunnerUtil.executeConfiguration, which starts the runner directly and skips that.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.execution.RunManager)
importClass(com.intellij.execution.runners.ExecutionUtil)
importClass(com.intellij.execution.executors.DefaultDebugExecutor)
importClass(com.intellij.execution.executors.DefaultRunExecutor)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var settings = RunManager.getInstance(project).findConfigurationByName("__NAME__")
if (settings == null) throw new java.lang.IllegalArgumentException("No run configuration __NAME__")
var executor = "__EXECUTOR__" == "Debug" ? DefaultDebugExecutor.getDebugExecutorInstance() : DefaultRunExecutor.getRunExecutorInstance()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { ExecutionUtil.runConfiguration(settings, executor) } }), ModalityState.nonModal())
"started __NAME__ with __EXECUTOR__ like the toolbar"
