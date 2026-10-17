#!/usr/bin/env bash
# A copy of the user's Unity project (nine-to-fire) for the WSL sandbox: Assets, Packages, ProjectSettings, the generated solution and
# projects, Library/ScriptAssemblies and Library/PackageCache (the package sources and dlls the generated projects point at); the Windows
# paths of the generated projects rewritten to the copy and to the editor on /mnt/c — what Unity on Linux would generate.
set -euo pipefail
src="${1:-/mnt/c/Users/dvislobokov/nine-to-fire}"
target="$HOME/robot/unity"
rm -rf "$target"
mkdir -p "$target/Library"
cp -r "$src/Assets" "$src/Packages" "$src/ProjectSettings" "$target/"
cp "$src"/*.csproj "$src/nine-to-fire.sln" "$target/"
cp -r "$src/Library/ScriptAssemblies" "$target/Library/"
cp -r "$src/Library/PackageCache" "$target/Library/"
python3 - "$target" <<'PY'
import sys, glob, re, io
target = sys.argv[1]
for p in glob.glob(target + '/*.csproj') + glob.glob(target + '/*.sln'):
    s = io.open(p, encoding='utf-8-sig').read()
    s = s.replace('C:\\Users\\dvislobokov\\nine-to-fire', target)
    s = s.replace('C:\\Program Files\\Unity\\Hub\\Editor\\6000.6.0f1\\Editor\\Data', '/mnt/c/Program Files/Unity/Hub/Editor/6000.6.0f1/Editor/Data')
    s = re.sub(r'(<(?:HintPath|Compile Include=")[^<"]*)', lambda m: m.group(1).replace('\\', '/'), s)
    s = re.sub(r'(<Compile Include=")([^"]*)', lambda m: m.group(1) + m.group(2).replace('\\', '/'), s)
    s = re.sub(r'(<None Include=")([^"]*)', lambda m: m.group(1) + m.group(2).replace('\\', '/'), s)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('rewritten')
PY
grep -c "/mnt/c/Program Files/Unity" "$target/Game.Core.csproj"
grep -m1 "<Compile" "$target/Game.Core.csproj"
du -sh "$target"
