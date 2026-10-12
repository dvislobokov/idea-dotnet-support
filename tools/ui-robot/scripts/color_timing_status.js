// The recording of color_timing.js so far: one line per event, the last line is `done` when it has ended.
var rec = java.lang.System.getProperties().get("dotnet.colorTiming")
var out = new java.lang.StringBuilder()
if (rec == null) out.append("no recording\n")
else {
    var it = rec.get("lines").iterator()
    while (it.hasNext()) out.append("at " + it.next() + "\n")
}
out.toString()
