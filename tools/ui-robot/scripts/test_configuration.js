// Creates (or finds) a ".NET Project" run configuration __NAME__ that runs `dotnet test` of __PROJECT__ with the filter __FILTER__.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.execution.RunManager)
importClass(com.intellij.execution.configurations.ConfigurationType)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const manager = RunManager.getInstance(project)
let settings = manager.getAllSettings().stream().filter(function (s) { return s.getName() == "__NAME__" }).findFirst().orElse(null)
if (settings == null) {
    const type = ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList().stream().filter(function (t) { return t.getId() == "DotNetProjectRunConfiguration" }).findFirst().get()
    settings = manager.createConfiguration("__NAME__", type.getConfigurationFactories()[0])
    const loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
    const commands = loader.loadClass("io.github.dotnetsupport.run.DotNetCommand").getEnumConstants()
    const options = settings.getConfiguration().getOptions()
    options.setProjectPath("__PROJECT__")
    for (let i = 0; i < commands.length; i++) if (commands[i].name() == "TEST") options.setCommand(commands[i])
    if ("__FILTER__" != "") options.setTestFilter("__FILTER__")
    manager.addConfiguration(settings)
}
"configuration: " + settings.getName()
