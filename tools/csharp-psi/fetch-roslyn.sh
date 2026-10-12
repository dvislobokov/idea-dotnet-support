#!/usr/bin/env bash
# Fetches the parts of dotnet/roslyn that csharp-psi ports and uses as a corpus, at roslynCommit from gradle.properties,
# into .corpus/roslyn (not committed): Syntax.xml and SyntaxKind (step 3), the parser (step 5), the parsing and lexer
# tests (corpus, step 6). A blobless sparse clone in cone mode: only these directories (and top-level files such as
# License.txt) are downloaded. Cone patterns are relative: a leading "/" would be rewritten by MSYS into a Windows path.
set -eu
cd "$(dirname "$0")/../.."
commit=$(sed -n 's/^roslynCommit=//p' gradle.properties)
dir=.corpus/roslyn
if [ ! -d "$dir/.git" ]; then
    git clone --quiet --filter=blob:none --no-checkout --sparse https://github.com/dotnet/roslyn.git "$dir"
fi
git -C "$dir" sparse-checkout set --cone \
    src/Compilers/CSharp/Portable/Syntax \
    src/Compilers/CSharp/Portable/Parser \
    src/Compilers/CSharp/Test/Syntax/Parsing \
    src/Compilers/CSharp/Test/Syntax/LexicalAndXml
git -C "$dir" fetch --quiet --depth 1 origin "$commit"
git -C "$dir" -c advice.detachedHead=false checkout --quiet "$commit"
echo "roslyn at $(git -C "$dir" rev-parse --short HEAD): $(find "$dir/src" -name '*.cs' | wc -l) .cs files"
