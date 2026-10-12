// When the colors of identifiers appear after a C# file is opened (0.1.84). Opens __FILE__ (forward slashes) in the last open project — or,
// with __DIR__ not empty, opens the project __DIR__ first and the file as soon as the project is initialized (a cold open) — and records in a
// thread of the IDE, every few ms, how many highlighters with an identifier color of the palette (CSHARP_*_IDENTIFIER and the coarse
// TYPE / METHOD / MEMBER) are on screen: `daemon` — in the document's markup (the annotators), `zombies` — those of them the platform restored
// from its markup cache, `opening` — the layer CSharpOpeningColors paints as the editor opens (the editor's own markup); and pictures of the
// editor component (__SHOTS__ = a folder, empty = none) at the first EDT tick after the open, at the first identifier color and at the end.
// __CLOSE__ = yes closes the file first. Returns at once; color_timing_status.js prints the recording (times in ms from the open).
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.project.DumbService)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.editor.impl.DocumentMarkupModel)
importClass(com.intellij.ide.impl.ProjectUtil)
var dir = "__DIR__"
var path = "__FILE__"
var shots = "__SHOTS__"
var timeoutMs = 60000
var now = function () { return java.lang.System.nanoTime() / 1e6 }
var lines = new java.util.concurrent.CopyOnWriteArrayList()
var rec = new java.util.concurrent.ConcurrentHashMap()
rec.put("lines", lines)
java.lang.System.getProperties().put("dotnet.colorTiming", rec)
var identifierKeys = /^CSHARP_(.*_IDENTIFIER|TYPE|METHOD|MEMBER)$/

var findProject = function () {
    var open = ProjectManager.getInstance().getOpenProjects()
    if (dir == "") return open.length > 0 ? open[open.length - 1] : null
    for (var p = 0; p < open.length; p++) if (open[p].getBasePath() != null && String(open[p].getBasePath()).toLowerCase() == dir.toLowerCase()) return open[p]
    return null
}
var edt = function (f) {
    var box = new java.util.concurrent.atomic.AtomicReference(null)
    ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () { box.set(f()) } }), com.intellij.openapi.application.ModalityState.any())
    return box.get()
}
var shoot = function (editor, name) {
    if (shots == "" || editor == null) return
    edt(function () {
        var c = editor.getComponent()
        if (c.getWidth() <= 0 || c.getHeight() <= 0) return null
        var img = new java.awt.image.BufferedImage(c.getWidth(), c.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB)
        var g = img.createGraphics()
        c.paint(g)
        g.dispose()
        javax.imageio.ImageIO.write(img, "png", new java.io.File(shots + "/" + name + ".png"))
        return null
    })
}
var t0 = now()
rec.put("t0", java.lang.Double.valueOf(t0))
if (dir != "") ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { ProjectUtil.openOrImport(dir, null, true) } }))

new java.lang.Thread(new java.lang.Runnable({ run: function () {
    try {
        var project = null
        while (project == null || !project.isInitialized()) {
            if (now() - t0 > timeoutMs) { lines.add("ERROR the project did not open"); return }
            java.lang.Thread.sleep(10)
            project = findProject()
        }
        var file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
        if (file == null) { lines.add("ERROR no file " + path); return }
        if ("__CLOSE__" == "yes") {
            edt(function () { FileEditorManager.getInstance(project).closeFile(file); return null })
            java.lang.Thread.sleep(1500)
        }
        var openAt = now()
        rec.put("open", java.lang.Double.valueOf(openAt))
        lines.add("projectReady " + Math.round(openAt - t0) + " ms after start; dumb=" + DumbService.isDumb(project))
        var editor = edt(function () { return FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0, 0), true) })
        lines.add(Math.round(now() - openAt) + " opened (openTextEditor returned)")
        var document = editor.getDocument()
        edt(function () { return null })
        lines.add(Math.round(now() - openAt) + " first EDT tick after open")
        shoot(editor, "1-first-tick")
        var last = -1, lastZombies = -1, lastOwn = -1, firstColor = -1, lastChange = now(), finishedSeen = false
        while (now() - openAt < timeoutMs) {
            var counts = edt(function () {
                var model = DocumentMarkupModel.forDocument(document, project, false)
                var n = 0, z = 0
                if (model != null) {
                    var hs = model.getAllHighlighters()
                    for (var h = 0; h < hs.length; h++) {
                        var key = hs[h].getTextAttributesKey()
                        if (key == null) continue
                        var name = String(key.getExternalName())
                        if (!identifierKeys.test(name)) continue
                        n++
                        if (com.intellij.codeInsight.daemon.impl.HighlightingNecromancer.isZombieMarkup(hs[h])) z++
                    }
                }
                // the colors painted as the editor opens (CSharpOpeningColors, 0.1.84) live in the editor's own markup
                var e = 0
                var own = editor.getMarkupModel().getAllHighlighters()
                for (var o = 0; o < own.length; o++) {
                    var ownKey = own[o].getTextAttributesKey()
                    if (ownKey != null && identifierKeys.test(String(ownKey.getExternalName()))) e++
                }
                return [n, z, e]
            })
            var at = Math.round(now() - openAt)
            if (counts[0] != last || counts[1] != lastZombies || counts[2] != lastOwn) {
                lines.add(at + " identifier colors: daemon=" + counts[0] + " zombies=" + counts[1] + " opening=" + counts[2] + " dumb=" + DumbService.isDumb(project))
                if (counts[0] + counts[2] > 0 && firstColor < 0) { firstColor = at; shoot(editor, "2-first-color") }
                last = counts[0]; lastZombies = counts[1]; lastOwn = counts[2]; lastChange = now()
            }
            var psi = com.intellij.openapi.application.ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: function () { return com.intellij.psi.PsiManager.getInstance(project).findFile(file) } }))
            var finished = com.intellij.openapi.application.ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: function () { return com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx.getInstanceEx(project).isErrorAnalyzingFinished(psi) } }))
            if (finished && !finishedSeen) { lines.add(at + " error analysis marked finished"); finishedSeen = true }
            if (finishedSeen && last + lastOwn > 0 && now() - lastChange > 3000) break
            java.lang.Thread.sleep(5)
        }
        shoot(editor, "3-final")
        lines.add("done")
    } catch (e) {
        lines.add("ERROR " + e)
    }
} })).start()
"started"
