#!/bin/sh
# Makes the fixtures of the index of assemblies (src/test/resources/index): the indexes and the documentation of System.Console,
# System.Runtime (its index only),
# System.Linq and System.Collections of the reference pack of .NET 10, and of this folder's IndexFixture.dll.
# From the root of the repository, in Git Bash:  sh tools/index-fixture/fixtures.sh
set -e
root=$(pwd)
work=$(mktemp -d)
dotnet build indexer -c Release -o "$work/indexer" -v q -nologo
dotnet build tools/index-fixture -c Release -o "$work/fixture" -v q -nologo
dotnet_root=$(dirname "$(command -v dotnet)")
pack=$(ls -d "$dotnet_root"/packs/Microsoft.NETCore.App.Ref/10.0.* | sort -V | tail -1)/ref/net10.0
target="$root/src/test/resources/index"
for assembly in "$pack/System.Console.dll" "$pack/System.Linq.dll" "$pack/System.Collections.dll" "$pack/System.Runtime.dll" "$work/fixture/IndexFixture.dll"; do
  name=$(basename "$assembly" .dll)
  line=$(dotnet "$work/indexer/AssemblyIndexer.dll" --out "$work/out" --force "$assembly" | head -1)
  mvid=$(echo "$line" | sed 's/.*"mvid":"\([0-9a-f]*\)".*/\1/')
  cp "$work/out/$mvid.dnix" "$target/$name.dnix"
  # System.Runtime (the special types, Task, Nullable, ValueTuple, IEnumerable<T> for the types of expressions) goes without its 900 KB of docs
  if [ -f "$work/out/$mvid.dnxd" ] && [ "$name" != "System.Runtime" ]; then cp "$work/out/$mvid.dnxd" "$target/$name.dnxd"; fi
  echo "$name: $mvid"
done
rm -rf "$work"
