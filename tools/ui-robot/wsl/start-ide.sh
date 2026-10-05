#!/usr/bin/env bash
# A sandbox IDE of the plugin inside WSL, on an invisible X screen (Xvfb), with the Remote Robot server: unlike the sandbox of
# `runIdeForUiTests` on the user's desktop, real mouse and keyboard input (xdotool), hovers and video (ffmpeg) disturb nobody.
#
#   tools/ui-robot/wsl/start-ide.sh <plugin.zip (Windows or WSL path)> [robot port, 8596] [display, 99]
#
# Run from Windows: `wsl -d Ubuntu -- bash tools/ui-robot/wsl/start-ide.sh build/distributions/idea-dotnet-support-0.1.63.zip`
# (from the repo root; the repo is reached as /mnt/c/...). The robot answers on 127.0.0.1:<port> on both sides (WSL forwards
# localhost): `ROBOT_PORT=8596 python tools/ui-robot/robot.py ...` works as for the Windows sandbox. Stop with stop-ide.sh.
# Needs (README): IDEA for Linux in ~/ide (the version of `localIdePath`), .NET SDK in ~/.dotnet, apt packages xvfb xdotool ffmpeg.
set -euo pipefail

zip="${1:?plugin zip}"
port="${2:-8596}"
display="${3:-99}"
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
case "$zip" in
    [A-Za-z]:*) zip="$(wslpath -u "$zip")" ;;
    /*) ;;
    *) zip="$repo/$zip" ;;
esac

ide="$(ls -d "$HOME"/ide/idea-IU-* | sort | tail -1)"
root="$HOME/robot/sandbox"
mkdir -p "$root"/{config,system,plugins,log}

# the plugin as built, and the robot server as Gradle downloaded it for runIdeForUiTests on Windows
rm -rf "$root/plugins/idea-dotnet-support"
python3 -m zipfile -e "$zip" "$root/plugins"   # no unzip in a bare Ubuntu
robot_plugin="$repo/.intellijPlatform/sandbox/idea-dotnet-support/IU-2026.1.4/plugins_runIdeForUiTests/robot-server-plugin"
[ -d "$robot_plugin" ] || { echo "no $robot_plugin: run ./gradlew.bat runIdeForUiTests once on Windows" >&2; exit 1; }
rm -rf "$root/plugins/robot-server-plugin"
cp -r "$robot_plugin" "$root/plugins/"

cat > "$root/idea.properties" <<EOF
idea.config.path=$root/config
idea.system.path=$root/system
idea.plugins.path=$root/plugins
idea.log.path=$root/log
EOF
{
    cat "$ide/bin/idea64.vmoptions"
    echo "-Xmx3g"
    # X11 on the Xvfb screen: with WSLg the IDE would pick Wayland (WLToolkit) and open on the user's desktop
    echo "-Dawt.toolkit.name=XToolkit"
    echo "-Drobot-server.port=$port"
    echo "-Djb.privacy.policy.text=<!--999.999-->"
    echo "-Djb.consents.confirmation.enabled=false"
    echo "-Dide.show.tips.on.startup.default.value=false"
    echo "-Didea.trust.all.projects=true"
    echo "-Dide.mac.message.dialogs.as.sheets=false"
} > "$root/idea.vmoptions"

if ! xdpyinfo -display ":$display" >/dev/null 2>&1; then
    Xvfb ":$display" -screen 0 1920x1080x24 -nolisten tcp > "$root/log/xvfb.log" 2>&1 &
    for _ in $(seq 1 50); do xdpyinfo -display ":$display" >/dev/null 2>&1 && break; sleep 0.1; done
fi

export DISPLAY=":$display"
unset WAYLAND_DISPLAY
export IDEA_PROPERTIES="$root/idea.properties"
export IDEA_VM_OPTIONS="$root/idea.vmoptions"
export DOTNET_ROOT="$HOME/.dotnet"
export PATH="$HOME/.dotnet:$HOME/.dotnet/tools:$PATH"
export DOTNET_CLI_TELEMETRY_OPTOUT=1
nohup "$ide/bin/idea" > "$root/log/ide-stdout.log" 2>&1 &
echo $! > "$root/ide.pid"
echo "IDE $(cat "$ide/build.txt") pid $(cat "$root/ide.pid") on :$display, robot on 127.0.0.1:$port; logs in $root/log"
