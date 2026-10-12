// The times open_project.js has recorded, `key=ms` from t0; `finished` when done, `error=` on failure; plus the heap and the IDE's pid.
const rec = java.lang.System.getProperties().get("dotnet.compare")
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
    if (rec.get("_finished") != null) text += "finished\n"
    if (rec.get("_error") != null) text += "error=" + rec.get("_error") + "\n"
}
const rt = java.lang.Runtime.getRuntime()
text += "heapUsedMb=" + Math.round((rt.totalMemory() - rt.freeMemory()) / 1048576) + "\n"
text += "pid=" + java.lang.ProcessHandle.current().pid() + "\n"
text
