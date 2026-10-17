#!/usr/bin/env bash
# A sandbox of GIGA IDE 2026.1 for the robot: its own config/system/plugins/log under ~/giga-sandbox, the plugin zip given and the
# robot server on 127.0.0.1:8584 (ROBOT_PORT=8584 for robot.py). Usage: bash tools/ui-robot/giga/start-giga.sh <plugin zip>, in the
# background; the window opens on the desktop, so the robot works through the API only (shots of components). The first start shows the
# tour of the Islands theme: close it with `robot.py clicktext "//div[@class='MyDialog']" Skip` before any js that waits for the EDT.
# Stop: the idea64.exe of GIGA IDE whose command line has debug-playground (Stop-Process).
set -euo pipefail
zip="$1"
base="/c/Users/dvislobokov/giga-sandbox"
win="C:/Users/dvislobokov/giga-sandbox"
ide="/c/Program Files/GIGA IDE/GIGA IDE 2026.1"
mkdir -p "$base/config" "$base/system" "$base/plugins" "$base/log"
# the plugins anew: ours from the zip, the robot server
for p in "$base/plugins/idea-dotnet-support" "$base/plugins/robot-server-plugin"; do [ -d "$p" ] && mv "$p" "$p.old.$(date +%s)"; done
unzip -q -o "$zip" -d "$base/plugins"
unzip -q -o /c/Users/dvislobokov/.gradle/caches/modules-2/files-2.1/com.intellij.remoterobot/robot-server-plugin/0.11.23/*/robot-server-plugin-0.11.23.zip -d "$base/plugins"
cp "$ide/bin/idea64.exe.vmoptions" "$base/idea64.vmoptions"
cat >> "$base/idea64.vmoptions" <<EOF
-Didea.config.path=$win/config
-Didea.system.path=$win/system
-Didea.plugins.path=$win/plugins
-Didea.log.path=$win/log
-Drobot-server.port=8584
-Djb.privacy.policy.text=<!--999.999-->
-Djb.consents.confirmation.enabled=false
-Dide.show.tips.on.startup.default.value=false
-Didea.trust.all.projects=true
-Didea.initially.ask.config=never
EOF
export IDEA_VM_OPTIONS="$win/idea64.vmoptions"
"$ide/bin/idea64.exe" "C:/Users/dvislobokov/idea-dotnet-support/debug-playground" > "$base/log/stdout.txt" 2>&1
