// Attribute descriptors (display name -> TextAttributesKey external name, fallback key, colors in the current scheme) of color pages whose
// display name matches __PAGE__.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.editor.colors.EditorColorsManager)
var out = new java.lang.StringBuilder("@@@")
function hex(c) { return c == null ? "-" : "#" + java.lang.String.format("%06x", new java.lang.Integer(c.getRGB() & 0xffffff)) }
app = ApplicationManager.getApplication()
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var scheme = EditorColorsManager.getInstance().getGlobalScheme()
    out.append("scheme: " + scheme.getName() + "\n")
    var pages = []
    var cs = com.intellij.openapi.options.colors.ColorSettingsPages.getInstance().getRegisteredPages()
    for (var i = 0; i < cs.length; i++) pages.push(cs[i])
    try {
        var ext = com.intellij.openapi.extensions.ExtensionPointName.create("com.intellij.colorSettingsPage").getExtensionList()
        for (var i = 0; i < ext.size(); i++) pages.push(ext.get(i))
    } catch (e) {}
    var seen = {}
    for (var p = 0; p < pages.length; p++) {
        var page = pages[p]
        var name = String(page.getDisplayName())
        if (!new RegExp("__PAGE__").test(name) || seen[name]) continue
        seen[name] = 1
        out.append("##### " + name + "  (" + page.getClass().getName() + ")\n")
        var ds = page.getAttributeDescriptors()
        for (var i = 0; i < ds.length; i++) {
            var k = ds[i].getKey()
            var a = scheme.getAttributes(k)
            var fb = k.getFallbackAttributeKey()
            out.append("  " + ds[i].getDisplayName() + "  =  " + k.getExternalName() + (fb ? "  (fallback " + fb.getExternalName() + ")" : "") +
                (a ? "  fg " + hex(a.getForegroundColor()) + (a.getFontType() ? " font " + a.getFontType() : "") + (a.getEffectType() && a.getEffectColor() ? " effect " + a.getEffectType() + " " + hex(a.getEffectColor()) : "") + (a.getBackgroundColor() ? " bg " + hex(a.getBackgroundColor()) : "") : "") + "\n")
        }
        try { var demo = page.getDemoText(); out.append("  --- demo text ---\n" + demo + "\n") } catch (e) {}
    }
} }), ModalityState.any())
out.toString()
