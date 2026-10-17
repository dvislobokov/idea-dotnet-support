// The threads whose stack mentions __NEEDLE__: name, state and the top frames.
var traces = java.lang.Thread.getAllStackTraces()
var it = traces.entrySet().iterator()
var text = "threads: " + traces.size() + "\n"
while (it.hasNext()) {
    var e = it.next()
    var frames = e.getValue()
    var hit = false
    for (var i = 0; i < frames.length; i++) if (String(frames[i].toString()).indexOf("__NEEDLE__") >= 0) { hit = true; break }
    if (!hit) continue
    text += "== " + e.getKey().getName() + " [" + e.getKey().getState() + "]\n"
    for (var j = 0; j < frames.length && j < 18; j++) text += "   " + frames[j] + "\n"
}
text
