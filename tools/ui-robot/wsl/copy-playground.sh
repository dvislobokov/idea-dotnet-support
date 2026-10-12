#!/usr/bin/env bash
# A copy of debug-playground for the WSL sandbox in ~/robot/playground (a Linux path: `dotnet` and the IDE are slow on /mnt/c), without
# bin / obj / .idea, the files of the user's work in progress taken from HEAD (they may not compile), restored with the SDK of WSL.
#   wsl -d Ubuntu -- bash tools/ui-robot/wsl/copy-playground.sh
set -euo pipefail
repo="$(cd "$(dirname "$0")/../../.." && pwd)"
target="$HOME/robot/playground"
rm -rf "$target"
mkdir -p "$target"
(cd "$repo/debug-playground" && tar cf - --exclude=.idea --exclude=bin --exclude=obj .) | tar xf - -C "$target"
# the user's in-progress edit breaks the build of Console
git -C "$repo" show HEAD:debug-playground/Console/Editor/CompletionRanking.cs > "$target/Console/Editor/CompletionRanking.cs"
export PATH="$HOME/.dotnet:$PATH" DOTNET_ROOT="$HOME/.dotnet" DOTNET_CLI_TELEMETRY_OPTOUT=1
(cd "$target" && dotnet restore DebugPlayground.sln 2>&1 | tail -2)
echo "playground in $target"
