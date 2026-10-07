#!/usr/bin/env bash
# Fetches the MSBuild project files (*.csproj, *.sln, *.slnx, *.props, *.targets, *.config, global.json) of corpus repositories from GitHub:
# the corpus snapshots carry only *.cs, so without this `dotnet restore` has nothing to restore. The files go into an overlay directory
# <out>/<owner>__<repo>/... that the exporter copies over the sources (-Pml.projects=<out>); the corpus itself stays untouched.
# usage: fetch-projects.sh <out> <owner__repo>...   (a repository whose overlay exists is skipped)
set -uo pipefail
out=$1; shift
mkdir -p "$out"
for name in "$@"; do
  dst="$out/$name"
  [ -d "$dst" ] && continue
  full="${name/__//}"
  tgz=$(mktemp --suffix=.tgz)
  if ! curl -sfL --retry 3 --max-time 600 -o "$tgz" "https://codeload.github.com/$full/tar.gz/HEAD"; then rm -f "$tgz"; echo "error: $name: download failed" >&2; continue; fi
  mkdir -p "$dst"
  tar -xzf "$tgz" -C "$dst" --strip-components=1 --wildcards '*.csproj' '*.sln' '*.slnx' '*.props' '*.targets' '*.config' '*/global.json' '*/*.json' 2>/dev/null
  # only the project-level json files are wanted (global.json, nuget config): drop the rest
  find "$dst" -name '*.json' ! -name 'global.json' ! -name 'nuget.json' -delete
  find "$dst" -type d -empty -delete
  rm -f "$tgz"
  echo "$name: $(find "$dst" -name '*.csproj' | wc -l) csproj, $(find "$dst" -name '*.sln' -o -name '*.slnx' | wc -l) sln"
done
