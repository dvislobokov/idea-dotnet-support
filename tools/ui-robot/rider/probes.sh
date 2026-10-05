#!/usr/bin/env bash
# The completion probes of docs/RIDER_REFERENCE.md against the IDE on ROBOT_PORT (Rider from start-rider.ps1, or the plugin's sandbox):
#   ROBOT_PORT=8594 bash tools/ui-robot/rider/probes.sh > out.txt
# PLAYGROUND — the copy of debug-playground the IDE has open; RiderProbe.cs is copied into it (line numbers below are of that file).
set -u
cd "$(dirname "$0")/../../.."
export NO_PROXY=127.0.0.1 PYTHONIOENCODING=utf-8
PLAYGROUND=${PLAYGROUND:-build/ui-robot/rider-playground}
cp tools/ui-robot/rider/RiderProbe.cs "$PLAYGROUND/Console/Editor/RiderProbe.cs"
F="$(cd "$PLAYGROUND" && pwd -W)/Console/Editor/RiderProbe.cs"
. tools/ui-robot/scripts/session.sh
sed "s|__FILE__|$F|g" tools/ui-robot/scripts/reload_file.js > /tmp/reload_probe.js
probe() {
    echo "=== [$3] line $1: [$2]"
    robot_js rider_complete.js "s|__FILE__|$F|g" "s|__LINE__|$1|g" "s|__TYPE__|$2|g" "s|__LIMIT__|10|g" "s|__KIND__|$3|g" "s|__SYNC__|1500|g" "s|__WAIT__|6000|g"
    # the IDE may have saved a half-typed line: back to the probe file as it is on disk
    cp tools/ui-robot/rider/RiderProbe.cs "$PLAYGROUND/Console/Editor/RiderProbe.cs"; $ROBOT js /tmp/reload_probe.js > /dev/null 2>&1
}
probe 10 "        return " SMART
probe 10 "        return " BASIC
probe 15 "        return " SMART
probe 10 "        return Task.Fr" BASIC
probe 10 "        return Task.FromResult(" SMART
probe 20 "        var s = await client.GetStringAsync(nameof(client), " BASIC
probe 20 "        var s = await client.GetStringAsync(nameof(client), " SMART
probe 20 "        await Task.Delay(100, " SMART
probe 26 "        await stream.ReadAsync(new byte[1], " SMART
probe 32 "        var list = new List<str" BASIC
probe 32 "        WriteLi" BASIC
probe 32 "        ArgumentNullException." BASIC
probe 32 "        if (string." BASIC
probe 37 "        await Task." BASIC
probe 37 "        var t = Task.Run(() => 1).Configure" BASIC
probe 32 "        foreach (var item in " SMART
probe 32 "        name." BASIC
