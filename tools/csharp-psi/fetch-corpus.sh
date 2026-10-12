#!/usr/bin/env bash
# Fetches the C# corpus beyond Roslyn's own sources at the tags pinned in gradle.properties (runtimeTag,
# aspnetcoreTag) into .corpus/runtime and .corpus/aspnetcore (not committed). Like tools/csharp-psi/fetch-roslyn.sh: blobless
# sparse clones in cone mode, depth 1 of the tag, idempotent. Cone patterns are relative: a leading "/" would be
# rewritten by MSYS into a Windows path.
set -eu
cd "$(dirname "$0")/../.."

# fetch <name> <repo url> <tag property> <cone dir>...
fetch() {
    name=$1 url=$2 prop=$3
    shift 3
    tag=$(sed -n "s/^$prop=//p" gradle.properties)
    dir=.corpus/$name
    if [ ! -d "$dir/.git" ]; then
        git clone --quiet --filter=blob:none --no-checkout --sparse "$url" "$dir"
    fi
    git -C "$dir" sparse-checkout set --cone "$@"
    git -C "$dir" fetch --quiet --depth 1 origin "refs/tags/$tag:refs/tags/$tag"
    git -C "$dir" -c advice.detachedHead=false checkout --quiet "refs/tags/$tag"
    echo "$name at $tag ($(git -C "$dir" rev-parse --short HEAD)): $(find "$dir/src" -name '*.cs' | wc -l) .cs files"
}

# runtime: the class libraries with their tests, and CoreLib.
fetch runtime https://github.com/dotnet/runtime.git runtimeTag \
    src/libraries \
    src/coreclr/System.Private.CoreLib
# aspnetcore: everything under src (frameworks, servers, tests).
fetch aspnetcore https://github.com/dotnet/aspnetcore.git aspnetcoreTag \
    src
