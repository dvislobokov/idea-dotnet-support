#!/usr/bin/env bash
# Refreshes ml-core/ from a checkout of the shared ML completion engine (https://github.com/dvislobokov/idea-ml-completion):
# only its pure-Kotlin ml-core module is part of this build. usage: tools/ml/sync-ml-core.sh [<checkout>]  (default ../idea-ml-completion)
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
src="${1:-$root/../idea-ml-completion}"
[ -d "$src/ml-core/src" ] || { echo "no ml-core in $src" >&2; exit 1; }
rm -rf "$root/ml-core/src"
cp -r "$src/ml-core/src" "$root/ml-core/src"
cp "$src/ml-core/build.gradle.kts" "$root/ml-core/build.gradle.kts"
rev="$(git -C "$src" rev-parse --short HEAD 2>/dev/null || echo unknown)"
echo "$rev" > "$root/ml-core/ENGINE-REVISION"
echo "ml-core synced from $src at $rev"
