#!/usr/bin/env bash
# Stops the WSL sandbox IDE of start-ide.sh (the one of its pid file) and its Xvfb screen [display, 99].
display="${1:-99}"
root="$HOME/robot/sandbox"
if [ -f "$root/ide.pid" ]; then
    pid="$(cat "$root/ide.pid")"
    kill "$pid" 2>/dev/null && for _ in $(seq 1 100); do kill -0 "$pid" 2>/dev/null || break; sleep 0.2; done
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$root/ide.pid"
fi
pkill -f "Xvfb :$display " 2>/dev/null || true
echo "stopped"
