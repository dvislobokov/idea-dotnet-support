// Creates (or finds) a ".NET Project" run configuration __NAME__ that runs __PROJECT__ (`dotnet run`, or the program itself for a project
// of the old format) with the program arguments __ARGS__; start it with `robot.py run "__NAME__"`.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.execution.RunManager)
importClass(com.intellij.execution.configurations.ConfigurationType)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const manager = RunManager.getInstance(project)
let settings = manager.getAllSettings().stream().filter(function (s) { return s.getName() == "__NAME__" }).findFirst().orElse(null)
if (settings == null) {
    const type = ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList().stream().filter(function (t) { return t.getId() == "DotNetProjectRunConfiguration" }).findFirst().get()
    settings = manager.createConfiguration("__NAME__", type.getConfigurationFactories()[0])
    const options = settings.getConfiguration().getOptions()
    options.setProjectPath("__PROJECT__")
    options.setProgramArguments("__ARGS__")
    manager.addConfiguration(settings)
}
const tasks = manager.getBeforeRunTasks(settings.getConfiguration())
let names = ""
for (let i = 0; i < tasks.size(); i++) names += " " + tasks.get(i).getProviderId()
"configuration: " + settings.getName() + ", before launch:" + names
