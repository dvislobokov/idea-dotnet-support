importClass(com.intellij.openapi.keymap.KeymapManager)
importClass(javax.swing.KeyStroke)
var km = KeymapManager.getInstance().getActiveKeymap()
var out = "@@@keymap: " + km.getName() + " (parent " + (km.getParent() ? km.getParent().getName() : "-") + ")\n"
var keys = "__KEYS__".split(",")
for (var i = 0; i < keys.length; i++) {
    var ks = KeyStroke.getKeyStroke(keys[i])
    var ids = km.getActionIds(ks)
    out += keys[i] + " -> " + java.lang.String.join(", ", ids) + "\n"
}
out
