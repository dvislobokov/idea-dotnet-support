// The edition (edition/DotNetEdition.kt): GIGA IDE's own work mode (PluginVerificationService of its plugin com.gigaide.pro, if it is
// there) and what the plugin makes of it — the mode, isPro, isFree, a development build or not.
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
importClass(com.intellij.openapi.application.ApplicationManager)

var out = new java.lang.StringBuilder()
var pro = PluginManagerCore.getPlugin(PluginId.getId("com.gigaide.pro"))
out.append("com.gigaide.pro: " + (pro == null ? "absent" : (pro.isEnabled() ? "enabled " + pro.getVersion() : "disabled")) + "\n")
if (pro != null && pro.isEnabled()) {
    var service = pro.getPluginClassLoader().loadClass("com.gigaide.pro.api.PluginVerificationService")
    out.append("GIGA IDE work mode: " + service.getMethod("getWorkMode").invoke(ApplicationManager.getApplication().getService(service)) + "\n")
}
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var edition = loader.loadClass("io.github.dotnetsupport.edition.DotNetEdition").getField("INSTANCE").get(null)
out.append("plugin: mode " + edition.mode() + ", isPro " + edition.isPro() + ", isFree " + edition.isFree() + ", development build " + edition.isUnlockedBuild() + "\n")
out.toString()
