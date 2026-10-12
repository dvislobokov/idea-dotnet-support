// Opens __FILE__ (forward slashes), waits __WAIT__ ms, dumps: highlighters of the document and editor markup models (text attributes key,
// range text, tooltip, gutter icon), inlays (inline / after line end / block, with their text), folding regions are skipped.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.editor.impl.DocumentMarkupModel)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var editor = null
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, 0), true)
} }), ModalityState.any())
java.lang.Thread.sleep(__WAIT__)
var out = new java.lang.StringBuilder("@@@")
function oneLine(s) { return s == null ? "" : String(s).replace(/<[^>]*>/g, "").replace(/&nbsp;/g, " ").replace(/&quot;/g, "\"").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&").replace(/\s+/g, " ").trim() }
function inlayText(inlay) {
    var r = inlay.getRenderer()
    var t = ""
    try { t = String(r.toString()) } catch (e) {}
    var cls = String(r.getClass().getName()).replace(/^.*\./, "")
    // presentation-based renderers
    try { if (r.getPresentation) t = String(r.getPresentation().toString()) } catch (e) {}
    try { if (r.getText) t = String(r.getText()) } catch (e) {}
    try { if (r.getModel) t = String(r.getModel().toString()) } catch (e) {}
    if (t.indexOf("@") >= 0 && t.indexOf(cls) >= 0) t = ""
    return cls + (t ? " «" + t.replace(/\s+/g, " ").substring(0, 160) + "»" : "")
}
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var doc = editor.getDocument()
    var text = String(doc.getText())
    function pos(o) { var l = doc.getLineNumber(o); return (l + 1) + ":" + (o - doc.getLineStartOffset(l) + 1) }
    var models = [["document", DocumentMarkupModel.forDocument(doc, project, true)], ["editor", editor.getMarkupModel()]]
    var keyCount = {}
    for (var m = 0; m < models.length; m++) {
        var hs = models[m][1].getAllHighlighters()
        out.append("##### " + models[m][0] + " markup: " + hs.length + " highlighters\n")
        var rows = []
        for (var i = 0; i < hs.length; i++) {
            var h = hs[i]
            var key = h.getTextAttributesKey()
            var keyName = key == null ? "" : String(key.getExternalName())
            var s = h.getStartOffset(), e = h.getEndOffset()
            var frag = text.substring(s, Math.min(e, s + 50)).replace(/\n/g, "⏎")
            var tip = ""
            try { var et = h.getErrorStripeTooltip(); if (et != null) tip = oneLine(et.getDescription ? et.getDescription() : et) } catch (x) { try { tip = oneLine(h.getErrorStripeTooltip()) } catch (y) {} }
            var gutter = ""
            var gr = h.getGutterIconRenderer()
            if (gr != null) { var gt = ""; try { gt = oneLine(gr.getTooltipText()) } catch (x) {} gutter = " GUTTER[" + String(gr.getClass().getName()).replace(/^.*\./, "") + (gt ? ": " + gt : "") + "]" }
            var stripe = h.getErrorStripeMarkColor(null) != null ? " STRIPE" : ""
            var layer = h.getLayer()
            if (keyName == "" && tip == "" && gutter == "" ) {
                var ta = h.getTextAttributes(null)
                if (ta == null) continue
                keyName = "<attrs fg=" + ta.getForegroundColor() + " effect=" + ta.getEffectType() + ">"
            }
            keyCount[keyName] = (keyCount[keyName] || 0) + 1
            rows.push({ s: s, line: pos(s) + "-" + pos(e) + "  [" + keyName + "] layer " + layer + "  «" + frag + "»" + (tip ? "  TIP: " + tip.substring(0, 200) : "") + gutter + stripe })
        }
        rows.sort(function (a, b) { return a.s - b.s })
        for (var i = 0; i < rows.length; i++) out.append("  " + rows[i].line + "\n")
    }
    out.append("##### keys: count\n")
    var ks = []
    for (var k in keyCount) ks.push(k)
    ks.sort()
    for (var i = 0; i < ks.length; i++) out.append("  " + ks[i] + ": " + keyCount[ks[i]] + "\n")
    var im = editor.getInlayModel()
    var inl = im.getInlineElementsInRange(0, doc.getTextLength())
    out.append("##### inline inlays: " + inl.size() + "\n")
    for (var i = 0; i < inl.size(); i++) out.append("  " + pos(inl.get(i).getOffset()) + "  " + inlayText(inl.get(i)) + "\n")
    var ale = im.getAfterLineEndElementsInRange(0, doc.getTextLength())
    out.append("##### after-line-end inlays: " + ale.size() + "\n")
    for (var i = 0; i < ale.size(); i++) out.append("  " + pos(ale.get(i).getOffset()) + "  " + inlayText(ale.get(i)) + "\n")
    var blk = im.getBlockElementsInRange(0, doc.getTextLength())
    out.append("##### block inlays: " + blk.size() + "\n")
    for (var i = 0; i < blk.size(); i++) out.append("  " + pos(blk.get(i).getOffset()) + (blk.get(i).isAbove ? (blk.get(i).isAbove() ? " above" : " below") : "") + "  " + inlayText(blk.get(i)) + "\n")
} }), ModalityState.any())
out.toString()
