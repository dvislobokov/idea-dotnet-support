// What baseline_start.js has recorded so far: one `key=milliseconds` per line, from the opening of the project (t0), and `daemon=` the
// passes of the daemon on the measured file, from the opening of the file. `finished` once the recording is over; `error=` if it failed.
const rec = java.lang.System.getProperties().get("dotnet.baseline")
let text = ""
if (rec == null) text = "error=nothing recorded\n"
else {
    const t0 = rec.get("t0")
    const keys = new java.util.TreeSet(rec.keySet()).toArray()
    for (var k = 0; k < keys.length; k++) {
        var key = String(keys[k])
        if (key.charAt(0) == "_") continue
        text += key + "=" + Math.round(rec.get(key) - t0) + "\n"
    }
    const fileStart = rec.get("fileOpenStart")
    if (fileStart != null) {
        const daemon = rec.get("_daemon").toArray()
        var passes = []
        for (var d = 0; d < daemon.length; d++) passes.push(Math.round(daemon[d] - fileStart))
        text += "daemon=" + passes.join(",") + "\n"
        // a Java list: its toString is "[a, b]"
        text += "colors=" + String(rec.get("_colors")).replace(/[\[\] ]/g, "") + "\n"
        text += "problems=" + String(rec.get("_problems")).replace(/[\[\] ]/g, "") + "\n"
    }
    if (rec.get("_finished") != null) text += "finished\n"
    if (rec.get("_error") != null) text += "error=" + rec.get("_error") + "\n"
}
text
