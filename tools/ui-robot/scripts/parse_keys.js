// The `#if` symbols and the language version __FILE__ (forward slashes) is parsed with: the keys CSharpParseOptions puts on the file.
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
var symbols = loader.loadClass("io.github.dotnetsupport.csharp.lang.CSharpPreprocessorSymbols").getField("KEY").get(null)
var level = loader.loadClass("io.github.dotnetsupport.csharp.lang.CSharpLanguageLevel").getField("KEY").get(null)
var set = vfile.getUserData(symbols)
var wanted = "__SYMBOLS__".split(",")
var text = "symbols: " + (set == null ? "none" : set.size()) + ", language version: " + vfile.getUserData(level) + "\n"
for (var i = 0; i < wanted.length; i++) text += wanted[i] + ": " + (set != null && set.contains(wanted[i])) + "\n"
text
