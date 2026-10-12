// Sets the project's formatter as Settings | .NET | Toolset and Build → «Formatter» does: __CHOICE__ = AUTO | BUILT_IN |
// DOTNET_FORMAT | CSHARPIER | NONE. Prints the choice and what it resolves to for the project directory (reformat.js says who formats a file).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var choiceClass = loader.loadClass("io.github.dotnetsupport.format.FormatterChoice")
var settings = project.getService(loader.loadClass("io.github.dotnetsupport.format.DotNetFormattingSettings"))
var result = ""
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    settings.setFormatter(java.lang.Enum.valueOf(choiceClass, "__CHOICE__"))
    result = "formatter: " + settings.getFormatter() + ", resolves to " + settings.resolve(null, null)
} }))
result
