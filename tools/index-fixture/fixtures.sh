#!/bin/sh
# Makes the fixtures of the index of assemblies (src/test/resources/index): the indexes and the documentation of System.Console,
# System.Runtime (its index only),
# System.Linq, System.Collections and System.ObjectModel (the compiler-messages corpus, no docs) of the reference pack of .NET 10, of this folder's IndexFixture.dll and of access/AccessFixture.dll
# (the errors of access: internal members, accessors, InternalsVisibleTo) and of components/ (a stand-in of Microsoft.AspNetCore.Components:
# the components and tag helpers of Razor, RazorTagHelpersTest) and of mvc/ (a stand-in of the tag helpers of ASP.NET Core MVC,
# RazorCshtmlTagHelpersTest).
# From the root of the repository, in Git Bash:  sh tools/index-fixture/fixtures.sh
set -e
root=$(pwd)
work=$(mktemp -d)
dotnet build indexer -c Release -o "$work/indexer" -v q -nologo
dotnet build tools/index-fixture -c Release -o "$work/fixture" -v q -nologo
dotnet build tools/index-fixture/access -c Release -o "$work/access" -v q -nologo
dotnet build tools/index-fixture/components -c Release -o "$work/components" -v q -nologo
dotnet build tools/index-fixture/mvc -c Release -o "$work/mvc" -v q -nologo
dotnet_root=$(dirname "$(command -v dotnet)")
pack=$(ls -d "$dotnet_root"/packs/Microsoft.NETCore.App.Ref/10.0.* | sort -V | tail -1)/ref/net10.0
target="$root/src/test/resources/index"
for assembly in "$pack/System.Console.dll" "$pack/System.Linq.dll" "$pack/System.Collections.dll" "$pack/System.Runtime.dll" "$pack/System.ObjectModel.dll" "$work/fixture/IndexFixture.dll" "$work/access/AccessFixture.dll" "$work/components/Microsoft.AspNetCore.Components.dll" "$work/mvc/Microsoft.AspNetCore.Mvc.TagHelpers.dll"; do
  name=$(basename "$assembly" .dll)
  # the stand-in of ASP.NET Core's components (RazorTagHelpersTest) under a name of its own
  [ "$name" = "Microsoft.AspNetCore.Components" ] && name=AspNetCoreComponents
  [ "$name" = "Microsoft.AspNetCore.Mvc.TagHelpers" ] && name=AspNetCoreMvc
  line=$(dotnet "$work/indexer/AssemblyIndexer.dll" --out "$work/out" --force "$assembly" | head -1)
  mvid=$(echo "$line" | sed 's/.*"mvid":"\([0-9a-f]*\)".*/\1/')
  cp "$work/out/$mvid.dnix" "$target/$name.dnix"
  # System.Runtime (the special types, Task, Nullable, ValueTuple, IEnumerable<T> for the types of expressions) goes without its 900 KB of docs
  if [ -f "$work/out/$mvid.dnxd" ] && [ "$name" != "System.Runtime" ] && [ "$name" != "System.ObjectModel" ]; then cp "$work/out/$mvid.dnxd" "$target/$name.dnxd"; fi
  echo "$name: $mvid"
done
rm -rf "$work"
