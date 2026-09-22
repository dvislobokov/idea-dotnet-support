// Settings | Tools | .NET | Debugger: externalSource=__EXTERNAL__, allowImplicitEvaluation=__IMPLICIT__
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
importClass(com.intellij.openapi.application.ApplicationManager)
const loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
const settings = ApplicationManager.getApplication().getService(loader.loadClass("io.github.dotnetsupport.settings.DotNetSettings"))
settings.setDebugExternalSource(__EXTERNAL__)
settings.setDebugAllowImplicitEvaluation(__IMPLICIT__)
"externalSource=" + settings.getDebugExternalSource() + " allowImplicitEvaluation=" + settings.getDebugAllowImplicitEvaluation()
