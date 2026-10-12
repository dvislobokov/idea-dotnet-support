#!/usr/bin/env bash
# Real input and pictures on the invisible screen of start-ide.sh (nothing reaches the user's desktop):
#   screen.sh shot <out.png> [display]                 the whole virtual screen (it holds only the sandbox IDE)
#   screen.sh move <x> <y> [display]                   the mouse pointer, screen coordinates (hover: move, wait, shot)
#   screen.sh click <x> <y> [display]
#   screen.sh key <keys> [display]                     xdotool names: ctrl+space, alt+Return, shift+F6, Escape
#   screen.sh type <text> [display]                    typed character by character, 30 ms apart, as a person types
#   screen.sh record <out.mp4> <seconds> [display]     a video of the screen
# Windows paths for <out> are converted (C:\... → /mnt/c/...).
set -euo pipefail
command="${1:?command}"
shift
out() { case "$1" in [A-Za-z]:*) wslpath -u "$1" ;; *) echo "$1" ;; esac; }
case "$command" in
    shot) export DISPLAY=":${2:-99}"; ffmpeg -loglevel error -y -f x11grab -video_size 1920x1080 -i "$DISPLAY" -frames:v 1 "$(out "$1")" ;;
    move) DISPLAY=":${3:-99}" xdotool mousemove "$1" "$2" ;;
    click) DISPLAY=":${3:-99}" xdotool mousemove "$1" "$2" click 1 ;;
    key) DISPLAY=":${2:-99}" xdotool key --delay 50 "$1" ;;
    type) DISPLAY=":${2:-99}" xdotool type --delay 30 -- "$1" ;;
    record) export DISPLAY=":${3:-99}"; ffmpeg -loglevel error -y -f x11grab -framerate 15 -video_size 1920x1080 -i "$DISPLAY" -t "$2" -pix_fmt yuv420p "$(out "$1")" ;;
    *) echo "unknown command $command" >&2; exit 1 ;;
esac
