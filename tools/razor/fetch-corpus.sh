#!/usr/bin/env bash
# Fetches the Razor / Blazor corpus at pinned commits into .corpus/razor/<name> (not committed). Like tools/csharp-psi/fetch-corpus.sh:
# blobless clones, depth 1 of the commit, idempotent; a cone list makes the checkout sparse. Cone patterns are relative: a leading "/"
# would be rewritten by MSYS into a Windows path. Restore of the solutions is separate: see tools/razor/README.md.
set -eu
cd "$(dirname "$0")/../.."

# fetch <name> <repo url> <commit> [cone dir]...
fetch() {
    name=$1 url=$2 commit=$3
    shift 3
    dir=.corpus/razor/$name
    if [ ! -d "$dir/.git" ]; then
        git clone --quiet --filter=blob:none --no-checkout "$url" "$dir"
    fi
    if [ $# -gt 0 ]; then git -C "$dir" sparse-checkout set --cone "$@"; else git -C "$dir" sparse-checkout disable; fi
    git -C "$dir" fetch --quiet --depth 1 origin "$commit"
    git -C "$dir" -c advice.detachedHead=false checkout --quiet "$commit"
    echo "$name at ${commit:0:10}: $(find "$dir" -name '*.razor' | wc -l) .razor, $(find "$dir" -name '*.cshtml' | wc -l) .cshtml, $(find "$dir" -name '*.cs' | wc -l) .cs"
}

# Blazor: a component library (Microsoft.NET.Sdk.Razor; generic components, cascading values, RenderFragment, bUnit tests, docs site).
fetch mudblazor https://github.com/MudBlazor/MudBlazor.git 326bf8c8dcd9f1765d4301e468fcad36d398a16c
# Razor: an MVC application (Microsoft.NET.Sdk.Web; @model, tag helpers, areas, layouts, sections, view components, plugins).
fetch nopcommerce https://github.com/nopSolutions/nopCommerce.git cce4b936b335551926eb1433a622231dcfc3e2b4
# A modern Blazor Web App (static SSR + interactive server, forms, Aspire) of a middle size: the first one to open in the IDE.
fetch eshop https://github.com/dotnet/eShop.git dc7ea499cd356924fb6689b3702964a5869dbae9
# The oracle: the Razor compiler moved into dotnet/roslyn (dotnet/razor is archived); its integration tests are inputs with the
# generated C# (*.codegen.cs), the IR, the source mappings and the diagnostics as baselines.
fetch razor-compiler https://github.com/dotnet/roslyn.git 036431aef582b93345a8f54b7a452926d2abd459 \
    src/Razor/src/Compiler
