#!/usr/bin/env bash
# The check of a release zip before it goes to dist/ and gets its tag: a development build of -PeditionChecks=false (the resource
# edition/unlocked in the plugin's jar, edition/DotNetEdition.kt) has the same name as a release build and must not be released.
# Usage: bash tools/release/check-zip.sh build/distributions/idea-dotnet-support-<version>.zip
set -euo pipefail
zip="${1:?the zip of the plugin}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$zip" '*/lib/idea-dotnet-support-*.jar' -d "$tmp"
jar="$(find "$tmp" -name 'idea-dotnet-support-*.jar' | head -1)"
[ -n "$jar" ] || { echo "FAIL: no plugin jar in $zip"; exit 1; }
# the listing first, then the search: `unzip | grep -q` under pipefail fails on the early exit of grep and would pass every zip
listing="$(unzip -l "$jar")"
if grep -q 'edition/unlocked' <<< "$listing"; then
    echo "FAIL: $zip is a development build (-PeditionChecks=false): rebuild it without the flag"
    exit 1
fi
echo "OK: $zip has the edition checks on"
