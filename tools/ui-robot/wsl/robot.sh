#!/usr/bin/env bash
# robot.py against the WSL sandbox of start-ide.sh, run by the Python of WSL: the robot server listens on 127.0.0.1 inside WSL, and the
# localhost forwarding of WSL does not reach it from Windows (NAT mode). Same commands as robot.py; paths of `js` scripts relative to the repo.
#   wsl -d Ubuntu -- bash tools/ui-robot/wsl/robot.sh windows
export ROBOT_PORT="${ROBOT_PORT:-8596}" NO_PROXY=127.0.0.1 no_proxy=127.0.0.1 PYTHONIOENCODING=utf-8
exec python3 "$(cd "$(dirname "$0")/.." && pwd)/robot.py" "$@"
